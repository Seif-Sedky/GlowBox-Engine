package engine.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the complete schema for one table: its name, tableId, columns, and a
 * running count of how many pages it currently occupies on disk.
 *
 * Column byte offsets are computed automatically at construction time by
 * walking the column list in order — no manual offset tracking needed.
 *
 * pageCount is mutable so the heap file manager can increment it as new pages
 * are allocated, without needing to touch the catalog.
 */
public class TableSchema {

	private final int tableId;
	private final String tableName;
	private final List<ColumnDef> columns;
	private final int recordSize; // total bytes per record (sum of column sizes)
	private int pageCount;
	private final TableStats stats = new TableStats();

	public TableStats getStats() {
		return stats;
	}
	// -------------------------------------------------------------------------
	// Construction
	// -------------------------------------------------------------------------

	/**
	 * @param tableId   unique numeric id for this table (matches its .db filename)
	 * @param tableName human-readable name used in SQL and the UI
	 * @param columns   column definitions in declared order, WITHOUT byte offsets
	 *                  set — offsets are assigned here automatically
	 */
	public TableSchema(int tableId, String tableName, List<ColumnDef> columns) {
		this.tableId = tableId;
		this.tableName = tableName;

		// Assign byte offsets sequentially and rebuild the list
		List<ColumnDef> positioned = new ArrayList<>(columns.size());
		int offset = 0;
		for (ColumnDef col : columns) {
			ColumnDef placed = (col.getType() == ColumnDef.DataType.CHAR)
					? new ColumnDef(col.getName(), col.getCharLength(), offset)
					: new ColumnDef(col.getName(), col.getType(), offset);
			positioned.add(placed);
			offset += col.getByteSize();
		}

		this.columns = Collections.unmodifiableList(positioned);
		this.recordSize = offset;
		this.pageCount = 0;
	}

	// -------------------------------------------------------------------------
	// Column lookup
	// -------------------------------------------------------------------------

	/** Returns the column at the given zero-based position. */
	public ColumnDef getColumn(int index) {
		return columns.get(index);
	}

	/** Returns the column with the given name, or throws if not found. */
	public ColumnDef getColumn(String name) {
		return columns.stream().filter(c -> c.getName().equalsIgnoreCase(name)).findFirst().orElseThrow(
				() -> new IllegalArgumentException("Column '" + name + "' not found in table '" + tableName + "'."));
	}

	/** Returns the zero-based index of the named column, or -1 if not found. */
	public int columnIndex(String name) {
		for (int i = 0; i < columns.size(); i++) {
			if (columns.get(i).getName().equalsIgnoreCase(name))
				return i;
		}
		return -1;
	}

	public boolean hasColumn(String name) {
		return columnIndex(name) != -1;
	}

	public int getTableId() {
		return tableId;
	}

	public String getTableName() {
		return tableName;
	}

	public List<ColumnDef> getColumns() {
		return columns;
	}

	public int getColumnCount() {
		return columns.size();
	}

	public int getRecordSize() {
		return recordSize;
	}

	public int getPageCount() {
		return pageCount;
	}

	public void setPageCount(int pageCount) {
		this.pageCount = pageCount;
	}

	public void incrementPageCount() {
		this.pageCount++;
	}

	@Override
	public String toString() {
		return "TableSchema{id=" + tableId + ", name=" + tableName + ", recordSize=" + recordSize + "B, pages="
				+ pageCount + ", columns=" + columns + "}";
	}
}