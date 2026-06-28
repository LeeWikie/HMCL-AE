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

    /// Called when the streaming response has completed successfully.
    ///
    /// @param fullResponse the complete concatenated response text
    void onComplete(String fullResponse);

    /// Called when an error occurs during streaming.
    ///
    /// @param error the exception describing the failure
    void onError(LlmException error);
}
