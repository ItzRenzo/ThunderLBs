package exposed.thunder.thunderLBs.font;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class TextMeasurer {
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    private final MiniMessage miniMessage;
    private final GlyphWidthRegistry widths;

    public TextMeasurer(GlyphWidthRegistry widths) {
        this(MiniMessage.miniMessage(), widths);
    }

    public TextMeasurer(MiniMessage miniMessage, GlyphWidthRegistry widths) {
        this.miniMessage = miniMessage;
        this.widths = widths;
    }


    public record Measurement(int width, int lastLineWidth, int lineCount, boolean exact) {
        static final Measurement EMPTY = new Measurement(0, 0, 1, true);
    }


    public Measurement measure(String miniMessageText) {
        if (miniMessageText == null || miniMessageText.isEmpty()) {
            return Measurement.EMPTY;
        }
        Component parsed;
        try {
            parsed = miniMessage.deserialize(miniMessageText);
        } catch (RuntimeException ex) {


            Cursor cursor = new Cursor();
            cursor.append(miniMessageText, false, true);
            return cursor.finish(false);
        }
        return measure(parsed);
    }


    public Measurement measure(Component component) {
        Cursor cursor = new Cursor();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(component, false, true));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Component current = frame.component;
            boolean bold = resolve(current.style().decoration(TextDecoration.BOLD), frame.bold);
            boolean defaultFont = frame.defaultFont && isDefaultFont(current.style().font());

            if (current instanceof TextComponent text) {
                cursor.append(text.content(), bold, defaultFont);
            } else {


                cursor.exact = false;
            }

            List<Component> children = current.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Frame(children.get(i), bold, defaultFont));
            }
        }
        return cursor.finish(cursor.exact);
    }


    public int measurePlain(String text, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Cursor cursor = new Cursor();
        cursor.append(text, bold, true);
        return cursor.finish(cursor.exact).lastLineWidth();
    }


    public boolean isMeasurable(String text) {
        if (text == null) {
            return true;
        }
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint != '\n' && widths.knownAdvance(codePoint, false) == GlyphWidthProvider.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultFont(Key font) {
        return font == null || DEFAULT_FONT.equals(font);
    }

    private static boolean resolve(TextDecoration.State state, boolean inherited) {
        return switch (state) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> inherited;
        };
    }

    private record Frame(Component component, boolean bold, boolean defaultFont) {}

    private final class Cursor {
        private int width;
        private int lineWidth;
        private int lines = 1;
        private boolean exact = true;

        private void append(String content, boolean bold, boolean defaultFont) {
            if (content.isEmpty()) {
                return;
            }
            if (!defaultFont) {

                exact = false;
            }
            for (int offset = 0; offset < content.length(); ) {
                int codePoint = content.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    width = Math.max(width, lineWidth);
                    lineWidth = 0;
                    lines++;
                    continue;
                }
                int known = widths.knownAdvance(codePoint, bold);
                if (known == GlyphWidthProvider.UNKNOWN) {
                    exact = false;
                    lineWidth += widths.advance(codePoint, bold);
                } else {
                    lineWidth += known;
                }
            }
        }

        private Measurement finish(boolean measuredExactly) {
            return new Measurement(Math.max(width, lineWidth), lineWidth, lines, measuredExactly);
        }
    }
}
