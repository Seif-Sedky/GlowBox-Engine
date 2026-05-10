package engine.execution;

import engine.record.Field;
import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the set UNION of two child operators (duplicates removed).
 *
 * <h2>Algorithm — hash-based, smaller relation in memory</h2>
 * <ol>
 *   <li>Execute both child operators to get {@code leftList} and {@code rightList}.</li>
 *   <li>Identify the smaller relation (by record count) and build a
 *       {@code HashMap<String, Record>} from it keyed by {@link #recordKey}.</li>
 *   <li>Emit all records from the <em>larger</em> relation into the output,
 *       inserting each into the map as it is emitted.</li>
 *   <li>For each record in the <em>smaller</em> relation: if its key is not
 *       already in the map (i.e. it has no duplicate in the larger), emit it.</li>
 * </ol>
 *
 * <h2>Assumptions</h2>
 * <ul>
 *   <li>At least one relation fits entirely in memory — guaranteed by the
 *       "smaller relation in memory" invariant.  The larger relation is streamed
 *       record-by-record (already materialised by the child operator in this
 *       bulk model).</li>
 *   <li>Row equality is determined by comparing every field value ({@link #recordKey}).
 *       Two records are equal if and only if all their field values match.</li>
 *   <li>Both child operators must produce records with identical schemas
 *       (same column count and types) — a standard SQL UNION requirement.</li>
 * </ul>
 */
public class UnionOperator extends Operator {

    private final Operator       left;
    private final Operator       right;
    private final ExecutionStats stats;

    public UnionOperator(Operator left, Operator right, ExecutionStats stats) {
        this.left  = left;
        this.right = right;
        this.stats = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public UnionOperator(Operator left, Operator right) {
        this(left, right, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> leftList  = left.execute();
        List<Record> rightList = right.execute();

        stats.addScanned(leftList.size() + rightList.size());

        // Identify larger and smaller — smaller goes into the in-memory HashMap
        List<Record> larger  = (leftList.size() >= rightList.size()) ? leftList  : rightList;
        List<Record> smaller = (leftList.size() >= rightList.size()) ? rightList : leftList;

        // Build the seen-set from the larger relation first (we always emit the larger)
        Map<String, Record> seen   = new HashMap<>();
        List<Record>        output = new ArrayList<>();

        for (Record r : larger) {
            String key = recordKey(r);
            if (!seen.containsKey(key)) {
                seen.put(key, r);
                output.add(r);
            }
        }

        // Emit smaller records not already seen in the larger
        for (Record r : smaller) {
            stats.addComparisons(1);
            String key = recordKey(r);
            if (!seen.containsKey(key)) {
                seen.put(key, r);
                output.add(r);
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() { return "Union"; }

    public ExecutionStats getStats() { return stats; }

    // -------------------------------------------------------------------------
    // Shared key helper — used by all set operators
    // -------------------------------------------------------------------------

    /**
     * Produces a canonical string key for a record by concatenating the type
     * and value of every field.  Two records are considered equal if and only
     * if they produce the same key (i.e. all field values match).
     *
     * <p>Format: {@code "TYPE:value|TYPE:value|…"} — the type prefix prevents
     * cross-type collisions (e.g. INT 1 vs BOOLEAN true).
     */
    static String recordKey(Record r) {
        StringBuilder sb = new StringBuilder();
        for (Field f : r.getFields()) {
            sb.append(f.getType()).append(':').append(f.getValue()).append('|');
        }
        return sb.toString();
    }
}
