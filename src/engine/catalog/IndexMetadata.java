package engine.catalog;

import java.util.List;

/**
 * Describes one index registered against a table.
 *
 * Holds everything the optimizer and execution engine need to decide whether to
 * use this index and how to open it: which table, which column, what kind of
 * structure, and where its root lives on disk.
 */
public class IndexMetadata {

	public enum IndexType {
		BPLUS_TREE, EXTENDIBLE_HASH, RTREE, BITMAP, LINEAR_HASH
	}

	private final String indexName;
	private final int tableId;
	private final List<String> columnNames; // one entry for 1D indexes, two for R-Tree
	private final IndexType indexType;

	/**
	 * The page number of the root/directory page of this index. Set to -1 when the
	 * index is freshly created and has no pages yet. Updated by the index
	 * implementation as the structure grows.
	 */
	private int rootPageNumber;

	// -------------------------------------------------------------------------
	// Construction
	// -------------------------------------------------------------------------

	public IndexMetadata(String indexName, int tableId, String columnName, IndexType indexType) {
		this.indexName = indexName;
		this.tableId = tableId;
		this.columnNames = List.of(columnName);
		this.indexType = indexType;
		this.rootPageNumber = -1;
	}

	// Multi-column constructor
	public IndexMetadata(String indexName, int tableId, List<String> columnNames, IndexType indexType) {
		if (columnNames.size() < 2)
			throw new IllegalArgumentException("Multi-column index requires at least 2 columns.");
		this.columnNames = List.copyOf(columnNames);
		this.indexName = indexName;
		this.tableId = tableId;
		this.indexType = indexType;
		this.rootPageNumber = -1;

	}

	// -------------------------------------------------------------------------
	// Accessors
	// -------------------------------------------------------------------------

	
	public String getIndexName() {
		return indexName;
	}

	public int getTableId() {
		return tableId;
	}

	public String getColumnName() {
	    if (columnNames.size() != 1)
	        throw new IllegalStateException(
	            "Index '" + indexName + "' is multi-column. Use getColumnNames() instead.");
	    return columnNames.get(0);
	}

	public List<String> getColumnNames() { return columnNames; }
	public boolean isMultiDimensional()  { return columnNames.size() > 1; }

	public IndexType getIndexType() {
		return indexType;
	}

	public int getRootPageNumber() {
		return rootPageNumber;
	}

	public void setRootPageNumber(int rootPageNumber) {
		this.rootPageNumber = rootPageNumber;
	}

	@Override
	public String toString() {
		return "IndexMetadata{name=" + indexName + ", table=" + tableId + ", column=" + getColumnName() + ", type="
				+ indexType + ", root=" + rootPageNumber + "}";
	}
}