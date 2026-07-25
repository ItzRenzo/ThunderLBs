package exposed.thunder.thunderLBs.font;

public interface GlyphWidthProvider {

    int UNKNOWN = GlyphWidthTable.UNKNOWN;

    int PRIORITY_CONFIG_OVERRIDE = 0;
    int PRIORITY_RESOURCE_PACK = 50;
    int PRIORITY_VANILLA = 1000;
    int PRIORITY_FALLBACK = Integer.MAX_VALUE;


    int advance(int codePoint);


    int priority();


    default boolean approximate() {
        return false;
    }


    default String providerName() {
        return getClass().getSimpleName();
    }
}
