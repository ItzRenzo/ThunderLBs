package exposed.thunder.thunderLBs.placeholder;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderBridgeTest {
    @Test
    void explicitPlayerContextTakesPriority() {
        assertEquals("viewer", PlaceholderBridge.firstAvailable("viewer", List.of("online-player")));
    }

    @Test
    void firstOnlinePlayerSuppliesMissingContext() {
        assertEquals("online-player",
                PlaceholderBridge.firstAvailable(null, Arrays.asList(null, "online-player", "other")));
        assertNull(PlaceholderBridge.firstAvailable(null, List.of()));
    }

    @Test
    void detectsPlaceholderApiExpansionsThatEchoTheirRequestIdentifier() {
        assertTrue(PlaceholderBridge.isEchoedIdentifier(
                "%neptune_WINS_8_name%", "WINS_8_name"));
        assertTrue(PlaceholderBridge.isUnresolved(
                "%neptune_WINS_8_name%", "%neptune_WINS_8_name%"));
    }

    @Test
    void doesNotTreatNormalResolvedValuesOrCompositeTextAsEchoes() {
        assertFalse(PlaceholderBridge.isEchoedIdentifier(
                "%neptune_WINS_8_name%", "ocheryu"));
        assertFalse(PlaceholderBridge.isEchoedIdentifier(
                "Winner: %neptune_WINS_8_name%", "WINS_8_name"));
        assertFalse(PlaceholderBridge.isEchoedIdentifier("%server_name%", "Thunder"));
    }
}
