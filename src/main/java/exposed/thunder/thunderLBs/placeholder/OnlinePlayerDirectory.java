package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.ThunderLBs;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies the player lookups the placeholder path needs, without calling Bukkit from whichever
 * thread happens to be resolving.
 *
 * <p>{@code Bukkit.getPlayerExact} and {@code Bukkit.getOnlinePlayers} read live server state, so
 * they are mirrored here from join/quit events on the main thread. {@code getOfflinePlayerIfCached}
 * cannot be mirrored, so its results are memoised and the Bukkit call itself is only ever made on
 * the main thread — an off-main miss returns nothing and queues a fill, so the answer is available
 * from the next resolve onwards.
 */
final class OnlinePlayerDirectory implements Listener {

    private final ThunderLBs plugin;
    private final Map<String, Player> online = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlineById = new ConcurrentHashMap<>();
    private final Map<String, Optional<OfflinePlayer>> offlineCache = new ConcurrentHashMap<>();

    /** Must be constructed on the main thread. */
    OnlinePlayerDirectory(ThunderLBs plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            add(player);
        }
    }

    void close() {
        HandlerList.unregisterAll(this);
        online.clear();
        onlineById.clear();
        offlineCache.clear();
    }

    /** An online player with this exact name, ignoring case. Safe from any thread. */
    Player onlineByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return online.get(name.toLowerCase(Locale.ROOT));
    }

    /** The online player with this id, or {@code null} if they are gone. Safe from any thread. */
    Player byId(UUID playerId) {
        return playerId == null ? null : onlineById.get(playerId);
    }

    /** Any online player, for use as a fallback placeholder context. Safe from any thread. */
    Player anyOnline() {
        for (Player player : online.values()) {
            return player;
        }
        return null;
    }

    /**
     * A cached {@link OfflinePlayer} for this name. Returns {@code null} rather than touching Bukkit
     * when called off the main thread with nothing cached, and schedules the lookup so the next call
     * can answer it.
     */
    OfflinePlayer offlineByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        Optional<OfflinePlayer> cached = offlineCache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (Bukkit.isPrimaryThread()) {
            return lookupOffline(key, name);
        }
        plugin.taskScheduler().runGlobal(() -> lookupOffline(key, name));
        return null;
    }

    private OfflinePlayer lookupOffline(String key, String name) {
        Optional<OfflinePlayer> resolved = Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(name));
        offlineCache.put(key, resolved);
        return resolved.orElse(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        add(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        onlineById.remove(player.getUniqueId());
        String name = player.getName();
        if (name != null) {
            online.remove(name.toLowerCase(Locale.ROOT));
        }
    }

    private void add(Player player) {
        onlineById.put(player.getUniqueId(), player);
        String name = player.getName();
        if (name != null) {
            String key = name.toLowerCase(Locale.ROOT);
            online.put(key, player);
            // An online player is the better answer than anything the offline cache holds.
            offlineCache.remove(key);
        }
    }
}
