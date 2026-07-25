package exposed.thunder.thunderLBs.font;

import java.util.ArrayList;
import java.util.List;

public final class GlyphWidthRegistry {

    private static final int BOLD_ADVANCE = 1;

    private final List<GlyphWidthProvider> providers = new ArrayList<>();
    private volatile GlyphWidthProvider[] chain = new GlyphWidthProvider[0];
    private volatile ConfigGlyphWidths overrides = ConfigGlyphWidths.EMPTY;
    private volatile FallbackGlyphWidths fallback = new FallbackGlyphWidths(FallbackGlyphWidths.DEFAULT_ADVANCE);


    public static GlyphWidthRegistry createDefault() {
        GlyphWidthRegistry registry = new GlyphWidthRegistry();
        registry.register(new VanillaGlyphWidths());
        registry.rebuild();
        return registry;
    }


    public synchronized void register(GlyphWidthProvider provider) {
        if (provider != null && !providers.contains(provider)) {
            providers.add(provider);
            rebuild();
        }
    }

    public synchronized void unregister(GlyphWidthProvider provider) {
        if (providers.remove(provider)) {
            rebuild();
        }
    }


    public synchronized void applyConfig(ConfigGlyphWidths configOverrides, int fallbackAdvance) {
        this.overrides = configOverrides == null ? ConfigGlyphWidths.EMPTY : configOverrides;
        this.fallback = new FallbackGlyphWidths(fallbackAdvance);
        rebuild();
    }


    public int advance(int codePoint, boolean bold) {
        return withBold(resolve(codePoint, false), bold);
    }


    public int knownAdvance(int codePoint, boolean bold) {
        int resolved = resolve(codePoint, true);
        return resolved == GlyphWidthProvider.UNKNOWN ? GlyphWidthProvider.UNKNOWN : withBold(resolved, bold);
    }

    private int resolve(int codePoint, boolean exactOnly) {
        for (GlyphWidthProvider provider : chain) {
            if (exactOnly && provider.approximate()) {
                continue;
            }
            int advance = provider.advance(codePoint);
            if (advance != GlyphWidthProvider.UNKNOWN) {
                return advance;
            }
        }
        return GlyphWidthProvider.UNKNOWN;
    }

    private static int withBold(int advance, boolean bold) {
        return bold && advance > 0 ? advance + BOLD_ADVANCE : advance;
    }

    public List<String> describe() {
        List<String> names = new ArrayList<>();
        for (GlyphWidthProvider provider : chain) {
            names.add(provider.providerName());
        }
        return List.copyOf(names);
    }

    private void rebuild() {
        List<GlyphWidthProvider> ordered = new ArrayList<>(providers.size() + 2);
        if (!overrides.isEmpty()) {
            ordered.add(overrides);
        }
        ordered.addAll(providers);
        ordered.add(fallback);
        ordered.sort((left, right) -> Integer.compare(left.priority(), right.priority()));
        chain = ordered.toArray(GlyphWidthProvider[]::new);
    }
}
