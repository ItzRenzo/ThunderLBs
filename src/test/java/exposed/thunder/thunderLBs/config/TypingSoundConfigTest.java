package exposed.thunder.thunderLBs.config;

import org.bukkit.SoundCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypingSoundConfigTest {
    @Test
    void categoryNamesAreReadCaseInsensitively() {
        List<String> warnings = new ArrayList<>();

        assertEquals(SoundCategory.BLOCKS,
                PluginConfig.Sounds.Typing.parseCategory("blocks", warnings::add));
        assertEquals(SoundCategory.BLOCKS,
                PluginConfig.Sounds.Typing.parseCategory("  BLOCKS  ", warnings::add));
        assertEquals(SoundCategory.PLAYERS,
                PluginConfig.Sounds.Typing.parseCategory("Players", warnings::add));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void anAbsentCategoryFallsBackToMaster() {
        List<String> warnings = new ArrayList<>();

        assertEquals(SoundCategory.MASTER, PluginConfig.Sounds.Typing.parseCategory(null, warnings::add));
        assertEquals(SoundCategory.MASTER, PluginConfig.Sounds.Typing.parseCategory("  ", warnings::add));
        assertTrue(warnings.isEmpty(), "an unset optional key is not a mistake worth logging");
    }

    @Test
    void anUnknownCategorySaysSoInsteadOfSilentlyPickingOne() {
        List<String> warnings = new ArrayList<>();

        assertEquals(SoundCategory.MASTER,
                PluginConfig.Sounds.Typing.parseCategory("loudest", warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("loudest"), warnings.get(0));
        assertTrue(warnings.get(0).contains("master"), warnings.get(0));
    }
}
