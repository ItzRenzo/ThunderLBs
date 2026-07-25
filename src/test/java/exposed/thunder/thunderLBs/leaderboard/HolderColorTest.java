package exposed.thunder.thunderLBs.leaderboard;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HolderColorTest {
    @Test
    void acceptsLegacySolidColors() {
        assertTrue(HolderColor.isValid("#38BDF8"));
        assertTrue(HolderColor.isValid("dark_gray"));
        assertFalse(HolderColor.isGradient("#38BDF8"));
    }

    @Test
    void acceptsTwoThroughTenHexGradientStops() {
        String tenStops = gradient(10);
        assertTrue(HolderColor.isValid("#112233:#445566"));
        assertTrue(HolderColor.isValid(tenStops));
        assertTrue(HolderColor.isGradient(tenStops));
        assertEquals(79, tenStops.length());
        assertEquals(HolderColor.MAX_INPUT_LENGTH, tenStops.length());
        assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(HolderColor.wrap("maximum", tenStops)));
    }

    @Test
    void rejectsMalformedAndOversizedGradients() {
        assertFalse(HolderColor.isValid("#112233:"));
        assertFalse(HolderColor.isValid("#123:#445566"));
        assertFalse(HolderColor.isValid("red:#445566"));
        assertFalse(HolderColor.isValid(gradient(11)));
    }

    @Test
    void rewritesOnlyMatchingColorTagsAndTheirClosures() {
        String rendered = "<color:#112233:#445566><bold>A</bold>"
                + "<color:red>B</color>C</color>";

        String result = HolderColor.apply(rendered, "#112233:#445566");

        assertEquals("<gradient:#112233:#445566><bold>A</bold>"
                + "<color:red>B</color>C</gradient>", result);
        assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(result));
    }

    @Test
    void wrapsContentWithTheAppropriateMiniMessageTag() {
        assertEquals("<color:gold>x</color>", HolderColor.wrap("x", "gold"));
        assertEquals("<gradient:#112233:#445566>x</gradient>",
                HolderColor.wrap("x", "#112233:#445566"));
    }

    private static String gradient(int colors) {
        return String.join(":", Collections.nCopies(colors, "#123456"));
    }
}
