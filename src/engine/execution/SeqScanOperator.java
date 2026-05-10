package engine.execution;

import engine.record.Record;
import engine.storage.HeapFile;
import engine.storage.LocatedRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sequential scan — reads every live record from a heap file in page order.
 *
 * This is the simplest (and most expensive) access operator.  It reads every
 * page of the heap file from first to last, yielding every non-deleted slot.
 * No predicate is applied here — use {@link FilterOperator} on top of a
 * SeqScan when filtering is needed.
 *
 * <p>Cost: proportional to the total number of pages in the table.
 */
public class SeqScanOperator extends Operator {

    private final HeapFile       heapFile;
    private final ExecutionStats stats;

    public SeqScanOperator(HeapFile heapFile, ExecutionStats stats) {
        this.heapFile = heapFile;
        this.stats    = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public SeqScanOperator(HeapFile heapFile) {
        this(heapFile, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<LocatedRecord> located = heapFile.scan();
        List<Record>        output  = new ArrayList<>(located.size());

        for (LocatedRecord lr : located) {
            output.add(lr.record());
        }

        stats.addScanned(output.size());
        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() {
        return "SeqScan(" + heapFile.getSchema().getTableName() + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
