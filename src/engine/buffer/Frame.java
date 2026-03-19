package engine.buffer;

import engine.storage.Page;

/**
 * A single slot in the buffer pool's frame array.
 *
 * A Frame wraps a Page that is currently loaded in memory and tracks
 * whether anything is actively using it (pinCount) so the replacer
 * knows it cannot be evicted while in use.
 */
public class Frame {

    private Page page;
    private int  pinCount;

    public Frame() {
        this.page     = null;
        this.pinCount = 0;
    }
    public Page getPage()     { return page; }
    public int  getPinCount() { return pinCount; }
    public boolean isEmpty()  { return page == null; }

    public void setPage(Page page) {
        this.page = page;
        this.pinCount = 0;
    }

    public void clear() {
        this.page     = null;
        this.pinCount = 0;
    }

    public void pin()   { pinCount++; }

    public void unpin() {
        if (pinCount > 0) pinCount--;
    }

    public boolean isPinned() { return pinCount > 0; }
}