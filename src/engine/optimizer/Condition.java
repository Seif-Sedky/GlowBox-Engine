package engine.optimizer;

import engine.record.Field;
import engine.record.Record;

import java.util.function.Predicate;

/**
 * A WHERE predicate expressed in a form the optimizer can inspect.
 *
 * Unlike a raw Java Predicate<Record> lambda (which is opaque), a Condition
 * exposes the column name, operator, and value so the optimizer can ask:
 * "is there an index on this column?" and "is this an equality check?"
 * before deciding which physical operator to use.
 *
 * toPredicate() converts it to a Predicate<Record> for the actual operators.
 */
public class Condition {

    public enum Type { EQUAL, LESS_THAN, GREATER_THAN, BETWEEN }

    private final String columnName;
    private final Type   type;
    private final Object value;    // Integer | Boolean | String
    private final Object value2;   // only used for BETWEEN (upper bound)

    private Condition(String columnName, Type type, Object value, Object value2) {
        this.columnName = columnName;
        this.type       = type;
        this.value      = value;
        this.value2     = value2;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static Condition equal(String column, Object value) {
        return new Condition(column, Type.EQUAL, value, null);
    }

    public static Condition lessThan(String column, Object value) {
        return new Condition(column, Type.LESS_THAN, value, null);
    }

    public static Condition greaterThan(String column, Object value) {
        return new Condition(column, Type.GREATER_THAN, value, null);
    }

    public static Condition between(String column, Object low, Object high) {
        return new Condition(column, Type.BETWEEN, low, high);
    }

    // -------------------------------------------------------------------------
    // Conversion to executable predicate
    // -------------------------------------------------------------------------

    /**
     * Converts this Condition into a Predicate<Record> that operators can use.
     * Comparison is done via Field.compareTo() which handles all three types.
     */
    public Predicate<Record> toPredicate() {
        return record -> {
            Field field      = record.getField(columnName);
            Field searchField = makeField(field, value);

            return switch (type) {
                case EQUAL        -> field.compareTo(searchField) == 0;
                case LESS_THAN    -> field.compareTo(searchField) <  0;
                case GREATER_THAN -> field.compareTo(searchField) >  0;
                case BETWEEN      -> {
                    Field lo = makeField(field, value);
                    Field hi = makeField(field, value2);
                    yield field.compareTo(lo) >= 0 && field.compareTo(hi) <= 0;
                }
            };
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getColumnName() { return columnName; }
    public Type   getType()       { return type;       }
    public Object getValue()      { return value;      }
    public Object getValue2()     { return value2;     }

    /** True only if this is an equality predicate — the only type a hash index supports. */
    public boolean isEquality()   { return type == Type.EQUAL; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps a raw value in a Field using the same ColumnDef as the target field,
     * so compareTo() works correctly without type mismatches.
     */
    private static Field makeField(Field target, Object rawValue) {
        return switch (target.getType()) {
            case INT     -> Field.ofInt    (target.getColumn(), (Integer) rawValue);
            case BOOLEAN -> Field.ofBoolean(target.getColumn(), (Boolean) rawValue);
            case CHAR    -> Field.ofChar   (target.getColumn(), (String)  rawValue);
        };
    }

    /**
     * Returns the search key as a Field — used by SelectIndexOperator.
     * Only valid for EQUAL conditions.
     */
    public Field toField(Record sampleRecord) {
        return makeField(sampleRecord.getField(columnName), value);
    }

    @Override
    public String toString() {
        return switch (type) {
            case EQUAL        -> columnName + " = "       + value;
            case LESS_THAN    -> columnName + " < "       + value;
            case GREATER_THAN -> columnName + " > "       + value;
            case BETWEEN      -> columnName + " BETWEEN " + value + " AND " + value2;
        };
    }
}