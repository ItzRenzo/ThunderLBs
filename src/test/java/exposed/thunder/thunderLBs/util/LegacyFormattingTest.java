package exposed.thunder.thunderLBs.util;

import exposed.thunder.thunderLBs.font.GlyphWidthRegistry;
import exposed.thunder.thunderLBs.font.TextMeasurer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFormattingTest {
    private final TextMeasurer measurer = new TextMeasurer(GlyphWidthRegistry.createDefault());

    @Test
    void textWithoutCodesIsReturnedUnchanged() {
        String plain = "<red>already MiniMessage</red>";
        // Same instance, not merely equal: a placeholder that speaks MiniMessage
        // must reach the renderer without being rewritten or copied.
        assertSame(plain, LegacyFormatting.toMiniMessage(plain));
        assertFalse(LegacyFormatting.hasLegacyCodes(plain));
    }

    @Test
    void convertsAmpersandAndSectionColours() {
        assertEquals("<color:gray>[Member] </color>", LegacyFormatting.toMiniMessage("&7[Member] "));
        assertEquals("<color:gray>[Member] </color>", LegacyFormatting.toMiniMessage("§7[Member] "));
        assertEquals("<color:white>W</color>", LegacyFormatting.toMiniMessage("&fW"));
    }

    @Test
    void convertsDecorations() {
        assertEquals("<color:red><bold>ADMIN</bold></color>", LegacyFormatting.toMiniMessage("&c&lADMIN"));
        assertEquals("<italic>tilted</italic>", LegacyFormatting.toMiniMessage("&otilted"));
        assertEquals("<bold><italic>both</italic></bold>", LegacyFormatting.toMiniMessage("&l&oboth"));
    }

    @Test
    void aColourClearsDecorationsTheWayVanillaDoes() {
        // §l then §c is bold-then-red in the file, but the client drops the bold
        // at the colour. Emitting <bold><red> would be wider than what is drawn.
        assertEquals("<color:red>X</color>", LegacyFormatting.toMiniMessage("&l&cX"));
        assertEquals("<color:gray><bold>a</bold></color><color:red>b</color>",
                LegacyFormatting.toMiniMessage("&7&la&cb"));
    }

    @Test
    void resetClearsColourAndDecorations() {
        assertEquals("<color:red><bold>Red</bold></color>Plain",
                LegacyFormatting.toMiniMessage("&c&lRed&rPlain"));
    }

    @Test
    void convertsBothHexSpellings() {
        assertEquals("<color:#ff8800>Hi</color>", LegacyFormatting.toMiniMessage("&#FF8800Hi"));
        assertEquals("<color:#ff8800>Hi</color>", LegacyFormatting.toMiniMessage("§x§F§F§8§8§0§0Hi"));
        // Not six hex digits, so not a colour: the & stays literal text.
        assertEquals("&#GGGGGGx", LegacyFormatting.toMiniMessage("&#GGGGGGx"));
    }

    @Test
    void anAmpersandThatIsNotACodeStaysLiteral() {
        assertEquals("Rock & Roll", LegacyFormatting.toMiniMessage("Rock & Roll"));
        assertEquals("trailing &", LegacyFormatting.toMiniMessage("trailing &"));
    }

    @Test
    void emitsNoEmptyTagPairs() {
        // Codes with nothing between them used to leave <bold></bold> behind. Zero
        // width either way, but it is a cache key downstream and it shows up in
        // every debug dump, so the shape is pinned.
        assertEquals("", LegacyFormatting.toMiniMessage("&c&l&r"));
        assertEquals("<color:blue>x</color>", LegacyFormatting.toMiniMessage("&c&a&9x"));
    }

    @Test
    void outputIsBalancedSoStyleCannotLeakIntoTheRestOfTheRow() {
        String row = LegacyFormatting.toMiniMessage("&c&lVIP ") + "Alice";
        assertEquals(measurer.measure("Alice").width(),
                measurer.measure(row).width() - measurer.measure("<bold>VIP </bold>").width());
        assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(row));
    }

    @Test
    void aBoldCodeIsMeasuredAsBoldRatherThanAsTwoCharactersOfInk() {
        // The whole reason this class exists. Left as "&lAAA" the measurer sees
        // five glyphs and no bold; converted, it sees three bold ones -- which is
        // what the client draws, and so where a <pad:...> column has to be put.
        assertEquals(21, measurer.measure(LegacyFormatting.toMiniMessage("&lAAA")).width());
        assertTrue(measurer.measure("&lAAA").width() > 21);
    }

    @Test
    void italicCostsNothingButMustStillParseAsFormatting() {
        // Vanilla shears an italic glyph without advancing the cursor, so the
        // width is the plain one -- but only once "&o" is a tag rather than ink.
        assertEquals(18, measurer.measure(LegacyFormatting.toMiniMessage("&oAAA")).width());
        assertTrue(measurer.measure("&oAAA").width() > 18);
    }
}
