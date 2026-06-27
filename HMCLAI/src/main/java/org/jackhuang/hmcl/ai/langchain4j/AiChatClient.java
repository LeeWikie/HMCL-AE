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

import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Thin abstraction over LLM communication so that both the legacy
/// [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient] and the new
/// [`LangChain4jChatAdapter`] can be used interchangeably by
/// [`ChatAgent`][org.jackhuang.hmcl.ai.agent.ChatAgent].
///
/// @see LangChain4jChatAdapter
@NotNullByDefault
public interface AiChatClient {

    /// Sends a non-streaming chat completion request and returns the full
    /// response text on a background thread.
    ///
    /// @param messages the conversation history to send to the model
    /// @return a future that yields the assistant's response text, or
    ///         completes exceptionally on error
    CompletableFuture<@Nullable String> sendMessage(List<LlmMessage> messages);

    /// Sends a streaming chat completion request. Tokens are delivered to
    /// [callback] as they arrive. The call returns immediately; all work
    /// runs on a background thread.
    ///
    /// @param messages the conversation history to send to the model
    /// @param callback the callback receiving tokens, completion, and errors
    void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback);
}
