package exposed.thunder.thunderLBs.leaderboard;

import exposed.thunder.thunderLBs.font.PadDiagnostic;
import exposed.thunder.thunderLBs.font.PadPass;
import exposed.thunder.thunderLBs.font.PadSettings;
import exposed.thunder.thunderLBs.font.PadTagScanner;
import exposed.thunder.thunderLBs.font.TextMeasurer;
import exposed.thunder.thunderLBs.config.PluginConfig;
import exposed.thunder.thunderLBs.placeholder.PlaceholderPatterns;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LeaderboardValidator {
    private static final int MAX_POSITIONS = 100;
    private static final long MAX_TIMING_TICKS = 20L * 60L * 60L;

    private static final String DUMMY_COLOR = "#ffffff";

    private static final String CHECK_PLAYER = "Player";
    private static final String CHECK_VALUE = "1";

    private static final String WIDE_PLAYER = "Wilhelmina_Smith";
    private static final String WIDE_VALUE = "999,999,999";

    private static final int LINE_WIDTH_HEADROOM = 8;
    private static final int LINE_WIDTH_STEP = 10;

    private final MiniMessage miniMessage;
    private final TextMeasurer measurer;

    public LeaderboardValidator() {
        this(null);
    }


    public LeaderboardValidator(TextMeasurer measurer) {
        this.miniMessage = MiniMessage.miniMessage();
        this.measurer = measurer;
    }

    public List<ValidationIssue> validate(LeaderboardDefinition definition) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!LeaderboardFiles.isValidId(definition.id())) {
            error(issues, "id", "must contain only a-z, 0-9, _ or - and be at most 64 characters");
        }
        if (definition.origin().getWorld() == null) {
            add(issues, definition.enabled() ? ValidationIssue.Severity.ERROR : ValidationIssue.Severity.WARNING,
                    "world", "world is not currently loaded");
        }
        if (definition.viewDistance() < 4 || definition.viewDistance() > 256) {
            error(issues, "view-distance", "must be between 4 and 256 blocks");
        }

        LeaderboardSettings settings = definition.settings();
        if (settings.positions() < 1 || settings.positions() > MAX_POSITIONS) {
            error(issues, "settings.positions", "must be between 1 and " + MAX_POSITIONS);
        }
        if (!Double.isFinite(settings.boardScale())
                || settings.boardScale() < 0.1D || settings.boardScale() > 5.0D) {
            error(issues, "settings.board-scale", "must be between 0.1 and 5.0");
        }
        if (!Double.isFinite(settings.rowYOffset())
                || settings.rowYOffset() < -5.0D || settings.rowYOffset() > 5.0D) {
            error(issues, "settings.row-y-offset", "must be between -5.0 and 5.0");
        }
        validatePositiveTiming(issues, "settings.page-duration-ticks", settings.pageDurationTicks());
        validatePositiveTiming(issues, "settings.interval-ticks", settings.intervalTicks());
        validateNonNegativeTiming(issues, "settings.row-delay-ticks", settings.rowDelayTicks());
        validateNonNegativeTiming(issues, "settings.typing-interval-ticks", settings.typingIntervalTicks());

        if (definition.pages().isEmpty()) {
            error(issues, "pages", "at least one page is required");
        }
        Set<String> holders = new HashSet<>();
        boolean pagePadded = false;
        for (int index = 0; index < definition.pages().size(); index++) {
            LeaderboardPage page = definition.pages().get(index);
            String path = "pages.page-" + (index + 1);
            if (page.holderId() == null || page.holderId().isBlank()) {
                error(issues, path + ".holder", "must not be empty");
            } else if (!holders.add(page.effectiveType(definition.type()) + "|"
                    + page.holderId().toLowerCase(Locale.ROOT) + "|" + page.interval())) {
                warning(issues, path + ".holder", "duplicates an earlier holder with the same type and interval");
            }
            if (page.title() == null || page.title().isBlank()) {
                error(issues, path + ".title", "must not be empty");
            }
            if (!HolderColor.isValid(page.color())) {
                error(issues, path + ".color",
                        "must be a named MiniMessage color, a hex color, or a gradient of 2-10 hex colors");
            }
            if (page.rowPattern() != null) {
                pagePadded |= validatePattern(issues, path + ".formatting.row", page.rowPattern(), definition);
            }
            if (page.relativePattern() != null) {
                pagePadded |= validatePattern(issues, path + ".formatting.relative", page.relativePattern(), definition);
            }
        }

        LeaderboardFormatting formatting = definition.formatting();
        boolean padded = validatePattern(issues, "formatting.title", formatting.titlePattern(), definition)
                | validatePattern(issues, "formatting.row", formatting.rowPattern(), definition)
                | validatePattern(issues, "formatting.relative", formatting.relativePattern(), definition)
                | validatePattern(issues, "formatting.bar.background", formatting.barBackground(), definition)
                | validatePattern(issues, "formatting.bar.foreground", formatting.barForeground(), definition);
        if (padded || pagePadded) {


            if (!formatting.padding().enabled()) {
                warning(issues, "formatting.padding",
                        "padding is disabled, so <pad:...> tags are removed without inserting any filler");
            }
            if (formatting.padding().settings().rowWidth() == PadSettings.ROW_WIDTH_OFF) {




                warning(issues, "formatting.padding.row-width",
                        "is off, so rows keep their natural widths and the <pad:...> column will still look"
                                + " ragged; set it to auto or a pixel width");
            }
        }
        formatting.rankColors().forEach((position, value) -> {
            if (!HolderColor.isValid(value)) {
                error(issues, "formatting.rank-colors." + position,
                        "must be a named MiniMessage color, a hex color, or a gradient of 2-10 hex colors");
            }
        });

        long lastRowStart = settings.rowDelayTicks() * Math.max(0L, settings.positions() - 1L);
        if (lastRowStart >= settings.pageDurationTicks()) {
            warning(issues, "settings.page-duration-ticks",
                    "the final row starts after the page begins fading; increase page duration or reduce row delay");
        }
        return List.copyOf(issues);
    }

    public boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
    }


    private boolean validatePattern(List<ValidationIssue> issues, String path, String pattern,
            LeaderboardDefinition definition) {
        String rendered = substitute(pattern, CHECK_PLAYER, CHECK_VALUE);
        if (PadTagScanner.find(rendered).isEmpty()) {
            validateMiniMessage(issues, path, rendered);
            return false;
        }

        PadPass padding = definition.formatting().padding();
        List<PadDiagnostic> diagnostics = new ArrayList<>();
        String padded = padding.apply(rendered, DUMMY_COLOR, diagnostics);
        for (PadDiagnostic diagnostic : diagnostics) {
            add(issues, diagnostic.severity() == PadDiagnostic.Severity.ERROR
                            ? ValidationIssue.Severity.ERROR : ValidationIssue.Severity.WARNING,
                    path, diagnostic.message());
        }


        validateMiniMessage(issues, path, padded);
        validateMeasurement(issues, path, padded, definition);
        return true;
    }


    public int suggestedLineWidth(LeaderboardDefinition definition) {
        if (definition == null) {
            return -1;
        }
        int suggested = suggestedLineWidth(measurer, definition.formatting());
        for (LeaderboardPage page : definition.pages()) {
            suggested = Math.max(suggested, suggestedLineWidth(measurer, page.formatting(definition.formatting())));
        }
        return suggested;
    }

    static int suggestedLineWidth(TextMeasurer measurer, LeaderboardFormatting formatting) {
        if (measurer == null || formatting == null) {
            return -1;
        }
        int widest = 0;
        for (String pattern : List.of(formatting.titlePattern(), formatting.rowPattern(),
                formatting.relativePattern(), formatting.barBackground(), formatting.barForeground())) {
            widest = Math.max(widest, worstCaseWidth(measurer, formatting, pattern));
        }
        int rowWidth = formatting.padding().settings().rowWidth();
        if (rowWidth > PadSettings.ROW_WIDTH_AUTO) {
            widest = Math.max(widest, rowWidth);
        }
        if (widest <= 0) {
            return -1;
        }
        int suggested = widest + LINE_WIDTH_HEADROOM;
        suggested = ((suggested + LINE_WIDTH_STEP - 1) / LINE_WIDTH_STEP) * LINE_WIDTH_STEP;
        return Math.max(PluginConfig.MIN_LINE_WIDTH, Math.min(PluginConfig.MAX_LINE_WIDTH, suggested));
    }

    private static int worstCaseWidth(TextMeasurer measurer, LeaderboardFormatting formatting, String pattern) {
        String rendered = substitute(pattern, WIDE_PLAYER, WIDE_VALUE);
        if (rendered.isEmpty()) {
            return 0;
        }
        if (!PadTagScanner.find(rendered).isEmpty()) {
            rendered = formatting.padding().apply(rendered, DUMMY_COLOR, new ArrayList<>());
        }
        try {
            return measurer.measure(rendered).width();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String substitute(String pattern, String player, String value) {
        String substituted = pattern == null ? "" : pattern
                .replace("%color%", DUMMY_COLOR)
                .replace("%icon%", "*")
                .replace("%title%", "Title")
                .replace("%position%", "1")
                .replace("%player%", player)
                .replace("%value%", value)
                .replace("%rank%", "1");
        return PlaceholderPatterns.strip(substituted);
    }

    private void validateMeasurement(List<ValidationIssue> issues, String path, String padded,
            LeaderboardDefinition definition) {
        if (measurer == null) {
            return;
        }
        TextMeasurer.Measurement measurement = measurer.measure(padded);
        if (measurement.width() > definition.lineWidth()) {
            int suggested = suggestedLineWidth(definition);
            String fix = suggested > definition.lineWidth()
                    ? " Raise it with /lb edit " + definition.id() + " linewidth " + suggested
                    : " Raise it with /lb edit " + definition.id() + " linewidth <pixels>";
            warning(issues, path, "reaches " + measurement.width() + " pixels, past this board's line width of "
                    + definition.lineWidth() + "; rows will wrap." + fix);
        }
        if (measurement.lineCount() > 1) {
            warning(issues, path, "a line break in a padded format is unsupported;"
                    + " columns after the break will not line up");
        }
        if (!measurement.exact()) {
            warning(issues, path, "contains characters of unknown width, such as a resource-pack glyph or a"
                    + " non-default font, so the column may be off. Add them to formatting.padding.glyph-widths");
        }
    }

    private void validateMiniMessage(List<ValidationIssue> issues, String path, String value) {
        try {
            miniMessage.deserialize(value == null ? "" : value);
        } catch (RuntimeException ex) {
            error(issues, path, "invalid MiniMessage: " + firstLine(ex.getMessage()));
        }
    }

    private void validatePositiveTiming(List<ValidationIssue> issues, String path, long value) {
        if (value < 1 || value > MAX_TIMING_TICKS) {
            error(issues, path, "must be between 1 and " + MAX_TIMING_TICKS + " ticks");
        }
    }

    private void validateNonNegativeTiming(List<ValidationIssue> issues, String path, long value) {
        if (value < 0 || value > MAX_TIMING_TICKS) {
            error(issues, path, "must be between 0 and " + MAX_TIMING_TICKS + " ticks");
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown parsing error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static void error(List<ValidationIssue> issues, String path, String message) {
        add(issues, ValidationIssue.Severity.ERROR, path, message);
    }

    private static void warning(List<ValidationIssue> issues, String path, String message) {
        add(issues, ValidationIssue.Severity.WARNING, path, message);
    }

    private static void add(List<ValidationIssue> issues, ValidationIssue.Severity severity, String path,
            String message) {
        issues.add(new ValidationIssue(severity, path, message));
    }
}
