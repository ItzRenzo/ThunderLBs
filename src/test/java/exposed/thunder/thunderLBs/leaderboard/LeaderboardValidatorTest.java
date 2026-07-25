package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.font.GlyphWidthRegistry;
import exposed.thunder.thunderLBs.font.PadMode;
import exposed.thunder.thunderLBs.font.PadPass;
import exposed.thunder.thunderLBs.font.PadSettings;
import exposed.thunder.thunderLBs.font.RowNormalizer;
import exposed.thunder.thunderLBs.font.TextMeasurer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardValidatorTest {
    private static final TextMeasurer MEASURER = new TextMeasurer(GlyphWidthRegistry.createDefault());

    @Test
    void aPaddedRowNeedsMoreThanTheColumnItPadsTo() {
        // The reason the stock line-width and the stock padding target used to
        // fight each other: the column is only where the value STARTS, so the
        // width a board actually needs is the target plus everything drawn after
        // it -- here a nine-digit value.
        LeaderboardFormatting formatting = padded("<white>%player%</white><pad:150:.>%value%",
                PadSettings.defaults());
        int suggested = LeaderboardValidator.suggestedLineWidth(MEASURER, formatting);
        assertTrue(suggested > 150, "suggested " + suggested + " does not clear the 150px column");
        assertTrue(suggested <= PluginConfig.DEFAULT_LINE_WIDTH,
                "suggested " + suggested + " exceeds the shipped default of "
                        + PluginConfig.DEFAULT_LINE_WIDTH + ", so the stock config still wraps");
    }

    @Test
    void theSuggestionIsMeasuredAgainstTheWorstCaseNameAndValue() {
        // A suggestion has to survive real data, not the short stand-ins a
        // warning uses, or the number it prints would itself need raising the
        // first time a long name shows up.
        LeaderboardFormatting shortValue = plain("<white>%player%</white> %value%");
        int suggested = LeaderboardValidator.suggestedLineWidth(MEASURER, shortValue);
        int actualShort = MEASURER.measure("<white>Bo</white> 1").width();
        assertTrue(suggested > actualShort * 3,
                "suggested " + suggested + " looks measured from short stand-ins, not worst case");
    }

    @Test
    void theSuggestionRoundsUpAndNeverDropsBelowTheMinimum() {
        LeaderboardFormatting tiny = plain("%value%");
        int suggested = LeaderboardValidator.suggestedLineWidth(MEASURER, tiny);
        assertEquals(0, suggested % 10, "suggested " + suggested + " is not rounded to a whole 10px step");
        assertTrue(suggested >= PluginConfig.MIN_LINE_WIDTH, "suggested " + suggested + " is below the clamp");
    }

    @Test
    void aFixedRowWidthRaisesTheSuggestionOnItsOwn() {
        // row-width pads every row out to a fixed pixel width, so the board is
        // that wide even when no single format measures that far.
        PadSettings wide = new PadSettings(true, 150, ".", PadMode.FILL, "dark_gray", 900);
        LeaderboardFormatting formatting = padded("<white>%player%</white><pad:150:.>%value%", wide);
        assertTrue(LeaderboardValidator.suggestedLineWidth(MEASURER, formatting) >= 900);
    }

    @Test
    void withoutAMeasurerThereIsNothingToSuggest() {
        assertEquals(-1, LeaderboardValidator.suggestedLineWidth(null, plain("%player%")));
        assertEquals(-1, LeaderboardValidator.suggestedLineWidth(MEASURER, null));
    }

    private static LeaderboardFormatting plain(String rowPattern) {
        return padded(rowPattern, PadSettings.defaults());
    }

    private static LeaderboardFormatting padded(String rowPattern, PadSettings settings) {
        return new LeaderboardFormatting("", rowPattern, rowPattern, "", "", List.of(), Map.of(),
                null, PadPass.create(MEASURER, settings), RowNormalizer.create(MEASURER, settings));
    }
}
