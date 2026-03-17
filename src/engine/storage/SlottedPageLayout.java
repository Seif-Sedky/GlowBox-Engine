package engine.storage;

/**
 * Stateless utility class that interprets a Page's bytes as a slotted-page.
 *
 * ┌─────────────────────────── PAGE (256 bytes) ─────────────────────────────┐
 * │  HEADER (4 bytes fixed)                                                  │
 * │  [numSlots : 2B] [freeSpacePtr : 2B]                                     │
 * │                                                                          │
 * │  SLOT DIRECTORY  (grows →)     FREE SPACE        RECORDS (← grows)       │
 * │  [slot0: off+len][slot1: ...]  ░░░░░░░░░░░░░░░  [...rec1...][...rec0...] │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Header layout  (offsets from byte 0):
 *   0–1  numSlots        : number of slot entries currently in the directory
 *   2–3  freeSpacePtr    : byte offset of the start of the next free region
 *                          (records are appended leftward from PAGE_SIZE)
 *
 * Slot entry layout  (4 bytes each, starting at offset HEADER_SIZE):
 *   0–1  recordOffset    : byte offset of the record within the page
 *                          SLOT_DELETED (0) means this slot is a tombstone
 *   2–3  recordLength    : byte length of the record (0 when deleted)
 *
 * Records grow from the END of the page toward the header.
 * The slot directory grows from the header toward the end of the page.
 * Free space sits between them.
 *
 * Why this layout?
 *   - Slot numbers are stable — a RecordId (pageId, slotNum) remains valid
 *     after compaction because we never renumber slots (deleted slots are
 *     reused by future inserts).
 *   - Physical deletes are supported: the record bytes are zeroed and the slot
 *     is marked SLOT_DELETED, making the tombstone visible in the UI.
 *   - No variable-length metadata — every slot entry is exactly 4 bytes,
 *     so slot N always lives at offset HEADER_SIZE + N * SLOT_ENTRY_SIZE.
 */
public final class SlottedPageLayout {

    public static final int OFFSET_NUM_SLOTS      = 0;
    public static final int OFFSET_FREE_SPACE_PTR = 2;
    public static final int HEADER_SIZE           = 4;
    public static final int SLOT_ENTRY_SIZE       = 4;
    public static final short SLOT_DELETED        = 0;
    private SlottedPageLayout() {}
    /**
     * Writes a blank slotted-page header onto a freshly allocated page.
     * Must be called exactly once per new page before any other method.
     *
     * Initial state:
     *   numSlots     = 0
     *   freeSpacePtr = PAGE_SIZE   (free region starts at the very end)
     */
    public static void initPage(Page page) {
        page.writeShort(OFFSET_NUM_SLOTS,       (short) 0);
        page.writeShort(OFFSET_FREE_SPACE_PTR,  (short) Page.PAGE_SIZE);
    } //why this not in page class?

    /**
     * Inserts a record into the page and returns its slot number.
     *
     * Strategy:
     *   1. Try to reuse a deleted slot (avoids fragmenting the directory).
     *   2. Otherwise append a new slot entry to the directory.
     *   3. Write the record bytes just below freeSpacePtr.
     *   4. Return the slot number the record was inserted in, otherwise throw an exception
     */
    public static int insertRecord(Page page, byte[] recordBytes) {
        if (recordBytes == null || recordBytes.length == 0)
            throw new IllegalArgumentException("Record bytes must be non-empty.");
        if (recordBytes.length > Page.PAGE_SIZE - HEADER_SIZE)
            throw new IllegalArgumentException(
                "Record (" + recordBytes.length + "B) exceeds maximum storable size.");

        int numSlots     = readNumSlots(page); //reads the number of slots from the page using the defined offset 
        int freeSpacePtr = readFreeSpacePtr(page); //reads the pointer to the free space using the defined offset
        int recordLen    = recordBytes.length; 

        // ── Step 1: look for a reusable deleted slot ─────────────────────────
        int targetSlot = -1;
        for (int s = 0; s < numSlots; s++) {
            if (readSlotOffset(page, s) == SLOT_DELETED) {
                targetSlot = s;
                break;
            }
        }

        // ── Step 2: compute space needed ──────────────────────────────────────
        // If we reuse a slot we don't need extra directory space; otherwise we
        // need SLOT_ENTRY_SIZE more bytes for the new slot entry.
        int slotDirEnd   = HEADER_SIZE + numSlots * SLOT_ENTRY_SIZE;
        int extraDirCost = (targetSlot == -1) ? SLOT_ENTRY_SIZE : 0;
        int newFreePtr   = freeSpacePtr - recordLen;

        if (newFreePtr < slotDirEnd + extraDirCost)
            throw new IllegalStateException(
                "Not enough free space on page " + page.getPageId()
                + ": need " + (recordLen + extraDirCost)
                + "B, have " + freeSpace(page) + "B.");

        // ── Step 3: allocate the slot ─────────────────────────────────────────
        if (targetSlot == -1) {
            targetSlot = numSlots;
            writeNumSlots(page, (short) (numSlots + 1));
        }

        // ── Step 4: write record at new freeSpacePtr ─────────────────────────
        page.writeBytes(newFreePtr, recordBytes, 0, recordLen);
        writeFreeSpacePtr(page, (short) newFreePtr);

        // ── Step 5: update slot directory ────────────────────────────────────
        writeSlotOffset(page, targetSlot, (short) newFreePtr);
        writeSlotLength(page, targetSlot, (short) recordLen);

        return targetSlot;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns a fresh copy of the record bytes stored in the given slot.
     *
     * @param page     the page to read from
     * @param slotNum  zero-based slot index
     * @return copy of the record bytes
     * @throws IllegalArgumentException if slotNum is out of range
     * @throws IllegalStateException    if the slot has been deleted
     */
    public static byte[] getRecord(Page page, int slotNum) {
        checkSlotRange(page, slotNum);

        short offset = readSlotOffset(page, slotNum);
        if (offset == SLOT_DELETED)
            throw new IllegalStateException(
                "Slot " + slotNum + " on page " + page.getPageId() + " has been deleted.");

        short length = readSlotLength(page, slotNum);
        byte[] record = new byte[length];
        page.readBytes(offset, record, 0, length);
        return record;
    }
    public static boolean isSlotOccupied(Page page, int slotNum) {
        if (slotNum < 0 || slotNum >= readNumSlots(page)) return false;
        return readSlotOffset(page, slotNum) != SLOT_DELETED;
    }

    // -------------------------------------------------------------------------
    // Delete  (physical — bytes are zeroed, slot is tombstoned)
    // -------------------------------------------------------------------------

    /**
     * Physically deletes the record in the given slot.
     *
     * The record's bytes are zeroed so the UI can show the gap, and the slot
     * entry is marked SLOT_DELETED so it can be reused by a future insert.
     * The free space pointer is NOT moved backwards — compaction is a separate
     * explicit operation.
     *
     * @param page    the page containing the record
     * @param slotNum zero-based slot index
     */
    public static void deleteRecord(Page page, int slotNum) {
        checkSlotRange(page, slotNum);

        short offset = readSlotOffset(page, slotNum);
        if (offset == SLOT_DELETED)
            throw new IllegalStateException(
                "Slot " + slotNum + " on page " + page.getPageId() + " is already deleted.");

        short length = readSlotLength(page, slotNum);

        // Zero the record bytes (makes the delete visible in the UI)
        byte[] zeros = new byte[length];
        page.writeBytes(offset, zeros, 0, length);

        // Tombstone the slot
        writeSlotOffset(page, slotNum, SLOT_DELETED);
        writeSlotLength(page, slotNum, (short) 0);
    }

    // -------------------------------------------------------------------------
    // Update  (in-place, only allowed if new record is the same size)
    // -------------------------------------------------------------------------

    /**
     * Overwrites a record in-place.
     *
     * Because all our types are fixed-length, updates never change record size,
     * so an in-place write is always safe and keeps the slot directory stable.
     *
     * @param page       the page containing the record
     * @param slotNum    zero-based slot index
     * @param newBytes   replacement bytes — must be exactly the same length as
     *                   the existing record
     */
    public static void updateRecord(Page page, int slotNum, byte[] newBytes) {
        checkSlotRange(page, slotNum);

        short offset = readSlotOffset(page, slotNum);
        if (offset == SLOT_DELETED) //offset is zero, thus deleted
            throw new IllegalStateException(
                "Cannot update deleted slot " + slotNum + " on page " + page.getPageId());

        short existingLength = readSlotLength(page, slotNum); // you have the position, now get the length 
        if (newBytes.length != existingLength)
            throw new IllegalArgumentException(
                "Update byte length (" + newBytes.length + ") != existing length ("
                + existingLength + "). All types are fixed-length; sizes must match.");

        page.writeBytes(offset, newBytes, 0, newBytes.length);
    }

    // -------------------------------------------------------------------------
    // Compaction
    // -------------------------------------------------------------------------

    /**
     * Reclaims fragmented space by rewriting all live records contiguously.
     *
     * After many deletes the record area may be scattered with gaps. This method
     * rebuilds the record section from scratch, then updates the slot directory
     * to reflect the new offsets. The slot numbers of surviving records do not
     * change — RecordIds remain valid.
     *
     * This is an O(n) full-page rewrite and should be called explicitly (e.g.,
     * when an insert fails due to fragmentation despite technically enough
     * live-record space being present).
     */
    public static void compact(Page page) {
        int numSlots = readNumSlots(page);

        // Collect live records in slot order
        byte[][] liveRecords  = new byte[numSlots][];
        int[]    liveSlots    = new int[numSlots];
        int      liveCount    = 0;

        for (int s = 0; s < numSlots; s++) {
            if (isSlotOccupied(page, s)) {
                liveRecords[liveCount] = getRecord(page, s);
                liveSlots[liveCount]   = s;
                liveCount++;
            }
        }

        // Reset free space pointer and re-pack records from the end
        int writePtr = Page.PAGE_SIZE;
        for (int i = 0; i < liveCount; i++) {
            byte[] rec = liveRecords[i];
            writePtr -= rec.length;
            page.writeBytes(writePtr, rec, 0, rec.length);
            writeSlotOffset(page, liveSlots[i], (short) writePtr);
            // length unchanged — fixed-size records
        }

        writeFreeSpacePtr(page, (short) writePtr);
    }

    // -------------------------------------------------------------------------
    // Space & metadata helpers
    // -------------------------------------------------------------------------

    /** Number of slot entries in the directory (includes deleted slots). */
    public static int readNumSlots(Page page) {
        return page.readShort(OFFSET_NUM_SLOTS) & 0xFFFF;
    }

    /** Number of live (non-deleted) records on this page. */
    public static int liveRecordCount(Page page) {
        int count = 0;
        int total = readNumSlots(page);
        for (int s = 0; s < total; s++) {
            if (isSlotOccupied(page, s)) count++;
        }
        return count;
    }

    /**
     * Bytes of contiguous free space between the end of the slot directory
     * and the start of the record area. This is the space available for the
     * NEXT insert without compaction.
     */
    public static int freeSpace(Page page) {
        int slotDirEnd   = HEADER_SIZE + readNumSlots(page) * SLOT_ENTRY_SIZE;
        int freeSpacePtr = readFreeSpacePtr(page);
        return Math.max(0, freeSpacePtr - slotDirEnd);
    }

    /**
     * Returns true if a record of exactly {@code recordLen} bytes can be
     * inserted without requiring compaction.
     *
     * @param needNewSlot pass true if no deleted slot is available for reuse
     */
    public static boolean hasSpaceFor(Page page, int recordLen, boolean needNewSlot) {
        int cost = recordLen + (needNewSlot ? SLOT_ENTRY_SIZE : 0);
        return freeSpace(page) >= cost;
    }

    // -------------------------------------------------------------------------
    // Diagnostic dump  (useful during development and for the Explain view)
    // -------------------------------------------------------------------------

    /**
     * Returns a multi-line human-readable summary of the page's slot directory.
     * Intended for debugging and the UI's Explain panel — not for production paths.
     */
    public static String dump(Page page) {
        int numSlots     = readNumSlots(page);
        int freeSpacePtr = readFreeSpacePtr(page);
        StringBuilder sb = new StringBuilder();
        sb.append("=== SlottedPage ").append(page.getPageId()).append(" ===\n");
        sb.append("  numSlots    : ").append(numSlots).append("\n");
        sb.append("  freeSpacePtr: ").append(freeSpacePtr).append("\n");
        sb.append("  freeSpace   : ").append(freeSpace(page)).append("B\n");
        sb.append("  dirty       : ").append(page.isDirty()).append("\n");
        sb.append("  Slots:\n");
        for (int s = 0; s < numSlots; s++) {
            short off = readSlotOffset(page, s);
            short len = readSlotLength(page, s);
            if (off == SLOT_DELETED)
                sb.append("    [").append(s).append("] DELETED\n");
            else
                sb.append("    [").append(s).append("] offset=").append(off)
                  .append(", len=").append(len).append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private low-level slot directory accessors
    // -------------------------------------------------------------------------

    private static int readFreeSpacePtr(Page page) {
        return page.readShort(OFFSET_FREE_SPACE_PTR) & 0xFFFF;
    }

    private static void writeFreeSpacePtr(Page page, short value) {
        page.writeShort(OFFSET_FREE_SPACE_PTR, value);
    }

    private static void writeNumSlots(Page page, short value) {
        page.writeShort(OFFSET_NUM_SLOTS, value);
    }

    /** Byte offset of slot N's entry within the page. */
    private static int slotEntryOffset(int slotNum) {
        return HEADER_SIZE + slotNum * SLOT_ENTRY_SIZE;
    }

    private static short readSlotOffset(Page page, int slotNum) { //get where the record is in the page (offset)
        return page.readShort(slotEntryOffset(slotNum));
    }

    private static short readSlotLength(Page page, int slotNum) { //get how long is the record/how much bytes to move from the offset (stores 2 bytes after the offset of slot number)
        return page.readShort(slotEntryOffset(slotNum) + 2);
    }

    private static void writeSlotOffset(Page page, int slotNum, short offset) {
        page.writeShort(slotEntryOffset(slotNum), offset);
    }

    private static void writeSlotLength(Page page, int slotNum, short length) {
        page.writeShort(slotEntryOffset(slotNum) + 2, length);
    }

    private static void checkSlotRange(Page page, int slotNum) {
        int numSlots = readNumSlots(page);
        if (slotNum < 0 || slotNum >= numSlots)
            throw new IllegalArgumentException(
                "Slot " + slotNum + " out of range [0, " + numSlots + ") on page "
                + page.getPageId());
    }
}