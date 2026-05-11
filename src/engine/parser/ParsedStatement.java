package engine.parser;


import engine.catalog.ColumnDef;
import engine.optimizer.Condition;
import engine.optimizer.QueryRequest;

import java.util.List;
import java.util.Map;

/**
 * Sealed hierarchy of every statement type the parser can produce.
 */
public sealed interface ParsedStatement permits
        ParsedStatement.SelectStatement,
        ParsedStatement.SetOperationStatement,
        ParsedStatement.InsertStatement,
        ParsedStatement.UpdateStatement,
        ParsedStatement.DeleteStatement,
        ParsedStatement.CreateTableStatement,
        ParsedStatement.DropTableStatement,
        ParsedStatement.CreateIndexStatement,
        ParsedStatement.DropIndexStatement {

    // -------------------------------------------------------------------------
    // SELECT
    // -------------------------------------------------------------------------

    record SelectStatement(QueryRequest queryRequest) implements ParsedStatement {}

    // -------------------------------------------------------------------------
    // SET OPERATIONS  (UNION / INTERSECT / EXCEPT)
    // -------------------------------------------------------------------------

    /**
     * Two SELECT statements connected by a set operation.
     *
     * Both sides must produce records with identical schemas —
     * the same column count and compatible types — as required by SQL.
     *
     * Syntax:
     *   SELECT * FROM t1 [WHERE ...]
     *   UNION | INTERSECT | EXCEPT
     *   SELECT * FROM t2 [WHERE ...]
     */
    record SetOperationStatement(
        SelectStatement left,
        SetOp           op,
        SelectStatement right
    ) implements ParsedStatement {

        public enum SetOp { UNION, INTERSECT, EXCEPT }
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------

    record InsertStatement(
        String       tableName,
        List<String> rawValues
    ) implements ParsedStatement {}

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    record UpdateStatement(
        String              tableName,
        Map<String, String> assignments,
        Condition           condition
    ) implements ParsedStatement {}

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    record DeleteStatement(
        String    tableName,
        Condition condition
    ) implements ParsedStatement {}

    // -------------------------------------------------------------------------
    // DDL
    // -------------------------------------------------------------------------

    record CreateTableStatement(
        String          tableName,
        List<ColumnDef> columns
    ) implements ParsedStatement {}

    record DropTableStatement(String tableName) implements ParsedStatement {}

    record CreateIndexStatement(
        String indexName,
        String tableName,
        String columnName
    ) implements ParsedStatement {}

    record DropIndexStatement(String indexName) implements ParsedStatement {}
}