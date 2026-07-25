package exposed.thunder.thunderLBs.font;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record PadSettings(boolean enabled, int target, String filler, PadMode mode, String style, int rowWidth) {
    public static final int MIN_TARGET = 1;
    public static final int MAX_TARGET = 4096;


    public static final int ROW_WIDTH_OFF = -1;

    public static final int ROW_WIDTH_AUTO = 0;

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_TARGET = 150;
    public static final String DEFAULT_FILLER = ".";
    public static final PadMode DEFAULT_MODE = PadMode.FILL;
    public static final String DEFAULT_STYLE = "dark_gray";
    public static final int DEFAULT_ROW_WIDTH = ROW_WIDTH_AUTO;

    private static final PadSettings DEFAULTS = new PadSettings(
            DEFAULT_ENABLED, DEFAULT_TARGET, DEFAULT_FILLER, DEFAULT_MODE, DEFAULT_STYLE, DEFAULT_ROW_WIDTH);

    public PadSettings {
        target = Math.max(MIN_TARGET, Math.min(MAX_TARGET, target));
        if (filler == null || filler.isEmpty()) {
            filler = DEFAULT_FILLER;
        }
        if (mode == null) {
            mode = DEFAULT_MODE;
        }
        if (style == null) {
            style = "";
        }
        if (rowWidth < ROW_WIDTH_AUTO) {
            rowWidth = ROW_WIDTH_OFF;
        } else if (rowWidth > MAX_TARGET) {
            rowWidth = MAX_TARGET;
        }
    }


    public PadSettings(boolean enabled, int target, String filler, PadMode mode, String style) {
        this(enabled, target, filler, mode, style, DEFAULT_ROW_WIDTH);
    }

    public static PadSettings defaults() {
        return DEFAULTS;
    }


    public static PadSettings from(ConfigurationSection section, PadSettings fallback) {
        PadSettings base = fallback == null ? DEFAULTS : fallback;
        if (section == null) {
            return base;
        }
        return new PadSettings(
                section.getBoolean("enabled", base.enabled()),
                section.getInt("target", base.target()),
                section.getString("filler", base.filler()),
                PadMode.fromString(section.getString("mode"), base.mode()),
                section.getString("style", base.style()),
                parseRowWidth(section.getString("row-width"), base.rowWidth()));
    }

    public void serialize(ConfigurationSection section) {
        section.set("enabled", enabled);
        section.set("target", target);
        section.set("filler", filler);
        section.set("mode", mode.configName());
        section.set("style", style);
        section.set("row-width", rowWidthName());
    }


    public static int parseRowWidth(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "auto", "true", "widest" -> ROW_WIDTH_AUTO;
            case "off", "false", "none" -> ROW_WIDTH_OFF;
            default -> {
                try {
                    int parsed = Integer.parseInt(value);
                    yield parsed <= 0 ? ROW_WIDTH_OFF : Math.min(parsed, MAX_TARGET);
                } catch (NumberFormatException ex) {
                    yield fallback;
                }
            }
        };
    }


    public String rowWidthName() {
        if (rowWidth == ROW_WIDTH_AUTO) {
            return "auto";
        }
        return rowWidth == ROW_WIDTH_OFF ? "off" : String.valueOf(rowWidth);
    }

    public boolean isDefault() {
        return equals(DEFAULTS);
    }
}
