package exposed.thunder.thunderLBs.render.packet;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.leaderboard.Leaderboard;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.render.DisplayOptions;
import exposed.thunder.thunderLBs.render.RenderBackend;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class PacketRenderBackend implements RenderBackend, Listener {
    private static final long VIEWER_REFRESH_MILLIS = 1000L;
    private static final long JOIN_SYNC_DELAY_TICKS = 1L;

    private final ThunderLBs plugin;
    private final RegionTaskScheduler scheduler;
    private final ViewerPositionTracker positions;
    private final ScheduledExecutorService sweepExecutor;
    private final Map<Leaderboard, Channel> channels = new ConcurrentHashMap<>();

    public PacketRenderBackend(ThunderLBs plugin) {
        this.plugin = plugin;
        this.scheduler = RegionTaskScheduler.create(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.positions = new ViewerPositionTracker(plugin);
        this.positions.start();
        // Range checks are pure arithmetic over packet-sourced positions, and every packet this
        // backend sends is thread-safe, so the whole sweep runs off the main thread.
        this.sweepExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ThunderLBs-viewer-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.sweepExecutor.scheduleAtFixedRate(this::sweepViewers,
                VIEWER_REFRESH_MILLIS, VIEWER_REFRESH_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public String name() {
        return "packet";
    }

    @Override
    public void register(Leaderboard board) {
        Channel channel = new Channel(board);
        channels.put(board, channel);
        sweepExecutor.execute(this::sweepViewers);
    }

    @Override
    public void unregister(Leaderboard board) {
        Channel channel = channels.remove(board);
        if (channel != null) {
            channel.destroyAll();
        }
    }

    @Override
    public BoardDisplay spawn(Leaderboard board, Location location, DisplayOptions options) {
        Channel channel = options.viewer() == null ? channels.get(board) : null;
        PacketBoardDisplay display = new PacketBoardDisplay(
                this,
                SpigotReflectionUtil.generateEntityId(),
                UUID.randomUUID(),
                channel,
                location,
                options);
        if (options.viewer() != null) {
            display.sendFullSpawn(options.viewer());
        } else if (channel != null) {
            channel.displays.add(display);
            for (Player player : channel.viewerPlayers()) {
                display.sendFullSpawn(player);
            }
        }
        return display;
    }

    boolean sharedPacketSerialization() {
        return plugin.getPluginConfig().performance().sharedPacketSerialization();
    }

    void logSharedSerializationUnavailable(LinkageError error) {
        plugin.getLogger().warning("shared-packet-serialization is not compatible with the installed "
                + "packetevents version; falling back to per-player packet sends: " + error);
    }

    void forget(PacketBoardDisplay display) {
        Channel channel = display.channel();
        if (channel != null) {
            channel.displays.remove(display);
        }
    }

    @Override
    public void shutdown() {
        sweepExecutor.shutdownNow();
        try {
            // Let an in-flight sweep finish before tearing the channels down, so it cannot send
            // spawn packets for displays that are about to be destroyed.
            if (!sweepExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                plugin.getLogger().warning("Viewer sweep did not stop in time; continuing shutdown.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        positions.stop();
        for (Channel channel : channels.values()) {
            channel.destroyAll();
        }
        channels.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (Channel channel : channels.values()) {
            channel.viewers.remove(uuid);
            channel.board.removeViewer(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        syncSoon(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (Channel channel : channels.values()) {
            channel.viewers.remove(uuid);
            channel.board.removeViewer(uuid);
        }
        syncSoon(event.getPlayer());
    }

    /**
     * Syncs one player a tick from now. The delay lets the connection settle before the first spawn
     * packets go out; re-seeding from Bukkit on the way through means this does not depend on the
     * tracker's own listener having already handled the same event.
     */
    private void syncSoon(Player player) {
        scheduler.executeDelayed(player, () -> {
            ViewerPositionTracker.Position position = positions.refresh(player);
            if (position != null) {
                synchronizeViewer(player, position);
            }
        }, null, JOIN_SYNC_DELAY_TICKS);
    }

    private void sweepViewers() {
        if (channels.isEmpty()) {
            return;
        }
        try {
            positions.forEachViewer((player, position) -> {
                try {
                    synchronizeViewer(player, position);
                } catch (RuntimeException exception) {
                    // Isolate per viewer: one half-closed connection must not starve the sync of
                    // every player ordered after it.
                    plugin.getLogger().log(Level.WARNING,
                            "Leaderboard viewer sync failed for " + player.getName(), exception);
                }
            });
            purgeOfflineViewers();
        } catch (RuntimeException exception) {
            // A throw here would silently kill the repeating task, so it stops at the sweep.
            plugin.getLogger().log(Level.WARNING, "Leaderboard viewer sweep failed", exception);
        }
    }

    /**
     * Drops viewers who are no longer online. A quit racing an in-flight sweep can re-add a player
     * right after the quit listener removed them, and the position tracker never yields them again
     * — the sweep itself is the authority, so a ghost entry lives for at most one sweep period.
     */
    private void purgeOfflineViewers() {
        for (Channel channel : channels.values()) {
            for (Player player : channel.viewers.values()) {
                if (!player.isOnline()) {
                    channel.removeViewer(player.getUniqueId(), player);
                }
            }
        }
    }

    private void synchronizeViewer(Player player, ViewerPositionTracker.Position position) {
        for (Channel channel : channels.values()) {
            channel.synchronizeViewer(player, position);
        }
    }

    final class Channel {
        private final Leaderboard board;
        final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
        final List<PacketBoardDisplay> displays = new CopyOnWriteArrayList<>();

        private Channel(Leaderboard board) {
            this.board = board;
        }

        Collection<Player> viewerPlayers() {
            return viewers.values();
        }

        void synchronizeViewer(Player player, ViewerPositionTracker.Position position) {
            Location origin = board.origin();
            World world = board.world();
            UUID uuid = player.getUniqueId();
            if (!player.isOnline()) {
                removeViewer(uuid, player);
                return;
            }
            if (world == null || !world.getUID().equals(position.worldUid())) {
                removeViewer(uuid, player);
                return;
            }

            double distanceSquared = position.distanceSquaredTo(origin);
            if (distanceSquared > board.rangeSquared()) {
                removeViewer(uuid, player);
                return;
            }

            Player existing = viewers.putIfAbsent(uuid, player);
            if (existing == null || existing != player) {
                if (existing != null) {
                    // Same player, new connection: the mapped Player is from before a relog and
                    // its packets would go nowhere. Replace it and resend the full state.
                    viewers.put(uuid, player);
                }
                // Iterate the COW list directly: a size()/get(i) pair races page rotations
                // shrinking the list, and the iterator is a consistent snapshot.
                for (PacketBoardDisplay display : displays) {
                    display.sendFullSpawn(player);
                }
            }
            // Every sweep, not just on entry: the board reuses this position for its typing sound,
            // so a viewer who walks around while a page is up has to keep the sound following them.
            board.trackViewer(player, position.x(), position.y(), position.z(), distanceSquared);
        }

        private void removeViewer(UUID uuid, Player player) {
            if (viewers.remove(uuid) == null) {
                return;
            }
            for (PacketBoardDisplay display : displays) {
                display.sendDestroy(player);
            }
            board.removeViewer(uuid);
        }

        private void dropAllViewers() {
            for (Player player : viewerPlayers()) {
                for (PacketBoardDisplay display : displays) {
                    display.sendDestroy(player);
                }
            }
            viewers.clear();
        }

        private void destroyAll() {
            while (!displays.isEmpty()) {
                displays.get(displays.size() - 1).remove();
            }
            viewers.clear();
        }
    }
}
