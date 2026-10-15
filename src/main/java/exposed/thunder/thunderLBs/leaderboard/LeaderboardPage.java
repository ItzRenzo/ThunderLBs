package exposed.thunder.thunderLBs.leaderboard;

import org.bukkit.configuration.ConfigurationSection;

public final class LeaderboardPage {
    public static final String DEFAULT_INTERVAL = "alltime";

    private final String holderId;
    private final String title;
    private final String color;
    private final String icon;
    private final ValueFormat valueFormat;
    private final String prefix;
    private final String suffix;
    private final String interval;
    private final LeaderboardType type;
    private final String rowPattern;
    private final String relativePattern;

    public LeaderboardPage(String holderId, String title, String color, String icon, ValueFormat valueFormat,
            String prefix, String suffix) {
        this(holderId, title, color, icon, valueFormat, prefix, suffix, DEFAULT_INTERVAL);
    }

    public LeaderboardPage(String holderId, String title, String color, String icon, ValueFormat valueFormat,
            String prefix, String suffix, String interval) {
        this(holderId, title, color, icon, valueFormat, prefix, suffix, interval, null, null, null);
    }

    public LeaderboardPage(String holderId, String title, String color, String icon, ValueFormat valueFormat,
            String prefix, String suffix, String interval, LeaderboardType type,
            String rowPattern, String relativePattern) {
        this.holderId = holderId;
        this.title = title;
        this.color = color;
        this.icon = icon;
        this.valueFormat = valueFormat;
        this.prefix = sanitizeSuffix(prefix);
        this.suffix = sanitizeSuffix(suffix);
        this.interval = sanitizeInterval(interval);
        this.type = type;
        this.rowPattern = rowPattern;
        this.relativePattern = relativePattern;
    }

    private static String sanitizeInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        return interval.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String sanitizeSuffix(String suffix) {
        if (suffix == null) {
            return "";
        }
        String trimmed = suffix.trim();
        if (trimmed.equalsIgnoreCase("none")) {
            return "";
        }
        return trimmed;
    }

    public String holderId() {
        return holderId;
    }

    public String title() {
        return title;
    }

    public String color() {
        return color;
    }

    public String icon() {
        return icon;
    }

    public ValueFormat valueFormat() {
        return valueFormat;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public String interval() {
        return interval;
    }

    public LeaderboardPage withInterval(String interval) {
        return new LeaderboardPage(holderId, title, color, icon, valueFormat, prefix, suffix, interval,
                type, rowPattern, relativePattern);
    }

    public LeaderboardType type() {
        return type;
    }

    public LeaderboardType effectiveType(LeaderboardType boardType) {
        return type == null ? boardType : type;
    }

    public String rowPattern() {
        return rowPattern;
    }

    public String relativePattern() {
        return relativePattern;
    }

    public LeaderboardFormatting formatting(LeaderboardFormatting inherited) {
        return inherited.withPagePatterns(rowPattern, relativePattern);
    }

    public LeaderboardPage withType(LeaderboardType type) {
        return new LeaderboardPage(holderId, title, color, icon, valueFormat, prefix, suffix, interval,
                type, rowPattern, relativePattern);
    }

    public LeaderboardPage withFormatting(String rowPattern, String relativePattern) {
        return new LeaderboardPage(holderId, title, color, icon, valueFormat, prefix, suffix, interval,
                type, rowPattern, relativePattern);
    }

    public boolean hasSuffix() {
        return !suffix.isEmpty();
    }

    public boolean hasPrefix() {
        return !prefix.isEmpty();
    }

    public static LeaderboardPage from(ConfigurationSection section) {
        if (section == null) {
            return new LeaderboardPage("kills", "MOST KILLS", "#FF2929", "*", ValueFormat.SHORT_NUMBER, "", "");
        }
        String holder = section.getString("holder", "kills");
        String title = section.getString("title", "MOST KILLS");
        String color = section.getString("color", "#FF2929");
        String icon = section.getString("icon", "*");
        ValueFormat format = ValueFormat.fromConfig(section.getString("value-format"), ValueFormat.SHORT_NUMBER);
        String prefix = section.getString("prefix", "");
        String suffix = section.getString("suffix", "");
        String interval = section.getString("interval", DEFAULT_INTERVAL);
        LeaderboardType type = LeaderboardType.fromString(section.getString("type"), null);
        return new LeaderboardPage(holder, title, color, icon, format, prefix, suffix, interval, type,
                section.getString("formatting.row"), section.getString("formatting.relative"));
    }

    public void serialize(ConfigurationSection section) {
        section.set("type", type == null ? null : type.name());
        section.set("holder", holderId);
        section.set("title", title);
        section.set("color", color);
        section.set("icon", icon);
        section.set("value-format", valueFormat.name());
        section.set("prefix", prefix);
        section.set("suffix", suffix);
        section.set("interval", interval);
        section.set("formatting.row", rowPattern);
        section.set("formatting.relative", relativePattern);
    }

    @Override
    public String toString() {
        return holderId + " (" + title + ")";
    }
}
