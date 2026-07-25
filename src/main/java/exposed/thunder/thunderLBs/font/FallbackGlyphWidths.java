package exposed.thunder.thunderLBs.font;

public final class FallbackGlyphWidths implements GlyphWidthProvider {
    public static final int DEFAULT_ADVANCE = 8;
    public static final int MIN_ADVANCE = 0;
    public static final int MAX_ADVANCE = 64;
    private static final int WIDE_ADVANCE = 16;


    private static final int[] WIDE_RANGES = {
            0x1100, 0x115F,
            0x2E80, 0x303E,
            0x3041, 0x33FF,
            0x3400, 0x4DBF,
            0x4E00, 0x9FFF,
            0xA000, 0xA4CF,
            0xAC00, 0xD7A3,
            0xF900, 0xFAFF,
            0xFE10, 0xFE19,
            0xFE30, 0xFE6F,
            0xFF00, 0xFF60,
            0xFFE0, 0xFFE6,
            0x1F300, 0x1F64F,
            0x1F900, 0x1F9FF,
            0x20000, 0x3FFFD,
    };

    private final int advance;

    public FallbackGlyphWidths(int advance) {
        this.advance = Math.max(MIN_ADVANCE, Math.min(MAX_ADVANCE, advance));
    }

    @Override
    public int advance(int codePoint) {
        return isWide(codePoint) ? WIDE_ADVANCE : advance;
    }

    @Override
    public int priority() {
        return PRIORITY_FALLBACK;
    }

    @Override
    public boolean approximate() {
        return true;
    }

    @Override
    public String providerName() {
        return "fallback " + advance + "px";
    }


    public int configuredAdvance() {
        return advance;
    }

    static boolean isWide(int codePoint) {
        int low = 0;
        int high = (WIDE_RANGES.length / 2) - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (codePoint < WIDE_RANGES[middle * 2]) {
                high = middle - 1;
            } else if (codePoint > WIDE_RANGES[middle * 2 + 1]) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }
}
