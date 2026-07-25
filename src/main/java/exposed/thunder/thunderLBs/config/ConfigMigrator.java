package exposed.thunder.thunderLBs.config;

import exposed.thunder.thunderLBs.leaderboard.LeaderboardFiles;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardPage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

public final class ConfigMigrator {
    public static final int CURRENT_VERSION = 6;

    private static final int LEGACY_VERSION = 1;
    private static final String CONFIG_FILE = "config.yml";
    private static final String PROVIDERS_FILE = "providers.yml";
    private static final String MESSAGES_FILE = "messages.yml";

    private static final String OLD_AJ_NAME = "%ajlb_lb_%holder%_%position%_alltime_name%";
    private static final String OLD_AJ_VALUE = "%ajlb_lb_%holder%_%position%_alltime_rawvalue%";
    private static final String OLD_AJ_VIEWER_RANK = "%ajlb_position_%holder%_alltime%";
    private static final String OLD_AJ_VIEWER_VALUE = "%ajlb_value_%holder%_alltime_raw%";

    private final JavaPlugin plugin;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void migrate() {
        File dataFolder = plugin.getDataFolder();
        File configFile = new File(dataFolder, CONFIG_FILE);
        if (!configFile.isFile()) {
            return;
        }

        try {
            YamlConfiguration config = load(configFile);
            int sourceVersion = readVersion(config);
            if (sourceVersion > CURRENT_VERSION) {
                plugin.getLogger().warning("Configuration version " + sourceVersion
                        + " is newer than the supported version " + CURRENT_VERSION + "; leaving it unchanged.");
                return;
            }
            if (sourceVersion == CURRENT_VERSION) {
                return;
            }

            File providersFile = new File(dataFolder, PROVIDERS_FILE);
            File messagesFile = new File(dataFolder, MESSAGES_FILE);
            YamlConfiguration providers = load(providersFile);
            YamlConfiguration messages = load(messagesFile);
            YamlConfiguration defaultConfig = loadBundled(CONFIG_FILE);
            YamlConfiguration defaultProviders = loadBundled(PROVIDERS_FILE);
            YamlConfiguration defaultMessages = loadBundled(MESSAGES_FILE);

            backup(configFile, new File(dataFolder, "config-v" + sourceVersion + "-backup.yml"));
            backup(providersFile, new File(dataFolder, "providers-v" + sourceVersion + "-backup.yml"));
            backup(messagesFile, new File(dataFolder, "messages-v" + sourceVersion + "-backup.yml"));

            migrateDocuments(config, providers, messages, defaultConfig, defaultProviders, defaultMessages,
                    sourceVersion);
            migrateLeaderboardFiles(dataFolder, sourceVersion);

            LeaderboardFiles.atomicSave(providers, providersFile);
            LeaderboardFiles.atomicSave(messages, messagesFile);
            LeaderboardFiles.atomicSave(config, configFile);
            plugin.getLogger().info("Migrated ThunderLBs configuration from version " + sourceVersion
                    + " to " + CURRENT_VERSION + ".");
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not complete the ThunderLBs configuration migration. Backups were kept and the "
                            + "migration will be retried while config.yml remains at its old version.",
                    exception);
        }
    }

    static void migrateDocuments(
            YamlConfiguration config,
            YamlConfiguration providers,
            YamlConfiguration messages,
            YamlConfiguration defaultConfig,
            YamlConfiguration defaultProviders,
            YamlConfiguration defaultMessages,
            int sourceVersion) {
        if (sourceVersion < 2) {
            migrateLegacyConfig(config, providers, messages, defaultProviders, defaultMessages);
        }
        if (sourceVersion < 3) {
            migrateVersionThree(config, providers, messages, defaultConfig, defaultProviders, defaultMessages);
        }
        if (sourceVersion < 4) {
            migrateVersionFour(config, providers);
        }
        if (sourceVersion < 5) {
            migrateVersionFive(config, defaultConfig);
        }
        if (sourceVersion < 6) {
            migrateVersionSix(config, defaultConfig);
        }
        config.set("config-version", CURRENT_VERSION);
    }

    static boolean migrateLeaderboardConfiguration(YamlConfiguration leaderboard) {
        ConfigurationSection pages = leaderboard.getConfigurationSection("pages");
        if (pages == null) {
            return false;
        }
        boolean changed = false;
        for (String key : pages.getKeys(false)) {
            ConfigurationSection page = pages.getConfigurationSection(key);
            if (page != null && !page.isSet("interval")) {
                page.set("interval", LeaderboardPage.DEFAULT_INTERVAL);
                changed = true;
            }
        }
        return changed;
    }

    private static void migrateLegacyConfig(
            YamlConfiguration config,
            YamlConfiguration providers,
            YamlConfiguration messages,
            YamlConfiguration defaultProviders,
            YamlConfiguration defaultMessages) {
        moveIfPresent(config, "defaults.progress-offset-y", config, "defaults.bar-offset-y", null);

        String legacyMode = config.getString("progress-bar.mode");
        if (!config.isSet("bar.mode") && legacyMode != null) {
            config.set("bar.mode", switch (legacyMode.toLowerCase(java.util.Locale.ROOT)) {
                case "segmented" -> "dots";
                case "single" -> "bar";
                default -> legacyMode;
            });
        }
        moveIfPresent(config, "progress-bar.use-holder-color", config, "bar.use-holder-color", null);
        moveIfPresent(config, "formatting.progress.background", config, "bar.background", null);
        moveIfPresent(config, "formatting.progress.foreground", config, "bar.foreground", null);

        moveIfPresent(config, "placeholders.name", providers, "topper.name", defaultProviders);
        moveIfPresent(config, "placeholders.value", providers, "topper.value", defaultProviders);
        moveIfPresent(config, "group-placeholders.name", providers, "topper.group-name", defaultProviders);
        moveIfPresent(config, "group-placeholders.value", providers, "topper.group-value", defaultProviders);
        moveIfPresent(config, "group-placeholders.team", providers, "topper.group-team", defaultProviders);
        moveIfPresent(config, "placeholders.missing-name", config, "missing-text", null);

        copyLegacySection(config.getConfigurationSection("providers"), providers, defaultProviders, "");
        copyLegacySection(config.getConfigurationSection("messages"), messages, defaultMessages, "");
        removeLegacyPaths(config);
    }

    private static void migrateVersionThree(
            YamlConfiguration config,
            YamlConfiguration providers,
            YamlConfiguration messages,
            YamlConfiguration defaultConfig,
            YamlConfiguration defaultProviders,
            YamlConfiguration defaultMessages) {
        copyLegacySection(config.getConfigurationSection("providers"), providers, defaultProviders, "");
        copyLegacySection(config.getConfigurationSection("messages"), messages, defaultMessages, "");
        config.set("providers", null);
        config.set("messages", null);

        replaceIfStock(providers, "ajleaderboards.name", OLD_AJ_NAME,
                defaultProviders.getString("ajleaderboards.name"));
        replaceIfStock(providers, "ajleaderboards.value", OLD_AJ_VALUE,
                defaultProviders.getString("ajleaderboards.value"));
        replaceIfStock(providers, "ajleaderboards.viewer-rank", OLD_AJ_VIEWER_RANK,
                defaultProviders.getString("ajleaderboards.viewer-rank"));
        replaceIfStock(providers, "ajleaderboards.viewer-value", OLD_AJ_VIEWER_VALUE,
                defaultProviders.getString("ajleaderboards.viewer-value"));

        mergeMissing(config, defaultConfig, "");
        mergeMissing(providers, defaultProviders, "");
        mergeMissing(messages, defaultMessages, "");
    }

    private static void migrateVersionFour(
            YamlConfiguration config,
            YamlConfiguration providers) {
        for (String providerName : List.of("topper", "ajleaderboards", "timedtopper")) {
            ConfigurationSection section = providers.getConfigurationSection(providerName);
            if (section == null) {
                continue;
            }
            if (isStockNativeProvider(providerName, section)) {
                providers.set(providerName, null);
                continue;
            }

            String migratedName = uniqueProviderName(providers, providerName + "-papi");
            Map<String, Object> values = new LinkedHashMap<>(section.getValues(false));
            providers.set(providerName, null);
            providers.createSection(migratedName, values);
            if (providerName.equalsIgnoreCase(config.getString("provider", ""))) {
                config.set("provider", migratedName);
            }
        }
    }


    private static void migrateVersionFive(
            YamlConfiguration config,
            YamlConfiguration defaultConfig) {
        mergeMissing(config, defaultConfig, "");
    }


    private static void migrateVersionSix(
            YamlConfiguration config,
            YamlConfiguration defaultConfig) {
        mergeMissing(config, defaultConfig, "");
    }

    private static boolean isStockNativeProvider(
            String providerName,
            ConfigurationSection section) {
        Map<String, Set<String>> accepted = switch (providerName) {
            case "topper" -> Map.of(
                    "name", Set.of("%topper_%holder%;top_name;%position%%"),
                    "value", Set.of("%topper_%holder%;top_value_raw;%position%%"),
                    "viewer-rank", Set.of("%topper_%holder%;top_rank%"),
                    "viewer-value", Set.of("%topper_%holder%;value_raw%"),
                    "group-name", Set.of("%grouptopper_%holder%;top_name;%position%%"),
                    "group-value", Set.of("%grouptopper_%holder%;top_value_raw;%position%%"),
                    "group-viewer-rank", Set.of("%grouptopper_%holder%;top_rank%"),
                    "group-viewer-value", Set.of("%grouptopper_%holder%;value_raw%"),
                    "group-team", Set.of("%topper_team%"));
            case "ajleaderboards" -> Map.of(
                    "name", Set.of(
                            OLD_AJ_NAME,
                            "%ajlb_lb_%holder%_%position%_%interval%_name%"),
                    "value", Set.of(
                            OLD_AJ_VALUE,
                            "%ajlb_lb_%holder%_%position%_%interval%_rawvalue%"),
                    "viewer-rank", Set.of(
                            OLD_AJ_VIEWER_RANK,
                            "%ajlb_position_%holder%_%interval%%"),
                    "viewer-value", Set.of(
                            OLD_AJ_VIEWER_VALUE,
                            "%ajlb_value_%holder%_%interval%_raw%"));
            case "timedtopper" -> Map.of(
                    "name", Set.of("%timedtopper_%holder%;top_name;%position%%"),
                    "value", Set.of("%timedtopper_%holder%;top_value_raw;%position%%"),
                    "viewer-rank", Set.of("%timedtopper_%holder%;top_rank%"),
                    "viewer-value", Set.of("%timedtopper_%holder%;value_raw%"));
            default -> Map.of();
        };
        if (accepted.isEmpty()) {
            return false;
        }
        for (String key : section.getKeys(false)) {
            Set<String> acceptedValues = accepted.get(key);
            if (acceptedValues == null || !acceptedValues.contains(section.getString(key))) {
                return false;
            }
        }
        return true;
    }

    private static String uniqueProviderName(
            ConfigurationSection providers,
            String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (providers.isSet(candidate)) {
            candidate = baseName + "-" + suffix++;
        }
        return candidate;
    }

    private void migrateLeaderboardFiles(File dataFolder, int sourceVersion) {
        File leaderboardsFolder = new File(dataFolder, "leaderboards");
        File[] files = leaderboardsFolder.listFiles((directory, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }

        File backupFolder = new File(dataFolder, "leaderboards-v" + sourceVersion + "-backup");
        for (File file : files) {
            try {
                YamlConfiguration leaderboard = load(file);
                if (!migrateLeaderboardConfiguration(leaderboard)) {
                    continue;
                }
                backup(file, new File(backupFolder, file.getName()));
                LeaderboardFiles.atomicSave(leaderboard, file);
            } catch (IOException | InvalidConfigurationException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not add the interval setting to leaderboard file " + file.getName() + ".", exception);
            }
        }
    }

    private static void removeLegacyPaths(YamlConfiguration config) {
        config.set("defaults.progress-fill-interval-ticks", null);
        config.set("defaults.progress-offset-y", null);
        config.set("defaults.progress-direction-scale", null);
        config.set("placeholders", null);
        config.set("group-placeholders", null);
        config.set("formatting.progress", null);
        config.set("progress-bar", null);
        config.set("providers", null);
        config.set("messages", null);
    }

    private static void moveIfPresent(
            ConfigurationSection source,
            String sourcePath,
            ConfigurationSection target,
            String targetPath,
            ConfigurationSection targetDefaults) {
        if (!source.isSet(sourcePath)) {
            return;
        }
        if (canReplace(target, targetPath, targetDefaults)) {
            target.set(targetPath, source.get(sourcePath));
        }
    }

    private static void copyLegacySection(
            ConfigurationSection source,
            ConfigurationSection target,
            ConfigurationSection targetDefaults,
            String prefix) {
        if (source == null) {
            return;
        }
        for (String key : source.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copyLegacySection(child, target, targetDefaults, path);
            } else if (canReplace(target, path, targetDefaults)) {
                target.set(path, source.get(key));
            }
        }
    }

    private static boolean canReplace(
            ConfigurationSection target,
            String path,
            ConfigurationSection targetDefaults) {
        if (!target.isSet(path)) {
            return true;
        }
        return isHistoricalDefault(path, target.get(path))
                || targetDefaults != null
                && targetDefaults.isSet(path)
                && Objects.equals(target.get(path), targetDefaults.get(path));
    }

    private static boolean isHistoricalDefault(String path, Object value) {
        return switch (path) {
            case "ajleaderboards.name" -> Objects.equals(value, OLD_AJ_NAME);
            case "ajleaderboards.value" -> Objects.equals(value, OLD_AJ_VALUE);
            case "ajleaderboards.viewer-rank" -> Objects.equals(value, OLD_AJ_VIEWER_RANK);
            case "ajleaderboards.viewer-value" -> Objects.equals(value, OLD_AJ_VIEWER_VALUE);
            default -> false;
        };
    }

    private static void replaceIfStock(
            ConfigurationSection configuration,
            String path,
            String oldValue,
            String newValue) {
        if (Objects.equals(configuration.getString(path), oldValue) && newValue != null) {
            configuration.set(path, newValue);
        }
    }

    private static void mergeMissing(
            ConfigurationSection target,
            ConfigurationSection defaults,
            String prefix) {
        for (String key : defaults.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            copyComments(target, defaults, path, key);
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (defaultChild != null) {
                ConfigurationSection targetChild = target.getConfigurationSection(path);
                if (targetChild == null) {
                    if (target.isSet(path)) {
                        continue;
                    }
                    target.createSection(path);
                }
                mergeMissing(target, defaultChild, path);
            } else if (!target.isSet(path)) {
                target.set(path, defaults.get(key));
            }
        }
    }

    private static void copyComments(
            ConfigurationSection target,
            ConfigurationSection defaults,
            String targetPath,
            String defaultPath) {
        if (target.getComments(targetPath).isEmpty()) {
            List<String> comments = defaults.getComments(defaultPath);
            if (!comments.isEmpty()) {
                target.setComments(targetPath, comments);
            }
        }
        if (target.getInlineComments(targetPath).isEmpty()) {
            List<String> comments = defaults.getInlineComments(defaultPath);
            if (!comments.isEmpty()) {
                target.setInlineComments(targetPath, comments);
            }
        }
    }

    private static int readVersion(YamlConfiguration config) {
        Object rawVersion = config.get("config-version");
        if (rawVersion instanceof Number number) {
            return Math.max(LEGACY_VERSION, number.intValue());
        }
        if (rawVersion instanceof String string) {
            try {
                return Math.max(LEGACY_VERSION, Integer.parseInt(string.trim()));
            } catch (NumberFormatException ignored) {
                return LEGACY_VERSION;
            }
        }
        return LEGACY_VERSION;
    }

    private YamlConfiguration loadBundled(String name) throws IOException, InvalidConfigurationException {
        InputStream input = plugin.getResource(name);
        if (input == null) {
            throw new IOException("Bundled resource is missing: " + name);
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.options().parseComments(true);
            configuration.load(reader);
            return configuration;
        }
    }

    private static YamlConfiguration load(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        if (file.isFile()) {
            configuration.load(file);
        }
        return configuration;
    }

    private static void backup(File source, File target) throws IOException {
        if (!source.isFile() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }
}
