/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [TodoWriteTool]'s non-blocking discipline echo (harness D5): the tool now surfaces
/// rule violations the model declared — more than one `in_progress` item, and unfinished items
/// silently dropped from the previous list — as `[WARNING]` lines appended to an otherwise
/// successful result, so the model can self-correct immediately instead of 15 cycles later.
///
/// The [TodoWriteTool.TodoUiHandler] is a no-op sink: these tests exercise the pure bookkeeping,
/// no JavaFX toolkit required.
public final class TodoWriteToolTest {

    private static final TodoWriteTool.TodoUiHandler NOOP = todos -> {
    };

    private static String todosJson(String... contentStatusPairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < contentStatusPairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"content\":\"").append(contentStatusPairs[i])
                    .append("\",\"status\":\"").append(contentStatusPairs[i + 1]).append("\"}");
        }
        return sb.append("]").toString();
    }

    @Test
    void twoInProgressItemsAppendWarning() {
        TodoWriteTool tool = new TodoWriteTool(NOOP);
        ToolResult result = tool.execute(Map.of("todos",
                todosJson("Install mods", "in_progress", "Verify install", "in_progress")));

        assertTrue(result.isSuccess(), () -> "warning must NOT fail the call: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("[WARNING]"), () -> "expected an in_progress warning: " + out);
        assertTrue(out.contains("in_progress"), () -> "warning must name the offending status: " + out);
    }

    @Test
    void singleInProgressItemHasNoWarning() {
        TodoWriteTool tool = new TodoWriteTool(NOOP);
        ToolResult result = tool.execute(Map.of("todos",
                todosJson("Install mods", "in_progress", "Verify install", "pending")));

        assertTrue(result.isSuccess(), () -> "expected success: " + result.getError());
        assertFalse(result.getOutput().contains("[WARNING]"),
                () -> "a well-formed list must not warn: " + result.getOutput());
    }

    @Test
    void droppingAnUnfinishedItemAppendsWarning() {
        TodoWriteTool tool = new TodoWriteTool(NOOP);
        // First plan: two items, one still pending (unfinished).
        tool.execute(Map.of("todos",
                todosJson("Install mods", "in_progress", "Verify install", "pending")));
        // Second call silently drops the still-unfinished "Verify install".
        ToolResult result = tool.execute(Map.of("todos",
                todosJson("Install mods", "done")));

        assertTrue(result.isSuccess(), () -> "warning must NOT fail the call: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("[WARNING]"), () -> "expected a dropped-item warning: " + out);
        assertTrue(out.contains("Verify install"),
                () -> "warning must name the dropped unfinished item: " + out);
    }

    @Test
    void carryingAFinishedItemForwardDoesNotWarn() {
        TodoWriteTool tool = new TodoWriteTool(NOOP);
        tool.execute(Map.of("todos",
                todosJson("Install mods", "in_progress", "Verify install", "pending")));
        // Complete the first, advance the second: nothing unfinished was dropped.
        ToolResult result = tool.execute(Map.of("todos",
                todosJson("Install mods", "done", "Verify install", "in_progress")));

        assertTrue(result.isSuccess(), () -> "expected success: " + result.getError());
        assertFalse(result.getOutput().contains("[WARNING]"),
                () -> "advancing the plan legitimately must not warn: " + result.getOutput());
    }

    @Test
    void emptyTodosStillFailsWithGuidance() {
        TodoWriteTool tool = new TodoWriteTool(NOOP);
        ToolResult result = tool.execute(Map.of("todos", "[]"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("todos"), () -> "unexpected message: " + result.getError());
    }
}
