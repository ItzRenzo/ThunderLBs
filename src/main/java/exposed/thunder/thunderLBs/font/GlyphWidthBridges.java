package exposed.thunder.thunderLBs.font;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class GlyphWidthBridges {

    private static final String[] SUPPORTED = {"Nexo", "Oraxen", "ItemsAdder"};

    private GlyphWidthBridges() {}


    public static List<String> detected(PluginManager plugins) {
        List<String> present = new ArrayList<>(SUPPORTED.length);
        for (String name : SUPPORTED) {
            Plugin plugin = plugins.getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                present.add(name);
            }
        }
        return present;
    }

    public static void installAvailable(PluginManager plugins, GlyphWidthRegistry registry, Logger logger) {
        List<String> present = detected(plugins);
        if (present.isEmpty()) {
            return;
        }
        logger.info(String.join(" and ", present) + " detected. ThunderLBs cannot read custom glyph widths "
                + "automatically yet, so <pad:...> assumes the fallback width for resource-pack characters. "
                + "Set formatting.padding.glyph-widths for any that measure wrongly.");
    }
}
