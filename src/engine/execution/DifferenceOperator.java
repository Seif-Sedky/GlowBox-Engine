package engine.execution;

import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the set DIFFERENCE {@code left − right} (records in left but not in right).
 *
 * <h2>Algorithm — hash-based, smaller relation in memory</h2>
 *
 * Two cases arise depending on which relation is smaller:
 *
 * <h3>Case 1 — right (subtracted set) is smaller or equal</h3>
 * <ol>
 *   <li>Build an in-memory set from {@code right}.</li>
 *   <li>Iterate {@code left}; emit every record whose key is NOT in the set.</li>
 * </ol>
 * This is the natural case: we load the "blocklist" into memory and filter the larger
 * left relation against it.
 *
 * <h3>Case 2 — left is strictly smaller than right</h3>
 * <ol>
 *   <li>Build an in-memory {@code HashMap<String, List<Record>>} from {@code left},
 *       grouping records by key to handle potential duplicates.</li>
 *   <li>Iterate {@code right}; for each record found in the map, remove one
 *       occurrence from the map (cancellation).</li>
 *   <li>Emit all records remaining in the map after the right-side scan.</li>
 * </ol>
 * A {@code List<Record>} per key is used instead of a simple set so that if
 * {@code left} contains duplicate rows, each duplicate can be independently
 * cancelled by a matching row in {@code right} (multiset semantics).
 *
 * <h2>Assumptions</h2>
 * <ul>
 *   <li>At least one relation fits entirely in memory.</li>
 *   <li>Row equality is full field-by-field comparison via
 *       {@link UnionOperator#recordKey}.</li>
 *   <li>Both child operators produce records with identical schemas.</li>
 * </ul>
 */
public class DifferenceOperator extends Operator {

    private final Operator       left;
    private final Operator       right;
    private final ExecutionStats stats;

    public DifferenceOperator(Operator left, Operator right, ExecutionStats stats) {
        this.left  = left;
        this.right = right;
        this.stats = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public DifferenceOperator(Operator left, Operator right) {
        this(left, right, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> leftList  = left.execute();
        List<Record> rightList = right.execute();

        stats.addScanned(leftList.size() + rightList.size());

        return (rightList.size() <= leftList.size())
            ? rightIsSmaller(leftList, rightList)
            : leftIsSmaller(leftList, rightList);
    }

    // -------------------------------------------------------------------------
    // Case 1: right is smaller → load right into memory, filter left
    // -------------------------------------------------------------------------

    /**
     * Right (the subtracted set) fits in memory.
     * Build a set from right, stream left and emit records not in the set.
     */
    private List<Record> rightIsSmaller(List<Record> leftList, List<Record> rightList) {
        // Build in-memory set from right
        Map<String, Boolean> rightSet = new HashMap<>();
        for (Record r : rightList) {
            rightSet.put(UnionOperator.recordKey(r), true);
        }

        List<Record> output = new ArrayList<>();
        for (Record r : leftList) {
            stats.addComparisons(1);
            if (!rightSet.containsKey(UnionOperator.recordKey(r))) {
                output.add(r);
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    // -------------------------------------------------------------------------
    // Case 2: left is smaller → load left into memory, cancel against right
    // -------------------------------------------------------------------------

    /**
     * Left (the minuend) fits in memory.
     * Build a map from left, cancel entries found in right, emit survivors.
     *
     * <p>Uses {@code List<Record>} per key so that duplicate rows in left are
     * cancelled one-for-one by matching rows in right (multiset difference).
     */
    private List<Record> leftIsSmaller(List<Record> leftList, List<Record> rightList) {
        // Build in-memory map: key → list of records with that key
        Map<String, List<Record>> leftMap = new HashMap<>();
        for (Record r : leftList) {
            leftMap.computeIfAbsent(UnionOperator.recordKey(r), k -> new ArrayList<>()).add(r);
        }

        // Cancel: for each right record found in the map, remove one occurrence
        for (Record r : rightList) {
            stats.addComparisons(1);
            String key = UnionOperator.recordKey(r);
            List<Record> bucket = leftMap.get(key);
            if (bucket != null && !bucket.isEmpty()) {
                bucket.remove(bucket.size() - 1);   // remove one occurrence
                if (bucket.isEmpty()) leftMap.remove(key);
            }
        }

        // Emit everything remaining in the map
        List<Record> output = new ArrayList<>();
        for (List<Record> bucket : leftMap.values()) {
            output.addAll(bucket);
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() { return "Difference"; }

    public ExecutionStats getStats() { return stats; }
}
