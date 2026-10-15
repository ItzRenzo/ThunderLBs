package exposed.thunder.thunderLBs.commands;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.animation.EasingType;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.leaderboard.BarMode;
import exposed.thunder.thunderLBs.leaderboard.Leaderboard;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardDefinition;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardFiles;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardManager;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardSettings;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardPage;
import exposed.thunder.thunderLBs.leaderboard.LeaderboardType;
import exposed.thunder.thunderLBs.leaderboard.ValueFormat;
import exposed.thunder.thunderLBs.leaderboard.ValidationIssue;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge.ProviderValue;
import exposed.thunder.thunderLBs.render.TextAlignment;
import exposed.thunder.thunderLBs.util.FormattingUtil;
import exposed.thunder.thunderLBs.util.VersionSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class LeaderboardsCommand implements CommandExecutor, TabCompleter {
    private static final String THEME = "&#38BDF8";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();
    private static final List<String> ROOT_SUBCOMMANDS = List.of("list", "create", "delete", "restore", "enable",
            "disable", "reload", "validate", "doctor", "tp", "movehere", "info", "edit", "debug", "help");
    private static final List<String> EDIT_OPTIONS = List.of("positions", "holder", "billboard", "yaw", "location",
            "textshadow", "bar", "viewdistance", "linewidth", "alignment", "relative", "duration", "animations");
    private static final List<String> ALIGNMENTS = List.of("center", "left", "right");
    private static final List<String> HOLDER_ACTIONS = List.of(
            "list", "add", "set", "remove", "interval", "type", "row", "relative");
    private static final List<String> INTERVAL_SUGGESTIONS = List.of("alltime", "hourly", "daily", "weekly",
            "monthly", "yearly");
    private static final List<String> DURATION_TYPES = List.of("interval", "page", "rowdelay", "row", "typing");
    private static final List<String> BOOLEAN_OPTIONS = List.of("true", "false");
    private static final List<String> BAR_MODES = List.of("dots", "bar", "none");
    private static final List<String> ANIMATION_PARTS = List.of("title", "rows", "progress");
    private static final List<String> ANIMATION_TYPES = List.of(
            "none",
            "linear",
            "ease_in",
            "ease_out",
            "ease_in_out",
            "ease_out_back",
            "ease_in_cubic",
            "ease_out_cubic",
            "ease_in_sine");
    private static final List<String> DEBUG_SCOPES = List.of("placeholders");
    private static final List<String> DEBUG_ACTIONS = List.of("on", "off", "toggle");

    private final ThunderLBs plugin;
    private final LeaderboardManager manager;
    private final LeaderboardEditor editDialogs;

    public LeaderboardsCommand(ThunderLBs plugin, LeaderboardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.editDialogs = createEditor(plugin, manager);
    }

    private static LeaderboardEditor createEditor(ThunderLBs plugin, LeaderboardManager manager) {
        if (!VersionSupport.dialogsSupported()) {
            return null;
        }
        try {
            return (LeaderboardEditor) Class.forName("exposed.thunder.thunderLBs.commands.LeaderboardEditDialogs")
                    .getConstructor(ThunderLBs.class, LeaderboardManager.class)
                    .newInstance(plugin, manager);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not initialize the dialog editor; falling back to chat-based editing.", e);
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> handleList(sender);
            case "create" -> handleCreate(sender, label, args);
            case "delete" -> handleDelete(sender, args);
            case "restore" -> handleRestore(sender, args);
            case "enable" -> handleEnabled(sender, args, true);
            case "disable" -> handleEnabled(sender, args, false);
            case "reload" -> handleReload(sender, args);
            case "validate" -> handleValidation(sender, args, false);
            case "doctor" -> handleValidation(sender, args, true);
            case "tp" -> handleTeleport(sender, label, args);
            case "movehere" -> handleMoveHere(sender, label, args);
            case "info" -> handleInfo(sender, label, args);
            case "edit" -> handleEdit(sender, label, args);
            case "debug" -> handleDebug(sender, label, args);
            case "help" -> sendUsage(sender, label);
            default -> {
                sendPrefixed(sender, "&cUnknown subcommand '&f" + args[0] + "&c'.");
                sendUsage(sender, label);
            }
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<Leaderboard> boards = new ArrayList<>(manager.all());
        String header = plugin.getPluginConfig().prefixedMessage("list-header",
                Map.of("count", String.valueOf(boards.size())));
        send(sender, header);
        if (boards.isEmpty()) {
            return;
        }
        for (Leaderboard leaderboard : boards) {
            LeaderboardDefinition def = leaderboard.definition();
            String message = plugin.getPluginConfig().messages().format("list-entry", Map.of(
                    "id", def.id(),
                    "location", FormattingUtil.formatLocation(def.origin()),
                    "pages", String.valueOf(def.pages().size()),
                    "positions", String.valueOf(def.settings().positions()),
                    "enabled", String.valueOf(def.enabled())));
            send(sender, message);
        }
    }

    private void handleCreate(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /" + label + " create <id> [holder]");
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getPluginConfig().prefixedMessage("not-player", null));
            return;
        }
        String id = args[1];
        if (!LeaderboardFiles.isValidId(id)) {
            sendPrefixed(sender, "&cInvalid ID. Use only letters, numbers, underscores, and hyphens (maximum 64 characters).");
            return;
        }
        String holder = args.length >= 3 ? args[2] : "kills";
        Location origin = player.getLocation().clone();
        origin.setPitch(0.0F);
        origin.setYaw(0.0F);
        Optional<Leaderboard> created = manager.create(id, origin, holder, LeaderboardType.PLAYER);
        if (created.isEmpty()) {
            send(sender, plugin.getPluginConfig().prefixedMessage("exists", Map.of("id", id)));
            return;
        }
        send(sender, plugin.getPluginConfig().prefixedMessage("created", Map.of(
                "id", id,
                "location", FormattingUtil.formatLocation(origin))));
        if (args.length < 3) {
            sendPrefixed(sender, "&7No holder given, so &f" + holder + " &7was used. Change it with &f/" + label
                    + " edit " + id + " holder set 1 <holderId> ...");
        }
        String path = new java.io.File(manager.folder(), id.toLowerCase(Locale.ROOT) + ".yml").getName();
        send(sender, plugin.getPluginConfig().prefixedMessage("created-file", Map.of("path", path)));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /leaderboards delete <id> confirm");
            return;
        }
        String id = args[1];
        if (!manager.configuredIds().contains(LeaderboardFiles.normalizeId(id))) {
            send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", id)));
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            sendPrefixed(sender, "&eThis moves &f" + id + " &eto the trash. Run &f/leaderboards delete " + id
                    + " confirm &eto continue.");
            return;
        }
        LeaderboardManager.OperationResult result = manager.trash(id);
        if (!result.success()) {
            sendPrefixed(sender, "&cCould not delete leaderboard: &f" + result.message());
            return;
        }
        send(sender, plugin.getPluginConfig().prefixedMessage("deleted", Map.of("id", id)));
        sendPrefixed(sender, "The file can be recovered with &f/leaderboards restore " + id + "&7.");
    }

    private void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /leaderboards restore <id>");
            return;
        }
        LeaderboardManager.OperationResult result = manager.restore(args[1]);
        if (!result.success()) {
            sendOperationFailure(sender, result);
            return;
        }
        sendPrefixed(sender, result.message());
    }

    private void handleEnabled(CommandSender sender, String[] args, boolean enabled) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /leaderboards " + (enabled ? "enable" : "disable") + " <id>");
            return;
        }
        LeaderboardManager.OperationResult result = manager.setEnabled(args[1], enabled);
        if (!result.success()) {
            sendOperationFailure(sender, result);
            return;
        }
        sendPrefixed(sender, "Leaderboard &f" + args[1] + " &7is now &f"
                + (enabled ? "enabled" : "disabled") + "&7.");
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
            LeaderboardManager.LoadSummary summary = plugin.reloadPluginConfig();
            if (summary.successful()) {
                send(sender, plugin.getPluginConfig().prefixedMessage("reloaded-all", Map.of()));
            } else {
                sendPrefixed(sender, "&eReloaded &f" + summary.loaded() + " &eboard(s), retained &f" + summary.retained()
                        + " &elast-known-good board(s), and found &f" + summary.failed() + " &eerror(s).");
                for (String failure : summary.failures()) {
                    send(sender, "  &c- " + failure);
                }
                send(sender, "  &8Tip: &7Run &f/leaderboards validate all &7for field-level details.");
            }
            return;
        }
        String id = args[1];
        LeaderboardManager.OperationResult result = manager.reloadDetailed(id);
        if (!result.success()) {
            sendOperationFailure(sender, result);
            return;
        }
        sendValidationWarnings(sender, result.issues());
        send(sender, plugin.getPluginConfig().prefixedMessage("reloaded", Map.of("id", id)));
    }

    private void handleValidation(CommandSender sender, String[] args, boolean doctor) {
        if (doctor) {
            sendPrefixed(sender, "&fThunderLBs doctor");
            diagnoseRenderBackend(sender);
            send(sender, "&8- &7PlaceholderAPI: &f" + dependencyState("PlaceholderAPI"));
            send(sender, "&8- &7Provider: &f" + plugin.getPluginConfig().providerName()
                    + " &8(&f" + providerState() + "&8)");
            send(sender, "&8- &7PacketEvents: &f" + dependencyState("packetevents"));
            diagnoseCustomFonts(sender);
            diagnoseProviderPatterns(sender);
        }

        String requested = args.length >= 2 ? args[1] : "all";
        List<String> ids;
        if (requested.equalsIgnoreCase("all")) {
            ids = manager.configuredIds();
        } else {
            if (!manager.configuredIds().contains(LeaderboardFiles.normalizeId(requested))) {
                send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", requested)));
                return;
            }
            ids = List.of(LeaderboardFiles.normalizeId(requested));
        }
        if (ids.isEmpty()) {
            sendPrefixed(sender, "No leaderboard configurations to validate.");
            return;
        }

        int errors = 0;
        int warnings = 0;
        for (String id : ids) {
            LeaderboardManager.OperationResult result = manager.validateFile(id);
            List<ValidationIssue> issues = result.issues();
            if (result.success() && issues.isEmpty()) {
                send(sender, "&a✔ &f" + id + " &7is valid.");
                if (doctor) {
                    diagnoseLineWidth(sender, manager.get(id));
                    diagnosePlaceholders(sender, manager.get(id));
                }
                continue;
            }
            sendPrefixed(sender, "Validation for &f" + id + "&7:");
            if (issues.isEmpty()) {
                errors++;
                send(sender, "&cERROR &fconfiguration&7: " + result.message());
                continue;
            }
            for (ValidationIssue issue : issues) {
                if (issue.severity() == ValidationIssue.Severity.ERROR) {
                    errors++;
                } else {
                    warnings++;
                }
                sendValidationIssue(sender, issue);
            }
            if (doctor && result.success()) {
                diagnoseLineWidth(sender, manager.get(id));
                diagnosePlaceholders(sender, manager.get(id));
            }
        }
        sendPrefixed(sender, (errors == 0 ? "" : "&c") + "Validation complete: &f" + errors + " error(s)&7, &f"
                + warnings + " warning(s)&7.");
    }

    private void diagnoseRenderBackend(CommandSender sender) {
        String active = plugin.renderBackend().name();
        String configured = plugin.getPluginConfig().renderMode().name().toLowerCase(Locale.ROOT);
        if (active.equalsIgnoreCase(configured)) {
            send(sender, "&8- &7Render backend: &f" + active);
            return;
        }
        send(sender, "&8- &7Render backend: &f" + active + " &8(&econfigured: " + configured + "&8)");
        send(sender, "&eWARNING &frender.mode&7: &f" + configured + " &7is not available, so &f" + active
                + " &7is being used instead. Install PacketEvents or set render.mode to &f" + active + "&7.");
    }

    private void diagnoseCustomFonts(CommandSender sender) {
        List<String> present = exposed.thunder.thunderLBs.font.GlyphWidthBridges
                .detected(Bukkit.getPluginManager());
        if (present.isEmpty()) {
            return;
        }
        send(sender, "&8- &7Custom fonts: &f" + String.join(", ", present));
        send(sender, "&eWARNING &fformatting.padding&7: ThunderLBs cannot read their glyph widths, so &f<pad:...>"
                + " &7assumes &f" + plugin.getPluginConfig().fallbackGlyphAdvance()
                + "px &7per resource-pack character. Set formatting.padding.glyph-widths for any that measure"
                + " wrongly.");
    }

    private void diagnoseLineWidth(CommandSender sender, Leaderboard board) {
        if (board == null) {
            return;
        }
        LeaderboardDefinition definition = board.definition();
        int fitted = manager.validator().suggestedLineWidth(definition);
        if (fitted <= 0) {
            return;
        }
        if (fitted <= definition.lineWidth()) {
            send(sender, "&8  &7line width &f" + definition.lineWidth() + "px &7fits its widest format (&f"
                    + fitted + "px &7needed).");
            return;
        }
        send(sender, "&eWARNING &f" + definition.id() + ".line-width&7: &f" + definition.lineWidth()
                + "px &7is under the &f" + fitted + "px &7its widest format needs. &f/lb edit "
                + definition.id() + " linewidth " + fitted);
    }

    private String dependencyState(String name) {
        org.bukkit.plugin.Plugin dependency = Bukkit.getPluginManager().getPlugin(name);
        if (dependency == null) {
            return "missing";
        }
        return dependency.isEnabled() ? "enabled (" + dependency.getPluginMeta().getVersion() + ")" : "disabled";
    }

    private String providerState() {
        String provider = plugin.getPluginConfig().providerName();
        if (provider.equalsIgnoreCase("topper")) {
            return dependencyState("Topper");
        }
        if (provider.equalsIgnoreCase("ajleaderboards")) {
            return dependencyState("ajLeaderboards");
        }
        if (provider.equalsIgnoreCase("timedtopper")) {
            return dependencyState("TimedTopper");
        }
        return "custom patterns";
    }

    private void diagnoseProviderPatterns(CommandSender sender) {
        if (plugin.getPlaceholderBridge().isNativeProvider(plugin.getPluginConfig().providerName())) {
            return;
        }
        exposed.thunder.thunderLBs.config.PluginConfig.Provider provider = plugin.getPluginConfig().provider();
        String[] names = { "name", "value", "viewer-rank", "viewer-value" };
        String[] values = { provider.name(), provider.value(), provider.viewerRank(), provider.viewerValue() };
        for (int index = 0; index < names.length; index++) {
            if (values[index] == null || values[index].isBlank()) {
                send(sender, "&cERROR &fproviders." + plugin.getPluginConfig().providerName() + "." + names[index]
                        + "&7: required provider pattern is empty");
            }
        }
    }

    private void diagnosePlaceholders(CommandSender sender, Leaderboard board) {
        if (board == null || board.definition().pages().isEmpty()
                || !plugin.getPlaceholderBridge().isAvailable()) {
            return;
        }
        LeaderboardDefinition definition = board.definition();
        for (LeaderboardPage page : definition.pages()) {
            if (plugin.getPlaceholderBridge().supportsProvider(
                    plugin.getPluginConfig().providerName(), page.effectiveType(definition.type()))) {
                diagnoseNativeValue(sender, definition, page, "name", ProviderValue.TOP_NAME);
                diagnoseNativeValue(sender, definition, page, "value", ProviderValue.TOP_VALUE);
                continue;
            }
            exposed.thunder.thunderLBs.config.PluginConfig.Provider provider = plugin.getPluginConfig().provider();
            boolean group = page.effectiveType(definition.type()) == LeaderboardType.GROUP;
            diagnosePlaceholder(sender, definition.id(), "name", group ? provider.groupName() : provider.name(),
                    page.holderId(), page.interval());
            diagnosePlaceholder(sender, definition.id(), "value", group ? provider.groupValue() : provider.value(),
                    page.holderId(), page.interval());
        }
    }

    private void diagnosePlaceholder(CommandSender sender, String boardId, String type, String pattern,
            String holder, String interval) {
        if (pattern == null || pattern.isBlank()) {
            send(sender, "&eWARNING &f" + boardId + ".provider." + type + "&7: provider pattern is empty");
            return;
        }
        String placeholder = exposed.thunder.thunderLBs.config.PluginConfig.Provider
                .applyInterval(pattern, interval)
                .replace("%holder%", holder)
                .replace("%position%", "1");
        String resolved = plugin.getPlaceholderBridge().resolve(placeholder);
        if (resolved == null || resolved.equals(placeholder) || resolved.contains("%")) {
            send(sender, "&eWARNING &f" + boardId + ".provider." + type
                    + "&7: sample placeholder did not resolve (&f" + placeholder + "&7)");
        }
    }

    private void diagnoseNativeValue(
            CommandSender sender,
            LeaderboardDefinition definition,
            LeaderboardPage page,
            String type,
            ProviderValue value) {
        String resolved = plugin.getPlaceholderBridge().resolveProviderNow(
                plugin.getPluginConfig().providerName(),
                page.effectiveType(definition.type()),
                page.holderId(),
                page.interval(),
                1,
                value,
                null,
                "");
        if (resolved == null || resolved.isBlank()) {
            send(sender, "&eWARNING &f" + definition.id() + ".provider." + type
                    + "&7: sample native query did not resolve");
        }
    }

    private void handleTeleport(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /" + label + " tp <id>");
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getPluginConfig().prefixedMessage("not-player", null));
            return;
        }
        Leaderboard leaderboard = manager.get(args[1]);
        if (leaderboard == null) {
            send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", args[1])));
            return;
        }
        Location target = leaderboard.definition().origin();
        if (target.getWorld() == null) {
            sendPrefixed(sender, "&cThe leaderboard world is not loaded. Use movehere or load the world first.");
            return;
        }
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleportAsync(target).thenAccept(success -> plugin.taskScheduler().executeDelayed(player, () -> {
                    if (!success) {
                        sendPrefixed(player, "&cCould not teleport to that leaderboard.");
                        return;
                    }
                    String message = plugin.getPluginConfig().prefixedMessage("teleported",
                            Map.of("id", leaderboard.definition().id()));
                    if (message.isEmpty()) {
                        message = plugin.getPluginConfig().messages().prefix() + "Teleported to leaderboard &f"
                                + leaderboard.definition().id() + "&7.";
                    }
                    send(player, message);
                }, null, 1L));
    }

    private void handleMoveHere(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /" + label + " movehere <id>");
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getPluginConfig().prefixedMessage("not-player", null));
            return;
        }
        Leaderboard leaderboard = manager.get(args[1]);
        if (leaderboard == null) {
            send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", args[1])));
            return;
        }
        LeaderboardDefinition definition = leaderboard.definition();
        Location location = player.getLocation().clone();
        location.setPitch(0.0F);
        definition.setOrigin(location);
        if (saveDefinition(sender, definition)) {
            String message = plugin.getPluginConfig().prefixedMessage("moved", Map.of(
                    "id", definition.id(),
                    "location", FormattingUtil.formatLocation(definition.origin())));
            if (message.isEmpty()) {
                message = plugin.getPluginConfig().messages().prefix() + "Moved leaderboard &f" + definition.id()
                        + " &7to &f" + FormattingUtil.formatLocation(definition.origin()) + "&7.";
            }
            send(sender, message);
        }
    }

    private void handleInfo(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendPrefixed(sender, "&cUsage: /" + label + " info <id>");
            return;
        }
        Leaderboard leaderboard = manager.get(args[1]);
        if (leaderboard == null) {
            send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", args[1])));
            return;
        }
        LeaderboardDefinition def = leaderboard.definition();
        LeaderboardSettings settings = def.settings();
        sendPrefixed(sender, "&fLeaderboard " + def.id() + " &7("
                + def.type().name().toLowerCase(Locale.ROOT) + "):");
        send(sender, "&8- &7Location: &f" + FormattingUtil.formatLocation(def.origin())
                + " &7yaw &f" + String.format(Locale.ROOT, "%.1f", def.origin().getYaw()));
        send(sender, "&8- &7Positions: &f" + settings.positions()
                + " &8| &7View distance: &f" + def.viewDistance() + " blocks");
        send(sender, "&8- &7Line width: &f" + def.lineWidth() + "px"
                + " &8| &7Alignment: &f" + def.alignment().configName());
        send(sender, "&8- &7Enabled: &f" + def.enabled()
                + " &8| &7Relative line: &f" + settings.showRelativePosition());
        send(sender, "&8- &7Billboard: &f" + def.billboard().name().toLowerCase(Locale.ROOT)
                + " &8| &7Text shadow: &f" + def.textShadow()
                + " &8| &7Bar: &f" + def.barMode().name().toLowerCase(Locale.ROOT));
        send(sender, "&8- &7Interval: &f" + settings.intervalTicks() + "t &8| &7Page duration: &f"
                + settings.pageDurationTicks() + "t &8| &7Row delay: &f" + settings.rowDelayTicks() + "t");
        send(sender, "&8- &7Pages (&f" + def.pages().size() + "&7):");
        List<LeaderboardPage> pages = def.pages();
        for (int i = 0; i < pages.size(); i++) {
            LeaderboardPage page = pages.get(i);
            send(sender, "  &8#" + (i + 1) + " &f" + page.holderId() + " &8→ &7" + page.title()
                    + " &8| &7" + page.effectiveType(def.type()).name()
                    + " &8| &7" + page.color() + " &8| &7" + page.valueFormat().name());
        }
    }

    private void handleDebug(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sendDebugUsage(sender, label);
            return;
        }
        String scope = args[1].toLowerCase(Locale.ROOT);
        if (!scope.equals("placeholders")) {
            sendPrefixed(sender, "&cUnknown debug category. Available: &fplaceholders&c.");
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getPluginConfig().prefixedMessage("not-player", null));
            return;
        }
        if (args.length < 3) {
            boolean enabled = plugin.isPlaceholderDebugging(player.getUniqueId());
            String statusMessage = plugin.getPluginConfig().prefixedMessage("debug-placeholder-status",
                    Map.of("state", enabled ? "enabled" : "disabled"));
            if (statusMessage.isEmpty()) {
                statusMessage = plugin.getPluginConfig().messages().prefix()
                        + "Placeholder debug is currently &f" + (enabled ? "enabled" : "disabled") + "&7.";
            }
            send(sender, statusMessage);
            sendDebugUsage(sender, label);
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        boolean enabled;
        if (action.equals("toggle")) {
            enabled = plugin.togglePlaceholderDebug(player.getUniqueId());
        } else {
            Boolean value = parseBoolean(action);
            if (value == null) {
                sendDebugUsage(sender, label);
                return;
            }
            enabled = plugin.setPlaceholderDebug(player.getUniqueId(), value);
        }
        String response = plugin.getPluginConfig()
                .prefixedMessage(enabled ? "debug-placeholder-enabled" : "debug-placeholder-disabled", null);
        if (response.isEmpty()) {
            response = plugin.getPluginConfig().messages().prefix() + "Placeholder debug &f"
                    + (enabled ? "enabled" : "disabled") + "&7.";
        }
        send(sender, response);
    }

    private void sendDebugUsage(CommandSender sender, String label) {
        String usage = plugin.getPluginConfig().prefixedMessage("debug-placeholder-usage", Map.of("label", label));
        if (usage.isEmpty()) {
            usage = plugin.getPluginConfig().messages().prefix()
                    + "&cUsage: /" + label + " debug placeholders <on|off|toggle>";
        }
        send(sender, usage);
    }

    private void handleEdit(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            if (sender instanceof Player player) {
                if (args.length > 1 && args[1].equalsIgnoreCase("help")) {
                    sendEditUsage(sender, label);
                } else if (editDialogs == null) {
                    String message = plugin.getPluginConfig().prefixedMessage("editor-unsupported", null);
                    if (message.isEmpty()) {
                        message = plugin.getPluginConfig().messages().prefix()
                                + "The dialog editor needs Minecraft 1.21.7+ — inventory editor coming soon :)";
                    }
                    send(player, message);
                    sendEditUsage(sender, label);
                } else if (args.length == 1) {
                    editDialogs.openSelector(player);
                } else {
                    editDialogs.openBoard(player, args[1]);
                }
                return;
            }
            sendEditUsage(sender, label);
            return;
        }
        String id = args[1];
        Leaderboard leaderboard = manager.get(id);
        if (leaderboard == null) {
            send(sender, plugin.getPluginConfig().prefixedMessage("missing", Map.of("id", id)));
            return;
        }
        LeaderboardDefinition definition = leaderboard.definition();
        String option = args[2].toLowerCase(Locale.ROOT);
        switch (option) {
            case "positions" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " positions <amount>");
                    return;
                }
                editPositions(sender, definition, args[3]);
            }
            case "holder" -> handleHolderEdit(sender, definition, args);
            case "billboard" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " billboard <fixed|vertical>");
                    return;
                }
                editBillboard(sender, definition, args[3]);
            }
            case "yaw" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " yaw <value>");
                    return;
                }
                editYaw(sender, definition, args[3]);
            }
            case "location" -> editLocation(sender, definition, args);
            case "textshadow" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " textshadow <true|false>");
                    return;
                }
                editTextShadow(sender, definition, args[3]);
            }
            case "bar", "bartype" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " bar <dots|bar|none> [useHolderColor]");
                    return;
                }
                editBar(sender, definition, args);
            }
            case "viewdistance" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " viewdistance <blocks>");
                    return;
                }
                editViewDistance(sender, definition, args[3]);
            }
            case "linewidth" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " linewidth <pixels>");
                    return;
                }
                editLineWidth(sender, definition, args[3]);
            }
            case "alignment", "align" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " alignment <center|left|right>");
                    return;
                }
                editAlignment(sender, definition, args[3]);
            }
            case "relative" -> {
                if (args.length < 4) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " relative <true|false>");
                    return;
                }
                editRelative(sender, definition, args[3]);
            }
            case "duration" -> {
                if (args.length < 5) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id
                            + " duration <interval|page|rowdelay|typing> <ticks>");
                    return;
                }
                editDuration(sender, definition, args[3], args[4]);
            }
            case "animations" -> {
                if (args.length < 5) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + id + " animations <title|rows|progress> <type>");
                    return;
                }
                editAnimations(sender, definition, args[3], args[4]);
            }
            default -> {
                sendPrefixed(sender, "&cUnknown edit option.");
                sendEditUsage(sender, label);
            }
        }
    }

    private void handleHolderEdit(CommandSender sender, LeaderboardDefinition definition, String[] args) {
        if (args.length < 4) {
            sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id()
                    + " holder <list|add|set|remove|interval|type|row|relative> ...");
            return;
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        List<LeaderboardPage> pages = definition.pages();
        switch (action) {
            case "list" -> {
                if (pages.isEmpty()) {
                    send(sender, "&7No holder entries defined.");
                    return;
                }
                sendPrefixed(sender, "Listing holders for &f" + definition.id() + "&7:");
                for (int i = 0; i < pages.size(); i++) {
                    LeaderboardPage page = pages.get(i);
                    String summary = "&8#" + (i + 1) + " &f" + page.holderId() + " &8→ &7" + page.title()
                            + " &8| &7Type:&f " + page.effectiveType(definition.type()).name()
                            + " &8| &7Color:&f " + page.color()
                            + " &8| &7Icon:&f " + page.icon()
                            + " &8| &7Format:&f " + page.valueFormat().name()
                            + (page.hasSuffix() ? " &8| &7Suffix:&f " + page.suffix() : "")
                            + (LeaderboardPage.DEFAULT_INTERVAL.equals(page.interval()) ? ""
                                    : " &8| &7Interval:&f " + page.interval());
                    send(sender, summary);
                }
            }
            case "add" -> {
                if (args.length < 9) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id()
                            + " holder add <holderId> <title> <color> <icon> <valueFormat> [suffix]");
                    return;
                }
                String holderId = args[4];
                String title = parseDisplayText(args[5]);
                String color = args[6];
                String icon = args[7];
                ValueFormat format = parseValueFormat(sender, args[8]);
                if (format == null) {
                    return;
                }
                String suffix = parseSuffix(args, 9);
                pages.add(new LeaderboardPage(holderId, title, color, icon, format, "", suffix));
                if (saveDefinition(sender, definition)) {
                    sendPrefixed(sender, "Added holder &f" + holderId + " &7(#" + pages.size() + ").");
                }
            }
            case "set" -> {
                if (args.length < 10) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id()
                            + " holder set <index> <holderId> <title> <color> <icon> <valueFormat> [suffix]");
                    return;
                }
                Integer index = parseIndex(sender, args[4], pages.size());
                if (index == null) {
                    return;
                }
                String holderId = args[5];
                String title = parseDisplayText(args[6]);
                String color = args[7];
                String icon = args[8];
                ValueFormat format = parseValueFormat(sender, args[9]);
                if (format == null) {
                    return;
                }
                String suffix = args.length > 10 ? parseSuffix(args, 10) : pages.get(index).suffix();
                LeaderboardPage previous = pages.get(index);
                pages.set(index, new LeaderboardPage(holderId, title, color, icon, format, previous.prefix(), suffix,
                        previous.interval(), previous.type(), previous.rowPattern(), previous.relativePattern()));
                if (saveDefinition(sender, definition)) {
                    sendPrefixed(sender, "Updated holder &f#" + (index + 1) + " &7(&f" + holderId + "&7).");
                }
            }
            case "type", "row", "relative" -> {
                if (args.length < 6) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id()
                            + " holder " + action + " <index> "
                            + (action.equals("type") ? "<PLAYER|GROUP|inherit>" : "<pattern...|inherit>"));
                    return;
                }
                Integer index = parseIndex(sender, args[4], pages.size());
                if (index == null) {
                    return;
                }
                LeaderboardPage page = pages.get(index);
                if (action.equals("type")) {
                    LeaderboardType type = LeaderboardType.fromString(args[5], null);
                    if (type == null && !args[5].equalsIgnoreCase("inherit")) {
                        sendPrefixed(sender, "&cPage type must be PLAYER, GROUP, or inherit.");
                        return;
                    }
                    pages.set(index, page.withType(type));
                } else {
                    String pattern = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
                    if (pattern.equalsIgnoreCase("inherit")) {
                        pattern = null;
                    }
                    pages.set(index, action.equals("row")
                            ? page.withFormatting(pattern, page.relativePattern())
                            : page.withFormatting(page.rowPattern(), pattern));
                }
                if (saveDefinition(sender, definition)) {
                    sendPrefixed(sender, "Updated " + action + " for holder &f#" + (index + 1) + "&7.");
                }
            }
            case "interval" -> {
                if (args.length < 6) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id()
                            + " holder interval <index> <interval>");
                    return;
                }
                Integer index = parseIndex(sender, args[4], pages.size());
                if (index == null) {
                    return;
                }
                String interval = args[5].toLowerCase(Locale.ROOT);
                pages.set(index, pages.get(index).withInterval(interval));
                if (saveDefinition(sender, definition)) {
                    sendPrefixed(sender, "Interval for holder &f#" + (index + 1) + " &7set to &f"
                            + pages.get(index).interval() + "&7.");
                }
            }
            case "remove" -> {
                if (args.length < 5) {
                    sendPrefixed(sender, "&cUsage: /leaderboards edit " + definition.id() + " holder remove <index>");
                    return;
                }
                Integer index = parseIndex(sender, args[4], pages.size());
                if (index == null) {
                    return;
                }
                if (pages.size() == 1) {
                    sendPrefixed(sender, "&cA leaderboard must keep at least one holder.");
                    return;
                }
                LeaderboardPage removed = pages.remove((int) index);
                if (saveDefinition(sender, definition)) {
                    sendPrefixed(sender, "Removed holder &f" + removed.holderId() + " &7(#" + (index + 1) + ").");
                }
            }
            default -> sendPrefixed(sender,
                    "&cUnknown holder action. Use list, add, set, remove, interval, type, row, or relative.");
        }
    }

    private void editPositions(CommandSender sender, LeaderboardDefinition definition, String value) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cPositions must be a number.");
            return;
        }
        if (amount <= 0) {
            sendPrefixed(sender, "&cPositions must be greater than zero.");
            return;
        }
        definition.settings().setPositions(amount);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Positions set to &f" + amount + "&7.");
        }
    }

    private void editBillboard(CommandSender sender, LeaderboardDefinition definition, String value) {
        Display.Billboard billboard = parseBillboard(value);
        if (billboard == null) {
            sendPrefixed(sender, "&cInvalid billboard type. Use fixed or vertical.");
            return;
        }
        definition.setBillboard(billboard);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Billboard set to &f"
                    + definition.billboard().name().toLowerCase(Locale.ROOT) + "&7.");
        }
    }

    private void editYaw(CommandSender sender, LeaderboardDefinition definition, String value) {
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cYaw must be a number.");
            return;
        }
        Location origin = definition.origin();
        origin.setYaw((float) parsed);
        definition.setOrigin(origin);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Yaw set to &f" + String.format(Locale.ROOT, "%.1f", origin.getYaw()) + "&7.");
        }
    }

    private void editLocation(CommandSender sender, LeaderboardDefinition definition, String[] args) {
        if (args.length >= 6) {
            Double x = parseCoordinate(args[3]);
            Double y = parseCoordinate(args[4]);
            Double z = parseCoordinate(args[5]);
            if (x == null || y == null || z == null) {
                sendPrefixed(sender, "&cCoordinates must be numbers.");
                return;
            }
            float yaw = definition.origin().getYaw();
            if (args.length >= 7) {
                Float parsedYaw = parseYaw(args[6]);
                if (parsedYaw == null) {
                    sendPrefixed(sender, "&cYaw must be a number.");
                    return;
                }
                yaw = parsedYaw;
            }
            World world = definition.origin().getWorld();
            if (world == null && sender instanceof Player playerSender) {
                world = playerSender.getWorld();
            }
            if (world == null) {
                sendPrefixed(sender, "&cUnable to determine world for leaderboard location.");
                return;
            }
            Location updated = new Location(world, x, y, z, yaw, 0.0F);
            definition.setOrigin(updated);
            if (saveDefinition(sender, definition)) {
                sendPrefixed(sender, "New location: &f" + FormattingUtil.formatLocation(definition.origin()));
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getPluginConfig().prefixedMessage("not-player", null));
            return;
        }
        Location playerLocation = player.getLocation().clone();
        playerLocation.setPitch(0.0F);
        definition.setOrigin(playerLocation);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "New location: &f" + FormattingUtil.formatLocation(definition.origin()));
        }
    }

    private Double parseCoordinate(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Float parseYaw(String raw) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void editTextShadow(CommandSender sender, LeaderboardDefinition definition, String value) {
        Boolean parsed = parseBoolean(value);
        if (parsed == null) {
            sendPrefixed(sender, "&cText shadow must be true or false.");
            return;
        }
        definition.setTextShadow(parsed);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Text shadow set to &f" + parsed + "&7.");
        }
    }

    private void editBar(CommandSender sender, LeaderboardDefinition definition, String[] args) {
        BarMode mode = BarMode.fromString(args[3], null);
        if (mode == null) {
            sendPrefixed(sender, "&cInvalid bar type. Use dots, bar, or none.");
            return;
        }
        boolean useHolderColor = definition.barUseHolderColor();
        if (args.length >= 5) {
            Boolean parsed = parseBoolean(args[4]);
            if (parsed == null) {
                sendPrefixed(sender, "&cuseHolderColor must be true or false.");
                return;
            }
            useHolderColor = parsed;
        }
        definition.setBarSettings(mode, useHolderColor);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, String.format(Locale.ROOT, "Bar: &f%s &8| &7Use holder color: &f%s",
                    definition.barMode().name().toLowerCase(Locale.ROOT),
                    definition.barUseHolderColor()));
        }
    }

    private void editViewDistance(CommandSender sender, LeaderboardDefinition definition, String value) {
        int distance;
        try {
            distance = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cView distance must be a number of blocks.");
            return;
        }
        if (distance < 4 || distance > 256) {
            sendPrefixed(sender, "&cView distance must be between 4 and 256 blocks.");
            return;
        }
        definition.setViewDistance(distance);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "View distance set to &f" + definition.viewDistance() + " &7blocks.");
        }
    }

    private void editLineWidth(CommandSender sender, LeaderboardDefinition definition, String value) {
        int pixels;
        try {
            pixels = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cLine width must be a number of pixels.");
            return;
        }
        if (pixels < PluginConfig.MIN_LINE_WIDTH || pixels > PluginConfig.MAX_LINE_WIDTH) {
            sendPrefixed(sender, "&cLine width must be between " + PluginConfig.MIN_LINE_WIDTH + " and "
                    + PluginConfig.MAX_LINE_WIDTH + " pixels.");
            return;
        }
        definition.setLineWidth(pixels);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Line width set to &f" + definition.lineWidth() + " &7pixels.");
        }
    }

    private void editAlignment(CommandSender sender, LeaderboardDefinition definition, String value) {
        TextAlignment parsed = TextAlignment.fromString(value, null);
        if (parsed == null) {
            sendPrefixed(sender, "&cAlignment must be center, left or right.");
            return;
        }
        definition.setAlignment(parsed);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Alignment set to &f" + definition.alignment().configName() + "&7.");
            if (parsed != TextAlignment.LEFT) {
                sendPrefixed(sender, "&7Note: &f<pad:...> &7columns only line up across rows when alignment is left.");
            }
        }
    }

    private void editRelative(CommandSender sender, LeaderboardDefinition definition, String value) {
        Boolean parsed = parseBoolean(value);
        if (parsed == null) {
            sendPrefixed(sender, "&cRelative must be true or false.");
            return;
        }
        definition.settings().setShowRelativePosition(parsed);
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, "Relative rank line set to &f" + parsed + "&7.");
        }
    }

    private void editDuration(CommandSender sender, LeaderboardDefinition definition, String type, String value) {
        long ticks;
        try {
            ticks = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cTicks must be a number.");
            return;
        }
        if (ticks <= 0) {
            sendPrefixed(sender, "&cTicks must be greater than zero.");
            return;
        }
        LeaderboardSettings settings = definition.settings();
        String normalized = type.toLowerCase(Locale.ROOT);
        String label;
        switch (normalized) {
            case "interval" -> {
                settings.setIntervalTicks(ticks);
                label = "Interval";
            }
            case "page" -> {
                settings.setPageDurationTicks(ticks);
                label = "Page duration";
            }
            case "rowdelay", "row" -> {
                settings.setRowDelayTicks(ticks);
                label = "Row delay";
            }
            case "typing" -> {
                settings.setTypingIntervalTicks(ticks);
                label = "Typing interval";
            }
            default -> {
                sendPrefixed(sender, "&cUnknown duration type. Use interval, page, rowdelay, or typing.");
                return;
            }
        }
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, label + " set to &f" + ticks + " &7ticks.");
        }
    }

    private void editAnimations(CommandSender sender, LeaderboardDefinition definition, String target,
            String typeArgument) {
        String part = target.toLowerCase(Locale.ROOT);
        boolean disable = typeArgument.equalsIgnoreCase("none");
        EasingType easing = disable ? null : EasingType.fromFriendly(typeArgument, null);
        if (!disable && easing == null) {
            sendPrefixed(sender, "&cUnknown animation type. Options: &f" + String.join("&7, &f", ANIMATION_TYPES));
            return;
        }
        switch (part) {
            case "title" -> {
                definition.animations().title().setEnabled(!disable);
                if (!disable) {
                    definition.animations().title().setInCurve(easing);
                    definition.animations().title().setOutCurve(easing);
                }
            }
            case "rows" -> {
                definition.animations().row().setEnabled(!disable);
                if (!disable) {
                    definition.animations().row().setInCurve(easing);
                    definition.animations().row().setOutCurve(easing);
                }
            }
            case "progress" -> {
                definition.animations().bar().setEnabled(!disable);
                if (!disable) {
                    definition.animations().bar().setCurve(easing);
                }
            }
            default -> {
                sendPrefixed(sender, "&cUnknown animation part. Use title, rows, or progress.");
                return;
            }
        }
        if (saveDefinition(sender, definition)) {
            sendPrefixed(sender, String.format(Locale.ROOT, "Updated %s animation to &f%s&7.", part,
                    typeArgument.toLowerCase(Locale.ROOT)));
        }
    }

    private boolean saveDefinition(CommandSender sender, LeaderboardDefinition definition) {
        LeaderboardManager.OperationResult result = manager.saveAndReload(definition);
        if (!result.success()) {
            sendOperationFailure(sender, result);
            return false;
        }
        sendValidationWarnings(sender, result.issues());
        send(sender, plugin.getPluginConfig().prefixedMessage("saved", Map.of("id", definition.id())));
        return true;
    }

    private void sendOperationFailure(CommandSender sender, LeaderboardManager.OperationResult result) {
        sendPrefixed(sender, "&c" + result.message());
        for (ValidationIssue issue : result.issues()) {
            sendValidationIssue(sender, issue);
        }
    }

    private void sendValidationWarnings(CommandSender sender, List<ValidationIssue> issues) {
        issues.stream()
                .filter(issue -> issue.severity() == ValidationIssue.Severity.WARNING)
                .forEach(issue -> sendValidationIssue(sender, issue));
    }

    private void sendValidationIssue(CommandSender sender, ValidationIssue issue) {
        String color = issue.severity() == ValidationIssue.Severity.ERROR ? "&c" : "&e";
        send(sender, color + issue.severity().name() + " &f" + issue.path() + "&7: " + issue.message());
    }

    private String parseDisplayText(String argument) {
        return argument.replace('_', ' ');
    }

    private String parseSuffix(String[] args, int startIndex) {
        if (args.length <= startIndex) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        String value = builder.toString().replace('_', ' ').trim();
        return value.equalsIgnoreCase("none") ? "" : value;
    }

    private Integer parseIndex(CommandSender sender, String raw, int size) {
        int index;
        try {
            index = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            sendPrefixed(sender, "&cIndex must be a number.");
            return null;
        }
        if (index <= 0 || index > size) {
            sendPrefixed(sender, "&cIndex must be between 1 and " + size + ".");
            return null;
        }
        return index - 1;
    }

    private ValueFormat parseValueFormat(CommandSender sender, String raw) {
        try {
            return ValueFormat.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sendPrefixed(sender, "&cUnknown value format. Available: &f" + String.join("&7, &f", EnumSet.allOf(ValueFormat.class)
                    .stream()
                    .map(Enum::name)
                    .collect(Collectors.toList())));
            return null;
        }
    }

    private Boolean parseBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> Boolean.TRUE;
            case "false", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void sendUsage(CommandSender sender, String label) {
        sendPrefixed(sender, "&fThunderLBs &7v" + plugin.getPluginMeta().getVersion());
        sendHelpLine(sender, "/" + label + " list", "", "List active leaderboards", true);
        sendHelpLine(sender, "/" + label + " create", "<id> [holder]", "Create a leaderboard here", false);
        sendHelpLine(sender, "/" + label + " delete", "<id> confirm", "Move a leaderboard to trash", false);
        sendHelpLine(sender, "/" + label + " restore", "<id>", "Restore the latest deleted copy", false);
        sendHelpLine(sender, "/" + label + " enable", "<id>", "Enable leaderboard rendering", false);
        sendHelpLine(sender, "/" + label + " disable", "<id>", "Disable leaderboard rendering", false);
        sendHelpLine(sender, "/" + label + " reload", "[id|all]", "Reload leaderboard configuration", false);
        sendHelpLine(sender, "/" + label + " validate", "[id|all]", "Check leaderboard configuration", false);
        sendHelpLine(sender, "/" + label + " doctor", "[id|all]", "Check dependencies and configuration", false);
        sendHelpLine(sender, "/" + label + " tp", "<id>", "Teleport to a leaderboard", false);
        sendHelpLine(sender, "/" + label + " movehere", "<id>", "Move a leaderboard here", false);
        sendHelpLine(sender, "/" + label + " info", "<id>", "Show leaderboard details", false);
        sendHelpLine(sender, "/" + label + " edit", "", "Show leaderboard editing commands", true);
        sendHelpLine(sender, "/" + label + " debug", "placeholders <on|off|toggle>", "Toggle placeholder debug output", false);
    }

    private void sendEditUsage(CommandSender sender, String label) {
        sendPrefixed(sender, "&fThunderLBs edit commands");
        sendHelpLine(sender, "/" + label + " edit", "<id> positions <amount>", "Change visible entries", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> holder add|set|remove|list|interval|type|row|relative ...", "Manage holders",
                false);
        sendHelpLine(sender, "/" + label + " edit", "<id> location [x y z (yaw)]", "Change the origin", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> textshadow <true|false>", "Toggle text shadows", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> bar <dots|bar|none> [useHolderColor]", "Configure the page bar", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> viewdistance <blocks>", "Set the visibility range", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> linewidth <pixels>", "Set the wrap width", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> alignment <center|left|right>",
                "Align lines (left for <pad:...>)", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> relative <true|false>", "Toggle the viewer rank line", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> duration <type> <ticks>", "Adjust timing values", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> billboard <fixed|vertical>", "Change billboard behavior", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> yaw <value>", "Update the facing direction", false);
        sendHelpLine(sender, "/" + label + " edit", "<id> animations <part> <type>", "Adjust animation easing", false);
    }

    private void sendHelpLine(CommandSender sender, String command, String displayArgs, String description,
            boolean runDirectly) {
        String displayedCommand = command + (displayArgs.isEmpty() ? "" : " " + displayArgs);
        Component line = LEGACY.deserialize("  " + THEME + displayedCommand)
                .clickEvent(runDirectly ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command + " "))
                .hoverEvent(HoverEvent.showText(Component.text(runDirectly ? "Click to run" : "Click to insert")))
                .append(LEGACY.deserialize(" &8- &7" + description));
        sender.sendMessage(line);
    }

    private void sendPrefixed(CommandSender sender, String message) {
        send(sender, plugin.getPluginConfig().messages().prefix() + message);
    }

    private void send(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        sender.sendMessage(LEGACY.deserialize(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], ROOT_SUBCOMMANDS);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (root.equals("debug")) {
                return partial(args[1], DEBUG_SCOPES);
            }
            if (root.equals("restore")) {
                return partial(args[1], manager.trashedIds());
            }
            if (root.equals("delete") || root.equals("enable") || root.equals("disable")
                    || root.equals("edit") || root.equals("reload") || root.equals("validate") || root.equals("doctor")
                    || root.equals("tp") || root.equals("movehere") || root.equals("info")) {
                List<String> ids = root.equals("delete") || root.equals("validate") || root.equals("doctor")
                        ? manager.configuredIds()
                        : manager.all().stream()
                                .map(lb -> lb.definition().id())
                                .collect(Collectors.toList());
                if (root.equals("reload") || root.equals("validate") || root.equals("doctor")) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("all");
                    suggestions.addAll(ids);
                    return partial(args[1], suggestions);
                }
                return partial(args[1], ids);
            }
        }
        if (root.equals("create") && args.length == 3) {
            return partial(args[2], holderSuggestions(LeaderboardType.PLAYER));
        }
        if (root.equals("delete") && args.length == 3) {
            return partial(args[2], List.of("confirm"));
        }
        if (root.equals("debug")) {
            if (args.length == 3) {
                return partial(args[2], DEBUG_ACTIONS);
            }
            return Collections.emptyList();
        }
        if (root.equals("edit")) {
            if (args.length == 3) {
                return partial(args[2], EDIT_OPTIONS);
            }
            Leaderboard leaderboard = manager.get(args[1]);
            if (leaderboard == null) {
                return Collections.emptyList();
            }
            LeaderboardDefinition definition = leaderboard.definition();
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "holder" -> {
                    if (args.length == 4) {
                        return partial(args[3], HOLDER_ACTIONS);
                    }
                    String action = args[3].toLowerCase(Locale.ROOT);
                    if (List.of("type", "row", "relative").contains(action)) {
                        if (args.length == 5) {
                            return partial(args[4], indexSuggestions(definition.pages().size()));
                        }
                        if (args.length == 6) {
                            return partial(args[5], action.equals("type")
                                    ? List.of("PLAYER", "GROUP", "inherit") : List.of("inherit"));
                        }
                        return Collections.emptyList();
                    }
                    if (action.equals("interval")) {
                        if (args.length == 5) {
                            return partial(args[4], indexSuggestions(definition.pages().size()));
                        }
                        if (args.length == 6) {
                            int idx = parseIndexForCompletion(args[4], definition.pages().size());
                            String current = idx >= 0 ? definition.pages().get(idx).interval() : null;
                            return partial(args[5], ordered(current, intervalSuggestions(idx >= 0
                                    ? definition.pages().get(idx).effectiveType(definition.type()) : definition.type())));
                        }
                        return Collections.emptyList();
                    }
                    if (action.equals("set") || action.equals("remove")) {
                        if (definition.pages().isEmpty()) {
                            return Collections.emptyList();
                        }
                        if (args.length == 5) {
                            return partial(args[4], indexSuggestions(definition.pages().size()));
                        }
                        if (action.equals("set") && args.length == 6) {
                            int idx = parseIndexForCompletion(args[4], definition.pages().size());
                            String current = idx >= 0 ? definition.pages().get(idx).holderId() : null;
                            return partial(args[5], ordered(current, holderSuggestions(idx >= 0
                                    ? definition.pages().get(idx).effectiveType(definition.type()) : definition.type())));
                        }
                    }
                    if (action.equals("add") && args.length == 5) {
                        return partial(args[4], holderSuggestions(definition.type()));
                    }
                    int formatSlot = action.equals("add") ? 8 : action.equals("set") ? 9 : -1;
                    if (formatSlot >= 0 && args.length == formatSlot + 1) {
                        List<String> formats = EnumSet.allOf(ValueFormat.class).stream()
                                .map(f -> f.name().toLowerCase(Locale.ROOT))
                                .collect(Collectors.toList());
                        return partial(args[formatSlot], formats);
                    }
                }
                case "billboard" -> {
                    if (args.length == 4) {
                        return partial(args[3], List.of("fixed", "vertical"));
                    }
                }
                case "textshadow" -> {
                    if (args.length == 4) {
                        return partial(args[3], BOOLEAN_OPTIONS);
                    }
                }
                case "relative" -> {
                    if (args.length == 4) {
                        return partial(args[3], BOOLEAN_OPTIONS);
                    }
                }
                case "bar", "bartype" -> {
                    if (args.length == 4) {
                        return partial(args[3], BAR_MODES);
                    } else if (args.length == 5) {
                        return partial(args[4], BOOLEAN_OPTIONS);
                    }
                }
                case "positions" -> {
                    if (args.length == 4) {
                        return partial(args[3], ordered(String.valueOf(definition.settings().positions()),
                                List.of("3", "5", "10", "15", "25")));
                    }
                }
                case "viewdistance" -> {
                    if (args.length == 4) {
                        return partial(args[3], ordered(String.valueOf(definition.viewDistance()),
                                List.of("32", "48", "64", "96", "128")));
                    }
                }
                case "linewidth" -> {
                    if (args.length == 4) {
                        List<String> options = new ArrayList<>();
                        int fitted = manager.validator().suggestedLineWidth(definition);
                        if (fitted > 0) {
                            options.add(String.valueOf(fitted));
                        }
                        options.add(String.valueOf(definition.lineWidth()));
                        options.addAll(List.of("250", "300", "400", "600"));
                        return partial(args[3], ordered(null, options));
                    }
                }
                case "alignment", "align" -> {
                    if (args.length == 4) {
                        return partial(args[3], ordered(definition.alignment().name().toLowerCase(Locale.ROOT),
                                ALIGNMENTS));
                    }
                }
                case "duration" -> {
                    if (args.length == 4) {
                        return partial(args[3], DURATION_TYPES);
                    }
                    if (args.length == 5) {
                        return partial(args[4], durationSuggestions(definition, args[3]));
                    }
                }
                case "location" -> {
                    if (args.length >= 4 && args.length <= 7) {
                        return locationSuggestions(sender, definition, args);
                    }
                }
                case "animations" -> {
                    if (args.length == 4) {
                        return partial(args[3], ANIMATION_PARTS);
                    }
                    if (args.length == 5) {
                        return partial(args[4], ANIMATION_TYPES);
                    }
                }
                case "yaw" -> {
                    if (args.length == 4) {
                        List<String> options = new ArrayList<>();
                        options.add(String.format(Locale.ROOT, "%.1f", definition.origin().getYaw()));
                        if (sender instanceof Player player) {
                            options.add(String.format(Locale.ROOT, "%.1f", player.getLocation().getYaw()));
                        }
                        options.addAll(List.of("0.0", "90.0", "180.0", "-90.0"));
                        return partial(args[3], ordered(null, options));
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> holderSuggestions(LeaderboardType type) {
        return plugin.getPlaceholderBridge()
                .holderChoices(plugin.getPluginConfig().providerName(), type);
    }

    private List<String> intervalSuggestions(LeaderboardType type) {
        List<String> choices = plugin.getPlaceholderBridge()
                .intervalChoices(plugin.getPluginConfig().providerName(), type);
        return choices.isEmpty() ? INTERVAL_SUGGESTIONS : choices;
    }

    private static List<String> durationSuggestions(LeaderboardDefinition definition, String type) {
        LeaderboardSettings settings = definition.settings();
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "interval" -> ordered(String.valueOf(settings.intervalTicks()),
                    List.of("100", "200", "400", "1200"));
            case "page" -> ordered(String.valueOf(settings.pageDurationTicks()),
                    List.of("100", "160", "200", "300"));
            case "rowdelay", "row" -> ordered(String.valueOf(settings.rowDelayTicks()),
                    List.of("1", "2", "3", "5"));
            case "typing" -> ordered(String.valueOf(settings.typingIntervalTicks()),
                    List.of("1", "2", "3"));
            default -> Collections.emptyList();
        };
    }

    private static List<String> locationSuggestions(CommandSender sender, LeaderboardDefinition definition,
            String[] args) {
        Location origin = definition.origin();
        Location here = sender instanceof Player player ? player.getLocation() : null;
        int slot = args.length - 4;
        String current = switch (slot) {
            case 0 -> coordinate(origin.getX());
            case 1 -> coordinate(origin.getY());
            case 2 -> coordinate(origin.getZ());
            default -> String.format(Locale.ROOT, "%.1f", origin.getYaw());
        };
        String suggestion = null;
        if (here != null) {
            suggestion = switch (slot) {
                case 0 -> coordinate(here.getX());
                case 1 -> coordinate(here.getY());
                case 2 -> coordinate(here.getZ());
                default -> String.format(Locale.ROOT, "%.1f", here.getYaw());
            };
        }
        return partial(args[args.length - 1],
                ordered(suggestion, current == null ? List.of() : List.of(current)));
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static List<String> indexSuggestions(int size) {
        List<String> values = new ArrayList<>(Math.max(0, size));
        for (int i = 1; i <= size; i++) {
            values.add(String.valueOf(i));
        }
        return values;
    }

    private static List<String> ordered(String first, List<String> values) {
        List<String> unique = new ArrayList<>(values.size() + 1);
        if (first != null && !first.isEmpty()) {
            unique.add(first);
        }
        for (String value : values) {
            if (value != null && !value.isEmpty() && !unique.contains(value)) {
                unique.add(value);
            }
        }
        return unique;
    }

    private int parseIndexForCompletion(String raw, int size) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || value > size) {
                return -1;
            }
            return value - 1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static List<String> partial(String token, List<String> values) {
        if (token == null || token.isEmpty()) {
            return values;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private Display.Billboard parseBillboard(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "fixed" -> Display.Billboard.FIXED;
            case "vertical" -> Display.Billboard.VERTICAL;
            default -> null;
        };
    }
}
