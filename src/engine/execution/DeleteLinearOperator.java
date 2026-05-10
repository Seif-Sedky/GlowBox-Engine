package engine.execution;

import engine.index.hash.LinearHashIndex;
import engine.record.Field;
import engine.record.Record;
import engine.record.RecordId;
import engine.storage.HeapFile;
import engine.storage.LocatedRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Scans a heap file linearly, deletes every record matching a predicate,
 * and updates all provided indexes to reflect the removals.
 *
 * Algorithm:
 *   1. Scan the full heap file to get (rid, record) pairs.
 *   2. Apply the predicate to each record.
 *   3. For each match: delete from the heap file, then remove the
 *      corresponding key → rid entry from every index.
 *
 * Returns the list of records that were deleted — useful for logging
 * and for the UI to display what was removed.
 *
 * Index updates:
 *   Each index is keyed on one column. To remove the correct entry,
 *   the operator reads that column's value from the deleted record and
 *   calls index.delete(key, rid). If no indexes are provided the heap
 *   delete still happens correctly — indexes are optional.
 */
public class DeleteLinearOperator extends Operator {

    private final HeapFile              heapFile;
    private final Predicate<Record>     predicate;
    private final List<LinearHashIndex> indexes;
    private final ExecutionStats        stats;

    /**
     * @param heapFile  the table to delete from
     * @param predicate condition a record must satisfy to be deleted
     * @param indexes   all indexes on this table that must be kept in sync
     */
    public DeleteLinearOperator(HeapFile heapFile, Predicate<Record> predicate,
                                List<LinearHashIndex> indexes, ExecutionStats stats) {
        this.heapFile  = heapFile;
        this.predicate = predicate;
        this.indexes   = indexes;
        this.stats     = stats;
    }

    public DeleteLinearOperator(HeapFile heapFile, Predicate<Record> predicate,
                                List<LinearHashIndex> indexes) {
        this(heapFile, predicate, indexes, new ExecutionStats());
    }

    /** Convenience — no indexes to maintain. */
    public DeleteLinearOperator(HeapFile heapFile, Predicate<Record> predicate) {
        this(heapFile, predicate, List.of(), new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<LocatedRecord> all     = heapFile.scan();
        List<Record>        deleted = new ArrayList<>();

        for (LocatedRecord lr : all) {
            stats.addScanned(1);
            stats.addComparisons(1);

            if (predicate.test(lr.record())) {
                RecordId rid = lr.rid();

                // Remove from heap first
                heapFile.delete(rid);

                // Remove from every index — each index is keyed on one column
                for (LinearHashIndex index : indexes) {
                    Field key = lr.record().getField(index.getColumnName());
                    index.delete(key, rid);
                }

                deleted.add(lr.record());
            }
        }

        stats.addOutput(deleted.size());
        return deleted;
    }

    @Override
    public String operatorName() {
        return "DeleteLinear(" + heapFile.getSchema().getTableName() + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
