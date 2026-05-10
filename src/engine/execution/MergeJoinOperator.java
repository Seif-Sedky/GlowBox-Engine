package engine.execution;

import engine.catalog.ColumnDef;
import engine.catalog.TableSchema;
import engine.record.Field;
import engine.record.Record;

import java.io.IOException;
import java.util.*;

/**
 * Sort-Merge Join — joins two relations on an equality condition by first
 * sorting both on the join columns, then merging in a single linear pass.
 *
 * <h2>Algorithm</h2>
 *
 * <h3>Phase 1 — Sort (if not already sorted)</h3>
 * When {@code alreadySorted == false}, each relation is sorted using a simulated
 * external sort:
 * <ol>
 *   <li><b>Run creation:</b> the relation is divided into chunks of
 *       {@value BNLJoinOperator#BLOCK_SIZE} records.  Each chunk is sorted
 *       individually in memory, producing a <em>sorted run</em>.</li>
 *   <li><b>Run merging:</b> all sorted runs are merged simultaneously using a
 *       min-heap (priority queue), producing a single globally sorted list.</li>
 * </ol>
 * This faithfully simulates external sort-merge, where each chunk represents
 * one disk block loaded into the sort buffer.  All work is done in memory per
 * the in-memory simulation assumption documented on this class.
 *
 * <h3>Phase 2 — Merge join</h3>
 * With both lists sorted by their respective join columns, two cursors {@code i}
 * (left) and {@code j} (right) advance through the lists:
 * <ul>
 *   <li>If {@code left[i].key == right[j].key}: collect all right records
 *       matching the current left key (advancing {@code j}), emit a joined
 *       record for each pair.  Then advance {@code i}; if the new
 *       {@code left[i]} still has the same key, reset {@code j} to the
 *       start of the matching right group and repeat.</li>
 *   <li>If {@code left[i].key < right[j].key}: advance {@code i}.</li>
 *   <li>Otherwise: advance {@code j}.</li>
 * </ul>
 *
 * <h2>In-memory simulation note</h2>
 * External sort normally involves disk I/O between phases.  Here, both phases
 * operate entirely in memory.  Run boundaries and block reads are tracked in
 * {@link ExecutionStats} to reflect realistic cost for visualisation purposes.
 *
 * <h2>Output schema</h2>
 * Same as {@link BNLJoinOperator}: all left columns (prefixed with the left
 * table name) followed by all right columns (prefixed with the right table name).
 */
public class MergeJoinOperator extends Operator {

    private final Operator       left;
    private final Operator       right;
    private final String         leftColumn;
    private final String         rightColumn;
    private final boolean        alreadySorted;
    private final ExecutionStats stats;

    /**
     * @param left          left child operator
     * @param right         right child operator
     * @param leftColumn    column name from the left relation to join on
     * @param rightColumn   column name from the right relation to join on
     * @param alreadySorted if {@code true}, skip the sort phase (both inputs
     *                      are assumed pre-sorted on their respective join columns)
     */
    public MergeJoinOperator(Operator left, Operator right,
                             String leftColumn, String rightColumn,
                             boolean alreadySorted, ExecutionStats stats) {
        this.left          = left;
        this.right         = right;
        this.leftColumn    = leftColumn;
        this.rightColumn   = rightColumn;
        this.alreadySorted = alreadySorted;
        this.stats         = stats;
    }

    /** Convenience — inputs not pre-sorted, private stats. */
    public MergeJoinOperator(Operator left, Operator right,
                             String leftColumn, String rightColumn) {
        this(left, right, leftColumn, rightColumn, false, new ExecutionStats());
    }

    /** Convenience — caller specifies sort flag, private stats. */
    public MergeJoinOperator(Operator left, Operator right,
                             String leftColumn, String rightColumn, boolean alreadySorted) {
        this(left, right, leftColumn, rightColumn, alreadySorted, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> leftList  = left.execute();
        List<Record> rightList = right.execute();

        stats.addScanned(leftList.size() + rightList.size());

        // Phase 1: sort (skip if caller guarantees pre-sorted input)
        List<Record> sortedLeft  = alreadySorted ? leftList  : externalSort(leftList,  leftColumn);
        List<Record> sortedRight = alreadySorted ? rightList : externalSort(rightList, rightColumn);

        // Pre-build join schema once
        TableSchema joinSchema = BNLJoinOperator.buildJoinSchema(leftList, rightList);

        // Phase 2: merge join
        List<Record> output = mergeJoin(sortedLeft, sortedRight, joinSchema);

        stats.addOutput(output.size());
        return output;
    }

    // -------------------------------------------------------------------------
    // External sort simulation
    // -------------------------------------------------------------------------

    /**
     * Simulates external sort on {@code records} by the named column.
     *
     * <ol>
     *   <li>Split into runs of {@value BNLJoinOperator#BLOCK_SIZE} records,
     *       sort each run in memory (each split = one buffer page loaded).</li>
     *   <li>Merge all runs with a min-heap priority queue.</li>
     * </ol>
     */
    private List<Record> externalSort(List<Record> records, String sortColumn) {
        if (records.isEmpty()) return records;

        Comparator<Record> cmp = byColumn(sortColumn);
        int blockSize = BNLJoinOperator.BLOCK_SIZE;

        // Phase 1 — create sorted runs
        List<List<Record>> runs = new ArrayList<>();
        for (int i = 0; i < records.size(); i += blockSize) {
            int end = Math.min(i + blockSize, records.size());
            List<Record> run = new ArrayList<>(records.subList(i, end));
            run.sort(cmp);
            runs.add(run);
            stats.addBlocksRead(1);
        }

        // Phase 2 — merge all runs with a min-heap
        return mergeRuns(runs, sortColumn);
    }

    /**
     * Merges a list of individually sorted runs into one sorted list using a
     * min-heap.  Each heap entry tracks which run and position it came from so
     * the next record from that run can be loaded when the current one is consumed.
     *
     * <p>This simulates the "read first page of each run; when a page is exhausted,
     * load the next page of that run" step of external sort-merge.  Because we are
     * working in memory all pages are virtually available, but we track block
     * boundaries via {@code blockPos} counters to count block reads accurately.
     */
    private List<Record> mergeRuns(List<List<Record>> runs, String sortColumn) {
        Comparator<Record> cmp = byColumn(sortColumn);

        // heap entry: [runIndex, positionInRun]
        PriorityQueue<int[]> heap = new PriorityQueue<>(
            runs.size(),
            (a, b) -> cmp.compare(runs.get(a[0]).get(a[1]), runs.get(b[0]).get(b[1]))
        );

        // Seed the heap with the first record of each run
        // (simulates loading the first page of each run into a buffer slot)
        int[] blockPos = new int[runs.size()];   // which "block" we are on per run
        for (int r = 0; r < runs.size(); r++) {
            if (!runs.get(r).isEmpty()) {
                heap.offer(new int[]{r, 0});
                stats.addBlocksRead(1);          // first page of each run
            }
        }

        List<Record> merged = new ArrayList<>();
        int blockSize = BNLJoinOperator.BLOCK_SIZE;

        while (!heap.isEmpty()) {
            int[]  top  = heap.poll();
            int    ri   = top[0];
            int    pos  = top[1];
            merged.add(runs.get(ri).get(pos));
            stats.addComparisons(1);

            int nextPos = pos + 1;
            if (nextPos < runs.get(ri).size()) {
                heap.offer(new int[]{ri, nextPos});
                // Simulate a block read when we cross a page boundary in a run
                if (nextPos / blockSize > blockPos[ri]) {
                    blockPos[ri]++;
                    stats.addBlocksRead(1);
                }
            }
        }

        return merged;
    }

    // -------------------------------------------------------------------------
    // Merge join algorithm
    // -------------------------------------------------------------------------

    /**
     * Standard sort-merge join on two pre-sorted lists.
     *
     * <p>Cursors {@code i} and {@code j} advance through left and right.
     * When keys match, all right records sharing the same key are collected
     * ({@code j} scan), and a join record is emitted for every (left[i], right[j])
     * pair.  {@code i} then advances; if the new left[i] has the same key,
     * {@code j} is reset to the start of the right group ({@code jGroupStart}).
     */
    private List<Record> mergeJoin(List<Record> sortedLeft, List<Record> sortedRight,
                                   TableSchema joinSchema) {
        List<Record> output = new ArrayList<>();
        int i = 0, j = 0;

        while (i < sortedLeft.size() && j < sortedRight.size()) {
            Field lKey = sortedLeft.get(i).getField(leftColumn);
            Field rKey = sortedRight.get(j).getField(rightColumn);
            stats.addComparisons(1);

            int cmp = lKey.compareTo(rKey);

            if (cmp == 0) {
                // Keys match — find the extent of the matching right group
                int jGroupStart = j;
                while (j < sortedRight.size()
                       && sortedLeft.get(i).getField(leftColumn)
                                    .compareTo(sortedRight.get(j).getField(rightColumn)) == 0) {
                    output.add(BNLJoinOperator.mergeRecords(
                        sortedLeft.get(i), sortedRight.get(j), joinSchema));
                    j++;
                }
                int jGroupEnd = j;   // exclusive — j now points past the right group

                // Advance left; if next left record has same key, reset j to group start
                i++;
                while (i < sortedLeft.size()
                       && sortedLeft.get(i).getField(leftColumn)
                                    .compareTo(sortedLeft.get(i - 1).getField(leftColumn)) == 0) {
                    j = jGroupStart;
                    while (j < jGroupEnd) {
                        stats.addComparisons(1);
                        output.add(BNLJoinOperator.mergeRecords(
                            sortedLeft.get(i), sortedRight.get(j), joinSchema));
                        j++;
                    }
                    i++;
                }
                // j is already at jGroupEnd — continue from there
                j = jGroupEnd;

            } else if (cmp < 0) {
                i++;   // left key smaller — advance left
            } else {
                j++;   // right key smaller — advance right
            }
        }

        return output;
    }

    // -------------------------------------------------------------------------
    // Helpers shared with BNLJoinOperator (package-visible bridge)
    // -------------------------------------------------------------------------

    /** Creates a {@link Comparator} on a named column for any Record list. */
    static Comparator<Record> byColumn(String colName) {
        return (a, b) -> a.getField(colName).compareTo(b.getField(colName));
    }

    @Override
    public String operatorName() {
        return "MergeJoin(" + leftColumn + " = " + rightColumn
               + (alreadySorted ? ", pre-sorted" : ", sort first") + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
