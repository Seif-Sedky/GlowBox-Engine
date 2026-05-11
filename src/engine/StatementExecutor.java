package engine;

import engine.catalog.ColumnDef;
import engine.catalog.TableSchema;
import engine.execution.*;
import engine.index.hash.LinearHashIndex;
import engine.optimizer.QueryOptimizer;
import engine.parser.ParsedStatement;
import engine.record.Field;
import engine.record.Record;
import engine.record.RecordId;
import engine.storage.HeapFile;
import engine.storage.LocatedRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Executes a ParsedStatement against the engine.
 */
public class StatementExecutor {

    private final EngineContext context;

    public StatementExecutor(EngineContext context) {
        this.context = context;
    }

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    public record ExecutionResult(
        List<Record> rows,
        String       message,
        String       explain
    ) {
        static ExecutionResult ofRows(List<Record> rows, String explain) {
            return new ExecutionResult(rows, rows.size() + " row(s) returned.", explain);
        }
        static ExecutionResult ofMessage(String message) {
            return new ExecutionResult(List.of(), message, null);
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    public ExecutionResult execute(ParsedStatement stmt,
                                   boolean explain) throws IOException {
        return switch (stmt) {
            case ParsedStatement.SelectStatement      s -> executeSelect(s, explain);
            case ParsedStatement.SetOperationStatement s -> executeSetOperation(s);
            case ParsedStatement.InsertStatement      s -> executeInsert(s);
            case ParsedStatement.UpdateStatement      s -> executeUpdate(s);
            case ParsedStatement.DeleteStatement      s -> executeDelete(s);
            case ParsedStatement.CreateTableStatement s -> executeCreateTable(s);
            case ParsedStatement.DropTableStatement   s -> executeDropTable(s);
            case ParsedStatement.CreateIndexStatement s -> executeCreateIndex(s);
            case ParsedStatement.DropIndexStatement   s -> executeDropIndex(s);
        };
    }

    // -------------------------------------------------------------------------
    // SELECT
    // -------------------------------------------------------------------------

    private ExecutionResult executeSelect(ParsedStatement.SelectStatement stmt,
                                          boolean explain) throws IOException {
        QueryOptimizer optimizer = new QueryOptimizer(
            context.getCatalog(),
            context.getAllHeapFiles(),
            context.getAllIndexes());

        var plan      = optimizer.optimize(stmt.queryRequest());
        var rows      = plan.execute();
        var explainStr = explain ? plan.explain() : null;
        return ExecutionResult.ofRows(rows, explainStr);
    }

    // -------------------------------------------------------------------------
    // SET OPERATIONS
    // -------------------------------------------------------------------------

    /**
     * Executes UNION / INTERSECT / EXCEPT by running both sides independently
     * then passing their materialised results to the appropriate set operator.
     *
     * LiteralOperator wraps an already-fetched List<Record> so the set
     * operator classes (which expect Operator children) work unchanged.
     */
    private ExecutionResult executeSetOperation(
            ParsedStatement.SetOperationStatement stmt) throws IOException {

        List<Record> leftRows  = executeSelect(stmt.left(),  false).rows();
        List<Record> rightRows = executeSelect(stmt.right(), false).rows();

        Operator leftOp  = new LiteralOperator(leftRows);
        Operator rightOp = new LiteralOperator(rightRows);

        Operator setOp = switch (stmt.op()) {
            case UNION     -> new UnionOperator(leftOp, rightOp);
            case INTERSECT -> new IntersectionOperator(leftOp, rightOp);
            case EXCEPT    -> new DifferenceOperator(leftOp, rightOp);
        };

        return ExecutionResult.ofRows(setOp.execute(), null);
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------

    private ExecutionResult executeInsert(ParsedStatement.InsertStatement stmt)
            throws IOException {
        TableSchema schema   = context.getCatalog().getTable(stmt.tableName());
        HeapFile    heapFile = context.getHeapFile(stmt.tableName());
        Record      record   = buildRecord(schema, stmt.rawValues());
        RecordId    rid      = heapFile.insert(record);

        for (LinearHashIndex index : context.getIndexes(stmt.tableName()))
            index.insert(record.getField(index.getColumnName()), rid);

        return ExecutionResult.ofMessage("1 row inserted.");
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    private ExecutionResult executeUpdate(ParsedStatement.UpdateStatement stmt)
            throws IOException {
        TableSchema           schema   = context.getCatalog().getTable(stmt.tableName());
        HeapFile              heapFile = context.getHeapFile(stmt.tableName());
        List<LinearHashIndex> idxList  = context.getIndexes(stmt.tableName());

        Predicate<Record> pred = stmt.condition() != null
            ? stmt.condition().toPredicate() : r -> true;

        int count = 0;
        for (LocatedRecord lr : heapFile.scan()) {
            if (!pred.test(lr.record())) continue;

            Record newRecord = applyAssignments(lr.record(), stmt.assignments(), schema);
            heapFile.update(lr.rid(), newRecord);

            for (LinearHashIndex idx : idxList) {
                Field oldKey = lr.record().getField(idx.getColumnName());
                Field newKey = newRecord.getField(idx.getColumnName());
                if (oldKey.compareTo(newKey) != 0) {
                    idx.delete(oldKey, lr.rid());
                    idx.insert(newKey, lr.rid());
                }
            }
            count++;
        }
        return ExecutionResult.ofMessage(count + " row(s) updated.");
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    private ExecutionResult executeDelete(ParsedStatement.DeleteStatement stmt)
            throws IOException {
        HeapFile              heapFile = context.getHeapFile(stmt.tableName());
        List<LinearHashIndex> idxList  = context.getIndexes(stmt.tableName());

        Predicate<Record> pred = stmt.condition() != null
            ? stmt.condition().toPredicate() : r -> true;

        // Try index path for equality deletes
        if (stmt.condition() != null && stmt.condition().isEquality()) {
            LinearHashIndex idx = idxList.stream()
                .filter(i -> i.getColumnName()
                              .equalsIgnoreCase(stmt.condition().getColumnName()))
                .findFirst().orElse(null);

            if (idx != null) {
                var sample = heapFile.scan().stream().limit(1).toList();
                if (!sample.isEmpty()) {
                    Field key    = stmt.condition().toField(sample.get(0).record());
                    List<LinearHashIndex> others =
                        idxList.stream().filter(i -> i != idx).toList();
                    List<Record> deleted =
                        new DeleteIndexOperator(heapFile, idx, key, others).execute();
                    return ExecutionResult.ofMessage(deleted.size() + " row(s) deleted.");
                }
            }
        }

        List<Record> deleted = new DeleteLinearOperator(heapFile, pred, idxList).execute();
        return ExecutionResult.ofMessage(deleted.size() + " row(s) deleted.");
    }

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    private ExecutionResult executeCreateTable(ParsedStatement.CreateTableStatement stmt)
            throws IOException {
        context.createTable(stmt.tableName(), stmt.columns());
        return ExecutionResult.ofMessage("Table '" + stmt.tableName() + "' created.");
    }

    private ExecutionResult executeDropTable(ParsedStatement.DropTableStatement stmt)
            throws IOException {
        context.dropTable(stmt.tableName());
        return ExecutionResult.ofMessage("Table '" + stmt.tableName() + "' dropped.");
    }

    private ExecutionResult executeCreateIndex(ParsedStatement.CreateIndexStatement stmt)
            throws IOException {
        context.createIndex(stmt.indexName(), stmt.tableName(), stmt.columnName());
        return ExecutionResult.ofMessage(
            "Index '" + stmt.indexName() + "' created on "
            + stmt.tableName() + "(" + stmt.columnName() + ").");
    }

    private ExecutionResult executeDropIndex(ParsedStatement.DropIndexStatement stmt)
            throws IOException {
        context.dropIndex(stmt.indexName());
        return ExecutionResult.ofMessage("Index '" + stmt.indexName() + "' dropped.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Record buildRecord(TableSchema schema, List<String> rawValues) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < schema.getColumnCount(); i++) {
            ColumnDef col = schema.getColumn(i);
            String    raw = rawValues.get(i);
            fields.add(switch (col.getType()) {
                case INT     -> Field.ofInt    (col, Integer.parseInt(raw.trim()));
                case BOOLEAN -> Field.ofBoolean(col, raw.trim().equalsIgnoreCase("true"));
                case CHAR    -> Field.ofChar   (col, raw);
            });
        }
        return new Record(schema, fields);
    }

    private static Record applyAssignments(Record original,
                                            java.util.Map<String, String> assignments,
                                            TableSchema schema) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < schema.getColumnCount(); i++) {
            ColumnDef col = schema.getColumn(i);
            String    raw = assignments.get(col.getName());
            fields.add(raw != null
                ? switch (col.getType()) {
                    case INT     -> Field.ofInt    (col, Integer.parseInt(raw.trim()));
                    case BOOLEAN -> Field.ofBoolean(col, raw.trim().equalsIgnoreCase("true"));
                    case CHAR    -> Field.ofChar   (col, raw);
                }
                : original.getField(i));
        }
        return new Record(schema, fields);
    }
}