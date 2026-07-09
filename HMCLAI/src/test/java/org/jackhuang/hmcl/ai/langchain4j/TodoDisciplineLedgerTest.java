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
package org.jackhuang.hmcl.ai.langchain4j;

import org.jackhuang.hmcl.ai.langchain4j.LangChain4jChatAdapter.LoopGuardState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Direct unit coverage for the todo-discipline ledger (real-trace failure mode: the model built a
/// 9-item checklist, marked only 1 item "in_progress", hit a problem, and abandoned the whole list
/// for an unrelated new one — the original 9 items, some already actually finished, were never
/// checked off). Exercises {@link LangChain4jChatAdapter#parseTodoWriteArguments} and
/// {@link LangChain4jChatAdapter#updateTodoLedger} directly, independent of the full streaming loop
/// (see {@link TraceLoopEndToEndTest} for end-to-end coverage of the guard actually firing inside
/// {@code streamTurn}).
public final class TodoDisciplineLedgerTest {

    private static String todoWriteArgs(String todosArrayJson) {
        // Mirrors HMCL's real TodoWriteTool schema: the top-level arguments object nests the todo
        // array as a JSON-ARRAY-ENCODED STRING (not a real nested array), because the structured
        // schema parser only supports flat fields.
        return "{\"todos\":\"" + todosArrayJson.replace("\"", "\\\"") + "\"}";
    }

    @Test
    public void parseTodoWriteArgumentsUnwrapsTheDoubleEncodedStringSchema() {
        String args = todoWriteArgs("[{\"content\":\"安装 Mods\",\"status\":\"in_progress\"},"
                + "{\"content\":\"验证安装结果\",\"status\":\"pending\"}]");

        Map<String, String> parsed = LangChain4jChatAdapter.parseTodoWriteArguments(args);

        assertEquals(2, parsed.size());
        assertEquals("in_progress", parsed.get("安装 Mods"));
        assertEquals("pending", parsed.get("验证安装结果"));
    }

    @Test
    public void parseTodoWriteArgumentsTakesUpItemOrderFromTheInputArray() {
        String args = todoWriteArgs("[{\"content\":\"first\",\"status\":\"done\"},"
                + "{\"content\":\"second\",\"status\":\"pending\"},"
                + "{\"content\":\"third\",\"status\":\"in_progress\"}]");

        assertEquals(List.of("first", "second", "third"),
                List.copyOf(LangChain4jChatAdapter.parseTodoWriteArguments(args).keySet()),
                "insertion order must be preserved so dropped-item diffs stay deterministic");
    }

    @Test
    public void parseTodoWriteArgumentsAlsoAcceptsARealNestedArray() {
        // Tolerate a provider that skips the string-encoding and sends `todos` as a real array.
        String args = "{\"todos\":[{\"content\":\"A\",\"status\":\"pending\"}]}";

        Map<String, String> parsed = LangChain4jChatAdapter.parseTodoWriteArguments(args);

        assertEquals(1, parsed.size());
        assertEquals("pending", parsed.get("A"));
    }

    @Test
    public void parseTodoWriteArgumentsReturnsEmptyForUnparseableOrAbsentInput() {
        assertTrue(LangChain4jChatAdapter.parseTodoWriteArguments(null).isEmpty());
        assertTrue(LangChain4jChatAdapter.parseTodoWriteArguments("").isEmpty());
        assertTrue(LangChain4jChatAdapter.parseTodoWriteArguments("not json").isEmpty());
        assertTrue(LangChain4jChatAdapter.parseTodoWriteArguments("{\"other\":\"field\"}").isEmpty());
    }

    @Test
    public void updateTodoLedgerEstablishesOutstandingItemsOnTheFirstCall() {
        LoopGuardState state = new LoopGuardState();
        state.cyclesSinceTodoUpdate = 7; // pretend some cycles already passed with nothing outstanding

        List<String> dropped = LangChain4jChatAdapter.updateTodoLedger(state,
                todoWriteArgs("[{\"content\":\"A\",\"status\":\"pending\"},{\"content\":\"B\",\"status\":\"in_progress\"}]"));

        assertTrue(dropped.isEmpty(), "nothing was outstanding before, so nothing can have been dropped");
        assertEquals(java.util.Set.of("A", "B"), state.outstandingTodoContents);
        assertEquals(0, state.cyclesSinceTodoUpdate, "any successful todo_write call resets the staleness counter");
    }

    /// The real trace's own bug: an item that was outstanding in the OLD list and is missing
    /// entirely from the NEW list (neither carried over as still-outstanding nor marked "done") must
    /// be reported as dropped.
    @Test
    public void updateTodoLedgerReportsItemsThatVanishWithoutBeingMarkedDoneOrCarriedOver() {
        LoopGuardState state = new LoopGuardState();
        LangChain4jChatAdapter.updateTodoLedger(state, todoWriteArgs(
                "[{\"content\":\"装 Fabric\",\"status\":\"done\"},"
                + "{\"content\":\"装 Sodium\",\"status\":\"in_progress\"},"
                + "{\"content\":\"装 Lithium\",\"status\":\"pending\"}]"));
        assertEquals(2, state.outstandingTodoContents.size(), "只有done之外的两项仍未完成");

        // The plan "changes" mid-task, but instead of updating the existing list, the model replaces
        // it wholesale with an unrelated new one — "装 Sodium" and "装 Lithium" both vanish.
        List<String> dropped = LangChain4jChatAdapter.updateTodoLedger(state,
                todoWriteArgs("[{\"content\":\"换个思路重新装\",\"status\":\"in_progress\"}]"));

        assertEquals(List.of("装 Sodium", "装 Lithium"), dropped,
                "both still-outstanding items silently disappeared from the new list");
        assertEquals(java.util.Set.of("换个思路重新装"), state.outstandingTodoContents,
                "the ledger reflects the new list's own outstanding items regardless of the drop");
    }

    /// A legitimate update — marking a previously-outstanding item "done", or simply carrying it
    /// over unchanged — must NOT be reported as a drop.
    @Test
    public void updateTodoLedgerDoesNotReportItemsThatAreProperlyCarriedOverOrMarkedDone() {
        LoopGuardState state = new LoopGuardState();
        LangChain4jChatAdapter.updateTodoLedger(state, todoWriteArgs(
                "[{\"content\":\"A\",\"status\":\"in_progress\"},{\"content\":\"B\",\"status\":\"pending\"}]"));

        List<String> dropped = LangChain4jChatAdapter.updateTodoLedger(state, todoWriteArgs(
                "[{\"content\":\"A\",\"status\":\"done\"},{\"content\":\"B\",\"status\":\"in_progress\"}]"));

        assertTrue(dropped.isEmpty(), "A was marked done and B was carried over — nothing vanished");
        assertEquals(java.util.Set.of("B"), state.outstandingTodoContents,
                "A is done so it drops off the OUTSTANDING set, but that is not the same as being dropped from the list");
    }

    @Test
    public void updateTodoLedgerResetsTheStalenessCounterEvenWhenNothingIsDropped() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingTodoContents.add("A");
        state.cyclesSinceTodoUpdate = 14;

        LangChain4jChatAdapter.updateTodoLedger(state,
                todoWriteArgs("[{\"content\":\"A\",\"status\":\"done\"}]"));

        assertEquals(0, state.cyclesSinceTodoUpdate,
                "touching the list at all means the model didn't neglect it this cycle");
    }
}
