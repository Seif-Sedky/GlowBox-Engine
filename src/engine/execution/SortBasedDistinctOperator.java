package engine.execution;

import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Eliminates duplicate records by sorting first, then doing a single
 * linear pass to drop consecutive duplicates.
 *
 * Algorithm:
 *   1. Execute child operator to get all records.
 *   2. Sort by every column in declaration order — equal records end up adjacent.
 *   3. Walk the sorted list once; emit a record only when it differs from
 *      the previous one.
 *      one of the 2 algorithms discussed in the slides
 *
 * Cost: O(n log n) for the sort, O(n) for the dedup pass.
 *
 * When to prefer this over HashBasedDistinct:
 *   - Output needs to be in sorted order anyway (e.g. feeding into a MergeJoin).
 *   - Memory is tight — sort can be done externally; hashing needs the full
 *     set in memory at once. For this engine both fit in memory, but the
 *     distinction is tracked in ExecutionStats for visualisation.
 */
public class SortBasedDistinctOperator extends Operator {

    private final Operator       child;
    private final ExecutionStats stats;

    public SortBasedDistinctOperator(Operator child, ExecutionStats stats) {
        this.child = child;
        this.stats = stats;
    }

    public SortBasedDistinctOperator(Operator child) {
        this(child, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> input = child.execute();
        stats.addScanned(input.size());

        if (input.isEmpty()) return input;

        // Sort by all columns in declaration order
        input.sort(fullRecordComparator());

        // Single pass — emit when current != previous
        List<Record> output = new ArrayList<>();
        output.add(input.get(0));

        for (int i = 1; i < input.size(); i++) {
            stats.addComparisons(1);
            if (!UnionOperator.recordKey(input.get(i))
                     .equals(UnionOperator.recordKey(input.get(i - 1)))) {
                output.add(input.get(i));
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    /**
     * Comparator that orders records by every column in declaration order.
     * Columns are compared left to right; the first differing column
     * determines the ordering.
     */
    private static Comparator<Record> fullRecordComparator() {
        return (a, b) -> {
            for (int i = 0; i < a.getSchema().getColumnCount(); i++) {
                int cmp = a.getField(i).compareTo(b.getField(i));
                if (cmp != 0) return cmp;
            }
            return 0;
        };
    }

    @Override
    public String operatorName() { return "SortBasedDistinct"; }

    public ExecutionStats getStats() { return stats; }
}
