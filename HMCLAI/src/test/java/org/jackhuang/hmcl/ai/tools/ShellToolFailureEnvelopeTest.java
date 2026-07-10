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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertEnvelope;
import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertFailureEnvelope;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the ShellTool.execute failure-text contract: every model-visible failure — empty
/// command, timeout, process-start failure, interruption — must follow the unified
/// [`ToolFailures`] envelope with the right `Retryable` classification and carry its key data
/// (the timeout number, the shell name). The three exception/timeout paths are asserted through
/// the package-private static builders (same testability convention as buildDescription) so no
/// real process is spawned and the 60s timeout is not actually waited out; the empty-command
/// path is additionally exercised end-to-end through execute().
public final class ShellToolFailureEnvelopeTest {

    @Test
    void emptyCommandFailureIsRetryableYesEnvelope() {
        ToolResult result = ShellTool.emptyCommandFailure();
        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Retryable: yes"),
                () -> "empty command should be retryable yes (pure input fix): " + result.getError());
    }

    @Test
    void executeWithNoCommandReturnsEmptyCommandEnvelope() {
        // End-to-end through execute(): both a missing 'command' key and a blank one trim to empty
        // and must surface the well-formed envelope, not a bare string.
        assertFailureEnvelope(new ShellTool().execute(Map.of()));
        assertFailureEnvelope(new ShellTool().execute(Map.of("command", "   ")));
    }

    @Test
    void timeoutFailureIsRetryableLaterAndCarriesTheBudget() {
        ToolResult result = ShellTool.timeoutFailure(60);
        assertFailureEnvelope(result);
        String error = result.getError();
        assertTrue(error.contains("Retryable: later"),
                () -> "a fixed-budget timeout should be retryable later: " + error);
        assertTrue(error.contains("60s"),
                () -> "the timeout envelope must carry the boundary number: " + error);
    }

    @Test
    void startFailureIsRetryableNoAndNamesTheShellAndDetail() {
        ToolResult result = ShellTool.startFailure("PowerShell", "cannot run program \"powershell\"");
        assertFailureEnvelope(result);
        String error = result.getError();
        assertTrue(error.contains("Retryable: no"),
                () -> "a shell that cannot be launched is terminal (no): " + error);
        assertTrue(error.contains("PowerShell"),
                () -> "the start-failure envelope must name the shell: " + error);
        assertTrue(error.contains("cannot run program"),
                () -> "the start-failure envelope must carry the OS error detail: " + error);
    }

    @Test
    void startFailureToleratesNullDetail() {
        // IOException#getMessage() may be null — the envelope must still be well-formed.
        assertFailureEnvelope(ShellTool.startFailure("bash", null));
    }

    @Test
    void interruptedFailureIsRetryableLaterEnvelope() {
        ToolResult result = ShellTool.interruptedFailure();
        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Retryable: later"),
                () -> "an interrupted wait is transient (later), not a command error: " + result.getError());
    }

    @Test
    void allFailureEnvelopesAreWellFormed() {
        for (ToolResult result : new ToolResult[]{
                ShellTool.emptyCommandFailure(),
                ShellTool.timeoutFailure(60),
                ShellTool.startFailure("PowerShell", "x"),
                ShellTool.interruptedFailure()}) {
            assertEnvelope(result.getError());
        }
    }
}
