package engine.execution;

import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eliminates duplicate records using a hash map — one pass, no sorting.
 *
 * Algorithm:
 *   1. Execute child operator to get all records.
 *   2. Insert each record into a LinkedHashMap keyed by its full record key.
 *      Duplicates are silently dropped since the key already exists.
 *   3. Return the map's values — one record per unique key, in insertion order.
 *	disk impementation would be pretty similar, you will read the hash buckets linearly to eliminate duplicates
 *
 *
 * Cost: O(n) — single pass with O(1) amortised hash map operations.
 *
 * When to prefer this over SortBasedDistinct:
 *   - Output order does not matter.
 *   - Dataset is large and sorting cost (O(n log n)) is significant.
 *   - No downstream operator requires sorted input.
 *
 * Limitation: the entire input must fit in memory (the hash map holds one
 * representative record per distinct key). For this engine's demo datasets
 * this is always satisfied.
 *
 * LinkedHashMap is used instead of HashMap to preserve insertion order,
 * making output deterministic — useful for testing and visualisation.
 */
public class HashBasedDistinctOperator extends Operator {

    private final Operator       child;
    private final ExecutionStats stats;

    public HashBasedDistinctOperator(Operator child, ExecutionStats stats) {
        this.child = child;
        this.stats = stats;
    }

    public HashBasedDistinctOperator(Operator child) {
        this(child, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> input = child.execute();
        stats.addScanned(input.size());

        Map<String, Record> seen = new LinkedHashMap<>();

        for (Record r : input) {
            stats.addComparisons(1);
            seen.putIfAbsent(UnionOperator.recordKey(r), r);
        }

        List<Record> output = new ArrayList<>(seen.values());
        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() { return "HashBasedDistinct"; }

    public ExecutionStats getStats() { return stats; }
}
