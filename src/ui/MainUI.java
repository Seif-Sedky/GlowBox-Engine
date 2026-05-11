package ui;
import engine.*;
import engine.parser.ParsedStatement;
import engine.parser.QueryParser;
import engine.record.Field;
import engine.record.Record;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * Single-screen JavaFX workbench for the Visual Database Engine.
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────┐
 *   │  header                                         │
 *   ├───────────────────┬─────────────────────────────┤
 *   │  SQL editor       │  results / explain output   │
 *   ├───────────────────┴─────────────────────────────┤
 *   │  toolbar: [Execute]  [☐ Explain]  status label  │
 *   ├─────────────────────────────────────────────────┤
 *   │  stats bar                                      │
 *   └─────────────────────────────────────────────────┘
 *
 * The engine runs on a background thread so the UI stays responsive.
 * Errors from the parser or engine are shown in the output area.
 */
public class MainUI extends Application {

    // ── Engine ────────────────────────────────────────────────────────────────
    private EngineContext      context;
    private StatementExecutor  executor;

    // ── UI nodes ──────────────────────────────────────────────────────────────
    private TextArea  sqlEditor;
    private TextArea  outputArea;
    private Label     statusLabel;
    private Label     statsLabel;
    private CheckBox  explainCheck;

    // ── Colours & fonts ───────────────────────────────────────────────────────
    private static final String BG_DARK   = "#0d1117";
    private static final String BG_PANEL  = "#161b22";
    private static final String BG_INPUT  = "#1c2128";
    private static final String ACCENT    = "#58a6ff";
    private static final String GREEN     = "#3fb950";
    private static final String RED       = "#f85149";
    private static final String TEXT_MAIN = "#e6edf3";
    private static final String TEXT_DIM  = "#8b949e";

    // =========================================================================
    // Application lifecycle
    // =========================================================================

    @Override
    public void start(Stage stage) {
        try {
            context  = EngineContext.create(Path.of("engine-data"));
            executor = new StatementExecutor(context);
        } catch (Exception e) {
            showFatalError(e);
            return;
        }

        stage.setTitle("Visual Database Engine");
        stage.setScene(buildScene());
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    @Override
    public void stop() { shutdown(); }

    // =========================================================================
    // Scene construction
    // =========================================================================

    private Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_DARK + ";");

        root.setTop(buildHeader());
        root.setCenter(buildWorkArea());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1100, 700);
        // Load monospace font for editor and output
        Font.loadFont(MainUI.class.getResourceAsStream(
            "/fonts/JetBrainsMono-Regular.ttf"), 13);
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("⬡  Visual Database Engine");
        title.setStyle(
            "-fx-font-family: 'JetBrains Mono', monospace;" +
            "-fx-font-size: 15px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: " + ACCENT + ";" +
            "-fx-padding: 14 20 14 20;");

        HBox header = new HBox(title);
        header.setStyle(
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Work area (editor + output) ───────────────────────────────────────────

    private SplitPane buildWorkArea() {
        // ─── SQL Editor (left) ───────────────────────────────────────────────
        sqlEditor = new TextArea("-- Write SQL here\n");
        sqlEditor.setStyle(
            "-fx-control-inner-background: " + BG_INPUT + ";" +
            "-fx-text-fill: " + TEXT_MAIN + ";" +
            "-fx-font-family: 'JetBrains Mono', monospace;" +
            "-fx-font-size: 13px;" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 0;");
        sqlEditor.setWrapText(false);

        // Run on Ctrl+Enter
        sqlEditor.setOnKeyPressed(e -> {
            if (e.isControlDown()
                    && e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                runQuery();
            }
        });

        Label editorLabel = sectionLabel("SQL Editor  (Ctrl+Enter to run)");
        VBox  leftPane    = new VBox(0, editorLabel, sqlEditor);
        VBox.setVgrow(sqlEditor, Priority.ALWAYS);

        // ─── Toolbar ─────────────────────────────────────────────────────────
        Button  runBtn  = buildButton("▶  Execute", ACCENT);
        explainCheck    = new CheckBox("Explain");
        explainCheck.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");

        Button  clearBtn = buildButton("Clear", "#484f58");
        runBtn.setOnAction(e -> runQuery());
        clearBtn.setOnAction(e -> { sqlEditor.clear(); clearOutput(); });

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");

        HBox toolbar = new HBox(10, runBtn, explainCheck, clearBtn, statusLabel);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle(
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 1 0 0 0;");

        VBox leftWithToolbar = new VBox(0, leftPane, toolbar);
        VBox.setVgrow(leftPane, Priority.ALWAYS);

        // ─── Output (right) ──────────────────────────────────────────────────
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setStyle(
            "-fx-control-inner-background: " + BG_INPUT + ";" +
            "-fx-text-fill: " + TEXT_MAIN + ";" +
            "-fx-font-family: 'JetBrains Mono', monospace;" +
            "-fx-font-size: 12px;" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 0;");

        Label outputLabel = sectionLabel("Output");
        VBox  rightPane   = new VBox(0, outputLabel, outputArea);
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftWithToolbar, rightPane);
        split.setDividerPositions(0.45);
        split.setStyle("-fx-background-color: " + BG_DARK + ";");
        return split;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        statsLabel = new Label("No query run yet.");
        statsLabel.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");

        HBox bar = new HBox(statsLabel);
        bar.setPadding(new Insets(5, 12, 5, 12));
        bar.setStyle(
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 1 0 0 0;");
        return bar;
    }

    // =========================================================================
    // Query execution
    // =========================================================================

    private void runQuery() {
        String sql = sqlEditor.getText().trim();
        if (sql.isEmpty() || sql.startsWith("--")) return;

        // Strip trailing comments / semicolons
        sql = sql.replaceAll("--.*", "").replace(";", "").trim();
        if (sql.isEmpty()) return;

        setStatus("Running…", TEXT_DIM);
        clearOutput();

        final String finalSql    = sql;
        final boolean wantExplain = explainCheck.isSelected();

        Task<StatementExecutor.ExecutionResult> task = new Task<>() {
            @Override
            protected StatementExecutor.ExecutionResult call() throws Exception {
                ParsedStatement stmt = QueryParser.parse(finalSql);
                return executor.execute(stmt, wantExplain);
            }
        };

        task.setOnSucceeded(e -> {
            StatementExecutor.ExecutionResult result = task.getValue();
            showResult(result);
            setStatus(result.message(), GREEN);
        });

        task.setOnFailed(e -> {
            Throwable err = task.getException();
            outputArea.setText("Error: " + err.getMessage());
            setStatus("Error", RED);
        });

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // Output rendering
    // =========================================================================

    private void showResult(StatementExecutor.ExecutionResult result) {
        StringBuilder sb = new StringBuilder();

        if (!result.rows().isEmpty()) {
            sb.append(formatTable(result.rows())).append("\n");
            sb.append(result.rows().size()).append(" row(s)\n");
        } else {
            sb.append(result.message()).append("\n");
        }

        if (result.explain() != null) {
            sb.append("\n").append(result.explain());
        }

        outputArea.setText(sb.toString());

        // Update stats bar
        if (!result.rows().isEmpty()) {
            statsLabel.setText("Rows returned: " + result.rows().size());
        } else {
            statsLabel.setText(result.message());
        }
    }

    /**
     * Renders a list of records as a plain-text table.
     *
     * Example:
     *   id  | name   | salary
     *   ----+--------+-------
     *   1   | Alice  | 50000
     */
    private static String formatTable(List<Record> rows) {
        if (rows.isEmpty()) return "(empty)";

        var schema = rows.get(0).getSchema();
        int cols   = schema.getColumnCount();

        // Compute column widths
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = schema.getColumn(c).getName().length();
        }
        for (Record r : rows) {
            for (int c = 0; c < cols; c++) {
                widths[c] = Math.max(widths[c], fieldStr(r.getField(c)).length());
            }
        }

        // Header
        StringBuilder sb = new StringBuilder();
        sb.append(rowLine(schema.getColumns().stream()
            .map(col -> col.getName()).toList(), widths, cols));
        sb.append(separator(widths, cols));

        // Rows
        for (Record r : rows) {
            List<String> values = new java.util.ArrayList<>();
            for (int c = 0; c < cols; c++) values.add(fieldStr(r.getField(c)));
            sb.append(rowLine(values, widths, cols));
        }

        return sb.toString();
    }

    private static String rowLine(List<String> values, int[] widths, int cols) {
        StringJoiner sj = new StringJoiner(" | ", "", "\n");
        for (int c = 0; c < cols; c++) {
            sj.add(pad(values.get(c), widths[c]));
        }
        return sj.toString();
    }

    private static String separator(int[] widths, int cols) {
        StringJoiner sj = new StringJoiner("-+-", "", "\n");
        for (int c = 0; c < cols; c++) {
            sj.add("-".repeat(widths[c]));
        }
        return sj.toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String fieldStr(Field f) {
        return f.getValue() == null ? "NULL" : f.getValue().toString();
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle(
            "-fx-text-fill: " + TEXT_DIM + ";" +
            "-fx-font-size: 11px;" +
            "-fx-padding: 4 8 4 8;" +
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 0 0 1 0;");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private Button buildButton(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle(
            "-fx-background-color: " + color + ";" +
            "-fx-text-fill: " + (color.equals(ACCENT) ? "#0d1117" : TEXT_MAIN) + ";" +
            "-fx-font-size: 12px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 5 14 5 14;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 4;");
        btn.setOnMouseEntered(e ->
            btn.setStyle(btn.getStyle().replace(color, shiftColor(color))));
        btn.setOnMouseExited(e ->
            btn.setStyle(btn.getStyle().replace(shiftColor(color), color)));
        return btn;
    }

    /** Naive brightness shift for hover — darkens the colour slightly. */
    private static String shiftColor(String hex) {
        return switch (hex) {
            case "#58a6ff" -> "#79b8ff";
            case "#484f58" -> "#5a6270";
            default        -> hex;
        };
    }

    private void setStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle(
                "-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        });
    }

    private void clearOutput() {
        Platform.runLater(() -> outputArea.clear());
    }

    private void showFatalError(Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Engine startup failed");
        alert.setContentText(e.getMessage());
        alert.showAndWait();
        Platform.exit();
    }

    private void shutdown() {
        try { if (context != null) context.shutdown(); }
        catch (Exception ignored) {}
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        launch(args);
    }
}