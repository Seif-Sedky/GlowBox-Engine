package engine.execution;

import engine.catalog.ColumnDef;
import engine.catalog.TableSchema;
import engine.record.Field;
import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Block Nested Loop Join — joins two relations on a single equality condition.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Execute both child operators to materialise {@code leftList} and
 *       {@code rightList}.</li>
 *   <li>Identify the <em>smaller</em> relation and hold it entirely in memory
 *       as the <em>inner block</em>.  The larger relation becomes the
 *       <em>outer</em>.</li>
 *   <li>Divide the outer relation into blocks of {@value #BLOCK_SIZE} records
 *       (simulating one disk page per block).</li>
 *   <li>For each outer block: compare every outer record against every record
 *       in the in-memory inner block.  If the join columns are equal, emit
 *       a combined record.</li>
 * </ol>
 *
 * <h2>Why BNL over simple nested loops</h2>
 * Plain nested loops read the inner relation once per outer <em>record</em>.
 * BNL reads the inner relation once per outer <em>block</em>, reducing I/O
 * from O(|outer| × |inner pages|) to O(|outer pages| × |inner pages|).
 * Holding the entire smaller relation as the inner block is the degenerate
 * best case of BNL: the inner is read exactly once.
 *
 * <h2>In-memory simulation note</h2>
 * Because this engine uses bulk materialisation, both relations are already
 * in memory as {@code List<Record>} when {@code execute()} is called.  The
 * block loop is nevertheless simulated faithfully — outer records are processed
 * in chunks of {@value #BLOCK_SIZE} — so that stats (blocksRead, comparisons)
 * reflect realistic BNL behaviour for educational visualisation.
 *
 * <h2>Output schema</h2>
 * Each output record contains all columns from the left relation followed by
 * all columns from the right, with columns prefixed by their source table name
 * (e.g. {@code employees.id}, {@code departments.id}) to avoid name collisions.
 */
public class BNLJoinOperator extends Operator {

    /** Simulated records per block (≈ one disk page). */
    static final int BLOCK_SIZE = 32;

    private final Operator       left;
    private final Operator       right;
    private final String         leftColumn;
    private final String         rightColumn;
    private final ExecutionStats stats;

    /**
     * @param left        left child operator
     * @param right       right child operator
     * @param leftColumn  column name from the left relation used in the join condition
     * @param rightColumn column name from the right relation used in the join condition
     */
    public BNLJoinOperator(Operator left, Operator right,
                           String leftColumn, String rightColumn,
                           ExecutionStats stats) {
        this.left        = left;
        this.right       = right;
        this.leftColumn  = leftColumn;
        this.rightColumn = rightColumn;
        this.stats       = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public BNLJoinOperator(Operator left, Operator right,
                           String leftColumn, String rightColumn) {
        this(left, right, leftColumn, rightColumn, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> leftList  = left.execute();
        List<Record> rightList = right.execute();

        stats.addScanned(leftList.size() + rightList.size());

        // Smaller relation → inner block (held entirely in memory)
        // Larger relation  → outer (iterated block by block)
        List<Record> outer;
        List<Record> inner;
        String       outerCol;
        String       innerCol;

        if (leftList.size() >= rightList.size()) {
            outer    = leftList;   outerCol = leftColumn;
            inner    = rightList;  innerCol = rightColumn;
        } else {
            outer    = rightList;  outerCol = rightColumn;
            inner    = leftList;   innerCol = leftColumn;
        }

        // Pre-build the merged output schema once (reused for every output record)
        TableSchema joinSchema = buildJoinSchema(leftList, rightList);

        List<Record> output = new ArrayList<>();

        // Process outer relation in blocks of BLOCK_SIZE
        for (int blockStart = 0; blockStart < outer.size(); blockStart += BLOCK_SIZE) {
            int blockEnd = Math.min(blockStart + BLOCK_SIZE, outer.size());
            stats.addBlocksRead(1);

            // For each outer record in this block, scan the entire inner block
            for (int oi = blockStart; oi < blockEnd; oi++) {
                Record outerRec = outer.get(oi);
                Field  outerKey = outerRec.getField(outerCol);

                for (Record innerRec : inner) {
                    stats.addComparisons(1);
                    if (outerKey.compareTo(innerRec.getField(innerCol)) == 0) {
                        // Determine left/right order for the merged record
                        Record leftRec  = (outer == leftList)  ? outerRec : innerRec;
                        Record rightRec = (outer == rightList) ? outerRec : innerRec;
                        output.add(mergeRecords(leftRec, rightRec, joinSchema));
                    }
                }
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    // -------------------------------------------------------------------------
    // Record / schema merging helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a combined {@link TableSchema} whose columns are all left columns
     * (prefixed with the left table name) followed by all right columns (prefixed
     * with the right table name).  This schema is constructed once per execute()
     * call and shared across all merged output records.
     *
     * <p>If either list is empty the corresponding schema is inferred as {@code null}
     * (no prefix needed).  In practice both lists are non-empty for a valid join.
     */
    static TableSchema buildJoinSchema(List<Record> leftList, List<Record> rightList) {
        // Determine schemas — use actual records if available
        TableSchema ls = leftList.isEmpty()  ? null : leftList.get(0).getSchema();
        TableSchema rs = rightList.isEmpty() ? null : rightList.get(0).getSchema();

        List<ColumnDef> cols = new ArrayList<>();

        if (ls != null) {
            String prefix = ls.getTableName() + ".";
            for (ColumnDef c : ls.getColumns()) cols.add(renamed(c, prefix + c.getName()));
        }
        if (rs != null) {
            String prefix = rs.getTableName() + ".";
            for (ColumnDef c : rs.getColumns()) cols.add(renamed(c, prefix + c.getName()));
        }

        return new TableSchema(0, joinName(ls, rs), cols);
    }

    /** Creates a copy of a {@link ColumnDef} with a new name (offset is re-assigned by TableSchema). */
    private static ColumnDef renamed(ColumnDef original, String newName) {
        return (original.getType() == ColumnDef.DataType.CHAR)
            ? new ColumnDef(newName, original.getCharLength())
            : new ColumnDef(newName, original.getType());
    }

    private static String joinName(TableSchema ls, TableSchema rs) {
        String l = (ls != null) ? ls.getTableName() : "?";
        String r = (rs != null) ? rs.getTableName() : "?";
        return l + "_join_" + r;
    }

    /**
     * Creates a merged {@link Record} under {@code joinSchema}.
     * Each field is rebound to the corresponding {@link ColumnDef} in the merged
     * schema so that column lookups on the output record work correctly.
     */
     static Record mergeRecords(Record leftRec, Record rightRec, TableSchema joinSchema) {
        List<Field> fields = new ArrayList<>();
        int colIdx = 0;

        for (Field f : leftRec.getFields()) {
            fields.add(rebind(f, joinSchema.getColumn(colIdx++)));
        }
        for (Field f : rightRec.getFields()) {
            fields.add(rebind(f, joinSchema.getColumn(colIdx++)));
        }

        return new Record(joinSchema, fields);
    }

    /** Rebinds a {@link Field}'s value to a new {@link ColumnDef}. */
    static Field rebind(Field f, ColumnDef newCol) {
        return switch (f.getType()) {
            case INT     -> Field.ofInt(newCol, f.getInt());
            case BOOLEAN -> Field.ofBoolean(newCol, f.getBoolean());
            case CHAR    -> Field.ofChar(newCol, f.getString());
        };
    }

    @Override
    public String operatorName() {
        return "BNLJoin(" + leftColumn + " = " + rightColumn + ")";
    }

    public ExecutionStats getStats() { return stats; }
}
