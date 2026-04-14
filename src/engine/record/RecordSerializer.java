package engine.record;

import engine.catalog.ColumnDef;
import engine.catalog.TableSchema;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts Records to raw bytes and back, driven entirely by the TableSchema.
 *
 * Layout is simply the columns concatenated in declaration order, each
 * occupying exactly its fixed byte width — no separators, no metadata.
 * This matches the offsets computed by TableSchema at construction time.
 *
 *   INT      → 4 bytes, big-endian
 *   BOOLEAN  → 1 byte  (0x00 = false, 0x01 = true)
 *   CHAR(n)  → n bytes, UTF-8, space-padded or truncated to exactly n
 *
 * The total serialised size is always TableSchema.getRecordSize() bytes.
 */
public class RecordSerializer {

    private final TableSchema schema;

    public RecordSerializer(TableSchema schema) {
        this.schema = schema;
    }

    // -------------------------------------------------------------------------
    // Serialise  (Record → byte[])
    // -------------------------------------------------------------------------

    public byte[] serialize(Record record) {
        ByteBuffer buf = ByteBuffer.allocate(schema.getRecordSize());

        for (int i = 0; i < schema.getColumnCount(); i++) {
            ColumnDef col   = schema.getColumn(i);
            Field     field = record.getField(i);

            switch (col.getType()) {
                case INT     -> buf.putInt(field.getInt());
                case BOOLEAN -> buf.put(field.getBoolean() ? (byte) 1 : (byte) 0);
                case CHAR    -> {
                    byte[] raw = field.getString()
                            .getBytes(StandardCharsets.UTF_8);
                    // Pad or truncate to exactly charLength bytes
                    byte[] fixed = new byte[col.getCharLength()];
                    System.arraycopy(raw, 0, fixed, 0,
                            Math.min(raw.length, fixed.length));
                    buf.put(fixed);
                }
            }
        }

        return buf.array();
    }

    // -------------------------------------------------------------------------
    // Deserialise  (byte[] → Record)
    // -------------------------------------------------------------------------

    public Record deserialize(byte[] bytes) {
        if (bytes.length != schema.getRecordSize())
            throw new IllegalArgumentException(
                "Expected " + schema.getRecordSize() + " bytes for table '"
                + schema.getTableName() + "', got " + bytes.length + ".");

        ByteBuffer   buf    = ByteBuffer.wrap(bytes);
        List<Field>  fields = new ArrayList<>(schema.getColumnCount());

        for (int i = 0; i < schema.getColumnCount(); i++) {
            ColumnDef col = schema.getColumn(i);
            fields.add(switch (col.getType()) {
                case INT     -> Field.ofInt(col, buf.getInt());
                case BOOLEAN -> Field.ofBoolean(col, buf.get() != 0);
                case CHAR    -> {
                    byte[] raw = new byte[col.getCharLength()];
                    buf.get(raw);
                    // Trim trailing spaces added during serialisation
                    String s = new String(raw, StandardCharsets.UTF_8).stripTrailing();
                    yield Field.ofChar(col, s);
                }
            });
        }

        return new Record(schema, fields);
    }
}
