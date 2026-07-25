package exposed.thunder.thunderLBs.font;

import exposed.thunder.thunderLBs.leaderboard.HolderColor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PadPass {

    public static final int MAX_PADS = 8;

    public static final int MAX_REPEATS = 512;


    private static final String HOLDER_COLOR_TOKEN = "%color%";
    private static final int CACHE_LIMIT = 512;

    private static final String KEY_SEPARATOR = " ";


    public static final PadPass DISABLED = new PadPass(null, PadSettings.defaults());

    private final TextMeasurer measurer;
    private final PadSettings settings;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private PadPass(TextMeasurer measurer, PadSettings settings) {
        this.measurer = measurer;
        this.settings = settings == null ? PadSettings.defaults() : settings;
    }


    public static PadPass create(TextMeasurer measurer, PadSettings settings) {
        if (measurer == null || settings == null || !settings.enabled()) {
            return DISABLED;
        }
        return new PadPass(measurer, settings);
    }

    public boolean enabled() {
        return measurer != null;
    }

    public PadSettings settings() {
        return settings;
    }


    public String apply(String rendered, String holderColor) {
        if (rendered == null || !PadTagScanner.mayContain(rendered)) {
            return rendered;
        }
        String key = holderColor + KEY_SEPARATOR + rendered;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String result = pad(rendered, holderColor, null);
        if (!result.equals(rendered)) {
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(key, result);
        }
        return result;
    }


    public String apply(String rendered, String holderColor, List<PadDiagnostic> diagnostics) {
        if (rendered == null || !PadTagScanner.mayContain(rendered)) {
            return rendered;
        }
        String result = pad(rendered, holderColor, diagnostics);
        if (PadTagScanner.mayContain(result)) {
            caution(diagnostics, "'" + result.substring(result.toLowerCase(Locale.ROOT).indexOf("<pad"))
                    + "' is not a complete pad tag and will be shown as literal text");
        }
        return result;
    }


    public void clearCache() {
        cache.clear();
    }

    private String pad(String rendered, String holderColor, List<PadDiagnostic> diagnostics) {
        List<PadTagScanner.PadTag> tags = PadTagScanner.find(rendered);
        if (tags.isEmpty()) {
            return rendered;
        }
        PadTagScanner.Stripped stripped = PadTagScanner.strip(rendered, tags);
        if (measurer == null) {
            return stripped.text();
        }

        StringBuilder out = new StringBuilder(rendered.length() + 64);
        int copied = 0;
        int shift = 0;
        int lineCount = 1;
        int limit = Math.min(tags.size(), MAX_PADS);
        for (int i = 0; i < limit; i++) {
            PadTagScanner.PadTag tag = tags.get(i);
            out.append(rendered, copied, tag.start());
            copied = tag.end();

            Request request = parse(tag.arguments(), diagnostics);
            if (request == null) {
                continue;
            }

            TextMeasurer.Measurement prefix = measurer.measure(stripped.text().substring(0, stripped.offsets()[i]));
            if (prefix.lineCount() > lineCount) {
                shift = 0;
            }
            lineCount = prefix.lineCount();
            if (!prefix.exact()) {
                caution(diagnostics, "text before <pad:" + tag.arguments()
                        + "> contains characters of unknown width, so the column may be off");
            }

            int cursor = prefix.lastLineWidth() + shift;
            int target = request.relative() ? cursor + request.target() : request.target();
            int budget = target - cursor;
            if (request.mode() == PadMode.RIGHT) {
                budget -= segmentWidth(stripped, tags, i, prefix);
            }

            int unit = measurer.measurePlain(request.filler(), false);
            if (unit <= 0) {
                fault(diagnostics, "pad filler '" + request.filler() + "' is zero pixels wide");
                continue;
            }
            int count = Math.min(budget / unit, MAX_REPEATS);
            if (count <= 0) {
                continue;
            }

            out.append("<b:false><i:false>")
                    .append(HolderColor.wrap(PadTagScanner.escape(request.filler()).repeat(count),
                            resolveStyle(request.style(), holderColor)))
                    .append("</i></b>");
            shift += count * unit;
        }
        for (int i = limit; i < tags.size(); i++) {
            PadTagScanner.PadTag tag = tags.get(i);
            out.append(rendered, copied, tag.start());
            copied = tag.end();
            caution(diagnostics, "more than " + MAX_PADS + " pad tags in one format; <pad:"
                    + tag.arguments() + "> was ignored");
        }
        return out.append(rendered, copied, rendered.length()).toString();
    }


    private int segmentWidth(PadTagScanner.Stripped stripped, List<PadTagScanner.PadTag> tags, int i,
            TextMeasurer.Measurement prefix) {
        int end = i + 1 < tags.size() ? stripped.offsets()[i + 1] : stripped.text().length();
        TextMeasurer.Measurement segment = measurer.measure(stripped.text().substring(0, end));
        if (segment.lineCount() != prefix.lineCount()) {
            return 0;
        }
        return Math.max(0, segment.lastLineWidth() - prefix.lastLineWidth());
    }

    private String resolveStyle(String style, String holderColor) {
        return HOLDER_COLOR_TOKEN.equalsIgnoreCase(style) ? holderColor : style;
    }

    private record Request(int target, boolean relative, String filler, PadMode mode, String style) {}


    private Request parse(String arguments, List<PadDiagnostic> diagnostics) {
        List<String> parts = PadTagScanner.splitArguments(arguments);
        if (parts == null) {
            fault(diagnostics, "unclosed quote in <pad:" + arguments + ">");
            return null;
        }
        int target = settings.target();
        boolean relative = false;
        String filler = settings.filler();
        PadMode mode = settings.mode();
        String style = settings.style();

        if (!parts.isEmpty() && !parts.get(0).isBlank()) {
            String raw = parts.get(0).trim();
            relative = raw.startsWith("+");
            try {
                target = Integer.parseInt(relative ? raw.substring(1) : raw);
            } catch (NumberFormatException ex) {
                fault(diagnostics, "pad target '" + raw + "' is not a whole number of pixels");
                return null;
            }
            int minimum = relative ? 0 : PadSettings.MIN_TARGET;
            if (target < minimum || target > PadSettings.MAX_TARGET) {
                fault(diagnostics, "pad target " + raw + " is outside " + minimum + "-"
                        + PadSettings.MAX_TARGET + " pixels");
                return null;
            }
        }
        if (parts.size() > 1) {
            filler = parts.get(1);
            if (filler.isEmpty()) {
                fault(diagnostics, "pad filler is empty");
                return null;
            }
        }
        if (parts.size() > 2 && !parts.get(2).isBlank()) {
            PadMode parsed = PadMode.fromString(parts.get(2), null);
            if (parsed == null) {
                fault(diagnostics, "unknown pad mode '" + parts.get(2).trim() + "'; use fill or right");
                return null;
            }
            mode = parsed;
        }
        if (parts.size() > 3 && !parts.get(3).isBlank()) {
            style = parts.get(3).trim();
            if (!HOLDER_COLOR_TOKEN.equalsIgnoreCase(style) && !HolderColor.isValid(style)) {
                fault(diagnostics, "pad style '" + style + "' is not a colour, gradient or " + HOLDER_COLOR_TOKEN);
                return null;
            }
        }
        if (parts.size() > 4) {
            caution(diagnostics, "<pad:" + arguments + "> has more than four arguments; the extras were ignored");
        }
        return new Request(target, relative, filler, mode, style.toLowerCase(Locale.ROOT));
    }


    private static void fault(List<PadDiagnostic> diagnostics, String message) {
        if (diagnostics != null) {
            diagnostics.add(PadDiagnostic.error(message));
        }
    }


    private static void caution(List<PadDiagnostic> diagnostics, String message) {
        if (diagnostics != null) {
            diagnostics.add(PadDiagnostic.warning(message));
        }
    }
}
