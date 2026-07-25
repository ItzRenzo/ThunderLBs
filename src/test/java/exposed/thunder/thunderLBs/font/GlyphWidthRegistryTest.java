package exposed.thunder.thunderLBs.font;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphWidthRegistryTest {
    @Test
    void resolvesInPriorityOrderWithConfigOverridesWinning() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();
        assertEquals(6, registry.advance('A', false));

        registry.applyConfig(new ConfigGlyphWidths(Map.of((int) 'A', 3)),
                FallbackGlyphWidths.DEFAULT_ADVANCE);

        assertEquals(3, registry.advance('A', false));
    }

    @Test
    void resourcePackProvidersOutrankVanillaButNotConfigOverrides() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();
        registry.register(fixed('A', 11, GlyphWidthProvider.PRIORITY_RESOURCE_PACK));

        assertEquals(11, registry.advance('A', false));

        registry.applyConfig(new ConfigGlyphWidths(Map.of((int) 'A', 3)),
                FallbackGlyphWidths.DEFAULT_ADVANCE);

        assertEquals(3, registry.advance('A', false));
    }

    @Test
    void unknownCodepointsFallBackToTheConfiguredAdvance() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();

        assertEquals(FallbackGlyphWidths.DEFAULT_ADVANCE, registry.advance(0xE001, false));

        registry.applyConfig(ConfigGlyphWidths.EMPTY, 12);

        assertEquals(12, registry.advance(0xE001, false));
    }

    @Test
    void eastAsianWideCodepointsGetADoubleWidthCell() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();

        assertEquals(16, registry.advance(0x4E00, false), "CJK unified ideograph");
        assertEquals(16, registry.advance(0xAC00, false), "Hangul syllable");
        assertEquals(16, registry.advance(0xFF21, false), "fullwidth latin A");
        assertEquals(16, registry.advance(0x20000, false), "CJK extension B");
        // Outside every wide block, so it takes the ordinary fallback.
        assertEquals(FallbackGlyphWidths.DEFAULT_ADVANCE, registry.advance(0x1000, false));
    }

    @Test
    void boldAddsOnePixelExceptToEmptyGlyphs() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();

        assertEquals(6, registry.advance('A', false));
        assertEquals(7, registry.advance('A', true));
        assertEquals(0, registry.advance(0x200C, false));
        assertEquals(0, registry.advance(0x200C, true), "a zero-width glyph stays zero-width when bold");
    }

    @Test
    void knownAdvanceReportsUnknownWhenOnlyTheFallbackCouldAnswer() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();

        assertEquals(6, registry.knownAdvance('A', false));
        assertEquals(GlyphWidthProvider.UNKNOWN, registry.knownAdvance(0xE001, false));
        assertEquals(GlyphWidthProvider.UNKNOWN, registry.knownAdvance(0x4E00, false),
                "the wide-block guess is still a guess");

        registry.applyConfig(new ConfigGlyphWidths(Map.of(0xE001, 12)),
                FallbackGlyphWidths.DEFAULT_ADVANCE);

        assertEquals(12, registry.knownAdvance(0xE001, false));
    }

    @Test
    void unregisterRestoresTheUnderlyingProvider() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();
        GlyphWidthProvider pack = fixed('A', 11, GlyphWidthProvider.PRIORITY_RESOURCE_PACK);

        registry.register(pack);
        assertEquals(11, registry.advance('A', false));

        registry.unregister(pack);
        assertEquals(6, registry.advance('A', false));
    }

    @Test
    void registeringTheSameProviderTwiceIsIdempotent() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();
        GlyphWidthProvider pack = fixed('A', 11, GlyphWidthProvider.PRIORITY_RESOURCE_PACK);

        registry.register(pack);
        registry.register(pack);
        registry.unregister(pack);

        assertEquals(6, registry.advance('A', false));
    }

    @Test
    void describeListsTheResolutionOrder() {
        GlyphWidthRegistry registry = GlyphWidthRegistry.createDefault();
        registry.applyConfig(new ConfigGlyphWidths(Map.of((int) 'A', 3)), 9);

        assertEquals(3, registry.describe().size(), registry.describe()::toString);
        assertEquals("config overrides", registry.describe().get(0));
        assertTrue(registry.describe().get(1).startsWith("vanilla "), registry.describe()::toString);
        assertEquals("fallback 9px", registry.describe().get(2));
    }

    @Test
    void fallbackAdvanceIsClampedToASaneRange() {
        assertEquals(FallbackGlyphWidths.MIN_ADVANCE, new FallbackGlyphWidths(-5).configuredAdvance());
        assertEquals(FallbackGlyphWidths.MAX_ADVANCE, new FallbackGlyphWidths(9999).configuredAdvance());
    }

    private static GlyphWidthProvider fixed(int codePoint, int advance, int priority) {
        return new GlyphWidthProvider() {
            @Override
            public int advance(int candidate) {
                return candidate == codePoint ? advance : UNKNOWN;
            }

            @Override
            public int priority() {
                return priority;
            }
        };
    }
}
