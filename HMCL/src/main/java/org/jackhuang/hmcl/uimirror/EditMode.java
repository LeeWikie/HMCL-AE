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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.ColorScheme;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;

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
 * hover events pass through so controls give their native darken feedback,
 * while press/click/drag are captured (the app doesn't act on them).
 *
 * <p><b>Select vs. send are decoupled</b> (deliberate, one-at-a-time — no flood):
 * <ul>
 *   <li><b>Click</b> a control → SELECT it locally: theme-accent glow, structural
 *       path computed. Nothing is sent.</li>
 *   <li><b>Enter</b> → SEND the current selection as ONE intent to the channel
 *       (channel.ts /event → Claude Code). This is "比划完，敲个回车，送过去".</li>
 *   <li><b>Esc</b> → clear the current selection.</li>
 * </ul>
 *
 * <p>NOTE (WIP): selection feedback is an accent glow, a stand-in for the crisp
 * accent OUTLINE (needs an overlay pane, tuned at runtime). Drag gestures are
 * captured but not yet interpreted. See hmcl-ae-ui-mirror/DESIGN.md.
 *
 * <p>Must run on the JavaFX Application Thread.
 */
public final class EditMode {
    private EditMode() {
    }

    private static final String CHANNEL_URL =
            System.getenv().getOrDefault("UIMIRROR_CHANNEL_URL", "http://127.0.0.1:8789/event");

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
        active = true;
        LOG.info("[ui-mirror] EDIT MODE on");
        toast("UI 编辑模式：开（点=选中，回车=发给 Claude，Esc=取消，Ctrl+Shift+E=退出）");
    }

    private static void exit() {
        Scene scene = Controllers.getScene();
        if (scene != null) {
            if (mouseFilter != null) scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
            if (keyFilter != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        }
        clearSelection();
        active = false;
        mouseFilter = null;
        keyFilter = null;
        LOG.info("[ui-mirror] EDIT MODE off");
        toast("UI 编辑模式：关");
    }

    private static void onMouse(MouseEvent e) {
        var t = e.getEventType();
        // Pass hover-family events through: drives the control's native :hover darken
        // feedback for free (no foreign highlight needed for aiming).
        if (t == MouseEvent.MOUSE_MOVED
                || t == MouseEvent.MOUSE_ENTERED || t == MouseEvent.MOUSE_ENTERED_TARGET
                || t == MouseEvent.MOUSE_EXITED || t == MouseEvent.MOUSE_EXITED_TARGET) {
            return;
        }
        // Everything else (press / release / click / drag) is ours, not the app's.
        e.consume();
        if (t == MouseEvent.MOUSE_PRESSED && e.getTarget() instanceof Node n) {
            try {
                select(n);
            } catch (Throwable ex) {
                LOG.warning("[ui-mirror] select failed", ex);
            }
        }
    }

    private static void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            e.consume();
            commit();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            clearSelection();
            toast("已取消选中");
        }
    }

    /** Click → select locally only (no send). Enter commits it. */
    private static void select(Node node) {
        clearSelection();
        selected = node;
        prevEffect = node.getEffect();
        // Selection mark: accent-colored glow (theme -monet-primary). Interim stand-in
        // for the crisp outline (which needs an overlay pane, tuned at runtime).
        node.setEffect(new DropShadow(BlurType.THREE_PASS_BOX, accentColor(), 12, 0.9, 0, 0));
        selectedPath = structuralPath(node);
        LOG.info("[ui-mirror] selected " + selectedPath + " (Enter to send)");
    }

    /** Enter → deliberately send the current selection as ONE intent (no per-click flood). */
    private static void commit() {
        if (selected == null) {
            toast("没选中控件");
            return;
        }
        emit(selected, selectedPath);
        toast("已发送 → Claude");
    }

    private static void clearSelection() {
        if (selected != null) {
            selected.setEffect(prevEffect);
        }
        selected = null;
        prevEffect = null;
        selectedPath = null;
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
     * Format matches {@link SceneExporter} so exporter paths and live-click paths agree.
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

    /** POST the selection as a channel event (best-effort, non-blocking). */
    private static void emit(Node node, String path) {
        try {
            String text = textOf(node);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("control_id", path);
            meta.put("action", "select");
            meta.put("fx_type", nearestFxType(node.getClass()));
            meta.put("type", node.getClass().getName());
            if (text != null && !text.isEmpty()) meta.put("text", text);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", "选中控件 " + (text != null && !text.isEmpty() ? "「" + text + "」" : path));
            payload.put("meta", meta);

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
            LOG.warning("[ui-mirror] emit failed", t);
        }
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
