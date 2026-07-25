package exposed.thunder.thunderLBs.font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PadTagScanner {

    public static final String NAME = "pad";

    private PadTagScanner() {}


    public record PadTag(int start, int end, String arguments) {}


    public static boolean mayContain(String input) {
        return input != null && input.indexOf('<') >= 0 && containsIgnoreCase(input, "<pad");
    }


    public static List<PadTag> find(String input) {
        if (!mayContain(input)) {
            return List.of();
        }
        List<PadTag> tags = new ArrayList<>(2);
        int length = input.length();
        int index = 0;
        while (index < length) {
            char current = input.charAt(index);
            if (current == '\\') {


                index += 2;
                continue;
            }
            if (current != '<') {
                index++;
                continue;
            }
            int close = findTagEnd(input, index + 1);
            if (close < 0) {



                index++;
                continue;
            }
            PadTag tag = match(input, index, close);
            if (tag != null) {
                tags.add(tag);
            }
            index = close + 1;
        }
        return tags.isEmpty() ? List.of() : tags;
    }


    public static Stripped strip(String input, List<PadTag> tags) {
        if (tags.isEmpty()) {
            return new Stripped(input, new int[0]);
        }
        StringBuilder stripped = new StringBuilder(input.length());
        int[] offsets = new int[tags.size()];
        int copied = 0;
        for (int i = 0; i < tags.size(); i++) {
            PadTag tag = tags.get(i);
            stripped.append(input, copied, tag.start());
            offsets[i] = stripped.length();
            copied = tag.end();
        }
        stripped.append(input, copied, input.length());
        return new Stripped(stripped.toString(), offsets);
    }


    public record Stripped(String text, int[] offsets) {}


    private static int findTagEnd(String input, int from) {
        char quote = 0;
        for (int index = from; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == '>') {
                return index;
            }
            if (current == '<') {

                return -1;
            }
        }
        return -1;
    }


    private static PadTag match(String input, int start, int close) {
        int nameStart = start + 1;
        int nameEnd = nameStart;
        while (nameEnd < close) {
            char current = input.charAt(nameEnd);
            if (current == ':' || current == '\'' || current == '"') {
                break;
            }
            nameEnd++;
        }
        String name = input.substring(nameStart, nameEnd);
        if (!NAME.equalsIgnoreCase(name)) {
            return null;
        }
        String arguments = nameEnd < close && input.charAt(nameEnd) == ':'
                ? input.substring(nameEnd + 1, close)
                : input.substring(nameEnd, close);
        return new PadTag(start, close + 1, arguments);
    }


    public static List<String> splitArguments(String arguments) {
        List<String> parts = new ArrayList<>(4);
        if (arguments.isEmpty()) {
            return parts;
        }
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (current == '\\' && index + 1 < arguments.length()) {
                part.append(arguments.charAt(++index));
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                } else {
                    part.append(current);
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == ':') {
                parts.add(part.toString());
                part.setLength(0);
                continue;
            }
            part.append(current);
        }
        if (quote != 0) {
            return null;
        }
        parts.add(part.toString());
        return parts;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        int limit = haystack.length() - needle.length();
        for (int index = 0; index <= limit; index++) {
            if (haystack.regionMatches(true, index, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }


    public static String escape(String text) {
        if (text.indexOf('\\') < 0 && text.indexOf('<') < 0) {
            return text;
        }
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\' || current == '<') {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return escaped.toString();
    }


    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
