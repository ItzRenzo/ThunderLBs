package exposed.thunder.thunderLBs.font;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ConfigGlyphWidths implements GlyphWidthProvider {
    public static final ConfigGlyphWidths EMPTY = new ConfigGlyphWidths(Map.of());

    private final Map<Integer, Integer> advances;

    public ConfigGlyphWidths(Map<Integer, Integer> advances) {
        this.advances = advances.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(advances));
    }

    @Override
    public int advance(int codePoint) {
        Integer override = advances.get(codePoint);
        return override == null ? UNKNOWN : override;
    }

    @Override
    public int priority() {
        return PRIORITY_CONFIG_OVERRIDE;
    }

    @Override
    public String providerName() {
        return "config overrides";
    }

    public boolean isEmpty() {
        return advances.isEmpty();
    }

    public int size() {
        return advances.size();
    }
}
