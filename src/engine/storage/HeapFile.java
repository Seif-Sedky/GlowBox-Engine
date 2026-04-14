package engine.storage;

import engine.buffer.BufferPoolManager;
import engine.catalog.TableSchema;
import engine.record.Record;
import engine.record.RecordId;
import engine.record.RecordSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the heap file for a single table.
 *
 * A heap file is an unordered collection of pages. Records are inserted
 * into the first page with enough free space, with new pages allocated
 * as needed. There is no ordering guarantee — that is the job of indexes.
 *
 * HeapFile is the only class that should call SlottedPageLayout directly.
 * Every layer above (execution operators, indexes) goes through HeapFile
 * to read and write records.
 *
 * Responsibilities:
 *   - INSERT: serialise → find space → write slot → update stats → return RID
 *   - GET:    fetch page → read slot → deserialise → return Record
 *   - DELETE: fetch page → read+deserialise (for stats) → zero slot → update stats
 *   - UPDATE: fetch page → overwrite slot bytes in place
 *   - SCAN:   iterate every page, every live slot, yield LocatedRecords
 */
public class HeapFile {

    private final TableSchema      schema;
    private final BufferPoolManager bufferPool;
    private final RecordSerializer  serializer;

    public HeapFile(TableSchema schema, BufferPoolManager bufferPool) {
        this.schema     = schema;
        this.bufferPool = bufferPool;
        this.serializer = new RecordSerializer(schema);
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------

    /**
     * Inserts a record into the first page with enough free space,
     * allocating a new page if necessary.
     *
     * @return the RecordId assigned to the inserted record
     */
    public RecordId insert(Record record) throws IOException {
        byte[] bytes = serializer.serialize(record);

        // Walk existing pages looking for one with enough contiguous free space
        for (int pageNum = 0; pageNum < schema.getPageCount(); pageNum++) {
            PageId pageId = new PageId(schema.getTableId(), pageNum);
            Page   page   = bufferPool.fetchPage(pageId);

            boolean needsNewSlot = !hasDeletedSlot(page);
            if (SlottedPageLayout.hasSpaceFor(page, bytes.length, needsNewSlot)) {
                int slotNum = SlottedPageLayout.insertRecord(page, bytes);
                bufferPool.unpinPage(pageId, true);

                RecordId rid = new RecordId(pageId, slotNum);
                schema.getStats().recordInsert(record);
                return rid;
            }

            bufferPool.unpinPage(pageId, false); //didnt insert
        }

        // No existing page had space — allocate a new one
        Page newPage = bufferPool.allocatePage(schema.getTableId());
        PageId newPageId = newPage.getPageId();
        schema.incrementPageCount();

        int slotNum = SlottedPageLayout.insertRecord(newPage, bytes);
        bufferPool.unpinPage(newPageId, true);

        RecordId rid = new RecordId(newPageId, slotNum);
        schema.getStats().recordInsert(record);
        return rid;
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    /**
     * Retrieves the record at the given RecordId.
     */
    public Record get(RecordId rid) throws IOException {
        Page page = bufferPool.fetchPage(rid.getPageId());
        byte[] bytes = SlottedPageLayout.getRecord(page, rid.getSlotNumber());
        bufferPool.unpinPage(rid.getPageId(), false);
        return serializer.deserialize(bytes);
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    /**
     * Physically deletes the record at the given RecordId.
     * The record is read first so stats can be updated accurately.
     */
    public void delete(RecordId rid) throws IOException {
        Page page = bufferPool.fetchPage(rid.getPageId());

        // Read before deleting so we can update histogram stats
        byte[]  bytes  = SlottedPageLayout.getRecord(page, rid.getSlotNumber());
        Record  record = serializer.deserialize(bytes);

        SlottedPageLayout.deleteRecord(page, rid.getSlotNumber());
        bufferPool.unpinPage(rid.getPageId(), true);

        schema.getStats().recordDelete(record);
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    /**
     * Updates the record at the given RecordId with new field values.
     * Because all types are fixed-length, this is always an in-place overwrite.
     *
     * Stats are adjusted: the old record is subtracted, the new one added.
     */
    public void update(RecordId rid, Record newRecord) throws IOException {
        byte[] oldBytes = serializer.serialize(get(rid));
        Record oldRecord = serializer.deserialize(oldBytes);

        byte[] newBytes = serializer.serialize(newRecord);
		Page page = bufferPool.fetchPage(rid.getPageId());
        SlottedPageLayout.updateRecord(page, rid.getSlotNumber(), newBytes);
        bufferPool.unpinPage(rid.getPageId(), true);

        schema.getStats().recordDelete(oldRecord);
        schema.getStats().recordInsert(newRecord);
    }

    // -------------------------------------------------------------------------
    // SCAN
    // -------------------------------------------------------------------------

    /**
     * Returns every live record in the table, in heap order.
     *
     * This is what SeqScanOperator calls. Each record is paired with its
     * RecordId so callers that need to pass RIDs to index lookups can do so.
     */
    public List<LocatedRecord> scan() throws IOException {
        List<LocatedRecord> results = new ArrayList<>();

        for (int pageNum = 0; pageNum < schema.getPageCount(); pageNum++) {
            PageId pageId = new PageId(schema.getTableId(), pageNum);
            Page   page   = bufferPool.fetchPage(pageId);

            int numSlots = SlottedPageLayout.readNumSlots(page);
            for (int slot = 0; slot < numSlots; slot++) {
                if (SlottedPageLayout.isSlotOccupied(page, slot)) {
                    byte[]   bytes  = SlottedPageLayout.getRecord(page, slot);
                    Record   record = serializer.deserialize(bytes);
                    RecordId rid    = new RecordId(pageId, slot);
                    results.add(new LocatedRecord(rid, record));
                }
            }

            bufferPool.unpinPage(pageId, false);
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public TableSchema getSchema() { return schema; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Returns true if the page has at least one deleted slot that can be reused. */
    private boolean hasDeletedSlot(Page page) {
        int numSlots = SlottedPageLayout.readNumSlots(page);
        for (int s = 0; s < numSlots; s++) {
            if (!SlottedPageLayout.isSlotOccupied(page, s)) return true;
        }
        return false;
    }
}