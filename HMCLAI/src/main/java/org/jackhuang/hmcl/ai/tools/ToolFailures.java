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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Factory for the unified tool-failure "error envelope" every model-visible failure text
/// should follow:
///
/// ```
/// <what happened, carrying key data (candidate lists / valid values / boundary numbers)>.
/// Retryable: <yes|no|later> — <one-sentence reason>. Next: <actionable next step>.
/// ```
///
/// Semantics of the [`Retryable`] classification:
///   - `yes`   — retrying with different parameters / spelling / path will likely succeed
///     (format error, typo, path outside allowed roots, ...).
///   - `no`    — terminal state; retrying is pointless (user declined, capability boundary,
///     missing configuration). The `Next:` step MUST offer a non-retry way out
///     (ask the user / switch tools / point to settings), never just "do not retry".
///   - `later` — transient condition (network hiccup, rate limit, file locked by a running
///     game); retry after waiting or after one corrective action, not immediately.
///
/// All texts produced here are model-visible and therefore English, consistent with the
/// existing tool corpus. Use [`#failureEnvelope`] to build the text, or [`#failure`] to get a
/// ready [`ToolResult`]. [`#isWellFormedEnvelope`] is the JUnit-free predicate that the
/// envelope-format assertion helpers (regression lock for all failure-text rewrites) build on.
@NotNullByDefault
public final class ToolFailures {

    private ToolFailures() {
    }

    /// Whether — and when — the model should retry the failed call. See class doc for the
    /// exact contract of each value.
    public enum Retryable {
        /// Retrying with corrected parameters will likely succeed.
        YES("yes"),
        /// Terminal; the `Next:` step must offer a non-retry way out.
        NO("no"),
        /// Transient; retry after waiting or one corrective action.
        LATER("later");

        private final String token;

        Retryable(String token) {
            this.token = token;
        }

        /// The lowercase token that appears verbatim after `"Retryable: "` in the envelope.
        public String token() {
            return token;
        }
    }

    /// Matches the `Retryable: <token>` segment with a word boundary so that e.g.
    /// `"Retryable: nothing"` does not pass as `no`.
    private static final Pattern RETRYABLE_SEGMENT = Pattern.compile("Retryable: (yes|no|later)\\b");

    private static final String NEXT_MARKER = "Next: ";

    /// Builds an envelope without the optional one-sentence reason clause:
    /// `"<what>. Retryable: <token>. Next: <next>."`.
    ///
    /// Prefer the four-argument overload when a short reason is available — the full §-format
    /// includes it and it costs the model nothing to read.
    ///
    /// @param what      what happened, ideally carrying the data the model was looking for
    ///                  (candidate names, valid values, boundary numbers); non-blank
    /// @param retryable the retry classification
    /// @param next      the actionable next step; non-blank (for [`Retryable#NO`] it must be a
    ///                  non-retry way out)
    /// @return the assembled envelope text
    public static String failureEnvelope(String what, Retryable retryable, String next) {
        return failureEnvelope(what, retryable, null, next);
    }

    /// Builds the full envelope:
    /// `"<what>. Retryable: <token> — <reason>. Next: <next>."`.
    ///
    /// Segments are trimmed and a single trailing `'.'` is stripped from each before assembly,
    /// so callers may pass either `"Do X"` or `"Do X."` without producing `".."`.
    ///
    /// @param what      what happened, carrying key data; non-blank
    /// @param retryable the retry classification
    /// @param reason    one-sentence reason for the classification; `null` omits the clause
    ///                  (equivalent to the three-argument overload), but must not be blank
    ///                  when present
    /// @param next      the actionable next step; non-blank
    /// @return the assembled envelope text
    /// @throws IllegalArgumentException if `what` or `next` is blank, or `reason` is non-null
    ///                                  but blank
    public static String failureEnvelope(String what, Retryable retryable, @Nullable String reason, String next) {
        String whatSegment = requireSegment(what, "what");
        String nextSegment = requireSegment(next, "next");
        StringBuilder envelope = new StringBuilder(whatSegment)
                .append(". Retryable: ")
                .append(retryable.token());
        if (reason != null) {
            envelope.append(" — ").append(requireSegment(reason, "reason"));
        }
        return envelope.append(". ")
                .append(NEXT_MARKER)
                .append(nextSegment)
                .append('.')
                .toString();
    }

    /// Convenience for `ToolResult.failure(failureEnvelope(what, retryable, next))`.
    public static ToolResult failure(String what, Retryable retryable, String next) {
        return ToolResult.failure(failureEnvelope(what, retryable, next));
    }

    /// Convenience for `ToolResult.failure(failureEnvelope(what, retryable, reason, next))`.
    public static ToolResult failure(String what, Retryable retryable, @Nullable String reason, String next) {
        return ToolResult.failure(failureEnvelope(what, retryable, reason, next));
    }

    /// JUnit-free structural check used by the envelope-format regression lock: the text must
    /// contain a `"Retryable: <yes|no|later>"` segment followed (in that order) by a
    /// `"Next: "` segment with non-blank content. Lives in main sources so that tests in any
    /// module depending on HMCLAI can assert on it without shared test fixtures.
    ///
    /// @param text a failure text, possibly `null`
    /// @return whether the text is a well-formed envelope
    public static boolean isWellFormedEnvelope(@Nullable String text) {
        if (text == null) {
            return false;
        }
        Matcher retryable = RETRYABLE_SEGMENT.matcher(text);
        if (!retryable.find()) {
            return false;
        }
        int next = text.indexOf(NEXT_MARKER, retryable.end());
        if (next < 0) {
            return false;
        }
        return !text.substring(next + NEXT_MARKER.length()).isBlank();
    }

    /// Trims the segment, rejects blank input, and strips a single trailing `'.'` so the
    /// assembled envelope never contains `".."`.
    private static String requireSegment(String segment, String name) {
        //noinspection ConstantValue -- callers outside @NotNullByDefault-checked code may still pass null
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("Envelope segment '" + name + "' must not be blank");
        }
        String trimmed = segment.trim();
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Envelope segment '" + name + "' must not be blank");
            }
        }
        return trimmed;
    }
}
