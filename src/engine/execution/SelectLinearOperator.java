package engine.execution;

import engine.record.Record;
import engine.storage.HeapFile;
import engine.storage.LocatedRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Scans a heap file linearly and returns every record matching a predicate.
 *
 * <p>Functionally equivalent to {@link SeqScanOperator} + {@link FilterOperator}
 * combined into one operator, which makes physical plan readouts cleaner.
 * No index is consulted — records are fetched in disk (heap) order, page by page.
 *
 * <p><b>When to use this vs {@link SelectIndexOperator}:</b>
 * <ul>
 *   <li>No index exists on the target column.</li>
 *   <li>The predicate is not a simple equality (e.g. range {@code >}, {@code !=},
 *       {@code OR}, {@code LIKE}) — the hash index only supports exact equality.</li>
 *   <li>The table is small enough that a full scan is cheaper than the index
 *       lookup overhead.</li>
 * </ul>
 *
 * <p>Cost: O(N) — proportional to total number of records in the heap file.
 */
public class SelectLinearOperator extends Operator {

    private final HeapFile          heapFile;
    private final Predicate<Record> predicate;
    private final ExecutionStats    stats;

    public SelectLinearOperator(HeapFile heapFile, Predicate<Record> predicate,
                                ExecutionStats stats) {
        this.heapFile  = heapFile;
        this.predicate = predicate;
        this.stats     = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public SelectLinearOperator(HeapFile heapFile, Predicate<Record> predicate) {
        this(heapFile, predicate, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<LocatedRecord> all    = heapFile.scan();
        List<Record>        output = new ArrayList<>();

        for (LocatedRecord lr : all) {
            stats.addScanned(1);
            stats.addComparisons(1);
            if (predicate.test(lr.record())) {
                output.add(lr.record());
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() {
        return "SelectLinear(" + heapFile.getSchema().getTableName() + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
