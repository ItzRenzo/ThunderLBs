package exposed.thunder.thunderLBs.util;

import java.util.Locale;

public final class LegacyFormatting {
    public static final char SECTION = '§';
    public static final char AMPERSAND = '&';


    private static final String[] COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white"
    };


    private static final char[] DECORATION_CODES = {'l', 'o', 'n', 'm', 'k'};
    private static final String[] DECORATION_NAMES = {
            "bold", "italic", "underlined", "strikethrough", "obfuscated"
    };

    private LegacyFormatting() {
    }


    public static String toMiniMessage(String legacy) {
        if (!hasLegacyCodes(legacy)) {
            return legacy;
        }

        StringBuilder out = new StringBuilder(legacy.length() + 24);
        StringBuilder run = new StringBuilder(legacy.length());
        String color = null;
        boolean[] decorations = new boolean[DECORATION_CODES.length];

        int index = 0;
        while (index < legacy.length()) {
            char current = legacy.charAt(index);
            if (!isMarker(current) || index + 1 >= legacy.length()) {
                run.append(current);
                index++;
                continue;
            }

            String hex = readHexColor(legacy, index);
            if (hex != null) {
                flush(out, run, color, decorations);


                java.util.Arrays.fill(decorations, false);
                color = hex;
                index += legacy.charAt(index + 1) == '#' ? 8 : 14;
                continue;
            }

            char code = Character.toLowerCase(legacy.charAt(index + 1));
            String named = namedColor(code);
            if (named != null) {
                flush(out, run, color, decorations);
                java.util.Arrays.fill(decorations, false);
                color = named;
                index += 2;
                continue;
            }

            int decoration = decorationIndex(code);
            if (decoration >= 0) {
                if (!decorations[decoration]) {
                    flush(out, run, color, decorations);
                    decorations[decoration] = true;
                }
                index += 2;
                continue;
            }

            if (code == 'r') {
                flush(out, run, color, decorations);
                java.util.Arrays.fill(decorations, false);
                color = null;
                index += 2;
                continue;
            }


            run.append(current);
            index++;
        }
        flush(out, run, color, decorations);
        return out.toString();
    }


    public static boolean hasLegacyCodes(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (isMarker(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarker(char character) {
        return character == SECTION || character == AMPERSAND;
    }


    private static void flush(StringBuilder out, StringBuilder run, String color, boolean[] decorations) {
        if (run.length() == 0) {
            return;
        }


        if (color != null) {
            out.append("<color:").append(color).append('>');
        }
        for (int i = 0; i < decorations.length; i++) {
            if (decorations[i]) {
                out.append('<').append(DECORATION_NAMES[i]).append('>');
            }
        }
        out.append(run);
        for (int i = decorations.length - 1; i >= 0; i--) {
            if (decorations[i]) {
                out.append("</").append(DECORATION_NAMES[i]).append('>');
            }
        }
        if (color != null) {
            out.append("</color>");
        }
        run.setLength(0);
    }

    private static String namedColor(char code) {
        int value = Character.digit(code, 16);
        return value < 0 ? null : COLORS[value];
    }

    private static int decorationIndex(char code) {
        for (int i = 0; i < DECORATION_CODES.length; i++) {
            if (DECORATION_CODES[i] == code) {
                return i;
            }
        }
        return -1;
    }


    private static String readHexColor(String legacy, int start) {
        char next = legacy.charAt(start + 1);
        if (next == '#') {
            if (start + 8 > legacy.length() || !isHex(legacy, start + 2, 6)) {
                return null;
            }
            return legacy.substring(start + 1, start + 8).toLowerCase(Locale.ROOT);
        }
        if (Character.toLowerCase(next) != 'x' || start + 14 > legacy.length()) {
            return null;
        }
        StringBuilder hex = new StringBuilder(7).append('#');
        for (int pair = 0; pair < 6; pair++) {
            int offset = start + 2 + (pair * 2);
            if (!isMarker(legacy.charAt(offset)) || Character.digit(legacy.charAt(offset + 1), 16) < 0) {
                return null;
            }
            hex.append(Character.toLowerCase(legacy.charAt(offset + 1)));
        }
        return hex.toString();
    }

    private static boolean isHex(String text, int start, int length) {
        for (int i = start; i < start + length; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
