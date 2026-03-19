package engine.buffer;

import engine.storage.DiskManager;
import engine.storage.Page;
import engine.storage.PageId;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches disk pages in a fixed array of in-memory frames (Buffer Pool).
 *
 * Every layer above (heap files, indexes, execution operators) calls
 * fetchPage() to get a page and unpinPage() when done with it. if not in BufferPool 
 * then only BufferPool asks Disk Manager for it 
 *
 * Pool size is deliberately small (default 16 frames) so that evictions
 * happen frequently and generate interesting visualisation events.
 *
 * Simplicity choices:
 *   - Fixed-size frame array, no dynamic resizing.
 *   - LRU eviction via LRUReplacer.
 *   - No concurrency — single-threaded access assumed throughout.
 */
public class BufferPoolManager {

    private final Frame[] frames;
    private final Map<PageId, Integer> pageTable;//table to track the pages in memory, pageId → frame index
    private final LRUReplacer replacer;
    private final DiskManager diskManager;

    // Stats — reset per query so Compare Mode can read them independently
    // (Assuming I rerun the query multiple times for each vis)
    
    private int pageFetches;
    private int cacheHits;
    private int cacheMisses;
    private int evictions;


    public BufferPoolManager(int poolSize, DiskManager diskManager) {
        this.frames      = new Frame[poolSize];
        this.pageTable   = new HashMap<>(poolSize); 
        this.replacer    = new LRUReplacer(poolSize);
        this.diskManager = diskManager;

        for (int i = 0; i < poolSize; i++) {
            frames[i] = new Frame();
        }
    }


    /**
     * Returns the requested page, loading it from disk if not already cached.
     *
     * The returned page is pinned — the caller MUST call unpinPage() when
     * finished with it so the frame becomes eligible for eviction.
     *
     * @param pageId the page to fetch
     * @return the in-memory Page (pinned)
     * @throws IOException if a disk read or write-back fails
     * @throws IllegalStateException if all frames are pinned and no eviction is possible
     */
    public Page fetchPage(PageId pageId) throws IOException {
        pageFetches++;

        // Cache hit (Exists in bufferpool)
        if (pageTable.containsKey(pageId)) {
            cacheHits++;
            int frameIndex = pageTable.get(pageId);
            frames[frameIndex].pin();
            replacer.remove(frameIndex);   // pinned — not evictable
            return frames[frameIndex].getPage();
        }

        // Cache miss (need page from disk), find a free frame to put the page in 
        cacheMisses++;
        int frameIndex = findFreeFrame();

        // Evict if you did not find a free frame
        if (frameIndex == -1) {
            frameIndex = evictFrame();
        }

        // Load page from disk into the chosen frame 
        Page page = diskManager.readPage(pageId);
        frames[frameIndex].setPage(page);
        frames[frameIndex].pin();
        pageTable.put(pageId, frameIndex);
        replacer.remove(frameIndex);   // pinned frames are not eviction candidates

        return page;
    }

    /**
     * Unpins a page so it becomes eligible for eviction once its pin count
     * reaches zero. Marks the page dirty if the caller modified it.
     *
     * @param pageId  the page to unpin
     * @param isDirty true if the caller wrote to the page
     */
    public void unpinPage(PageId pageId, boolean isDirty) {
        if (!pageTable.containsKey(pageId)) {
        	System.out.println("Page does not exist");
        	return;
        }

        int frameIndex = pageTable.get(pageId);
        Frame frame    = frames[frameIndex];

        if (isDirty) frame.getPage().markDirty();
        frame.unpin();

        if (!frame.isPinned()) {
            replacer.insert(frameIndex);  // now eligible for eviction
        }
    }

    /**
     * Allocates a new page on disk and immediately loads it into the pool.
     * The returned page is pinned — caller must unpinPage() when done.
     *
     * The upper layers (like the B+ Tree and Record layer) are never allowed to speak to the hard drive.
     * Thus this is a necessary method in case your page runs out of memory and you need a new page, you dont just 
     * construct a new page 
     * @param tableId the table to extend
     * @return the new Page (pinned, slotted-page header already initialised)
     */
    public Page allocatePage(int tableId) throws IOException {
        int frameIndex = findFreeFrame();
        if (frameIndex == -1) frameIndex = evictFrame();

        Page page = diskManager.allocatePage(tableId);
        frames[frameIndex].setPage(page);
        frames[frameIndex].pin();
        pageTable.put(page.getPageId(), frameIndex);

        return page;
    }

    /**
     * Forces all dirty pages to disk without evicting them.
     * Call this on shutdown or after a batch of writes.
     */
    public void flushAll() throws IOException {
        for (int i = 0; i < frames.length; i++) {
            Frame frame = frames[i];
            if (!frame.isEmpty() && frame.getPage().isDirty()) {
                diskManager.writePage(frame.getPage());
            }
        }
    }
    
    // -------------------------------------------------------------------------
    // Stats  (used by Compare Mode and the stats dashboard)
    // -------------------------------------------------------------------------

    public int getPageFetches() { return pageFetches; }
    public int getCacheHits()   { return cacheHits;   }
    public int getCacheMisses() { return cacheMisses;  }
    public int getEvictions()   { return evictions;    }

    public double hitRatio() {
        return pageFetches == 0 ? 0.0 : (double) cacheHits / pageFetches;
    }

    /** Resets all counters — call before each query in Compare Mode. */
    public void resetStats() {
        pageFetches = 0;
        cacheHits   = 0;
        cacheMisses = 0;
        evictions   = 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the index of the first empty frame, or -1 if none exist.
     */
    private int findFreeFrame() {
        for (int i = 0; i < frames.length; i++) {
            if (frames[i].isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Evicts the LRU unpinned frame, writing it back to disk if dirty.
     *
     * @return the index of the now-free frame
     * @throws IllegalStateException if every frame is currently pinned
     */
    private int evictFrame() throws IOException {
        int frameIndex = replacer.victim()
            .orElseThrow(() -> new IllegalStateException(
                "Buffer pool exhausted: all " + frames.length + " frames are pinned."));

        Frame frame = frames[frameIndex];
        Page  page  = frame.getPage();

        if (page.isDirty()) {
            diskManager.writePage(page);
        }

        pageTable.remove(page.getPageId());
        frame.clear();
        evictions++;

        return frameIndex;
    }
}