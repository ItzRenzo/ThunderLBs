package exposed.thunder.thunderLBs.font;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphWidthTableTest {
    /**
     * Advances vanilla has used for over a decade. Asserted here independently
     * of the font assets, so a truncated or corrupted regeneration of
     * {@code vanilla-widths.txt} fails the build.
     */
    private static final Map<Character, Integer> KNOWN_ASCII = knownAscii();

    @Test
    void bundledTableReproducesKnownVanillaAdvances() {
        GlyphWidthTable table = GlyphWidthTable.vanilla();

        KNOWN_ASCII.forEach((character, expected) -> assertEquals(expected, table.advance(character),
                () -> "advance of '" + character + "' (U+"
                        + String.format("%04X", (int) character) + ")"));
    }

    @Test
    void bundledTableCoversTheFullPrintableAsciiRange() {
        GlyphWidthTable table = GlyphWidthTable.vanilla();

        for (char character = ' '; character <= '~'; character++) {
            assertNotEquals(GlyphWidthTable.UNKNOWN, table.advance(character),
                    "printable ASCII must be measurable: '" + character + "'");
        }
    }

    @Test
    void zeroWidthNonJoinerAdvancesNothing() {
        assertEquals(0, GlyphWidthTable.vanilla().advance(0x200C));
    }

    @Test
    void bundledTableCoversTheGlyphsThisPluginShipsInDefaultFormats() {
        GlyphWidthTable table = GlyphWidthTable.vanilla();

        // config.yml uses these in the default row format and the dots bar.
        assertEquals(8, table.advance('→'), "rightwards arrow");
        assertEquals(6, table.advance('⏺'), "black circle for record");
        for (int circled = 0x2460; circled <= 0x2469; circled++) {
            assertNotEquals(GlyphWidthTable.UNKNOWN, table.advance(circled),
                    "circled number U+" + Integer.toHexString(circled));
        }
    }

    @Test
    void bundledTableIsLoadedAndSubstantial() {
        GlyphWidthTable table = GlyphWidthTable.vanilla();

        assertTrue(table.rangeCount() > 500,
                "expected a fully generated table but found " + table.rangeCount() + " ranges");
        assertNotEquals("unknown", table.minecraftVersion(),
                "the table should record the Minecraft version it came from");
    }

    @Test
    void reportsUnknownForCodepointsOutsideTheTable() {
        GlyphWidthTable table = GlyphWidthTable.vanilla();

        // Private use area: exactly where resource-pack glyphs live, which is
        // why the registry needs a fallback rather than trusting this table.
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance(0xE000));
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance(Character.MAX_CODE_POINT));
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance(-1));
    }

    @Test
    void parsesSingleCodepointsAndRangesAcrossTheAsciiBoundary() throws IOException {
        GlyphWidthTable table = GlyphWidthTable.parse(new StringReader("""
                # comment
                format=1
                minecraft=test
                ranges=3
                0041=6
                0060-0080=3
                1F30A=9
                """));

        assertEquals(3, table.rangeCount());
        assertEquals("test", table.minecraftVersion());
        assertEquals(6, table.advance('A'));
        assertEquals(3, table.advance('`'));
        assertEquals(3, table.advance(0x7F));
        assertEquals(3, table.advance(0x80));
        assertEquals(9, table.advance(0x1F30A));
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance('B'));
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance(0x81));
    }

    @Test
    void rejectsADeclaredRangeCountThatDisagreesWithTheContent() {
        IOException failure = assertThrows(IOException.class, () -> GlyphWidthTable.parse(new StringReader("""
                format=1
                ranges=7
                0041=6
                """)));

        assertTrue(failure.getMessage().contains("declares 7"), failure.getMessage());
    }

    @Test
    void rejectsUnsortedOrOverlappingRanges() {
        assertThrows(IOException.class, () -> GlyphWidthTable.parse(new StringReader("""
                format=1
                0060-0070=3
                0041=6
                """)));
        assertThrows(IOException.class, () -> GlyphWidthTable.parse(new StringReader("""
                format=1
                0041-0050=3
                0050-0060=6
                """)));
    }

    @Test
    void rejectsAnUnsupportedFormatVersion() {
        assertThrows(IOException.class, () -> GlyphWidthTable.parse(new StringReader("format=99\n")));
    }

    @Test
    void emptyTableMeasuresNothingRatherThanThrowing() {
        GlyphWidthTable table = GlyphWidthTable.empty();

        assertEquals(0, table.rangeCount());
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance('A'));
        assertEquals(GlyphWidthTable.UNKNOWN, table.advance(0x1F30A));
    }

    private static Map<Character, Integer> knownAscii() {
        Map<Character, Integer> known = new LinkedHashMap<>();
        known.put(' ', 4);
        known.put('!', 2);
        known.put('"', 4);
        known.put('#', 6);
        known.put('$', 6);
        known.put('%', 6);
        known.put('&', 6);
        known.put('\'', 2);
        known.put('(', 4);
        known.put(')', 4);
        known.put('*', 4);
        known.put('+', 6);
        known.put(',', 2);
        known.put('-', 6);
        known.put('.', 2);
        known.put('/', 6);
        known.put('0', 6);
        known.put('1', 6);
        known.put(':', 2);
        known.put(';', 2);
        known.put('@', 7);
        known.put('A', 6);
        known.put('I', 4);
        known.put('W', 6);
        known.put('[', 4);
        known.put(']', 4);
        known.put('f', 5);
        known.put('i', 2);
        known.put('j', 6);
        known.put('k', 5);
        known.put('l', 3);
        known.put('r', 6);
        known.put('t', 4);
        known.put('|', 2);
        known.put('~', 7);
        return known;
    }
}
