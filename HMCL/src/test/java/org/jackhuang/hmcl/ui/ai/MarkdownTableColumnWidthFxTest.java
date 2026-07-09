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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Regression test for a table-column-collapse bug: without a per-column minimum width,
/// GridPane's auto-sizing could shrink a short-text column (e.g. "release") narrower than a
/// single glyph once the row's total preferred width exceeded MarkdownMessageView.MAX_WIDTH,
/// causing TextFlow to word-wrap mid-character. Asserts the built table grid carries one
/// ColumnConstraints per column with a positive floor, and is wrapped in a horizontally
/// scrollable container scoped to the table row whose vertical scrollbar policy is NEVER for
/// a short table and AS_NEEDED (behind a fixed max-height cap) once the table has enough rows
/// to otherwise render as one very tall unbroken block.
public final class MarkdownTableColumnWidthFxTest {

    // Wide enough columns to push the row's natural width past MAX_WIDTH (710), forcing
    // GridPane to shrink columns absent a floor.
    private static final String TABLE_MARKDOWN = "| Channel | Description | Status |\n"
            + "|---|---|---|\n"
            + "| release | This is a long description column meant to occupy most of the row width | on |\n"
            + "| beta | Another sufficiently long description to push the table past its max width | off |\n";

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

    @Test
    public void tableColumnsGetAMinimumWidthFloorAndScrollHorizontally() throws Exception {
        MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create(TABLE_MARKDOWN);
            assertNotNull(view, "GFM table markdown must be recognised as markdown");
            viewHolder[0] = view;
            StackPane root = new StackPane(view);
            root.setPrefSize(600, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        MarkdownMessageView view = viewHolder[0];
        // The table is the sole rendered block for this input.
        javafx.scene.Node rendered = view.getChildren().get(0);

        ScrollPane scrollPane = assertInstanceOf(ScrollPane.class, rendered,
                "table must be wrapped in a ScrollPane so an overflowing table scrolls sideways "
                        + "within its own row instead of overflowing the bubble");
        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scrollPane.getVbarPolicy(),
                "a short table (well under the row-count height cap) must not show a vertical "
                        + "scrollbar it doesn't need");
        assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scrollPane.getHbarPolicy(),
                "table should only show a horizontal scrollbar when it actually overflows");
        assertTrue(!scrollPane.isFitToWidth(),
                "fitToWidth would force the grid back to viewport width, defeating column minimums");

        GridPane grid = assertInstanceOf(GridPane.class, scrollPane.getContent(),
                "ScrollPane must wrap the table GridPane");

        int columnCount = 3;
        assertEquals(columnCount, grid.getColumnConstraints().size(),
                "grid must carry one ColumnConstraints entry per table column");
        for (ColumnConstraints cc : grid.getColumnConstraints()) {
            assertTrue(cc.getMinWidth() > 0,
                    "every column must have a positive minimum width floor so a short-content "
                            + "column can never be squeezed narrower than a single glyph");
        }

        // Direct behavioural check (not just structural): find the "release" cell's TextFlow
        // and confirm it actually laid out as ONE line. Mid-character wrapping would stack
        // 7 single-glyph lines and blow up the TextFlow's rendered height; a real single line
        // stays close to one line-height (~13px font + 2px line spacing).
        TextFlow releaseFlow = findCellFlow(grid, "release");
        assertNotNull(releaseFlow, "must find the rendered 'release' table cell");
        double height = releaseFlow.getBoundsInLocal().getHeight();
        assertTrue(height < 24,
                "'release' cell must render as a single line (height=" + height
                        + "px); a height this large means it wrapped one character per line");
    }

    // 1 header row + 10 data rows = 11 total rows, comfortably past the row-count-based
    // height-cap threshold that switches the table from a fixed (never-scrolling) block to a
    // capped, vertically scrollable one.
    private static String manyRowTableMarkdown() {
        StringBuilder sb = new StringBuilder("| Channel | Status |\n|---|---|\n");
        for (int i = 0; i < 10; i++) {
            sb.append("| row").append(i).append(" | on |\n");
        }
        return sb.toString();
    }

    @Test
    public void tallTableIsHeightCappedAndScrollsVertically() throws Exception {
        MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create(manyRowTableMarkdown());
            assertNotNull(view, "GFM table markdown must be recognised as markdown");
            viewHolder[0] = view;
            StackPane root = new StackPane(view);
            root.setPrefSize(600, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        MarkdownMessageView view = viewHolder[0];
        javafx.scene.Node rendered = view.getChildren().get(0);
        ScrollPane scrollPane = assertInstanceOf(ScrollPane.class, rendered,
                "table must be wrapped in a ScrollPane");

        assertTrue(scrollPane.getMaxHeight() > 0 && scrollPane.getMaxHeight() < 600,
                "a many-row table must be capped to a bounded pixel height (was "
                        + scrollPane.getMaxHeight() + "), not left to grow to one very tall "
                        + "unbroken block inside the non-virtualized message list");
        assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scrollPane.getVbarPolicy(),
                "a table taller than the height cap must gain its own vertical scrollbar "
                        + "instead of unconditionally suppressing it");
    }

    /// Walks the grid's children looking for a TextFlow whose concatenated Text content
    /// matches, so the test asserts against the actual rendered node rather than re-deriving
    /// it from the markdown source.
    private static TextFlow findCellFlow(GridPane grid, String text) {
        for (Node child : grid.getChildren()) {
            if (!(child instanceof HBox cell)) continue;
            for (Node inner : cell.getChildren()) {
                if (inner instanceof TextFlow flow) {
                    StringBuilder sb = new StringBuilder();
                    for (Node textNode : flow.getChildren()) {
                        if (textNode instanceof Text t) sb.append(t.getText());
                    }
                    if (text.equals(sb.toString())) return flow;
                }
            }
        }
        return null;
    }
}
