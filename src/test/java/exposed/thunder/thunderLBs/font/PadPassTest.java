package exposed.thunder.thunderLBs.font;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadPassTest {
    // 'A' is 6 px, '.' is 2 px, ' ' is 4 px in the vanilla table, which keeps the
    // arithmetic in these tests checkable by hand.
    private static final String COLOR = "#ff2929";

    private final GlyphWidthRegistry widths = GlyphWidthRegistry.createDefault();
    private final TextMeasurer measurer = new TextMeasurer(widths);
    private final PadPass pass = PadPass.create(measurer, PadSettings.defaults());

    private int columnOf(String padded) {
        return measurer.measure(padded).lastLineWidth();
    }

    @Test
    void patternsWithoutAPadTagAreReturnedUnchanged() {
        String plain = "<color:%color%>1.</color> <white>Bob</white> <dark_gray>→</dark_gray>";

        assertSame(plain, pass.apply(plain, COLOR));
        assertSame(plain, pass.apply(plain, COLOR, new ArrayList<>()));
        // A near miss must not be rewritten either.
        assertSame("<padding>x", pass.apply("<padding>x", COLOR));
    }

    @Test
    void fillReachesTheTargetColumnExactly() {
        assertEquals(30, columnOf(pass.apply("AAA<pad:30:.>", COLOR)));
        assertEquals(30, columnOf(pass.apply("AAAA<pad:30:.>", COLOR)));
        assertEquals(6, columnOf(pass.apply("A<pad:7:.>", COLOR)),
                "one pixel short of the column is unfixable with a 2 px filler");
    }

    @Test
    void fillFloorsRatherThanOvershooting() {
        // Target 31 with a 2 px filler from column 18: 6 dots reach 30, a 7th would pass 31.
        String padded = pass.apply("AAA<pad:31:.>", COLOR);

        assertEquals(30, columnOf(padded));
        assertEquals(6, padded.chars().filter(c -> c == '.').count());
    }

    @Test
    void emitsTheExpectedMiniMessage() {
        assertEquals("AAA<b:false><i:false><color:dark_gray>......</color></i></b>X",
                pass.apply("AAA<pad:30:.>X", COLOR));
    }

    @Test
    void fillerStyleAddsNoWidthOfItsOwn() {
        assertEquals(30 + measurer.measurePlain("X", false),
                columnOf(pass.apply("AAA<pad:30:.>X", COLOR)));
    }

    @Test
    void overflowEmitsNoFillerAndNoStyleTags() {
        assertEquals("AAAAAAAAAAX", pass.apply("AAAAAAAAAA<pad:30:.>X", COLOR));
        assertEquals("AAAAAX", pass.apply("AAAAA<pad:30:.>X", COLOR), "exactly at the column");
    }

    @Test
    void columnsLineUpAcrossRowsOfDifferentWidth() {
        int shortName = columnOf(pass.apply("1. Bob<pad:150:.>", COLOR));
        int longName = columnOf(pass.apply("2. Alexander<pad:150:.>", COLOR));

        // Both land within one filler unit of the column; the residue is the
        // parity of the text before the pad and is physically unfixable.
        assertTrue(shortName > 150 - 2 && shortName <= 150, "short name column: " + shortName);
        assertTrue(longName > 150 - 2 && longName <= 150, "long name column: " + longName);
        assertTrue(Math.abs(shortName - longName) <= 1);
    }

    @Test
    void relativeTargetsMeasureFromTheCursor() {
        assertEquals(30, columnOf(pass.apply("AAA<pad:+12:.>", COLOR)));
        assertEquals(36, columnOf(pass.apply("AAAA<pad:+12:.>", COLOR)));
        assertEquals(18, columnOf(pass.apply("AAA<pad:+0:.>", COLOR)));
    }

    @Test
    void rightAlignPutsTheSegmentEdgeOnTheColumn() {
        // Column 18 + 24 px of dots + a 18 px segment = 60.
        assertEquals(60, columnOf(pass.apply("AAA<pad:60:.:right>AAA", COLOR)));
        assertEquals(60, columnOf(pass.apply("AAA<pad:60:.:right>AAAA", COLOR)));
        // Nothing fits: the segment simply overflows past the column.
        assertEquals("AAAAAAAAAA", pass.apply("AAAAA<pad:30:.:right>AAAAA", COLOR));
    }

    @Test
    void rightAlignIsScopedToTheRunBeforeTheNextPad() {
        // "A" 6 px + 20 px of spaces puts the first "AA" edge at 38, inside (36, 40],
        // and the second run is then pushed to end at 78, inside (76, 80].
        String padded = pass.apply("A<pad:40:' ':right>AA<pad:80:' ':right>AA", COLOR);

        assertEquals(78, columnOf(padded));
        assertFalse(padded.contains("<pad"));
    }

    @Test
    void aRightPadAfterAFillPadAccountsForTheEarlierFiller() {
        // Fill to 30, the next run ends at 42, then filler puts the last run flush against 80.
        assertEquals(78, columnOf(pass.apply("A<pad:30:.>AA<pad:80:' ':right>AA", COLOR)));
    }

    @Test
    void configuredDefaultsFillInOmittedArguments() {
        PadPass custom = PadPass.create(measurer, new PadSettings(true, 60, "-", PadMode.FILL, "gray"));

        assertEquals(60, columnOf(custom.apply("AAA<pad>", COLOR)));
        assertEquals("AAA<b:false><i:false><color:gray>-------</color></i></b>",
                custom.apply("AAA<pad>", COLOR));
        // Defaults are per argument, so a target-only tag keeps the configured filler.
        assertEquals("AAA<b:false><i:false><color:gray>--</color></i></b>",
                custom.apply("AAA<pad:30>", COLOR));
    }

    @Test
    void theHolderColourTokenResolvesToTheBoardColour() {
        assertTrue(pass.apply("AAA<pad:30:.:fill:%color%>", COLOR).contains("<color:#ff2929>"));
        assertTrue(pass.apply("AAA<pad:30:.:fill:%color%>", "#ff2929:#ffe600")
                .contains("<gradient:#ff2929:#ffe600>"), "gradients wrap as gradients");
    }

    @Test
    void fillerIsEscapedSoItCannotBecomeMarkup() {
        String padded = pass.apply("AAA<pad:30:'<'>", COLOR);

        assertTrue(padded.contains("\\<"), padded);
        assertFalse(PadTagScanner.mayContain(padded));
        // '<' is 5 px, so two of them fit in the 12 px gap and the rest is lost to the floor.
        assertEquals(28, columnOf(padded));
    }

    @Test
    void malformedTagsAreStrippedRatherThanRendered() {
        String[] bad = {
            "<pad:abc>",
            "<pad:0>",
            "<pad:99999>",
            "<pad:-40>",
            "<pad:150:>",
            "<pad:150:.:sideways>",
            "<pad:150:.:fill:notacolour>",
        };

        for (String tag : bad) {
            String padded = pass.apply("A" + tag + "B", COLOR);

            assertEquals("AB", padded, tag);
            assertFalse(padded.contains("<pad"), tag);
        }
    }

    @Test
    void malformedTagsAreReported() {
        assertTrue(diagnose("A<pad:abc>B").get(0).contains("not a whole number"));
        assertTrue(diagnose("A<pad:0>B").get(0).contains("outside"));
        assertTrue(diagnose("A<pad:150:>B").get(0).contains("filler is empty"));
        assertTrue(diagnose("A<pad:150:.:sideways>B").get(0).contains("unknown pad mode"));
        assertTrue(diagnose("A<pad:150:.:fill:notacolour>B").get(0).contains("not a colour"));
        assertTrue(diagnose("A<pad:150:.:fill:gray:extra>B").get(0).contains("more than four"));
        assertTrue(diagnose("AAA<pad:150>").isEmpty(), "a well-formed tag reports nothing");
    }

    @Test
    void zeroWidthFillerIsRejected() {
        String zeroWidth = new String(Character.toChars(0x200C));
        String padded = pass.apply("A<pad:150:'" + zeroWidth + "'>B", COLOR);

        assertEquals("AB", padded);
        assertTrue(diagnose("A<pad:150:'" + zeroWidth + "'>B").get(0).contains("zero pixels wide"));
    }

    @Test
    void anUnterminatedTagIsLeftAloneButReported() {
        // MiniMessage does not read this as a tag either -- the quote swallows the
        // '>' -- so it renders literally and the operator has to be told.
        String input = "A<pad:150:'unclosed>B";

        assertSame(input, pass.apply(input, COLOR));
        assertTrue(diagnose(input).get(0).contains("not a complete pad tag"));
    }

    @Test
    void unknownGlyphsBeforeAPadAreReported() {
        String priv = new String(Character.toChars(0xE001));

        assertTrue(diagnose(priv + "<pad:150:.>").get(0).contains("unknown width"));
    }

    @Test
    void tagsBeyondTheLimitAreStrippedAndReported() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < PadPass.MAX_PADS + 2; i++) {
            input.append("A<pad:+10:.>");
        }
        String padded = pass.apply(input.toString(), COLOR);
        List<String> diagnostics = diagnose(input.toString());

        assertFalse(padded.contains("<pad"));
        assertEquals(2, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.get(0).contains("more than " + PadPass.MAX_PADS));
    }

    @Test
    void fillerRepeatsAreCapped() {
        String padded = pass.apply("<pad:4096:.>", COLOR);

        assertEquals(PadPass.MAX_REPEATS, padded.chars().filter(c -> c == '.').count());
    }

    @Test
    void applyingTwiceChangesNothingAndIsDeterministic() {
        String input = "1. <white>Bob</white><pad:150:.><color:%color%>x50";
        String once = pass.apply(input, COLOR);

        assertSame(once, pass.apply(once, COLOR), "the output has no pad tag left to expand");
        assertEquals(once, pass.apply(input, COLOR));
        assertEquals(once, PadPass.create(measurer, PadSettings.defaults()).apply(input, COLOR),
                "a fresh instance must agree, so the string is a safe cache key");
    }

    @Test
    void theDisabledPassStripsTagsWithoutInsertingFiller() {
        assertEquals("AB", PadPass.DISABLED.apply("A<pad:150:.>B", COLOR));
        assertFalse(PadPass.DISABLED.enabled());
        assertSame("A", PadPass.DISABLED.apply("A", COLOR));
        assertSame(PadPass.DISABLED, PadPass.create(measurer,
                new PadSettings(false, 150, ".", PadMode.FILL, "dark_gray")));
        assertSame(PadPass.DISABLED, PadPass.create(null, PadSettings.defaults()));
    }

    @Test
    void everyResultIsStillValidMiniMessage() {
        String[] inputs = {
            "AAA<pad:30:.>X",
            "1. <white>Bob</white><pad:150:.><color:#ff2929>x50",
            "<gradient:#ff2929:#ffe600>AAA<pad:60:.:right>AAA</gradient>",
            "<bold>AAA<pad:+20:'<'></bold>",
            "A<pad:40:' ':right>BB<pad:80:' ':right>CC",
            "A<pad:abc>B",
            "A<pad:150:.:fill:%color%>B",
            "<pad:4096:.>",
        };
        MiniMessage miniMessage = MiniMessage.miniMessage();

        for (String input : inputs) {
            String padded = pass.apply(input, COLOR);
            assertDoesNotThrow(() -> miniMessage.deserialize(padded), padded);
        }
    }

    @Test
    void boldTextBeforeAPadIsMeasuredAsBold() {
        // Bold adds a pixel per glyph, so "AAA" reaches 21 rather than 18 and only
        // four dots fit in the 9 px that are left.
        assertEquals(29, columnOf(pass.apply("<bold>AAA</bold><pad:30:.>", COLOR)));
        assertEquals(29, columnOf(pass.apply("<bold>AAA<pad:30:.>", COLOR)),
                "the filler itself is forced non-bold, so it stays 2 px per dot");
    }

    private List<String> diagnose(String input) {
        List<PadDiagnostic> diagnostics = new ArrayList<>();
        pass.apply(input, COLOR, diagnostics);
        return diagnostics.stream().map(PadDiagnostic::message).toList();
    }

    /** Severities as reported, so the validator's ERROR/WARNING mapping is pinned. */
    private List<PadDiagnostic.Severity> severities(String input) {
        List<PadDiagnostic> diagnostics = new ArrayList<>();
        pass.apply(input, COLOR, diagnostics);
        return diagnostics.stream().map(PadDiagnostic::severity).toList();
    }

    @Test
    void unusableTagsAreErrorsAndAppliedOnesAreOnlyWarnings() {
        assertEquals(List.of(PadDiagnostic.Severity.ERROR), severities("A<pad:abc>B"));
        assertEquals(List.of(PadDiagnostic.Severity.ERROR), severities("A<pad:150:>B"));
        assertEquals(List.of(PadDiagnostic.Severity.ERROR), severities("A<pad:150:.:sideways>B"));
        assertEquals(List.of(PadDiagnostic.Severity.ERROR), severities("A<pad:150:.:fill:notacolour>B"));
        // The pad still lands; only the extra argument is ignored.
        assertEquals(List.of(PadDiagnostic.Severity.WARNING), severities("A<pad:150:.:fill:gray:extra>B"));
        assertEquals(List.of(PadDiagnostic.Severity.WARNING), severities("A<pad:150:'unclosed>B"));
    }
}
