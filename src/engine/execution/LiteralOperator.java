package engine.execution;

import engine.record.Record;

import java.util.List;

/**
 * Wraps an already-materialised List<Record> as an Operator.
 *
 * Used by StatementExecutor when executing set operations — both sides
 * of a UNION / INTERSECT / EXCEPT are executed independently first,
 * then their results are passed to the set operator via this wrapper
 * so the set operators can remain written against the Operator interface.
 */
public class LiteralOperator extends Operator {

    private final List<Record> rows;

    public LiteralOperator(List<Record> rows) {
        this.rows = rows;
    }

    @Override
    public List<Record> execute() {
        return rows;
    }

    @Override
    public String operatorName() {
        return "Literal(" + rows.size() + " rows)";
    }
}