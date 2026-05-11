package engine;

import engine.buffer.BufferPoolManager;
import engine.catalog.Catalog;
import engine.catalog.ColumnDef;
import engine.catalog.IndexMetadata;
import engine.catalog.TableSchema;
import engine.index.hash.LinearHashIndex;
import engine.storage.DiskManager;
import engine.storage.HeapFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Holds every live engine component and wires them together.
 *
 * Acts as the single source of truth for running instances of
 * HeapFile and LinearHashIndex. The StatementExecutor and
 * QueryOptimizer both take their inputs from here.
 *
 * Lifecycle:
 *   EngineContext ctx = EngineContext.create(Path.of("data/"));
 *   ctx.shutdown(); // on application exit
 */
public class EngineContext {

    private final DiskManager                          diskManager;
    private final BufferPoolManager                    bufferPool;
    private final Catalog                              catalog;
    private final Map<String, HeapFile>                heapFiles  = new LinkedHashMap<>();
    private final Map<String, List<LinearHashIndex>>   indexes    = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private EngineContext(DiskManager diskManager, BufferPoolManager bufferPool,
                          Catalog catalog) {
        this.diskManager = diskManager;
        this.bufferPool  = bufferPool;
        this.catalog     = catalog;
    }

    /**
     * Creates a fresh engine context rooted at the given directory.
     * The directory is created if it does not exist.
     */
    public static EngineContext create(Path dataDirectory) throws IOException {
        DiskManager     dm = new DiskManager(dataDirectory);
        BufferPoolManager bp = new BufferPoolManager(16, dm);
        Catalog         cat = new Catalog();
        return new EngineContext(dm, bp, cat);
    }

    // -------------------------------------------------------------------------
    // Table management
    // -------------------------------------------------------------------------

    /**
     * Creates a new table: registers it in the catalog, creates its .db file,
     * and registers a live HeapFile for it.
     */
    public TableSchema createTable(String tableName,
                                   List<ColumnDef> columns) throws IOException {
        TableSchema schema = catalog.createTable(tableName, columns);
        diskManager.createTableFile(schema.getTableId());
        heapFiles.put(tableName.toLowerCase(), new HeapFile(schema, bufferPool));
        indexes.put(tableName.toLowerCase(), new ArrayList<>());
        return schema;
    }

    /**
     * Drops a table: removes it from the catalog, deletes its .db file,
     * and drops all associated indexes.
     */
    public void dropTable(String tableName) throws IOException {
        TableSchema schema = catalog.getTable(tableName);

        // Drop all indexes first
        for (IndexMetadata meta : catalog.getIndexesForTable(schema.getTableId())) {
            diskManager.deleteNamedFile(indexFileName(schema, meta.getColumnName()));
        }

        catalog.dropTable(tableName);
        diskManager.deleteTableFile(schema.getTableId());
        heapFiles.remove(tableName.toLowerCase());
        indexes.remove(tableName.toLowerCase());
    }

    // -------------------------------------------------------------------------
    // Index management
    // -------------------------------------------------------------------------

    /**
     * Creates a linear hash index on the given column.
     * Scans all existing records and inserts them into the index.
     */
    public LinearHashIndex createIndex(String indexName, String tableName,
                                       String columnName) throws IOException {
        catalog.createIndex(indexName, tableName, columnName,
                            IndexMetadata.IndexType.LINEAR_HASH);

        TableSchema     schema   = catalog.getTable(tableName);
        ColumnDef       col      = schema.getColumn(columnName);
        LinearHashIndex index    = new LinearHashIndex(diskManager, col, tableName);
        HeapFile        heapFile = getHeapFile(tableName);

        // Populate index from existing records
        for (var lr : heapFile.scan()) {
            index.insert(lr.record().getField(columnName), lr.rid());
        }

        indexes.computeIfAbsent(tableName.toLowerCase(), k -> new ArrayList<>()).add(index);
        return index;
    }

    /**
     * Drops an index: removes it from the catalog, deletes its .db file,
     * and removes it from the live index map.
     */
    public void dropIndex(String indexName) throws IOException {
        IndexMetadata meta   = catalog.getIndex(indexName);
        TableSchema   schema = catalog.getTable(meta.getTableId());

        diskManager.deleteNamedFile(indexFileName(schema, meta.getColumnName()));
        catalog.dropIndex(indexName);

        List<LinearHashIndex> tableIndexes =
            indexes.get(schema.getTableName().toLowerCase());
        if (tableIndexes != null) {
            tableIndexes.removeIf(idx ->
                idx.getColumnName().equalsIgnoreCase(meta.getColumnName()));
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public DiskManager      getDiskManager()  { return diskManager; }
    public BufferPoolManager getBufferPool()  { return bufferPool;  }
    public Catalog           getCatalog()     { return catalog;     }

    public HeapFile getHeapFile(String tableName) {
        HeapFile hf = heapFiles.get(tableName.toLowerCase());
        if (hf == null)
            throw new IllegalArgumentException("No live HeapFile for table: " + tableName);
        return hf;
    }

    public List<LinearHashIndex> getIndexes(String tableName) {
        return indexes.getOrDefault(tableName.toLowerCase(), List.of());
    }

    /** Returns a snapshot of all HeapFiles — used by QueryOptimizer. */
    public Map<String, HeapFile> getAllHeapFiles() {
        return Collections.unmodifiableMap(heapFiles);
    }

    /** Returns a snapshot of all index lists — used by QueryOptimizer. */
    public Map<String, List<LinearHashIndex>> getAllIndexes() {
        return Collections.unmodifiableMap(indexes);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Flushes all dirty pages and closes all file handles. */
    public void shutdown() throws IOException {
        bufferPool.flushAll();
        diskManager.shutdown();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String indexFileName(TableSchema schema, String columnName) {
        return "idx_" + schema.getTableName() + "_" + columnName;
    }
}
