package exposed.thunder.thunderLBs.font;

import java.util.ArrayList;
import java.util.List;

public final class RowNormalizer {

    private static final String FILLER = " ";

    private static final int MAX_REPEATS = 512;


    public static final RowNormalizer DISABLED = new RowNormalizer(null, PadSettings.ROW_WIDTH_OFF);

    private final TextMeasurer measurer;
    private final int rowWidth;

    private RowNormalizer(TextMeasurer measurer, int rowWidth) {
        this.measurer = measurer;
        this.rowWidth = rowWidth;
    }


    public static RowNormalizer create(TextMeasurer measurer, PadSettings settings) {
        if (measurer == null || settings == null || !settings.enabled()
                || settings.rowWidth() == PadSettings.ROW_WIDTH_OFF) {
            return DISABLED;
        }
        return new RowNormalizer(measurer, settings.rowWidth());
    }

    public boolean enabled() {
        return measurer != null;
    }


    public int rowWidth() {
        return rowWidth;
    }


    public List<String> normalize(List<String> rows) {
        if (measurer == null || rows == null || rows.isEmpty()) {
            return rows;
        }
        int unit = measurer.measurePlain(FILLER, false);
        if (unit <= 0) {
            return rows;
        }

        int[] widths = new int[rows.size()];


        int target = Math.max(rowWidth, 0);
        for (int i = 0; i < rows.size(); i++) {
            widths[i] = rows.get(i) == null ? 0 : measurer.measure(rows.get(i)).width();
            if (rowWidth == PadSettings.ROW_WIDTH_AUTO) {
                target = Math.max(target, widths[i]);
            }
        }

        List<String> normalized = new ArrayList<>(rows.size());
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            int count = row == null ? 0 : Math.min((target - widths[i]) / unit, MAX_REPEATS);
            if (count <= 0) {
                normalized.add(row);
                continue;
            }







            normalized.add(row + "<b:false><i:false>" + FILLER.repeat(count) + "</i></b>");
            changed = true;
        }
        return changed ? normalized : rows;
    }
}
