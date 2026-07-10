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

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// FX component test for the reasoning ("思考过程") card (#53): streams append into the
/// content label, and the shared CollapseHeader toggles the collapsed/expanded state
/// (whole-row hot zone since the B4 refactor — driven via CollapseHeader#toggle(), A7).
/// Event-injection pipeline (see MarkdownCodeCopyFxTest for the pipeline rationale).
public final class ReasoningCardFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        FxToolkit.registerPrimaryStage();
        // CollapseHeader's chevron animation touches AnimationUtils, whose static init reads
        // SettingsManager — seed it so this test doesn't depend on run order.
        AiMainPageFxTestSupport.ensureSettingsManagerLoaded();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @Test
    public void streamsAppendAndHeaderTogglesCollapse() throws Exception {
        AIMainPage.ReasoningCard[] cardRef = new AIMainPage.ReasoningCard[1];
        FxToolkit.setupSceneRoot(() -> {
            cardRef[0] = new AIMainPage.ReasoningCard("让我想想：", true);
            StackPane root = new StackPane(cardRef[0]);
            root.setPrefSize(500, 300);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        AIMainPage.ReasoningCard card = cardRef[0];
        FxRobot robot = new FxRobot();

        Label content = robot.from(card).lookup(".ai-caption").queryAs(Label.class);
        assertTrue(content.isVisible(), "starts expanded while streaming");
        assertEquals("让我想想：", content.getText());

        // streaming tokens append into the same label
        WaitForAsyncUtils.asyncFx(() -> {
            card.append("用户想装");
            card.append("光影。");
        }).get(5, TimeUnit.SECONDS);
        assertEquals("让我想想：用户想装光影。", content.getText());

        // answer started → the page collapses the card programmatically
        WaitForAsyncUtils.asyncFx(() -> card.setExpanded(false)).get(5, TimeUnit.SECONDS);
        assertFalse(content.isVisible(), "collapsed once the answer starts");
        assertFalse(content.isManaged(), "collapsed content must not take layout space");

        // user clicks the header (whole-row hot zone) to peek at the reasoning again
        CollapseHeader header = robot.from(card).lookup(".ai-collapse-header").queryAs(CollapseHeader.class);
        WaitForAsyncUtils.asyncFx(header::toggle).get(5, TimeUnit.SECONDS);
        assertTrue(content.isVisible(), "header click expands");

        WaitForAsyncUtils.asyncFx(header::toggle).get(5, TimeUnit.SECONDS);
        assertFalse(content.isVisible(), "second click collapses again");
    }
}
