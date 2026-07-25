package exposed.thunder.thunderLBs.font;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowNormalizerTest {
    // 'A' is 6 px and ' ' is 4 px in the vanilla table, so a row of n A's is 6n
    // wide and every deficit below is checkable by hand.
    private static final int SPACE = 4;

    private final GlyphWidthRegistry widths = GlyphWidthRegistry.createDefault();
    private final TextMeasurer measurer = new TextMeasurer(widths);

    private RowNormalizer normalizer(int rowWidth) {
        return RowNormalizer.create(measurer, new PadSettings(
                true, 150, ".", PadMode.FILL, "dark_gray", rowWidth));
    }

    private List<Integer> widthsOf(List<String> rows) {
        List<Integer> measured = new ArrayList<>(rows.size());
        for (String row : rows) {
            measured.add(measurer.measure(row).width());
        }
        return measured;
    }

    @Test
    void autoWidensEveryRowToWithinOneSpaceOfTheWidest() {
        // 12, 60, 30 px.
        List<String> rows = List.of("AA", "AAAAAAAAAA", "AAAAA");

        List<Integer> result = widthsOf(normalizer(PadSettings.ROW_WIDTH_AUTO).normalize(rows));

        assertEquals(60, result.get(1), "the widest row is the target and must not move");
        for (int width : result) {
            // Floor, so a row can fall short by up to one space; the left edge is
            // therefore off by at most half of that. Overshooting instead would put
            // the column past its target, which is worse.
            assertTrue(width > 60 - SPACE && width <= 60,
                    "row measured " + width + " px, expected (" + (60 - SPACE) + ", 60]");
        }
    }

    @Test
    void aFixedWidthIsUsedInsteadOfTheWidestRow() {
        List<String> rows = List.of("AA", "AAAAA");

        List<Integer> result = widthsOf(normalizer(200).normalize(rows));

        for (int width : result) {
            assertTrue(width > 200 - SPACE && width <= 200, "row measured " + width + " px, expected ~200");
        }
    }

    @Test
    void aRowPastTheFixedWidthIsLeftAloneRatherThanTrimmed() {
        // 60 px, well past the 20 px target: overflow pushes, exactly as a pad does.
        List<String> rows = List.of("AAAAAAAAAA", "AA");

        List<String> result = normalizer(20).normalize(rows);

        assertSame(rows.get(0), result.get(0));
        assertEquals(60, measurer.measure(result.get(0)).width());
    }

    @Test
    void rowsThatAreAlreadyEqualAreReturnedUnchanged() {
        List<String> rows = List.of("AAA", "BBB");

        // Same list instance, so nothing downstream re-deserializes for nothing.
        assertSame(rows, normalizer(PadSettings.ROW_WIDTH_AUTO).normalize(rows));
    }

    @Test
    void offAndDisabledPaddingProduceANoOpNormalizer() {
        List<String> rows = List.of("AA", "AAAAA");

        assertSame(RowNormalizer.DISABLED, normalizer(PadSettings.ROW_WIDTH_OFF));
        assertSame(RowNormalizer.DISABLED, RowNormalizer.create(measurer, new PadSettings(
                false, 150, ".", PadMode.FILL, "dark_gray", PadSettings.ROW_WIDTH_AUTO)));
        assertSame(RowNormalizer.DISABLED, RowNormalizer.create(null, PadSettings.defaults()));
        assertSame(rows, RowNormalizer.DISABLED.normalize(rows));
    }

    @Test
    void emptyAndSingleRowPagesAreHandled() {
        RowNormalizer normalizer = normalizer(PadSettings.ROW_WIDTH_AUTO);
        List<String> none = List.of();
        List<String> one = List.of("AAA");

        assertSame(none, normalizer.normalize(none));
        // Nothing to align against, so auto's target is the row itself.
        assertSame(one, normalizer.normalize(one));
    }

    @Test
    void fillerIsInvisibleAndDoesNotDisturbTheRow() {
        List<String> rows = List.of("<white>AA</white>", "<white>AAAAAAAAAA</white>");

        List<String> result = normalizer(PadSettings.ROW_WIDTH_AUTO).normalize(rows);

        assertNotEquals(rows.get(0), result.get(0));
        assertTrue(result.get(0).endsWith("</b>"), "filler must close the bold override it opens");
        for (String row : result) {
            assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(row));
            // Whitespace only: the visible text of the row is exactly what it was.
            String plain = PlainTextComponentSerializer.plainText()
                    .serialize(MiniMessage.miniMessage().deserialize(row));
            assertTrue(plain.stripTrailing().matches("A+"), "unexpected visible text '" + plain + "'");
        }
    }

    @Test
    void fillerIsNotAffectedByAmbientBold() {
        // Bold makes a space 5 px, not 4, so the arithmetic only holds if the pass
        // forces bold off. Lowercase false matters: Adventure's isFalse() is
        // case-sensitive, so <b:FALSE> would leave bold on.
        List<String> rows = List.of("<bold>AA", "<bold>AAAAAAAAAA");

        List<String> result = normalizer(PadSettings.ROW_WIDTH_AUTO).normalize(rows);
        // Ten bold A's are 70 px.
        int target = measurer.measure(result.get(1)).width();
        int padded = measurer.measure(result.get(0)).width();

        assertEquals(70, target);
        assertTrue(padded > target - SPACE && padded <= target,
                "bold row padded to " + padded + " px, expected ~" + target);
    }

    @Test
    void normalisationIsDeterministic() {
        List<String> rows = List.of("AA", "AAAAA", "AAAAAAAAAA");
        RowNormalizer normalizer = normalizer(PadSettings.ROW_WIDTH_AUTO);

        assertEquals(normalizer.normalize(rows), normalizer.normalize(rows));
    }

    @Test
    void rowWidthSpellingsRoundTrip() {
        assertEquals(PadSettings.ROW_WIDTH_AUTO, PadSettings.parseRowWidth("auto", 99));
        assertEquals(PadSettings.ROW_WIDTH_AUTO, PadSettings.parseRowWidth(" AUTO ", 99));
        assertEquals(PadSettings.ROW_WIDTH_OFF, PadSettings.parseRowWidth("off", 99));
        assertEquals(PadSettings.ROW_WIDTH_OFF, PadSettings.parseRowWidth("0", 99));
        assertEquals(340, PadSettings.parseRowWidth("340", 99));
        assertEquals(PadSettings.MAX_TARGET, PadSettings.parseRowWidth("999999", 99));
        // Unreadable values keep whatever was inherited rather than failing a load.
        assertEquals(99, PadSettings.parseRowWidth("sideways", 99));
        assertEquals(99, PadSettings.parseRowWidth(null, 99));

        assertEquals("auto", new PadSettings(true, 150, ".", PadMode.FILL, "gray",
                PadSettings.ROW_WIDTH_AUTO).rowWidthName());
        assertEquals("off", new PadSettings(true, 150, ".", PadMode.FILL, "gray",
                PadSettings.ROW_WIDTH_OFF).rowWidthName());
        assertEquals("340", new PadSettings(true, 150, ".", PadMode.FILL, "gray", 340).rowWidthName());
    }
}
