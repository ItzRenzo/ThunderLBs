package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.ThunderLBs;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge;
import exposed.thunder.thunderLBs.placeholder.PlaceholderBridge.ProviderValue;
import exposed.thunder.thunderLBs.placeholder.ProviderQueries;
import exposed.thunder.thunderLBs.render.BoardDisplay;
import exposed.thunder.thunderLBs.render.DisplayOptions;
import exposed.thunder.thunderLBs.scheduler.RegionTaskScheduler;
import exposed.thunder.thunderLBs.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RelativeDisplayManager {
    private static final int GLIDE_TICKS = 10;
    private static final int ANIMATION_INTERPOLATION_TICKS = 1;
    private static final int OPACITY_TRANSPARENT = 0;
    private static final int OPACITY_LOWER_HALF = 127;
    private static final int OPACITY_UPPER_HALF = 128;
    private static final int OPACITY_OPAQUE = 255;

    private final ThunderLBs plugin;
    private final Leaderboard leaderboard;
    private final PlaceholderBridge placeholderBridge;
    private final LeaderboardDefinition definition;
    private final Location origin;
    private final World world;
    private final Location displayLocation;
    private final double rangeSquared;
    private final long refreshTicks;
    private final float boardScale;
    private final Map<UUID, BoardDisplay> playerDisplays = new HashMap<>();
    private final Map<UUID, String> lastRenderedContent = new HashMap<>();
    // Written from each player's region thread in renderContent, cleared from the origin thread.
    private final Map<UUID, CachedRender> renderCache = new ConcurrentHashMap<>();
    private final List<TaskHandle> animationTasks = new ArrayList<>();
    private final List<TaskHandle> retirementTasks = new ArrayList<>();
    private final Set<BoardDisplay> retiringDisplays = new HashSet<>();
    private final RegionTaskScheduler scheduler;
    private volatile LeaderboardPage activePage;
    private volatile ContentContext contentContext;
    private final boolean memoizableRelative;
    private TaskHandle refreshTask;
    private float animationOffset;
    private byte animationOpacity = packOpacity(OPACITY_TRANSPARENT);
    private LinearTransition linearTransition;
    private boolean warnedUnsupported;

    public RelativeDisplayManager(ThunderLBs plugin, Leaderboard leaderboard, PlaceholderBridge placeholderBridge) {
        this.plugin = plugin;
        this.leaderboard = leaderboard;
        this.placeholderBridge = placeholderBridge;
        this.definition = leaderboard.definition();
        this.origin = leaderboard.origin();
        this.world = leaderboard.world();
        this.boardScale = (float) definition.settings().boardScale();
        this.displayLocation = calculateLocation(definition);
        this.rangeSquared = leaderboard.rangeSquared();
        this.refreshTicks = plugin.getPluginConfig().performance().relativeRefreshTicks();
        this.memoizableRelative = !definition.formatting().relativeHasLivePatterns();
        this.scheduler = RegionTaskScheduler.create(plugin);
    }

    public void start() {
        stopTask();
        if (!definition.settings().showRelativePosition()) {
            return;
        }
        refreshTask = scheduler.runGlobalAtFixedRate(this::refreshPlayers, refreshTicks, refreshTicks);
        refresh();
    }

    public void stop() {
        stopTask();
        cancelAnimationTasks();
        cancelRetirementTasks();
        activePage = null;
        contentContext = null;
        cleanup();
    }

    private void stopTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void cancelAnimationTasks() {
        for (TaskHandle task : animationTasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        animationTasks.clear();
        linearTransition = null;
    }

    private void cancelRetirementTasks() {
        for (TaskHandle task : retirementTasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        retirementTasks.clear();
    }

    public void cleanup() {
        for (BoardDisplay display : playerDisplays.values()) {
            if (display != null) {
                leaderboard.untrack(display);
                display.remove();
            }
        }
        playerDisplays.clear();
        lastRenderedContent.clear();
        renderCache.clear();
        for (BoardDisplay display : retiringDisplays) {
            leaderboard.untrack(display);
            display.remove();
        }
        retiringDisplays.clear();
    }

    public void updatePage(LeaderboardPage page) {
        List<BoardDisplay> outgoingDisplays = detachPlayerDisplays();
        ContentContext nextContext = createContentContext(page);
        activatePage(page, nextContext, entranceDelay());
        retireDisplays(outgoingDisplays);
        refresh();
    }

    private List<BoardDisplay> detachPlayerDisplays() {
        List<BoardDisplay> displays = new ArrayList<>(playerDisplays.values());
        playerDisplays.clear();
        lastRenderedContent.clear();
        renderCache.clear();
        return displays;
    }

    private void activatePage(LeaderboardPage page, ContentContext context, long delay) {
        activePage = page;
        contentContext = context;
        startPageAnimation(delay);
    }

    private long entranceDelay() {
        long rowDelay = Math.max(1L, leaderboard.definition().settings().rowDelayTicks());
        return rowDelay * Math.max(1, leaderboard.definition().settings().positions());
    }

    private void startPageAnimation(long entranceDelay) {
        cancelAnimationTasks();
        float[] offsets = leaderboard.animationCache().rowInOffsets();
        float initialOffset = offsets.length == 0 ? 0.0F : offsets[0];
        LeaderboardAnimations.Row animation = definition.animations().row();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        applyAnimationState(initialOffset, packOpacity(
                opacity.inEnabled() ? OPACITY_TRANSPARENT : OPACITY_OPAQUE));
        int frameCount = PageSession.phaseFrameCount(offsets.length, opacity.inEnabled(), animation.inFrames());
        if (PageSession.usesClientInterpolation(animation.inEnabled(), animation.inCurve(),
                opacity.inEnabled(), opacity.inCurve(), frameCount)) {
            float targetOffset = offsets[offsets.length - 1];
            scheduleLinearTransition(
                    Math.max(1L, entranceDelay),
                    frameCount,
                    targetOffset,
                    opacity.inEnabled(),
                    true,
                    this::schedulePageExit
            );
            return;
        }

        float[] phaseOffsets = PageSession.expandFrames(offsets, frameCount);
        TaskHandle entrance = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            private int frame;

            @Override
            public void accept(TaskHandle task) {
                if (frame >= phaseOffsets.length) {
                    task.cancel();
                    float finalOffset = phaseOffsets[phaseOffsets.length - 1];
                    applyAnimationState(finalOffset, packOpacity(OPACITY_OPAQUE));
                    schedulePageExit();
                    return;
                }
                applyAnimationState(
                        phaseOffsets[frame],
                        PageSession.opacityAtFrame(
                                frame, phaseOffsets.length, true, opacity.inEnabled(), opacity.inCurve())
                );
                frame++;
            }
        }, Math.max(1L, entranceDelay), 1L);
        animationTasks.add(entrance);
    }

    private void schedulePageExit() {
        float[] offsets = leaderboard.animationCache().rowOutOffsets();
        long delay = Math.max(1L, leaderboard.definition().settings().pageDurationTicks());
        LeaderboardAnimations.Row animation = definition.animations().row();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = PageSession.phaseFrameCount(offsets.length, opacity.outEnabled(), animation.outFrames());
        if (PageSession.usesClientInterpolation(animation.outEnabled(), animation.outCurve(),
                opacity.outEnabled(), opacity.outCurve(), frameCount)) {
            float targetOffset = offsets[offsets.length - 1];
            scheduleLinearTransition(
                    delay,
                    frameCount,
                    targetOffset,
                    opacity.outEnabled(),
                    false,
                    () -> {}
            );
            return;
        }
        float[] phaseOffsets = PageSession.expandFrames(offsets, frameCount);
        TaskHandle exit = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            private int frame;

            @Override
            public void accept(TaskHandle task) {
                if (frame >= phaseOffsets.length) {
                    task.cancel();
                    float finalOffset = phaseOffsets[phaseOffsets.length - 1];
                    applyAnimationState(finalOffset, packOpacity(
                            opacity.outEnabled() ? OPACITY_TRANSPARENT : OPACITY_OPAQUE));
                    return;
                }
                applyAnimationState(
                        phaseOffsets[frame],
                        PageSession.opacityAtFrame(
                                frame, phaseOffsets.length, false, opacity.outEnabled(), opacity.outCurve())
                );
                frame++;
            }
        }, delay, 1L);
        animationTasks.add(exit);
    }

    private void retireDisplays(List<BoardDisplay> displays) {
        if (displays.isEmpty()) {
            return;
        }
        retirementTasks.removeIf(TaskHandle::isCancelled);
        retiringDisplays.addAll(displays);

        float[] offsets = leaderboard.animationCache().rowOutOffsets();
        LeaderboardAnimations.Row animation = definition.animations().row();
        LeaderboardAnimations.Opacity opacity = definition.animations().opacity();
        int frameCount = PageSession.phaseFrameCount(offsets.length, opacity.outEnabled(), animation.outFrames());
        if (PageSession.usesClientInterpolation(animation.outEnabled(), animation.outCurve(),
                opacity.outEnabled(), opacity.outCurve(), frameCount)) {
            float startOffset = offsets[0];
            float targetOffset = offsets[offsets.length - 1];
            scheduleRetiringLinearTransition(
                    displays, frameCount, startOffset, targetOffset, opacity.outEnabled());
            return;
        }

        float[] phaseOffsets = PageSession.expandFrames(offsets, frameCount);
        TaskHandle exit = scheduler.runAtFixedRate(origin, new java.util.function.Consumer<>() {
            private int frame;

            @Override
            public void accept(TaskHandle task) {
                if (frame >= phaseOffsets.length) {
                    task.cancel();
                    removeRetiringDisplays(displays);
                    return;
                }
                for (BoardDisplay display : displays) {
                    if (display == null || display.isRemoved()) {
                        continue;
                    }
                    display.interpolation(0, ANIMATION_INTERPOLATION_TICKS);
                    applyRetiringState(display, phaseOffsets[frame], PageSession.opacityAtFrame(
                            frame, phaseOffsets.length, false, opacity.outEnabled(), opacity.outCurve()));
                }
                frame++;
            }
        }, 1L, 1L);
        retirementTasks.add(exit);
    }

    private void scheduleRetiringLinearTransition(List<BoardDisplay> displays,
            int frameCount,
            float startOffset,
            float targetOffset,
            boolean opacityEnabled) {
        if (!opacityEnabled) {
            applyRetiringDisplays(
                    displays,
                    PageSession.linearInterpolationTicks(frameCount),
                    targetOffset,
                    packOpacity(OPACITY_OPAQUE));
            TaskHandle cleanup = scheduler.runDelayed(origin, task -> {
                task.cancel();
                removeRetiringDisplays(displays);
            }, PageSession.linearCompletionDelayTicks(frameCount));
            retirementTasks.add(cleanup);
            return;
        }

        PageSession.LinearOpacitySplit split = Objects.requireNonNull(
                PageSession.linearOpacitySplit(frameCount));
        float firstOffset = PageSession.interpolateLinearValue(
                startOffset, targetOffset, split.firstTicks(), split.totalTicks());
        applyRetiringDisplays(
                displays, split.firstTicks(), firstOffset, packOpacity(OPACITY_UPPER_HALF));

        TaskHandle bridge = scheduler.runDelayed(origin, bridgeTask -> {
            float bridgeOffset = PageSession.interpolateLinearValue(
                    startOffset, targetOffset, split.firstTicks() + 1, split.totalTicks());
            applyRetiringDisplays(displays, 0, bridgeOffset, packOpacity(OPACITY_LOWER_HALF));

            TaskHandle finish = scheduler.runDelayed(origin, finishTask -> {
                applyRetiringDisplays(
                        displays, split.secondTicks(), targetOffset, packOpacity(OPACITY_TRANSPARENT));
                TaskHandle cleanup = scheduler.runDelayed(origin, cleanupTask -> {
                    cleanupTask.cancel();
                    removeRetiringDisplays(displays);
                }, split.secondTicks() + 1L);
                retirementTasks.add(cleanup);
            }, 1L);
            retirementTasks.add(finish);
        }, split.firstTicks());
        retirementTasks.add(bridge);
    }

    private void applyRetiringDisplays(List<BoardDisplay> displays,
            int interpolationTicks,
            float offset,
            byte opacity) {
        for (BoardDisplay display : displays) {
            if (display == null || display.isRemoved()) {
                continue;
            }
            display.interpolation(0, interpolationTicks);
            applyRetiringState(display, offset, opacity);
        }
    }

    private void applyRetiringState(BoardDisplay display, float offset, byte opacity) {
        display.transformAndOpacity(offset * boardScale, 0.0F, 0.0F,
                boardScale, boardScale, boardScale, opacity);
    }

    private void removeRetiringDisplays(List<BoardDisplay> displays) {
        for (BoardDisplay display : displays) {
            if (display == null || !retiringDisplays.remove(display)) {
                continue;
            }
            leaderboard.untrack(display);
            display.remove();
        }
    }

    private void applyAnimationState(float offset, byte opacity) {
        linearTransition = null;
        this.animationOffset = offset;
        this.animationOpacity = opacity;
        for (BoardDisplay display : playerDisplays.values()) {
            if (display == null || display.isRemoved()) {
                continue;
            }
            display.interpolation(0, ANIMATION_INTERPOLATION_TICKS);
            display.transformAndOpacity(offset * boardScale, 0.0F, 0.0F,
                    boardScale, boardScale, boardScale, opacity);
        }
    }

    private void scheduleLinearTransition(long initialDelayTicks,
            int frameCount,
            float targetOffset,
            boolean opacityEnabled,
            boolean fadeIn,
            Runnable onComplete) {
        TaskHandle start = scheduler.runDelayed(origin, task -> {
            if (activePage == null) {
                task.cancel();
                return;
            }
            int duration = PageSession.linearInterpolationTicks(frameCount);
            float startOffset = animationOffset;
            byte startOpacity = animationOpacity;
            if (!opacityEnabled) {
                LinearTransition transition = beginLinearSegment(
                        duration, startOffset, targetOffset, startOpacity, packOpacity(OPACITY_OPAQUE));
                scheduleLinearCompletion(
                        transition,
                        PageSession.linearCompletionDelayTicks(frameCount),
                        targetOffset,
                        packOpacity(OPACITY_OPAQUE),
                        onComplete);
                return;
            }

            PageSession.LinearOpacitySplit split = Objects.requireNonNull(
                    PageSession.linearOpacitySplit(frameCount));
            float firstOffset = PageSession.interpolateLinearValue(
                    startOffset, targetOffset, split.firstTicks(), split.totalTicks());
            byte firstOpacity = packOpacity(fadeIn ? OPACITY_LOWER_HALF : OPACITY_UPPER_HALF);
            LinearTransition first = beginLinearSegment(
                    split.firstTicks(), startOffset, firstOffset, startOpacity, firstOpacity);

            TaskHandle bridge = scheduler.runDelayed(origin, bridgeTask -> {
                if (linearTransition != first) {
                    bridgeTask.cancel();
                    return;
                }
                linearTransition = null;
                float bridgeOffset = PageSession.interpolateLinearValue(
                        startOffset, targetOffset, split.firstTicks() + 1, split.totalTicks());
                byte bridgeOpacity = packOpacity(fadeIn ? OPACITY_UPPER_HALF : OPACITY_LOWER_HALF);
                animationOffset = bridgeOffset;
                animationOpacity = bridgeOpacity;
                for (BoardDisplay display : playerDisplays.values()) {
                    applyImmediateLinearState(display, bridgeOffset, bridgeOpacity);
                }

                TaskHandle finish = scheduler.runDelayed(origin, finishTask -> {
                    if (activePage == null || linearTransition != null) {
                        finishTask.cancel();
                        return;
                    }
                    byte targetOpacity = packOpacity(fadeIn ? OPACITY_OPAQUE : OPACITY_TRANSPARENT);
                    LinearTransition second = beginLinearSegment(
                            split.secondTicks(), bridgeOffset, targetOffset, bridgeOpacity, targetOpacity);
                    scheduleLinearCompletion(
                            second,
                            split.secondTicks() + 1L,
                            targetOffset,
                            targetOpacity,
                            onComplete);
                }, 1L);
                animationTasks.add(finish);
            }, split.firstTicks());
            animationTasks.add(bridge);
        }, Math.max(1L, initialDelayTicks));
        animationTasks.add(start);
    }

    private LinearTransition beginLinearSegment(int duration,
            float startOffset,
            float targetOffset,
            byte startOpacity,
            byte targetOpacity) {
        LinearTransition transition = new LinearTransition(
                Bukkit.getCurrentTick(),
                Math.max(1, duration),
                startOffset,
                targetOffset,
                startOpacity,
                targetOpacity);
        linearTransition = transition;
        animationOffset = startOffset;
        animationOpacity = startOpacity;
        for (BoardDisplay display : playerDisplays.values()) {
            applyLinearTarget(display, transition, duration);
        }
        return transition;
    }

    private void scheduleLinearCompletion(LinearTransition expected,
            long delay,
            float targetOffset,
            byte targetOpacity,
            Runnable onComplete) {
        TaskHandle completion = scheduler.runDelayed(origin, completionTask -> {
            if (linearTransition != expected) {
                completionTask.cancel();
                return;
            }
            linearTransition = null;
            animationOffset = targetOffset;
            animationOpacity = targetOpacity;
            for (BoardDisplay display : playerDisplays.values()) {
                if (display != null && !display.isRemoved()) {
                    display.interpolation(0, ANIMATION_INTERPOLATION_TICKS);
                }
            }
            onComplete.run();
        }, delay);
        animationTasks.add(completion);
    }

    private void applyImmediateLinearState(BoardDisplay display, float offset, byte opacity) {
        if (display == null || display.isRemoved()) {
            return;
        }
        display.interpolation(0, 0);
        display.transformAndOpacity(offset * boardScale, 0.0F, 0.0F,
                boardScale, boardScale, boardScale, opacity);
    }

    private void applyLinearTarget(BoardDisplay display, LinearTransition transition, int remainingTicks) {
        if (display == null || display.isRemoved()) {
            return;
        }
        display.interpolation(0, Math.max(1, remainingTicks));
        display.transformAndOpacity(
                transition.targetOffset() * boardScale,
                0.0F,
                0.0F,
                boardScale,
                boardScale,
                boardScale,
                transition.targetOpacity());
    }

    public void refresh() {
        LeaderboardDefinition def = definition;
        if (!def.settings().showRelativePosition()) {
            scheduler.execute(origin, this::cleanup);
            return;
        }
        if (activePage == null) {
            scheduler.execute(origin, this::cleanup);
            return;
        }
        if (contentContext == null
                || (!contentContext.nativeProvider() && contentContext.rankPlaceholder().isEmpty())) {
            if (!warnedUnsupported) {
                warnedUnsupported = true;
                plugin.getLogger().warning("Provider '" + plugin.getPluginConfig().providerName()
                        + "' has no viewer-rank source for leaderboard '" + def.id()
                        + "'; the relative row is disabled.");
            }
            scheduler.execute(origin, this::cleanup);
            return;
        }

        if (world == null) {
            scheduler.execute(origin, this::cleanup);
            return;
        }

        scheduler.runGlobal(this::refreshPlayers);
    }

    private void refreshPlayers() {
        // Iterates the board's tracked audience rather than everyone online: the backend sweep (or
        // refreshAudience for the entity backend) already keeps it world- and range-filtered, so the
        // per-cycle cost scales with the players near this board, not with the server's player count.
        Set<UUID> viewers = new HashSet<>();
        for (Leaderboard.Viewer viewer : leaderboard.viewers()) {
            Player player = viewer.player();
            if (!player.isOnline()) {
                continue;
            }
            viewers.add(player.getUniqueId());
            scheduler.executeDelayed(player, () -> refreshOnPlayer(player), null, 1L);
        }
        scheduler.execute(origin, () -> removeDisplaysExcept(viewers));
    }

    /** Drops the displays of players who left the audience: logged out, changed world, or walked away. */
    private void removeDisplaysExcept(Set<UUID> viewers) {
        Iterator<Map.Entry<UUID, BoardDisplay>> it = playerDisplays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BoardDisplay> entry = it.next();
            if (!viewers.contains(entry.getKey())) {
                BoardDisplay display = entry.getValue();
                if (display != null) {
                    leaderboard.untrack(display);
                    display.remove();
                }
                lastRenderedContent.remove(entry.getKey());
                renderCache.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private void refreshOnPlayer(Player player) {
        LeaderboardPage page = activePage;
        ContentContext context = contentContext;
        if (!definition.settings().showRelativePosition() || page == null || context == null
                || (!context.nativeProvider() && context.rankPlaceholder().isEmpty())) {
            scheduler.execute(origin, () -> removeDisplay(player.getUniqueId()));
            return;
        }

        if (world == null || !world.equals(player.getWorld())
                || player.getLocation().distanceSquared(origin) > rangeSquared) {
            scheduler.execute(origin, () -> removeDisplay(player.getUniqueId()));
            return;
        }

        String rendered = renderContent(player, page, context);
        scheduler.execute(origin, () -> {
            if (activePage == page && contentContext == context) {
                updatePlayerDisplay(player, rendered);
            }
        });
    }

    void removeViewer(UUID playerId) {
        removeDisplay(playerId);
    }

    private void updatePlayerDisplay(Player player, String rendered) {
        BoardDisplay display = playerDisplays.get(player.getUniqueId());

        if (display == null || display.isRemoved()) {
            display = spawnDisplay(player);
            playerDisplays.put(player.getUniqueId(), display);
        }

        updateContent(display, player.getUniqueId(), rendered);
    }

    private Location calculateLocation(LeaderboardDefinition def) {
        PluginConfig.Defaults defaults = plugin.getPluginConfig().defaults();
        int positions = Math.max(1, def.settings().positions());
        double offsetY = def.settings().rowYOffset()
                - ((positions + 1) * defaults.rowSpacing())
                + defaults.relativeOffset();
        return origin.clone().add(0, offsetY * boardScale, 0);
    }

    private BoardDisplay spawnDisplay(Player viewer) {
        AnimationSnapshot animation = currentAnimationSnapshot();
        BoardDisplay display = plugin.renderBackend().spawn(leaderboard, displayLocation, new DisplayOptions()
                .billboard(definition.billboard())
                .shadowed(definition.textShadow())
                .lineWidth(definition.lineWidth())
                .alignment(definition.alignment())
                .opacity(animation.opacity())
                .interpolationDuration(ANIMATION_INTERPOLATION_TICKS)
                .teleportDuration(GLIDE_TICKS)
                .translation(animation.offset() * boardScale, 0.0F, 0.0F)
                .scale(boardScale, boardScale, boardScale)
                .viewRange(Math.max(0.1F, definition.viewDistance() / 64.0F))
                .viewer(viewer));
        leaderboard.track(display);
        if (animation.transition() != null && animation.remainingTicks() > 0) {
            LinearTransition expected = animation.transition();
            TaskHandle continueAnimation = scheduler.runDelayed(origin, task -> {
                if (linearTransition != expected || display.isRemoved()) {
                    task.cancel();
                    return;
                }
                AnimationSnapshot current = currentAnimationSnapshot();
                if (current.transition() != expected) {
                    return;
                }
                if (current.remainingTicks() <= 0) {
                    display.interpolation(0, ANIMATION_INTERPOLATION_TICKS);
                    display.transformAndOpacity(
                            expected.targetOffset() * boardScale,
                            0.0F,
                            0.0F,
                            boardScale,
                            boardScale,
                            boardScale,
                            expected.targetOpacity());
                    return;
                }
                applyLinearTarget(display, expected, current.remainingTicks());
            }, 1L);
            animationTasks.add(continueAnimation);
        }
        return display;
    }

    private AnimationSnapshot currentAnimationSnapshot() {
        LinearTransition transition = linearTransition;
        if (transition == null) {
            return new AnimationSnapshot(animationOffset, animationOpacity, null, 0);
        }
        long elapsed = Math.max(0L, (long) Bukkit.getCurrentTick() - transition.startTick());
        int duration = Math.max(1, transition.durationTicks());
        float offset = interpolateLinear(transition.startOffset(), transition.targetOffset(), elapsed, duration);
        byte opacity = interpolateOpacity(transition.startOpacity(), transition.targetOpacity(), elapsed, duration);
        int remaining = remainingLinearTicks(elapsed, duration);
        return new AnimationSnapshot(offset, opacity, transition, remaining);
    }

    static float interpolateLinear(float start, float target, long elapsedTicks, int durationTicks) {
        double progress = linearProgress(elapsedTicks, durationTicks);
        return (float) (start + (target - start) * progress);
    }

    static byte interpolateOpacity(byte start, byte target, long elapsedTicks, int durationTicks) {
        double progress = linearProgress(elapsedTicks, durationTicks);
        int startAlpha = Byte.toUnsignedInt(start);
        int targetAlpha = Byte.toUnsignedInt(target);
        return packOpacity((int) Math.round(startAlpha + (targetAlpha - startAlpha) * progress));
    }

    static int remainingLinearTicks(long elapsedTicks, int durationTicks) {
        int duration = Math.max(1, durationTicks);
        long elapsed = Math.max(0L, Math.min((long) duration, elapsedTicks));
        return duration - (int) elapsed;
    }

    private static double linearProgress(long elapsedTicks, int durationTicks) {
        int duration = Math.max(1, durationTicks);
        long elapsed = Math.max(0L, Math.min((long) duration, elapsedTicks));
        return (double) elapsed / (double) duration;
    }

    private ContentContext createContentContext(LeaderboardPage page) {
        PluginConfig config = plugin.getPluginConfig();
        boolean nativeProvider = placeholderBridge.supportsProvider(config.providerName(), definition.type());
        ProviderQueries queries = ProviderQueries.create(config.providerName(), config.provider(),
                definition.type(), page.holderId(), page.interval(), nativeProvider);
        return new ContentContext(queries, page.color(), page.icon());
    }

    private String renderContent(Player player, LeaderboardPage page, ContentContext context) {
        PluginConfig config = plugin.getPluginConfig();
        String rankPlaceholder = context.rankPlaceholder();
        String valuePlaceholder = context.valuePlaceholder();
        String rankSource = context.queries().viewerRankSource();
        String valueSource = context.queries().viewerValueSource();

        String resolvedRank = placeholderBridge.resolveProvider(
                config.providerName(),
                definition.type(),
                page.holderId(),
                page.interval(),
                0,
                ProviderValue.VIEWER_RANK,
                player,
                rankPlaceholder);
        boolean rankMissing = isMissing(resolvedRank, rankSource);
        String rankStr = rankMissing ? config.missingPosition() : resolvedRank;
        String resolvedValue = placeholderBridge.resolveProvider(
                config.providerName(),
                definition.type(),
                page.holderId(),
                page.interval(),
                0,
                ProviderValue.VIEWER_VALUE,
                player,
                valuePlaceholder);
        String valueStr = sanitize(resolvedValue, valueSource, config);

        if (!valueStr.equals(config.missingText())) {
            valueStr = page.valueFormat().format(valueStr);
            if (page.hasPrefix()) {
                valueStr = page.prefix() + valueStr;
            }
            if (page.hasSuffix()) {
                valueStr = valueStr + page.suffix();
            }
        }

        String playerName;
        if (definition.type() == LeaderboardType.GROUP) {
            playerName = resolveGroupName(player, page, context, rankStr, rankMissing, config);
        } else {
            playerName = player.getName();
        }

        String positionStr = displayPosition(rankStr, rankMissing);

        String color = context.color();
        if (!rankMissing) {
            try {
                color = definition.formatting().rankColor(
                        Integer.parseInt(rankStr.trim()), config.formatting(), color);
            } catch (NumberFormatException ignored) {}
        }



        if (!memoizableRelative) {
            return definition.formatting().renderRelative(
                    color, positionStr, rankStr, playerName, valueStr, context.icon(),
                    placeholderBridge.patterns(player));
        }

        // With no live %..% patterns in the template, the output is a pure function of these inputs,
        // so an unchanged set of inputs reuses the previous render instead of running the template,
        // padding and color passes again on every refresh.
        RenderInputs inputs = new RenderInputs(page, context, color, positionStr, rankStr, playerName, valueStr);
        CachedRender cached = renderCache.get(player.getUniqueId());
        if (cached != null && cached.inputs().equals(inputs)) {
            return cached.rendered();
        }
        String rendered = definition.formatting().renderRelative(
                color, positionStr, rankStr, playerName, valueStr, context.icon());
        renderCache.put(player.getUniqueId(), new CachedRender(inputs, rendered));
        return rendered;
    }

    /** The inputs the relative template renders from; page and context compare by identity. */
    private record RenderInputs(LeaderboardPage page, ContentContext context, String color,
            String position, String rank, String player, String value) {
    }

    private record CachedRender(RenderInputs inputs, String rendered) {
    }

    private String resolveGroupName(Player player,
            LeaderboardPage page,
            ContentContext context,
            String rank,
            boolean rankMissing,
            PluginConfig config) {
        if (context.nativeProvider()) {
            if (rankMissing) {
                return config.missingText();
            }
            try {
                int position = Integer.parseInt(rank.trim());
                String source = context.queries().topNameSource(position);
                String resolved = placeholderBridge.resolveProvider(
                        config.providerName(),
                        definition.type(),
                        page.holderId(),
                        page.interval(),
                        position,
                        ProviderValue.TOP_NAME,
                        player,
                        "");
                if (!isMissing(resolved, source)) {
                    return resolved;
                }
            } catch (NumberFormatException ignored) {}
            return config.missingText();
        }

        String teamPlaceholder = context.teamPlaceholder();
        return teamPlaceholder.isEmpty() ? player.getName()
                : sanitize(placeholderBridge.resolve(teamPlaceholder, player), teamPlaceholder, config);
    }

    private void updateContent(BoardDisplay display, UUID playerId, String rendered) {
        String previous = lastRenderedContent.get(playerId);
        if (Objects.equals(previous, rendered)) {
            return;
        }

        display.text(plugin.deserializeMiniMessage(rendered));
        lastRenderedContent.put(playerId, rendered);
    }

    private String sanitize(String value, String placeholder, PluginConfig config) {
        return isMissing(value, placeholder) ? config.missingText() : value;
    }

    private static boolean isMissing(String value, String placeholder) {
        return value == null || value.isBlank() || value.equalsIgnoreCase(placeholder) || value.contains("%");
    }

    static String displayPosition(String rank, boolean missing) {
        if (missing) {
            return rank;
        }
        try {
            Integer.parseInt(rank.trim());
            return "#" + rank.trim();
        } catch (NumberFormatException e) {
            return rank;
        }
    }

    private static byte packOpacity(int alpha) {
        int clamped = Math.max(OPACITY_TRANSPARENT, Math.min(OPACITY_OPAQUE, alpha));
        return (byte) clamped;
    }

    private void removeDisplay(UUID uuid) {
        BoardDisplay display = playerDisplays.remove(uuid);
        lastRenderedContent.remove(uuid);
        renderCache.remove(uuid);
        if (display != null) {
            leaderboard.untrack(display);
            display.remove();
        }
    }

    private record ContentContext(ProviderQueries queries, String color, String icon) {

        boolean nativeProvider() {
            return queries.nativeProvider();
        }

        String rankPlaceholder() {
            return queries.viewerRankPlaceholder();
        }

        String valuePlaceholder() {
            return queries.viewerValuePlaceholder();
        }

        String teamPlaceholder() {
            return queries.teamPlaceholder();
        }
    }

    private record LinearTransition(long startTick,
                                    int durationTicks,
                                    float startOffset,
                                    float targetOffset,
                                    byte startOpacity,
                                    byte targetOpacity) {
    }

    private record AnimationSnapshot(float offset,
                                     byte opacity,
                                     LinearTransition transition,
                                     int remainingTicks) {
    }
}
