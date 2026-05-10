package engine.execution;

import engine.index.hash.LinearHashIndex;
import engine.record.Field;
import engine.record.Record;
import engine.record.RecordId;
import engine.storage.HeapFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses a LinearHashIndex to locate and delete records matching a specific
 * equality condition (column = value) without scanning the entire heap file.
 *
 * Algorithm:
 *   1. Search the index for the given key → get matching RecordIds.
 *   2. For each RecordId: fetch the record from the heap (for return value
 *      and for updating other indexes), delete it from the heap, then
 *      remove the key → rid entry from the primary index and all other indexes.
 *
 * Why fetch the record before deleting:
 *   HeapFile.delete() physically zeros the slot. Once that happens the bytes
 *   are gone. We need the record in hand first to:
 *     a) return it to the caller so the UI can show what was deleted
 *     b) extract the key values needed to clean up other indexes
 *
 * Primary index vs other indexes:
 *   The primary index is the one used to find the records (the one whose
 *   column matches the search key). Other indexes may be keyed on different
 *   columns of the same table and must also be updated to stay consistent.
 */
public class DeleteIndexOperator extends Operator {

    private final HeapFile              heapFile;
    private final LinearHashIndex       primaryIndex;
    private final Field                 searchKey;
    private final List<LinearHashIndex> otherIndexes;
    private final ExecutionStats        stats;

    /**
     * @param heapFile      the table to delete from
     * @param primaryIndex  the index used to locate matching records
     * @param searchKey     the equality value to search for
     * @param otherIndexes  any additional indexes on this table that need updating
     */
    public DeleteIndexOperator(HeapFile heapFile, LinearHashIndex primaryIndex,
                               Field searchKey, List<LinearHashIndex> otherIndexes,
                               ExecutionStats stats) {
        this.heapFile     = heapFile;
        this.primaryIndex = primaryIndex;
        this.searchKey    = searchKey;
        this.otherIndexes = otherIndexes;
        this.stats        = stats;
    }

    public DeleteIndexOperator(HeapFile heapFile, LinearHashIndex primaryIndex,
                               Field searchKey, List<LinearHashIndex> otherIndexes) {
        this(heapFile, primaryIndex, searchKey, otherIndexes, new ExecutionStats());
    }

    /** Convenience — no other indexes to maintain. */
    public DeleteIndexOperator(HeapFile heapFile, LinearHashIndex primaryIndex,
                               Field searchKey) {
        this(heapFile, primaryIndex, searchKey, List.of(), new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<RecordId> rids    = primaryIndex.search(searchKey);
        List<Record>   deleted = new ArrayList<>(rids.size());

        for (RecordId rid : rids) {
            stats.addScanned(1);

            // Fetch before deleting — bytes are gone after heap delete
            Record record = heapFile.get(rid);

            // Delete from heap
            heapFile.delete(rid);

            // Remove from primary index
            primaryIndex.delete(searchKey, rid);

            // Remove from every other index on this table
            for (LinearHashIndex other : otherIndexes) {
                Field key = record.getField(other.getColumnName());
                other.delete(key, rid);
            }

            deleted.add(record);
        }

        stats.addOutput(deleted.size());
        return deleted;
    }

    @Override
    public String operatorName() {
        return "DeleteIndex(" + heapFile.getSchema().getTableName()
               + " on " + primaryIndex.getColumnName()
               + "=" + searchKey.getValue() + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
