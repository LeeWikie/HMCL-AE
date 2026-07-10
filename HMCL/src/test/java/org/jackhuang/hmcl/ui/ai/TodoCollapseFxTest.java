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

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// §B B7 regression gate (blueprint V5-5): the pinned TODO checklist card must stay
/// collapsible after the B4 CollapseHeader unification, and the expand/collapse state must
/// survive the card rebuild that every `todo_write` triggers (`todoExpanded` carry-over).
/// Injection is A7-compliant: direct `toggle()` calls on the FX thread, no physical robot.
public final class TodoCollapseFxTest {

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

    @Test
    public void todoCardCollapsesAndStateSurvivesRebuild() throws Exception {
        AIMainPage page = showPage();
        List<TodoItem> todos = List.of(
                new TodoItem("确认实例", "done"),
                new TodoItem("安装光影", "in_progress"),
                new TodoItem("验证效果", "pending"));

        invoke(page, "updateTodoCard", new Class<?>[]{List.class}, todos);
        WaitForAsyncUtils.waitForFxEvents();

        VBox container = (VBox) getField(page, "todoCardContainer");
        assertTrue(container.isVisible() && container.isManaged(), "todo card must be shown");
        CollapseHeader header = headerOf(container);
        Node body = bodyOf(container);
        assertTrue(header.isExpanded(), "todo card starts expanded");
        assertTrue(body.isVisible() && body.isManaged(), "expanded card shows its checklist body");
        assertEquals(3, ((VBox) body).getChildren().size(), "one row per todo item");

        // Collapse via the whole-row header hot zone's action (A7: direct method injection).
        WaitForAsyncUtils.asyncFx(header::toggle).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(header.isExpanded(), "toggle must collapse the card");
        assertFalse(body.isVisible(), "collapsed card hides its body");
        assertFalse(body.isManaged(), "collapsed body must release its layout space");

        // Every todo_write rebuilds the card; the collapsed state must carry over (§B B7).
        invoke(page, "updateTodoCard", new Class<?>[]{List.class}, todos);
        WaitForAsyncUtils.waitForFxEvents();
        CollapseHeader rebuiltHeader = headerOf(container);
        Node rebuiltBody = bodyOf(container);
        assertNotSame(header, rebuiltHeader, "todo_write rebuilds the header/body pair");
        assertFalse(rebuiltHeader.isExpanded(), "collapsed state must survive the rebuild");
        assertFalse(rebuiltBody.isVisible() || rebuiltBody.isManaged(),
                "rebuilt card must come back still collapsed");

        // And it must expand again on demand — collapse capability, not a one-way hide.
        WaitForAsyncUtils.asyncFx(rebuiltHeader::toggle).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(rebuiltHeader.isExpanded(), "toggle must re-expand the rebuilt card");
        assertTrue(rebuiltBody.isVisible() && rebuiltBody.isManaged(),
                "re-expanded card shows the checklist again");
    }

    private static CollapseHeader headerOf(VBox container) {
        VBox card = (VBox) container.getChildren().get(0);
        return (CollapseHeader) card.getChildren().get(0);
    }

    private static Node bodyOf(VBox container) {
        VBox card = (VBox) container.getChildren().get(0);
        return card.getChildren().get(1);
    }
}
