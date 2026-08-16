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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    private final ExecutorService sendExecutor;
    private final Map<Leaderboard, Channel> channels = new ConcurrentHashMap<>();

    public PacketRenderBackend(ThunderLBs plugin) {
        this.plugin = plugin;
        this.scheduler = RegionTaskScheduler.create(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.positions = new ViewerPositionTracker(plugin);
        this.positions.start();
        this.sweepExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ThunderLBs-viewer-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.sweepExecutor.scheduleAtFixedRate(this::sweepViewers,
                VIEWER_REFRESH_MILLIS, VIEWER_REFRESH_MILLIS, TimeUnit.MILLISECONDS);
        this.sendExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ThunderLBs-packet-sender");
            thread.setDaemon(true);
            return thread;
        });
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

    void dispatchSend(Runnable send) {
        try {
            sendExecutor.execute(() -> runSend(send));
        } catch (RejectedExecutionException rejected) {
            runSend(send);
        }
    }

    private void runSend(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Leaderboard packet send failed", exception);
        }
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
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                plugin.getLogger().warning("Packet sender did not drain in time; continuing shutdown.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
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
                    plugin.getLogger().log(Level.WARNING,
                            "Leaderboard viewer sync failed for " + player.getName(), exception);
                }
            });
            purgeOfflineViewers();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Leaderboard viewer sweep failed", exception);
        }
    }

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
                    viewers.put(uuid, player);
                }
                for (PacketBoardDisplay display : displays) {
                    display.sendFullSpawn(player);
                }
            }
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
