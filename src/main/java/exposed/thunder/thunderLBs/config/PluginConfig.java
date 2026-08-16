package exposed.thunder.thunderLBs.config;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.animation.EasingType;
import exposed.thunder.thunderLBs.font.ConfigGlyphWidths;
import exposed.thunder.thunderLBs.font.FallbackGlyphWidths;
import exposed.thunder.thunderLBs.font.GlyphWidthRegistry;
import exposed.thunder.thunderLBs.font.PadPass;
import exposed.thunder.thunderLBs.font.PadSettings;
import exposed.thunder.thunderLBs.font.RowNormalizer;
import exposed.thunder.thunderLBs.font.TextMeasurer;
import exposed.thunder.thunderLBs.leaderboard.BarMode;
import exposed.thunder.thunderLBs.render.TextAlignment;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class PluginConfig {
    private static final Set<String> NATIVE_PROVIDER_NAMES =
            Set.of("topper", "ajleaderboards", "timedtopper");

    public enum RenderMode {
        PACKET,
        ENTITY;

        public static RenderMode fromString(String value, RenderMode fallback) {
            if (value == null || value.isEmpty()) {
                return fallback;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "packet", "packets" -> PACKET;
                case "entity", "entities" -> ENTITY;
                default -> fallback;
            };
        }
    }


    public static final int MIN_LINE_WIDTH = 50;
    public static final int MAX_LINE_WIDTH = 4096;
    public static final int DEFAULT_LINE_WIDTH = 250;

    private final ThunderLBs plugin;
    private RenderMode renderMode;
    private int viewDistance;
    private int lineWidth;
    private TextAlignment alignment;
    private String providerName;
    private Map<String, Provider> providers;
    private Provider provider;
    private String missingText;
    private String missingPosition;
    private Defaults defaults;
    private Bar bar;
    private Formatting formatting;
    private ConfigGlyphWidths glyphWidths;
    private int fallbackGlyphAdvance;
    private TextMeasurer textMeasurer;
    private PadPass padPass;
    private RowNormalizer rowNormalizer;
    private Sounds sounds;
    private Animation animation;
    private Performance performance;
    private Messages messages;
    private boolean debugPlaceholders;

    public PluginConfig(ThunderLBs plugin) {
        this.plugin = plugin;
        reload(false);
    }

    public void reload(boolean fromDisk) {
        if (fromDisk) {
            plugin.reloadConfig();
        }
        FileConfiguration cfg = plugin.getConfig();
        YamlConfiguration providersCfg = load("providers.yml");
        YamlConfiguration messagesCfg = load("messages.yml");
        this.renderMode = RenderMode.fromString(cfg.getString("render.mode", "packet"), RenderMode.PACKET);
        this.viewDistance = Math.max(4, cfg.getInt("render.view-distance", 48));
        this.lineWidth = Math.max(MIN_LINE_WIDTH,
                Math.min(MAX_LINE_WIDTH, cfg.getInt("render.line-width", DEFAULT_LINE_WIDTH)));
        this.alignment = TextAlignment.fromString(cfg.getString("render.alignment"), TextAlignment.CENTER);
        this.providers = loadProviders(providersCfg);
        String configuredProvider = Objects.toString(cfg.getString("provider", "topper"), "topper")
                .toLowerCase(Locale.ROOT);
        if (providers.containsKey(configuredProvider)) {
            this.providerName = configuredProvider;
        } else {
            this.providerName = "topper";
            plugin.getLogger().warning("Unknown provider '" + configuredProvider + "', falling back to topper.");
        }
        this.provider = providers.get(providerName);
        this.missingText = cfg.getString("missing-text", "---");
        this.missingPosition = Objects.toString(cfg.get("missing-position", "-1"), "-1");
        if (missingPosition.isBlank()) {
            this.missingPosition = "-1";
        }
        this.defaults = Defaults.from(cfg);
        this.bar = Bar.from(cfg.getConfigurationSection("bar"));
        this.formatting = Formatting.from(cfg.getConfigurationSection("formatting"));
        ConfigurationSection padding = cfg.getConfigurationSection("formatting.padding");
        this.glyphWidths = loadGlyphWidths(padding == null ? null : padding.getConfigurationSection("glyph-widths"));
        this.fallbackGlyphAdvance = padding == null
                ? FallbackGlyphWidths.DEFAULT_ADVANCE
                : padding.getInt("fallback-advance", FallbackGlyphWidths.DEFAULT_ADVANCE);
        GlyphWidthRegistry widths = plugin.glyphWidths();
        widths.applyConfig(glyphWidths, fallbackGlyphAdvance);
        if (this.textMeasurer == null) {
            this.textMeasurer = new TextMeasurer(widths);
        }
        this.padPass = PadPass.create(textMeasurer, formatting.padding());
        this.rowNormalizer = RowNormalizer.create(textMeasurer, formatting.padding());
        this.sounds = Sounds.from(cfg.getConfigurationSection("sounds"),
                warning -> plugin.getLogger().warning(warning));
        this.animation = Animation.from(cfg.getConfigurationSection("animation"));
        this.performance = Performance.from(cfg.getConfigurationSection("performance"));
        this.messages = Messages.from(messagesCfg);
        this.debugPlaceholders = cfg.getBoolean("debug.placeholders", false);
    }

    private YamlConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), name));
    }


    static ConfigGlyphWidths loadGlyphWidths(ConfigurationSection section) {
        if (section == null) {
            return ConfigGlyphWidths.EMPTY;
        }
        Map<Integer, Integer> widths = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            int codePoint = parseCodePoint(key);
            if (codePoint < 0 || !section.isInt(key)) {
                continue;
            }
            int advance = section.getInt(key);
            if (advance < FallbackGlyphWidths.MIN_ADVANCE || advance > FallbackGlyphWidths.MAX_ADVANCE) {
                continue;
            }
            widths.put(codePoint, advance);
        }
        return widths.isEmpty() ? ConfigGlyphWidths.EMPTY : new ConfigGlyphWidths(widths);
    }

    private static int parseCodePoint(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        if (trimmed.length() > 2 && trimmed.regionMatches(true, 0, "U+", 0, 2)) {
            try {
                int codePoint = Integer.parseInt(trimmed.substring(2), 16);
                return Character.isValidCodePoint(codePoint) ? codePoint : -1;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        int codePoint = trimmed.codePointAt(0);
        return Character.charCount(codePoint) == trimmed.length() ? codePoint : -1;
    }

    private Map<String, Provider> loadProviders(ConfigurationSection section) {
        Map<String, Provider> loaded = new LinkedHashMap<>();
        loaded.put("topper", Provider.empty());
        loaded.put("ajleaderboards", Provider.empty());
        loaded.put("timedtopper", Provider.empty());
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection providerSection = section.getConfigurationSection(key);
                if (providerSection == null) {
                    continue;
                }
                String name = key.toLowerCase(Locale.ROOT);
                if (NATIVE_PROVIDER_NAMES.contains(name)) {
                    continue;
                }
                Provider base = loaded.getOrDefault(name, Provider.empty());
                loaded.put(name, Provider.from(providerSection, base));
            }
        }
        return Collections.unmodifiableMap(loaded);
    }

    public RenderMode renderMode() {
        return renderMode;
    }

    public int viewDistance() {
        return viewDistance;
    }


    public int lineWidth() {
        return lineWidth;
    }


    public TextAlignment alignment() {
        return alignment;
    }

    public String providerName() {
        return providerName;
    }

    public Provider provider() {
        return provider;
    }

    public String missingText() {
        return missingText;
    }

    public String missingPosition() {
        return missingPosition;
    }

    public Defaults defaults() {
        return defaults;
    }

    public Bar bar() {
        return bar;
    }

    public Formatting formatting() {
        return formatting;
    }


    public ConfigGlyphWidths glyphWidths() {
        return glyphWidths;
    }


    public int fallbackGlyphAdvance() {
        return fallbackGlyphAdvance;
    }


    public TextMeasurer textMeasurer() {
        return textMeasurer;
    }


    public PadPass padPass() {
        return padPass;
    }


    public PadPass padPass(PadSettings settings) {
        if (settings == null || settings.equals(formatting.padding())) {
            return padPass;
        }
        return PadPass.create(textMeasurer, settings);
    }


    public RowNormalizer rowNormalizer() {
        return rowNormalizer;
    }


    public RowNormalizer rowNormalizer(PadSettings settings) {
        if (settings == null || settings.equals(formatting.padding())) {
            return rowNormalizer;
        }
        return RowNormalizer.create(textMeasurer, settings);
    }

    public Sounds sounds() {
        return sounds;
    }

    public Animation animation() {
        return animation;
    }

    public Performance performance() {
        return performance;
    }

    public Messages messages() {
        return messages;
    }

    public boolean debugPlaceholders() {
        return debugPlaceholders;
    }

    public String prefixedMessage(String key, Map<String, String> placeholders) {
        String content = messages.format(key, placeholders);
        if (content.isEmpty()) {
            return "";
        }
        return messages.prefix() + content;
    }

    public record Provider(
            String name,
            String value,
            String viewerRank,
            String viewerValue,
            String groupName,
            String groupValue,
            String groupViewerRank,
            String groupViewerValue,
            String groupTeam) {

        public static String applyInterval(String pattern, String interval) {
            if (pattern == null || pattern.isEmpty()) {
                return pattern;
            }
            if (pattern.contains("%interval%")) {
                return pattern.replace("%interval%", interval);
            }
            if (!"alltime".equals(interval) && pattern.contains("alltime")) {
                return pattern.replace("alltime", interval);
            }
            return pattern;
        }

        public static Provider empty() {
            return new Provider("", "", "", "", "", "", "", "", "");
        }

        public static Provider from(ConfigurationSection section, Provider base) {
            return new Provider(
                    section.getString("name", base.name()),
                    section.getString("value", base.value()),
                    section.getString("viewer-rank", base.viewerRank()),
                    section.getString("viewer-value", base.viewerValue()),
                    section.getString("group-name", base.groupName()),
                    section.getString("group-value", base.groupValue()),
                    section.getString("group-viewer-rank", base.groupViewerRank()),
                    section.getString("group-viewer-value", base.groupViewerValue()),
                    section.getString("group-team", base.groupTeam()));
        }

        public boolean supportsGroups() {
            return !groupName.isEmpty() && !groupValue.isEmpty();
        }
    }

    public record Defaults(
            int positions,
            long pageDurationTicks,
            long intervalTicks,
            long rowDelayTicks,
            long typingIntervalTicks,
            double rowSpacing,
            double rowStartOffset,
            double barOffsetY,
            double relativeOffset,
            boolean cleanupOnStart) {
        private static Defaults from(FileConfiguration cfg) {
            return new Defaults(
                    cfg.getInt("defaults.positions", 10),
                    cfg.getLong("defaults.page-duration-ticks", 160L),
                    cfg.getLong("defaults.interval-ticks", 200L),
                    cfg.getLong("defaults.row-delay-ticks", 2L),
                    cfg.getLong("defaults.typing-interval-ticks", 1L),
                    cfg.getDouble("defaults.row-spacing", 0.325D),
                    cfg.getDouble("defaults.row-start-offset", -0.8D),
                    cfg.getDouble("defaults.bar-offset-y", -0.5D),
                    cfg.getDouble("defaults.relative-offset", -0.3D),
                    cfg.getBoolean("defaults.cleanup-on-start", true));
        }
    }

    public record Bar(
            BarMode mode,
            String symbol,
            String separator,
            String pendingColor,
            boolean useHolderColor,
            String activeColor,
            String background,
            String foreground) {
        private static Bar from(ConfigurationSection section) {
            if (section == null) {
                return new Bar(BarMode.DOTS, "⏺", " ", "dark_gray", true, "#FFFFFF",
                        "<#4D4D4D><st>                    </st>",
                        "<#CFCFCF><st>                    </st>");
            }
            return new Bar(
                    BarMode.fromString(section.getString("mode", "dots"), BarMode.DOTS),
                    section.getString("symbol", "⏺"),
                    section.getString("separator", " "),
                    section.getString("pending-color", "dark_gray"),
                    section.getBoolean("use-holder-color", true),
                    section.getString("active-color", "#FFFFFF"),
                    section.getString("background", "<#4D4D4D><st>                    </st>"),
                    section.getString("foreground", "<#CFCFCF><st>                    </st>"));
        }
    }

    public record Formatting(
            String title,
            String row,
            String relative,
            List<String> circledNumbers,
            Map<Integer, String> rankColors,
            PadSettings padding) {


        public Formatting(String title,
                String row,
                String relative,
                List<String> circledNumbers,
                Map<Integer, String> rankColors) {
            this(title, row, relative, circledNumbers, rankColors, PadSettings.defaults());
        }

        private static Formatting from(ConfigurationSection section) {
            if (section == null) {
                return new Formatting(defaultTitle(), defaultRow(), defaultRow(), defaultCircled(), Map.of());
            }
            List<String> circled = section.getStringList("circled-numbers");
            if (circled.isEmpty()) {
                circled = defaultCircled();
            }
            return new Formatting(
                    section.getString("title", defaultTitle()),
                    section.getString("row", defaultRow()),
                    section.getString("relative", defaultRow()),
                    Collections.unmodifiableList(circled),
                    loadRankColors(section.getConfigurationSection("rank-colors")),
                    PadSettings.from(section.getConfigurationSection("padding"), PadSettings.defaults()));
        }

        public static Map<Integer, String> loadRankColors(ConfigurationSection section) {
            if (section == null) {
                return Map.of();
            }
            Map<Integer, String> colors = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                String color = section.getString(key);
                if (color == null || color.isBlank()) {
                    continue;
                }
                try {
                    colors.put(Integer.parseInt(key.trim()), color.trim());
                } catch (NumberFormatException ignored) {}
            }
            return Collections.unmodifiableMap(colors);
        }

        public String rankColor(int position, String fallback) {
            return rankColors.getOrDefault(position, fallback);
        }

        private static String defaultTitle() {
            return "<color:%color%>%icon%</color> <bold><color:%color%>%title%</color></bold>";
        }

        private static String defaultRow() {
            return "<color:%color%>%position%</color> <white>%player%</white> <dark_gray>→</dark_gray> <color:%color%>%value%</color>";
        }

        private static List<String> defaultCircled() {
            return List.of("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩");
        }
    }

    public record Sounds(Typing typing) {
        private static Sounds from(ConfigurationSection section, Consumer<String> warnings) {
            return new Sounds(Typing.from(
                    section == null ? null : section.getConfigurationSection("typing"), warnings));
        }

        public record Typing(boolean enabled,
                             String key,
                             SoundCategory category,
                             float volume,
                             float pitchMin,
                             float pitchMax,
                             double radius) {
            static final String DEFAULT_KEY = "minecraft:block.note_block.hat";
            static final SoundCategory DEFAULT_CATEGORY = SoundCategory.MASTER;

            private static Typing from(ConfigurationSection section, Consumer<String> warnings) {
                if (section == null) {
                    return new Typing(true, DEFAULT_KEY, DEFAULT_CATEGORY, 0.5F, 1.85F, 2.0F, 16.0D);
                }
                boolean enabled = section.getBoolean("enabled", true);
                String key = section.getString("key", DEFAULT_KEY);
                if (enabled) {
                    warnAboutUnknownSound(key, warnings);
                }
                return new Typing(
                        enabled,
                        key,
                        parseCategory(section.getString("category"), warnings),
                        (float) section.getDouble("volume", 0.5D),
                        (float) section.getDouble("pitch-min", 1.85D),
                        (float) section.getDouble("pitch-max", 2.0D),
                        section.getDouble("radius", 16.0D));
            }

            static SoundCategory parseCategory(String raw, Consumer<String> warnings) {
                if (raw == null || raw.isBlank()) {
                    return DEFAULT_CATEGORY;
                }
                try {
                    return SoundCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    warnings.accept("Unknown sounds.typing.category '" + raw + "'; using "
                            + DEFAULT_CATEGORY.name().toLowerCase(Locale.ROOT) + ". Valid values: "
                            + categoryNames() + ".");
                    return DEFAULT_CATEGORY;
                }
            }

            private static String categoryNames() {
                StringBuilder builder = new StringBuilder();
                for (SoundCategory category : SoundCategory.values()) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(category.name().toLowerCase(Locale.ROOT));
                }
                return builder.toString();
            }

            private static void warnAboutUnknownSound(String key, Consumer<String> warnings) {
                NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key);
                if (parsed == null) {
                    warnings.accept("sounds.typing.key '" + key + "' is not a valid sound key, so no "
                            + "typing sound will play. Expected something like " + DEFAULT_KEY + ".");
                    return;
                }
                try {
                    if (Registry.SOUNDS.get(parsed) == null) {
                        warnings.accept("sounds.typing.key '" + key + "' is not a sound this server "
                                + "knows about. If it comes from a resource pack this is fine; "
                                + "otherwise check the spelling, because nothing will play.");
                    }
                } catch (RuntimeException ignored) {}
            }
        }
    }

    public record Performance(long relativeRefreshTicks,
                              long providerCacheTtlTicks,
                              boolean asyncProviders,
                              boolean asyncPapi,
                              boolean sharedPacketSerialization) {
        private static final long DEFAULT_RELATIVE_REFRESH_TICKS = 40L;
        private static final long DEFAULT_PROVIDER_CACHE_TTL_TICKS = 100L;
        private static final long MAX_REFRESH_TICKS = 20L * 60L * 60L;

        static Performance from(ConfigurationSection section) {
            if (section == null) {
                return new Performance(DEFAULT_RELATIVE_REFRESH_TICKS, DEFAULT_PROVIDER_CACHE_TTL_TICKS,
                        true, true, true);
            }
            return new Performance(
                    clampTicks(section.getLong("relative-refresh-ticks", DEFAULT_RELATIVE_REFRESH_TICKS)),
                    clampTicks(section.getLong("provider-cache-ttl-ticks", DEFAULT_PROVIDER_CACHE_TTL_TICKS)),
                    section.getBoolean("async-providers", true),
                    section.getBoolean("async-papi", true),
                    section.getBoolean("shared-packet-serialization", true));
        }

        private static long clampTicks(long ticks) {
            return Math.max(1L, Math.min(MAX_REFRESH_TICKS, ticks));
        }
    }

    public record Animation(Title title, Row row, Opacity opacity, Bar bar) {
        private static Animation from(ConfigurationSection section) {
            if (section == null) {
                return new Animation(Title.defaultTitle(), Row.defaultRow(), Opacity.defaultOpacity(), Bar.defaultBar());
            }
            return new Animation(
                    Title.from(section.getConfigurationSection("title")),
                    Row.from(section.getConfigurationSection("row")),
                    Opacity.from(section.getConfigurationSection("opacity")),
                    Bar.from(section.getConfigurationSection("bar")));
        }

        public record Title(int inFrames, int outFrames, EasingType inEasing, EasingType outEasing, double scale,
                double overshoot) {
            private static Title from(ConfigurationSection section) {
                if (section == null) {
                    return defaultTitle();
                }
                ConfigurationSection in = section.getConfigurationSection("in");
                ConfigurationSection out = section.getConfigurationSection("out");
                int inFrames = in != null ? in.getInt("frames", 20) : 20;
                int outFrames = out != null ? out.getInt("frames", 20) : 20;
                EasingType inEasing = EasingType.fromConfig(in != null ? in.getString("easing") : null,
                        EasingType.EASE_OUT_BACK);
                EasingType outEasing = EasingType.fromConfig(out != null ? out.getString("easing") : null,
                        EasingType.EASE_IN_SINE);
                double overshoot = in != null ? in.getDouble("overshoot", 4.5D) : 4.5D;
                double scale = in != null ? in.getDouble("scale", 1.5D) : section.getDouble("scale", 1.5D);
                return new Title(inFrames, outFrames, inEasing, outEasing, scale, overshoot);
            }

            private static Title defaultTitle() {
                return new Title(20, 20, EasingType.EASE_OUT_BACK, EasingType.EASE_IN_SINE, 1.5D, 4.5D);
            }
        }

        public record Row(int inFrames, int outFrames, EasingType inEasing, EasingType outEasing, double startOffset,
                double distance) {
            private static Row from(ConfigurationSection section) {
                if (section == null) {
                    return defaultRow();
                }
                ConfigurationSection in = section.getConfigurationSection("in");
                ConfigurationSection out = section.getConfigurationSection("out");
                int inFrames = in != null ? in.getInt("frames", 20) : 20;
                int outFrames = out != null ? out.getInt("frames", 20) : 20;
                EasingType inEasing = EasingType.fromConfig(in != null ? in.getString("easing") : null,
                        EasingType.EASE_OUT_CUBIC);
                EasingType outEasing = EasingType.fromConfig(out != null ? out.getString("easing") : null,
                        EasingType.EASE_IN_CUBIC);
                double startOffset = in != null ? in.getDouble("start-offset", -1.25D) : -1.25D;
                double distance = in != null ? in.getDouble("distance", 1.25D) : 1.25D;
                if (out != null) {
                    distance = out.getDouble("distance", distance);
                }
                return new Row(inFrames, outFrames, inEasing, outEasing, startOffset, distance);
            }

            private static Row defaultRow() {
                return new Row(20, 20, EasingType.EASE_OUT_CUBIC, EasingType.EASE_IN_CUBIC, -1.25D, 1.25D);
            }
        }

        public record Opacity(EasingType inEasing, EasingType outEasing) {
            private static Opacity from(ConfigurationSection section) {
                if (section == null) {
                    return defaultOpacity();
                }
                ConfigurationSection in = section.getConfigurationSection("in");
                ConfigurationSection out = section.getConfigurationSection("out");
                EasingType inEasing = EasingType.fromConfig(in != null ? in.getString("easing") : null,
                        EasingType.LINEAR);
                EasingType outEasing = EasingType.fromConfig(out != null ? out.getString("easing") : null,
                        EasingType.LINEAR);
                return new Opacity(inEasing, outEasing);
            }

            private static Opacity defaultOpacity() {
                return new Opacity(EasingType.LINEAR, EasingType.LINEAR);
            }
        }

        public record Bar(int frames, EasingType easing, double translationStart, double translationEnd) {
            private static Bar from(ConfigurationSection section) {
                if (section == null) {
                    return defaultBar();
                }
                return new Bar(
                        section.getInt("frames", 10),
                        EasingType.fromConfig(section.getString("easing"), EasingType.LINEAR),
                        section.getDouble("translation-start", -1.0D),
                        section.getDouble("translation-end", 0.0D));
            }

            private static Bar defaultBar() {
                return new Bar(10, EasingType.LINEAR, -1.0D, 0.0D);
            }
        }
    }

    public record Messages(
            String prefix,
            Map<String, String> messages) {
        private static Messages from(ConfigurationSection section) {
            if (section == null) {
                return new Messages("&#38BDF8ThunderLBs &8» &7", Map.of());
            }
            String prefix = section.getString("prefix", "&#38BDF8ThunderLBs &8» &7");
            Map<String, String> values = section.getValues(false)
                    .entrySet()
                    .stream()
                    .filter(entry -> !entry.getKey().equalsIgnoreCase("prefix"))
                    .filter(entry -> entry.getValue() instanceof String)
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> entry.getKey().toLowerCase(Locale.ROOT),
                            entry -> (String) entry.getValue()));
            return new Messages(prefix, Collections.unmodifiableMap(values));
        }

        public String message(String key) {
            return messages.getOrDefault(key.toLowerCase(Locale.ROOT), "");
        }

        public String format(String key, Map<String, String> placeholders) {
            String template = message(key);
            if (template.isEmpty()) {
                return "";
            }
            String result = template;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    result = result.replace("%" + entry.getKey() + "%", entry.getValue());
                }
            }
            return result;
        }
    }
}
