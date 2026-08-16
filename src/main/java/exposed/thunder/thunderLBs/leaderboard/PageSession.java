package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.animation.AnimationCache;
import exposed.thunder.thunderLBs.animation.EasingType;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.placeholder.PatternPlaceholders;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge.ProviderValue;
import exposed.thunder.thunderLBs.placeholder.PlaceholderPatterns;
import exposed.thunder.thunderLBs.placeholder.ProviderQueries;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.render.DisplayOptions;
import exposed.thunder.thunderLBs.render.RenderBackend;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import exposed.thunder.thunderLBs.scheduler.TaskHandle;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PageSession {
    private static final int OPACITY_TRANSPARENT = 0;
    private static final int OPACITY_LOWER_HALF = 127;
    private static final int OPACITY_UPPER_HALF = 128;
    private static final int OPACITY_OPAQUE = 255;
    private static final int TITLE_INTERPOLATION = 1;
    private static final int ROW_INTERPOLATION = 1;
    private static final int BAR_INITIAL_INTERPOLATION = 1;
    private static final int BAR_FADE_TICKS = 10;
    private static final double BAR_DEPTH_OFFSET = 0.01D;
    private static final double COMFORTABLE_RANGE_FRACTION = 0.5D;

    private static final Pattern HEX_COLOR_TAG = Pattern.compile("<#([A-Fa-f0-9]{3,6})>");
    private static final Pattern COLOR_TAG = Pattern.compile("<color:([^>]+)>");

    private final ThunderLBs plugin;
    private final PluginConfig config;
    private final PlaceholderBridge placeholderBridge;
    private final AnimationCache animationCache;
    private final Leaderboard leaderboard;
    private final LeaderboardDefinition definition;
    private final LeaderboardPage page;
    private final RenderBackend backend;
    private final Location origin;
    private final World world;
    private final BarMode barMode;
    private final boolean barUseHolderColor;
    private final boolean debugPlaceholders;

    private final boolean rowHasPlaceholders;

    private final List<BoardDisplay> displays;
    private final List<TaskHandle> tasks;
    private final List<FrameAnimation> frameAnimations;
    private final RegionTaskScheduler scheduler;
    private static final long ROW_BACKFILL_PERIOD_TICKS = 40L;
    private static final int ROW_BACKFILL_MAX_ATTEMPTS = 15;

    private final List<String> rowStrings = new ArrayList<>();
    private final List<Component> rowComponents = new ArrayList<>();
    private BoardDisplay[] rowDisplays = new BoardDisplay[0];
    private boolean rowsPending;

    private PatternPlaceholders boardPlaceholders = PatternPlaceholders.NONE;
    private TaskHandle frameAnimationTask;
    private boolean active;
    private boolean completed;
    private int liveDisplays;
    private final Display.Billboard billboard;
    private final int pageIndex;
    private final int totalPages;
    private final float viewRange;
    private final float boardScale;

    PageSession(ThunderLBs plugin,
            PluginConfig config,
            PlaceholderBridge placeholderBridge,
            AnimationCache animationCache,
            Leaderboard leaderboard,
            LeaderboardDefinition definition,
            LeaderboardPage page,
            int pageIndex) {
        this.plugin = plugin;
        this.config = config;
        this.placeholderBridge = placeholderBridge;
        this.animationCache = animationCache;
        this.leaderboard = leaderboard;
        this.definition = definition;
        this.page = page;
        this.backend = plugin.renderBackend();
        this.origin = leaderboard.origin();
        this.world = leaderboard.world();
        this.displays = new ArrayList<>();
        this.tasks = new ArrayList<>();
        this.frameAnimations = new ArrayList<>();
        this.scheduler = RegionTaskScheduler.create(plugin);
        this.completed = false;
        this.liveDisplays = 0;
        this.billboard = definition.billboard();
        this.totalPages = Math.max(1, definition.pages().size());
        this.pageIndex = Math.max(0, Math.min(this.totalPages - 1, pageIndex));
        this.barMode = definition.barMode();
        this.barUseHolderColor = definition.barUseHolderColor();
        this.debugPlaceholders = plugin.hasPlaceholderDebugListeners();
        this.rowHasPlaceholders = PlaceholderPatterns.mayContain(definition.formatting().rowPattern());
        this.viewRange = Math.max(0.1F, definition.viewDistance() / 64.0F);
        this.boardScale = (float) definition.settings().boardScale();
    }

    public void start() {
        World world = this.world;
        if (world == null) {
            plugin.getLogger().warning("World for leaderboard '" + definition.id() + "' is not loaded. Page skipped.");
            return;
        }
        if (!leaderboard.isRenderable()) {
            checkCompletion();
            return;
        }
        this.active = true;
        this.completed = false;
        this.liveDisplays = 0;
        this.boardPlaceholders = placeholderBridge.patterns(null);
        List<RowEntry> entries = prepareEntries();
        spawnTitle(world);
        spawnBar(world);
        spawnRows(world, entries);
        if (rowsPending) {
            scheduleRowBackfill();
        }
    }

    public void stop() {
        if (completed) {
            return;
        }
        this.active = false;
        for (TaskHandle task : tasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();
        frameAnimations.clear();
        frameAnimationTask = null;
        while (!displays.isEmpty()) {
            despawnDisplay(displays.get(displays.size() - 1));
        }
        checkCompletion();
    }

    boolean hasLiveDisplays() {
        return active && liveDisplays > 0;
    }

    private DisplayOptions baseOptions() {


        return new DisplayOptions()
                .billboard(billboard)
                .lineWidth(definition.lineWidth())
                .alignment(definition.alignment())
                .viewRange(viewRange);
    }

    private BoardDisplay spawnDisplay(Location location, DisplayOptions options) {
        BoardDisplay display = backend.spawn(leaderboard, location, options);
        leaderboard.track(display);
        displays.add(display);
        liveDisplays++;
        return display;
    }

    private List<RowEntry> prepareEntries() {
        PluginConfig.Provider provider = config.provider();
        boolean nativeProvider = placeholderBridge.supportsProvider(config.providerName(), definition.type());
        if (definition.type() == LeaderboardType.GROUP && !nativeProvider && !provider.supportsGroups()) {
            plugin.getLogger().warning("Provider '" + config.providerName()
                    + "' has no group placeholders configured; leaderboard '" + definition.id()
                    + "' will not resolve.");
        }
        RowRender render = renderRows();
        List<String> normalized = render.normalized();
        List<RowEntry> rows = new ArrayList<>(normalized.size());
        rowStrings.clear();
        rowComponents.clear();
        rowDisplays = new BoardDisplay[normalized.size()];
        for (int i = 0; i < normalized.size(); i++) {
            rowStrings.add(normalized.get(i));
            rowComponents.add(plugin.deserializeMiniMessage(normalized.get(i)));
            rows.add(new RowEntry(render.offsets().get(i), i));
        }
        rowsPending = render.pending();
        return rows;
    }

    private RowRender renderRows() {
        int positions = Math.max(1, definition.settings().positions());
        List<String> circled = definition.formatting().circledNumbers(config.formatting());
        List<String> rendered = new ArrayList<>(positions);
        List<Double> offsets = new ArrayList<>(positions);
        PluginConfig.Provider provider = config.provider();
        boolean nativeProvider = placeholderBridge.supportsProvider(config.providerName(), definition.type());
        boolean pending = false;
        String holderId = page.holderId();
        String interval = page.interval();
        ProviderQueries queries = ProviderQueries.create(config.providerName(), provider,
                definition.type(), holderId, interval, nativeProvider);
        for (int index = 1; index <= positions; index++) {
            String namePlaceholder = queries.topNamePlaceholder(index);
            String valuePlaceholder = queries.topValuePlaceholder(index);
            String nameSource = queries.topNameSource(index);
            String valueSource = queries.topValueSource(index);

            String resolvedName = placeholderBridge.resolveProvider(
                    config.providerName(),
                    definition.type(),
                    holderId,
                    interval,
                    index,
                    ProviderValue.TOP_NAME,
                    null,
                    namePlaceholder);
            logPlaceholder("name", index, nameSource, resolvedName);
            String playerName = sanitize(resolvedName, nameSource);

            String resolvedValue = placeholderBridge.resolveProvider(
                    config.providerName(),
                    definition.type(),
                    holderId,
                    interval,
                    index,
                    ProviderValue.TOP_VALUE,
                    null,
                    valuePlaceholder);
            logPlaceholder("value", index, valueSource, resolvedValue);
            pending |= resolvedName == null || resolvedValue == null;
            String rawValue = sanitize(resolvedValue, valueSource);
            String formattedValue = page.valueFormat().format(rawValue);
            if (page.hasPrefix()) {
                formattedValue = page.prefix() + formattedValue;
            }
            if (page.hasSuffix()) {
                formattedValue = formattedValue + page.suffix();
            }
            String circledNumber = index <= circled.size() ? circled.get(index - 1) : String.valueOf(index);
            double offsetY = scaledRowOffset(
                    definition.settings().rowYOffset(), config.defaults().rowSpacing(), index, boardScale);

            String rowColor = definition.formatting().rankColor(index, config.formatting(), page.color());
            offsets.add(offsetY);
            rendered.add(renderRow(rowColor, circledNumber, playerName, formattedValue,
                    rowPlaceholders(playerName)));
        }

        return new RowRender(definition.formatting().normalizeRows(rendered), offsets, pending);
    }

    private void scheduleRowBackfill() {
        TaskHandle task = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            int attempts = 0;

            @Override
            public void accept(TaskHandle task) {
                if (!ensureSessionActive()) {
                    task.cancel();
                    return;
                }
                attempts++;
                boolean stillPending = refreshRowText();
                if (!stillPending || attempts >= ROW_BACKFILL_MAX_ATTEMPTS) {
                    task.cancel();
                }
            }
        }, ROW_BACKFILL_PERIOD_TICKS, ROW_BACKFILL_PERIOD_TICKS);
        tasks.add(task);
    }

    private boolean refreshRowText() {
        RowRender render = renderRows();
        List<String> normalized = render.normalized();
        for (int i = 0; i < normalized.size() && i < rowStrings.size(); i++) {
            if (normalized.get(i).equals(rowStrings.get(i))) {
                continue;
            }
            rowStrings.set(i, normalized.get(i));
            Component component = plugin.deserializeMiniMessage(normalized.get(i));
            rowComponents.set(i, component);
            BoardDisplay display = rowDisplays[i];
            if (display != null && !display.isRemoved()) {
                display.text(component);
            }
        }
        return render.pending();
    }

    private String sanitize(String value, String placeholder) {
        boolean invalid = value == null || value.isBlank() || value.equalsIgnoreCase(placeholder)
                || value.contains("%");
        return invalid ? config.missingText() : value;
    }

    private void logPlaceholder(String type, int index, String placeholder, String resolved) {
        if (debugPlaceholders) {
            plugin.emitPlaceholderDebug(definition, page, index, type, placeholder, resolved);
        }
    }



    private void spawnTitle(World world) {
        Location base = origin.clone();
        if (barMode == BarMode.NONE) {
            base.add(0.0D, config.defaults().barOffsetY() * boardScale, 0.0D);
        }
        LeaderboardAnimations.Title titleAnimation = definition.animations().title();
        LeaderboardAnimations.Opacity opacityAnimation = definition.animations().opacity();
        float[] titleFrames = animationCache.titleInScale();
        float initialScale = titleAnimation.inEnabled() ? titleFrames[0] : titleFrames[titleFrames.length - 1];
        byte initialOpacity = opacityAnimation.inEnabled()
                ? packOpacity(OPACITY_TRANSPARENT)
                : packOpacity(OPACITY_OPAQUE);
        BoardDisplay display = spawnDisplay(base, baseOptions()
                .shadowed(definition.textShadow())
                .opacity(initialOpacity)
                .scale(initialScale * boardScale, initialScale * boardScale, initialScale * boardScale)
                .interpolationDuration(TITLE_INTERPOLATION)
                .teleportDuration(TITLE_INTERPOLATION));

        scheduleTitleTyping(display);
        scheduleTitleAnimations(display);
    }

    private void scheduleTitleTyping(BoardDisplay display) {
        String fullTitle = page.title() == null ? "" : page.title();
        long interval = Math.max(1L, definition.settings().typingIntervalTicks());
        PluginConfig.Sounds.Typing typing = config.sounds().typing();
        Location base = origin;
        int steps = Math.max(1, fullTitle.length());
        TaskHandle task = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            int index = 0;

            @Override
            public void accept(TaskHandle task) {
                if (!ensureSessionActive() || display.isRemoved()) {
                    task.cancel();
                    return;
                }
                if (index >= steps) {
                    task.cancel();
                    return;
                }
                int end = Math.min(index + 1, fullTitle.length());
                display.text(plugin.deserializeMiniMessage(renderTitle(fullTitle.substring(0, end))));
                if (typing.enabled()) {
                    playTypingSound(base, typing);
                }
                index++;
            }
        }, interval, interval);
        tasks.add(task);
    }

    private String renderTitle(String title) {
        return definition.formatting().renderTitle(
                page.color(), page.icon(), Objects.requireNonNullElse(title, ""), boardPlaceholders);
    }

    private void playTypingSound(Location base, PluginConfig.Sounds.Typing typing) {
        if (world == null) {
            return;
        }
        float volume = Math.max(0.0F, typing.volume());
        float pitch = typing.pitchMin()
                + ThreadLocalRandom.current().nextFloat() * (typing.pitchMax() - typing.pitchMin());
        double radiusSquared = typing.radius() * typing.radius();
        for (Leaderboard.Viewer viewer : leaderboard.viewers()) {
            if (viewer.distanceSquared() > radiusSquared) {
                continue;
            }
            Player player = viewer.player();
            if (!player.isOnline()) {
                continue;
            }
            player.playSound(
                    typingEmissionPoint(base, viewer, volume),
                    typing.key(),
                    typing.category(),
                    volume,
                    pitch);
        }
    }

    static Location typingEmissionPoint(Location origin, Leaderboard.Viewer viewer, float volume) {
        double audibleRange = 16.0D * Math.max(1.0D, volume);
        double comfortableRange = audibleRange * COMFORTABLE_RANGE_FRACTION;
        double deltaX = origin.getX() - viewer.x();
        double deltaY = origin.getY() - viewer.y();
        double deltaZ = origin.getZ() - viewer.z();
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        if (distance <= comfortableRange || distance <= 0.0D) {
            return origin;
        }
        double scale = comfortableRange / distance;
        return new Location(
                origin.getWorld(),
                viewer.x() + deltaX * scale,
                viewer.y() + deltaY * scale,
                viewer.z() + deltaZ * scale);
    }

    private void scheduleTitleAnimations(BoardDisplay display) {
        float[] frames = animationCache.titleInScale();
        LeaderboardAnimations.Title animation = definition.animations().title();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = phaseFrameCount(frames.length, opacity.inEnabled(), animation.inFrames());
        if (usesClientInterpolation(animation.inEnabled(), animation.inCurve(),
                opacity.inEnabled(), opacity.inCurve(), frameCount)) {
            float startScale = frames[0];
            float targetScale = frames[frames.length - 1];
            scheduleLinearTransition(
                    display,
                    1L,
                    frameCount,
                    startScale,
                    targetScale,
                    opacity.inEnabled(),
                    true,
                    (scale, alpha) -> applyScale(display, scale, alpha),
                    () -> scheduleTitleFade(display)
            );
            return;
        }
        float[] phaseFrames = expandFrames(frames, frameCount);
        scheduleFrameAnimation(
                display,
                1L,
                phaseFrames,
                (scale, frame, totalFrames) -> {
                    display.interpolation(0, TITLE_INTERPOLATION);
                    applyScale(display, scale, opacityAtFrame(
                            frame, totalFrames, true, opacity.inEnabled(), opacity.inCurve()));
                },
                () -> scheduleTitleFade(display)
        );
    }

    private void scheduleTitleFade(BoardDisplay display) {
        long delay = Math.max(1L, definition.settings().pageDurationTicks());
        float[] frames = animationCache.titleOutScale();
        LeaderboardAnimations.Title animation = definition.animations().title();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = phaseFrameCount(frames.length, opacity.outEnabled(), animation.outFrames());
        if (usesClientInterpolation(animation.outEnabled(), animation.outCurve(),
                opacity.outEnabled(), opacity.outCurve(), frameCount)) {
            float startScale = frames[0];
            float targetScale = frames[frames.length - 1];
            scheduleLinearTransition(
                    display,
                    delay,
                    frameCount,
                    startScale,
                    targetScale,
                    opacity.outEnabled(),
                    false,
                    (scale, alpha) -> applyScale(display, scale, alpha),
                    () -> despawnDisplay(display)
            );
            return;
        }
        float[] phaseFrames = expandFrames(frames, frameCount);
        scheduleFrameAnimation(
                display,
                delay,
                phaseFrames,
                (scale, frame, totalFrames) -> {
                    display.interpolation(0, TITLE_INTERPOLATION);
                    applyScale(display, scale, opacityAtFrame(
                            frame, totalFrames, false, opacity.outEnabled(), opacity.outCurve()));
                },
                () -> despawnDisplay(display)
        );
    }

    private void applyScale(BoardDisplay display, float scale, byte opacity) {
        float scaled = scale * boardScale;
        display.transformAndOpacity(0.0F, 0.0F, 0.0F, scaled, scaled, scaled, opacity);
    }



    private void spawnBar(World world) {
        switch (barMode) {
            case NONE -> {
            }
            case DOTS -> spawnDots(world);
            case BAR -> spawnFillBar(world);
        }
    }

    private void spawnDots(World world) {
        Location anchor = origin.clone().add(0.0D, config.defaults().barOffsetY() * boardScale, 0.0D);
        spawnDisplay(anchor, baseOptions()
                .shadowed(false)
                .opacity(packOpacity(OPACITY_OPAQUE))
                .text(buildDotsComponent())
                .interpolationDuration(TITLE_INTERPOLATION)
                .teleportDuration(TITLE_INTERPOLATION)
                .scale(boardScale, boardScale, boardScale));
    }

    private Component buildDotsComponent() {
        PluginConfig.Bar bar = config.bar();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < totalPages; index++) {
            String color;
            if (index <= pageIndex) {
                color = barUseHolderColor ? definition.pages().get(index).color() : bar.activeColor();
            } else {
                color = bar.pendingColor();
            }
            builder.append(HolderColor.wrap(bar.symbol(), color));
            if (index < totalPages - 1) {
                builder.append(bar.separator());
            }
        }
        return plugin.deserializeMiniMessage(builder.toString());
    }

    private void spawnFillBar(World world) {
        Location anchor = origin.clone().add(0.0D, config.defaults().barOffsetY() * boardScale, 0.0D);
        String foregroundColor = barUseHolderColor ? page.color() : null;
        boolean clientInterpolated = usesClientInterpolatedBar();
        BarPair pair = spawnBarPair(world, anchor, foregroundColor, clientInterpolated);
        animateFillBar(pair);
    }

    private BarPair spawnBarPair(World world,
            Location containerLocation,
            String foregroundColor,
            boolean valueInitiallyOpaque) {
        Location valueLocation = computeValueLocation(containerLocation);
        String backgroundTemplate = definition.formatting().barBackground();
        String foregroundTemplate = definition.formatting().barForeground();

        BoardDisplay container = spawnDisplay(containerLocation, baseOptions()
                .shadowed(false)
                .text(deserializeBarComponent(backgroundTemplate, null))
                .opacity(packOpacity(OPACITY_TRANSPARENT))
                .interpolationDuration(BAR_INITIAL_INTERPOLATION)
                .teleportDuration(BAR_INITIAL_INTERPOLATION)
                .scale(boardScale, 2.0F * boardScale, boardScale));

        BoardDisplay value = spawnDisplay(valueLocation, baseOptions()
                .shadowed(false)
                .text(deserializeBarComponent(foregroundTemplate, foregroundColor))
                .opacity(packOpacity(valueInitiallyOpaque ? OPACITY_OPAQUE : OPACITY_TRANSPARENT))
                .interpolationDuration(BAR_INITIAL_INTERPOLATION)
                .teleportDuration(BAR_INITIAL_INTERPOLATION)
                .scale(0.0F, 2.0F * boardScale, boardScale));

        BarPair pair = new BarPair(container, value);
        applyBarFrame(pair, 0.0F, -1.0F);
        return pair;
    }

    private void animateFillBar(BarPair pair) {
        BoardDisplay container = pair.container();
        BoardDisplay value = pair.value();
        container.interpolation(0, BAR_INITIAL_INTERPOLATION);
        container.opacity(packOpacity(OPACITY_OPAQUE));

        long cycle = progressCycleTicks(
                definition.settings().pageDurationTicks(),
                animationCache.titleInScale().length);
        float[] scales = animationCache.barScales();
        float[] translations = animationCache.barTranslations();
        int frameCount = Math.max(Math.max(scales.length, translations.length), 1);
        if (usesClientInterpolatedBar()) {
            animateLinearFillBar(pair, cycle, scales, translations);
            return;
        }
        value.opacity(packOpacity(OPACITY_TRANSPARENT));

        TaskHandle fadeIn = scheduler.runDelayed(origin, task -> {
                if (!ensureSessionActive() || container.isRemoved() || value.isRemoved()) {
                    task.cancel();
                    return;
                }
                value.opacity(packOpacity(OPACITY_OPAQUE));
        }, 1L);
        tasks.add(fadeIn);

        int interpolationTicks = progressInterpolationTicks(cycle, frameCount);
        value.interpolation(0, interpolationTicks);

        TaskHandle progress = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            long elapsed = 0L;
            int lastFrame = -1;

            @Override
            public void accept(TaskHandle task) {
                if (!ensureSessionActive() || value.isRemoved()) {
                    task.cancel();
                    return;
                }
                double ratio = Math.min(1.0D, (double) (elapsed + 1L) / (double) cycle);
                int targetFrame = frameCount <= 1 ? 0
                        : Math.min(frameCount - 1, (int) Math.floor(ratio * (frameCount - 1)));
                if (targetFrame != lastFrame) {
                    float progressValue = scales.length == 0 ? (float) ratio
                            : scales[Math.min(targetFrame, scales.length - 1)];
                    float translationValue = translations.length == 0 ? (-1.0F + progressValue)
                            : translations[Math.min(targetFrame, translations.length - 1)];
                    value.interpolation(0, interpolationTicks);
                    applyBarFrame(pair, progressValue, translationValue);
                    lastFrame = targetFrame;
                }
                if (ratio >= 1.0D) {
                    task.cancel();
                    scheduleBarFade(pair, barFadeDelayTicks(
                            definition.settings().intervalTicks(), cycle, BAR_FADE_TICKS));
                    return;
                }
                elapsed++;
            }
        }, 1L, 1L);
        tasks.add(progress);
    }

    private void animateLinearFillBar(BarPair pair,
            long cycle,
            float[] scales,
            float[] translations) {
        BoardDisplay value = pair.value();
        float targetScale = scales.length == 0 ? 1.0F : scales[scales.length - 1];
        float targetTranslation = translations.length == 0 ? 0.0F : translations[translations.length - 1];

        TaskHandle target = scheduler.runDelayed(origin, task -> {
            if (!ensureSessionActive() || value.isRemoved()) {
                task.cancel();
                return;
            }
            value.interpolation(0, linearCycleInterpolationTicks(cycle));
            applyBarFrame(pair, targetScale, targetTranslation);

            TaskHandle resetInterpolation = scheduler.runDelayed(origin, resetTask -> {
                if (!ensureSessionActive() || value.isRemoved()) {
                    resetTask.cancel();
                    return;
                }
                value.interpolation(0, BAR_INITIAL_INTERPOLATION);
            }, linearCycleInterpolationTicks(cycle));
            tasks.add(resetInterpolation);
        }, 1L);
        tasks.add(target);

        long fadeDelay = barFadeDelayTicks(
                definition.settings().intervalTicks(),
                cycle,
                BAR_FADE_TICKS);
        scheduleBarFade(pair, cycle + fadeDelay);
    }

    private void scheduleBarFade(BarPair pair, long fadeDelayTicks) {
        BoardDisplay container = pair.container();
        BoardDisplay value = pair.value();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        if (!opacity.outEnabled()) {
            TaskHandle cleanup = scheduler.runDelayed(origin, cleanupTask -> {
                despawnDisplay(container);
                despawnDisplay(value);
            }, fadeDelayTicks + BAR_FADE_TICKS);
            tasks.add(cleanup);
            return;
        }

        if (usesClientInterpolation(
                false, EasingType.LINEAR, true, opacity.outCurve(), BAR_FADE_TICKS)) {
            scheduleLinearTransition(
                    container,
                    fadeDelayTicks,
                    BAR_FADE_TICKS,
                    0.0F,
                    0.0F,
                    true,
                    false,
                    (ignored, alpha) -> container.opacity(alpha),
                    () -> {});
            scheduleLinearTransition(
                    value,
                    fadeDelayTicks,
                    BAR_FADE_TICKS,
                    0.0F,
                    0.0F,
                    true,
                    false,
                    (ignored, alpha) -> value.opacity(alpha),
                    () -> {
                        despawnDisplay(container);
                        despawnDisplay(value);
                    });
            return;
        }

        scheduleFrameAnimation(
                container,
                fadeDelayTicks,
                new float[BAR_FADE_TICKS],
                (ignored, frame, totalFrames) -> {
                    byte alpha = opacityAtFrame(
                            frame, totalFrames, false, true, opacity.outCurve());
                    container.interpolation(0, BAR_INITIAL_INTERPOLATION);
                    container.opacity(alpha);
                    value.interpolation(0, BAR_INITIAL_INTERPOLATION);
                    value.opacity(alpha);
                },
                () -> {
                    despawnDisplay(container);
                    despawnDisplay(value);
                });
    }

    static long progressCycleTicks(long pageDurationTicks, int entranceFrames) {
        return Math.max(1L, pageDurationTicks) + Math.max(1, entranceFrames);
    }

    static int progressInterpolationTicks(long cycleTicks, int frameCount) {
        int transitions = Math.max(1, frameCount - 1);
        return Math.max(1, (int) Math.ceil((double) Math.max(1L, cycleTicks) / transitions));
    }

    static int linearInterpolationTicks(int frameCount) {
        return Math.max(1, frameCount - 1);
    }

    static long linearCompletionDelayTicks(int frameCount) {
        return Math.max(1L, frameCount);
    }

    static int linearCycleInterpolationTicks(long cycleTicks) {
        long transitions = Math.max(1L, cycleTicks - 1L);
        return (int) Math.min(Integer.MAX_VALUE, transitions);
    }

    static boolean usesClientInterpolation(boolean enabled, EasingType easing, int frameCount) {
        return enabled && easing == EasingType.LINEAR && frameCount > 1;
    }

    static boolean usesClientInterpolation(boolean motionEnabled,
            EasingType motionEasing,
            boolean opacityEnabled,
            EasingType opacityEasing,
            int frameCount) {
        if ((!motionEnabled && !opacityEnabled) || frameCount <= 1) {
            return false;
        }
        if (motionEnabled && motionEasing != EasingType.LINEAR) {
            return false;
        }
        if (motionEnabled && opacityEnabled) {
            return false;
        }
        if (!opacityEnabled) {
            return true;
        }
        return opacityEasing == EasingType.LINEAR && linearOpacitySplit(frameCount) != null;
    }

    static int phaseFrameCount(int motionFrameCount, boolean opacityEnabled, int configuredFrames) {
        int opacityFrames = opacityEnabled ? Math.max(1, configuredFrames) : 1;
        return Math.max(Math.max(1, motionFrameCount), opacityFrames);
    }

    static LinearOpacitySplit linearOpacitySplit(int frameCount) {
        int totalTicks = linearInterpolationTicks(frameCount);
        if (totalTicks < 3) {
            return null;
        }
        int firstTicks = Math.max(1, (totalTicks - 1) / 2);
        int secondTicks = totalTicks - firstTicks - 1;
        if (secondTicks < 1) {
            return null;
        }
        return new LinearOpacitySplit(totalTicks, firstTicks, secondTicks);
    }

    static double scaledRowOffset(double rowYOffset, double rowSpacing, int index, double boardScale) {
        return (rowYOffset - (index * rowSpacing)) * boardScale;
    }

    static long barFadeDelayTicks(long intervalTicks, long cycleTicks, int fadeTicks) {
        return Math.max(1L, intervalTicks - cycleTicks - Math.max(1, fadeTicks));
    }

    private Location computeValueLocation(Location containerLocation) {
        Vector direction = origin.getDirection();
        if (direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize().multiply(BAR_DEPTH_OFFSET * boardScale);
        Location valueLocation = containerLocation.clone().add(direction);
        if ((valueLocation.getBlockX() >> 4) != (containerLocation.getBlockX() >> 4)
                || (valueLocation.getBlockZ() >> 4) != (containerLocation.getBlockZ() >> 4)) {
            valueLocation = containerLocation.clone().subtract(direction);
        }
        return valueLocation;
    }

    private Component deserializeBarComponent(String rawTemplate, String preferredColor) {


        String template = boardPlaceholders.resolve(rawTemplate);
        String fallbackColor = extractColorFromTemplate(template);
        String colorToUse = HolderColor.normalize(preferredColor);
        if (colorToUse == null) {
            colorToUse = HolderColor.normalize(fallbackColor);
        }
        if (colorToUse == null) {
            colorToUse = "#cfcfcf";
        }
        String processed = template;
        if (processed.contains("%color%")) {
            processed = processed.replace("%color%", colorToUse);
        }
        if (HolderColor.isValid(preferredColor)) {
            processed = overrideColorTag(processed, colorToUse);
        }


        return plugin.deserializeMiniMessage(definition.formatting().padding()
                .apply(HolderColor.apply(processed, colorToUse), colorToUse));
    }

    private String extractColorFromTemplate(String template) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        Matcher hexMatcher = HEX_COLOR_TAG.matcher(template);
        if (hexMatcher.find()) {
            return "#" + hexMatcher.group(1);
        }
        Matcher colorMatcher = COLOR_TAG.matcher(template);
        if (colorMatcher.find()) {
            return colorMatcher.group(1);
        }
        return null;
    }

    private String overrideColorTag(String template, String color) {
        Matcher hexMatcher = HEX_COLOR_TAG.matcher(template);
        if (hexMatcher.find()) {
            return hexMatcher.replaceFirst(Matcher.quoteReplacement("<color:" + color + ">"));
        }
        Matcher colorMatcher = COLOR_TAG.matcher(template);
        if (colorMatcher.find()) {
            return colorMatcher.replaceFirst(Matcher.quoteReplacement("<color:" + color + ">"));
        }
        return HolderColor.wrap(template, color);
    }

    private void applyBarFrame(BarPair pair, float progressValue, float translationValue) {
        float clampedProgress = Math.max(0.0F, Math.min(1.0F, progressValue));
        float translationFactor = Float.isNaN(translationValue) ? (-1.0F + clampedProgress) : translationValue;
        float offset = Math.max(-1.0F, Math.min(1.0F, translationFactor));
        BoardDisplay value = pair.value();
        value.transform(offset * boardScale, 0.0F, 0.0F,
                clampedProgress * boardScale, 2.0F * boardScale, boardScale);
    }



    private void spawnRows(World world, List<RowEntry> entries) {
        long delayBetween = Math.max(1L, definition.settings().rowDelayTicks());
        for (int i = 0; i < entries.size(); i++) {
            RowEntry entry = entries.get(i);
            long spawnDelay = delayBetween * i;
            TaskHandle task = scheduler.runDelayed(origin, scheduledTask -> {
                    if (!leaderboard.isRenderable()) {
                        stop();
                        return;
                    }
                    if (!active) {
                        scheduledTask.cancel();
                        return;
                    }
                    spawnRow(world, entry);
            }, spawnDelay);
            tasks.add(task);
        }
    }

    private void spawnRow(World world, RowEntry entry) {
        Location base = origin.clone().add(0.0D, entry.offsetY(), 0.0D);
        float[] rowOffsets = animationCache.rowInOffsets();
        float startOffset = rowOffsets.length == 0 ? 0.0F : rowOffsets[0];
        LeaderboardAnimations.Opacity opacityAnimation = definition.animations().opacity();
        byte initialOpacity = opacityAnimation.inEnabled()
                ? packOpacity(OPACITY_TRANSPARENT)
                : packOpacity(OPACITY_OPAQUE);
        BoardDisplay row = spawnDisplay(base, baseOptions()
                .shadowed(definition.textShadow())
                .text(rowComponents.get(entry.index()))
                .opacity(initialOpacity)
                .interpolationDuration(ROW_INTERPOLATION)
                .teleportDuration(ROW_INTERPOLATION)
                .translation(startOffset * boardScale, 0.0F, 0.0F)
                .scale(boardScale, boardScale, boardScale));
        rowDisplays[entry.index()] = row;
        animateRow(row);
    }

    private String renderRow(String color, String circledNumber, String playerName, String value,
            PatternPlaceholders placeholders) {
        return definition.formatting().renderRow(color, circledNumber, playerName, value, page.icon(),
                placeholders);
    }


    private PatternPlaceholders rowPlaceholders(String playerName) {
        if (!rowHasPlaceholders) {
            return PatternPlaceholders.NONE;
        }



        boolean namesAPlayer = definition.type() != LeaderboardType.GROUP
                && !playerName.equals(config.missingText());
        return placeholderBridge.patterns(namesAPlayer ? placeholderBridge.holderContext(playerName) : null);
    }

    private void animateRow(BoardDisplay display) {
        float[] offsets = animationCache.rowInOffsets();
        LeaderboardAnimations.Row animation = definition.animations().row();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = phaseFrameCount(offsets.length, opacity.inEnabled(), animation.inFrames());
        if (usesClientInterpolation(animation.inEnabled(), animation.inCurve(),
                opacity.inEnabled(), opacity.inCurve(), frameCount)) {
            float startOffset = offsets[0];
            float targetOffset = offsets[offsets.length - 1];
            scheduleLinearTransition(
                    display,
                    1L,
                    frameCount,
                    startOffset,
                    targetOffset,
                    opacity.inEnabled(),
                    true,
                    (offset, alpha) -> applyRowTransform(display, offset, alpha),
                    () -> scheduleRowFade(display)
            );
            return;
        }
        float[] phaseOffsets = expandFrames(offsets, frameCount);
        scheduleFrameAnimation(
                display,
                1L,
                phaseOffsets,
                (offset, frame, totalFrames) -> {
                    display.interpolation(0, ROW_INTERPOLATION);
                    applyRowTransform(display, offset, opacityAtFrame(
                            frame, totalFrames, true, opacity.inEnabled(), opacity.inCurve()));
                },
                () -> scheduleRowFade(display)
        );
    }

    private void scheduleRowFade(BoardDisplay display) {
        long delay = Math.max(1L, definition.settings().pageDurationTicks());
        float[] offsets = animationCache.rowOutOffsets();
        LeaderboardAnimations.Row animation = definition.animations().row();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = phaseFrameCount(offsets.length, opacity.outEnabled(), animation.outFrames());
        if (usesClientInterpolation(animation.outEnabled(), animation.outCurve(),
                opacity.outEnabled(), opacity.outCurve(), frameCount)) {
            float startOffset = offsets[0];
            float targetOffset = offsets[offsets.length - 1];
            scheduleLinearTransition(
                    display,
                    delay,
                    frameCount,
                    startOffset,
                    targetOffset,
                    opacity.outEnabled(),
                    false,
                    (offset, alpha) -> applyRowTransform(display, offset, alpha),
                    () -> despawnDisplay(display)
            );
            return;
        }
        float[] phaseOffsets = expandFrames(offsets, frameCount);
        scheduleFrameAnimation(
                display,
                delay,
                phaseOffsets,
                (offset, frame, totalFrames) -> {
                    display.interpolation(0, ROW_INTERPOLATION);
                    applyRowTransform(display, offset, opacityAtFrame(
                            frame, totalFrames, false, opacity.outEnabled(), opacity.outCurve()));
                },
                () -> despawnDisplay(display)
        );
    }

    private void applyRowTransform(BoardDisplay display, float offset, byte opacity) {
        display.transformAndOpacity(offset * boardScale, 0.0F, 0.0F,
                boardScale, boardScale, boardScale, opacity);
    }

    private void scheduleFrameAnimation(BoardDisplay display,
            long initialDelayTicks,
            float[] frames,
            FrameApplier applier,
            Runnable onComplete) {
        long startTick = Bukkit.getCurrentTick() + Math.max(1L, initialDelayTicks);
        frameAnimations.add(new FrameAnimation(display, startTick, frames, applier, onComplete));
        ensureFrameAnimationTask();
    }

    private void ensureFrameAnimationTask() {
        if (frameAnimationTask != null && !frameAnimationTask.isCancelled()) {
            return;
        }
        frameAnimationTask = scheduler.runAtFixedRate(origin, this::tickFrameAnimations, 1L, 1L);
        tasks.add(frameAnimationTask);
    }

    private void tickFrameAnimations(TaskHandle task) {
        if (!ensureSessionActive()) {
            frameAnimations.clear();
            task.cancel();
            frameAnimationTask = null;
            return;
        }

        long currentTick = Bukkit.getCurrentTick();
        for (int index = frameAnimations.size() - 1; index >= 0; index--) {
            FrameAnimation animation = frameAnimations.get(index);
            if (animation.display.isRemoved()) {
                frameAnimations.remove(index);
                continue;
            }
            if (currentTick < animation.startTick) {
                continue;
            }
            if (animation.frame >= animation.frames.length) {
                frameAnimations.remove(index);
                animation.onComplete.run();
                continue;
            }

            animation.applier.apply(
                    animation.frames[animation.frame],
                    animation.frame,
                    animation.frames.length);
            animation.frame++;
        }

        if (frameAnimations.isEmpty()) {
            task.cancel();
            frameAnimationTask = null;
        }
    }

    private void scheduleLinearTransition(BoardDisplay display,
            long initialDelayTicks,
            int frameCount,
            float startValue,
            float targetValue,
            boolean opacityEnabled,
            boolean fadeIn,
            LinearFrameApplier applier,
            Runnable onComplete) {
        TaskHandle start = scheduler.runDelayed(origin, task -> {
            if (!ensureSessionActive() || display.isRemoved()) {
                task.cancel();
                return;
            }
            if (!opacityEnabled) {
                display.interpolation(0, linearInterpolationTicks(frameCount));
                applier.apply(targetValue, packOpacity(OPACITY_OPAQUE));

                TaskHandle completion = scheduler.runDelayed(origin, completionTask -> {
                    if (!ensureSessionActive() || display.isRemoved()) {
                        completionTask.cancel();
                        return;
                    }
                    display.interpolation(0, 1);
                    onComplete.run();
                }, linearCompletionDelayTicks(frameCount));
                tasks.add(completion);
                return;
            }

            LinearOpacitySplit split = Objects.requireNonNull(linearOpacitySplit(frameCount));
            float firstValue = interpolateLinearValue(
                    startValue, targetValue, split.firstTicks(), split.totalTicks());
            byte firstOpacity = packOpacity(fadeIn ? OPACITY_LOWER_HALF : OPACITY_UPPER_HALF);
            display.interpolation(0, split.firstTicks());
            applier.apply(firstValue, firstOpacity);

            TaskHandle bridge = scheduler.runDelayed(origin, bridgeTask -> {
                if (!ensureSessionActive() || display.isRemoved()) {
                    bridgeTask.cancel();
                    return;
                }
                float bridgeValue = interpolateLinearValue(
                        startValue, targetValue, split.firstTicks() + 1, split.totalTicks());
                byte bridgeOpacity = packOpacity(fadeIn ? OPACITY_UPPER_HALF : OPACITY_LOWER_HALF);
                display.interpolation(0, 0);
                applier.apply(bridgeValue, bridgeOpacity);

                TaskHandle finish = scheduler.runDelayed(origin, finishTask -> {
                    if (!ensureSessionActive() || display.isRemoved()) {
                        finishTask.cancel();
                        return;
                    }
                    display.interpolation(0, split.secondTicks());
                    applier.apply(targetValue, packOpacity(fadeIn ? OPACITY_OPAQUE : OPACITY_TRANSPARENT));

                    TaskHandle completion = scheduler.runDelayed(origin, completionTask -> {
                        if (!ensureSessionActive() || display.isRemoved()) {
                            completionTask.cancel();
                            return;
                        }
                        display.interpolation(0, 1);
                        onComplete.run();
                    }, split.secondTicks() + 1L);
                    tasks.add(completion);
                }, 1L);
                tasks.add(finish);
            }, split.firstTicks());
            tasks.add(bridge);
        }, Math.max(1L, initialDelayTicks));
        tasks.add(start);
    }

    static float interpolateLinearValue(float start, float target, int elapsedTicks, int totalTicks) {
        double progress = (double) elapsedTicks / (double) Math.max(1, totalTicks);
        return (float) (start + (target - start) * progress);
    }

    private boolean usesClientInterpolatedBar() {
        LeaderboardAnimations.Bar animation = definition.animations().bar();
        int frameCount = Math.max(
                animationCache.barScales().length,
                animationCache.barTranslations().length);
        return usesClientInterpolation(animation.enabled(), animation.curve(), frameCount);
    }

    private boolean ensureSessionActive() {
        if (!active) {
            return false;
        }
        if (leaderboard.isRenderable()) {
            return true;
        }
        stop();
        return false;
    }

    private void despawnDisplay(BoardDisplay display) {
        if (display == null) {
            return;
        }
        boolean removed = displays.remove(display);
        if (removed) {
            liveDisplays = Math.max(0, liveDisplays - 1);
        }
        leaderboard.untrack(display);
        display.remove();
        if (removed) {
            checkCompletion();
        }
    }

    private void checkCompletion() {
        if (!completed && liveDisplays <= 0) {
            completed = true;
            active = false;
            leaderboard.onSessionFinished(this);
        }
    }

    private static byte packOpacity(int alpha) {
        int clamped = Math.max(0, Math.min(OPACITY_OPAQUE, alpha));
        return (byte) clamped;
    }

    static byte opacityAtFrame(int frame,
            int totalFrames,
            boolean fadeIn,
            boolean enabled,
            EasingType easing) {
        if (!enabled) {
            return packOpacity(OPACITY_OPAQUE);
        }
        if (totalFrames <= 1) {
            return packOpacity(fadeIn ? OPACITY_OPAQUE : OPACITY_TRANSPARENT);
        }
        double progress = (double) frame / (double) (totalFrames - 1);
        double eased = Objects.requireNonNullElse(easing, EasingType.LINEAR).apply(progress, 0.0D);
        eased = Math.max(0.0D, Math.min(1.0D, eased));
        double alpha = fadeIn ? eased : (1.0D - eased);
        return packOpacity((int) Math.round(alpha * OPACITY_OPAQUE));
    }

    static float[] expandFrames(float[] frames, int totalFrames) {
        int count = Math.max(1, totalFrames);
        if (frames.length == count) {
            return frames;
        }
        float[] expanded = new float[count];
        if (frames.length <= 1) {
            float value = frames.length == 0 ? 0.0F : frames[0];
            java.util.Arrays.fill(expanded, value);
            return expanded;
        }
        for (int frame = 0; frame < count; frame++) {
            double progress = count <= 1 ? 1.0D : (double) frame / (double) (count - 1);
            double source = progress * (frames.length - 1);
            int lower = (int) Math.floor(source);
            int upper = Math.min(frames.length - 1, lower + 1);
            double fraction = source - lower;
            expanded[frame] = (float) (frames[lower] + (frames[upper] - frames[lower]) * fraction);
        }
        return expanded;
    }

    private record BarPair(BoardDisplay container, BoardDisplay value) {
    }

    private record RowEntry(double offsetY, int index) {
    }

    private record RowRender(List<String> normalized, List<Double> offsets, boolean pending) {
    }

    record LinearOpacitySplit(int totalTicks, int firstTicks, int secondTicks) {
    }

    @FunctionalInterface
    private interface FrameApplier {
        void apply(float value, int frame, int totalFrames);
    }

    @FunctionalInterface
    private interface LinearFrameApplier {
        void apply(float value, byte opacity);
    }

    private static final class FrameAnimation {
        private final BoardDisplay display;
        private final long startTick;
        private final float[] frames;
        private final FrameApplier applier;
        private final Runnable onComplete;
        private int frame;

        private FrameAnimation(BoardDisplay display,
                long startTick,
                float[] frames,
                FrameApplier applier,
                Runnable onComplete) {
            this.display = display;
            this.startTick = startTick;
            this.frames = frames;
            this.applier = applier;
            this.onComplete = onComplete;
        }
    }
}
