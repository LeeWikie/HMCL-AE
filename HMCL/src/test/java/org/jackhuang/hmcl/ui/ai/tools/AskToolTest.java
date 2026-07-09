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
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers {@link AskTool}'s bounded wait on the ask-panel future: a normal answer still succeeds,
/// an explicit cancellation still fails the same way as before, and — the fix under test — a
/// panel that NEVER gets answered (e.g. the UI never resolves it) times out and returns the exact
/// same "user did not respond" failure instead of hanging the calling thread forever.
///
/// The package-private constructor lets the test inject a short timeout (milliseconds) instead of
/// the production 10-minute default, so the never-answered case completes almost instantly rather
/// than actually waiting out the real timeout.
public final class AskToolTest {

    /// A single-question 'questions' parameter shaped exactly as the tool's own input schema
    /// describes it (a JSON array of {question, type, options, allowCustom} objects) — the
    /// map-of-maps shape {@code parseQuestions} expects, NOT a list of {@link AskTool.Question}.
    private static List<Map<String, Object>> oneQuestion() {
        return List.of(Map.of(
                "question", "安装哪个版本?",
                "type", "single",
                "options", List.of("1.21.1", "1.20.1"),
                "allowCustom", true));
    }

    @Test
    @Timeout(10)
    void answeredBeforeTimeoutSucceeds() throws Exception {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        AskTool tool = new AskTool(questions -> future, 200, TimeUnit.MILLISECONDS);

        future.complete(List.of("1.21.1"));
        ToolResult result = tool.execute(Map.of("questions", oneQuestion()));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("1.21.1"));
    }

    @Test
    @Timeout(10)
    void explicitCancellationFails() {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        AskTool tool = new AskTool(questions -> future, 5, TimeUnit.SECONDS);

        future.cancel(true);
        ToolResult result = tool.execute(Map.of("questions", oneQuestion()));

        assertFalse(result.isSuccess());
        assertEquals("The user cancelled the questions (did not answer).", result.getError());
    }

    /// The fix: a future that is NEVER completed (the UI never resolves the panel — the user
    /// navigated away, closed the window improperly, or a UI bug left it un-dismissable) must not
    /// hang {@link AskTool#execute} forever. With a short injected timeout, execute() must return
    /// within a small bounded window and the result must be the same "did not respond" failure
    /// already used for an explicit cancellation, not a new/different message.
    @Test
    @Timeout(10)
    void neverAnsweredTimesOutAndReturnsCancellationResult() {
        CompletableFuture<List<String>> future = new CompletableFuture<>(); // never completed
        AskTool tool = new AskTool(questions -> future, 150, TimeUnit.MILLISECONDS);

        long start = System.nanoTime();
        ToolResult result = tool.execute(Map.of("questions", oneQuestion()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.isSuccess());
        assertEquals("The user cancelled the questions (did not answer).", result.getError());
        // Bounded: must return promptly after the injected timeout, not hang indefinitely.
        assertTrue(elapsedMs < 5_000, "execute() took " + elapsedMs + "ms, expected a bounded timeout");
        // The tool must have cancelled the future itself, so a UI-side completion listener (the
        // real AIMainPage wiring) would be notified and could dismiss the stale panel.
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());
    }
}
