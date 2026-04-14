package engine.record;

import engine.catalog.ColumnDef;

/**
 * A single typed value in a record.
 *
 * Wraps one of the three supported Java types and knows which
 * ColumnDef it came from, so callers can always check the type
 * without carrying the schema separately.
 *
 * Immutable — fields are never modified in place.
 */
public final class Field {

    private final ColumnDef column;
    private final Object    value;   // Integer | Boolean | String

    // -------------------------------------------------------------------------
    // Factory methods  (cleaner than constructor overloads)
    // -------------------------------------------------------------------------

    public static Field ofInt(ColumnDef column, int value) {
        assertType(column, ColumnDef.DataType.INT);
        return new Field(column, value);
    }

    public static Field ofBoolean(ColumnDef column, boolean value) {
        assertType(column, ColumnDef.DataType.BOOLEAN);
        return new Field(column, value);
    }

    /**
     * @param value the string value — will be truncated or space-padded to
     *              exactly CHAR(n) length to keep serialisation predictable.
     */
    public static Field ofChar(ColumnDef column, String value) {
        assertType(column, ColumnDef.DataType.CHAR);
        return new Field(column, normalise(value, column.getCharLength()));
    }

    private Field(ColumnDef column, Object value) {
        this.column = column;
        this.value  = value;
    }

    // -------------------------------------------------------------------------
    // Value accessors  (type-checked)
    // -------------------------------------------------------------------------

    public int getInt() {
        assertType(column, ColumnDef.DataType.INT);
        return (Integer) value;
    }

    public boolean getBoolean() {
        assertType(column, ColumnDef.DataType.BOOLEAN);
        return (Boolean) value;
    }

    public String getString() {
        assertType(column, ColumnDef.DataType.CHAR);
        return (String) value;
    }

    /** Returns the raw wrapped object — useful for generic comparison logic. */
    public Object getValue() { return value; }

    public ColumnDef           getColumn() { return column; }
    public ColumnDef.DataType  getType()   { return column.getType(); }

    // -------------------------------------------------------------------------
    // Comparison  (used by filter operators)
    // -------------------------------------------------------------------------

    /**
     * Compares this field's value to another field of the same type.
     * Returns negative / zero / positive like Comparable.compareTo.
     */
    public int compareTo(Field other) {
        if (this.column.getType() != other.column.getType())
            throw new IllegalArgumentException("Cannot compare fields of different types.");

        return switch (column.getType()) {
            case INT     -> Integer.compare(getInt(), other.getInt());
            case BOOLEAN -> Boolean.compare(getBoolean(), other.getBoolean());
            case CHAR    -> getString().compareTo(other.getString());
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Pads with spaces or truncates to exactly n characters. */
    private static String normalise(String s, int n) {
        if (s.length() == n) return s;
        if (s.length()  > n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }

    private static void assertType(ColumnDef col, ColumnDef.DataType expected) {
        if (col.getType() != expected)
            throw new IllegalArgumentException(
                "Column '" + col.getName() + "' is " + col.getType()
                + ", not " + expected + ".");
    }

    @Override
    public String toString() {
        return column.getName() + "=" + value;
    }
}