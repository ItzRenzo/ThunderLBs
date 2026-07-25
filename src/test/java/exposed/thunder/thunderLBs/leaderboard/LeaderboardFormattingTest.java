package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.font.GlyphWidthRegistry;
import exposed.thunder.thunderLBs.font.PadMode;
import exposed.thunder.thunderLBs.font.PadPass;
import exposed.thunder.thunderLBs.font.PadSettings;
import exposed.thunder.thunderLBs.font.RowNormalizer;
import exposed.thunder.thunderLBs.font.TextMeasurer;
import exposed.thunder.thunderLBs.placeholder.PlaceholderPatterns;
import exposed.thunder.thunderLBs.util.LegacyFormatting;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardFormattingTest {
    private final LeaderboardFormatting formatting = new LeaderboardFormatting(
            "<%color%>%icon% %title%",
            "%position% <%color%>%player%: %value% %icon%",
            "%position%/%rank% <%color%>%player%: %value% %icon%",
            "background",
            "foreground");

    @Test
    void rendersCompiledTemplates() {
        assertEquals("<#fff>* Kills", formatting.renderTitle("#fff", "*", "Kills"));
        assertEquals("1 <#fff>Alice: 42 *", formatting.renderRow("#fff", "1", "Alice", "42", "*"));
        assertEquals("#2/2 <#fff>Alice: 42 *",
                formatting.renderRelative("#fff", "#2", "2", "Alice", "42", "*"));
    }

    @Test
    void leavesUnknownPlaceholdersUntouched() {
        LeaderboardFormatting custom = new LeaderboardFormatting(
                "%title% %custom%", "%player%", "%player%", "", "");
        assertEquals("Title %custom%", custom.renderTitle("#fff", "", "Title"));
    }

    @Test
    void rendersHolderGradientsAcrossTitlesRowsAndRelativeRows() {
        LeaderboardFormatting defaults = new LeaderboardFormatting(
                "<color:%color%>%icon%</color> <bold><color:%color%>%title%</color></bold>",
                "<color:%color%>%position%</color> %player% <color:%color%>%value%</color>",
                "<color:%color%>%position%</color> %player% <color:%color%>%value%</color>",
                "", "");
        String gradient = "#FF0000:#00FF00:#0000FF";

        String title = defaults.renderTitle(gradient, "*", "Kills");
        String row = defaults.renderRow(gradient, "1", "Alice", "42", "*");
        String relative = defaults.renderRelative(gradient, "#2", "2", "Alice", "21", "*");

        assertEquals("<gradient:#ff0000:#00ff00:#0000ff>*</gradient> "
                + "<bold><gradient:#ff0000:#00ff00:#0000ff>Kills</gradient></bold>", title);
        assertEquals("<gradient:#ff0000:#00ff00:#0000ff>1</gradient> Alice "
                + "<gradient:#ff0000:#00ff00:#0000ff>42</gradient>", row);
        assertEquals("<gradient:#ff0000:#00ff00:#0000ff>#2</gradient> Alice "
                + "<gradient:#ff0000:#00ff00:#0000ff>21</gradient>", relative);
        MiniMessage miniMessage = MiniMessage.miniMessage();
        assertDoesNotThrow(() -> miniMessage.deserialize(title));
        assertDoesNotThrow(() -> miniMessage.deserialize(row));
        assertDoesNotThrow(() -> miniMessage.deserialize(relative));
    }

    private static final PluginConfig.Formatting GLOBAL = new PluginConfig.Formatting(
            "title", "row", "relative",
            List.of("①", "②", "③"),
            Map.of(1, "#FFD700", 2, "#C0C0C0"));

    @Test
    void inheritsGlobalOverridesWhenBoardHasNone() {
        LeaderboardFormatting plain = new LeaderboardFormatting("t", "r", "r", "", "");
        assertEquals(List.of("①", "②", "③"), plain.circledNumbers(GLOBAL));
        assertEquals("#FFD700", plain.rankColor(1, GLOBAL, "#FFFFFF"));
        assertEquals("#FFFFFF", plain.rankColor(3, GLOBAL, "#FFFFFF"));
    }

    @Test
    void boardOverridesWinPerPositionAndFallBackToGlobal() {
        LeaderboardFormatting board = new LeaderboardFormatting("t", "r", "r", "", "",
                List.of("#1", "#2", "#3"),
                Map.of(1, "#4be44f"));
        assertEquals(List.of("#1", "#2", "#3"), board.circledNumbers(GLOBAL));
        assertEquals("#4be44f", board.rankColor(1, GLOBAL, "#FFFFFF"));
        assertEquals("#C0C0C0", board.rankColor(2, GLOBAL, "#FFFFFF"));
        assertEquals("#FFFFFF", board.rankColor(3, GLOBAL, "#FFFFFF"));
    }

    @Test
    void serializesOverridesOnlyWhenPresent() {
        YamlConfiguration yaml = new YamlConfiguration();
        new LeaderboardFormatting("t", "r", "r", "bg", "fg")
                .serialize(yaml.createSection("plain"));
        assertFalse(yaml.isSet("plain.circled-numbers"));
        assertFalse(yaml.isSet("plain.rank-colors"));

        new LeaderboardFormatting("t", "r", "r", "bg", "fg", List.of("#1"), Map.of(1, "#4be44f"))
                .serialize(yaml.createSection("overridden"));
        assertEquals(List.of("#1"), yaml.getStringList("overridden.circled-numbers"));
        assertEquals("#4be44f", yaml.getString("overridden.rank-colors.1"));
    }

    /** A pad pass over the real vanilla widths, as the plugin builds it on reload. */
    private static LeaderboardFormatting padded(String rowPattern) {
        return padded(rowPattern, PadSettings.defaults());
    }

    private static LeaderboardFormatting padded(String rowPattern, PadSettings settings) {
        return new LeaderboardFormatting("t", rowPattern, rowPattern, "", "", List.of(), Map.of(),
                null, PadPass.create(MEASURER, settings), RowNormalizer.create(MEASURER, settings));
    }

    private static final TextMeasurer MEASURER = new TextMeasurer(GlyphWidthRegistry.createDefault());

    @Test
    void theValueColumnLinesUpAcrossNamesOfDifferentWidth() {
        LeaderboardFormatting board = padded("<white>%player%</white><pad:60:.>%value%");

        String narrow = board.renderRow("#fff", "1", "Bo", "42", "*");
        String wide = board.renderRow("#fff", "2", "Wilhelmina", "42", "*");

        int narrowColumn = columnBefore(narrow, "42");
        int wideColumn = columnBefore(wide, "42");
        assertEquals(narrowColumn, wideColumn);
        // A '.' is 2px wide, so flooring can leave the column at most 1px short.
        assertTrue(narrowColumn > 58 && narrowColumn <= 60, "column was " + narrowColumn);
    }

    @Test
    void aNameWiderThanTheTargetPushesTheValueWithoutTruncating() {
        LeaderboardFormatting board = padded("<white>%player%</white><pad:20:.>%value%");

        String row = board.renderRow("#fff", "1", "Wilhelmina", "42", "*");

        assertTrue(row.contains("Wilhelmina"), row);
        assertTrue(row.endsWith("42"), row);
        assertFalse(row.contains("."), "overflow must emit no filler at all: " + row);
        assertTrue(columnBefore(row, "42") > 20);
    }

    @Test
    void paddingSurvivesHolderGradientRewriting() {
        LeaderboardFormatting board = padded("<color:%color%>%player%</color><pad:80:.><color:%color%>%value%</color>");

        String row = board.renderRow("#FF0000:#0000FF", "1", "Alice", "42", "*");

        assertTrue(row.contains("<gradient:#ff0000:#0000ff>"), row);
        assertFalse(row.contains("<pad"), row);
        assertTrue(row.contains("<color:dark_gray>"), row);
        assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(row));
    }

    @Test
    void serializesPaddingOnlyWhenTheBoardOverridesIt() {
        YamlConfiguration yaml = new YamlConfiguration();

        padded("r").serialize(yaml.createSection("inherited"));
        assertFalse(yaml.isSet("inherited.padding"));

        new LeaderboardFormatting("t", "r", "r", "bg", "fg", List.of(), Map.of(),
                new PadSettings(true, 200, "-", PadMode.RIGHT, "gray", 340),
                PadPass.DISABLED, RowNormalizer.DISABLED)
                .serialize(yaml.createSection("overridden"));
        assertTrue(yaml.getBoolean("overridden.padding.enabled"));
        assertEquals(200, yaml.getInt("overridden.padding.target"));
        assertEquals("-", yaml.getString("overridden.padding.filler"));
        assertEquals("right", yaml.getString("overridden.padding.mode"));
        assertEquals("gray", yaml.getString("overridden.padding.style"));
        assertEquals("340", yaml.getString("overridden.padding.row-width"));
    }

    @Test
    void paddedRowsAreWidenedToOneWidthSoTheColumnLinesUpOnScreen() {
        LeaderboardFormatting board = padded("<white>%player%</white><pad:60:.>%value%");
        List<String> rows = List.of(
                board.renderRow("#fff", "1", "Bo", "42", "*"),
                board.renderRow("#fff", "2", "Wilhelmina", "4200000", "*"));

        List<String> normalized = board.normalizeRows(rows);

        // Without this the second row is far wider, and since a text display centres
        // its text on the anchor, the first row's column would sit further right on
        // screen despite both being at 60px within the row.
        int first = MEASURER.measure(normalized.get(0)).width();
        int second = MEASURER.measure(normalized.get(1)).width();
        assertTrue(Math.abs(first - second) < 4, "widths still differ: " + first + " vs " + second);
    }

    @Test
    void rightModeEndsEveryValueAtTheSameColumnHoweverWideTheValueIs() {
        // fill puts the START of the value at the column, so "---" (18px) reaches
        // 18px further right than "1" (6px). right puts the END there instead.
        LeaderboardFormatting board = padded("<color:%color%>%position%.</color> "
                + "<white>%player%</white><pad:150:.:right><color:%color%>%value%</color>");

        String narrowValue = board.renderRow("#f59827", "②", "ThunderRedStar", "1", "*");
        String wideValue = board.renderRow("#f59827", "⑤", "---", "---", "*");

        // The value is last, so its right edge is the row's total width.
        int narrow = MEASURER.measure(narrowValue).width();
        int wide = MEASURER.measure(wideValue).width();
        // A '.' is 2px, so flooring can leave either edge at most 1px short of 150.
        assertTrue(narrow > 148 && narrow <= 150, "narrow value ended at " + narrow);
        assertTrue(wide > 148 && wide <= 150, "wide value ended at " + wide);
    }

    @Test
    void autoLeavesABoardWithNoPadTagExactlyAsItWas() {
        // auto is the default, so it must be invisible to boards that never asked
        // for a column -- otherwise every existing hologram shifts on upgrade.
        LeaderboardFormatting board = padded("<white>%player%</white> %value%");
        List<String> rows = List.of(
                board.renderRow("#fff", "1", "Bo", "42", "*"),
                board.renderRow("#fff", "2", "Wilhelmina", "4200000", "*"));

        assertSame(rows, board.normalizeRows(rows));
    }

    @Test
    void anExplicitRowWidthAppliesEvenWithoutAPadTag() {
        LeaderboardFormatting board = padded("<white>%player%</white> %value%",
                new PadSettings(true, 150, ".", PadMode.FILL, "dark_gray", 200));
        List<String> rows = List.of(board.renderRow("#fff", "1", "Bo", "42", "*"));

        List<String> normalized = board.normalizeRows(rows);

        assertTrue(MEASURER.measure(normalized.get(0)).width() > 196, normalized.get(0));
    }

    @Test
    void aPlaceholderInThePatternIsResolvedBeforeTheColumnIsMeasured() {
        LeaderboardFormatting board = padded("%vault_prefix%<white>%player%</white><pad:80:.>%value%");

        String bare = board.renderRow("#fff", "1", "Alice", "42", "*",
                pattern -> pattern.replace("%vault_prefix%", ""));
        String prefixed = board.renderRow("#fff", "1", "Alice", "42", "*",
                pattern -> pattern.replace("%vault_prefix%", LegacyFormatting.toMiniMessage("&7[VIP] ")));

        assertFalse(prefixed.contains("%vault_prefix%"), prefixed);
        assertTrue(prefixed.contains("[VIP] "), prefixed);
        // Measured after resolution, so the prefix eats into the filler instead of
        // pushing the value 28px past the column. A '.' is 2px, hence the 1px floor.
        assertTrue(columnBefore(bare, "42") > 78 && columnBefore(bare, "42") <= 80,
                "bare column was " + columnBefore(bare, "42"));
        assertTrue(columnBefore(prefixed, "42") > 78 && columnBefore(prefixed, "42") <= 80,
                "prefixed column was " + columnBefore(prefixed, "42"));
    }

    @Test
    void aBoldPrefixFromAPlaceholderIsMeasuredBold() {
        LeaderboardFormatting board = padded("%vault_prefix%<white>%player%</white><pad:80:.>%value%");

        String row = board.renderRow("#fff", "1", "Alice", "42", "*",
                pattern -> pattern.replace("%vault_prefix%", LegacyFormatting.toMiniMessage("&l[VIP]")));

        // "[VIP]" draws 5px wider bold than plain -- two fillers' worth. Count the
        // plain width and the value lands 5px past the column instead of on it.
        int column = columnBefore(row, "42");
        assertTrue(column > 78 && column <= 80, "column was " + column);
    }

    @Test
    void aProviderValueCannotIntroduceAPlaceholderOfItsOwn() {
        LeaderboardFormatting board = new LeaderboardFormatting("t", "%player%: %value%", "r", "", "");

        String row = board.renderRow("#fff", "1", "Alice", "%server_online%", "*",
                pattern -> PlaceholderPatterns.resolve(pattern, placeholder -> "RESOLVED"));

        // Resolution runs on the pattern, before the tokens go in, so a value that
        // happens to look like a placeholder is drawn rather than expanded.
        assertEquals("Alice: %server_online%", row);
    }

    /** Width in pixels of everything before the trailing {@code suffix}. */
    private static int columnBefore(String rendered, String suffix) {
        assertTrue(rendered.endsWith(suffix), rendered);
        return MEASURER.measure(rendered.substring(0, rendered.length() - suffix.length())).lastLineWidth();
    }

    @Test
    void loadsRankColorSectionWithIntegerKeys() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                rank-colors:
                  1: "#4be44f"
                  2: "#4ce1e7"
                  bogus: "#ffffff"
                """);
        ConfigurationSection section = yaml.getConfigurationSection("rank-colors");
        Map<Integer, String> colors = PluginConfig.Formatting.loadRankColors(section);
        assertEquals(Map.of(1, "#4be44f", 2, "#4ce1e7"), colors);
        assertTrue(PluginConfig.Formatting.loadRankColors(null).isEmpty());
    }
}
