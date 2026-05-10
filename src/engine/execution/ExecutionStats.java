package engine.execution;

/**
 * Accumulates per-operator execution statistics.
 *
 * Operators update these counters during {@code execute()} so the UI's stats
 * dashboard and Compare Mode can display them after each query runs.
 *
 * <p>An {@code ExecutionStats} object can be shared across an entire plan tree
 * (aggregate totals) or held privately per operator (isolated measurement) —
 * the choice is left to the caller.
 */
public class ExecutionStats {

    private int recordsScanned;  // records read from storage or a child operator
    private int recordsOutput;   // records produced (passed to parent / returned)
    private int comparisons;     // record-level comparisons (joins, set ops, sorts)
    private int blocksRead;      // simulated page/block reads for block algorithms

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    public void addScanned(int n)     { recordsScanned += n; }
    public void addOutput(int n)      { recordsOutput  += n; }
    public void addComparisons(int n) { comparisons    += n; }
    public void addBlocksRead(int n)  { blocksRead     += n; }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    public int getRecordsScanned() { return recordsScanned; }
    public int getRecordsOutput()  { return recordsOutput;  }
    public int getComparisons()    { return comparisons;    }
    public int getBlocksRead()     { return blocksRead;     }

    /** Resets all counters to zero — call before each query in Compare Mode. */
    public void reset() {
        recordsScanned = 0;
        recordsOutput  = 0;
        comparisons    = 0;
        blocksRead     = 0;
    }

    @Override
    public String toString() {
        return String.format(
            "ExecutionStats{scanned=%d, output=%d, comparisons=%d, blocksRead=%d}",
            recordsScanned, recordsOutput, comparisons, blocksRead);
    }
}
