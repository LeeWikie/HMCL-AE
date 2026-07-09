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
package org.jackhuang.hmcl.ai.llm;

import org.jetbrains.annotations.NotNullByDefault;

/// Callback interface for receiving streaming LLM responses.
///
/// Implementations receive tokens one at a time via [#onToken], the complete
/// concatenated response via [#onComplete], and any errors via [#onError].
/// All methods are called from a background thread managed by the LLM client.
///
/// @see LlmClient#sendMessageStreaming
@NotNullByDefault
public interface LlmStreamCallback {

    /// Called for each token received from the model during streaming.
    ///
    /// @param token a single text token; never `null`
    void onToken(String token);

    /// Called for each reasoning/"thinking" token from models that expose it (e.g. DeepSeek-R1's
    /// `reasoning_content`), streamed before the visible answer. The UI surfaces these in a
    /// collapsible card. Default no-op so callbacks that don't render reasoning are unaffected.
    ///
    /// @param token a single reasoning token; never `null`
    default void onReasoningToken(String token) {
    }

    /// Called when token usage for the response becomes available, before
    /// [#onComplete]. Only invoked when the provider reports a usage object;
    /// implementations that do not display usage may ignore this.
    ///
    /// @param usage the token usage reported by the provider
    default void onUsage(LlmUsage usage) {
    }

    /// Called when the model requests a tool call during the agent loop, before the tool
    /// runs. Lets the UI show agent activity (gated by the tool-call display setting).
    ///
    /// @param toolName  the name of the tool being invoked
    /// @param arguments the raw JSON arguments string (may be empty)
    default void onToolActivity(String toolName, String arguments) {
    }

    /// Called after a tool finishes executing during the agent loop, so the UI can update
    /// the matching tool-call card with the outcome. Pairs with [#onToolActivity].
    ///
    /// @param toolName      the name of the tool that ran
    /// @param success       `true` when the tool succeeded (result is not an error)
    /// @param resultSummary a short, trimmed summary of the tool result
    default void onToolResult(String toolName, boolean success, String resultSummary) {
    }

    /// Called when one CYCLE of the agent's tool loop finishes producing its text segment — right
    /// before that cycle's tool call(s) run, or right before the turn ends if it had none. Fired
    /// once per cycle that produced non-blank text, in order; joining every segment (in order,
    /// blank-line separated) reproduces {@link #onComplete}'s full text.
    ///
    /// Persisting each segment as its own message (instead of waiting for {@link #onComplete} to
    /// persist one combined blob) is what keeps a reloaded session's bubbles matching what the live
    /// stream actually rendered — the live UI already renders segments as separate bubbles; without
    /// this, reloading silently merged them into one.
    ///
    /// Default no-op for callbacks that don't need per-segment granularity.
    ///
    /// @param segment this cycle's finalized text (leaked tool markup already stripped)
    default void onSegmentComplete(String segment) {
    }

    /// Called when the streaming response has completed successfully.
    ///
    /// @param fullResponse the complete concatenated response text
    void onComplete(String fullResponse);

    /// Called when an error occurs during streaming.
    ///
    /// @param error the exception describing the failure
    void onError(LlmException error);

    /// Whether the user has cancelled this turn (pressed Stop). The agent loop polls this between
    /// tool cycles and tool calls so a stopped turn stops executing tools instead of running on.
    /// Defaults to {@code false} for callbacks that don't support cancellation.
    default boolean isCancelled() {
        return false;
    }
}
