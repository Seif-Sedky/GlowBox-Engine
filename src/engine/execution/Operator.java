package engine.execution;

import engine.record.Record;
import java.io.IOException;
import java.util.List;

/**
 * Abstract base class for all physical execution operators in GlowBox Engine.
 *
 * <h2>Design choice — bulk materialisation (not volcano / iterator)</h2>
 *
 * Every operator collects its full output into a {@code List<Record>} when
 * {@link #execute()} is called, rather than yielding one row at a time through
 * open / next / close callbacks (the classic volcano iterator model).
 *
 * <p>A row-at-a-time iterator is standard in production systems because it
 * avoids materialising intermediate results and pipelines work between operators.
 * It was deliberately <em>not</em> chosen here for the following reasons:
 *
 * <ul>
 *   <li>Each operator would need to maintain open cursor state between successive
 *       {@code next()} calls — a scan position, a hash-table iterator, a merge
 *       pointer — significantly complicating every implementation.</li>
 *   <li>GlowBox's primary goal is <em>visualisation and pedagogy</em>.  Bulk
 *       materialisation makes each operator a self-contained, stateless,
 *       easily inspectable unit: call {@code execute()}, get a list, inspect it.</li>
 *   <li>Operators compose trivially: one operator's {@code List<Record>} is
 *       passed directly as input to the next, with no shared iterator protocol.</li>
 *   <li>Demo datasets are small enough that intermediate lists fit comfortably
 *       in heap memory.</li>
 * </ul>
 */
public abstract class Operator {

    /**
     * Executes this operator and returns all output records.
     * May be called multiple times; each call re-executes from scratch.
     */
    public abstract List<Record> execute() throws IOException;

    /** Human-readable operator name — used in plan display and tracing. */
    public abstract String operatorName();
}
