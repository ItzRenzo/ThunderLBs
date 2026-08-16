package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;
import exposed.thunder.thunderLBs.util.LegacyFormatting;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PlaceholderBridge implements AutoCloseable {
    private static final long TICK_MILLIS = 50L;
    private static final long CACHE_IDLE_MILLIS = 5L * 60L * 1000L;
    private static final long SWEEP_PERIOD_SECONDS = 60L;
    private static final int REFRESH_THREADS = 2;
    private static final int REFRESH_QUEUE_CAPACITY = 512;

    private final ThunderLBs plugin;
    private final boolean placeholderApiAvailable;
    private final List<NativeLeaderboardProvider> nativeProviders;
    private final Set<String> warnedNativeResolvers = ConcurrentHashMap.newKeySet();
    private final OnlinePlayerDirectory directory;
    private final ProviderCache cache;
    private final ThreadPoolExecutor refreshPool;
    private final ScheduledExecutorService maintenance;

    public PlaceholderBridge(ThunderLBs plugin) {
        this.plugin = plugin;
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.nativeProviders = List.of(new TopperQueryBridge(plugin), new AjLeaderboardsBridge(plugin));
        if (!placeholderApiAvailable) {
            plugin.getLogger().warning("PlaceholderAPI was not detected. Native leaderboard providers will still "
                    + "work, but custom provider placeholders will not be resolved.");
        }
        this.directory = new OnlinePlayerDirectory(plugin);
        this.refreshPool = new ThreadPoolExecutor(REFRESH_THREADS, REFRESH_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REFRESH_QUEUE_CAPACITY), daemonFactory("ThunderLBs-provider"));
        this.cache = new ProviderCache(this::resolveCached, System::currentTimeMillis, this::executorFor);
        this.maintenance = Executors.newSingleThreadScheduledExecutor(daemonFactory("ThunderLBs-provider-sweep"));
        this.maintenance.scheduleAtFixedRate(() -> cache.sweep(CACHE_IDLE_MILLIS),
                SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private long ttlMillis(ProviderCache.Key key) {
        PluginConfig.Performance performance = plugin.getPluginConfig().performance();
        long ticks = isViewerValue(key.value())
                ? performance.relativeRefreshTicks()
                : performance.providerCacheTtlTicks();
        return ticks * TICK_MILLIS;
    }

    private Executor executorFor(ProviderCache.Key key) {
        PluginConfig.Performance performance = plugin.getPluginConfig().performance();
        boolean nativeProvider = key.type() != null && supportsProvider(key.provider(), key.type());
        boolean async = nativeProvider ? performance.asyncProviders() : performance.asyncPapi();
        if (async) {
            return refreshPool;
        }
        return runnable -> submitSynchronous(key, runnable);
    }

    private void submitSynchronous(ProviderCache.Key key, Runnable refresh) {
        if (!plugin.isEnabled()) {
            throw new RejectedExecutionException("the plugin is disabling");
        }
        Player viewer = key.viewer() == null ? null : directory.byId(key.viewer());
        if (viewer != null) {
            plugin.taskScheduler().executeDelayed(viewer, refresh, refresh, 1L);
            return;
        }
        plugin.taskScheduler().runGlobal(refresh);
    }

    private static boolean isViewerValue(ProviderValue value) {
        return value == ProviderValue.VIEWER_RANK || value == ProviderValue.VIEWER_VALUE;
    }

    private String resolveCached(ProviderCache.Key key) {
        OfflinePlayer context = contextFor(key.viewer());
        if (key.viewer() != null && context == null) {
            throw new IllegalStateException("viewer " + key.viewer() + " is offline");
        }
        return resolveProviderUncached(
                key.provider(), key.type(), key.holder(), key.interval(), key.position(), key.value(),
                context, key.placeholder());
    }

    private OfflinePlayer contextFor(UUID viewer) {
        return viewer == null ? null : directory.byId(viewer);
    }

    public boolean isAvailable() {
        return placeholderApiAvailable
                || nativeProviders.stream().anyMatch(NativeLeaderboardProvider::isAvailable);
    }

    public boolean supportsProvider(String providerName, LeaderboardType leaderboardType) {
        return nativeProviders.stream().anyMatch(provider -> provider.supports(providerName, leaderboardType));
    }

    public boolean isNativeProvider(String providerName) {
        return supportsProvider(providerName, LeaderboardType.PLAYER)
                || supportsProvider(providerName, LeaderboardType.GROUP);
    }

    public List<String> holderChoices(String providerName, LeaderboardType leaderboardType) {
        return nativeChoices(providerName, leaderboardType, true);
    }

    public List<String> intervalChoices(String providerName, LeaderboardType leaderboardType) {
        return nativeChoices(providerName, leaderboardType, false);
    }

    public String resolveProvider(
            String providerName,
            LeaderboardType leaderboardType,
            String holder,
            String interval,
            int position,
            ProviderValue value,
            OfflinePlayer player,
            String customPlaceholder) {
        ProviderCache.Key key = new ProviderCache.Key(
                providerName,
                leaderboardType,
                holder,
                interval,
                position,
                value,
                player == null ? null : player.getUniqueId(),
                customPlaceholder);
        return cache.read(key, ttlMillis(key));
    }

    public String resolveProviderNow(
            String providerName,
            LeaderboardType leaderboardType,
            String holder,
            String interval,
            int position,
            ProviderValue value,
            OfflinePlayer player,
            String customPlaceholder) {
        return resolveProviderUncached(
                providerName, leaderboardType, holder, interval, position, value, player, customPlaceholder);
    }

    private String resolveProviderUncached(
            String providerName,
            LeaderboardType leaderboardType,
            String holder,
            String interval,
            int position,
            ProviderValue value,
            OfflinePlayer player,
            String customPlaceholder) {
        NativeLeaderboardProvider.Request request = new NativeLeaderboardProvider.Request(
                providerName,
                leaderboardType,
                holder,
                interval,
                position,
                value,
                player);
        for (NativeLeaderboardProvider provider : nativeProviders) {
            if (!provider.supports(providerName, leaderboardType)) {
                continue;
            }
            try {
                String resolved = provider.resolve(request);
                return resolved == null ? "" : resolved;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                if (warnedNativeResolvers.add(provider.name())) {
                    plugin.getLogger().warning("Failed to resolve a native " + provider.name()
                            + " query; further failures from this integration will be suppressed: "
                            + rootMessage(exception));
                }
                return "";
            }
        }
        return resolveCustomProvider(customPlaceholder, player);
    }

    private String resolveCustomProvider(String placeholder, OfflinePlayer player) {
        String resolved = resolve(placeholder, player);
        if (player == null && placeholderApiAvailable && isUnresolved(placeholder, resolved)) {
            OfflinePlayer onlineContext = directory.anyOnline();
            if (onlineContext != null) {
                resolved = resolve(placeholder, onlineContext);
            }
        }



        return isEchoedIdentifier(placeholder, resolved) ? placeholder : resolved;
    }

    public String resolve(String placeholder, OfflinePlayer player) {
        String normalizedPlaceholder = placeholder == null ? "" : placeholder;
        if (!placeholderApiAvailable) {
            return normalizedPlaceholder;
        }
        try {
            return PlaceholderAPI.setPlaceholders(player, normalizedPlaceholder);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to resolve placeholder '" + normalizedPlaceholder + "': "
                    + throwable.getMessage());
            return normalizedPlaceholder;
        }
    }

    public String resolve(String placeholder) {
        return resolve(placeholder, null);
    }


    public PatternPlaceholders patterns(OfflinePlayer context) {
        if (!placeholderApiAvailable) {
            return PatternPlaceholders.NONE;
        }
        Map<String, String> memo = new HashMap<>(4);
        return pattern -> PlaceholderPatterns.resolve(pattern, placeholder -> memo.computeIfAbsent(
                placeholder, raw -> LegacyFormatting.toMiniMessage(resolve(raw, context))));
    }


    public OfflinePlayer holderContext(String name) {
        if (!placeholderApiAvailable || name == null || name.isBlank()) {
            return null;
        }
        Player online = directory.onlineByName(name);
        if (online != null) {
            return online;
        }
        return directory.offlineByName(name);
    }

    static boolean isUnresolved(String placeholder, String resolved) {
        return resolved == null
                || resolved.equalsIgnoreCase(placeholder == null ? "" : placeholder)
                || isEchoedIdentifier(placeholder, resolved);
    }

    static boolean isEchoedIdentifier(String placeholder, String resolved) {
        if (placeholder == null || resolved == null
                || placeholder.length() < 4
                || placeholder.charAt(0) != '%'
                || placeholder.charAt(placeholder.length() - 1) != '%'
                || placeholder.indexOf('%', 1) != placeholder.length() - 1) {
            return false;
        }
        int identifierSeparator = placeholder.indexOf('_', 1);
        if (identifierSeparator < 0 || identifierSeparator >= placeholder.length() - 2) {
            return false;
        }
        String requestIdentifier = placeholder.substring(identifierSeparator + 1, placeholder.length() - 1);
        return resolved.equalsIgnoreCase(requestIdentifier);
    }

    static <T> T firstAvailable(T requested, Iterable<? extends T> fallbacks) {
        if (requested != null) {
            return requested;
        }
        for (T fallback : fallbacks) {
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }

    private List<String> nativeChoices(
            String providerName,
            LeaderboardType leaderboardType,
            boolean holders) {
        for (NativeLeaderboardProvider provider : nativeProviders) {
            if (!provider.supports(providerName, leaderboardType)) {
                continue;
            }
            try {
                List<String> choices = holders
                        ? provider.holderChoices(providerName, leaderboardType)
                        : provider.intervalChoices(providerName, leaderboardType);
                return normalizeChoices(choices);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                String warningKey = provider.name() + (holders ? "-holders" : "-intervals");
                if (warnedNativeResolvers.add(warningKey)) {
                    plugin.getLogger().warning("Failed to read " + (holders ? "holder" : "interval")
                            + " choices from " + provider.name() + ": " + rootMessage(exception));
                }
                return List.of();
            }
        }
        return List.of();
    }

    static List<String> normalizeChoices(List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String choice : choices) {
            if (choice == null || choice.isBlank()) {
                continue;
            }
            String trimmed = choice.trim();
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        return unique.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Override
    public void close() {
        maintenance.shutdownNow();
        refreshPool.shutdownNow();
        directory.close();
        cache.clear();
        for (NativeLeaderboardProvider provider : nativeProviders) {
            provider.close();
        }
        warnedNativeResolvers.clear();
    }

    public enum ProviderValue {
        TOP_NAME,
        TOP_VALUE,
        VIEWER_RANK,
        VIEWER_VALUE
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
