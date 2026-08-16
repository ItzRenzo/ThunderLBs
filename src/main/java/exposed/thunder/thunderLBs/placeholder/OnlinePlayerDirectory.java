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

final class OnlinePlayerDirectory implements Listener {

    private final ThunderLBs plugin;
    private final Map<String, Player> online = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlineById = new ConcurrentHashMap<>();
    private final Map<String, Optional<OfflinePlayer>> offlineCache = new ConcurrentHashMap<>();

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

    Player onlineByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return online.get(name.toLowerCase(Locale.ROOT));
    }

    Player byId(UUID playerId) {
        return playerId == null ? null : onlineById.get(playerId);
    }

    Player anyOnline() {
        for (Player player : online.values()) {
            return player;
        }
        return null;
    }

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
            offlineCache.remove(key);
        }
    }
}
