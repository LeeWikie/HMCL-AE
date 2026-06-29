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

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// LangChain4j-backed implementation of [`AiChatClient`] that delegates
/// all LLM communication to LangChain4j chat models.
///
/// ## Message conversion
///
/// HMCL [`LlmMessage`] objects are mapped to LangChain4j message types:
/// - `"system"` role → [`SystemMessage`]
/// - `"user"` role → [`UserMessage`]
/// - `"assistant"` role → [`AiMessage`]
///
/// ## Tool support
///
/// An optional [`LangChain4jToolAdapter`] can be supplied. When provided,
/// tool specifications are included in each request and tool execution
/// results from the model are dispatched back to the adapter for execution
/// against the HMCL tool registry.
///
/// ## Threading
///
/// Non-streaming calls return a [`CompletableFuture`] that completes on a
/// background thread. Streaming calls use LangChain4j's built-in async
/// response handler.
///
/// @see LangChain4jModelFactory
/// @see LangChain4jToolAdapter
@NotNullByDefault
public final class LangChain4jChatAdapter implements AiChatClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    @Nullable
    private final LangChain4jToolAdapter toolAdapter;
    private final ExecutorService executor;

    /// Creates an adapter backed by the given chat and streaming models
    /// without tool support.
    ///
    /// @param chatModel      the non-streaming chat model
    /// @param streamingChatModel the streaming chat model
    public LangChain4jChatAdapter(ChatModel chatModel,
                                  StreamingChatModel streamingChatModel) {
        this(chatModel, streamingChatModel, null);
    }

    /// Creates an adapter backed by the given models and optional tool
    /// adapter.
    ///
    /// @param chatModel      the non-streaming chat model
    /// @param streamingChatModel the streaming chat model
    /// @param toolAdapter    optional tool adapter for tool-calling support
    public LangChain4jChatAdapter(ChatModel chatModel,
                                  StreamingChatModel streamingChatModel,
                                  @Nullable LangChain4jToolAdapter toolAdapter) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.toolAdapter = toolAdapter;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "l4j-chat-adapter");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<@Nullable String> sendMessage(List<LlmMessage> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doChat(messages);
            } catch (Exception e) {
                throw new CompletionException(wrapError(e));
            }
        }, executor);
    }

    /// Maximum tool-call cycles before giving up, as a runaway backstop (Pi loops until
    /// done; we keep a generous cap so a misbehaving model can't loop forever).
    /// Configurable via {@link #setAgentLimits(int, int, int)}.
    private volatile int maxToolCycles = 25;

    /// Maximum number of recent conversation messages sent to the model
    /// (`0` = unlimited). Leading system messages are always kept.
    private volatile int maxContextMessages = 0;

    /// Maximum characters of a single tool result fed back to the model
    /// (`0` = unlimited).
    private volatile int toolResultMaxChars = 0;

    /// Applies the agent-loop limits from settings. Non-positive cycle counts
    /// fall back to the default backstop; non-positive context/result limits
    /// mean "unlimited".
    public void setAgentLimits(int maxToolCycles, int maxContextMessages, int toolResultMaxChars) {
        this.maxToolCycles = maxToolCycles > 0 ? maxToolCycles : 25;
        this.maxContextMessages = Math.max(0, maxContextMessages);
        this.toolResultMaxChars = Math.max(0, toolResultMaxChars);
    }

    /// Trims the conversation to the most recent {@link #maxContextMessages} entries,
    /// always preserving leading system messages and never starting the kept window
    /// with an orphaned tool-result message (whose originating assistant message would
    /// otherwise be dropped, which the OpenAI/Anthropic APIs reject).
    private List<ChatMessage> applyContextLimit(List<ChatMessage> conversation) {
        int limit = maxContextMessages;
        if (limit <= 0 || conversation.size() <= limit) {
            return conversation;
        }
        List<ChatMessage> systems = new ArrayList<>();
        for (ChatMessage m : conversation) {
            if (m instanceof SystemMessage) systems.add(m);
            else break;
        }
        int start = Math.max(systems.size(), conversation.size() - limit);
        List<ChatMessage> tail = new ArrayList<>(conversation.subList(start, conversation.size()));
        while (!tail.isEmpty() && tail.get(0) instanceof ToolExecutionResultMessage) {
            tail.remove(0);
        }
        List<ChatMessage> trimmed = new ArrayList<>(systems.size() + tail.size());
        trimmed.addAll(systems);
        trimmed.addAll(tail);
        return trimmed;
    }

    /// Truncates a tool result fed back to the model when it exceeds
    /// {@link #toolResultMaxChars}. Returns the original message when within budget.
    private ToolExecutionResultMessage truncateToolResult(ToolExecutionRequest req,
                                                          ToolExecutionResultMessage result) {
        int limit = toolResultMaxChars;
        if (limit <= 0 || result == null) {
            return result;
        }
        String text = result.text();
        if (text == null || text.length() <= limit) {
            return result;
        }
        String truncated = text.substring(0, limit)
                + "\n…[truncated " + (text.length() - limit) + " chars by tool-result limit]";
        return ToolExecutionResultMessage.from(req, truncated);
    }

    @Override
    public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));
        streamTurn(conversation, callback, 0);
    }

    /// One turn of the streaming agent loop: stream the model's response (with tools
    /// attached); if it asks for tool calls, execute them, append the results, and recurse;
    /// otherwise finish. Mirrors Pi's loop — keep going until the model stops calling tools.
    private void streamTurn(List<ChatMessage> conversation, LlmStreamCallback callback, int cycle) {
        if (cycle >= maxToolCycles) {
            callback.onComplete("");
            return;
        }

        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(conversation);
        if (toolAdapter != null && !toolAdapter.buildToolSpecifications().isEmpty()) {
            requestBuilder.toolSpecifications(toolAdapter.buildToolSpecifications());
        }

        streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (token != null && !token.isEmpty()) {
                    callback.onToken(token);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                TokenUsage usage = response.tokenUsage();
                if (usage != null) {
                    Integer in = usage.inputTokenCount();
                    Integer out = usage.outputTokenCount();
                    Integer total = usage.totalTokenCount();
                    LlmUsage reported = LlmUsage.of(
                            in != null ? in : 0,
                            out != null ? out : 0,
                            total != null ? total : 0);
                    if (reported.hasData()) {
                        callback.onUsage(reported);
                    }
                }

                AiMessage aiMessage = response.aiMessage();
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    // Tool-call turn: record it, run each tool, feed results back, loop.
                    conversation.add(aiMessage);
                    for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                        callback.onToolActivity(req.name(), req.arguments());
                        ToolExecutionResultMessage result = truncateToolResult(req, toolAdapter.execute(req));
                        if (result != null) {
                            conversation.add(result);
                            String resultText = result.text() != null ? result.text() : "";
                            boolean success = !resultText.startsWith("Error:");
                            String summary = resultText.length() > 300
                                    ? resultText.substring(0, 300) + "…"
                                    : resultText;
                            callback.onToolResult(req.name(), success, summary);
                        }
                    }
                    streamTurn(conversation, callback, cycle + 1);
                    return;
                }

                String content = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "";
                callback.onComplete(content);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(wrapError(error));
            }
        });
    }

    /// Sends a non-streaming request via the LangChain4j chat model and
    /// converts the response. Tool calls in the response are executed via
    /// the tool adapter and the assistant's text content is returned.
    ///
    /// @param messages the HMCL conversation history
    /// @return the assistant's text response
    private String doChat(List<LlmMessage> messages) {
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));

        for (int cycle = 0; cycle < maxToolCycles; cycle++) {
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(conversation);

            if (toolAdapter != null && !toolAdapter.buildToolSpecifications().isEmpty()) {
                requestBuilder.toolSpecifications(toolAdapter.buildToolSpecifications());
            }

            ChatResponse response = chatModel.chat(requestBuilder.build());
            AiMessage aiMessage = response.aiMessage();

            if (aiMessage == null) return "";

            // If no tool calls, return the text response
            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() != null ? aiMessage.text() : "";
            }

            // Add assistant message (with tool requests) to conversation
            conversation.add(aiMessage);

            // Execute every tool request and add exactly one result per
            // request. The adapter never returns null, so every assistant
            // tool-use request gets a matching tool result — required by the
            // OpenAI/Anthropic APIs — even when a tool fails (the failure is
            // returned to the model as an "Error: ..." result so it can retry).
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                conversation.add(truncateToolResult(req, toolAdapter.execute(req)));
            }
        }

        return ""; // exceeded max cycles
    }

    /// Converts a list of HMCL [`LlmMessage`] objects to LangChain4j
    /// [`ChatMessage`] objects.
    ///
    /// @param messages the HMCL messages to convert
    /// @return a list of LangChain4j chat messages
    static List<ChatMessage> convertMessages(List<LlmMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (LlmMessage msg : messages) {
            result.add(convertMessage(msg));
        }
        return result;
    }

    /// Converts a single HMCL message to a LangChain4j message.
    ///
    /// @param msg the HMCL message to convert
    /// @return the corresponding LangChain4j chat message
    private static ChatMessage convertMessage(LlmMessage msg) {
        String content = msg.getContent() != null ? msg.getContent() : "";
        switch (msg.getRole()) {
            case "system":
                return SystemMessage.from(content);
            case "user":
                return UserMessage.from(content);
            case "assistant":
                return AiMessage.from(content);
            default:
                return UserMessage.from(content);
        }
    }

    /// Wraps a LangChain4j or runtime exception into a
    /// [`LlmException`] following the same error conventions as
    /// the legacy [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient].
    ///
    /// @param error the throwable from LangChain4j
    /// @return a LlmException wrapping the error
    static LlmException wrapError(Throwable error) {
        if (error instanceof LlmException) {
            return (LlmException) error;
        }
        if (error instanceof CancellationException) {
            return new LlmException("Request cancelled", 0, error);
        }
        return new LlmException("AI request failed: " + error.getMessage(), 0, error);
    }
}
