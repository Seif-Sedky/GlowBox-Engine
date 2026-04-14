package engine.catalog;

import engine.record.Field;
import engine.record.Record;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-table statistics maintained incrementally on every INSERT and DELETE.
 *
 * Strategy by type:
 *   INT     → equi-width Histogram (captures distribution for range queries)
 *   CHAR    → distinct value count via a HashSet<String>
 *   BOOLEAN → distinct value count (trivially at most 2)
 *
 * Histograms are registered per-column at table creation time via
 * registerIntColumn(). Columns not registered are simply not tracked.
 *
 * Lives as a field inside TableSchema — not a separate catalog class.
 */
public class TableStats {

    private int rowCount;

    /** INT columns — full histogram */
    private final Map<String, Histogram> histograms = new HashMap<>();

    /** CHAR and BOOLEAN columns — distinct value sets */
    private final Map<String, Set<String>> distinctSets = new HashMap<>();

    // -------------------------------------------------------------------------
    // Registration  (called once at table creation / bootstrap time)
    // -------------------------------------------------------------------------

    /**
     * Registers an INT column for histogram tracking.
     *
     * @param columnName  the column name (case-insensitive)
     * @param min         expected minimum value in demo data
     * @param max         expected maximum value in demo data
     * @param bucketCount number of histogram buckets (10 is fine)
     */
    public void registerIntColumn(String columnName, int min, int max, int bucketCount) {
        histograms.put(columnName.toLowerCase(), new Histogram(min, max, bucketCount));
    }

    /**
     * Registers a CHAR or BOOLEAN column for distinct value tracking.
     */
    public void registerCategoricalColumn(String columnName) {
        distinctSets.put(columnName.toLowerCase(), new HashSet<>());
    }

    // -------------------------------------------------------------------------
    // Incremental updates  (called by HeapFile)
    // -------------------------------------------------------------------------

    /**
     * Called after every successful INSERT.
     * Walks every field in the record and updates the matching tracker.
     */
    public void recordInsert(Record record) {
        rowCount++;
        for (Field field : record.getFields()) {
            String col = field.getColumn().getName().toLowerCase();
            switch (field.getType()) {
                case INT -> {
                    Histogram h = histograms.get(col);
                    if (h != null) h.increment(field.getInt());
                }
                case BOOLEAN -> {
                    Set<String> s = distinctSets.get(col);
                    if (s != null) s.add(String.valueOf(field.getBoolean()));
                }
                case CHAR -> {
                    Set<String> s = distinctSets.get(col);
                    if (s != null) s.add(field.getString().strip());
                }
            }
        }
    }

    /**
     * Called after every successful DELETE.
     * Decrements the histogram bucket for INT columns.
     * Distinct sets are left unchanged — a slight overestimate is harmless.
     */
    public void recordDelete(Record record) {
        if (rowCount > 0) rowCount--;
        for (Field field : record.getFields()) {
            if (field.getType() == ColumnDef.DataType.INT) {
                Histogram h = histograms.get(field.getColumn().getName().toLowerCase());
                if (h != null) h.decrement(field.getInt());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Optimizer queries
    // -------------------------------------------------------------------------

    public int rowCount() { return rowCount; }

    /**
     * Selectivity for an equality predicate on an INT column.
     * Falls back to 1/distinctValues if no histogram is registered.
     */
    public double pointSelectivity(String columnName, int value) {
        Histogram h = histograms.get(columnName.toLowerCase());
        if (h != null) return h.pointSelectivity(value);
        return 1.0 / Math.max(1, distinctCount(columnName));
    }

    /**
     * Selectivity for a range predicate on an INT column (low <= col <= high).
     */
    public double rangeSelectivity(String columnName, int low, int high) {
        Histogram h = histograms.get(columnName.toLowerCase());
        if (h != null) return h.rangeSelectivity(low, high);
        return 0.5; // safe fallback
    }

    /**
     * Selectivity for an equality predicate on a CHAR or BOOLEAN column.
     * Assumes uniform distribution across distinct values.
     */
    public double categoricalSelectivity(String columnName) {
        return 1.0 / Math.max(1, distinctCount(columnName));
    }

    /**
     * Number of distinct values seen for a categorical column.
     * Returns 1 as a safe fallback if column was never registered.
     */
    public int distinctCount(String columnName) {
        Set<String> s = distinctSets.get(columnName.toLowerCase());
        return (s == null || s.isEmpty()) ? 1 : s.size();
    }

    /** Returns the histogram for an INT column, or null if not registered. */
    public Histogram getHistogram(String columnName) {
        return histograms.get(columnName.toLowerCase());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TableStats{rows=").append(rowCount).append("\n");
        histograms.forEach((col, h) ->
            sb.append("  INT ").append(col).append(": ").append(h).append("\n"));
        distinctSets.forEach((col, s) ->
            sb.append("  CAT ").append(col).append(": ").append(s.size()).append(" distinct\n"));
        return sb.append("}").toString();
    }
}