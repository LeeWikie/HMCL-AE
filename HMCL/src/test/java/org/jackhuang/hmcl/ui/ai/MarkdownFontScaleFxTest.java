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
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies the Markdown font pipeline is fully CSS-driven: MarkdownMessageView no longer calls
/// setFont anywhere, so the chat font-size setting — expressed as the `.ai-chat-font-*` class on
/// the message list, which sets a px base on `.ai-bubble` — must cascade into Markdown body text,
/// headings (em-relative md-h1..md-h4 rules), bold runs (md-bold) and code runs (md-code-text /
/// md-code-inline, Monospaced at 0.92em). Regression test for the "字号设置对 Markdown 无效" bug:
/// previously every Text carried a hard-coded 13px font that ignored the setting entirely.
///
/// Runs headed on a developer machine; self-skips where no display is available (headless CI).
public final class MarkdownFontScaleFxTest {

    private static final String MARKDOWN = "# 标题\n\n正文 **粗体** *斜体* `code`\n\n```java\nint a = 1;\n```\n";

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    /// Builds the production class chain: messageList (ai-message-list + the font-scale class)
    /// -> mdView (ai-bubble, as AIMainPage attaches it) — and loads root.css so the real
    /// `.ai-chat-font-* .ai-bubble` px rules and the em-relative md-* rules apply.
    private static MarkdownMessageView showMarkdown(String fontScaleClass) throws Exception {
        MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create(MARKDOWN, 710);
            assertNotNull(view, "heading + emphasis + code markdown must be recognised as markdown");
            view.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");
            viewHolder[0] = view;
            VBox messageList = new VBox(view);
            messageList.getStyleClass().addAll("ai-message-list", fontScaleClass);
            StackPane root = new StackPane(messageList);
            root.setPrefSize(800, 600);
            root.getStylesheets().add(Objects.requireNonNull(
                    MarkdownFontScaleFxTest.class.getResource("/assets/css/root.css")).toExternalForm());
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        return viewHolder[0];
    }

    @Test
    public void largeFontScaleCascadesIntoMarkdownText() throws Exception {
        MarkdownMessageView view = showMarkdown("ai-chat-font-large");

        // Body text follows the 16px large base directly (no more hard-coded 13px).
        Text body = findTextStartingWith(view, "正文");
        assertNotNull(body, "must find the body text run");
        assertEquals(16, body.getFont().getSize(), 0.1,
                "body text must pick up the 16px base from .ai-chat-font-large .ai-bubble");

        // Heading = 1.4em of the 16px base ≈ 22.4.
        Text heading = findTextStartingWith(findHeadingFlow(view), "标题");
        assertNotNull(heading, "must find the h1 text run");
        assertEquals(16 * 1.4, heading.getFont().getSize(), 0.5,
                "h1 must scale em-relative (1.4em) off the 16px base");
        assertTrue(heading.getFont().getStyle().toLowerCase().contains("bold"),
                "h1 text must render bold via the md-h1 rule (was: " + heading.getFont().getStyle() + ")");

        // Bold / italic runs style via md-bold / md-em classes, not setFont.
        Text bold = findTextStartingWith(view, "粗体");
        assertNotNull(bold, "must find the bold text run");
        assertTrue(bold.getFont().getStyle().toLowerCase().contains("bold"),
                "**bold** must render bold via the md-bold rule (was: " + bold.getFont().getStyle() + ")");
        Text italic = findTextStartingWith(view, "斜体");
        assertNotNull(italic, "must find the italic text run");
        assertTrue(italic.getFont().getStyle().toLowerCase().contains("italic"),
                "*italic* must render italic via the md-em rule (was: " + italic.getFont().getStyle() + ")");

        // Inline code: Monospaced at 0.92em of the base.
        Text code = findTextStartingWith(view, "code");
        assertNotNull(code, "must find the inline code run");
        assertEquals("Monospaced", code.getFont().getFamily(),
                "inline code must use the Monospaced logical family via the md-code-inline rule");
        assertEquals(16 * 0.92, code.getFont().getSize(), 0.5,
                "inline code must scale em-relative (0.92em) off the 16px base");

        // Fenced code block runs (md-code-text) follow the same Monospaced + 0.92em rule.
        Text codeBlockRun = findTextStartingWith(view, "int");
        assertNotNull(codeBlockRun, "must find a code-block text run");
        assertEquals("Monospaced", codeBlockRun.getFont().getFamily(),
                "code block text must use the Monospaced logical family via the md-code-text rule");
        assertEquals(16 * 0.92, codeBlockRun.getFont().getSize(), 0.5,
                "code block text must scale em-relative (0.92em) off the 16px base");
    }

    @Test
    public void smallFontScaleCascadesIntoMarkdownText() throws Exception {
        MarkdownMessageView view = showMarkdown("ai-chat-font-small");

        Text body = findTextStartingWith(view, "正文");
        assertNotNull(body, "must find the body text run");
        assertEquals(12, body.getFont().getSize(), 0.1,
                "body text must pick up the 12px base from .ai-chat-font-small .ai-bubble");

        Text heading = findTextStartingWith(findHeadingFlow(view), "标题");
        assertNotNull(heading, "must find the h1 text run");
        assertEquals(12 * 1.4, heading.getFont().getSize(), 0.5,
                "h1 must scale em-relative (1.4em) off the 12px base");
    }

    /// Finds the TextFlow carrying an md-h* class — the heading block styledText() built.
    private static TextFlow findHeadingFlow(Parent root) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof TextFlow flow
                    && flow.getStyleClass().stream().anyMatch(c -> c.startsWith("md-h"))) {
                return flow;
            }
            if (child instanceof Parent parent) {
                TextFlow found = findHeadingFlow(parent);
                if (found != null) return found;
            }
        }
        return null;
    }

    /// Depth-first search for the first Text node whose content starts with the given prefix,
    /// so assertions target the actual rendered runs rather than re-deriving them from source.
    private static Text findTextStartingWith(Parent root, String prefix) {
        if (root == null) return null;
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Text t && t.getText() != null && t.getText().startsWith(prefix)) {
                return t;
            }
            if (child instanceof Parent parent) {
                Text found = findTextStartingWith(parent, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }
}
