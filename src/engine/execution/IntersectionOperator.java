package engine.execution;

import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the set INTERSECTION of two child operators (records present in both).
 *
 * <h2>Algorithm — hash-based, smaller relation in memory</h2>
 * <ol>
 *   <li>Execute both child operators to get {@code leftList} and {@code rightList}.</li>
 *   <li>Identify the smaller relation and build a {@code HashMap<String, Record>}
 *       from it, keyed by {@link UnionOperator#recordKey}.</li>
 *   <li>Iterate the larger relation.  For each record whose key exists in the
 *       in-memory map: emit the record and <em>remove the key from the map</em>
 *       to prevent emitting duplicate matches if the larger relation has
 *       repeated rows.</li>
 * </ol>
 *
 * <h2>Assumptions</h2>
 * <ul>
 *   <li>At least one relation fits entirely in memory (the smaller one).</li>
 *   <li>Row equality is full field-by-field comparison via
 *       {@link UnionOperator#recordKey}.</li>
 *   <li>Both child operators produce records with identical schemas.</li>
 * </ul>
 */
public class IntersectionOperator extends Operator {

    private final Operator       left;
    private final Operator       right;
    private final ExecutionStats stats;

    public IntersectionOperator(Operator left, Operator right, ExecutionStats stats) {
        this.left  = left;
        this.right = right;
        this.stats = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public IntersectionOperator(Operator left, Operator right) {
        this(left, right, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> leftList  = left.execute();
        List<Record> rightList = right.execute();

        stats.addScanned(leftList.size() + rightList.size());

        // Smaller relation → in-memory map; larger relation → probes the map
        List<Record> larger  = (leftList.size() >= rightList.size()) ? leftList  : rightList;
        List<Record> smaller = (leftList.size() >= rightList.size()) ? rightList : leftList;

        // Build map from smaller — key → record (one entry per unique row)
        Map<String, Record> smallerMap = new HashMap<>();
        for (Record r : smaller) {
            smallerMap.put(UnionOperator.recordKey(r), r);
        }

        // Probe: for each record in the larger, check if it exists in smallerMap
        List<Record> output = new ArrayList<>();
        for (Record r : larger) {
            stats.addComparisons(1);
            String key = UnionOperator.recordKey(r);
            if (smallerMap.containsKey(key)) {
                output.add(r);
                smallerMap.remove(key);  // remove to avoid emitting duplicate matches
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() { return "Intersection"; }

    public ExecutionStats getStats() { return stats; }
}
