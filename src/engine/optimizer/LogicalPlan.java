package engine.optimizer;

import java.util.Collections;
import java.util.List;

/**
 * A tree of logical operations describing WHAT a query does,
 * before the optimizer decides HOW to execute it physically.
 *
 * The optimizer builds a LogicalPlan from the QueryRequest first,
 * then maps each logical node to a physical operator.
 * The UI's Explain view shows both trees side by side so the user
 * can see the logical intent and the physical decision separately.
 *
 * Logical nodes:
 *   SCAN     — read all records from a table
 *   FILTER   — apply a condition to a record stream
 *   JOIN     — combine two record streams on a condition
 *   DISTINCT — remove duplicate records
 */
public class LogicalPlan {

    public enum NodeType { SCAN, FILTER, JOIN, DISTINCT }

    // -------------------------------------------------------------------------
    // Node
    // -------------------------------------------------------------------------

    public static class Node {

        private final NodeType   type;
        private final String     description;
        private final List<Node> children;

        private Node(NodeType type, String description, List<Node> children) {
            this.type        = type;
            this.description = description;
            this.children    = Collections.unmodifiableList(children);
        }

        public NodeType   getType()        { return type;        }
        public String     getDescription() { return description; }
        public List<Node> getChildren()    { return children;    }

        /** Renders this node and its subtree as an indented string. */
        public String format(int indent) {
            String pad = "  ".repeat(indent);
            StringBuilder sb = new StringBuilder(pad)
                .append("[").append(type).append("] ").append(description).append("\n");
            for (Node child : children) {
                sb.append(child.format(indent + 1));
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    public static Node scan(String tableName) {
        return new Node(NodeType.SCAN, "SCAN " + tableName, List.of());
    }

    public static Node filter(String tableName, Condition condition, Node child) {
        return new Node(NodeType.FILTER,
            "FILTER " + tableName + " WHERE " + condition,
            List.of(child));
    }

    public static Node join(String leftCol, String rightCol, Node left, Node right) {
        return new Node(NodeType.JOIN,
            "JOIN ON " + leftCol + " = " + rightCol,
            List.of(left, right));
    }

    public static Node distinct(Node child) {
        return new Node(NodeType.DISTINCT, "DISTINCT", List.of(child));
    }

    // -------------------------------------------------------------------------
    // Plan
    // -------------------------------------------------------------------------

    private final Node root;

    public LogicalPlan(Node root) {
        this.root = root;
    }

    public Node   getRoot()  { return root; }
    public String format()   { return root.format(0); }

    @Override
    public String toString() { return format(); }
}