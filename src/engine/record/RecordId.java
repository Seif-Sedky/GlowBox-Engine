package engine.record;

import engine.storage.PageId;

import java.util.Objects;

/**
 * A stable pointer to a specific record on disk: (pageId, slotNumber).
 *
 * RecordIds are stored in index leaf nodes to point back to the actual
 * heap record. They remain valid across other inserts and even after
 * compaction, because SlottedPageLayout never renumbers live slots.
 */
public final class RecordId {

    private final PageId pageId;
    private final int    slotNumber;

    public RecordId(PageId pageId, int slotNumber) {
        this.pageId     = pageId;
        this.slotNumber = slotNumber;
    }

    public PageId getPageId()    { return pageId;     }
    public int    getSlotNumber(){ return slotNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecordId r)) return false;
        return slotNumber == r.slotNumber && pageId.equals(r.pageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotNumber);
    }

    @Override
    public String toString() {
        return "RID(" + pageId + ", slot=" + slotNumber + ")";
    }
}