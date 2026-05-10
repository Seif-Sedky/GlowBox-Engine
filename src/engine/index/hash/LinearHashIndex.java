package engine.index.hash;

import engine.catalog.ColumnDef;
import engine.index.Index;
import engine.record.Field;
import engine.record.RecordId;
import engine.storage.DiskManager;
import engine.storage.Page;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * Disk-based Linear Hash Index.
 *
 * Maps field values to RecordIds using linear hashing, where the number
 * of buckets grows and shrinks incrementally to maintain a target load factor.
 *
 * ── Algorithm summary ────────────────────────────────────────────────────────
 * State: i  = number of LSB bits used to determine bucket
 *        M  = index of the last UNLOCKED bucket (0-based)
 *
 * Lookup(key):
 *   b = hash(key) & ((1<<i)-1)       // take i LSB bits
 *   if b > M: b = b - (1<<(i-1))     // redirect to temp bucket
 *   search bucket b and its overflow chain
 *
 * Insert(key, rid):
 *   find bucket b via Lookup rule, insert entry
 *   if bucket page is full → chain an overflow page
 *   after insert: if utilization > 80% → split()
 *
 * Split():
 *   newBucket = M + 1
 *   tempBucket = newBucket - 2^(i-1)   // the bucket that held its entries
 *   allocate a page for newBucket
 *   collect all entries from tempBucket + its overflow chain
 *   clear tempBucket chain
 *   M = newBucket
 *   if M+1 == 2^i: i++
 *   re-insert collected entries (they now distribute between temp and new bucket)
 *
 * Merge() (inverse of split):
 *   lastBucket = M
 *   tempBucket = lastBucket - 2^(i-1)
 *   collect all entries from lastBucket
 *   re-insert them into tempBucket
 *   M = lastBucket - 1
 *   if M < 2^(i-1): i--
 *
 * ── Disk layout ──────────────────────────────────────────────────────────────
 * Page 0: Header
 *   [i:4B][M:4B][totalRecords:4B][nextFreePage:4B][bucketPageNums: 60×4B = 240B]
 *
 * All other pages: either bucket primary pages or overflow pages.
 * Overflow chains are singly linked via the overflowPageNum field in
 * each bucket page header (BucketPageLayout).
 *
 * bucketPageNums[b] = page number of bucket b's primary page, or -1 if not yet allocated.
 * nextFreePage: next page number to use when allocating a new bucket or overflow page.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class LinearHashIndex implements Index {

    // ── Header page field offsets ────────────────────────────────────────────
    private static final int H_OFFSET_I             = 0;
    private static final int H_OFFSET_M             = 4;
    private static final int H_OFFSET_TOTAL_RECORDS = 8;
    private static final int H_OFFSET_NEXT_FREE     = 12;
    private static final int H_OFFSET_BUCKET_NUMS   = 16;  // int[] starts here
    private static final int MAX_BUCKETS            = 60;  // fits in header page
    private static final int HEADER_PAGE_NUM        = 0;

    // ── Thresholds ───────────────────────────────────────────────────────────
    private static final double LOAD_FACTOR = 0.80;

    // ── State ────────────────────────────────────────────────────────────────
    private int   i;
    private int   M;
    private int   totalRecords;
    private int   nextFreePage;
    private final int[] bucketPageNums = new int[MAX_BUCKETS];

    // ── Immutable config ─────────────────────────────────────────────────────
    private final DiskManager diskManager;
    private final ColumnDef   column;
    private final String      fileName;
    private final int         keySize;
    private final int         entriesPerPage;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) a linear hash index on the given column.
     *
     * @param diskManager the disk manager for I/O
     * @param column      the column being indexed
     * @param tableName   used to derive the index filename
     */
    public LinearHashIndex(DiskManager diskManager, ColumnDef column,
                           String tableName) throws IOException {
        this.diskManager    = diskManager;
        this.column         = column;
        this.fileName       = "idx_" + tableName + "_" + column.getName();
        this.keySize        = column.getByteSize();
        this.entriesPerPage = BucketPageLayout.capacity(keySize);

        Arrays.fill(bucketPageNums, -1);

        if (diskManager.namedPageCount(fileName) == 0) {
            createFreshIndex();
        } else {
            loadFromDisk();
        }
    }

    // -------------------------------------------------------------------------
    // Index interface
    // -------------------------------------------------------------------------

    @Override
    public void insert(Field key, RecordId rid) throws IOException {
        byte[] keyBytes = serializeKey(key);
        int    bucket   = bucketFor(hash(key));
        insertIntoBucket(bucket, keyBytes, rid);
        totalRecords++;
        writeHeader();

        if (utilization() > LOAD_FACTOR) {
            split();
        }
    }

    @Override
    public List<RecordId> search(Field key) throws IOException {
        byte[]        keyBytes = serializeKey(key);
        int           bucket   = bucketFor(hash(key));
        List<RecordId> results = new ArrayList<>();
        collectMatches(bucket, keyBytes, results, false, null);
        return results;
    }

    @Override
    public void delete(Field key, RecordId rid) throws IOException {
        byte[] keyBytes = serializeKey(key);
        int    bucket   = bucketFor(hash(key));
        boolean removed = deleteFromChain(bucket, keyBytes, rid);

        if (removed) {
            totalRecords--;
            writeHeader();
            if (M > 0 && utilization() < LOAD_FACTOR) {
                merge();
            }
        }
    }

    @Override
    public String getColumnName() {
        return column.getName();
    }

    /**
     * Returns every RecordId stored in the index, in bucket order.
     * Used by IndexScanOperator to enumerate all indexed records
     * without knowing specific key values.
     */
    public List<RecordId> getAllRids() throws IOException {
        List<RecordId> all = new ArrayList<>();
        for (int b = 0; b <= M; b++) {
            int pageNum = bucketPageNums[b];
            while (pageNum != -1) {
                Page page  = readBucketPage(pageNum);
                int  count = BucketPageLayout.getEntryCount(page);
                for (int idx = 0; idx < count; idx++) {
                    all.add(BucketPageLayout.getRecordId(page, idx, keySize));
                }
                pageNum = BucketPageLayout.getOverflowPageNum(page);
            }
        }
        return all;
    }

    // -------------------------------------------------------------------------
    // Core algorithm — split
    // -------------------------------------------------------------------------

    private void split() throws IOException {
        int newBucket  = M + 1;
        int tempBucket = newBucket - (1 << (i - 1));

        if (newBucket >= MAX_BUCKETS) return; // safety guard for demo

        // Collect every entry currently in tempBucket's chain
        List<byte[]>   keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();
        collectAll(tempBucket, keys, rids);

        // Allocate a fresh page for the new bucket
        bucketPageNums[newBucket] = allocateBlankPage();

        // Clear tempBucket's primary page and orphan its overflow pages
        clearBucketChain(tempBucket);

        // Update state BEFORE re-inserting so bucketFor() uses new i/M
        M = newBucket;
        if (M + 1 == (1 << i)) i++;

        // Re-distribute: each entry will now hash to either temp or new bucket
        for (int idx = 0; idx < keys.size(); idx++) {
            int target = bucketFor(hashBytes(keys.get(idx)));
            insertIntoBucket(target, keys.get(idx), rids.get(idx));
        }

        writeHeader();
    }

    // -------------------------------------------------------------------------
    // Core algorithm — merge
    // -------------------------------------------------------------------------

    private void merge() throws IOException {
        int lastBucket = M;
        int tempBucket = lastBucket - (1 << (i - 1));

        // Collect entries from the bucket being removed
        List<byte[]>   keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();
        collectAll(lastBucket, keys, rids);

        // Clear the last bucket's chain
        clearBucketChain(lastBucket);
        bucketPageNums[lastBucket] = -1;

        // Update state BEFORE re-inserting
        M = lastBucket - 1;
        if (i > 1 && M < (1 << (i - 1))) i--;

        // Move entries to the temp bucket
        for (int idx = 0; idx < keys.size(); idx++) {
            insertIntoBucket(tempBucket, keys.get(idx), rids.get(idx));
        }

        writeHeader();
    }

    // -------------------------------------------------------------------------
    // Bucket I/O helpers
    // -------------------------------------------------------------------------

    /**
     * Inserts keyBytes+rid into the primary page of bucket b,
     * chaining a new overflow page if the primary is full.
     */
    private void insertIntoBucket(int bucket, byte[] keyBytes,
                                  RecordId rid) throws IOException {
        int  pageNum = bucketPageNums[bucket];
        Page page    = readBucketPage(pageNum);

        // Walk the overflow chain to find a page with space
        while (!BucketPageLayout.hasSpace(page, keySize)) {
            int overflowNum = BucketPageLayout.getOverflowPageNum(page);
            if (overflowNum == -1) {
                // Chain a new overflow page
                int newPage = allocateBlankPage();
                BucketPageLayout.setOverflowPageNum(page, newPage);
                writeBucketPage(pageNum, page);
                pageNum = newPage;
                page    = readBucketPage(pageNum);
            } else {
                writeBucketPage(pageNum, page); // nothing changed on this page
                pageNum = overflowNum;
                page    = readBucketPage(pageNum);
            }
        }

        BucketPageLayout.addEntry(page, keyBytes, rid);
        writeBucketPage(pageNum, page);
    }

    /**
     * Walks bucket b's chain looking for entries matching keyBytes.
     * If deleteTarget is non-null, removes that specific RID instead.
     */
    private boolean deleteFromChain(int bucket, byte[] keyBytes,
                                    RecordId target) throws IOException {
        int  pageNum = bucketPageNums[bucket];

        while (pageNum != -1) {
            Page page  = readBucketPage(pageNum);
            int  count = BucketPageLayout.getEntryCount(page);

            for (int idx = 0; idx < count; idx++) {
                byte[]   k   = BucketPageLayout.getKeyBytes(page, idx, keySize);
                RecordId rid = BucketPageLayout.getRecordId(page, idx, keySize);

                if (Arrays.equals(k, keyBytes) && rid.equals(target)) {
                    BucketPageLayout.removeEntry(page, idx, keySize);
                    writeBucketPage(pageNum, page);
                    return true;
                }
            }

            pageNum = BucketPageLayout.getOverflowPageNum(page);
        }

        return false;
    }

    /**
     * Collects all (keyBytes, rid) pairs from a bucket's full chain.
     */
    private void collectAll(int bucket, List<byte[]> keys,
                             List<RecordId> rids) throws IOException {
        int pageNum = bucketPageNums[bucket];

        while (pageNum != -1) {
            Page page  = readBucketPage(pageNum);
            int  count = BucketPageLayout.getEntryCount(page);

            for (int idx = 0; idx < count; idx++) {
                keys.add(BucketPageLayout.getKeyBytes(page, idx, keySize));
                rids.add(BucketPageLayout.getRecordId(page, idx, keySize));
            }

            pageNum = BucketPageLayout.getOverflowPageNum(page);//you keep traversing the chain till an overflow page with 
            //overflow == -1
        }
    }

    /**
     * Collects matching RecordIds from a bucket's chain.
     * If deleteTarget is non-null, removes that entry instead.
     */
    private void collectMatches(int bucket, byte[] keyBytes,
                                 List<RecordId> results,
                                 boolean doDelete, RecordId deleteTarget) throws IOException {
        int pageNum = bucketPageNums[bucket];
        while (pageNum != -1) {
            Page page  = readBucketPage(pageNum);
            int  count = BucketPageLayout.getEntryCount(page);

            for (int idx = 0; idx < count; idx++) {
                byte[] k = BucketPageLayout.getKeyBytes(page, idx, keySize);
                if (Arrays.equals(k, keyBytes)) {
                    results.add(BucketPageLayout.getRecordId(page, idx, keySize));
                }
            }

            pageNum = BucketPageLayout.getOverflowPageNum(page);
        }
    }

    /**
     * Resets the primary page of a bucket to empty and orphans its overflow chain.
     * Overflow pages become unreachable (wasted space — acceptable for demo).
     */
    private void clearBucketChain(int bucket) throws IOException {
        int  pageNum = bucketPageNums[bucket];
        Page page    = readBucketPage(pageNum);
        BucketPageLayout.initPage(page);
        writeBucketPage(pageNum, page);
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    /**
     * Hashes a Field value to a non-negative integer.
     * Uses & 0x7FFFFFFF to clear the sign bit safely.
     */
    private int hash(Field key) {
        return switch (key.getType()) {
            case INT     -> key.getInt() & 0x7FFFFFFF;
            case BOOLEAN -> key.getBoolean() ? 1 : 0;
            case CHAR    -> key.getString().strip().hashCode() & 0x7FFFFFFF;
        };
    }

    /**
     * Re-hashes from already-serialised key bytes.
     * Used during redistribution where we have bytes, not Field objects.
     */
    private int hashBytes(byte[] keyBytes) {
        return switch (column.getType()) {
            case INT  -> ByteBuffer.wrap(keyBytes).getInt() & 0x7FFFFFFF;
            case BOOLEAN -> (keyBytes[0] != 0) ? 1 : 0;
            case CHAR -> new String(keyBytes, StandardCharsets.UTF_8)
                             .strip().hashCode() & 0x7FFFFFFF;
        };
    }

    /**
     * Applies the i-bit LSB rule and the redirect rule to return
     * the bucket number for a given hash value.
     *
     *   b = hashValue & ((1<<i) - 1)   // take i LSB bits
     *   if b > M: b = b - 2^(i-1)      // redirect to temp bucket
     */
    private int bucketFor(int hashValue) {
        int b = hashValue & ((1 << i) - 1);
        if (b > M) b -= (1 << (i - 1));
        return b;
    }

    // -------------------------------------------------------------------------
    // Key serialisation
    // -------------------------------------------------------------------------

    private byte[] serializeKey(Field key) {
        return switch (key.getType()) {
            case INT -> {
                byte[] b = new byte[4];
                ByteBuffer.wrap(b).putInt(key.getInt());
                yield b;
            }
            case BOOLEAN -> new byte[]{ key.getBoolean() ? (byte)1 : (byte)0 };
            case CHAR -> {
                byte[] raw   = key.getString().getBytes(StandardCharsets.UTF_8);
                byte[] fixed = new byte[keySize];
                System.arraycopy(raw, 0, fixed, 0, Math.min(raw.length, keySize));
                yield fixed;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Header page persistence
    // -------------------------------------------------------------------------

    private void writeHeader() throws IOException {
        Page page = new Page(new engine.storage.PageId(0, HEADER_PAGE_NUM));
        page.writeInt(H_OFFSET_I,             i);
        page.writeInt(H_OFFSET_M,             M);
        page.writeInt(H_OFFSET_TOTAL_RECORDS, totalRecords);
        page.writeInt(H_OFFSET_NEXT_FREE,     nextFreePage);
        for (int b = 0; b < MAX_BUCKETS; b++) {
            page.writeInt(H_OFFSET_BUCKET_NUMS + b * 4, bucketPageNums[b]);
        }
        diskManager.writeNamedPage(fileName, page);
    }

    private void loadFromDisk() throws IOException {
        Page page = diskManager.readNamedPage(fileName, HEADER_PAGE_NUM);
        i            = page.readInt(H_OFFSET_I);
        M            = page.readInt(H_OFFSET_M);
        totalRecords = page.readInt(H_OFFSET_TOTAL_RECORDS);
        nextFreePage = page.readInt(H_OFFSET_NEXT_FREE);
        for (int b = 0; b < MAX_BUCKETS; b++) {
            bucketPageNums[b] = page.readInt(H_OFFSET_BUCKET_NUMS + b * 4);
        }
    }

    private void createFreshIndex() throws IOException {
        diskManager.createNamedFile(fileName);

        // Initialise state
        i            = 1;
        M            = 0;
        totalRecords = 0;
        nextFreePage = 2;  // page 0 = header, page 1 = bucket 0
        Arrays.fill(bucketPageNums, -1);

        // Allocate header page (page 0)
        diskManager.allocateNamedPage(fileName);

        // Allocate bucket 0 page (page 1)
        Page bucket0 = diskManager.allocateNamedPage(fileName);
        BucketPageLayout.initPage(bucket0);
        diskManager.writeNamedPage(fileName, bucket0);
        bucketPageNums[0] = 1;

        writeHeader();
    }

    // -------------------------------------------------------------------------
    // Page allocation helpers
    // -------------------------------------------------------------------------

    /** Allocates a blank page at nextFreePage and returns its page number. */
    private int allocateBlankPage() throws IOException {
        // The file must already have pages up to nextFreePage - 1.
        // Allocate one more at the end.
        Page page = diskManager.allocateNamedPage(fileName);
        BucketPageLayout.initPage(page);
        diskManager.writeNamedPage(fileName, page);
        return nextFreePage++;
    }

    private Page readBucketPage(int pageNum) throws IOException {
        return diskManager.readNamedPage(fileName, pageNum);
    }

    private void writeBucketPage(int pageNum, Page page) throws IOException {
        // Ensure the page object has the correct page number before writing
        diskManager.writeNamedPage(fileName, page);
    }

    // -------------------------------------------------------------------------
    // Utilization
    // -------------------------------------------------------------------------

    /**
     * Utilization = totalRecords / capacity of all primary (non-overflow) pages.
     * Overflow pages are excluded from capacity since they represent spillover.
     */
    private double utilization() {
        int capacity = (M + 1) * entriesPerPage;
        return capacity == 0 ? 0.0 : (double) totalRecords / capacity;
    }
}