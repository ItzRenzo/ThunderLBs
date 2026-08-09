package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.animation.AnimationCache;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import exposed.thunder.thunderLBs.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Leaderboard {
    private final ThunderLBs plugin;
    private final PluginConfig config;
    private final PlaceholderBridge placeholderBridge;
    private AnimationCache animationCache;
    private final LeaderboardDefinition definition;
    private final Location origin;
    private final World world;
    private final double rangeSquared;
    private final int originChunkX;
    private final int originChunkZ;
    private final String tag;
    private final List<BoardDisplay> trackedDisplays;
    private final List<PageSession> activeSessions;
    private final RelativeDisplayManager relativeDisplayManager;
    private final RegionTaskScheduler scheduler;
    private TaskHandle nextPageTask;
    private TaskHandle audienceTask;
    private final Map<UUID, Viewer> audience = new ConcurrentHashMap<>();
    private int pageIndex;

    /**
     * A player who can currently see this board, together with where they were when the backend last
     * placed them. The position is carried alongside the player so range checks and positional sounds
     * never have to call {@code Player#getLocation()} from the board's thread, which under Folia
     * belongs to whichever region owns the player rather than the one owning the board.
     */
    public record Viewer(Player player, double x, double y, double z, double distanceSquared) {
    }

    public Leaderboard(ThunderLBs plugin,
            PluginConfig config,
            PlaceholderBridge placeholderBridge,
            LeaderboardDefinition definition) {
        this.plugin = plugin;
        this.config = config;
        this.placeholderBridge = placeholderBridge;
        this.definition = definition;
        this.origin = definition.origin();
        this.world = origin.getWorld();
        double range = definition.viewDistance();
        this.rangeSquared = range * range;
        this.originChunkX = origin.getBlockX() >> 4;
        this.originChunkZ = origin.getBlockZ() >> 4;
        this.tag = "ThunderLBs:" + definition.id().toLowerCase(Locale.ROOT);
        this.trackedDisplays = new ArrayList<>();
        this.activeSessions = new ArrayList<>();
        this.relativeDisplayManager = new RelativeDisplayManager(plugin, this, placeholderBridge);
        this.scheduler = RegionTaskScheduler.create(plugin);
        rebuildAnimationCache();
    }

    public void start() {
        if (world == null) {
            // There is no region to dispatch to; startOnRegion reports the unloaded world.
            startOnRegion();
            return;
        }
        scheduler.execute(origin, this::startOnRegion);
    }

    private void startOnRegion() {
        World world = this.world;
        if (world == null) {
            plugin.getLogger().warning("Cannot start leaderboard '" + definition.id() + "' because its world is null.");
            return;
        }
        trackedDisplays.clear();
        plugin.renderBackend().register(this);
        relativeDisplayManager.start();
        if (plugin.renderBackend().name().equals("entity")) {
            audienceTask = scheduler.runGlobalAtFixedRate(this::refreshAudience, 1L, 20L);
        }
        if (nextPageTask == null) {
            scheduleNextPage(0L);
        }
    }

    public void stop() {
        if (world == null) {
            // Nothing was ever started on a region, so tear down inline rather than dispatching to
            // a region that does not exist.
            stopOnRegion();
            return;
        }
        scheduler.execute(origin, this::stopOnRegion);
    }

    private void stopOnRegion() {
        if (nextPageTask != null) {
            nextPageTask.cancel();
            nextPageTask = null;
        }
        if (audienceTask != null) {
            audienceTask.cancel();
            audienceTask = null;
        }
        audience.clear();
        while (!activeSessions.isEmpty()) {
            activeSessions.get(activeSessions.size() - 1).stop();
        }
        relativeDisplayManager.stop();
        while (!trackedDisplays.isEmpty()) {
            BoardDisplay display = trackedDisplays.remove(trackedDisplays.size() - 1);
            display.remove();
        }
        plugin.renderBackend().unregister(this);
    }

    private void scheduleNextPage(long delay) {
        if (nextPageTask != null) {
            nextPageTask.cancel();
        }
        nextPageTask = scheduler.runDelayed(origin, task -> {
            nextPageTask = null;
            showNextPage();
        }, Math.max(0L, delay));
    }

    private void showNextPage() {
        World world = this.world;
        if (world == null) {
            plugin.getLogger()
                    .warning("Cannot show page for leaderboard '" + definition.id() + "' because its world is null.");
            return;
        }
        if (definition.pages().isEmpty()) {
            plugin.getLogger().warning("Leaderboard '" + definition.id() + "' has no pages configured.");
            return;
        }
        long interval = Math.max(1L, definition.settings().intervalTicks());
        if (!isRenderable()) {
            scheduleNextPage(interval);
            return;
        }
        int totalPages = Math.max(1, definition.pages().size());
        int currentPageIndex = pageIndex % totalPages;
        LeaderboardPage page = definition.pages().get(currentPageIndex);
        pageIndex++;

        retireStaleSessions();
        relativeDisplayManager.updatePage(page);

        PageSession session = new PageSession(plugin, config, placeholderBridge, animationCache, this, definition,
                page, currentPageIndex);
        activeSessions.add(session);
        session.start();
        scheduleNextPage(interval);
    }

    private void retireStaleSessions() {
        while (activeSessions.size() > 1) {
            PageSession stale = activeSessions.remove(0);
            stale.stop();
        }
    }

    private void stopActiveSessions() {
        while (!activeSessions.isEmpty()) {
            activeSessions.get(activeSessions.size() - 1).stop();
        }
    }

    public LeaderboardDefinition definition() {
        return definition;
    }

    public Location origin() {
        return origin;
    }

    public World world() {
        return world;
    }

    public double rangeSquared() {
        return rangeSquared;
    }

    public String tag() {
        return tag;
    }

    public void track(BoardDisplay display) {
        trackedDisplays.add(display);
    }

    public void untrack(BoardDisplay display) {
        trackedDisplays.remove(display);
    }

    void onSessionFinished(PageSession session) {
        activeSessions.remove(session);
    }

    /**
     * Records a player as able to see this board, at the given position. Backends call this on every
     * sweep so the stored position stays fresh; only a player who was not already in the audience
     * triggers the page check, which is the expensive half.
     */
    public void trackViewer(Player player, double x, double y, double z, double distanceSquared) {
        Viewer previous = audience.put(player.getUniqueId(),
                new Viewer(player, x, y, z, distanceSquared));
        if (previous == null) {
            scheduler.execute(origin, this::synchronizeViewerOnRegion);
        }
    }

    /** Everyone currently able to see this board. Safe to iterate from any thread. */
    public Collection<Viewer> viewers() {
        return audience.values();
    }

    private void synchronizeViewerOnRegion() {
        boolean pageIsActive = false;
        for (PageSession session : activeSessions) {
            if (session.hasLiveDisplays()) {
                pageIsActive = true;
                break;
            }
        }
        if (!pageIsActive) {
            showNextPage();
        }
    }

    public void removeViewer(UUID playerId) {
        audience.remove(playerId);
        scheduler.execute(origin, () -> relativeDisplayManager.removeViewer(playerId));
    }

    public void rebuildAnimationCache() {
        this.animationCache = new AnimationCache(definition.animations());
    }

    public AnimationCache animationCache() {
        return animationCache;
    }

    boolean isRenderable() {
        if (world == null) {
            return false;
        }
        if (plugin.renderBackend().name().equals("entity") && !isOriginChunkLoaded()) {
            return false;
        }
        return hasAudience();
    }

    boolean hasAudience() {
        return world != null && !audience.isEmpty();
    }

    private void refreshAudience() {
        Set<UUID> online = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            // Runs on the thread owning the player, which is the only one allowed to read their
            // location; the position it finds is then published for the board's thread to reuse.
            scheduler.executeDelayed(player, () -> {
                Location location = player.getLocation();
                double distanceSquared = world != null && world.equals(player.getWorld())
                        ? location.distanceSquared(origin)
                        : Double.MAX_VALUE;
                if (distanceSquared <= rangeSquared) {
                    trackViewer(player, location.getX(), location.getY(), location.getZ(),
                            distanceSquared);
                } else {
                    audience.remove(playerId);
                }
            }, () -> audience.remove(playerId), 1L);
        }
        audience.keySet().retainAll(online);
    }

    boolean isOriginChunkLoaded() {
        if (world == null) {
            return false;
        }
        return world.isChunkLoaded(originChunkX, originChunkZ);
    }
}
