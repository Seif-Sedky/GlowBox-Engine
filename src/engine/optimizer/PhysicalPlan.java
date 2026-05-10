package engine.optimizer;

import engine.execution.ExecutionStats;
import engine.execution.Operator;
import engine.record.Record;

import java.io.IOException;
import java.util.List;

/**
 * The output of the query optimizer — a ready-to-execute operator tree
 * paired with the logical plan and the trace of decisions that produced it.
 *
 * Callers execute the plan by calling execute(), which delegates to the
 * root operator. Stats are accumulated inside the operators during execution
 * and can be read back afterwards via getStats().
 */
public class PhysicalPlan {

    private final Operator       root;
    private final LogicalPlan    logicalPlan;
    private final OptimizerTrace trace;
    private final ExecutionStats stats;

    public PhysicalPlan(Operator root, LogicalPlan logicalPlan,
                        OptimizerTrace trace, ExecutionStats stats) {
        this.root        = root;
        this.logicalPlan = logicalPlan;
        this.trace       = trace;
        this.stats       = stats;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Executes the physical plan and returns all output records.
     * Can be called multiple times — each call re-executes from scratch.
     */
    public List<Record> execute() throws IOException {
        stats.reset();
        return root.execute();
    }

    // -------------------------------------------------------------------------
    // Explain output
    // -------------------------------------------------------------------------

    /**
     * Returns a formatted explain string showing the logical plan,
     * the optimizer decisions, and the physical operator tree.
     * This is what the UI's Explain view renders.
     */
    public String explain() {
        return "=== Logical Plan ===\n"  + logicalPlan.format()  + "\n"
             + "=== Physical Plan ===\n" + formatPhysical(root, 0) + "\n"
             + trace.format();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Operator       getRoot()        { return root;        }
    public LogicalPlan    getLogicalPlan() { return logicalPlan; }
    public OptimizerTrace getTrace()       { return trace;       }
    public ExecutionStats getStats()       { return stats;       }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Renders the physical operator tree as an indented string. */
    private String formatPhysical(Operator op, int indent) {
        return "  ".repeat(indent) + op.operatorName() + "\n";
        // A full recursive render would need Operator to expose children —
        // for now operatorName() at the root is enough for the explain view.
    }

    @Override
    public String toString() { return explain(); }
}