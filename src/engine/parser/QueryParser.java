//I have no understanding of how this class works in detail, only on a high level, since I have no strong background in 
//compilers

package engine.parser;

import engine.catalog.ColumnDef;
import engine.optimizer.Condition;
import engine.optimizer.QueryRequest;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.*;

/**
 * Translates a SQL string into a ParsedStatement using JSqlParser.
 *
 * Supported set operations:
 *   SELECT * FROM t1 [WHERE ...] UNION     SELECT * FROM t2 [WHERE ...]
 *   SELECT * FROM t1 [WHERE ...] INTERSECT SELECT * FROM t2 [WHERE ...]
 *   SELECT * FROM t1 [WHERE ...] EXCEPT    SELECT * FROM t2 [WHERE ...]
 *
 * see ParsedStatement for the
 * full list of supported statement types.
 */
public class QueryParser {

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static ParsedStatement parse(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql.trim());
            return translate(stmt);
        } catch (JSQLParserException e) {
            throw new ParseException("SQL parse error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Statement dispatch
    // -------------------------------------------------------------------------

    private static ParsedStatement translate(Statement stmt) {
        if (stmt instanceof Select s)      return translateSelect(s);
        if (stmt instanceof Insert i)      return translateInsert(i);
        if (stmt instanceof Update u)      return translateUpdate(u);
        if (stmt instanceof Delete d)      return translateDelete(d);
        if (stmt instanceof CreateTable c) return translateCreateTable(c);
        if (stmt instanceof CreateIndex c) return translateCreateIndex(c);
        if (stmt instanceof Drop d)        return translateDrop(d);
        throw new ParseException("Unsupported statement type: "
            + stmt.getClass().getSimpleName());
    }

    // -------------------------------------------------------------------------
    // SELECT  (plain or set operation)
    // -------------------------------------------------------------------------

    private static ParsedStatement translateSelect(Select select) {
        // JSqlParser 4.6+: SelectBody was removed. PlainSelect and SetOperationList
        // both implement Select directly — check the Select object itself.
        if (select instanceof SetOperationList sol) return translateSetOperation(sol);
        return translatePlainSelect((PlainSelect) select);
    }

    /**
     * Translates a UNION / INTERSECT / EXCEPT between exactly two SELECTs.
     * Chained set operations (A UNION B UNION C) are not supported.
     */
    private static ParsedStatement translateSetOperation(SetOperationList sol) {
        List<Select>       selects = sol.getSelects();   // JSqlParser 4.6+: List<Select> not List<SelectBody>
        List<SetOperation> ops     = sol.getOperations();

        if (selects.size() != 2 || ops.size() != 1)
            throw new ParseException(
                "Only binary set operations are supported "
                + "(exactly two SELECT statements). Got " + selects.size() + " selects.");

        ParsedStatement.SelectStatement left  = translatePlainSelect((PlainSelect) selects.get(0));
        ParsedStatement.SelectStatement right = translatePlainSelect((PlainSelect) selects.get(1));

        SetOperation rawOp = ops.get(0);
        ParsedStatement.SetOperationStatement.SetOp op;
        if      (rawOp instanceof UnionOp)     op = ParsedStatement.SetOperationStatement.SetOp.UNION;
        else if (rawOp instanceof IntersectOp) op = ParsedStatement.SetOperationStatement.SetOp.INTERSECT;
        else if (rawOp instanceof ExceptOp)    op = ParsedStatement.SetOperationStatement.SetOp.EXCEPT;
        else throw new ParseException("Unsupported set operation: " + rawOp.getClass().getSimpleName());

        return new ParsedStatement.SetOperationStatement(left, op, right);
    }

    /**
     * Translates a single PlainSelect into a SelectStatement.
     * Extracted as a helper so both translateSelect and translateSetOperation can use it.
     */
    private static ParsedStatement.SelectStatement translatePlainSelect(PlainSelect ps) {
        boolean distinct = ps.getDistinct() != null;

        // FROM clause — first (left) table
        String leftTable = ((Table) ps.getFromItem()).getName();
        String leftAlias = ps.getFromItem().getAlias() != null
            ? ps.getFromItem().getAlias().getName()
            : leftTable;

        // JOINs
        String rightTable   = null;
        String rightAlias   = null;
        String leftJoinCol  = null;
        String rightJoinCol = null;

        if (ps.getJoins() != null && !ps.getJoins().isEmpty()) {
            Join join = ps.getJoins().get(0);
            rightTable = ((Table) join.getRightItem()).getName();
            rightAlias = join.getRightItem().getAlias() != null
                ? join.getRightItem().getAlias().getName()
                : rightTable;

            Expression onExpr = join.getOnExpressions() != null
                && !join.getOnExpressions().isEmpty()
                ? join.getOnExpressions().iterator().next()
                : null;
            if (onExpr instanceof EqualsTo eq) {
                String[] cols = extractJoinColumns(eq);
                leftJoinCol  = cols[0];
                rightJoinCol = cols[1];
            }
        }

        // WHERE clause
        Condition leftFilter  = null;
        Condition rightFilter = null;

        if (ps.getWhere() != null) {
            List<Expression> parts = flattenAnd(ps.getWhere());
            for (Expression part : parts) {
                if (isJoinCondition(part)) {
                    String[] cols = extractJoinColumns((EqualsTo) part);
                    leftJoinCol  = cols[0];
                    rightJoinCol = cols[1];
                } else {
                    String    tablePrefix = extractTablePrefix(part);
                    Condition cond        = translateCondition(part);
                    if (tablePrefix == null
                        || tablePrefix.equalsIgnoreCase(leftAlias)
                        || tablePrefix.equalsIgnoreCase(leftTable)) {
                        leftFilter = cond;
                    } else {
                        rightFilter = cond;
                    }
                }
            }
        }

        // Build QueryRequest
        QueryRequest request;
        if (rightTable != null) {
            QueryRequest.Builder b = QueryRequest.join(leftTable, rightTable)
                .on(leftJoinCol, rightJoinCol);
            if (leftFilter  != null) b.whereLeft(leftFilter);
            if (rightFilter != null) b.whereRight(rightFilter);
            if (distinct)            b.withDistinct();
            request = b.build();
        } else {
            QueryRequest.Builder b = QueryRequest.singleTable(leftTable);
            if (leftFilter != null) b.where(leftFilter);
            if (distinct)           b.withDistinct();
            request = b.build();
        }

        return new ParsedStatement.SelectStatement(request);
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------

    private static ParsedStatement translateInsert(Insert insert) {
        String tableName = insert.getTable().getName();
        // JSqlParser 4.6+: insert.getValues() was removed.
        // INSERT values now live in insert.getSelect() as a PlainSelect with no FROM.
        var exprs = ((PlainSelect) insert.getSelect()).getValues().getExpressions();
        List<String> rawValues = new ArrayList<>();
        for (var e : exprs) rawValues.add(extractRawValue(e));
        return new ParsedStatement.InsertStatement(tableName, rawValues);
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    private static ParsedStatement translateUpdate(Update update) {
        String tableName = update.getTable().getName();
        Map<String, String> assignments = new LinkedHashMap<>();
        for (UpdateSet us : update.getUpdateSets()) {
            List<Column> cols = us.getColumns();
            var          vals = us.getValues();
            for (int i = 0; i < cols.size(); i++)
                assignments.put(cols.get(i).getColumnName(), extractRawValue(vals.get(i)));
        }
        Condition condition = update.getWhere() != null
            ? translateCondition(update.getWhere()) : null;
        return new ParsedStatement.UpdateStatement(tableName, assignments, condition);
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    private static ParsedStatement translateDelete(Delete delete) {
        String tableName = delete.getTable().getName();
        Condition condition = delete.getWhere() != null
            ? translateCondition(delete.getWhere()) : null;
        return new ParsedStatement.DeleteStatement(tableName, condition);
    }

    // -------------------------------------------------------------------------
    // CREATE TABLE
    // -------------------------------------------------------------------------

    private static ParsedStatement translateCreateTable(CreateTable create) {
        String tableName = create.getTable().getName();
        List<ColumnDef> columns = new ArrayList<>();
        for (ColumnDefinition colDef : create.getColumnDefinitions()) {
            String      colName  = colDef.getColumnName();
            ColDataType dataType = colDef.getColDataType();
            String      typeName = dataType.getDataType().toUpperCase();
            columns.add(switch (typeName) {
                case "INT", "INTEGER" -> new ColumnDef(colName, ColumnDef.DataType.INT);
                case "BOOLEAN"        -> new ColumnDef(colName, ColumnDef.DataType.BOOLEAN);
                case "CHAR" -> {
                    List<String> args = dataType.getArgumentsStringList();
                    int len = (args != null && !args.isEmpty())
                        ? Integer.parseInt(args.get(0).trim()) : 1;
                    yield new ColumnDef(colName, len);
                }
                default -> throw new ParseException("Unsupported type: " + typeName);
            });
        }
        return new ParsedStatement.CreateTableStatement(tableName, columns);
    }

    // -------------------------------------------------------------------------
    // CREATE INDEX
    // -------------------------------------------------------------------------

    private static ParsedStatement translateCreateIndex(CreateIndex ci) {
        return new ParsedStatement.CreateIndexStatement(
            ci.getIndex().getName(),
            ci.getTable().getName(),
            ci.getIndex().getColumnsNames().get(0));
    }

    // -------------------------------------------------------------------------
    // DROP
    // -------------------------------------------------------------------------

    private static ParsedStatement translateDrop(Drop drop) {
        String name = drop.getName().getName();
        return switch (drop.getType().toString().toUpperCase()) {
            case "TABLE" -> new ParsedStatement.DropTableStatement(name);
            case "INDEX" -> new ParsedStatement.DropIndexStatement(name);
            default -> throw new ParseException("Unsupported DROP type: " + drop.getType());
        };
    }

    // -------------------------------------------------------------------------
    // Expression → Condition
    // -------------------------------------------------------------------------

    private static Condition translateCondition(Expression expr) {
        if (expr instanceof EqualsTo eq)
            return Condition.equal(extractColumnName(eq.getLeftExpression()),
                                   extractValue(eq.getRightExpression()));
        if (expr instanceof GreaterThan gt)
            return Condition.greaterThan(extractColumnName(gt.getLeftExpression()),
                                         extractValue(gt.getRightExpression()));
        if (expr instanceof MinorThan lt)
            return Condition.lessThan(extractColumnName(lt.getLeftExpression()),
                                      extractValue(lt.getRightExpression()));
        if (expr instanceof GreaterThanEquals gte)
            return Condition.greaterThan(extractColumnName(gte.getLeftExpression()),
                                         extractValue(gte.getRightExpression()));
        if (expr instanceof MinorThanEquals lte)
            return Condition.lessThan(extractColumnName(lte.getLeftExpression()),
                                      extractValue(lte.getRightExpression()));
        if (expr instanceof Between b)
            return Condition.between(extractColumnName(b.getLeftExpression()),
                                     extractValue(b.getBetweenExpressionStart()),
                                     extractValue(b.getBetweenExpressionEnd()));
        throw new ParseException("Unsupported condition: "
            + expr.getClass().getSimpleName() + " → " + expr);
    }

    // -------------------------------------------------------------------------
    // Value extraction
    // -------------------------------------------------------------------------

    private static Object extractValue(Expression expr) {
        if (expr instanceof LongValue   lv) return (int) lv.getValue();
        if (expr instanceof StringValue sv) return sv.getValue();
        if (expr instanceof Column col) {
            String name = col.getColumnName();
            if (name.equalsIgnoreCase("TRUE"))  return Boolean.TRUE;
            if (name.equalsIgnoreCase("FALSE")) return Boolean.FALSE;
        }
        throw new ParseException("Unsupported value: "
            + expr.getClass().getSimpleName() + " → " + expr);
    }

    private static String extractRawValue(Expression expr) {
        if (expr instanceof LongValue   lv) return String.valueOf(lv.getValue());
        if (expr instanceof StringValue sv) return sv.getValue();
        if (expr instanceof Column col) {
            String name = col.getColumnName();
            if (name.equalsIgnoreCase("TRUE") || name.equalsIgnoreCase("FALSE"))
                return name.toLowerCase();
        }
        throw new ParseException("Unsupported raw value: " + expr);
    }

    // -------------------------------------------------------------------------
    // Join helpers
    // -------------------------------------------------------------------------

    private static boolean isJoinCondition(Expression expr) {
        if (!(expr instanceof EqualsTo eq)) return false;
        return eq.getLeftExpression()  instanceof Column
            && eq.getRightExpression() instanceof Column;
    }

    private static String[] extractJoinColumns(EqualsTo eq) {
        return new String[]{
            ((Column) eq.getLeftExpression()) .getColumnName(),
            ((Column) eq.getRightExpression()).getColumnName()
        };
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static String extractColumnName(Expression expr) {
        if (expr instanceof Column col) return col.getColumnName();
        throw new ParseException("Expected column, got: " + expr.getClass().getSimpleName());
    }

    private static String extractTablePrefix(Expression expr) {
        if (expr instanceof BinaryExpression be
                && be.getLeftExpression() instanceof Column col)
            return col.getTable() != null ? col.getTable().getName() : null;
        return null;
    }

    private static List<Expression> flattenAnd(Expression expr) {
        List<Expression> parts = new ArrayList<>();
        flattenAndInto(expr, parts);
        return parts;
    }

    private static void flattenAndInto(Expression expr, List<Expression> out) {
        if (expr instanceof AndExpression and) {
            flattenAndInto(and.getLeftExpression(),  out);
            flattenAndInto(and.getRightExpression(), out);
        } else {
            out.add(expr);
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static class ParseException extends RuntimeException {
        public ParseException(String msg)                  { super(msg);        }
        public ParseException(String msg, Throwable cause) { super(msg, cause); }
    }
}