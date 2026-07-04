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
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
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
 * <p>Operate mode = normal app. Edit mode freezes the live UI as a canvas:
 * hover passes through (native darken feedback), press/click/drag are captured.
 *
 * <p><b>Intents</b> (deliberate — nothing sends until confirmed):
 * <ul>
 *   <li><b>Left-click</b> → SELECT a control (accent glow).</li>
 *   <li><b>Drag</b> a control onto another → stages a PENDING MOVE: source stays
 *       highlighted, a rigid connector line + endpoint dot are drawn to the target,
 *       target highlighted. Nothing sent yet.</li>
 *   <li><b>Enter</b> → SEND the pending move if any, else the selection.</li>
 *   <li><b>Right-click</b> anywhere → a sticky-note comment box at that spot; type +
 *       Enter pins it and sends an annotate intent. Multiple, independent; click a
 *       note to delete. Coexists with select/drag, doesn't disturb them.</li>
 *   <li><b>Esc</b> → cancel selection / pending move.</li>
 * </ul>
 * See hmcl-ae-ui-mirror/DESIGN.md. Must run on the JavaFX Application Thread.
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

    // Force HTTP/1.1: the channel is a Node/Bun HTTP/1.1 server; Java's default HTTP/2
    // attempts an h2c upgrade the server mishandles → connection stalls / "received no bytes".
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static boolean active = false;
    private static EventHandler<MouseEvent> mouseFilter;
    private static EventHandler<KeyEvent> keyFilter;

    private static Node selected;
    private static Effect prevEffect;
    private static String selectedPath;

    // drag / pending-move state
    private static double pressX, pressY;
    private static boolean dragging = false;
    private static Node dropTarget;
    private static Effect dropPrevEffect;
    private static Node pendingTarget;
    private static String pendingTargetPath;

    // full-scene overlay
    private static Pane overlay;
    private static Line dragLine;
    private static Circle endpointDot;
    private static Pane commentsLayer;

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
        toast("UI 编辑模式：开（左键=选，拖=移动，右键=批注，回车=发送，Esc=取消，Ctrl+Shift+E=退出）");
    }

    private static void exit() {
        Scene scene = Controllers.getScene();
        if (scene != null) {
            if (mouseFilter != null) scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
            if (keyFilter != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        }
        clearAll();
        removeOverlay();
        active = false;
        dragging = false;
        mouseFilter = null;
        keyFilter = null;
        LOG.info("[ui-mirror] EDIT MODE off");
        toast("UI 编辑模式：关");
    }

    private static void onMouse(MouseEvent e) {
        // Let events on our own comment widgets through — they're interactive.
        if (isInComments(e.getTarget())) return;

        var t = e.getEventType();
        // Pass hover-family events through: drives the control's native :hover darken feedback.
        if (t == MouseEvent.MOUSE_MOVED
                || t == MouseEvent.MOUSE_ENTERED || t == MouseEvent.MOUSE_ENTERED_TARGET
                || t == MouseEvent.MOUSE_EXITED || t == MouseEvent.MOUSE_EXITED_TARGET) {
            return;
        }
        e.consume();
        try {
            if (t == MouseEvent.MOUSE_PRESSED) {
                if (e.getButton() == MouseButton.SECONDARY) {
                    addComment(e); // right-click → independent sticky-note comment
                } else if (e.getTarget() instanceof Node n) {
                    select(n);
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
        if (selected == null) return;
        if (!dragging) {
            double dx = e.getSceneX() - pressX, dy = e.getSceneY() - pressY;
            if (dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
            dragging = true;
        }
        setDropTarget(pickNode(e));
        updateDragLine(e);
    }

    /** Release does NOT send — it stages a pending move (line + endpoint stay); Enter sends. */
    private static void onRelease(MouseEvent e) {
        if (!dragging) return; // plain click: selection stays, Enter sends
        dragging = false;
        Node drop = pickNode(e);
        if (drop != null && drop != selected) {
            updateDragLine(e);      // snap line + dot to the exact release point, keep visible
            setDropTarget(drop);    // keep target highlighted
            pendingTarget = drop;
            pendingTargetPath = structuralPath(drop);
            toast("移动待发：回车确认，Esc 取消");
        } else {
            clearDropTarget();
            hideDragLine();
            toast("没落在有效目标上");
        }
    }

    private static void onKey(KeyEvent e) {
        if (focusInComments()) return; // a comment editor handles its own Enter/Esc
        if (e.getCode() == KeyCode.ENTER) {
            e.consume();
            commit();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            clearAll();
            toast("已取消");
        }
    }

    /** New left-click → fresh selection (abandons any pending move; comments untouched). */
    private static void select(Node node) {
        clearAll();
        selected = node;
        prevEffect = node.getEffect();
        node.setEffect(new DropShadow(BlurType.THREE_PASS_BOX, accentColor(), 12, 0.9, 0, 0));
        selectedPath = structuralPath(node);
        LOG.info("[ui-mirror] selected " + selectedPath);
    }

    /** Enter → send the pending move if any, else the current selection as a reference. */
    private static void commit() {
        if (pendingTarget != null && selected != null) {
            doMove(selected, selectedPath, pendingTarget, pendingTargetPath);
            clearAll();
            return;
        }
        if (selected == null) {
            toast("没选中控件");
            return;
        }
        Map<String, Object> meta = baseMeta(selected, selectedPath);
        meta.put("action", "select");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", "选中控件 " + label(selected, selectedPath));
        payload.put("meta", meta);
        post(payload);
        toast("已发送 → Claude");
    }

    private static void doMove(Node source, String sourcePath, Node dropNode, String dropPath) {
        Map<String, Object> meta = baseMeta(source, sourcePath);
        meta.put("action", "move");
        meta.put("target_id", dropPath);
        meta.put("target_fx_type", nearestFxType(dropNode.getClass()));
        String dstText = textOf(dropNode);
        if (dstText != null && !dstText.isEmpty()) meta.put("target_text", dstText);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", "把控件 " + label(source, sourcePath) + " 移动到 " + label(dropNode, dropPath));
        payload.put("meta", meta);
        post(payload);
        LOG.info("[ui-mirror] move " + sourcePath + " -> " + dropPath);
        toast("已发送移动 → Claude");
    }

    private static void setDropTarget(Node node) {
        if (node == selected) node = null; // never target the source itself
        if (node == dropTarget) return;
        if (dropTarget != null) dropTarget.setEffect(dropPrevEffect);
        dropTarget = node;
        if (node != null) {
            dropPrevEffect = node.getEffect();
            node.setEffect(new DropShadow(BlurType.THREE_PASS_BOX, accentColor(), 20, 0.55, 0, 0));
        } else {
            dropPrevEffect = null;
        }
    }

    private static void clearDropTarget() {
        if (dropTarget != null) dropTarget.setEffect(dropPrevEffect);
        dropTarget = null;
        dropPrevEffect = null;
    }

    private static Node pickNode(MouseEvent e) {
        var pr = e.getPickResult();
        return pr == null ? null : pr.getIntersectedNode();
    }

    /** Reset selection + pending move + drag line (comments are independent, left alone). */
    private static void clearAll() {
        hideDragLine();
        clearDropTarget();
        if (selected != null) selected.setEffect(prevEffect);
        selected = null;
        prevEffect = null;
        selectedPath = null;
        pendingTarget = null;
        pendingTargetPath = null;
    }

    // ---- overlay: drag connector (rigid line + endpoint dot) + comments layer ----

    private static void ensureOverlay(Scene scene) {
        if (overlay != null) return;
        if (!(scene.getRoot() instanceof Decorator d)) return;
        Pane wrap = d.getDrawerWrapper();
        if (wrap == null) return;
        dragLine = new Line();
        dragLine.setStrokeWidth(2.5);
        dragLine.setVisible(false);
        dragLine.setMouseTransparent(true);
        endpointDot = new Circle(5);
        endpointDot.setVisible(false);
        endpointDot.setMouseTransparent(true);
        commentsLayer = new Pane();
        commentsLayer.setManaged(false);
        commentsLayer.setPickOnBounds(false);
        overlay = new Pane(dragLine, endpointDot, commentsLayer);
        overlay.setManaged(false);      // don't disturb layout
        overlay.setPickOnBounds(false); // empty areas stay pick-through (drop detection); only notes are interactive
        wrap.getChildren().add(overlay);
    }

    private static void removeOverlay() {
        if (overlay != null && overlay.getParent() instanceof Pane pp) pp.getChildren().remove(overlay);
        overlay = null;
        dragLine = null;
        endpointDot = null;
        commentsLayer = null;
    }

    private static void updateDragLine(MouseEvent e) {
        if (dragLine == null || overlay == null || selected == null) return;
        var b = selected.localToScene(selected.getBoundsInLocal());
        Point2D p1 = overlay.sceneToLocal(b.getMinX() + b.getWidth() / 2, b.getMinY() + b.getHeight() / 2);
        Point2D p2 = overlay.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (p1 == null || p2 == null) return;
        Color c = accentColor();
        dragLine.setStroke(c);
        dragLine.setStartX(p1.getX());
        dragLine.setStartY(p1.getY());
        dragLine.setEndX(p2.getX());
        dragLine.setEndY(p2.getY());
        dragLine.setVisible(true);
        endpointDot.setFill(c);
        endpointDot.setCenterX(p2.getX());
        endpointDot.setCenterY(p2.getY());
        endpointDot.setVisible(true);
    }

    private static void hideDragLine() {
        if (dragLine != null) dragLine.setVisible(false);
        if (endpointDot != null) endpointDot.setVisible(false);
    }

    // ---- comments (right-click sticky notes; independent of select/drag) ----

    private static boolean isInComments(Object target) {
        if (commentsLayer == null || !(target instanceof Node)) return false;
        for (Node c = (Node) target; c != null; c = c.getParent())
            if (c == commentsLayer) return true;
        return false;
    }

    private static boolean focusInComments() {
        Scene scene = Controllers.getScene();
        return scene != null && isInComments(scene.getFocusOwner());
    }

    private static void addComment(MouseEvent e) {
        if (commentsLayer == null) return;
        Point2D pos = commentsLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
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
                commentsLayer.getChildren().remove(tf);
                ke.consume();
            }
        });
        commentsLayer.getChildren().add(tf);
        tf.requestFocus();
    }

    private static void confirmComment(TextField tf, Point2D pos, Node ctx, String ctxPath) {
        String text = tf.getText() == null ? "" : tf.getText().trim();
        commentsLayer.getChildren().remove(tf);
        if (text.isEmpty()) return;

        Label marker = new Label("📝 " + text);
        marker.setManaged(false);
        marker.setWrapText(true);
        marker.setMaxWidth(240);
        marker.setStyle(NOTE_STYLE + "-fx-padding: 3 7;");
        marker.relocate(pos.getX(), pos.getY());
        marker.setOnMouseClicked(ev -> { // click a note to delete it
            commentsLayer.getChildren().remove(marker);
            ev.consume();
        });
        commentsLayer.getChildren().add(marker);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("action", "annotate");
        meta.put("note", text);
        meta.put("x", Math.round(pos.getX()));
        meta.put("y", Math.round(pos.getY()));
        if (ctxPath != null) meta.put("control_id", ctxPath);
        String ctxText = ctx != null ? textOf(ctx) : null;
        if (ctxText != null && !ctxText.isEmpty()) meta.put("near_text", ctxText);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", "批注：" + text + (ctxPath != null ? "（在 " + label(ctx, ctxPath) + " 附近）" : ""));
        payload.put("meta", meta);
        post(payload);
        toast("已发送批注 → Claude");
    }

    // ---- shared bits ----

    private static Map<String, Object> baseMeta(Node node, String path) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("control_id", path);
        meta.put("fx_type", nearestFxType(node.getClass()));
        meta.put("type", node.getClass().getName());
        String text = textOf(node);
        if (text != null && !text.isEmpty()) meta.put("text", text);
        return meta;
    }

    private static String label(Node n, String path) {
        String t = textOf(n);
        return (t != null && !t.isEmpty()) ? "「" + t + "」" : path;
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

    /**
     * Bottom-up structural path of a node, e.g. {@code root/StackPane[0]/.../LineButton[3]}.
     * Format matches {@link SceneExporter} so exporter paths and live paths agree.
     */
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
