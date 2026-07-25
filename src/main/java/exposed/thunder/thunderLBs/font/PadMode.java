package exposed.thunder.thunderLBs.font;

import java.util.Locale;

public enum PadMode {

    FILL,

    RIGHT;

    public static PadMode fromString(String value, PadMode fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "fill", "column", "left" -> FILL;
            case "right", "end" -> RIGHT;
            default -> fallback;
        };
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
