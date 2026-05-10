package engine.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A log of every decision the optimizer made while building a physical plan.
 *
 * Each entry records:
 *   - what was being decided
 *   - the cost estimates for each option
 *   - which option was chosen and why
 *
 * This is what the UI's Explain view renders. Each entry maps to one
 * visible step in the explain output.
 */
public class OptimizerTrace {

    private final List<Entry> entries = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Entry
    // -------------------------------------------------------------------------

    public record Entry(
        String decision,      // what is being decided: "Access method for employees"
        String chosen,        // what was picked:       "SelectIndex"
        String reasoning,     // why:                   "Index on salary, equality predicate"
        double chosenCost,    // estimated cost of the chosen option
        double rejectedCost,  // estimated cost of the alternative
        String rejectedName   // name of the alternative
    ) {
        @Override
        public String toString() {
            return String.format(
                "[%s] → %s (cost=%.1f) over %s (cost=%.1f) — %s",
                decision, chosen, chosenCost, rejectedName, rejectedCost, reasoning);
        }
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Records a binary decision between two options.
     *
     * @param decision     label for what is being decided
     * @param chosen       name of the chosen operator/strategy
     * @param chosenCost   estimated cost of the chosen option
     * @param rejected     name of the rejected operator/strategy
     * @param rejectedCost estimated cost of the rejected option
     * @param reasoning    plain-English explanation of why chosen was picked
     */
    public void add(String decision, String chosen, double chosenCost,
                    String rejected, double rejectedCost, String reasoning) {
        entries.add(new Entry(decision, chosen, reasoning, chosenCost, rejectedCost, rejected));
    }

    /**
     * Records a decision where there was only one option (no alternative).
     */
    public void addUnary(String decision, String chosen, String reasoning) {
        entries.add(new Entry(decision, chosen, reasoning, 0, 0, "N/A"));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** Formats the full trace as a multi-line string for the explain view. */
    public String format() {
        if (entries.isEmpty()) return "(no optimizer decisions recorded)";
        StringBuilder sb = new StringBuilder("=== Query Optimizer Trace ===\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(i + 1).append(". ").append(entries.get(i)).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() { return format(); }
}