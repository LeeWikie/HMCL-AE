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

import org.jackhuang.hmcl.ai.tools.ToolFailures.Retryable;
import org.junit.jupiter.api.Test;

import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertEnvelope;
import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertFailureEnvelope;
import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`ToolFailures`], the unified failure-envelope factory, and for the
/// envelope-format regression lock ([`ToolFailures#isWellFormedEnvelope`] +
/// [`ToolFailureAssertions`]) that all subsequent failure-text rewrites build their test
/// cases on.
public final class ToolFailuresTest {

    // ---- envelope assembly ----

    @Test
    void fullEnvelopeMatchesSpecFormat() {
        String envelope = ToolFailures.failureEnvelope(
                "Instance 'Fabric 1.20' does not exist in the selected profile",
                Retryable.YES,
                "the name is wrong, not the operation",
                "available instances: Vanilla 1.21; Forge 1.19.2");
        assertEquals("Instance 'Fabric 1.20' does not exist in the selected profile."
                        + " Retryable: yes — the name is wrong, not the operation."
                        + " Next: available instances: Vanilla 1.21; Forge 1.19.2.",
                envelope);
        assertEnvelope(envelope);
    }

    @Test
    void threeArgOverloadOmitsReasonClause() {
        String envelope = ToolFailures.failureEnvelope(
                "This operation was already declined by the user this turn",
                Retryable.NO,
                "ask the user directly or choose a different action");
        assertEquals("This operation was already declined by the user this turn."
                        + " Retryable: no."
                        + " Next: ask the user directly or choose a different action.",
                envelope);
        assertEnvelope(envelope);
    }

    @Test
    void allThreeTokensRenderLowercase() {
        assertTrue(ToolFailures.failureEnvelope("a", Retryable.YES, "b").contains("Retryable: yes."));
        assertTrue(ToolFailures.failureEnvelope("a", Retryable.NO, "b").contains("Retryable: no."));
        assertTrue(ToolFailures.failureEnvelope("a", Retryable.LATER, "b").contains("Retryable: later."));
    }

    @Test
    void trailingPeriodsAndWhitespaceAreNormalized() {
        String envelope = ToolFailures.failureEnvelope(
                "  Download failed. ", Retryable.LATER, "likely a transient network hiccup.", " retry once; if it fails again, report to the user. ");
        assertEquals("Download failed."
                        + " Retryable: later — likely a transient network hiccup."
                        + " Next: retry once; if it fails again, report to the user.",
                envelope);
        assertFalse(envelope.contains(".."), "double periods must never appear: " + envelope);
    }

    @Test
    void blankSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolFailures.failureEnvelope("", Retryable.YES, "next"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolFailures.failureEnvelope("what", Retryable.YES, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> ToolFailures.failureEnvelope("what", Retryable.YES, " ", "next"));
        // A segment that is nothing but a period normalizes to blank and must be rejected too.
        assertThrows(IllegalArgumentException.class,
                () -> ToolFailures.failureEnvelope(".", Retryable.YES, "next"));
    }

    @Test
    void failureConvenienceWrapsEnvelopeIntoToolResult() {
        ToolResult result = ToolFailures.failure(
                "No version of 'Sodium' supports loader forge",
                Retryable.YES,
                "this project publishes builds for: fabric; quilt — retry with one of those");
        assertFalse(result.isSuccess());
        assertEquals("", result.getOutput());
        assertFailureEnvelope(result);

        ToolResult withReason = ToolFailures.failure(
                "Backup 'b-42' of world 'Home' was not found",
                Retryable.YES,
                "the id is likely wrong or was pruned",
                "available backups: b-40; b-41");
        assertFailureEnvelope(withReason);
        assertNotNull(withReason.getError());
        assertTrue(withReason.getError().contains("Retryable: yes — the id is likely wrong or was pruned."));
    }

    // ---- the regression-lock predicate itself ----

    @Test
    void wellFormedEnvelopePredicateAcceptsFactoryOutput() {
        assertTrue(ToolFailures.isWellFormedEnvelope(
                ToolFailures.failureEnvelope("a", Retryable.YES, "b", "c")));
        assertTrue(ToolFailures.isWellFormedEnvelope(
                ToolFailures.failureEnvelope("a", Retryable.LATER, "c")));
    }

    @Test
    void predicateAcceptsHandWrittenTextInSpecFormat() {
        // Rewrites landed by later sub-batches are hand-written strings, not factory calls —
        // the predicate must accept any text following the spec shape.
        assertTrue(ToolFailures.isWellFormedEnvelope(
                "Path '/x' is outside the allowed roots. Retryable: yes — pass a path under one of"
                        + " the allowed roots. Next: allowed roots are /a; /b."));
    }

    @Test
    void predicateRejectsMissingOrMalformedSegments() {
        assertFalse(ToolFailures.isWellFormedEnvelope(null));
        assertFalse(ToolFailures.isWellFormedEnvelope(""));
        assertFalse(ToolFailures.isWellFormedEnvelope("Something failed, try again."));
        // Unrecognized token.
        assertFalse(ToolFailures.isWellFormedEnvelope("Failed. Retryable: maybe. Next: do X."));
        // Token must end at a word boundary — "nothing" is not "no".
        assertFalse(ToolFailures.isWellFormedEnvelope("Failed. Retryable: nothing. Next: do X."));
        // Missing Next segment.
        assertFalse(ToolFailures.isWellFormedEnvelope("Failed. Retryable: yes — typo."));
        // Next must come after Retryable.
        assertFalse(ToolFailures.isWellFormedEnvelope("Next: do X. Retryable: yes."));
        // Next content must be non-blank.
        assertFalse(ToolFailures.isWellFormedEnvelope("Failed. Retryable: yes. Next: "));
    }

    @Test
    void retryableTokensMatchEnumNames() {
        assertEquals("yes", Retryable.YES.token());
        assertEquals("no", Retryable.NO.token());
        assertEquals("later", Retryable.LATER.token());
    }
}
