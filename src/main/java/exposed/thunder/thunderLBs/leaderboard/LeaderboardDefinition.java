package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.render.TextAlignment;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LeaderboardDefinition {

    private final String id;
    private final LeaderboardType type;
    private final File file;
    private final String originalWorldName;
    private boolean enabled;
    private Location origin;
    private String worldName;
    private LeaderboardSettings settings;
    private LeaderboardFormatting formatting;
    private final List<LeaderboardPage> pages;
    private Display.Billboard billboard;
    private boolean textShadow;
    private BarMode barMode;
    private boolean barUseHolderColor;
    private int viewDistance;
    private int lineWidth;
    private TextAlignment alignment;
    private LeaderboardAnimations animations;
    private boolean legacyFormat;

    public LeaderboardDefinition(String id, LeaderboardType type, File file, Location origin, String worldName,
            LeaderboardSettings settings, LeaderboardFormatting formatting, List<LeaderboardPage> pages,
            Display.Billboard billboard, boolean textShadow, BarMode barMode, boolean barUseHolderColor,
            int viewDistance, int lineWidth, TextAlignment alignment, LeaderboardAnimations animations,
            boolean enabled) {
        this.id = id;
        this.type = type;
        this.file = file;
        this.enabled = enabled;
        this.origin = origin.clone();
        this.origin.setPitch(0.0F);
        this.worldName = worldName;
        this.originalWorldName = worldName;
        this.settings = settings;
        this.formatting = formatting;
        this.pages = new ArrayList<>(pages);
        this.billboard = sanitizeBillboard(billboard);
        this.textShadow = textShadow;
        this.barMode = barMode == null ? BarMode.DOTS : barMode;
        this.barUseHolderColor = barUseHolderColor;
        this.viewDistance = viewDistance;
        this.lineWidth = clampLineWidth(lineWidth);
        this.alignment = alignment == null ? TextAlignment.CENTER : alignment;
        this.animations = animations.copy();
    }

    public static LeaderboardDefinition load(String id, File file, PluginConfig config)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            yaml.load(file);
        }
        LeaderboardType type = LeaderboardType.fromString(yaml.getString("type"), LeaderboardType.PLAYER);
        boolean enabled = yaml.getBoolean("enabled", true);
        String worldName = yaml.getString("world", "world");
        double x = yaml.getDouble("x", 0.0D);
        double y = yaml.getDouble("y", 0.0D);
        double z = yaml.getDouble("z", 0.0D);
        float yaw = (float) yaml.getDouble("yaw", 0.0D);
        World world = Bukkit.getWorld(worldName);
        Location location = new Location(world, x, y, z, yaw, 0.0F);

        LeaderboardSettings settings = LeaderboardSettings.from(yaml.getConfigurationSection("settings"), config);
        LeaderboardFormatting formatting = LeaderboardFormatting.from(yaml.getConfigurationSection("formatting"),
                config);
        List<LeaderboardPage> pages = new ArrayList<>();
        ConfigurationSection pagesSection = yaml.getConfigurationSection("pages");
        if (pagesSection != null) {
            for (String key : pagesSection.getKeys(false)) {
                ConfigurationSection section = pagesSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                pages.add(LeaderboardPage.from(section));
            }
        }
        if (pages.isEmpty()) {
            pages.add(new LeaderboardPage("kills", "MOST KILLS", "#FF2929", "*", ValueFormat.SHORT_NUMBER, "", ""));
        }
        String billboardName = yaml.getString("billboard", Display.Billboard.FIXED.name());
        Display.Billboard billboard = parseBillboard(billboardName);
        boolean textShadow = yaml.getBoolean("text-shadow", true);

        boolean legacy = false;
        BarMode barMode;
        boolean barUseHolderColor;
        ConfigurationSection barSection = yaml.getConfigurationSection("bar");
        ConfigurationSection legacySection = yaml.getConfigurationSection("progress-bar");
        PluginConfig.Bar barDefaults = config.bar();
        if (barSection != null) {
            barMode = BarMode.fromString(barSection.getString("mode"), barDefaults.mode());
            barUseHolderColor = barSection.getBoolean("use-holder-color", barDefaults.useHolderColor());
        } else if (legacySection != null) {
            legacy = true;
            barMode = BarMode.fromString(legacySection.getString("mode"), barDefaults.mode());
            barUseHolderColor = barDefaults.useHolderColor();
        } else {
            barMode = barDefaults.mode();
            barUseHolderColor = barDefaults.useHolderColor();
        }
        if (!yaml.isSet("view-distance")) {
            legacy = legacy || file.exists();
        }
        int viewDistance = yaml.getInt("view-distance", config.viewDistance());
        int lineWidth = yaml.getInt("line-width", config.lineWidth());
        TextAlignment alignment = TextAlignment.fromString(yaml.getString("alignment"), config.alignment());
        LeaderboardAnimations animations = LeaderboardAnimations.from(yaml.getConfigurationSection("animations"),
                config.animation());
        LeaderboardDefinition definition = new LeaderboardDefinition(id, type, file, location, worldName, settings,
                formatting, pages, billboard, textShadow, barMode, barUseHolderColor, viewDistance, lineWidth,
                alignment, animations, enabled);
        definition.legacyFormat = legacy;
        return definition;
    }

    public void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (InvalidConfigurationException exception) {
                throw new IOException("Cannot preserve leaderboard configuration in " + file, exception);
            }
        }
        yaml.set("enabled", enabled);
        yaml.set("type", type.name());
        String saveWorld = origin.getWorld() != null ? origin.getWorld().getName()
                : (worldName != null ? worldName : originalWorldName);
        yaml.set("world", saveWorld);
        yaml.set("x", origin.getX());
        yaml.set("y", origin.getY());
        yaml.set("z", origin.getZ());
        yaml.set("yaw", origin.getYaw());
        Display.Billboard toSave = billboard != null ? billboard : Display.Billboard.FIXED;
        yaml.set("billboard", toSave.name());
        yaml.set("text-shadow", textShadow);
        yaml.set("view-distance", viewDistance);
        yaml.set("line-width", lineWidth);
        yaml.set("alignment", alignment.configName());
        ConfigurationSection barSection = LeaderboardFiles.section(yaml, "bar");
        barSection.set("mode", barMode.name().toLowerCase(java.util.Locale.ROOT));
        barSection.set("use-holder-color", barUseHolderColor);

        ConfigurationSection settingsSection = LeaderboardFiles.section(yaml, "settings");
        settings.serialize(settingsSection);

        ConfigurationSection formattingSection = LeaderboardFiles.section(yaml, "formatting");
        formatting.serialize(formattingSection);

        ConfigurationSection animationsSection = LeaderboardFiles.section(yaml, "animations");
        animations.serialize(animationsSection);

        ConfigurationSection pagesSection = LeaderboardFiles.section(yaml, "pages");
        Set<String> pageKeys = new HashSet<>();
        for (int i = 1; i <= pages.size(); i++) {
            pageKeys.add("page-" + i);
        }
        for (String key : new ArrayList<>(pagesSection.getKeys(false))) {
            if (!pageKeys.contains(key)) {
                pagesSection.set(key, null);
            }
        }
        for (int i = 0; i < pages.size(); i++) {
            LeaderboardPage page = pages.get(i);
            ConfigurationSection section = LeaderboardFiles.section(pagesSection, "page-" + (i + 1));
            page.serialize(section);
        }
        LeaderboardFiles.atomicSave(yaml, file);
        legacyFormat = false;
    }

    public String id() {
        return id;
    }

    public LeaderboardType type() {
        return type;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public File file() {
        return file;
    }

    public boolean legacyFormat() {
        return legacyFormat;
    }

    public Location origin() {
        Location clone = origin.clone();
        clone.setPitch(0.0F);
        return clone;
    }

    public void setOrigin(Location origin) {
        Location clone = origin.clone();
        clone.setPitch(0.0F);
        this.origin = clone;
        if (origin.getWorld() != null) {
            this.worldName = origin.getWorld().getName();
        }
    }

    public LeaderboardSettings settings() {
        return settings;
    }

    public void setSettings(LeaderboardSettings settings) {
        this.settings = settings;
    }

    public LeaderboardFormatting formatting() {
        return formatting;
    }

    public void setFormatting(LeaderboardFormatting formatting) {
        this.formatting = formatting;
    }

    public List<LeaderboardPage> pages() {
        return pages;
    }

    public Display.Billboard billboard() {
        return billboard;
    }

    public void setBillboard(Display.Billboard billboard) {
        this.billboard = sanitizeBillboard(billboard);
    }

    public boolean textShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean textShadow) {
        this.textShadow = textShadow;
    }

    public BarMode barMode() {
        return barMode;
    }

    public boolean barUseHolderColor() {
        return barUseHolderColor;
    }

    public void setBarSettings(BarMode mode, boolean useHolderColor) {
        this.barMode = mode == null ? BarMode.DOTS : mode;
        this.barUseHolderColor = useHolderColor;
    }

    public int viewDistance() {
        return viewDistance;
    }

    public void setViewDistance(int viewDistance) {
        this.viewDistance = viewDistance;
    }


    public int lineWidth() {
        return lineWidth;
    }

    public void setLineWidth(int lineWidth) {
        this.lineWidth = clampLineWidth(lineWidth);
    }

    public TextAlignment alignment() {
        return alignment;
    }

    public void setAlignment(TextAlignment alignment) {
        this.alignment = alignment == null ? TextAlignment.CENTER : alignment;
    }

    public LeaderboardAnimations animations() {
        return animations;
    }

    public void setAnimations(LeaderboardAnimations animations) {
        this.animations = animations.copy();
    }

    private static int clampLineWidth(int lineWidth) {
        return Math.max(PluginConfig.MIN_LINE_WIDTH, Math.min(PluginConfig.MAX_LINE_WIDTH, lineWidth));
    }

    private static Display.Billboard parseBillboard(String value) {
        if (value == null || value.isEmpty()) {
            return Display.Billboard.FIXED;
        }
        try {
            Display.Billboard candidate = Display.Billboard.valueOf(value.toUpperCase());
            return sanitizeBillboard(candidate);
        } catch (IllegalArgumentException ex) {
            return Display.Billboard.FIXED;
        }
    }

    private static Display.Billboard sanitizeBillboard(Display.Billboard billboard) {
        if (billboard == null) {
            return Display.Billboard.FIXED;
        }
        return switch (billboard) {
            case FIXED, VERTICAL -> billboard;
            default -> Display.Billboard.FIXED;
        };
    }
}
