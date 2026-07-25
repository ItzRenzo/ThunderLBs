package exposed.thunder.thunderLBs.placeholder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderPatternsTest {
    private static final String DEFAULT_ROW =
            "<color:%color%>%position%</color> <white>%player%</white>"
                    + " <dark_gray>→</dark_gray> <color:%color%>%value%</color>";

    @Test
    void theBuiltInTokensAreNotPlaceholders() {
        // None of them carries an underscore, which is the same rule PlaceholderAPI
        // applies -- so the shipped formats resolve to nothing and cost nothing.
        assertFalse(PlaceholderPatterns.mayContain(DEFAULT_ROW));
        assertFalse(PlaceholderPatterns.mayContain("%title% %icon% %rank% %holder%"));
        assertSame(DEFAULT_ROW, PlaceholderPatterns.resolve(DEFAULT_ROW, placeholder -> "!"));
    }

    @Test
    void findsRealPlaceholdersAlongsideTheTokens() {
        assertTrue(PlaceholderPatterns.mayContain("<white>%vault_prefix%%player%</white>"));
        assertTrue(PlaceholderPatterns.mayContain("%server_online%"));
    }

    @Test
    void anIdentifierNeedsAnUnderscorePastItsFirstCharacter() {
        assertFalse(PlaceholderPatterns.mayContain("%_leading%"));
        assertFalse(PlaceholderPatterns.mayContain("%noseparator%"));
        assertTrue(PlaceholderPatterns.mayContain("%a_%"));
    }

    @Test
    void anEmptySpanIsNotAMatchAndItsClosingPercentOpensTheNext() {
        // The regex retries one character along rather than skipping to the next
        // percent, so "%%x_y%" really does contain "%x_y%".
        assertTrue(PlaceholderPatterns.mayContain("%%vault_prefix%"));
        assertEquals("%!", PlaceholderPatterns.resolve("%%vault_prefix%", placeholder -> "!"));
    }

    @Test
    void anUnpairedPercentSwallowsWhatFollowsJustAsPlaceholderApiDoes() {
        // "100% %vault_rank%" matches "% %" first, which is no placeholder, and the
        // rest is left with a lone opener. Matching that quirk is the point: the
        // pattern has to mean the same thing here as it does to PlaceholderAPI.
        assertFalse(PlaceholderPatterns.mayContain("100% %vault_rank%"));
    }

    @Test
    void replacesOnlyResolvablePlaceholdersAndKeepsEverythingElse() {
        List<String> seen = new ArrayList<>();
        String resolved = PlaceholderPatterns.resolve(
                "<white>%vault_prefix%%player%</white> %server_online%",
                placeholder -> {
                    seen.add(placeholder);
                    return placeholder.toUpperCase(Locale.ROOT);
                });

        assertEquals(List.of("%vault_prefix%", "%server_online%"), seen);
        assertEquals("<white>%VAULT_PREFIX%%player%</white> %SERVER_ONLINE%", resolved);
    }

    @Test
    void anUninstalledExpansionLeavesItsPlaceholderVisible() {
        // Both the "expansion echoed the request back" case and the "no expansion
        // at all" case. Showing %vault_prefix% is a diagnosis; a silent gap is not.
        assertSame(DEFAULT_ROW, PlaceholderPatterns.resolve(DEFAULT_ROW, placeholder -> null));
        String pattern = "%vault_prefix%Alice";
        assertSame(pattern, PlaceholderPatterns.resolve(pattern, placeholder -> placeholder));
        assertSame(pattern, PlaceholderPatterns.resolve(pattern, placeholder -> null));
    }

    @Test
    void stripRemovesPlaceholdersSoTheyAreNotMeasuredAsInk() {
        assertEquals("<white>%player%</white>",
                PlaceholderPatterns.strip("<white>%vault_prefix%%player%</white>"));
        assertSame(DEFAULT_ROW, PlaceholderPatterns.strip(DEFAULT_ROW));
    }

    @Test
    void nullIsCarriedThroughRatherThanThrowing() {
        assertFalse(PlaceholderPatterns.mayContain(null));
        assertEquals(null, PlaceholderPatterns.strip(null));
    }
}
