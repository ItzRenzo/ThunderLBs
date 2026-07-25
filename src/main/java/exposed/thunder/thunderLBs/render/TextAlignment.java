package exposed.thunder.thunderLBs.render;

import java.util.Locale;

public enum TextAlignment {
    CENTER(0),

    LEFT(8),
    RIGHT(16);

    private final int flagBits;

    TextAlignment(int flagBits) {
        this.flagBits = flagBits;
    }


    public int flagBits() {
        return flagBits;
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TextAlignment fromString(String value, TextAlignment fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "center", "centre", "centered", "middle" -> CENTER;
            case "left" -> LEFT;
            case "right" -> RIGHT;
            default -> fallback;
        };
    }
}
