package exposed.thunder.thunderLBs.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GlyphWidthTable {

    public static final int UNKNOWN = -1;

    static final String RESOURCE = "/thunderlbs/font/vanilla-widths.txt";
    private static final int SUPPORTED_FORMAT = 1;
    private static final int ASCII_LIMIT = 128;

    private static final class Holder {
        private static final GlyphWidthTable INSTANCE = loadResource();
    }


    private final byte[] ascii;
    private final int[] rangeStart;
    private final int[] rangeEnd;
    private final byte[] rangeWidth;
    private final String minecraftVersion;

    private GlyphWidthTable(byte[] ascii, int[] rangeStart, int[] rangeEnd, byte[] rangeWidth,
            String minecraftVersion) {
        this.ascii = ascii;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.rangeWidth = rangeWidth;
        this.minecraftVersion = minecraftVersion;
    }


    public static GlyphWidthTable vanilla() {
        return Holder.INSTANCE;
    }


    public int advance(int codePoint) {
        if (codePoint >= 0 && codePoint < ASCII_LIMIT) {
            byte width = ascii[codePoint];
            return width == Byte.MIN_VALUE ? UNKNOWN : width;
        }
        int low = 0;
        int high = rangeStart.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (codePoint < rangeStart[middle]) {
                high = middle - 1;
            } else if (codePoint > rangeEnd[middle]) {
                low = middle + 1;
            } else {
                return rangeWidth[middle];
            }
        }
        return UNKNOWN;
    }


    public int rangeCount() {
        return rangeStart.length;
    }


    public String minecraftVersion() {
        return minecraftVersion;
    }

    private static GlyphWidthTable loadResource() {
        try (InputStream stream = GlyphWidthTable.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                Logger.getLogger("ThunderLBs").severe("Missing glyph width table " + RESOURCE
                        + "; <pad:...> alignment will fall back to assumed widths");
                return empty();
            }
            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            Logger.getLogger("ThunderLBs").log(Level.SEVERE, "Could not read glyph width table " + RESOURCE
                    + "; <pad:...> alignment will fall back to assumed widths", ex);
            return empty();
        }
    }

    static GlyphWidthTable empty() {
        byte[] ascii = new byte[ASCII_LIMIT];
        java.util.Arrays.fill(ascii, Byte.MIN_VALUE);
        return new GlyphWidthTable(ascii, new int[0], new int[0], new byte[0], "unknown");
    }


    static GlyphWidthTable parse(java.io.Reader source) throws IOException {
        byte[] ascii = new byte[ASCII_LIMIT];
        java.util.Arrays.fill(ascii, Byte.MIN_VALUE);
        List<int[]> ranges = new ArrayList<>(1024);
        String version = "unknown";
        int declaredRanges = -1;

        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    throw new IOException("line " + number + ": expected key=value but found " + trimmed);
                }
                String key = trimmed.substring(0, equals);
                String value = trimmed.substring(equals + 1);
                switch (key) {
                    case "format" -> {
                        int format = Integer.parseInt(value);
                        if (format != SUPPORTED_FORMAT) {
                            throw new IOException("unsupported table format " + format
                                    + " (expected " + SUPPORTED_FORMAT + ")");
                        }
                    }
                    case "minecraft" -> version = value;
                    case "ranges" -> declaredRanges = Integer.parseInt(value);
                    default -> ranges.add(parseRange(number, key, value));
                }
            }
        }

        if (declaredRanges >= 0 && declaredRanges != ranges.size()) {
            throw new IOException("table declares " + declaredRanges + " ranges but contains " + ranges.size());
        }

        int[] start = new int[ranges.size()];
        int[] end = new int[ranges.size()];
        byte[] width = new byte[ranges.size()];
        int previousEnd = -1;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            if (range[0] <= previousEnd) {
                throw new IOException("ranges must be ascending and non-overlapping, but "
                        + Integer.toHexString(range[0]) + " follows " + Integer.toHexString(previousEnd));
            }
            previousEnd = range[1];
            start[i] = range[0];
            end[i] = range[1];
            width[i] = (byte) range[2];
            for (int codePoint = range[0]; codePoint <= Math.min(range[1], ASCII_LIMIT - 1); codePoint++) {
                ascii[codePoint] = (byte) range[2];
            }
        }
        return new GlyphWidthTable(ascii, start, end, width, version);
    }

    private static int[] parseRange(int line, String key, String value) throws IOException {
        try {
            int dash = key.indexOf('-');
            int first = Integer.parseInt(dash < 0 ? key : key.substring(0, dash), 16);
            int last = dash < 0 ? first : Integer.parseInt(key.substring(dash + 1), 16);
            int width = Integer.parseInt(value);
            if (last < first) {
                throw new IOException("line " + line + ": range " + key + " ends before it starts");
            }
            if (width < 0 || width > Byte.MAX_VALUE) {
                throw new IOException("line " + line + ": advance " + width + " is out of range");
            }
            if (first < 0 || last > Character.MAX_CODE_POINT) {
                throw new IOException("line " + line + ": " + key + " is not a valid codepoint range");
            }
            return new int[] {first, last, width};
        } catch (NumberFormatException ex) {
            throw new IOException("line " + line + ": could not parse "
                    + key.toUpperCase(Locale.ROOT) + "=" + value, ex);
        }
    }
}
