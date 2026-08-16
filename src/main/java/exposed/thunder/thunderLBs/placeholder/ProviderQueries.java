package exposed.thunder.thunderLBs.placeholder;

import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;

public final class ProviderQueries {

    private final String providerName;
    private final LeaderboardType type;
    private final String holder;
    private final String interval;
    private final boolean nativeProvider;
    private final String topNamePattern;
    private final String topValuePattern;
    private final String viewerRankPattern;
    private final String viewerValuePattern;
    private final String teamPattern;

    private ProviderQueries(String providerName, LeaderboardType type, String holder, String interval,
            boolean nativeProvider, String topNamePattern, String topValuePattern,
            String viewerRankPattern, String viewerValuePattern, String teamPattern) {
        this.providerName = providerName;
        this.type = type;
        this.holder = holder;
        this.interval = interval;
        this.nativeProvider = nativeProvider;
        this.topNamePattern = topNamePattern;
        this.topValuePattern = topValuePattern;
        this.viewerRankPattern = viewerRankPattern;
        this.viewerValuePattern = viewerValuePattern;
        this.teamPattern = teamPattern;
    }

    public static ProviderQueries create(String providerName,
            PluginConfig.Provider provider,
            LeaderboardType type,
            String holder,
            String interval,
            boolean nativeProvider) {
        boolean group = type == LeaderboardType.GROUP;
        return new ProviderQueries(
                providerName,
                type,
                holder,
                interval,
                nativeProvider,
                bind(group ? provider.groupName() : provider.name(), holder, interval),
                bind(group ? provider.groupValue() : provider.value(), holder, interval),
                bind(group ? provider.groupViewerRank() : provider.viewerRank(), holder, interval),
                bind(group ? provider.groupViewerValue() : provider.viewerValue(), holder, interval),
                group ? bind(provider.groupTeam(), holder, interval) : "");
    }

    private static String bind(String pattern, String holder, String interval) {
        String withInterval = PluginConfig.Provider.applyInterval(pattern, interval);
        return withInterval == null ? "" : withInterval.replace("%holder%", holder);
    }

    public String providerName() {
        return providerName;
    }

    public LeaderboardType type() {
        return type;
    }

    public String holder() {
        return holder;
    }

    public String interval() {
        return interval;
    }

    public boolean nativeProvider() {
        return nativeProvider;
    }

    public String topNamePlaceholder(int position) {
        return atPosition(topNamePattern, position);
    }

    public String topValuePlaceholder(int position) {
        return atPosition(topValuePattern, position);
    }

    public String viewerRankPlaceholder() {
        return viewerRankPattern;
    }

    public String viewerValuePlaceholder() {
        return viewerValuePattern;
    }

    public String teamPlaceholder() {
        return teamPattern;
    }

    public String topNameSource(int position) {
        return nativeProvider ? nativeSource("top_name;" + position) : topNamePlaceholder(position);
    }

    public String topValueSource(int position) {
        return nativeProvider ? nativeSource("top_value_raw;" + position) : topValuePlaceholder(position);
    }

    public String viewerRankSource() {
        return nativeProvider ? nativeSource("top_rank") : viewerRankPattern;
    }

    public String viewerValueSource() {
        return nativeProvider ? nativeSource("value_raw") : viewerValuePattern;
    }

    private static String atPosition(String pattern, int position) {
        return pattern.replace("%position%", Integer.toString(position));
    }

    private String nativeSource(String query) {
        return providerName + ":" + holder + ";" + query;
    }
}
