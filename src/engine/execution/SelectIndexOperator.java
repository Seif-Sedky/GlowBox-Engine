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
 * Uses a {@link LinearHashIndex} to locate records matching a specific equality
 * condition ({@code column = value}) without scanning the entire heap file.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Hash the search key to identify the correct bucket in the index.</li>
 *   <li>Collect all {@link RecordId}s stored in that bucket (and any overflow
 *       pages chained to it).</li>
 *   <li>Fetch each matching record from the heap file by its RecordId.</li>
 * </ol>
 *
 * <p><b>Limitation:</b> Only equality predicates ({@code =}) are supported.
 * The LinearHashIndex is a hash structure — range queries must use
 * {@link SelectLinearOperator} instead.
 *
 * <p><b>Cost vs SelectLinear:</b><br>
 * {@code SelectLinear} reads every page in the heap file — O(pages).<br>
 * {@code SelectIndex} reads one or two index pages then only the matching heap
 * pages — O(k) where k is the number of matching records.  For low-selectivity
 * queries on large tables this is far cheaper.
 */
public class SelectIndexOperator extends Operator {

    private final HeapFile        heapFile;
    private final LinearHashIndex index;
    private final Field           searchKey;
    private final ExecutionStats  stats;

    public SelectIndexOperator(HeapFile heapFile, LinearHashIndex index,
                               Field searchKey, ExecutionStats stats) {
        this.heapFile  = heapFile;
        this.index     = index;
        this.searchKey = searchKey;
        this.stats     = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public SelectIndexOperator(HeapFile heapFile, LinearHashIndex index, Field searchKey) {
        this(heapFile, index, searchKey, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<RecordId> rids   = index.search(searchKey);
        List<Record>   output = new ArrayList<>(rids.size());

        for (RecordId rid : rids) {
            stats.addScanned(1);
            output.add(heapFile.get(rid));
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() {
        return "SelectIndex(" + heapFile.getSchema().getTableName()
               + " on " + index.getColumnName() + "=" + searchKey.getValue() + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
