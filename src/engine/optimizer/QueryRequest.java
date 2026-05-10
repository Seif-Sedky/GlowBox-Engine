package engine.optimizer;

/**
 * Describes a query in a form the optimizer can reason about.
 *
 * Supports single-table queries and two-table equi-joins.
 * The optimizer inspects the conditions and table stats to choose
 * physical operators — this class just describes the intent.
 *
 * Usage:
 *   // Single table
 *   QueryRequest.singleTable("employees")
 *               .where(Condition.equal("salary", 50000))
 *               .withDistinct()
 *               .build();
 *
 *   // Join
 *   QueryRequest.join("employees", "departments")
 *               .on("dept_id", "id")
 *               .whereLeft(Condition.greaterThan("salary", 30000))
 *               .build();
 */
public class QueryRequest {

    public enum JoinPreference { AUTO, PREFER_BNL, PREFER_MERGE }

    // ── Left (or only) table ─────────────────────────────────────────────────
    private final String    leftTable;
    private final Condition leftCondition;    // nullable

    // ── Right table (join queries only) ──────────────────────────────────────
    private final String    rightTable;       // null → single-table query
    private final Condition rightCondition;   // nullable
    private final String    leftJoinColumn;   // nullable
    private final String    rightJoinColumn;  // nullable

    // ── Output modifiers ─────────────────────────────────────────────────────
    private final boolean        distinct;
    private final JoinPreference joinPreference;

    private QueryRequest(Builder b) {
        this.leftTable       = b.leftTable;
        this.leftCondition   = b.leftCondition;
        this.rightTable      = b.rightTable;
        this.rightCondition  = b.rightCondition;
        this.leftJoinColumn  = b.leftJoinColumn;
        this.rightJoinColumn = b.rightJoinColumn;
        this.distinct        = b.distinct;
        this.joinPreference  = b.joinPreference;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder singleTable(String tableName) {
        return new Builder(tableName);
    }

    public static Builder join(String leftTable, String rightTable) {
        return new Builder(leftTable).rightTable(rightTable);
    }

    public static class Builder {
        private final String leftTable;
        private Condition    leftCondition;
        private String       rightTable;
        private Condition    rightCondition;
        private String       leftJoinColumn;
        private String       rightJoinColumn;
        private boolean      distinct       = false;
        private JoinPreference joinPreference = JoinPreference.AUTO;

        private Builder(String leftTable) { this.leftTable = leftTable; }

        public Builder rightTable(String t)              { this.rightTable      = t;   return this; }
        public Builder where(Condition c)                { this.leftCondition   = c;   return this; }
        public Builder whereLeft(Condition c)            { this.leftCondition   = c;   return this; }
        public Builder whereRight(Condition c)           { this.rightCondition  = c;   return this; }
        public Builder on(String leftCol, String rightCol){
            this.leftJoinColumn  = leftCol;
            this.rightJoinColumn = rightCol;
            return this;
        }
        public Builder withDistinct()                    { this.distinct        = true; return this; }
        public Builder preferBNL()   { this.joinPreference = JoinPreference.PREFER_BNL;   return this; }
        public Builder preferMerge() { this.joinPreference = JoinPreference.PREFER_MERGE; return this; }
        public QueryRequest build()  { return new QueryRequest(this); }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String    getLeftTable()       { return leftTable;       }
    public Condition getLeftCondition()   { return leftCondition;   }
    public String    getRightTable()      { return rightTable;      }
    public Condition getRightCondition()  { return rightCondition;  }
    public String    getLeftJoinColumn()  { return leftJoinColumn;  }
    public String    getRightJoinColumn() { return rightJoinColumn; }
    public boolean   isDistinct()         { return distinct;        }
    public boolean   isJoin()             { return rightTable != null; }
    public JoinPreference getJoinPreference() { return joinPreference; }
}