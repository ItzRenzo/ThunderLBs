package exposed.thunder.thunderLBs.font;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several of these tests pin MiniMessage's behaviour rather than ours. Padding
 * silently mis-aligns if any of them changes upstream, so they are written to
 * fail the build instead.
 */
class TextMeasurerTest {
    private final GlyphWidthRegistry widths = GlyphWidthRegistry.createDefault();
    private final TextMeasurer measurer = new TextMeasurer(widths);

    @Test
    void measuresPlainAsciiAgainstTheVanillaTable() {
        assertEquals(0, measurer.measure("").width());
        assertEquals(6, measurer.measure("A").width());
        assertEquals(18, measurer.measure("AAA").width());
        assertEquals(4, measurer.measure(" ").width());
        assertEquals(2, measurer.measure(".").width());
        // "1. " -> '1' 6, '.' 2, ' ' 4
        assertEquals(12, measurer.measure("1. ").width());
    }

    @Test
    void colourTagsAreZeroWidth() {
        assertEquals(measurer.measure("AAA").width(),
                measurer.measure("<red>AAA</red>").width());
        assertEquals(measurer.measure("AAA").width(),
                measurer.measure("<color:#ff2929>A<white>A</white>A").width());
    }

    @Test
    void gradientsDoNotChangeWidth() {
        assertEquals(measurer.measure("Makeinen545").width(),
                measurer.measure("<gradient:#ff2929:#ffe600>Makeinen545</gradient>").width());
    }

    @Test
    void boldAddsOnePixelPerGlyph() {
        assertEquals(18, measurer.measure("AAA").width());
        assertEquals(21, measurer.measure("<bold>AAA</bold>").width());
        assertEquals(21, measurer.measure("<b>AAA</b>").width());
        assertEquals(21, measurer.measure("<b:true>AAA").width());
        // Bold is inherited by children and cancelled by an explicit false.
        assertEquals(21 + 18, measurer.measure("<bold>AAA<b:false>AAA</b></bold>").width());
    }

    @Test
    void italicAddsNothing() {
        // Vanilla's getAdvance(bold) only ever adds a pixel for bold; italic
        // shears the glyph in place and never moves the cursor. Counting even one
        // pixel for it would put a <pad:...> column somewhere the client does not
        // draw it -- so this is a pin on adding nothing, not an omission.
        assertEquals(18, measurer.measure("<italic>AAA</italic>").width());
        assertEquals(18, measurer.measure("<i>AAA</i>").width());
        assertEquals(21, measurer.measure("<bold><italic>AAA</italic></bold>").width());
        assertEquals(18, measurer.measure("<italic><b:false>AAA").width());
    }

    @Test
    void boldFalseMustBeSpelledLowercase() {
        // Adventure compares the literal case-sensitively, so <b:FALSE> leaves
        // bold ON. PadPass therefore emits lowercase <b:false>; if this ever
        // starts failing, the uppercase spelling became safe (or the tag stopped
        // parsing at all) and the pin should be revisited rather than deleted.
        assertEquals(21, measurer.measure("<bold>AAA</bold>").width());
        assertEquals(21, measurer.measure("<bold><b:FALSE>AAA").width());
    }

    @Test
    void resetClearsDecorationState() {
        assertEquals(21 + 18, measurer.measure("<bold>AAA<reset>AAA").width());
    }

    @Test
    void unknownTagsMeasureAsLiteralText() {
        // The whole padding design depends on this: MiniMessage renders a tag it
        // does not recognise as visible text, so <pad:...> has to be removed
        // before anything is measured or parsed.
        assertEquals(measurer.measure("\\<pad:150:.>").width(),
                measurer.measure("<pad:150:.>").width());
        assertTrue(measurer.measure("<pad:150:.>").width() > 0);
    }

    @Test
    void unmatchedClosingTagIsLiteralText() {
        assertEquals(measurer.measure("\\</bold>AAA").width(),
                measurer.measure("</bold>AAA").width());
    }

    @Test
    void hoverAndClickPayloadsAreNotMeasured() {
        assertEquals(measurer.measure("A").width(),
                measurer.measure("<hover:show_text:'WWWWWWWWWWWW'>A</hover>").width());
        assertEquals(measurer.measure("A").width(),
                measurer.measure("<click:run_command:'/a very long command'>A</click>").width());
    }

    @Test
    void quotedAngleBracketDoesNotEndATag() {
        assertEquals(measurer.measure("A").width(),
                measurer.measure("<hover:show_text:'a>b'>A</hover>").width());
    }

    @Test
    void escapedAngleBracketIsVisible() {
        assertEquals(0, measurer.measure("<red>").width(), "a recognised tag draws nothing");
        assertEquals(measurer.measurePlain("<red>", false), measurer.measure("\\<red>").width());
        assertTrue(measurer.measure("\\<red>").width() > measurer.measure("red").width(),
                "the angle brackets are drawn too");
    }

    @Test
    void newlineResetsTheCursorAndTheWidestLineWins() {
        TextMeasurer.Measurement measurement = measurer.measure("AAA\nA");

        assertEquals(18, measurement.width());
        assertEquals(6, measurement.lastLineWidth());
        assertEquals(2, measurement.lineCount());
    }

    @Test
    void surrogatePairsCountAsOneGlyph() {
        String emoji = new String(Character.toChars(0x1F30A));

        assertEquals(2, emoji.length(), "guard: this really is a surrogate pair");
        assertEquals(widths.advance(0x1F30A, false), measurer.measure(emoji).width());
    }

    @Test
    void unknownGlyphsAreMeasuredButReportedInexact() {
        String priv = new String(Character.toChars(0xE001));

        TextMeasurer.Measurement measurement = measurer.measure(priv);

        assertEquals(FallbackGlyphWidths.DEFAULT_ADVANCE, measurement.width());
        assertFalse(measurement.exact());
        assertTrue(measurer.measure("A").exact());
    }

    @Test
    void nonDefaultFontIsReportedInexact() {
        assertFalse(measurer.measure("<font:minecraft:uniform>AAA</font>").exact());
        assertTrue(measurer.measure("<red>AAA</red>").exact());
    }

    @Test
    void clientResolvedComponentsAreReportedInexact() {
        assertFalse(measurer.measure(Component.translatable("block.minecraft.stone")).exact());
        assertFalse(measurer.measure(Component.keybind("key.jump")).exact());
        assertTrue(measurer.measure(Component.text("AAA")).exact());
    }

    @Test
    void unclosedTagDoesNotThrow() {
        assertDoesNotThrow(() -> measurer.measure("<red"));
        assertDoesNotThrow(() -> measurer.measure("<"));
        assertDoesNotThrow(() -> measurer.measure("<gradient:"));
        assertDoesNotThrow(() -> measurer.measure("<hover:show_text:'unclosed"));
    }

    @Test
    void measurePlainIgnoresTagSyntax() {
        assertEquals(measurer.measure("\\<red>").width(), measurer.measurePlain("<red>", false));
        assertEquals(18, measurer.measurePlain("AAA", false));
        assertEquals(21, measurer.measurePlain("AAA", true));
        assertEquals(0, measurer.measurePlain("", false));
    }

    @Test
    void isMeasurableRejectsGlyphsNoProviderKnows() {
        assertTrue(measurer.isMeasurable("Makeinen545"));
        assertTrue(measurer.isMeasurable("...."));
        assertFalse(measurer.isMeasurable(new String(Character.toChars(0xE001))));
    }
}
