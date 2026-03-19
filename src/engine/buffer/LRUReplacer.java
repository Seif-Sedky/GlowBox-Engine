package engine.buffer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks which frame should be evicted next using LRU (Last Recently Used) order.
 *
 * Only unpinned frames are eviction candidates. Every time a frame
 * is accessed it moves to the "most recently used" end. The victim
 * is always the frame at the "least recently used" end.
 *
 * Implemented with LinkedHashMap in access-order mode — a standard
 * Java data structure that gives us LRU for free without writing
 * a custom doubly-linked list.
 * 
 * It is a standard HashMap that also has a Doubly-Linked List running through every single item inside it.
 * Think of it like a normal dictionary (fast lookups), but someone has taken a red thread and physically tied 
 * It from the first word you looked up, to the second word, to the third word.
 * Every single time you call .get() or .put() on an item, the map dynamically snips the thread,
 * pulls that item out of its current spot, and re-ties it at the very end of the line
 * 
 * What you really want for this waiting room is a Set (a list of unique frame IDs) that has that magic "Access Order" 
 * LRU red thread tied through it.
 * Java actually has a class called LinkedHashSet.
 * But there is a massive problem: Java developers never added the accessOrder = true magic switch to the LinkedHashSet constructor. 
 * It only supports standard insertion order.
 * If you want the free LRU auto-shuffling magic, Java forces you to use a LinkedHashMap. So we make the boolean value as
 * a dummy placeholder 
 * 
 */
public class LRUReplacer {

    /**
     * Tracks unpinned frames in LRU order.
     * Key = frame index, Value = ignored (true placeholder).
     * Access-order = true means get() moves the entry to the tail.
     */
    private final LinkedHashMap<Integer, Boolean> lruMap;

    public LRUReplacer(int capacity) {
        // accessOrder=true: iterating gives LRU → MRU order
        this.lruMap = new LinkedHashMap<>(capacity, 0.75f, true);
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Marks a frame as recently used and eligible for eviction.
     * Call this when a frame is unpinned.
     */
    public void insert(int frameIndex) {
        lruMap.put(frameIndex, Boolean.TRUE);
    }

    /**
     * Records that a frame was just accessed (moves it to MRU end).
     * Call this on every buffer hit.
     */
    public void recordAccess(int frameIndex) {
        lruMap.get(frameIndex); // access-order map moves this to the tail
    }

    /**
     * Removes a frame from eviction candidates entirely.
     * Call this when a frame is pinned.
     */
    public void remove(int frameIndex) {
        lruMap.remove(frameIndex);
    }

    /**
     * Returns the index of the least recently used unpinned frame,
     * and removes it from the replacer.
     * Returns empty if no frames are available for eviction.
     */
    public Optional<Integer> victim() {
        if (lruMap.isEmpty()) return Optional.empty();
        // The first entry in access-order iteration is the LRU one
        Map.Entry<Integer, Boolean> lruEntry = lruMap.entrySet().iterator().next();
        lruMap.remove(lruEntry.getKey());
        return Optional.of(lruEntry.getKey());
    }

    public int size() { return lruMap.size(); }
}