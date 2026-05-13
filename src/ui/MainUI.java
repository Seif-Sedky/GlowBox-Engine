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
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * Single-screen JavaFX workbench for the Visual Database Engine.
 *
 * Layout:
 * ┌─────────────────────────────────────────────────┐
 * │  header                                         │
 * ├───────────────────┬─────────────────────────────┤
 * │  SQL editor       │  results / explain output   │
 * ├───────────────────┴─────────────────────────────┤
 * │  toolbar: [Execute]  [☐ Explain]  status label  │
 * ├─────────────────────────────────────────────────┤
 * │  stats bar                                      │
 * └─────────────────────────────────────────────────┘
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

        stage.setTitle("GlowBox Engine");
        
        // Load custom window icon
        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/ui/icon.png")));
        } catch (Exception e) {
            System.err.println("Note: /icon.png not found in resources.");
        }

        stage.setScene(buildScene());
        stage.setMinWidth(950);
        stage.setMinHeight(650);
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
            "/fonts/JetBrainsMono-Regular.ttf"), 14);
        return scene;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label title = new Label("❖ GlowBox Engine");
        title.setStyle(
            "-fx-font-family: 'JetBrains Mono', 'Segoe UI', sans-serif;" +
            "-fx-font-size: 18px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: " + TEXT_MAIN + ";" +
            "-fx-padding: 16 24 16 24;"
        );

        // Add the namesake glow effect
        DropShadow glow = new DropShadow();
        glow.setColor(Color.web(ACCENT));
        glow.setRadius(12);
        glow.setSpread(0.15);
        title.setEffect(glow);

        HBox header = new HBox(title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle(
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Work area (editor + output) ───────────────────────────────────────────

    private SplitPane buildWorkArea() {
        String commonTextAreaStyle = 
            "-fx-control-inner-background: " + BG_INPUT + ";" +
            "-fx-background-color: transparent, " + BG_INPUT + ";" +
            "-fx-text-fill: " + TEXT_MAIN + ";" +
            "-fx-font-family: 'JetBrains Mono', monospace;" +
            "-fx-font-size: 14px;" +
            "-fx-border-color: #30363d;" +
            "-fx-border-radius: 6;" +
            "-fx-background-radius: 6;" +
            "-fx-focus-color: transparent;" +
            "-fx-faint-focus-color: transparent;";

        // ─── SQL Editor (left) ───────────────────────────────────────────────
        sqlEditor = new TextArea("-- Write SQL here--");
        sqlEditor.setStyle(commonTextAreaStyle);
        sqlEditor.setWrapText(false);

        // Run on Ctrl+Enter
        sqlEditor.setOnKeyPressed(e -> {
            if (e.isControlDown()
                    && e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                runQuery();
            }
        });

        Label editorLabel = sectionLabel("SQL Editor", "Press Ctrl+Enter to run");
        VBox  leftPane    = new VBox(8, editorLabel, sqlEditor);
        leftPane.setPadding(new Insets(12, 6, 12, 12));
        VBox.setVgrow(sqlEditor, Priority.ALWAYS);

        // ─── Toolbar ─────────────────────────────────────────────────────────
        Button  runBtn  = buildButton("▶ Execute", ACCENT, true);
        explainCheck    = new CheckBox("Explain");
        explainCheck.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13px; -fx-cursor: hand;");

        Button  clearBtn = buildButton("Clear", "#30363d", false);
        runBtn.setOnAction(e -> runQuery());
        clearBtn.setOnAction(e -> { sqlEditor.clear(); clearOutput(); });

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 13px;");

        HBox toolbar = new HBox(15, runBtn, explainCheck, clearBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbarContainer = new HBox(10, toolbar, spacer, statusLabel);
        toolbarContainer.setAlignment(Pos.CENTER);
        toolbarContainer.setPadding(new Insets(12, 16, 12, 16));
        toolbarContainer.setStyle(
            "-fx-background-color: " + BG_PANEL + ";" +
            "-fx-border-color: #30363d;" +
            "-fx-border-width: 1 0 0 0;" +
            "-fx-background-radius: 0 0 8 8;"
        );

        VBox leftWithToolbar = new VBox(0, leftPane, toolbarContainer);
        VBox.setVgrow(leftPane, Priority.ALWAYS);

        // ─── Output (right) ──────────────────────────────────────────────────
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setStyle(commonTextAreaStyle);

        Label outputLabel = sectionLabel("Console Output", "Results & Execution Plans");
        VBox  rightPane   = new VBox(8, outputLabel, outputArea);
        rightPane.setPadding(new Insets(12, 12, 12, 6));
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftWithToolbar, rightPane);
        split.setDividerPositions(0.45);
        split.setStyle("-fx-background-color: " + BG_DARK + "; -fx-box-border: transparent;");
        return split;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        statsLabel = new Label("No query run yet.");
        statsLabel.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");

        HBox bar = new HBox(statsLabel);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(6, 16, 6, 16));
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

        // Capture the start time
        final long startTime = System.currentTimeMillis();

        Task<StatementExecutor.ExecutionResult> task = new Task<>() {
            @Override
            protected StatementExecutor.ExecutionResult call() throws Exception {
                ParsedStatement stmt = QueryParser.parse(finalSql);
                return executor.execute(stmt, wantExplain);
            }
        };

        task.setOnSucceeded(e -> {
            // Calculate total duration in milliseconds
            long durationMs = System.currentTimeMillis() - startTime;
            
            StatementExecutor.ExecutionResult result = task.getValue();
            showResult(result, durationMs); // Pass the duration to your render method
            setStatus("Success", GREEN);
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
    
 // Notice the new durationMs parameter
    private void showResult(StatementExecutor.ExecutionResult result, long durationMs) {
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

        // Update stats bar to include the execution time
        if (!result.rows().isEmpty()) {
            statsLabel.setText("Rows returned: " + result.rows().size() + "  │  Execution time: " + durationMs + " ms");
        } else {
            statsLabel.setText(result.message() + "  │  Execution time: " + durationMs + " ms");
        }
    }

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

    private static String formatTable(List<Record> rows) {
        if (rows.isEmpty()) return "(empty)";

        var schema = rows.get(0).getSchema();
        int cols   = schema.getColumnCount();

        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = schema.getColumn(c).getName().length();
        }
        for (Record r : rows) {
            for (int c = 0; c < cols; c++) {
                widths[c] = Math.max(widths[c], fieldStr(r.getField(c)).length());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(rowLine(schema.getColumns().stream()
            .map(col -> col.getName()).toList(), widths, cols));
        sb.append(separator(widths, cols));

        for (Record r : rows) {
            List<String> values = new java.util.ArrayList<>();
            for (int c = 0; c < cols; c++) values.add(fieldStr(r.getField(c)));
            sb.append(rowLine(values, widths, cols));
        }

        return sb.toString();
    }

    private static String rowLine(List<String> values, int[] widths, int cols) {
        StringJoiner sj = new StringJoiner(" │ ", " ", "\n");
        for (int c = 0; c < cols; c++) {
            sj.add(pad(values.get(c), widths[c]));
        }
        return sj.toString();
    }

    private static String separator(int[] widths, int cols) {
        StringJoiner sj = new StringJoiner("─┼─", "─", "\n");
        for (int c = 0; c < cols; c++) {
            sj.add("─".repeat(widths[c]));
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

    private Label sectionLabel(String primary, String secondary) {
        Label l = new Label(primary + "  ");
        l.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        
        Label sub = new Label(secondary);
        sub.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
        
        HBox box = new HBox(l, sub);
        box.setAlignment(Pos.BOTTOM_LEFT);
        
        // We wrap the HBox in a graphic-only label to maintain your original layout structure seamlessly
        Label wrapper = new Label();
        wrapper.setGraphic(box);
        return wrapper;
    }

    private Button buildButton(String text, String bgColor, boolean isPrimary) {
        Button btn = new Button(text);
        String textColor = isPrimary ? BG_DARK : TEXT_MAIN;
        
        btn.setStyle(
            "-fx-background-color: " + bgColor + ";" +
            "-fx-text-fill: " + textColor + ";" +
            "-fx-font-size: 13px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 6 16 6 16;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 6;");
            
        btn.setOnMouseEntered(e ->
            btn.setStyle(btn.getStyle().replace(bgColor, shiftColor(bgColor))));
        btn.setOnMouseExited(e ->
            btn.setStyle(btn.getStyle().replace(shiftColor(bgColor), bgColor)));
        return btn;
    }

    private static String shiftColor(String hex) {
        return switch (hex) {
            case "#58a6ff" -> "#79b8ff";
            case "#30363d" -> "#484f58";
            default        -> hex;
        };
    }

    private void setStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle(
                "-fx-text-fill: " + color + "; -fx-font-size: 13px; -fx-font-weight: bold;");
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