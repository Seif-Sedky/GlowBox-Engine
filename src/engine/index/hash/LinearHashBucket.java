package engine.index.hash;

import engine.record.RecordId;
import engine.storage.Page;
import engine.storage.PageId;

/**
 * Stateless utility that interprets a Page as a flat array of fixed-size
 * index entries, preceded by a small header.
 *
 * Layout:
 * ┌──────────────────────── PAGE (256 bytes) ───────────────────────────┐
 * │ [overflowPageNum : 4B] [entryCount : 2B] [entry0][entry1]...        │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Each entry:
 *   [keyBytes : keySize B] [tableId : 4B] [pageNumber : 4B] [slotNum : 4B]
 *
 * overflowPageNum = -1 means no overflow page.
 * Entry count is the number of valid entries currently stored.
 *
 * Unlike SlottedPageLayout, entries are always appended and never have
 * gaps — deletion shifts remaining entries left. This is fine because
 * bucket pages hold at most ~15 entries (for INT keys), so shifts are cheap.
 */
public final class LinearHashBucket {

    public static final int OFFSET_OVERFLOW    = 0;  // int:  4 bytes
    public static final int OFFSET_ENTRY_COUNT = 4;  // short: 2 bytes
    public static final int HEADER_SIZE        = 6;

    /** Size of the RecordId portion of an entry (tableId + pageNum + slotNum). */
    public static final int RID_SIZE           = 12;

    private LinearHashBucket() {}

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    public static void initPage(Page page) {
        page.writeInt  (OFFSET_OVERFLOW,    -1);
        page.writeShort(OFFSET_ENTRY_COUNT, (short) 0);
    }

    // -------------------------------------------------------------------------
    // Capacity
    // -------------------------------------------------------------------------

    /** Max entries this page can hold for a given key size. */
    public static int capacity(int keySize) {
        return (Page.PAGE_SIZE - HEADER_SIZE) / entrySize(keySize);
    }

    public static boolean hasSpace(Page page, int keySize) {
        return getEntryCount(page) < capacity(keySize);
    }

    private static int entrySize(int keySize) {
        return keySize + RID_SIZE;
    }

    // -------------------------------------------------------------------------
    // Header accessors
    // -------------------------------------------------------------------------

    public static int getOverflowPageNum(Page page) {
        return page.readInt(OFFSET_OVERFLOW);
    }

    public static void setOverflowPageNum(Page page, int pageNum) {
        page.writeInt(OFFSET_OVERFLOW, pageNum);
    }

    public static int getEntryCount(Page page) {
        return page.readShort(OFFSET_ENTRY_COUNT) & 0xFFFF;
    }

    // -------------------------------------------------------------------------
    // Entry access
    // -------------------------------------------------------------------------

    /**
     * Appends one entry to the page. Caller must verify hasSpace() first.
     */
    public static void addEntry(Page page, byte[] keyBytes, RecordId rid) {
        int count  = getEntryCount(page);
        int offset = HEADER_SIZE + count * entrySize(keyBytes.length);

        page.writeBytes(offset, keyBytes, 0, keyBytes.length);
        offset += keyBytes.length;

        page.writeInt(offset,     rid.getPageId().getTableId());
        page.writeInt(offset + 4, rid.getPageId().getPageNumber());
        page.writeInt(offset + 8, rid.getSlotNumber());

        page.writeShort(OFFSET_ENTRY_COUNT, (short)(count + 1));
    }

    /**
     * Returns the key bytes of the entry at the given index.
     */
    public static byte[] getKeyBytes(Page page, int entryIndex, int keySize) {
        int    offset = entryOffset(entryIndex, keySize);
        byte[] key    = new byte[keySize];
        page.readBytes(offset, key, 0, keySize);
        return key;
    }

    /**
     * Returns the RecordId of the entry at the given index.
     */
    public static RecordId getRecordId(Page page, int entryIndex, int keySize) {
        int offset    = entryOffset(entryIndex, keySize) + keySize;
        int tableId   = page.readInt(offset);
        int pageNum   = page.readInt(offset + 4);
        int slotNum   = page.readInt(offset + 8);
        return new RecordId(new PageId(tableId, pageNum), slotNum);
    }

    /**
     * Removes the entry at the given index by shifting subsequent entries left.
     * O(n) but n ≤ 15 for INT keys so this is fine.
     */
    public static void removeEntry(Page page, int entryIndex, int keySize) {
        int count = getEntryCount(page);
        int eSize = entrySize(keySize);

        // Shift entries after entryIndex one position to the left
        for (int i = entryIndex + 1; i < count; i++) {
            int srcOffset  = HEADER_SIZE + i * eSize;
            int destOffset = HEADER_SIZE + (i - 1) * eSize;
            byte[] entry = new byte[eSize];
            page.readBytes(srcOffset, entry, 0, eSize);
            page.writeBytes(destOffset, entry, 0, eSize);
        }

        // Zero out the last (now duplicate) entry slot
        int lastOffset = HEADER_SIZE + (count - 1) * eSize;
        page.writeBytes(lastOffset, new byte[eSize], 0, eSize);

        page.writeShort(OFFSET_ENTRY_COUNT, (short)(count - 1));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int entryOffset(int entryIndex, int keySize) {
        return HEADER_SIZE + entryIndex * entrySize(keySize);
    }
}