package exposed.thunder.thunderLBs.leaderboard;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HolderColor {
    public static final int MAX_COLORS = 10;
    public static final int MAX_INPUT_LENGTH = (MAX_COLORS * 7) + (MAX_COLORS - 1);

    private static final Pattern FULL_HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern TAG = Pattern.compile("<([^<>]+)>");

    private HolderColor() {}

    public static boolean isValid(String value) {
        return parse(value) != null;
    }

    public static boolean isGradient(String value) {
        Parsed parsed = parse(value);
        return parsed != null && parsed.gradient();
    }

    public static String normalize(String value) {
        Parsed parsed = parse(value);
        return parsed == null ? null : parsed.normalized();
    }


    public static String apply(String rendered, String value) {
        String input = rendered == null ? "" : rendered;
        Parsed parsed = parse(value);
        if (parsed == null || !parsed.gradient()) {
            return input;
        }

        String gradient = parsed.normalized();
        Matcher matcher = TAG.matcher(input);
        StringBuilder output = new StringBuilder(input.length() + 32);
        Deque<Boolean> colorTags = new ArrayDeque<>();
        int cursor = 0;
        while (matcher.find()) {
            output.append(input, cursor, matcher.start());
            String body = matcher.group(1).trim();
            String replacement = matcher.group();

            if (startsWithIgnoreCase(body, "color:")) {
                String argument = body.substring("color:".length()).trim();
                boolean holderGradient = argument.equalsIgnoreCase(gradient);
                colorTags.push(holderGradient);
                if (holderGradient) {
                    replacement = "<gradient:" + gradient + ">";
                }
            } else if (body.equalsIgnoreCase("/color")) {
                if (!colorTags.isEmpty() && colorTags.pop()) {
                    replacement = "</gradient>";
                }
            } else if (body.equalsIgnoreCase(gradient)) {
                replacement = "<gradient:" + gradient + ">";
            } else if (body.equalsIgnoreCase("/" + gradient)) {
                replacement = "</gradient>";
            }

            output.append(replacement);
            cursor = matcher.end();
        }
        return output.append(input, cursor, input.length()).toString();
    }


    public static String wrap(String content, String value) {
        String safeContent = content == null ? "" : content;
        Parsed parsed = parse(value);
        if (parsed == null) {
            return safeContent;
        }
        String tag = parsed.gradient() ? "gradient:" + parsed.normalized() : "color:" + parsed.normalized();
        String closingTag = parsed.gradient() ? "gradient" : "color";
        return "<" + tag + ">" + safeContent + "</" + closingTag + ">";
    }

    private static Parsed parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            if (TextColor.fromHexString(normalized) != null
                    || NamedTextColor.NAMES.value(normalized) != null) {
                return new Parsed(normalized, false);
            }
            return null;
        }

        String[] colors = normalized.split(":", -1);
        if (colors.length < 2 || colors.length > MAX_COLORS) {
            return null;
        }
        for (String color : colors) {
            if (!FULL_HEX.matcher(color).matches()) {
                return null;
            }
        }
        return new Parsed(normalized, true);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private record Parsed(String normalized, boolean gradient) {}
}
