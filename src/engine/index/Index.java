//IMPORTANT ASSUMTPTION: INDICES BYPASS BUFFERPOOL ENTIRLEY, THEY CALL DISK MANAGER DIRECTLY
//For this project that's a perfectly acceptable tradeoff. The buffer pool exists to speed up heap file scans,
//and linear hash lookups are already O(1) — typically one or two page reads — so caching them buys very little. 
//It also keeps the implementation clean without needing to solve the ID collision problem.
package engine.index;

import engine.record.Field;
import engine.record.RecordId;

import java.io.IOException;
import java.util.List;

/**
 * Common interface for all index structures.
 *
 * Every index maps a key (a Field value) to one or more RecordIds
 * pointing at the matching heap records.
 */
public interface Index {

    /** Inserts a key → RID mapping into the index. */
    void insert(Field key, RecordId rid) throws IOException;

    /**
     * Returns all RecordIds whose key equals the given value.
     * Returns an empty list if no match is found.
     */
    List<RecordId> search(Field key) throws IOException;

    /**
     * Removes the specific key → RID mapping from the index.
     * Does nothing if the mapping does not exist.
     */
    void delete(Field key, RecordId rid) throws IOException;

    /** Name of the column this index is built on. */
    String getColumnName();
}