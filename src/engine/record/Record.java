package engine.record;

import engine.catalog.TableSchema;

import java.util.Collections;
import java.util.List;

/**
 * One row in a table — an ordered list of Fields.
 *
 * Records are immutable once constructed. The schema is carried along
 * so operators can inspect column metadata without a separate lookup.
 */
public final class Record {

    private final TableSchema schema;
    private final List<Field> fields;

    public Record(TableSchema schema, List<Field> fields) {
        if (fields.size() != schema.getColumnCount())
            throw new IllegalArgumentException(
                "Field count (" + fields.size() + ") does not match schema column count ("
                + schema.getColumnCount() + ") for table '" + schema.getTableName() + "'.");
        this.schema = schema;
        this.fields = Collections.unmodifiableList(fields);
    }

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /** Returns the field at the given zero-based column index. */
    public Field getField(int index) {
        return fields.get(index);
    }

    /** Returns the field for the named column. */
    public Field getField(String columnName) {
        int idx = schema.columnIndex(columnName);
        if (idx == -1)
            throw new IllegalArgumentException(
                "Column '" + columnName + "' not found in record schema.");
        return fields.get(idx);
    }

    public List<Field>  getFields() { return fields;  }
    public TableSchema  getSchema() { return schema;  }

    @Override
    public String toString() {
        return "Record" + fields.toString();
    }
}