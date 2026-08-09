package exposed.thunder.thunderLBs.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {
    @Test
    void theBundledConfigAlreadyCarriesTheCurrentVersion() throws Exception {
        // A fresh install writes the bundled file and then runs migrate(). If the
        // two disagree the very first startup "upgrades" the config and leaves a
        // config-vN-backup.yml next to it, which reads like something went wrong.
        assertEquals(ConfigMigrator.CURRENT_VERSION, resource("config.yml").getInt("config-version"));
    }

    @Test
    void theDefaultLineWidthClearsTheDefaultPaddingColumn() throws Exception {
        // These two defaults have to be read together: a <pad:150:...> column
        // still has a value drawn after it, and anything past line-width wraps
        // onto a second line. Shipping a line-width that its own padding default
        // cannot fit makes the stock config look broken the moment someone adds
        // a pad tag.
        YamlConfiguration bundled = resource("config.yml");
        int lineWidth = bundled.getInt("render.line-width");
        int target = bundled.getInt("formatting.padding.target");
        assertEquals(PluginConfig.DEFAULT_LINE_WIDTH, lineWidth);
        assertTrue(lineWidth >= target + 60,
                "line-width " + lineWidth + " leaves only " + (lineWidth - target)
                        + "px after the default padding target of " + target);
    }

    @Test
    void upgradesAddNewFlagsAndRemoveStockNativeProviderPatterns() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 2
                missing-text: "CUSTOM"
                providers:
                  custom:
                    name: "%custom_name%"
                    value: "%custom_value%"
                messages:
                  prefix: "Custom prefix "
                  custom-message: "Keep me"
                """);
        YamlConfiguration providers = yaml("""
                ajleaderboards:
                  name: "%ajlb_lb_%holder%_%position%_alltime_name%"
                  value: "%ajlb_lb_%holder%_%position%_alltime_rawvalue%"
                  viewer-rank: "%ajlb_position_%holder%_alltime%"
                  viewer-value: "%ajlb_value_%holder%_alltime_raw%"
                """);
        YamlConfiguration messages = new YamlConfiguration();

        ConfigMigrator.migrateDocuments(
                config,
                providers,
                messages,
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                2);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals("CUSTOM", config.getString("missing-text"));
        assertEquals("-1", config.getString("missing-position"));
        assertEquals(40L, config.getLong("performance.relative-refresh-ticks"));
        assertTrue(config.isConfigurationSection("formatting.rank-colors"));
        assertTrue(config.getConfigurationSection("formatting.rank-colors").getKeys(false).isEmpty());
        assertFalse(config.isSet("providers"));
        assertFalse(config.isSet("messages"));

        assertFalse(providers.isSet("ajleaderboards"));
        assertFalse(providers.isSet("timedtopper"));
        assertEquals("%custom_name%", providers.getString("custom.name"));

        assertEquals("Custom prefix ", messages.getString("prefix"));
        assertEquals("Keep me", messages.getString("custom-message"));
        assertTrue(messages.isSet("editor-unsupported"));
    }

    @Test
    void customizedBuiltinPatternsBecomeAnExplicitPapiProvider() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 2
                provider: ajleaderboards
                """);
        YamlConfiguration providers = yaml("""
                ajleaderboards:
                  name: "%my_custom_pattern%"
                  value: "%ajlb_lb_%holder%_%position%_alltime_rawvalue%"
                """);

        ConfigMigrator.migrateDocuments(
                config,
                providers,
                new YamlConfiguration(),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                2);

        assertFalse(providers.isSet("ajleaderboards"));
        assertEquals("ajleaderboards-papi", config.getString("provider"));
        assertEquals("%my_custom_pattern%", providers.getString("ajleaderboards-papi.name"));
        assertEquals("%ajlb_lb_%holder%_%position%_alltime_rawvalue%",
                providers.getString("ajleaderboards-papi.value"));
    }

    @Test
    void legacyConfigurationKeepsValuesWhileRenamingFlags() throws Exception {
        YamlConfiguration config = yaml("""
                defaults:
                  progress-offset-y: -0.75
                placeholders:
                  name: "%legacy_name%"
                  value: "%legacy_value%"
                  missing-name: "NONE"
                group-placeholders:
                  name: "%legacy_group_name%"
                  value: "%legacy_group_value%"
                  team: "%legacy_team%"
                formatting:
                  progress:
                    background: "legacy background"
                    foreground: "legacy foreground"
                progress-bar:
                  mode: segmented
                  use-holder-color: false
                messages:
                  prefix: "Legacy prefix "
                """);
        YamlConfiguration providers = new YamlConfiguration();
        YamlConfiguration messages = new YamlConfiguration();

        ConfigMigrator.migrateDocuments(
                config,
                providers,
                messages,
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                1);

        assertEquals(-0.75D, config.getDouble("defaults.bar-offset-y"));
        assertEquals("dots", config.getString("bar.mode"));
        assertFalse(config.getBoolean("bar.use-holder-color"));
        assertEquals("legacy background", config.getString("bar.background"));
        assertEquals("legacy foreground", config.getString("bar.foreground"));
        assertEquals("NONE", config.getString("missing-text"));
        assertFalse(config.isSet("placeholders"));
        assertFalse(config.isSet("progress-bar"));
        assertFalse(providers.isSet("topper"));
        assertEquals("topper-papi", config.getString("provider"));
        assertEquals("%legacy_name%", providers.getString("topper-papi.name"));
        assertEquals("%legacy_group_value%", providers.getString("topper-papi.group-value"));
        assertEquals("%legacy_team%", providers.getString("topper-papi.group-team"));
        assertEquals("Legacy prefix ", messages.getString("prefix"));
    }

    @Test
    void versionFourLeavesOnlyCustomProviderSections() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 3
                provider: topper
                """);
        YamlConfiguration providers = yaml("""
                topper:
                  name: "%topper_%holder%;top_name;%position%%"
                  value: "%topper_%holder%;top_value_raw;%position%%"
                  viewer-rank: "%topper_%holder%;top_rank%"
                  viewer-value: "%topper_%holder%;value_raw%"
                timedtopper:
                  name: "%timedtopper_%holder%;top_name;%position%%"
                  value: "%timedtopper_%holder%;top_value_raw;%position%%"
                  viewer-rank: "%timedtopper_%holder%;top_rank%"
                  viewer-value: "%timedtopper_%holder%;value_raw%"
                custom:
                  name: "%custom_name%"
                  value: "%custom_value%"
                """);

        ConfigMigrator.migrateDocuments(
                config,
                providers,
                new YamlConfiguration(),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                3);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals("topper", config.getString("provider"));
        assertFalse(providers.isSet("topper"));
        assertFalse(providers.isSet("timedtopper"));
        assertEquals("%custom_name%", providers.getString("custom.name"));
    }

    @Test
    void versionFiveAddsPaddingAndAlignmentWithoutTouchingCustomisedKeys() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 4
                render:
                  mode: entity
                  view-distance: 24
                formatting:
                  row: "<white>%player%</white>"
                """);

        ConfigMigrator.migrateDocuments(
                config,
                yaml("custom:\n  name: \"%custom_name%\"\n"),
                new YamlConfiguration(),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                4);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals(250, config.getInt("render.line-width"));
        assertEquals("center", config.getString("render.alignment"));
        assertTrue(config.getBoolean("formatting.padding.enabled"));
        assertEquals(150, config.getInt("formatting.padding.target"));
        assertEquals(".", config.getString("formatting.padding.filler"));
        assertEquals("fill", config.getString("formatting.padding.mode"));
        assertEquals("dark_gray", config.getString("formatting.padding.style"));
        assertEquals("auto", config.getString("formatting.padding.row-width"));
        assertEquals(8, config.getInt("formatting.padding.fallback-advance"));
        assertTrue(config.isConfigurationSection("formatting.padding.glyph-widths"));
        assertTrue(config.getConfigurationSection("formatting.padding.glyph-widths").getKeys(false).isEmpty());

        // Existing boards must render identically after the upgrade.
        assertEquals("entity", config.getString("render.mode"));
        assertEquals(24, config.getInt("render.view-distance"));
        assertEquals("<white>%player%</white>", config.getString("formatting.row"));
    }

    @Test
    void versionSixAddsRowWidthToAnAlreadyPaddedConfig() throws Exception {
        // A config written before row-width existed: the padding block is there and
        // customised, so only the one new key may appear.
        YamlConfiguration config = yaml("""
                config-version: 5
                formatting:
                  padding:
                    enabled: true
                    target: 125
                    filler: "."
                    mode: fill
                    style: dark_gray
                    glyph-widths: {}
                    fallback-advance: 8
                """);

        ConfigMigrator.migrateDocuments(
                config,
                new YamlConfiguration(),
                new YamlConfiguration(),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                5);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals("auto", config.getString("formatting.padding.row-width"));
        assertEquals(125, config.getInt("formatting.padding.target"));
    }

    @Test
    void existingLeaderboardPagesReceiveOnlyMissingIntervals() throws Exception {
        YamlConfiguration leaderboard = yaml("""
                pages:
                  page-1:
                    holder: kills
                  page-2:
                    holder: deaths
                    interval: weekly
                """);

        assertTrue(ConfigMigrator.migrateLeaderboardConfiguration(leaderboard));
        assertEquals("alltime", leaderboard.getString("pages.page-1.interval"));
        assertEquals("weekly", leaderboard.getString("pages.page-2.interval"));
        assertFalse(ConfigMigrator.migrateLeaderboardConfiguration(leaderboard));
    }

    @Test
    void upgradingFromVersionSixAddsTheProviderCacheControls() throws Exception {
        // The async toggles are the documented escape hatch for a misbehaving PlaceholderAPI
        // expansion, so an existing install has to actually receive them in its own config.yml
        // rather than only picking up the defaults silently.
        YamlConfiguration config = yaml("""
                config-version: 6
                performance:
                  relative-refresh-ticks: 80
                """);

        ConfigMigrator.migrateDocuments(
                config,
                yaml(""),
                yaml(""),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                6);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals(80L, config.getLong("performance.relative-refresh-ticks"),
                "a tuned value must survive the upgrade");
        assertEquals(100L, config.getLong("performance.provider-cache-ttl-ticks"));
        assertTrue(config.getBoolean("performance.async-providers"));
        assertTrue(config.getBoolean("performance.async-papi"));
        assertFalse(config.getComments("performance.async-papi").isEmpty(),
                "a new key has to arrive with its explanation, or nobody can find the escape hatch");
    }

    @Test
    void upgradingFromVersionSevenAddsTheSoundCategory() throws Exception {
        // Before this key the typing click always went out on the ambient channel, so players who
        // had that slider down heard nothing whatever key they picked. An existing install has to
        // actually receive the new default, not just inherit it invisibly.
        YamlConfiguration config = yaml("""
                config-version: 7
                sounds:
                  typing:
                    enabled: true
                    key: "minecraft:block.amethyst_block.chime"
                    volume: 0.85
                    pitch-min: 1.2
                    pitch-max: 2.0
                    radius: 16
                """);

        ConfigMigrator.migrateDocuments(
                config,
                yaml(""),
                yaml(""),
                resource("config.yml"),
                resource("providers.yml"),
                resource("messages.yml"),
                7);

        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals("master", config.getString("sounds.typing.category"));
        assertEquals("minecraft:block.amethyst_block.chime", config.getString("sounds.typing.key"),
                "a chosen sound must survive the upgrade");
        assertEquals(0.85D, config.getDouble("sounds.typing.volume"));
        assertFalse(config.getComments("sounds.typing.category").isEmpty(),
                "the category needs its explanation, or nobody will know why ambient was silent");
    }

    private static YamlConfiguration yaml(String input) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(input);
        return configuration;
    }

    private static YamlConfiguration resource(String name) throws Exception {
        InputStream input = ConfigMigratorTest.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IOException("Missing test resource " + name);
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.options().parseComments(true);
            configuration.load(reader);
            return configuration;
        }
    }
}
