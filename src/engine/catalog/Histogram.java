package engine.catalog;

/**
 * Equi-width histogram for a single INT column.
 *
 * The value range [min, max] is divided into a fixed number of buckets
 * of equal width. Each bucket counts how many inserted values fell into
 * its range. Used by the query optimizer to estimate selectivity of
 * range predicates without a full table scan.
 *
 * Simplicity choices:
 *   - Bucket count is fixed at construction (default 10).
 *   - Range is fixed at construction. Values outside [min, max] land
 *     in the first or last bucket rather than being rejected — keeps
 *     things robust for demo data without needing dynamic resizing.
 *   - No persistence. Rebuilt from inserts at runtime.
 */

//ADDS ALOT OF RAM OVERHEAD, MIGHT REMOVE

public class Histogram {

    private final int   min;
    private final int   max;
    private final int[] buckets;
    private final int   bucketWidth;
    private       int   totalCount;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param min         inclusive lower bound of the tracked range
     * @param max         inclusive upper bound of the tracked range
     * @param bucketCount number of equal-width buckets (suggested: 10)
     */
    public Histogram(int min, int max, int bucketCount) {
        if (max <= min)       throw new IllegalArgumentException("max must be > min.");
        if (bucketCount <= 0) throw new IllegalArgumentException("bucketCount must be > 0.");

        this.min         = min;
        this.max         = max;
        this.buckets     = new int[bucketCount];
        this.bucketWidth = (int) Math.ceil((double)(max - min) / bucketCount);
        this.totalCount  = 0;
    }

    // -------------------------------------------------------------------------
    // Updates
    // -------------------------------------------------------------------------

    /** Records one occurrence of the given value. */
    public void increment(int value) {
        buckets[bucketIndex(value)]++;
        totalCount++;
    }

    /** Decrements count for a deleted value. */
    public void decrement(int value) {
        int idx = bucketIndex(value);
        if (buckets[idx] > 0) buckets[idx]--;
        if (totalCount  > 0) totalCount--;
    }

    // -------------------------------------------------------------------------
    // Selectivity estimation  (used by CostEstimator)
    // -------------------------------------------------------------------------

    /**
     * Estimates the fraction of rows satisfying value == target.
     * Assumes uniform distribution within the matching bucket.
     */
    public double pointSelectivity(int target) {
        if (totalCount == 0) return 0.0;
        int count = buckets[bucketIndex(target)];
        // Assume uniform spread within the bucket
        return ((double) count / bucketWidth) / totalCount;
    }

    /**
     * Estimates the fraction of rows satisfying low <= value <= high.
     * Sums contributions from every bucket that overlaps the range.
     */
    public double rangeSelectivity(int low, int high) {
        if (totalCount == 0) return 0.0;
        if (low > high)      return 0.0;

        double matchingRows = 0.0;

        for (int i = 0; i < buckets.length; i++) {
            int bucketLow  = min + i * bucketWidth;
            int bucketHigh = Math.min(bucketLow + bucketWidth - 1, max);

            // No overlap
            if (high < bucketLow || low > bucketHigh) continue;

            // Partial or full overlap — scale by the fraction of the bucket covered
            int overlapLow  = Math.max(low,  bucketLow);
            int overlapHigh = Math.min(high, bucketHigh);
            double overlapFraction = (double)(overlapHigh - overlapLow + 1)
                                   / (bucketHigh - bucketLow + 1);

            matchingRows += buckets[i] * overlapFraction;
        }

        return matchingRows / totalCount;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int   getTotalCount()  { return totalCount; }
    public int[] getBuckets()     { return buckets.clone(); }
    public int   getBucketCount() { return buckets.length; }
    public int   getMin()         { return min; }
    public int   getMax()         { return max; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private int bucketIndex(int value) {
        if (value <= min) return 0;
        if (value >= max) return buckets.length - 1;
        return Math.min((value - min) / bucketWidth, buckets.length - 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Histogram[").append(min).append("..").append(max).append("] ");
        for (int i = 0; i < buckets.length; i++) {
            int lo = min + i * bucketWidth;
            int hi = Math.min(lo + bucketWidth - 1, max);
            sb.append("[").append(lo).append("-").append(hi)
              .append(":").append(buckets[i]).append("]");
        }
        return sb.toString();
    }
}