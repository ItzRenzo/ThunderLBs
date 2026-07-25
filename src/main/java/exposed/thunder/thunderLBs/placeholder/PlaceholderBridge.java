package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.ThunderLBs;
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
import java.util.concurrent.ConcurrentHashMap;

public final class PlaceholderBridge implements AutoCloseable {
    private final ThunderLBs plugin;
    private final boolean placeholderApiAvailable;
    private final List<NativeLeaderboardProvider> nativeProviders;
    private final Set<String> warnedNativeResolvers = ConcurrentHashMap.newKeySet();

    public PlaceholderBridge(ThunderLBs plugin) {
        this.plugin = plugin;
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.nativeProviders = List.of(new TopperQueryBridge(plugin), new AjLeaderboardsBridge(plugin));
        if (!placeholderApiAvailable) {
            plugin.getLogger().warning("PlaceholderAPI was not detected. Native leaderboard providers will still "
                    + "work, but custom provider placeholders will not be resolved.");
        }
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


            OfflinePlayer onlineContext = firstAvailable(null, Bukkit.getOnlinePlayers());
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
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
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
