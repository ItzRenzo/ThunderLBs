package exposed.thunder.thunderLBs;

import exposed.thunder.thunderLBs.commands.LeaderboardsCommand;
import exposed.thunder.thunderLBs.config.ConfigMigrator;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.font.GlyphWidthBridges;
import exposed.thunder.thunderLBs.font.GlyphWidthRegistry;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardDefinition;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardManager;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardPage;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge;
import exposed.thunder.thunderLBs.render.RenderBackend;
import exposed.thunder.thunderLBs.render.entity.EntityRenderBackend;
import exposed.thunder.thunderLBs.render.packet.PacketEventsSupport;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import exposed.thunder.thunderLBs.util.VersionSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ThunderLBs extends JavaPlugin implements Listener {
    private static final int MINI_MESSAGE_CACHE_SIZE = 2048;
    private static final int BSTATS_PLUGIN_ID = 32620;
    private static final Set<UUID> DEVELOPER_UUIDS = Set.of(
            UUID.fromString("8555bccd-a421-38b0-807d-6445640759df"),
            UUID.fromString("c735448a-701d-4152-bd37-a42b8b1acd00")
    );

    private final GlyphWidthRegistry glyphWidths = GlyphWidthRegistry.createDefault();
    private PluginConfig pluginConfig;
    private PlaceholderBridge placeholderBridge;
    private LeaderboardManager leaderboardManager;
    private MiniMessage miniMessage;
    private NamespacedKey leaderboardDisplayKey;
    private RenderBackend renderBackend;
    private RegionTaskScheduler taskScheduler;
    private Metrics metrics;
    private final Set<UUID> placeholderDebugPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Component> miniMessageComponentCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
                    return size() > MINI_MESSAGE_CACHE_SIZE;
                }
            });

    @Override
    public void onLoad() {
        ensureConfigFiles();
    }

    @Override
    public void onEnable() {
        this.taskScheduler = RegionTaskScheduler.create(this);
        logVersionSupport();
        this.miniMessage = MiniMessage.miniMessage();
        this.pluginConfig = new PluginConfig(this);
        this.placeholderBridge = new PlaceholderBridge(this);
        ensureDataFolders();
        this.leaderboardDisplayKey = new NamespacedKey(this, "leaderboard_display");
        this.renderBackend = createBackend();
        getLogger().info("Rendering leaderboards in " + renderBackend.name() + " mode.");
        getServer().getPluginManager().registerEvents(this, this);
        this.leaderboardManager = new LeaderboardManager(this, pluginConfig, placeholderBridge);
        initializeMetrics();
        LeaderboardsCommand command = new LeaderboardsCommand(this, leaderboardManager);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("leaderboards"),
                "leaderboards command not defined in plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        GlyphWidthBridges.installAvailable(getServer().getPluginManager(), glyphWidths, getLogger());
        // World managers such as Multiverse create their worlds during their own onEnable, so
        // Bukkit.getWorld(...) still returns null for them here. Wait for the first tick, by which
        // point every plugin has enabled and its worlds exist.
        taskScheduler.runGlobal(this::performInitialLoad);
    }

    private void performInitialLoad() {
        if (!isEnabled()) {
            return;
        }
        if (pluginConfig.defaults().cleanupOnStart()) {
            cleanupDisplays();
        }
        leaderboardManager.loadAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (leaderboardManager != null) {
            leaderboardManager.activateForWorld(event.getWorld());
        }
    }

    /**
     * Runs at MONITOR with cancelled events ignored, so boards are only torn down when the unload
     * is actually going ahead — and while the world is still loaded, so displays can be removed
     * cleanly. {@link #onWorldLoad} rebuilds the boards if the world returns.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (leaderboardManager != null) {
            leaderboardManager.deactivateForWorld(event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeveloperJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (DEVELOPER_UUIDS.contains(player.getUniqueId())) {
            player.sendMessage(Component.text()
                    .append(Component.text("ThunderLBs ", TextColor.color(0x38BDF8)))
                    .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("This server is running ThunderLBs ", NamedTextColor.GRAY))
                    .append(Component.text(getPluginMeta().getVersion(), NamedTextColor.WHITE))
                    .build());
        }
    }

    private void logVersionSupport() {
        if (!VersionSupport.dialogsSupported()) {
            getLogger().info("Dialog editor disabled on Minecraft " + Bukkit.getMinecraftVersion()
                    + " (requires 1.21.7+). '/leaderboards edit <id> <option> <value>' still works.");
        }
        if (!VersionSupport.teleportDurationSupported()) {
            getLogger().info("Display teleport interpolation is unavailable on Minecraft "
                    + Bukkit.getMinecraftVersion() + " (requires 1.20.2+); moving boards will snap instead of glide.");
        }
    }

    private void initializeMetrics() {
        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie(
                "total_holders",
                () -> Integer.toString(leaderboardManager.totalHolders())
        ));
    }

    private RenderBackend createBackend() {
        if (pluginConfig.renderMode() == PluginConfig.RenderMode.PACKET) {
            Plugin packetEventsPlugin = getServer().getPluginManager().getPlugin("packetevents");
            if (packetEventsPlugin == null) {
                getLogger().warning("Packet rendering requested but PacketEvents is not installed. "
                        + "Falling back to entity rendering.");
                return new EntityRenderBackend(this);
            }
            if (!packetEventsPlugin.isEnabled()) {
                getLogger().warning("Packet rendering requested but PacketEvents failed to enable. "
                        + "Falling back to entity rendering.");
                return new EntityRenderBackend(this);
            }
            try {
                if (!PacketEventsSupport.isReady()) {
                    getLogger().warning("Packet rendering requested but PacketEvents is not initialized. "
                            + "Falling back to entity rendering.");
                    return new EntityRenderBackend(this);
                }
                return PacketEventsSupport.createBackend(this);
            } catch (Throwable throwable) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Could not use PacketEvents. Falling back to entity rendering.", throwable);
                return new EntityRenderBackend(this);
            }
        }
        return new EntityRenderBackend(this);
    }

    @Override
    public void onDisable() {
        if (metrics != null) {
            metrics.shutdown();
        }
        if (leaderboardManager != null) {
            leaderboardManager.shutdown();
        }
        if (placeholderBridge != null) {
            placeholderBridge.close();
        }
        if (renderBackend != null) {
            renderBackend.shutdown();
        }
        cleanupDisplays();
        placeholderDebugPlayers.clear();
        miniMessageComponentCache.clear();
    }

    private void ensureDataFolders() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Failed to create data folder at " + dataFolder.getAbsolutePath());
        }
        File leaderboards = new File(dataFolder, "leaderboards");
        if (!leaderboards.exists() && !leaderboards.mkdirs()) {
            getLogger().warning("Failed to create leaderboards folder at " + leaderboards.getAbsolutePath());
        }
    }

    private void ensureConfigFiles() {
        saveDefaultConfig();
        saveBundledResource("messages.yml");
        saveBundledResource("providers.yml");
        new ConfigMigrator(this).migrate();
    }

    private void saveBundledResource(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    private void cleanupDisplays() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Runnable cleanup = () -> {
                    for (org.bukkit.entity.Entity candidate : chunk.getEntities()) {
                        if (!(candidate instanceof TextDisplay entity)) {
                            continue;
                        }
                        boolean tagged = entity.getScoreboardTags().stream()
                                .anyMatch(tag -> tag.startsWith("ThunderLBs"));
                        PersistentDataContainer container = entity.getPersistentDataContainer();
                        boolean marked = leaderboardDisplayKey != null
                                && container.has(leaderboardDisplayKey, PersistentDataType.BYTE);
                        if (tagged || marked) {
                            entity.remove();
                        }
                    }
                };
                if (taskScheduler != null) {
                    taskScheduler.execute(world, chunk.getX(), chunk.getZ(), cleanup);
                } else {
                    cleanup.run();
                }
            }
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public PlaceholderBridge getPlaceholderBridge() {
        return placeholderBridge;
    }

    public LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public RenderBackend renderBackend() {
        return renderBackend;
    }

    public RegionTaskScheduler taskScheduler() {
        return taskScheduler;
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }


    public GlyphWidthRegistry glyphWidths() {
        return glyphWidths;
    }

    public Component deserializeMiniMessage(String input) {
        String normalized = input == null ? "" : input;
        Component cached = miniMessageComponentCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        Component parsed = miniMessage.deserialize(normalized);
        miniMessageComponentCache.put(normalized, parsed);
        return parsed;
    }

    public LeaderboardManager.LoadSummary reloadPluginConfig() {
        PluginConfig.RenderMode previousMode = pluginConfig.renderMode();
        ensureConfigFiles();
        pluginConfig.reload(true);
        if (pluginConfig.renderMode() != previousMode) {
            getLogger().warning("render.mode changed; a server restart is required for it to take effect.");
        }
        GlyphWidthBridges.installAvailable(getServer().getPluginManager(), glyphWidths, getLogger());
        miniMessageComponentCache.clear();
        leaderboardManager.reloadAnimationCache();
        return leaderboardManager.loadAll();
    }

    public NamespacedKey leaderboardDisplayKey() {
        return leaderboardDisplayKey;
    }

    public boolean togglePlaceholderDebug(UUID uuid) {
        if (placeholderDebugPlayers.remove(uuid)) {
            return false;
        }
        placeholderDebugPlayers.add(uuid);
        return true;
    }

    public boolean setPlaceholderDebug(UUID uuid, boolean enabled) {
        if (enabled) {
            placeholderDebugPlayers.add(uuid);
            return true;
        }
        placeholderDebugPlayers.remove(uuid);
        return false;
    }

    public boolean isPlaceholderDebugging(UUID uuid) {
        return placeholderDebugPlayers.contains(uuid);
    }

    public boolean hasPlaceholderDebugListeners() {
        return (pluginConfig != null && pluginConfig.debugPlaceholders()) || !placeholderDebugPlayers.isEmpty();
    }

    public void emitPlaceholderDebug(LeaderboardDefinition definition,
                                     LeaderboardPage page,
                                     int index,
                                     String type,
                                     String placeholder,
                                     String resolved) {
        String output = resolved == null ? "<null>" : resolved;
        if (pluginConfig != null && pluginConfig.debugPlaceholders()) {
            getLogger().info(String.format(Locale.ROOT,
                    "[ThunderLBs][debug] %s/%s #%d %s placeholder '%s' -> '%s'",
                    definition.id(),
                    page.holderId(),
                    index,
                    type,
                    placeholder,
                    output
            ));
        }
        if (placeholderDebugPlayers.isEmpty()) {
            return;
        }
        Component message = Component.text()
                .append(Component.text("ThunderLBs ", TextColor.color(0x38BDF8)))
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(Component.text(definition.id() + "/" + page.holderId() + " #" + index + " " + type
                        + " placeholder '" + placeholder + "' -> '" + output + "'", NamedTextColor.GRAY))
                .build();
        for (UUID uuid : placeholderDebugPlayers) {
            taskScheduler.runGlobal(() -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    taskScheduler.executeDelayed(player, () -> player.sendMessage(message), null, 1L);
                }
            });
        }
    }
}
