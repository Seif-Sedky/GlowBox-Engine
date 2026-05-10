package engine.optimizer;

import engine.catalog.TableSchema;
import engine.execution.BNLJoinOperator;

/**
 * Static cost estimation methods used by the query optimizer.
 *
 * All costs are expressed in the same unit — estimated number of record
 * comparisons or page reads — so they can be directly compared when
 * choosing between two strategies.
 *
 * These are estimates, not exact measurements. The goal is to make the
 * right choice most of the time, not to predict runtime to the millisecond.
 */
public class CostEstimator {

    private CostEstimator() {}

    // -------------------------------------------------------------------------
    // Select costs
    // -------------------------------------------------------------------------

    /**
     * Cost of a full sequential scan — proportional to total pages.
     * Every page in the heap file must be read regardless of selectivity.
     */
    public static double seqScanCost(TableSchema schema) {
        return schema.getPageCount();
    }

    /**
     * Estimated cost of an index-based equality lookup.
     *
     * Formula:
     *   1 index bucket page read
     *   + estimated matching records × (1 / recordsPerPage) heap page reads
     *
     * selectivity comes from the histogram: for INT columns this is
     * pointSelectivity(value), for CHAR/BOOLEAN it is categoricalSelectivity.
     *
     * @param schema      table schema (for page count and record size)
     * @param selectivity fraction of rows estimated to match (0.0 – 1.0)
     */
    public static double indexSelectCost(TableSchema schema, double selectivity) {
        double matchingRecords = selectivity * schema.getStats().rowCount();
        double recordsPerPage  = recordsPerPage(schema);
        return 1 + Math.ceil(matchingRecords / recordsPerPage);
    }

    /**
     * Selectivity estimate for a condition against a given table's stats.
     * Returns a value between 0.0 and 1.0.
     */
    public static double selectivity(TableSchema schema, Condition condition) {
        String col = condition.getColumnName();

        return switch (condition.getType()) {
            case EQUAL -> {
                // Try histogram first (INT), fall back to categorical
                if (schema.getStats().getHistogram(col) != null) {
                    yield schema.getStats().pointSelectivity(col, (Integer) condition.getValue());
                }
                yield schema.getStats().categoricalSelectivity(col);
            }
            case LESS_THAN -> {
                if (schema.getStats().getHistogram(col) != null) {
                    yield schema.getStats().rangeSelectivity(col,
                        Integer.MIN_VALUE, (Integer) condition.getValue() - 1);
                }
                yield 0.5; // safe default for non-INT ranges
            }
            case GREATER_THAN -> {
                if (schema.getStats().getHistogram(col) != null) {
                    yield schema.getStats().rangeSelectivity(col,
                        (Integer) condition.getValue() + 1, Integer.MAX_VALUE);
                }
                yield 0.5;
            }
            case BETWEEN -> {
                if (schema.getStats().getHistogram(col) != null) {
                    yield schema.getStats().rangeSelectivity(col,
                        (Integer) condition.getValue(), (Integer) condition.getValue2());
                }
                yield 0.3; // ranges cover roughly 30% by default
            }
        };
    }

    // -------------------------------------------------------------------------
    // Join costs
    // -------------------------------------------------------------------------

    /**
     * Estimated cost of a Block Nested Loop Join.
     *
     * The smaller relation is held in memory as the inner block (read once).
     * The larger relation is the outer, read in blocks of BLOCK_SIZE records.
     *
     * Cost = ceil(outerRows / BLOCK_SIZE) * innerRows
     *        (comparisons per outer block × number of outer blocks)
     *
     * The optimizer always assigns the smaller relation as inner, so this
     * formula uses min/max to determine which is which.
     */
    public static double bnlJoinCost(int leftRows, int rightRows) {
        int outerRows = Math.max(leftRows, rightRows);
        int innerRows = Math.min(leftRows, rightRows);
        return Math.ceil((double) outerRows / BNLJoinOperator.BLOCK_SIZE) * innerRows;
    }

    /**
     * Estimated cost of a Sort-Merge Join.
     *
     * If inputs are already sorted the cost is just the merge pass:
     *   leftRows + rightRows
     *
     * If sorting is required, add simulated external sort cost for each side:
     *   N × log2(N) — approximates the number of comparisons in an N-record sort
     *
     * Total (unsorted): leftRows×log2(left) + rightRows×log2(right) + left + right
     */
    public static double mergeJoinCost(int leftRows, int rightRows, boolean alreadySorted) {
        double mergeCost = leftRows + rightRows;
        if (alreadySorted) return mergeCost;

        double sortLeft  = leftRows  > 1 ? leftRows  * (Math.log(leftRows)  / Math.log(2)) : 0;
        double sortRight = rightRows > 1 ? rightRows * (Math.log(rightRows) / Math.log(2)) : 0;
        return sortLeft + sortRight + mergeCost;
    }

    // -------------------------------------------------------------------------
    // Distinct costs
    // -------------------------------------------------------------------------

    /**
     * Sort-based distinct cost: O(n log n) sort + O(n) dedup pass.
     */
    public static double sortDistinctCost(int rows) {
        return rows > 1 ? rows * (Math.log(rows) / Math.log(2)) + rows : rows;
    }

    /**
     * Hash-based distinct cost: O(n) single pass.
     */
    public static double hashDistinctCost(int rows) {
        return rows;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Estimated number of records that fit in one page. */
    private static double recordsPerPage(TableSchema schema) {
        int usableBytes = 250; // PAGE_SIZE(256) minus slotted page header overhead
        return Math.max(1, usableBytes / schema.getRecordSize());
    }
}