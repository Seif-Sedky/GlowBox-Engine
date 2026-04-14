package engine.storage;

import engine.record.Record;
import engine.record.RecordId;

/**
 * A Record paired with its location on disk.
 *
 * HeapFile.scan() and HeapFile.get() return these so callers that need
 * to pass RecordIds to index structures have them available alongside
 * the actual record data.
 *
 * Using a Java record (the language feature) here is intentional —
 * it is literally just a named pair with no behaviour.
 */
public record LocatedRecord(RecordId rid, Record record) {}