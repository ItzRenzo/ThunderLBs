package exposed.thunder.thunderLBs.font;

public final class VanillaGlyphWidths implements GlyphWidthProvider {
    private final GlyphWidthTable table;

    public VanillaGlyphWidths() {
        this(GlyphWidthTable.vanilla());
    }

    public VanillaGlyphWidths(GlyphWidthTable table) {
        this.table = table;
    }

    @Override
    public int advance(int codePoint) {
        return table.advance(codePoint);
    }

    @Override
    public int priority() {
        return PRIORITY_VANILLA;
    }

    @Override
    public String providerName() {
        return "vanilla " + table.minecraftVersion();
    }
}
