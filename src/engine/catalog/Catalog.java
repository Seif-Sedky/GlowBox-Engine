package engine.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Catalog {

    private final Map<Integer, TableSchema>         tablesById     = new HashMap<>();
    private final Map<String,  TableSchema>         tablesByName   = new HashMap<>();
    private final Map<String,  IndexMetadata>       indexesByName  = new HashMap<>();
    private final Map<Integer, List<IndexMetadata>> indexesByTable = new HashMap<>();

    private int nextTableId = 1;

    // -------------------------------------------------------------------------
    // Table operations
    // -------------------------------------------------------------------------

    public TableSchema createTable(String tableName, List<ColumnDef> columns) {
        String key = tableName.toLowerCase();
        if (tablesByName.containsKey(key))
            throw new IllegalArgumentException("Table already exists: " + tableName);

        int id = nextTableId++;
        TableSchema schema = new TableSchema(id, tableName, columns);

        tablesById.put(id, schema);
        tablesByName.put(key, schema);
        indexesByTable.put(id, new ArrayList<>());

        return schema;
    }

    public void dropTable(String tableName) {
        TableSchema schema = getTable(tableName);

        List<IndexMetadata> tableIndexes = indexesByTable
                .getOrDefault(schema.getTableId(), List.of());
        for (IndexMetadata idx : tableIndexes)
            indexesByName.remove(idx.getIndexName().toLowerCase());

        tablesById.remove(schema.getTableId());
        tablesByName.remove(tableName.toLowerCase());
        indexesByTable.remove(schema.getTableId());
    }

    public TableSchema getTable(String tableName) {
        TableSchema schema = tablesByName.get(tableName.toLowerCase());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + tableName);
        return schema;
    }

    public TableSchema getTable(int tableId) {
        TableSchema schema = tablesById.get(tableId);
        if (schema == null)
            throw new IllegalArgumentException("Table not found for id: " + tableId);
        return schema;
    }

    public boolean tableExists(String tableName) {
        return tablesByName.containsKey(tableName.toLowerCase());
    }

    public List<TableSchema> getAllTables() {
        return Collections.unmodifiableList(new ArrayList<>(tablesById.values()));
    }

    // -------------------------------------------------------------------------
    // Index operations
    // -------------------------------------------------------------------------

    /**
     * Creates a single-column index (B+ Tree, Extendible Hash, Bitmap).
     */
    public IndexMetadata createIndex(String indexName, String tableName,
                                     String columnName, IndexMetadata.IndexType type) {
        if (type == IndexMetadata.IndexType.RTREE)
            throw new IllegalArgumentException(
                "R-Tree is multidimensional — use createIndex() with a column list.");

        TableSchema schema = getTable(tableName);

        if (!schema.hasColumn(columnName))
            throw new IllegalArgumentException(
                "Column '" + columnName + "' does not exist in table '" + tableName + "'.");

        return registerIndex(
            new IndexMetadata(indexName, schema.getTableId(), columnName, type));
    }

    /**
     * Creates a multidimensional index (R-Tree).
     *
     * Validates that:
     *   - The type is actually multidimensional.
     *   - At least 2 columns are provided.
     *   - Every named column exists in the table.
     *   - No duplicate column names are in the list.
     */
    public IndexMetadata createIndex(String indexName, String tableName,
                                     List<String> columnNames, IndexMetadata.IndexType type) {
        if (type != IndexMetadata.IndexType.RTREE)
            throw new IllegalArgumentException(
                "Only R-Tree supports multidimensional indexing. "
                + "For " + type + " use the single-column createIndex() overload.");

        if (columnNames == null || columnNames.size() < 2)
            throw new IllegalArgumentException(
                "Multidimensional index requires at least 2 columns.");

        TableSchema schema = getTable(tableName);

        // Validate every column exists in the table
        for (String col : columnNames)
            if (!schema.hasColumn(col))
                throw new IllegalArgumentException(
                    "Column '" + col + "' does not exist in table '" + tableName + "'.");

        // Reject duplicates — indexing the same column twice makes no sense
        long distinct = columnNames.stream()
                .map(String::toLowerCase)
                .distinct()
                .count();
        if (distinct != columnNames.size())
            throw new IllegalArgumentException(
                "Duplicate column names in multidimensional index: " + columnNames);

        return registerIndex(
            new IndexMetadata(indexName, schema.getTableId(), columnNames, type));
    }

    public void dropIndex(String indexName) {
        IndexMetadata meta = getIndex(indexName);
        indexesByName.remove(indexName.toLowerCase());
        indexesByTable.get(meta.getTableId()).remove(meta);
    }

    public IndexMetadata getIndex(String indexName) {
        IndexMetadata meta = indexesByName.get(indexName.toLowerCase());
        if (meta == null)
            throw new IllegalArgumentException("Index not found: " + indexName);
        return meta;
    }

    public List<IndexMetadata> getIndexesForTable(int tableId) {
        return Collections.unmodifiableList(
            indexesByTable.getOrDefault(tableId, List.of()));
    }

    /**
     * Finds a single-column index on the given table and column.
     * Returns null if none exists.
     */
    public IndexMetadata findIndex(int tableId, String columnName) {
        return getIndexesForTable(tableId).stream()
            .filter(idx -> !idx.isMultiDimensional() &&
                    idx.getColumnName().equalsIgnoreCase(columnName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Finds a multidimensional index covering exactly the two given columns
     * in any order. Returns null if none exists.
     */
    public IndexMetadata findSpatialIndex(int tableId, String col1, String col2) {
        return getIndexesForTable(tableId).stream()
            .filter(idx -> idx.isMultiDimensional() &&
                    idx.getColumnNames().stream()
                            .anyMatch(c -> c.equalsIgnoreCase(col1)) &&
                    idx.getColumnNames().stream()
                            .anyMatch(c -> c.equalsIgnoreCase(col2)))
            .findFirst()
            .orElse(null);
    }

    public boolean indexExists(String indexName) {
        return indexesByName.containsKey(indexName.toLowerCase());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Shared registration logic used by both createIndex overloads. */
    private IndexMetadata registerIndex(IndexMetadata meta) {
        String key = meta.getIndexName().toLowerCase();
        if (indexesByName.containsKey(key))
            throw new IllegalArgumentException(
                "Index already exists: " + meta.getIndexName());

        indexesByName.put(key, meta);
        indexesByTable.get(meta.getTableId()).add(meta);
        return meta;
    }
}