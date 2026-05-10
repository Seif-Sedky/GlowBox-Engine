package engine.execution;

import engine.record.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Applies a boolean predicate to every record produced by a child operator,
 * passing through only those for which the predicate returns {@code true}.
 *
 * <p>FilterOperator is a pure decorator: it wraps any other {@link Operator}
 * and adds a selection condition on top.  The predicate is a standard Java
 * {@link Predicate}{@code <Record>} lambda, which lets callers express any
 * combination of column comparisons without a separate predicate class hierarchy.
 *
 * <p>Example usage:
 * <pre>{@code
 *   Predicate<Record> highEarner = r -> r.getField("salary").getInt() > 50_000;
 *   Operator plan = new FilterOperator(new SeqScanOperator(empFile), highEarner);
 *   List<Record> result = plan.execute();
 * }</pre>
 *
 * <p>When an index is available and the predicate is an equality check, prefer
 * {@link SelectIndexOperator} to avoid a full table scan.
 */
public class FilterOperator extends Operator {

    private final Operator          child;
    private final Predicate<Record> predicate;
    private final ExecutionStats    stats;

    public FilterOperator(Operator child, Predicate<Record> predicate, ExecutionStats stats) {
        this.child     = child;
        this.predicate = predicate;
        this.stats     = stats;
    }

    /** Convenience constructor — creates a private {@link ExecutionStats} instance. */
    public FilterOperator(Operator child, Predicate<Record> predicate) {
        this(child, predicate, new ExecutionStats());
    }

    @Override
    public List<Record> execute() throws IOException {
        List<Record> input  = child.execute();
        List<Record> output = new ArrayList<>();

        for (Record r : input) {
            stats.addScanned(1);
            stats.addComparisons(1);
            if (predicate.test(r)) {
                output.add(r);
            }
        }

        stats.addOutput(output.size());
        return output;
    }

    @Override
    public String operatorName() { return "Filter"; }

    public ExecutionStats getStats() { return stats; }
}
