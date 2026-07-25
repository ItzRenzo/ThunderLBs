import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives Minecraft glyph advances from the vanilla bitmap font sheets and
 * writes the range-compressed table consumed by {@code GlyphWidthTable}.
 *
 * <p>Run manually, once per supported Minecraft version. This is deliberately
 * not a Gradle task: the client assets are not part of the repository and must
 * never become a build or CI dependency.
 *
 * <pre>
 * java tools/font/GenerateGlyphWidths.java \
 *     --assets "D:\Server Development\MCSymbols" \
 *     --minecraft 1.21.8 \
 *     --out src/main/resources/thunderlbs/font/vanilla-widths.txt
 * </pre>
 *
 * <p>The advance of a bitmap glyph is derived the way the client does it: the
 * sheet is cut into a fixed grid, the rightmost column containing any non
 * transparent pixel gives the inked width, that width is scaled by the
 * provider's declared height over the cell height, and one pixel of spacing is
 * added. Left bearing is ignored, exactly as vanilla ignores it.
 */
public final class GenerateGlyphWidths {
    private static final int FORMAT = 1;

    /**
     * Advances that vanilla has used for a decade. The generator refuses to
     * write a table that disagrees with any of them, so a bad asset directory
     * or a silent format change fails loudly instead of producing a table that
     * is subtly wrong everywhere.
     */
    private static final Map<Integer, Integer> EXPECTED = expected();

    private GenerateGlyphWidths() {}

    public static void main(String[] args) throws IOException {
        Path assets = null;
        Path out = null;
        String minecraft = null;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (flag) {
                case "--assets" -> { assets = requireValue(flag, value); i++; }
                case "--out" -> { out = requireValue(flag, value); i++; }
                case "--minecraft" -> { minecraft = require(flag, value); i++; }
                default -> throw new IllegalArgumentException("unknown option: " + flag);
            }
        }
        if (assets == null || out == null || minecraft == null) {
            System.err.println("usage: GenerateGlyphWidths --assets <dir> --minecraft <version> --out <file>");
            System.exit(2);
            return;
        }

        Map<Integer, Integer> advances = new TreeMap<>();
        int bitmapProviders = 0;
        int overlaps = 0;

        // Vanilla resolves providers in declaration order and keeps the first
        // usable glyph, so include/space.json (referenced before
        // include/default.json in font/default.json) wins outright.
        Map<Integer, Integer> spaces = readSpaceAdvances(assets);

        for (Object provider : providers(readJson(resolveFont(assets, "include/default.json")))) {
            Map<?, ?> entry = asObject(provider);
            if (!"bitmap".equals(entry.get("type"))) {
                continue;
            }
            bitmapProviders++;
            overlaps += readBitmapProvider(assets, entry, advances);
        }
        if (bitmapProviders == 0) {
            throw new IllegalStateException("no bitmap providers found in include/default.json");
        }
        advances.putAll(spaces);

        verify(advances);

        List<int[]> ranges = compress(advances);
        write(out, ranges, minecraft);

        System.out.printf("%d bitmap providers, %d codepoints (%d shadowed by an earlier provider), %d ranges -> %s%n",
                bitmapProviders, advances.size(), overlaps, ranges.size(), out);
    }

    /** Reads one bitmap provider into {@code advances}; returns how many codepoints it did not win. */
    private static int readBitmapProvider(Path assets, Map<?, ?> provider, Map<Integer, Integer> advances)
            throws IOException {
        List<Object> chars = asArray(provider.get("chars"));
        Path texture = resolveTexture(assets, asString(provider.get("file")));
        BufferedImage sheet = ImageIO.read(texture.toFile());
        if (sheet == null) {
            throw new IOException("not a readable image: " + texture);
        }

        int rows = chars.size();
        int columns = 0;
        for (Object row : chars) {
            String text = asString(row);
            columns = Math.max(columns, text.codePointCount(0, text.length()));
        }
        if (rows == 0 || columns == 0) {
            throw new IllegalStateException("empty chars grid in provider " + provider.get("file"));
        }

        int cellWidth = sheet.getWidth() / columns;
        int cellHeight = sheet.getHeight() / rows;
        // Absent "height" means the vanilla default of 8, as for ascii.png.
        double declaredHeight = provider.containsKey("height") ? asDouble(provider.get("height")) : 8.0D;
        double scale = declaredHeight / cellHeight;

        System.out.printf("  %s: sheet=%dx%d grid=%dx%d cell=%dx%d height=%.0f scale=%.4f%n",
                asString(provider.get("file")), sheet.getWidth(), sheet.getHeight(), columns, rows,
                cellWidth, cellHeight, declaredHeight, scale);

        int shadowed = 0;
        for (int row = 0; row < rows; row++) {
            String text = asString(chars.get(row));
            int column = 0;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                int cell = column++;
                if (codePoint == 0) {
                    // U+0000 marks an unused slot in the grid.
                    continue;
                }
                if (cell >= columns) {
                    throw new IllegalStateException("row " + row + " is wider than the grid");
                }
                int advance = advance(sheet, cell * cellWidth, row * cellHeight, cellWidth, cellHeight, scale);
                if (advances.putIfAbsent(codePoint, advance) != null) {
                    shadowed++;
                }
            }
        }
        return shadowed;
    }

    private static int advance(BufferedImage sheet, int originX, int originY, int cellWidth, int cellHeight,
            double scale) {
        int ink = 0;
        for (int x = cellWidth - 1; x >= 0; x--) {
            for (int y = 0; y < cellHeight; y++) {
                if ((sheet.getRGB(originX + x, originY + y) >>> 24) != 0) {
                    ink = x + 1;
                    break;
                }
            }
            if (ink != 0) {
                break;
            }
        }
        return ink == 0 ? 0 : (int) (0.5D + ink * scale) + 1;
    }

    private static Map<Integer, Integer> readSpaceAdvances(Path assets) throws IOException {
        Map<Integer, Integer> spaces = new LinkedHashMap<>();
        for (Object provider : providers(readJson(resolveFont(assets, "include/space.json")))) {
            Map<?, ?> entry = asObject(provider);
            if (!"space".equals(entry.get("type"))) {
                continue;
            }
            asObject(entry.get("advances")).forEach((key, value) -> {
                String text = asString(key);
                if (text.codePointCount(0, text.length()) != 1) {
                    throw new IllegalStateException("space advance key is not a single codepoint: " + text);
                }
                spaces.put(text.codePointAt(0), (int) asDouble(value));
            });
        }
        if (spaces.isEmpty()) {
            throw new IllegalStateException("no space advances found in include/space.json");
        }
        return spaces;
    }

    private static void verify(Map<Integer, Integer> advances) {
        List<String> problems = new ArrayList<>();
        EXPECTED.forEach((codePoint, want) -> {
            Integer got = advances.get(codePoint);
            if (!want.equals(got)) {
                problems.add(String.format("U+%04X %s expected %d but derived %s",
                        codePoint, new String(Character.toChars(codePoint)), want, got));
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("derived advances disagree with known vanilla values:"
                    + System.lineSeparator() + "  " + String.join(System.lineSeparator() + "  ", problems));
        }
        System.out.printf("  self-check: %d known vanilla advances reproduced%n", EXPECTED.size());
    }

    private static List<int[]> compress(Map<Integer, Integer> advances) {
        List<int[]> ranges = new ArrayList<>();
        advances.forEach((codePoint, advance) -> {
            if (!ranges.isEmpty()) {
                int[] last = ranges.get(ranges.size() - 1);
                if (last[1] + 1 == codePoint && last[2] == advance) {
                    last[1] = codePoint;
                    return;
                }
            }
            ranges.add(new int[] {codePoint, codePoint, advance});
        });
        return ranges;
    }

    private static void write(Path out, List<int[]> ranges, String minecraft) throws IOException {
        StringBuilder text = new StringBuilder(ranges.size() * 12 + 256);
        text.append("# GENERATED -- do not edit by hand.\n")
                .append("# Regenerate with:\n")
                .append("#   java tools/font/GenerateGlyphWidths.java --assets <client assets dir>")
                .append(" --minecraft <version> --out <this file>\n")
                .append("# See tools/README.md.\n")
                .append("format=").append(FORMAT).append('\n')
                .append("minecraft=").append(minecraft).append('\n')
                .append("ranges=").append(ranges.size()).append('\n');
        for (int[] range : ranges) {
            text.append(hex(range[0]));
            if (range[1] != range[0]) {
                text.append('-').append(hex(range[1]));
            }
            text.append('=').append(range[2]).append('\n');
        }
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, text.toString(), StandardCharsets.US_ASCII);
    }

    private static String hex(int codePoint) {
        String value = Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT);
        return value.length() >= 4 ? value : "0000".substring(value.length()) + value;
    }

    // ------------------------------------------------------------------
    // asset resolution
    // ------------------------------------------------------------------

    /**
     * Accepts both the in-jar layout ({@code assets/minecraft/font/...}) and a
     * flattened extract ({@code font/...}).
     */
    private static Path resolveFont(Path assets, String relative) {
        return firstExisting("font definition " + relative,
                assets.resolve("assets/minecraft/font").resolve(relative),
                assets.resolve("font").resolve(relative));
    }

    /** Resolves {@code minecraft:font/ascii.png} against either layout. */
    private static Path resolveTexture(Path assets, String reference) {
        String path = reference.contains(":") ? reference.substring(reference.indexOf(':') + 1) : reference;
        String name = path.substring(path.lastIndexOf('/') + 1);
        return firstExisting("texture " + reference,
                assets.resolve("assets/minecraft/textures").resolve(path),
                assets.resolve("textures").resolve(path),
                assets.resolve("textures").resolve(name));
    }

    private static Path firstExisting(String what, Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        StringBuilder tried = new StringBuilder();
        for (Path candidate : candidates) {
            tried.append(System.lineSeparator()).append("  ").append(candidate);
        }
        throw new IllegalArgumentException("could not find " + what + "; tried:" + tried);
    }

    private static List<Object> providers(Object document) {
        return asArray(asObject(document).get("providers"));
    }

    private static Path requireValue(String flag, String value) {
        return Path.of(require(flag, value));
    }

    private static String require(String flag, String value) {
        if (value == null) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // minimal JSON reader -- the font definitions are plain data, and a
    // generator that runs by hand should not need a dependency
    // ------------------------------------------------------------------

    private static Object readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (!json.done()) {
            throw new IOException("trailing content in " + path);
        }
        return value;
    }

    private static Map<?, ?> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("expected an object but found " + value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalStateException("expected an array but found " + value);
    }

    private static String asString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("expected a string but found " + value);
    }

    private static double asDouble(Object value) {
        if (value instanceof Double number) {
            return number;
        }
        throw new IllegalStateException("expected a number but found " + value);
    }

    private static final class Json {
        private final String text;
        private int cursor;

        private Json(String text) {
            this.text = text;
        }

        private boolean done() {
            return cursor >= text.length();
        }

        private void skipWhitespace() {
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
        }

        private Object readValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                cursor++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw fail("expected , or } but found '" + c + "'");
                }
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                cursor++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw fail("expected , or ] but found '" + c + "'");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return result.toString();
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        // Surrogate pairs are kept as-is; String already stores
                        // them that way and codePointAt recombines them.
                        result.append((char) Integer.parseInt(text.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                    default -> throw fail("unsupported escape \\" + escape);
                }
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, cursor)) {
                throw fail("expected " + literal);
            }
            cursor += literal.length();
            return value;
        }

        private Double readNumber() {
            int start = cursor;
            while (cursor < text.length() && "+-.eE0123456789".indexOf(text.charAt(cursor)) >= 0) {
                cursor++;
            }
            if (start == cursor) {
                throw fail("expected a value");
            }
            return Double.parseDouble(text.substring(start, cursor));
        }

        private char peek() {
            if (done()) {
                throw fail("unexpected end of input");
            }
            return text.charAt(cursor);
        }

        private char next() {
            char c = peek();
            cursor++;
            return c;
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw fail("expected '" + expected + "' but found '" + c + "'");
            }
        }

        private UncheckedIOException fail(String message) {
            return new UncheckedIOException(new IOException(message + " at offset " + cursor));
        }
    }

    private static Map<Integer, Integer> expected() {
        Map<Integer, Integer> known = new LinkedHashMap<>();
        known.put((int) ' ', 4);
        known.put((int) '!', 2);
        known.put((int) '"', 4);
        known.put((int) '#', 6);
        known.put((int) '$', 6);
        known.put((int) '%', 6);
        known.put((int) '&', 6);
        known.put((int) '\'', 2);
        known.put((int) '(', 4);
        known.put((int) ')', 4);
        known.put((int) '*', 4);
        known.put((int) '+', 6);
        known.put((int) ',', 2);
        known.put((int) '-', 6);
        known.put((int) '.', 2);
        known.put((int) '/', 6);
        known.put((int) '0', 6);
        known.put((int) '1', 6);
        known.put((int) ':', 2);
        known.put((int) ';', 2);
        known.put((int) '@', 7);
        known.put((int) 'A', 6);
        known.put((int) 'I', 4);
        known.put((int) 'W', 6);
        known.put((int) '[', 4);
        known.put((int) ']', 4);
        known.put((int) 'f', 5);
        known.put((int) 'i', 2);
        known.put((int) 'j', 6);
        known.put((int) 'k', 5);
        known.put((int) 'l', 3);
        known.put((int) 'r', 6);
        known.put((int) 't', 4);
        known.put((int) '|', 2);
        known.put((int) '~', 7);
        known.put(0x200C, 0);
        return known;
    }
}
