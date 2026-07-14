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
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// FX component test for the inline tool-call card and the tool-call group card after the B4
/// CollapseHeader unification: a running card advertises no toggle (chevron hidden), completion
/// with a result reveals the chevron and keeps the result collapsed until the whole-row header
/// is toggled, oversized results are truncated at 4000 chars (BF 2-3), and the group card counts
/// its children in the "已调用 N 个工具" summary. Direct method / toggle() injection only (A7).
public final class ToolCardFxTest {

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

    private static <T> T onFxAnd(java.util.concurrent.Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get(5, TimeUnit.SECONDS);
    }

    /// The collapsible result label = the card's direct child carrying .ai-tool-card-result
    /// (the progress label inside the nested progress box shares the class, so a subtree
    /// lookup would be ambiguous).
    private static Label directResultLabel(AIMainPage.ToolCard card) {
        return (Label) card.getChildren().stream()
                .filter(n -> n instanceof Label && n.getStyleClass().contains("ai-tool-card-result"))
                .findFirst().orElseThrow();
    }

    @Test
    public void chevronAppearsOnCompletionAndHeaderTogglesResult() throws Exception {
        AtomicReference<AIMainPage.ToolCard> cardRef = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            cardRef.set(new AIMainPage.ToolCard("instance"));
            StackPane root = new StackPane(cardRef.get());
            root.setPrefSize(760, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        AIMainPage.ToolCard card = cardRef.get();
        FxRobot robot = new FxRobot();

        CollapseHeader header = robot.from(card).lookup(".ai-collapse-header").queryAs(CollapseHeader.class);
        // NOT a .ai-tool-card-result lookup: the (hidden) progress label shares that style class,
        // so select the result label among the card's DIRECT children instead.
        Label result = directResultLabel(card);
        assertFalse(header.getChevron().isVisible(), "a running card must not advertise a toggle");
        assertFalse(result.isVisible(), "no result while the tool is running");

        // Clicking the (now whole-row) header while the tool is still running shows nothing —
        // exactly like the pre-refactor click-guard on an empty result.
        onFxAnd(() -> {
            header.toggle();
            return null;
        });
        assertFalse(result.isVisible(), "toggling an unfinished card must not reveal an empty result");

        onFxAnd(() -> {
            card.complete(true, "结果");
            return null;
        });
        assertTrue(header.getChevron().isVisible(), "an expandable completed card shows its chevron");
        assertTrue(header.getChevron().isManaged());
        assertFalse(result.isVisible(), "a freshly-completed card starts with the result collapsed");
        assertTrue(card.getStyleClass().contains("ai-tool-card-ok"), "success completion tags the ok class");

        // B3 redesign: compact capsule header (fit-content). The success/failure state is now shown
        // as a themed SVG status icon in the header's leading slot (replacing the old ✓/✗ text mark),
        // so a successful completion carries a CHECK_CIRCLE icon there.
        assertTrue(header.getStyleClass().contains("ai-collapse-header-compact"),
                "the tool card uses the compact capsule header");
        assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, header.getMaxWidth(), 0.01,
                "the compact header hugs its content (fit-content)");
        Node leading = header.getLeadingIcon();
        assertTrue(leading instanceof SVGContainer,
                "a completed card shows an SVG status icon in the leading slot, got: " + leading);
        assertEquals(SVG.CHECK_CIRCLE, ((SVGContainer) leading).getIcon(),
                "a successful completion shows the CHECK_CIRCLE status icon");

        onFxAnd(() -> {
            header.toggle();
            return null;
        });
        assertTrue(result.isVisible(), "header toggle expands the stored result");
        assertTrue(result.isManaged());
        assertEquals("结果", result.getText());

        onFxAnd(() -> {
            header.toggle();
            return null;
        });
        assertFalse(result.isVisible(), "second toggle collapses the result again");
        assertFalse(result.isManaged(), "collapsed result must not take layout space");
    }

    @Test
    public void oversizedResultIsTruncatedAt4000Chars() throws Exception {
        String longResult = "x".repeat(5000);
        AIMainPage.ToolCard card = onFxAnd(() -> {
            AIMainPage.ToolCard c = new AIMainPage.ToolCard("search");
            c.complete(false, longResult);
            return c;
        });
        Label result = directResultLabel(card);
        assertTrue(result.getText().startsWith("x".repeat(4000)), "first 4000 chars are kept verbatim");
        assertTrue(result.getText().contains("已截断"), "truncation is called out to the user");
        assertTrue(result.getText().length() < 4200, "UI-side cap keeps the label small");
        assertTrue(card.getStyleClass().contains("ai-tool-card-fail"), "failure completion tags the fail class");
    }

    @Test
    public void groupCardCountsAdditionsAndTogglesItsBody() throws Exception {
        AtomicReference<AIMainPage.ToolCallGroupCard> groupRef = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            AIMainPage.ToolCallGroupCard group = new AIMainPage.ToolCallGroupCard();
            for (int i = 0; i < 3; i++) {
                AIMainPage.ToolCard card = new AIMainPage.ToolCard("tool" + i);
                card.complete(true, "r" + i);
                group.add(card);
            }
            groupRef.set(group);
            StackPane root = new StackPane(group);
            root.setPrefSize(760, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        AIMainPage.ToolCallGroupCard group = groupRef.get();
        FxRobot robot = new FxRobot();

        CollapseHeader header = (CollapseHeader) group.getChildren().get(0);
        assertEquals("已调用 3 个工具", header.getTitleLabel().getText(), "summary counts every add()");
        // B3 rich summary: each completed tool NAME followed by a themed SVG status icon, joined by
        // " · " and rendered as a TextFlow (installed via setSummaryNode). Assert the names are all
        // present and every one of the three successes shows a CHECK_CIRCLE icon.
        TextFlow summary = (TextFlow) header.getSummaryNode();
        String summaryText = summary.getChildren().stream()
                .filter(n -> n instanceof Text).map(n -> ((Text) n).getText())
                .collect(Collectors.joining());
        assertTrue(summaryText.contains("tool0") && summaryText.contains("tool1")
                        && summaryText.contains("tool2"),
                "each tool name is listed in the rich summary, got: " + summaryText);
        long okIcons = summary.getChildren().stream()
                .filter(n -> n instanceof SVGContainer
                        && ((SVGContainer) n).getIcon() == SVG.CHECK_CIRCLE)
                .count();
        assertEquals(3, okIcons, "all three successful calls show a CHECK_CIRCLE status icon");

        VBox body = (VBox) group.getChildren().get(1);
        assertFalse(body.isVisible(), "group starts collapsed");
        assertFalse(body.isManaged());
        assertEquals(3, body.getChildren().size(), "all three cards re-parented into the body");

        WaitForAsyncUtils.asyncFx(header::toggle).get(5, TimeUnit.SECONDS);
        assertTrue(body.isVisible(), "header toggle unfolds the grouped cards");
        assertTrue(body.isManaged());

        // the nested cards keep their own collapse behavior inside the group
        CollapseHeader nested = robot.from(body.getChildren().get(0)).lookup(".ai-collapse-header")
                .queryAs(CollapseHeader.class);
        assertTrue(nested.getChevron().isVisible(), "nested completed card keeps its own chevron");
    }

    /// A run longer than three tools folds the remainder into a "+N" tail (B3 rich summary).
    @Test
    public void groupSummaryFoldsOverflowIntoPlusN() throws Exception {
        AIMainPage.ToolCallGroupCard group = onFxAnd(() -> {
            AIMainPage.ToolCallGroupCard g = new AIMainPage.ToolCallGroupCard();
            for (int i = 0; i < 5; i++) {
                AIMainPage.ToolCard card = new AIMainPage.ToolCard("t" + i);
                card.complete(i != 1, "r" + i); // t1 fails → ✗ mark
                g.add(card);
            }
            return g;
        });
        CollapseHeader header = (CollapseHeader) group.getChildren().get(0);
        TextFlow summary = (TextFlow) header.getSummaryNode();
        String summaryText = summary.getChildren().stream()
                .filter(n -> n instanceof Text).map(n -> ((Text) n).getText())
                .collect(Collectors.joining());
        assertTrue(summaryText.contains("t0") && summaryText.contains("t1") && summaryText.contains("t2"),
                "the first three tools are named, got: " + summaryText);
        assertTrue(summaryText.contains("+2"), "the remaining two tools fold into a +N tail");
        // t0 and t2 succeeded (CHECK_CIRCLE); t1 failed (CANCEL) — only the first three are shown.
        long okIcons = summary.getChildren().stream()
                .filter(n -> n instanceof SVGContainer && ((SVGContainer) n).getIcon() == SVG.CHECK_CIRCLE)
                .count();
        long failIcons = summary.getChildren().stream()
                .filter(n -> n instanceof SVGContainer && ((SVGContainer) n).getIcon() == SVG.CANCEL)
                .count();
        assertEquals(2, okIcons, "t0 and t2 show a success icon");
        assertEquals(1, failIcons, "t1 shows a failure icon");
    }
}
