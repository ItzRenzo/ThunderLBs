package exposed.thunder.thunderLBs.placeholder;

import java.util.function.UnaryOperator;

public final class PlaceholderPatterns {
    private PlaceholderPatterns() {
    }


    public static boolean mayContain(String pattern) {
        if (pattern == null) {
            return false;
        }
        int index = 0;
        while (index < pattern.length()) {
            int start = pattern.indexOf('%', index);
            if (start < 0) {
                return false;
            }
            int end = pattern.indexOf('%', start + 1);
            if (end < 0) {
                return false;
            }
            if (end == start + 1) {


                index = end;
                continue;
            }
            if (isResolvable(pattern, start + 1, end)) {
                return true;
            }
            index = end + 1;
        }
        return false;
    }


    public static String resolve(String pattern, UnaryOperator<String> lookup) {
        return walk(pattern, lookup);
    }


    public static String strip(String pattern) {
        return walk(pattern, placeholder -> "");
    }

    private static String walk(String pattern, UnaryOperator<String> lookup) {
        if (pattern == null) {
            return null;
        }
        StringBuilder out = null;
        int copied = 0;
        int index = 0;
        while (index < pattern.length()) {
            int start = pattern.indexOf('%', index);
            if (start < 0) {
                break;
            }
            int end = pattern.indexOf('%', start + 1);
            if (end < 0) {
                break;
            }
            if (end == start + 1) {
                index = end;
                continue;
            }
            if (!isResolvable(pattern, start + 1, end)) {
                index = end + 1;
                continue;
            }
            String placeholder = pattern.substring(start, end + 1);
            String replacement = lookup.apply(placeholder);
            index = end + 1;
            if (replacement == null || replacement.equals(placeholder)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(pattern.length() + 32);
            }
            out.append(pattern, copied, start).append(replacement);
            copied = end + 1;
        }
        return out == null ? pattern : out.append(pattern, copied, pattern.length()).toString();
    }


    private static boolean isResolvable(String pattern, int from, int to) {
        int underscore = pattern.indexOf('_', from);
        return underscore > from && underscore < to;
    }
}
