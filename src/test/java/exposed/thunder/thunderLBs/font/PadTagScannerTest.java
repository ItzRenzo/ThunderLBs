package exposed.thunder.thunderLBs.font;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadTagScannerTest {
    @Test
    void findsABareTagAndOneWithArguments() {
        List<PadTagScanner.PadTag> bare = PadTagScanner.find("A<pad>B");

        assertEquals(1, bare.size());
        assertEquals(1, bare.get(0).start());
        assertEquals(6, bare.get(0).end());
        assertEquals("", bare.get(0).arguments());

        List<PadTagScanner.PadTag> full = PadTagScanner.find("A<pad:150:.>B");

        assertEquals(1, full.size());
        assertEquals("150:.", full.get(0).arguments());
    }

    @Test
    void findsEveryTagInOrder() {
        List<PadTagScanner.PadTag> tags = PadTagScanner.find("<pad:50>a<pad:100:->b<PAD:150>");

        assertEquals(3, tags.size());
        assertEquals("50", tags.get(0).arguments());
        assertEquals("100:-", tags.get(1).arguments());
        assertEquals("150", tags.get(2).arguments(), "the tag name is case-insensitive");
    }

    @Test
    void ignoresNamesThatMerelyStartWithPad() {
        assertTrue(PadTagScanner.find("<padding>").isEmpty());
        assertTrue(PadTagScanner.find("<pad_left:20>").isEmpty());
        assertTrue(PadTagScanner.find("<padx>").isEmpty());
        assertTrue(PadTagScanner.find("</pad>").isEmpty());
    }

    @Test
    void ignoresEscapedTags() {
        assertTrue(PadTagScanner.find("\\<pad:150>").isEmpty());
        assertTrue(PadTagScanner.find("A\\<pad>B").isEmpty());
        // A literal backslash before a real tag: the backslash escapes itself.
        assertEquals(1, PadTagScanner.find("\\\\<pad:150>").size());
    }

    @Test
    void ignoresTagsHiddenInsideAQuotedArgument() {
        assertTrue(PadTagScanner.find("<hover:show_text:'<pad:150>'>A</hover>").isEmpty());
        assertTrue(PadTagScanner.find("<hover:show_text:\"<pad:150>\">A</hover>").isEmpty());
    }

    @Test
    void quotedArgumentHidesTheClosingBracket() {
        List<PadTagScanner.PadTag> tags = PadTagScanner.find("<pad:150:'>'>A");

        assertEquals(1, tags.size());
        assertEquals("150:'>'", tags.get(0).arguments());
        assertEquals(List.of("150", ">"), PadTagScanner.splitArguments(tags.get(0).arguments()));
    }

    @Test
    void survivesUnclosedAndStrayBrackets() {
        assertTrue(PadTagScanner.find("<pad:150").isEmpty());
        assertTrue(PadTagScanner.find("<").isEmpty());
        assertTrue(PadTagScanner.find("<pad:150:'unclosed>A").isEmpty());
        // The first '<' is text because another one arrives before any '>'.
        assertEquals(1, PadTagScanner.find("<oops <pad:150>").size());
    }

    @Test
    void mayContainIsAConservativeFastPath() {
        assertFalse(PadTagScanner.mayContain(null));
        assertFalse(PadTagScanner.mayContain(""));
        assertFalse(PadTagScanner.mayContain("<red>%player%</red> %value%"));
        assertTrue(PadTagScanner.mayContain("<pad:150>"));
        assertTrue(PadTagScanner.mayContain("<PaD>"));
        assertTrue(PadTagScanner.mayContain("<padding>"), "cheap check, refined by find()");
    }

    @Test
    void stripRemovesEveryTagAndReportsWhereItSat() {
        String input = "<red>1.</red> Bob<pad:150:.><white>x50";
        List<PadTagScanner.PadTag> tags = PadTagScanner.find(input);
        PadTagScanner.Stripped stripped = PadTagScanner.strip(input, tags);

        assertEquals("<red>1.</red> Bob<white>x50", stripped.text());
        assertEquals(1, stripped.offsets().length);
        assertEquals("<red>1.</red> Bob", stripped.text().substring(0, stripped.offsets()[0]));
        assertFalse(stripped.text().contains("<pad"));
    }

    @Test
    void stripHandlesSeveralTagsAndReturnsTheInputWhenThereAreNone() {
        String input = "a<pad:50>b<pad:100>c";
        PadTagScanner.Stripped stripped = PadTagScanner.strip(input, PadTagScanner.find(input));

        assertEquals("abc", stripped.text());
        assertEquals(1, stripped.offsets()[0]);
        assertEquals(2, stripped.offsets()[1]);

        String plain = "<red>%player%</red>";
        assertSame(plain, PadTagScanner.strip(plain, PadTagScanner.find(plain)).text());
    }

    @Test
    void splitArgumentsUnwrapsQuotesAndHonoursEscapes() {
        assertEquals(List.of(), PadTagScanner.splitArguments(""));
        assertEquals(List.of("150", ".", "right"), PadTagScanner.splitArguments("150:.:right"));
        assertEquals(List.of("150", " ", "right"), PadTagScanner.splitArguments("150:' ':right"));
        assertEquals(List.of("150", "a:b"), PadTagScanner.splitArguments("150:'a:b'"));
        assertEquals(List.of("150", ""), PadTagScanner.splitArguments("150:"));
        assertEquals(List.of("150", "'"), PadTagScanner.splitArguments("150:\\'"));
        assertNull(PadTagScanner.splitArguments("150:'unclosed"), "an open quote is an error");
    }

    @Test
    void escapeMakesTextRenderLiterally() {
        assertEquals(".", PadTagScanner.escape("."));
        assertEquals("\\<", PadTagScanner.escape("<"));
        assertEquals("\\\\", PadTagScanner.escape("\\"));
        assertEquals("\\<pad>", PadTagScanner.escape("<pad>"));
        assertTrue(PadTagScanner.find(PadTagScanner.escape("<pad:150>")).isEmpty());
    }

    /**
     * The scanner has to agree with MiniMessage about which {@code <pad...>}
     * tokens exist, because a token we miss stays in the string and renders as
     * eleven-odd literal characters, and a token we invent is text we delete.
     * MiniMessage renders an unknown tag verbatim, so its plain-text output is
     * the oracle.
     */
    @Test
    void agreesWithMiniMessageAboutWhichTokensAreTags() {
        String[] cases = {
            "<pad:150>",
            "A<pad>B",
            "<red>1.</red> Bob<pad:150:.><white>x50",
            "<pad:50>a<pad:100:->b",
            "<padding>",
            "<pad_left:20>",
            "<hover:show_text:'<pad:150>'>A</hover>",
            "<pad:150:'>'>A",
            "<oops <pad:150>",
            "<pad:150",
            "<bold><pad:+20:.></bold>",
            "<gradient:red:blue><pad:200:.:right></gradient>",
            "1. <white>%player%</white><pad:150:'.'><color:#ff2929>%value%",
            "<PAD:150>",
            "<pad:150:.:right:dark_gray>",
            "a > b <pad:80>",
            "<pad>",
            "<click:run_command:'/lb page <pad:1>'>x</click>",
            "<pad:150><pad:150>",
            "trailing <",
        };
        MiniMessage miniMessage = MiniMessage.miniMessage();
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

        for (String input : cases) {
            String rendered = plain.serialize(miniMessage.deserialize(input));
            for (PadTagScanner.PadTag tag : PadTagScanner.find(input)) {
                String raw = input.substring(tag.start(), tag.end());
                assertTrue(rendered.contains(raw),
                        () -> "scanner claims " + raw + " in " + input
                                + " is a pad tag, but MiniMessage rendered " + rendered);
            }
        }
    }

    /** Once the tags are gone, nothing that looks like one may be left behind. */
    @Test
    void strippingIsIdempotent() {
        String input = "<red>1.</red> Bob<pad:150:.><white>x50<pad:200:'>':right>";
        String once = PadTagScanner.strip(input, PadTagScanner.find(input)).text();

        assertFalse(once.contains("<pad"));
        assertTrue(PadTagScanner.find(once).isEmpty());
        assertSame(once, PadTagScanner.strip(once, PadTagScanner.find(once)).text());
    }
}
