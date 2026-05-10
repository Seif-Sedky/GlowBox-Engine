// Kind of embaressed to even call this a QO, but for the sake of the scope of this project, this is sufficient 
/**
On Plan Enumeration
For your project, simple if-else heuristic rules are the right choice. No plan enumeration.
Plan enumeration is what systems like PostgreSQL do — they generate all possible join orderings 
(using dynamic programming over subsets), estimate the cost of each, 
and pick the cheapest. For two tables that's manageable, 
but for N tables it's O(2^N) subsets. It requires accurate statistics, a cost model, 
and significant implementation effort.
Your optimizer only needs to make three decisions:
Decision 1 — Access method for each table:
index exists on predicate column AND predicate is equality
  → SelectIndexOperator
else
  → SelectLinearOperator
Decision 2 — Join algorithm:
both relations fit in memory (rowCount * recordSize < threshold)
  → both BNL and MergeJoin are viable, pick based on whether input is already sorted
if one relation is very large
  → BNL (avoids sort cost)
Decision 3 — Distinct method:
output needs to be sorted anyway (e.g. feeding into MergeJoin)
  → SortBasedDistinct
otherwise
  → HashBasedDistinct
The OptimizerTrace records each decision and the reasoning so the explain view can display it.
 That's your entire optimizer — a handful of if-else checks driven by TableStats, 
 with every decision logged. It's honest, it's teachable, and it's exactly what a simple rule-based optimizer is.


**/
package engine.optimizer;

import engine.catalog.Catalog;
import engine.catalog.IndexMetadata;
import engine.catalog.TableSchema;
import engine.execution.*;
import engine.index.hash.LinearHashIndex;
import engine.record.Field;
import engine.record.Record;
import engine.storage.HeapFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Builds an executable PhysicalPlan from a QueryRequest.
 *
 * Decision logic
 * ──────────────
 * 1. Access method per table
 *      Equality condition AND LinearHashIndex exists on that column
 *        → SelectIndexOperator   (O(1) bucket lookup)
 *      Anything else (range, no index, no condition)
 *        → SelectLinearOperator  (full heap scan)
 *
 * 2. Join algorithm (when two tables are involved)
 *      If JoinPreference is PREFER_BNL  → always BNL
 *      If JoinPreference is PREFER_MERGE → always MergeJoin
 *      If AUTO → CostEstimator compares both and picks the cheaper one
 *        BNL wins for small relations (avoids sort cost)
 *        MergeJoin wins when sort cost is amortised by large relation size
 *
 * 3. Distinct method (when DISTINCT is requested)
 *      Join was performed (output may benefit from being sorted) → SortBasedDistinct
 *      Single table scan → HashBasedDistinct (cheaper, no sort needed)
 *
 * Every decision is recorded in OptimizerTrace with its cost estimates.
 */
public class QueryOptimizer {

    private final Catalog                          catalog;
    private final Map<String, HeapFile>            heapFiles;
    private final Map<String, List<LinearHashIndex>> indexes;

    /**
     * @param catalog   schema and index registry
     * @param heapFiles map of tableName → HeapFile (all tables that may be queried)
     * @param indexes   map of tableName → list of LinearHashIndexes on that table
     */
    public QueryOptimizer(Catalog catalog,
                          Map<String, HeapFile> heapFiles,
                          Map<String, List<LinearHashIndex>> indexes) {
        this.catalog   = catalog;
        this.heapFiles = heapFiles;
        this.indexes   = indexes;
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Builds and returns a PhysicalPlan for the given request.
     * Does NOT execute the plan — call PhysicalPlan.execute() for that.
     */
    public PhysicalPlan optimize(QueryRequest request) throws IOException {
        OptimizerTrace trace  = new OptimizerTrace();
        ExecutionStats stats  = new ExecutionStats();
        LogicalPlan    logical = buildLogicalPlan(request);

        Operator root;

        if (request.isJoin()) {
            root = buildJoinPlan(request, trace, stats);
        } else {
            root = buildSingleTablePlan(
                request.getLeftTable(),
                request.getLeftCondition(),
                trace, stats);
        }

        // Wrap in DISTINCT if requested
        if (request.isDistinct()) {
            root = buildDistinctOperator(root, request.isJoin(), trace, stats);
        }

        return new PhysicalPlan(root, logical, trace, stats);
    }

    // -------------------------------------------------------------------------
    // Single-table plan
    // -------------------------------------------------------------------------

    /**
     * Decides between SelectIndex and SelectLinear for one table.
     * If condition is null, falls back to a plain SeqScan.
     */
    private Operator buildSingleTablePlan(String tableName, Condition condition,
                                          OptimizerTrace trace,
                                          ExecutionStats stats) throws IOException {
        TableSchema schema   = catalog.getTable(tableName);
        HeapFile    heapFile = heapFiles.get(tableName);

        // No condition → plain sequential scan
        if (condition == null) {
            trace.addUnary("Access method for " + tableName,
                "SeqScan", "No WHERE condition — full scan required");
            return new SeqScanOperator(heapFile, stats);
        }

        // Check for usable index — only equality on an indexed column qualifies
        LinearHashIndex usableIndex = condition.isEquality()
            ? findIndex(tableName, condition.getColumnName())
            : null;

        double seqCost   = CostEstimator.seqScanCost(schema);
        double indexCost = usableIndex != null
            ? CostEstimator.indexSelectCost(schema,
                CostEstimator.selectivity(schema, condition))
            : Double.MAX_VALUE;

        if (usableIndex != null && indexCost < seqCost) {
            trace.add(
                "Access method for " + tableName,
                "SelectIndex",  indexCost,
                "SelectLinear", seqCost,
                "Index on '" + condition.getColumnName() + "' available, "
                + "equality predicate, estimated cost " + String.format("%.1f", indexCost)
                + " < seq scan cost " + String.format("%.1f", seqCost));

            // Build a sample record to extract a typed Field from the condition
            List<Record> sample = heapFile.scan().stream()
                .map(lr -> lr.record()).limit(1).toList();

            if (!sample.isEmpty()) {
                Field searchKey = condition.toField(sample.get(0));
                return new SelectIndexOperator(heapFile, usableIndex, searchKey, stats);
            }
            // Fall through to linear if table is empty
        }

        trace.add(
            "Access method for " + tableName,
            "SelectLinear", seqCost,
            "SelectIndex",  indexCost == Double.MAX_VALUE ? seqCost : indexCost,
            usableIndex == null
                ? (condition.isEquality() ? "No index on '" + condition.getColumnName() + "'"
                                          : "Range predicate — hash index unsupported")
                : "Index exists but seq scan is cheaper");

        return new SelectLinearOperator(heapFile, condition.toPredicate(), stats);
    }

    // -------------------------------------------------------------------------
    // Join plan
    // -------------------------------------------------------------------------

    private Operator buildJoinPlan(QueryRequest request,
                                   OptimizerTrace trace,
                                   ExecutionStats stats) throws IOException {
        // Build left and right sub-plans independently
        Operator leftOp = buildSingleTablePlan(
            request.getLeftTable(), request.getLeftCondition(), trace, stats);
        Operator rightOp = buildSingleTablePlan(
            request.getRightTable(), request.getRightCondition(), trace, stats);

        // Estimate row counts for join cost comparison
        int leftRows  = catalog.getTable(request.getLeftTable()) .getStats().rowCount();
        int rightRows = catalog.getTable(request.getRightTable()).getStats().rowCount();

        // Guard: if stats are empty (fresh table), use a small default
        if (leftRows  == 0) leftRows  = 1;
        if (rightRows == 0) rightRows = 1;

        String leftCol  = request.getLeftJoinColumn();
        String rightCol = request.getRightJoinColumn();

        return switch (request.getJoinPreference()) {
            case PREFER_BNL   -> {
                trace.addUnary("Join algorithm", "BNL",
                    "User preference: PREFER_BNL");
                yield new BNLJoinOperator(leftOp, rightOp, leftCol, rightCol, stats);
            }
            case PREFER_MERGE -> {
                trace.addUnary("Join algorithm", "MergeJoin",
                    "User preference: PREFER_MERGE");
                yield new MergeJoinOperator(leftOp, rightOp, leftCol, rightCol, false, stats);
            }
            case AUTO         -> pickJoinAlgorithm(
                leftOp, rightOp, leftCol, rightCol,
                leftRows, rightRows, trace, stats);
        };
    }

    /**
     * Compares BNL and MergeJoin costs and picks the cheaper one.
     *
     * Rule of thumb that falls out of the cost formulas:
     *   Small relations → BNL wins (no sort overhead)
     *   Large relations → MergeJoin wins (sort amortises over many merge passes)
     */
    private Operator pickJoinAlgorithm(Operator leftOp, Operator rightOp,
                                       String leftCol, String rightCol,
                                       int leftRows, int rightRows,
                                       OptimizerTrace trace,
                                       ExecutionStats stats) {
        double bnlCost   = CostEstimator.bnlJoinCost(leftRows, rightRows);
        double mergeCost = CostEstimator.mergeJoinCost(leftRows, rightRows, false);

        if (bnlCost <= mergeCost) {
            trace.add(
                "Join algorithm",
                "BNL",       bnlCost,
                "MergeJoin", mergeCost,
                "BNL cost " + String.format("%.1f", bnlCost)
                + " ≤ MergeJoin cost " + String.format("%.1f", mergeCost)
                + " (small relations, sort overhead not worth it)");
            return new BNLJoinOperator(leftOp, rightOp, leftCol, rightCol, stats);
        }

        trace.add(
            "Join algorithm",
            "MergeJoin", mergeCost,
            "BNL",       bnlCost,
            "MergeJoin cost " + String.format("%.1f", mergeCost)
            + " < BNL cost " + String.format("%.1f", bnlCost)
            + " (sort overhead amortised over large relation)");
        return new MergeJoinOperator(leftOp, rightOp, leftCol, rightCol, false, stats);
    }

    // -------------------------------------------------------------------------
    // Distinct
    // -------------------------------------------------------------------------

    private Operator buildDistinctOperator(Operator child, boolean wasJoin,
                                           OptimizerTrace trace,
                                           ExecutionStats stats) {
        double sortCost = CostEstimator.sortDistinctCost(100); // rough estimate
        double hashCost = CostEstimator.hashDistinctCost(100);

        // After a join, output is already partially sorted by MergeJoin —
        // SortDistinct is preferred. For single-table results, hash is cheaper.
        if (wasJoin) {
            trace.add(
                "Distinct method",
                "SortBasedDistinct", sortCost,
                "HashBasedDistinct", hashCost,
                "Output of join may be partially sorted — sort-based dedup preferred");
            return new SortBasedDistinctOperator(child, stats);
        }

        trace.add(
            "Distinct method",
            "HashBasedDistinct", hashCost,
            "SortBasedDistinct", sortCost,
            "Single-table scan — hash-based distinct is O(n), no sort needed");
        return new HashBasedDistinctOperator(child, stats);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Finds a LinearHashIndex on the given table and column, or null if none.
     */
    private LinearHashIndex findIndex(String tableName, String columnName) {
        List<LinearHashIndex> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null) return null;
        return tableIndexes.stream()
            .filter(idx -> idx.getColumnName().equalsIgnoreCase(columnName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Builds the logical plan tree from a QueryRequest.
     * Used by PhysicalPlan.explain() to show the logical intent.
     */
    private LogicalPlan buildLogicalPlan(QueryRequest request) {
        LogicalPlan.Node left = request.getLeftCondition() != null
            ? LogicalPlan.filter(request.getLeftTable(),
                request.getLeftCondition(),
                LogicalPlan.scan(request.getLeftTable()))
            : LogicalPlan.scan(request.getLeftTable());

        LogicalPlan.Node root;

        if (request.isJoin()) {
            LogicalPlan.Node right = request.getRightCondition() != null
                ? LogicalPlan.filter(request.getRightTable(),
                    request.getRightCondition(),
                    LogicalPlan.scan(request.getRightTable()))
                : LogicalPlan.scan(request.getRightTable());

            root = LogicalPlan.join(
                request.getLeftJoinColumn(),
                request.getRightJoinColumn(),
                left, right);
        } else {
            root = left;
        }

        if (request.isDistinct()) {
            root = LogicalPlan.distinct(root);
        }

        return new LogicalPlan(root);
    }
}