package engine.catalog;

import engine.catalog.ColumnDef.DataType;

/**
 * Describes a single column in a table schema.
 *
 * Because all types are fixed-length, every column has a known byte size
 * and a fixed offset within the serialised record. These are computed once
 * by TableSchema at construction time and never change.
 */
public class ColumnDef {

    public enum DataType { INT, BOOLEAN, CHAR }

    private final String   name;
    private final DataType type;
    private final int      charLength;   // only meaningful when type == CHAR
    private final int      byteOffset;   // offset of this column within a record
    private final int      byteSize;     // number of bytes this column occupies

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** For INT and BOOLEAN columns. */
 // For INT and BOOLEAN — no offset, TableSchema will assign it
    public ColumnDef(String name, DataType type) {
        if (type == DataType.CHAR)
            throw new IllegalArgumentException("Use the CHAR constructor for CHAR columns.");
        this.name       = name;
        this.type       = type;
        this.charLength = 0;
        this.byteOffset = 0;   // placeholder, TableSchema overwrites this
        this.byteSize   = sizeOf(type, 0);
    }

    // For CHAR(n) — no offset
    public ColumnDef(String name, int charLength) {
        if (charLength <= 0)
            throw new IllegalArgumentException("CHAR length must be > 0.");
        this.name       = name;
        this.type       = DataType.CHAR;
        this.charLength = charLength;
        this.byteOffset = 0;   // placeholder
        this.byteSize   = charLength;
    }
    
      ColumnDef(String name, DataType type, int byteOffset) {
        if (type == DataType.CHAR)
            throw new IllegalArgumentException("Use the CHAR constructor for CHAR columns.");
        this.name       = name;
        this.type       = type;
        this.charLength = 0;
        this.byteOffset = byteOffset;
        this.byteSize   = sizeOf(type, 0);
    }

    /** For CHAR(n) columns. */
      ColumnDef(String name, int charLength, int byteOffset) {
        if (charLength <= 0)
            throw new IllegalArgumentException("CHAR length must be > 0.");
        this.name       = name;
        this.type       = DataType.CHAR;
        this.charLength = charLength;
        this.byteOffset = byteOffset;
        this.byteSize   = charLength;
    }


    /** Returns the byte size of a given type. */
    public static int sizeOf(DataType type, int charLength) {
        return switch (type) {
            case INT     -> 4;
            case BOOLEAN -> 1;
            case CHAR    -> charLength;
        };
    }

    public String   getName()       { return name;       }
    public DataType getType()       { return type;       }
    public int      getCharLength() { return charLength; }
    public int      getByteOffset() { return byteOffset; }
    public int      getByteSize()   { return byteSize;   }

    @Override
    public String toString() {
        String typeStr = (type == DataType.CHAR) ? "CHAR(" + charLength + ")" : type.name();
        return name + " " + typeStr + " @offset=" + byteOffset;
    }
}