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

import org.jetbrains.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/// The envelope-format regression lock (unified failure-envelope spec): every model-visible
/// failure text must contain a `"Retryable: <yes|no|later>"` segment and a following
/// non-blank `"Next: "` segment. All failure-text rewrites add one test case built on these
/// asserts so the format can never silently regress.
///
/// HMCLAI tests use this class directly; tests in other modules (without access to HMCLAI's
/// test sources) assert on the underlying main-source predicate
/// [`ToolFailures#isWellFormedEnvelope`] instead.
public final class ToolFailureAssertions {

    private ToolFailureAssertions() {
    }

    /// Asserts that `text` is a well-formed failure envelope: it contains both the
    /// `"Retryable:"` and `"Next:"` segments, in order, with a recognized retryable token and
    /// non-blank next step.
    public static void assertEnvelope(@Nullable String text) {
        assertNotNull(text, "expected a failure envelope, got null");
        assertTrue(text.contains("Retryable:"), () -> "missing 'Retryable:' segment in: " + text);
        assertTrue(text.contains("Next:"), () -> "missing 'Next:' segment in: " + text);
        assertTrue(ToolFailures.isWellFormedEnvelope(text),
                () -> "malformed envelope (need 'Retryable: <yes|no|later>' followed by a non-blank 'Next: ' segment): " + text);
    }

    /// Asserts that `result` is a failure whose error message is a well-formed envelope.
    public static void assertFailureEnvelope(ToolResult result) {
        assertFalse(result.isSuccess(), "expected a failure ToolResult, got success");
        assertEnvelope(result.getError());
    }
}
