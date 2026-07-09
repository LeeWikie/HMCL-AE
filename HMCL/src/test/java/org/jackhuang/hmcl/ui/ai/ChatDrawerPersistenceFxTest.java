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
package org.jackhuang.hmcl.ui.ai;

import com.jfoenix.controls.JFXButton;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// P6 (blueprint B1): the chat drawer's four AiSettings-backed toggles and the composer's
/// thinking-level picker only ever synced MEMORY (`bindBidirectional` — AiSettings has no
/// auto-save), so every one of them silently reverted on restart. Asserts the on-disk state by
/// re-loading a fresh AiSettings from the same config dir after driving the UI controls.
/// Event/direct-method injection only (A7).
public final class ChatDrawerPersistenceFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    /// Reloads AiSettings fresh from disk — the ONLY honest persistence oracle.
    private static AiSettings reloadFromDisk() throws Exception {
        AiSettings fresh = new AiSettings(SettingsManager.localConfigDirectory());
        fresh.load();
        return fresh;
    }

    /// Finds a LineToggleButton by exact title, descending through scene children AND
    /// ComponentList content (items only become scene children once skins exist).
    private static LineToggleButton findToggle(Node root, String title) {
        if (root instanceof LineToggleButton btn && title.equals(btn.getTitle())) {
            return btn;
        }
        if (root instanceof ComponentList list) {
            for (Node child : list.getContent()) {
                LineToggleButton found = findToggle(child, title);
                if (found != null) return found;
            }
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                LineToggleButton found = findToggle(child, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void drawerTogglePersistsToDiskAndBack() throws Exception {
        AIMainPage page = showPage();
        Node drawer = (Node) getField(page, "chatSettingsDrawer");
        assertNotNull(drawer);

        LineToggleButton enterSend = findToggle(drawer, "回车发送");
        assertNotNull(enterSend, "the drawer must contain the 回车发送 toggle");

        boolean original = reloadFromDisk().isSendOnEnter();
        try {
            WaitForAsyncUtils.asyncFx(() -> enterSend.setSelected(!original)).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(!original, reloadFromDisk().isSendOnEnter(),
                    "flipping the drawer toggle must write ai-settings to disk (P6)");

            WaitForAsyncUtils.asyncFx(() -> enterSend.setSelected(original)).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(original, reloadFromDisk().isSendOnEnter(),
                    "flipping it back must persist again");
        } finally {
            // Belt-and-braces restore even if an assertion above failed mid-way.
            AiSettings settings = (AiSettings) getField(page, "aiSettings");
            WaitForAsyncUtils.asyncFx(() -> {
                settings.sendOnEnterProperty().set(original);
                try {
                    settings.save();
                } catch (Exception ignored) {
                }
            }).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void thinkingLevelPickPersistsToDisk() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        String original = settings.getReasoningEffort();
        String target = "high".equals(original) ? "medium" : "high";
        try {
            // Open the thinking-level popup via its real button (the only ai-toolbar-icon-btn
            // whose graphic is a raw SVGPath — the bulb; other toolbar icon buttons use SVG enums).
            JFXButton thinkBtn = findThinkButton(page);
            assertNotNull(thinkBtn, "composer must contain the thinking-level button");
            WaitForAsyncUtils.asyncFx(thinkBtn::fire).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            com.jfoenix.controls.JFXPopup popup =
                    (com.jfoenix.controls.JFXPopup) getField(page, "thinkingPopup");
            assertNotNull(popup, "firing the button must open the picker popup");

            Node row = findLevelRow(popup.getPopupContent(), target);
            assertNotNull(row, "picker must list the '" + target + "' level");
            WaitForAsyncUtils.asyncFx(() -> Event.fireEvent(row, syntheticClick(row))).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertEquals(target, settings.getReasoningEffort(), "in-memory level must switch");
            assertEquals(target, reloadFromDisk().getReasoningEffort(),
                    "picking a thinking level must persist to disk (P6/C-17)");
        } finally {
            WaitForAsyncUtils.asyncFx(() -> {
                settings.reasoningEffortProperty().set(original);
                try {
                    settings.save();
                } catch (Exception ignored) {
                }
            }).get(10, TimeUnit.SECONDS);
        }
    }

    private static JFXButton findThinkButton(Node root) {
        if (root instanceof JFXButton btn
                && btn.getStyleClass().contains("ai-toolbar-icon-btn")
                && btn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
            return btn;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                JFXButton found = findThinkButton(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /// Finds the clickable level row (the RipplerContainer wrapping the Label with the level name).
    private static Node findLevelRow(Node root, String level) {
        if (root instanceof org.jackhuang.hmcl.ui.construct.RipplerContainer rippler) {
            if (containsLabelText(rippler, level)) {
                return rippler;
            }
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findLevelRow(child, level);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsLabelText(Node root, String text) {
        if (root instanceof Label label && text.equals(label.getText())) {
            return true;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsLabelText(child, text)) return true;
            }
        }
        return false;
    }

    private static MouseEvent syntheticClick(Node target) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }
}
