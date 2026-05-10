package engine.storage;

import java.util.Objects;

/**
 * A strongly-typed identifier for a page on disk.
 *
 * Rather than passing raw ints around the codebase (where a tableId,
 * a pageNumber, and a slotNumber all look identical), PageId bundles
 * the two coordinates that uniquely locate a page: which table file
 * it belongs to, and its position within that file.
 *
 * Every layer that touches pages — the buffer pool's HashMap, B+ Tree
 * node pointers, event payloads — uses this type so the compiler catches
 * mix-ups that a bare int never would.
 *
 * Page numbering is zero-based: the first page in a file is page 0.
 */

public final class PageId {

    /** Identifies which table's .db file this page lives in. */
    private final int tableId;

    /** Zero-based position of this page within the table's file. */
    private final int pageNumber;


    public PageId(int tableId, int pageNumber) {
        if (tableId < 0)    throw new IllegalArgumentException("tableId must be >= 0, got: "    + tableId);
        if (pageNumber < 0) throw new IllegalArgumentException("pageNumber must be >= 0, got: " + pageNumber);
        this.tableId    = tableId;
        this.pageNumber = pageNumber;
    }

    public int getTableId()    { return tableId;    }
    public int getPageNumber() { return pageNumber; }
    public String toString() {
        return "PageId{table=" + tableId + ", page=" + pageNumber + "}";
    }
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageId other)) return false;
        return tableId == other.tableId && pageNumber == other.pageNumber;
    }

    
    /**
     * Byte offset of this page within the file.
     * Used by DiskManager when seeking (search) before a read or write.
     */
    public long fileOffset() {
        return (long) pageNumber * Page.PAGE_SIZE;
    }

    /**
     * Generates a numeric bucket ID (hash code) based on the tableId and pageNumber.
     * * WHY THIS IS CRITICAL:
     * We use PageId as the key in the Buffer Pool's HashMap cache. 
     * If we don't override hashCode(), Java defaults to using the object's 
     * memory address. This means new PageId(1, 5) and new PageId(1, 5) 
     * would map to different buckets, breaking the cache completely and 
     * causing endless disk reads.
     */
    
    @Override
    public int hashCode() {
        return Objects.hash(tableId, pageNumber);
    }
   
}