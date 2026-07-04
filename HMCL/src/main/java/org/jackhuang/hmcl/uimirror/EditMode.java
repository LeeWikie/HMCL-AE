/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.uimirror;

import com.google.gson.Gson;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.ColorScheme;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.decorator.Decorator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * ui-mirror EDIT MODE (v2). Toggle with Ctrl+Shift+E.
 *
 * <p>Edit mode freezes the live UI as a canvas and accumulates a QUEUE of edit
 * intents; nothing goes to Claude Code until you press Enter (or the HUD's 发送).
 * <ul>
 *   <li><b>Left-click</b> a control → adds a SELECT intent (crisp accent outline; stays).</li>
 *   <li><b>Drag</b> a control onto another → adds a MOVE intent (connector line + endpoint,
 *       source & target outlined).</li>
 *   <li><b>Right-click</b> → a sticky-note comment box; type + Enter adds an ANNOTATE intent.</li>
 *   <li>The <b>HUD list</b> (draggable) shows every queued intent with a ✕ to remove it;
 *       hovering a row emphasizes just that intent on the canvas.</li>
 *   <li><b>Enter</b> → send the whole batch to Claude Code, then clear.  <b>Esc</b> → clear all.</li>
 * </ul>
 * (Free-draw → screenshot intent is a separate step.) Must run on the FX thread.
 */
public final class EditMode {
    private EditMode() {
    }

    private static final String CHANNEL_URL =
            System.getenv().getOrDefault("UIMIRROR_CHANNEL_URL", "http://127.0.0.1:8789/event");
    private static final double DRAG_THRESHOLD = 6.0;
    private static final String NOTE_STYLE =
            "-fx-background-color: rgba(255,241,170,0.97); -fx-text-fill: #333333; "
                    + "-fx-border-color: -monet-primary; -fx-border-radius: 4; -fx-background-radius: 4;";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // channel is HTTP/1.1; HTTP/2 h2c stalls it
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** One queued edit intent + its canvas visuals + its HUD row. */
    private static final class Intent {
        final String kind;              // "select" | "move" | "annotate"
        Node control;
        String controlPath;
        Node target;                    // move
        String targetPath;
        String note;                    // annotate
        Rectangle box;                  // control outline
        Rectangle targetBox;            // move target outline
        Line line;
        Circle dot;
        Node marker;                    // annotate sticky note
        Node hudRow;

        Intent(String kind) {
            this.kind = kind;
        }
    }

    private static final List<Intent> intents = new ArrayList<>();

    private static boolean active = false;
    private static EventHandler<MouseEvent> mouseFilter;
    private static EventHandler<KeyEvent> keyFilter;

    // in-progress drag
    private static Node pressControl;
    private static String pressPath;
    private static double pressX, pressY;
    private static boolean dragging = false;
    private static Node dragDropTarget;
    private static Line liveLine;
    private static Circle liveDot;

    // overlay layers
    private static Pane overlay;        // pick-through root (only hud + comments interactive)
    private static Pane decor;          // outlines, connectors, note markers (non-interactive)
    private static Pane comments;       // active comment text editors
    private static VBox hud;            // the intent list panel
    private static VBox hudRows;        // rows container inside the hud

    public static void toggle() {
        if (active) exit();
        else enter();
    }

    private static void enter() {
        Scene scene = Controllers.getScene();
        if (scene == null) {
            LOG.warning("[ui-mirror] no scene; cannot enter edit mode");
            return;
        }
        mouseFilter = EditMode::onMouse;
        keyFilter = EditMode::onKey;
        scene.addEventFilter(MouseEvent.ANY, mouseFilter);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        ensureOverlay(scene);
        active = true;
        LOG.info("[ui-mirror] EDIT MODE on");
        toast("UI 编辑模式：开（左键=选，拖=移动，右键=批注，攒好后回车发送，Esc=清空）");
    }

    private static void exit() {
        Scene scene = Controllers.getScene();
        if (scene != null) {
            if (mouseFilter != null) scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
            if (keyFilter != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        }
        clearQueue();
        removeOverlay();
        active = false;
        dragging = false;
        mouseFilter = null;
        keyFilter = null;
        LOG.info("[ui-mirror] EDIT MODE off");
        toast("UI 编辑模式：关");
    }

    // ---- input ----

    private static void onMouse(MouseEvent e) {
        if (isInteractive(e.getTarget())) return; // let hud + comment editors work
        var t = e.getEventType();
        if (t == MouseEvent.MOUSE_MOVED
                || t == MouseEvent.MOUSE_ENTERED || t == MouseEvent.MOUSE_ENTERED_TARGET
                || t == MouseEvent.MOUSE_EXITED || t == MouseEvent.MOUSE_EXITED_TARGET) {
            return; // hover passes through → native darken feedback
        }
        e.consume();
        try {
            if (t == MouseEvent.MOUSE_PRESSED) {
                if (e.getButton() == MouseButton.SECONDARY) {
                    addComment(e);
                } else {
                    pressControl = pickNode(e);
                    pressPath = pressControl != null ? structuralPath(pressControl) : null;
                    pressX = e.getSceneX();
                    pressY = e.getSceneY();
                    dragging = false;
                }
            } else if (t == MouseEvent.MOUSE_DRAGGED) {
                onDrag(e);
            } else if (t == MouseEvent.MOUSE_RELEASED) {
                onRelease(e);
            }
        } catch (Throwable ex) {
            LOG.warning("[ui-mirror] mouse handling failed", ex);
        }
    }

    private static void onDrag(MouseEvent e) {
        if (pressControl == null) return;
        if (!dragging) {
            double dx = e.getSceneX() - pressX, dy = e.getSceneY() - pressY;
            if (dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
            dragging = true;
        }
        dragDropTarget = pickNode(e);
        updateLiveLine(e);
    }

    private static void onRelease(MouseEvent e) {
        try {
            if (!dragging) {
                if (pressControl != null) addSelect(pressControl, pressPath);
            } else {
                Node drop = pickNode(e);
                if (drop != null && drop != pressControl) {
                    addMove(pressControl, pressPath, drop, structuralPath(drop));
                } else {
                    toast("没落在有效目标上");
                }
            }
        } finally {
            hideLiveLine();
            dragging = false;
            dragDropTarget = null;
            pressControl = null;
            pressPath = null;
        }
    }

    private static void onKey(KeyEvent e) {
        if (focusInComments()) return; // comment editor handles its own keys
        if (e.getCode() == KeyCode.ENTER) {
            e.consume();
            sendBatch();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            clearQueue();
            toast("已清空队列");
        }
    }

    // ---- intents ----

    private static void addSelect(Node control, String path) {
        Intent it = new Intent("select");
        it.control = control;
        it.controlPath = path;
        it.box = outline(control, 2);
        decor.getChildren().add(it.box);
        addIntent(it, "选中：" + label(control, path));
    }

    private static void addMove(Node source, String sourcePath, Node dropNode, String dropPath) {
        Intent it = new Intent("move");
        it.control = source;
        it.controlPath = sourcePath;
        it.target = dropNode;
        it.targetPath = dropPath;
        it.box = outline(source, 2);
        it.targetBox = outline(dropNode, 2);
        Point2D a = centerOf(source), b = centerOf(dropNode);
        it.line = connector(a, b);
        it.dot = new Circle(b.getX(), b.getY(), 5, accentColor());
        it.dot.setMouseTransparent(true);
        decor.getChildren().addAll(it.box, it.targetBox, it.line, it.dot);
        addIntent(it, "拖拽：" + label(source, sourcePath) + " → " + label(dropNode, dropPath));
    }

    private static void addAnnotate(String note, Node ctx, String ctxPath, Node marker) {
        Intent it = new Intent("annotate");
        it.note = note;
        it.control = ctx;
        it.controlPath = ctxPath;
        it.marker = marker; // already on the comments layer
        addIntent(it, "批注：" + note);
    }

    private static void addIntent(Intent it, String rowLabel) {
        intents.add(it);
        it.hudRow = makeHudRow(it, rowLabel);
        if (hudRows != null) hudRows.getChildren().add(it.hudRow);
        refreshHud();
    }

    private static void removeIntent(Intent it) {
        intents.remove(it);
        if (it.box != null) decor.getChildren().remove(it.box);
        if (it.targetBox != null) decor.getChildren().remove(it.targetBox);
        if (it.line != null) decor.getChildren().remove(it.line);
        if (it.dot != null) decor.getChildren().remove(it.dot);
        if (it.marker != null && comments != null) comments.getChildren().remove(it.marker);
        if (it.hudRow != null && hudRows != null) hudRows.getChildren().remove(it.hudRow);
        refreshHud();
    }

    private static void clearQueue() {
        for (Intent it : new ArrayList<>(intents)) removeIntent(it);
        intents.clear();
        hideLiveLine();
    }

    /** Enter / 发送 → one batch event with all queued intents, then clear. */
    private static void sendBatch() {
        if (intents.isEmpty()) {
            toast("队列是空的");
            return;
        }
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Intent it : intents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", it.kind);
            if (it.controlPath != null) m.put("control_id", it.controlPath);
            if (it.control != null) {
                String tx = textOf(it.control);
                if (tx != null && !tx.isEmpty()) m.put("text", tx);
            }
            if ("move".equals(it.kind)) {
                m.put("target_id", it.targetPath);
                String tt = textOf(it.target);
                if (tt != null && !tt.isEmpty()) m.put("target_text", tt);
            } else if ("annotate".equals(it.kind)) {
                m.put("note", it.note);
            }
            arr.add(m);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("action", "batch");
        meta.put("intents", arr);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", intents.size() + " 个编辑意图（batch）");
        payload.put("meta", meta);
        post(payload);
        toast("已发送 " + intents.size() + " 个意图 → Claude");
        clearQueue();
    }

    // ---- canvas visuals ----

    private static Rectangle outline(Node n, double strokeW) {
        Bounds b = n.localToScene(n.getBoundsInLocal());
        Point2D tl = decor.sceneToLocal(b.getMinX(), b.getMinY());
        Rectangle r = new Rectangle(tl.getX(), tl.getY(), b.getWidth(), b.getHeight());
        r.setFill(Color.TRANSPARENT);
        r.setStroke(accentColor());
        r.setStrokeWidth(strokeW);
        r.setArcWidth(6);
        r.setArcHeight(6);
        r.setMouseTransparent(true);
        return r;
    }

    private static Line connector(Point2D a, Point2D b) {
        Line l = new Line(a.getX(), a.getY(), b.getX(), b.getY());
        l.setStroke(accentColor());
        l.setStrokeWidth(2.5);
        l.setMouseTransparent(true);
        return l;
    }

    private static Point2D centerOf(Node n) {
        Bounds b = n.localToScene(n.getBoundsInLocal());
        return decor.sceneToLocal(b.getMinX() + b.getWidth() / 2, b.getMinY() + b.getHeight() / 2);
    }

    private static void updateLiveLine(MouseEvent e) {
        if (liveLine == null || decor == null || pressControl == null) return;
        Point2D a = centerOf(pressControl);
        Point2D b = decor.sceneToLocal(e.getSceneX(), e.getSceneY());
        Color c = accentColor();
        liveLine.setStroke(c);
        liveLine.setStartX(a.getX());
        liveLine.setStartY(a.getY());
        liveLine.setEndX(b.getX());
        liveLine.setEndY(b.getY());
        liveLine.setVisible(true);
        liveDot.setFill(c);
        liveDot.setCenterX(b.getX());
        liveDot.setCenterY(b.getY());
        liveDot.setVisible(true);
    }

    private static void hideLiveLine() {
        if (liveLine != null) liveLine.setVisible(false);
        if (liveDot != null) liveDot.setVisible(false);
    }

    private static void emphasize(Intent it, boolean on) {
        if (it.box != null) {
            it.box.setStrokeWidth(on ? 4 : 2);
            if (on) it.box.toFront();
        }
        if (it.targetBox != null) it.targetBox.setStrokeWidth(on ? 4 : 2);
        if (it.marker != null) it.marker.setOpacity(on ? 1.0 : 0.97);
    }

    // ---- HUD list ----

    private static Node makeHudRow(Intent it, String labelText) {
        Label lbl = new Label(labelText);
        lbl.setWrapText(true);
        lbl.setMaxWidth(Double.MAX_VALUE);
        lbl.setStyle("-fx-text-fill: -monet-on-surface;");
        HBox.setHgrow(lbl, Priority.ALWAYS);

        Label close = new Label("✕");
        close.setStyle("-fx-text-fill: -monet-on-surface-variant; -fx-padding: 0 4; -fx-cursor: hand;");
        close.setOnMouseClicked(ev -> {
            removeIntent(it);
            ev.consume();
        });

        HBox row = new HBox(6, lbl, close);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 3 6; -fx-background-radius: 4;");
        row.setOnMouseEntered(ev -> {
            row.setStyle("-fx-padding: 3 6; -fx-background-radius: 4; -fx-background-color: -monet-surface-container-high;");
            emphasize(it, true);
        });
        row.setOnMouseExited(ev -> {
            row.setStyle("-fx-padding: 3 6; -fx-background-radius: 4;");
            emphasize(it, false);
        });
        return row;
    }

    private static void refreshHud() {
        if (hud == null) return;
        hud.setVisible(!intents.isEmpty());
    }

    private static VBox buildHud() {
        Label title = new Label("编辑意图");
        title.setStyle("-fx-text-fill: -monet-on-surface; -fx-font-weight: bold; -fx-padding: 2 0;");

        hudRows = new VBox(2);

        Label send = new Label("发送 ⏎");
        send.setStyle("-fx-text-fill: -monet-on-primary; -fx-background-color: -monet-primary; "
                + "-fx-padding: 4 10; -fx-background-radius: 4; -fx-cursor: hand;");
        send.setOnMouseClicked(ev -> {
            sendBatch();
            ev.consume();
        });

        VBox box = new VBox(6, title, hudRows, send);
        box.setStyle("-fx-background-color: -monet-surface-container; -fx-padding: 8 10; "
                + "-fx-background-radius: 8; -fx-border-color: -monet-outline-variant; -fx-border-radius: 8;");
        box.setManaged(false);
        box.setMinWidth(240);
        box.setPrefWidth(280);
        box.setVisible(false);
        box.relocate(24, 60);

        // drag the panel by its title
        final double[] off = new double[2];
        title.setOnMousePressed(ev -> {
            off[0] = ev.getSceneX() - box.getLayoutX();
            off[1] = ev.getSceneY() - box.getLayoutY();
            ev.consume();
        });
        title.setOnMouseDragged(ev -> {
            box.relocate(ev.getSceneX() - off[0], ev.getSceneY() - off[1]);
            ev.consume();
        });
        return box;
    }

    // ---- comments (right-click sticky notes) ----

    private static void addComment(MouseEvent e) {
        if (comments == null) return;
        Point2D pos = comments.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (pos == null) return;
        Node ctx = pickNode(e);
        String ctxPath = ctx != null ? structuralPath(ctx) : null;

        TextField tf = new TextField();
        tf.setPromptText("批注…回车确认，Esc 取消");
        tf.setManaged(false);
        tf.setStyle(NOTE_STYLE);
        tf.resizeRelocate(pos.getX(), pos.getY(), 220, 30);
        tf.setOnAction(ev -> confirmComment(tf, pos, ctx, ctxPath));
        tf.addEventHandler(KeyEvent.KEY_PRESSED, ke -> {
            if (ke.getCode() == KeyCode.ESCAPE) {
                comments.getChildren().remove(tf);
                ke.consume();
            }
        });
        comments.getChildren().add(tf);
        Platform.runLater(tf::requestFocus); // defer so focus takes after this event dispatch
    }

    private static void confirmComment(TextField tf, Point2D pos, Node ctx, String ctxPath) {
        String text = tf.getText() == null ? "" : tf.getText().trim();
        comments.getChildren().remove(tf);
        if (text.isEmpty()) return;

        Label marker = new Label("📝 " + text);
        marker.setManaged(false);
        marker.setWrapText(true);
        marker.setMaxWidth(240);
        marker.setStyle(NOTE_STYLE + "-fx-padding: 3 7;");
        marker.relocate(pos.getX(), pos.getY());
        comments.getChildren().add(marker);
        addAnnotate(text, ctx, ctxPath, marker);
        // clicking the note (or its ✕ in the list) removes it via removeIntent
        marker.setOnMouseClicked(ev -> ev.consume());
    }

    // ---- overlay lifecycle ----

    private static void ensureOverlay(Scene scene) {
        if (overlay != null) return;
        if (!(scene.getRoot() instanceof Decorator d)) return;
        Pane wrap = d.getDrawerWrapper();
        if (wrap == null) return;

        decor = new Pane();
        decor.setManaged(false);
        decor.setMouseTransparent(true);

        liveLine = new Line();
        liveLine.setStrokeWidth(2.5);
        liveLine.setVisible(false);
        liveDot = new Circle(5);
        liveDot.setVisible(false);
        decor.getChildren().addAll(liveLine, liveDot);

        comments = new Pane();
        comments.setManaged(false);
        comments.setPickOnBounds(false);

        hud = buildHud();

        overlay = new Pane(decor, comments, hud);
        overlay.setManaged(false);
        overlay.setPickOnBounds(false); // empty areas pick-through; only hud + comments interactive
        wrap.getChildren().add(overlay);
    }

    private static void removeOverlay() {
        if (overlay != null && overlay.getParent() instanceof Pane pp) pp.getChildren().remove(overlay);
        overlay = null;
        decor = null;
        comments = null;
        hud = null;
        hudRows = null;
        liveLine = null;
        liveDot = null;
    }

    private static boolean isInteractive(Object target) {
        if (!(target instanceof Node)) return false;
        for (Node c = (Node) target; c != null; c = c.getParent())
            if (c == comments || c == hud) return true;
        return false;
    }

    private static boolean focusInComments() {
        Scene scene = Controllers.getScene();
        if (scene == null || comments == null) return false;
        for (Node c = scene.getFocusOwner(); c != null; c = c.getParent())
            if (c == comments) return true;
        return false;
    }

    /** True if the key event targets our overlay — so HMCL's decorator must not redirect it. */
    public static boolean ownsKeyTarget(Object target) {
        if (overlay == null || !(target instanceof Node)) return false;
        for (Node c = (Node) target; c != null; c = c.getParent())
            if (c == overlay) return true;
        return false;
    }

    // ---- shared bits ----

    private static Node pickNode(MouseEvent e) {
        var pr = e.getPickResult();
        return pr == null ? null : pr.getIntersectedNode();
    }

    private static String label(Node n, String path) {
        String t = textOf(n);
        return (t != null && !t.isEmpty()) ? "「" + t + "」" : shortPath(path);
    }

    private static String shortPath(String path) {
        if (path == null) return "?";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static void post(Map<String, Object> payload) {
        try {
            String json = new Gson().toJson(payload);
            HttpRequest req = HttpRequest.newBuilder(URI.create(CHANNEL_URL))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        LOG.warning("[ui-mirror] channel POST failed (channel running?): " + ex.getMessage());
                        return null;
                    });
        } catch (Throwable t) {
            LOG.warning("[ui-mirror] post failed", t);
        }
    }

    private static void toast(String msg) {
        try {
            Controllers.showToast(msg);
        } catch (Throwable ignored) {
        }
    }

    private static Color accentColor() {
        try {
            ColorScheme scheme = Themes.getColorScheme();
            if (scheme != null) return scheme.getColor(ColorRole.PRIMARY);
        } catch (Throwable ignored) {
        }
        return Color.web("#5C6BC0");
    }

    static String structuralPath(Node node) {
        List<String> parts = new ArrayList<>();
        Node cur = node;
        while (cur != null) {
            Parent p = cur.getParent();
            if (p == null) {
                parts.add("root");
                break;
            }
            int idx = p.getChildrenUnmodifiable().indexOf(cur);
            String simple = cur.getClass().getSimpleName();
            if (simple.isEmpty()) simple = nearestFxType(cur.getClass());
            parts.add(simple + "[" + idx + "]");
            cur = p;
        }
        Collections.reverse(parts);
        return String.join("/", parts);
    }

    private static String textOf(Node n) {
        if (n instanceof Labeled l) return l.getText();
        if (n instanceof Text t) return t.getText();
        return null;
    }

    private static String nearestFxType(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            if (c.getName().startsWith("javafx.")) return c.getSimpleName();
        return cls.getSimpleName();
    }
}
