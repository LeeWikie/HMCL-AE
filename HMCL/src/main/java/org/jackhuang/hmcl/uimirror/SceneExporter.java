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

import com.google.gson.GsonBuilder;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.ColorScheme;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * ui-mirror M0: dumps the live JavaFX scene graph (+ the current Material Design 3
 * theme variables) of the visible window to a JSON file, so an external web editor
 * can reproduce the UI. See {@code hmcl-ae-ui-mirror/DESIGN.md}.
 *
 * <p>A single walk only captures the currently visible page (off-screen pages are
 * detached from the scene graph and lazily created), so trigger a dump on each page
 * you visit — snapshots accumulate under {@code <cwd>/ui-mirror/}.
 *
 * <p>Must run on the JavaFX Application Thread (reads layout bounds).
 */
public final class SceneExporter {
    private SceneExporter() {
    }

    /** Output directory: {@code <launcher working dir>/ui-mirror/}. */
    public static final Path OUTPUT_DIR = Metadata.CURRENT_DIRECTORY.resolve("ui-mirror");

    /**
     * Walk the current scene and write a snapshot. Safe to call from any thread;
     * the actual walk is marshalled onto the FX thread.
     */
    public static void dumpCurrentScene() {
        FXUtils.runInFX(SceneExporter::dumpOnFXThread);
    }

    private static void dumpOnFXThread() {
        try {
            Scene scene = Controllers.getScene();
            if (scene == null || scene.getRoot() == null) {
                LOG.warning("[ui-mirror] no scene to export");
                return;
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("format", "hmcl-ui-mirror/1");
            snapshot.put("capturedAtMillis", System.currentTimeMillis());
            snapshot.put("sceneWidth", scene.getWidth());
            snapshot.put("sceneHeight", scene.getHeight());
            snapshot.put("theme", dumpTheme());
            snapshot.put("root", walk(scene.getRoot(), "root", 0));

            Files.createDirectories(OUTPUT_DIR);
            Path out = OUTPUT_DIR.resolve("scene-" + System.currentTimeMillis() + ".json");
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(snapshot);
            Files.write(out, json.getBytes(StandardCharsets.UTF_8));
            LOG.info("[ui-mirror] exported scene to " + out);
        } catch (Throwable e) {
            LOG.error("[ui-mirror] export failed", e);
        }
    }

    /** Emit every Material Design 3 color role as {@code -monet-*: #RRGGBB}. */
    private static Map<String, Object> dumpTheme() {
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("dark", Themes.darkModeProperty().get());
        Map<String, String> vars = new LinkedHashMap<>();
        ColorScheme scheme = Themes.getColorScheme();
        if (scheme != null) {
            for (ColorRole role : ColorRole.ALL) {
                vars.put(role.getVariableName(), toHex(scheme.getColor(role)));
            }
        }
        theme.put("vars", vars);
        return theme;
    }

    /**
     * Recursively serialize a node. {@code path} is the stable structural id used to
     * map a web edit back to the Java construction site (e.g. {@code root/VBox[0]/AdvancedListItem[2]}).
     */
    private static Map<String, Object> walk(Node node, String path, int depth) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", path);
        n.put("type", node.getClass().getName());
        n.put("fxType", nearestFxType(node.getClass()));
        n.put("visible", node.isVisible());

        List<String> classes = new ArrayList<>(node.getStyleClass());
        if (!classes.isEmpty()) {
            n.put("styleClasses", classes);
        }
        if (node.getId() != null) {
            n.put("fxId", node.getId());
        }

        String text = extractText(node);
        if (text != null && !text.isEmpty()) {
            n.put("text", text);
        }

        Bounds b = node.localToScene(node.getBoundsInLocal());
        if (b != null) {
            Map<String, Object> bounds = new LinkedHashMap<>();
            bounds.put("x", Math.round(b.getMinX()));
            bounds.put("y", Math.round(b.getMinY()));
            bounds.put("w", Math.round(b.getWidth()));
            bounds.put("h", Math.round(b.getHeight()));
            n.put("bounds", bounds);
        }

        if (node instanceof Parent parent) {
            List<Node> kids = parent.getChildrenUnmodifiable();
            if (!kids.isEmpty()) {
                List<Object> children = new ArrayList<>(kids.size());
                for (int i = 0; i < kids.size(); i++) {
                    Node child = kids.get(i);
                    String simple = child.getClass().getSimpleName();
                    if (simple.isEmpty()) {
                        simple = nearestFxType(child.getClass()); // anonymous subclass
                    }
                    children.add(walk(child, path + "/" + simple + "[" + i + "]", depth + 1));
                }
                n.put("children", children);
            }
        }
        return n;
    }

    private static String extractText(Node node) {
        if (node instanceof Labeled labeled) {
            return labeled.getText();
        }
        if (node instanceof Text t) {
            return t.getText();
        }
        if (node instanceof TextInputControl input) {
            String v = input.getText();
            return (v == null || v.isEmpty()) ? input.getPromptText() : v;
        }
        return null;
    }

    /** Walk up the class hierarchy to the nearest {@code javafx.*} ancestor's simple name. */
    private static String nearestFxType(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("javafx.")) {
                return c.getSimpleName();
            }
        }
        return cls.getSimpleName();
    }

    private static String toHex(Color c) {
        if (c == null) {
            return null;
        }
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
