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
import org.jackhuang.hmcl.ai.util.AiLog;
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
    /// Configurable via {@link #setAgentLimits(int, int, int, int)}.
    private volatile int maxToolCycles = 25;

    /// Maximum number of recent conversation messages sent to the model
    /// (`0` = unlimited). Leading system messages are always kept.
    private volatile int maxContextMessages = 0;

    /// Maximum characters of a single tool result fed back to the model.
    /// `0` = unlimited (no truncation); the default (from {@code AiSettings}) caps a single
    /// result at 20,000 chars, since one uncapped `read`/`web_fetch` dump gets re-sent on EVERY
    /// subsequent tool cycle of the turn and was the main way conversations blew past the window.
    private volatile int toolResultMaxChars = 20_000; // matches AiSettings.DEFAULT_TOOL_RESULT_MAX_CHARS

    /// Approximate character budget for one request's total content, derived from the
    /// active model's context window (`0` = eviction disabled). When a turn's growing
    /// tool loop exceeds this, the OLDEST tool results are replaced by a short
    /// placeholder (the model can re-run the tool if it still needs the data).
    private volatile long contextCharBudget = 0;

    /// Fraction of the context window the request content may fill before old tool
    /// results start being evicted (head-room for the system prompt + the reply).
    private static final double EVICTION_BUDGET_RATIO = 0.75;

    /// Heuristic chars-per-token used to convert the context window into a char budget.
    private static final int CHARS_PER_TOKEN = 4;

    /// Applies the agent-loop limits from settings. Non-positive cycle counts
    /// fall back to the default backstop; a non-positive message limit means
    /// "unlimited"; a non-positive tool-result limit means "auto cap";
    /// a non-positive context window disables in-turn tool-result eviction.
    public void setAgentLimits(int maxToolCycles, int maxContextMessages, int toolResultMaxChars,
                               int contextWindowTokens) {
        this.maxToolCycles = maxToolCycles > 0 ? maxToolCycles : 25;
        this.maxContextMessages = Math.max(0, maxContextMessages);
        this.toolResultMaxChars = Math.max(0, toolResultMaxChars);
        this.contextCharBudget = contextWindowTokens > 0
                ? (long) (contextWindowTokens * EVICTION_BUDGET_RATIO) * CHARS_PER_TOKEN : 0;
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

    /// Truncates a tool result fed back to the model when it exceeds the configured
    /// {@link #toolResultMaxChars}. A setting of `0` means unlimited — the user's explicit
    /// opt-out — so the result is returned untouched. Returns the original message when within budget.
    private ToolExecutionResultMessage truncateToolResult(ToolExecutionRequest req,
                                                          ToolExecutionResultMessage result) {
        if (result == null) {
            return null;
        }
        int limit = toolResultMaxChars;
        if (limit <= 0) {
            return result; // 0 = unlimited: the user opted out of the cap
        }
        String text = result.text();
        if (text == null || text.length() <= limit) {
            return result;
        }
        String truncated = text.substring(0, limit)
                + "\n…[truncated " + (text.length() - limit) + " chars by tool-result limit —"
                + " re-run the tool with narrower arguments (e.g. read with startLine/maxLines)"
                + " if you need the rest]";
        return ToolExecutionResultMessage.from(req, truncated);
    }

    /// Placeholder that replaces an evicted old tool result. Kept short and explicit so the
    /// model knows the data is re-obtainable rather than mysteriously missing.
    static final String EVICTED_TOOL_RESULT =
            "[old tool result dropped to free context — re-run the tool if you still need it]";

    /// The most recent tool results are never evicted — they are what the model is
    /// actively working from in the current cycles.
    private static final int KEEP_RECENT_TOOL_RESULTS = 4;

    /// Rough size of one message's content in characters, for budget accounting.
    private static int approxChars(ChatMessage m) {
        if (m instanceof ToolExecutionResultMessage t) {
            return t.text() != null ? t.text().length() : 0;
        }
        if (m instanceof AiMessage a) {
            int n = a.text() != null ? a.text().length() : 0;
            if (a.hasToolExecutionRequests()) {
                for (ToolExecutionRequest r : a.toolExecutionRequests()) {
                    n += (r.arguments() != null ? r.arguments().length() : 0) + 40;
                }
            }
            return n;
        }
        if (m instanceof SystemMessage s) {
            return s.text() != null ? s.text().length() : 0;
        }
        // UserMessage and anything else: toString is proportional enough for budgeting.
        return m.toString().length();
    }

    /// In-turn context editing: when the growing tool loop pushes the conversation past
    /// {@link #contextCharBudget}, replaces the OLDEST tool results (never the most recent
    /// {@link #KEEP_RECENT_TOOL_RESULTS}) with {@link #EVICTED_TOOL_RESULT} placeholders,
    /// oldest first, until back under budget. Mutates {@code conversation} in place so the
    /// eviction sticks for all remaining cycles of the turn. Keeping one placeholder per
    /// tool-use (instead of removing the message) preserves the request/result pairing the
    /// OpenAI/Anthropic APIs require. Idempotent — placeholders are never re-evicted.
    private void evictOldToolResults(List<ChatMessage> conversation) {
        long budget = contextCharBudget;
        if (budget <= 0) {
            return;
        }
        long total = 0;
        for (ChatMessage m : conversation) {
            total += approxChars(m);
        }
        if (total <= budget) {
            return;
        }
        List<Integer> evictable = new ArrayList<>();
        for (int i = 0; i < conversation.size(); i++) {
            if (conversation.get(i) instanceof ToolExecutionResultMessage t
                    && t.text() != null && t.text().length() > EVICTED_TOOL_RESULT.length()) {
                evictable.add(i);
            }
        }
        int limit = evictable.size() - KEEP_RECENT_TOOL_RESULTS;
        for (int k = 0; k < limit && total > budget; k++) {
            int i = evictable.get(k);
            ToolExecutionResultMessage t = (ToolExecutionResultMessage) conversation.get(i);
            total -= t.text().length() - EVICTED_TOOL_RESULT.length();
            conversation.set(i, ToolExecutionResultMessage.from(t.id(), t.toolName(), EVICTED_TOOL_RESULT));
        }
    }

    @Override
    public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));
        streamTurn(conversation, callback, 0, new java.util.HashMap<>(), new StringBuilder());
    }

    /// After this many identical (tool, arguments) calls in one turn, stop actually running
    /// the tool and feed back a BLOCKED notice instead — breaks the model out of a loop
    /// where it keeps re-issuing the same failing call until the cycle cap is hit.
    private static final int DUP_CALL_LIMIT = 3;

    /// After this many CONSECUTIVE failed/blocked tool results in one turn (regardless of which tool),
    /// inject a one-off guidance nudge telling the model to stop flailing and either answer with what
    /// it has or ask the user — catches "no progress" loops that keep varying arguments so the
    /// identical-call guard never trips. A reserved {@code callCounts} key tracks the running streak.
    private static final int NO_PROGRESS_LIMIT = 6;
    private static final String NO_PROGRESS_KEY = " consecutiveFailures";

    /// Executes a tool request unless it has already been issued (identical name+arguments)
    /// {@link #DUP_CALL_LIMIT} times this turn, in which case a synthetic BLOCKED result is
    /// returned so the conversation still has one result per tool-use (API requirement) while
    /// telling the model to stop repeating itself.
    /// Opening of a leaked tool-call special token. Some models (notably deepseek in streaming
    /// mode) emit their tool-call tokens (e.g. `<｜DSML｜tool_calls>…<｜DSML｜invoke name="glob">…`
    /// or ChatML `<|im_start|>`) into the TEXT content instead of returning structured tool calls
    /// langchain4j can run, dumping raw markup into the chat.
    ///
    /// Deliberately narrow so ordinary prose is never truncated: requires `<` immediately followed
    /// by a fullwidth bar (U+FF5C, only ever seen in leaked special tokens) plus a letter, or an
    /// ascii `<|` opening one of the known special-token names. Plain `x < |y|` maths or the
    /// F#/Elm `<|` operator followed by a space/expression do NOT match.
    private static final java.util.regex.Pattern LEAKED_TOOL_MARKUP =
            java.util.regex.Pattern.compile("<(?:\\uFF5C\\p{L}|\\|(?:im_start|im_end|im_sep|DSML|tool[_▁]))");

    /// Returns the user-facing prose before any leaked tool-call markup (the markup is trailing
    /// garbage the model appended after its real text), or the input unchanged if none is present.
    static String stripLeakedToolMarkup(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        java.util.regex.Matcher m = LEAKED_TOOL_MARKUP.matcher(s);
        return m.find() ? s.substring(0, m.start()).strip() : s;
    }

    /// Read-only polling tools that are EXEMPT from the duplicate-call guard: re-issuing the exact
    /// same call is their intended use (the background-job protocol tells the model to poll
    /// `check_job` with the same jobId until the job finishes). The overall cycle cap still bounds
    /// them, so an endless poll loop can't run forever.
    private static final java.util.Set<String> DUP_GUARD_EXEMPT =
            java.util.Set.of("check_job", "list_jobs");

    private ToolExecutionResultMessage executeWithLoopGuard(ToolExecutionRequest req,
                                                            java.util.Map<String, Integer> callCounts) {
        if (!DUP_GUARD_EXEMPT.contains(req.name())) {
            String fingerprint = req.name() + "|" + (req.arguments() == null ? "" : req.arguments());
            int count = callCounts.merge(fingerprint, 1, Integer::sum);
            if (count >= DUP_CALL_LIMIT) {
                return ToolExecutionResultMessage.from(req,
                        "BLOCKED: you have already made this exact '" + req.name() + "' call "
                        + (count - 1) + " times this turn. Do NOT repeat the identical call — change the"
                        + " arguments, use a different tool, or answer with the information you already"
                        + " have. If you are waiting on a background job, poll it with check_job instead.");
            }
        }
        return truncateToolResult(req, toolAdapter.execute(req));
    }

    /// One turn of the streaming agent loop: stream the model's response (with tools
    /// attached); if it asks for tool calls, execute them, append the results, and recurse;
    /// otherwise finish. Mirrors Pi's loop — keep going until the model stops calling tools.
    ///
    /// `callCounts` tracks how often each identical (tool, arguments) pair has been issued
    /// this turn, so we can break the model out of a loop where it keeps re-issuing the same
    /// failing call (see {@link #DUP_CALL_LIMIT}).
    ///
    /// `turnText` accumulates the FINAL text of every completed cycle (leaked markup already
    /// stripped, segments separated by blank lines) — the authoritative full-turn text handed to
    /// {@link LlmStreamCallback#onComplete}. Raw streamed tokens are NOT authoritative: they
    /// contain the un-stripped markup and no segment separators.
    ///
    /// Every path out of a turn fires exactly one terminal callback (onComplete/onError) —
    /// including cancellation, which must still reach the caller so it can persist the partial
    /// reply with its interruption marker (a silent return here previously left the session
    /// with a dangling user message whenever Stop was pressed during a tool cycle or retry).
    private void streamTurn(List<ChatMessage> conversation, LlmStreamCallback callback, int cycle,
                            java.util.Map<String, Integer> callCounts, StringBuilder turnText) {
        streamTurn(conversation, callback, cycle, callCounts, turnText, 0);
    }

    /// Appends a completed cycle's text segment to the turn accumulator, blank-line separated.
    private static void appendSegment(StringBuilder turnText, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        if (turnText.length() > 0) {
            turnText.append("\n\n");
        }
        turnText.append(segment.strip());
    }

    /// @param attempt how many times this cycle's request has already failed transiently and been
    ///                retried before any token streamed (0 on first try); see {@link #MAX_STREAM_RETRIES}.
    private void streamTurn(List<ChatMessage> conversation, LlmStreamCallback callback, int cycle,
                            java.util.Map<String, Integer> callCounts, StringBuilder turnText, int attempt) {
        if (callback.isCancelled()) {
            // User pressed Stop — abort instead of issuing another model call / tool cycle, but
            // still terminate the callback chain so the partial reply gets persisted.
            callback.onComplete(turnText.toString());
            return;
        }
        if (cycle >= maxToolCycles) {
            appendSegment(turnText, "（已连续调用工具 " + maxToolCycles
                    + " 轮仍未完成，已停止以避免无限空转。建议：换种说法或补充信息，必要时在右上角换一个更强的模型；也可在「高级」里调整工具调用轮数上限。）");
            callback.onComplete(turnText.toString());
            return;
        }

        // In-turn context editing: fold the oldest tool results into placeholders once the
        // accumulated tool loop no longer fits the model's window (they'd otherwise be
        // re-sent in full on every remaining cycle).
        evictOldToolResults(conversation);

        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(conversation);
        // On the final allowed cycle, drop the tools so the model is forced to produce a
        // text answer summarising progress instead of requesting yet another tool call
        // (which would otherwise be silently dropped as an empty reply).
        boolean allowTools = toolAdapter != null && !toolAdapter.buildToolSpecifications().isEmpty()
                && cycle < maxToolCycles - 1;
        if (allowTools) {
            requestBuilder.toolSpecifications(toolAdapter.buildToolSpecifications());
        }

        // Tracks whether any token has streamed yet: a transient error before the first token can be
        // retried transparently (nothing to roll back), but a mid-stream error must never be retried.
        final java.util.concurrent.atomic.AtomicBoolean tokenSeen = new java.util.concurrent.atomic.AtomicBoolean(false);
        final long startNanos = System.nanoTime();
        AiLog.info("[AI] 模型请求 cycle=" + cycle + " attempt=" + attempt
                + " 消息数=" + conversation.size() + " 工具=" + (allowTools ? "on" : "off"));
        streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (token != null && !token.isEmpty()) {
                    tokenSeen.set(true);
                    callback.onToken(token);
                }
            }

            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                // Reasoning tokens (DeepSeek-R1 reasoning_content, enabled via returnThinking) stream
                // ahead of the answer; forward them so the UI can show a collapsible "思考过程" card.
                if (partialThinking != null) {
                    String t = partialThinking.text();
                    if (t != null && !t.isEmpty()) {
                        callback.onReasoningToken(t);
                    }
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
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                AiLog.info("[AI] 模型响应 cycle=" + cycle + " 耗时=" + elapsedMs + "ms tokens(in/out/total)="
                        + (usage != null ? usage.inputTokenCount() + "/" + usage.outputTokenCount()
                        + "/" + usage.totalTokenCount() : "n/a")
                        + " 工具调用=" + (aiMessage != null && aiMessage.hasToolExecutionRequests()
                        ? aiMessage.toolExecutionRequests().size() : 0));
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    // Tool-call turn: record it, run each tool, feed results back, loop.
                    // Any prose the model emitted before its tool calls becomes a finished segment.
                    appendSegment(turnText, stripLeakedToolMarkup(
                            aiMessage.text() != null ? aiMessage.text() : ""));
                    conversation.add(aiMessage);
                    for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                        if (callback.isCancelled()) {
                            // Stopped mid-turn — skip remaining tool calls but still terminate the
                            // callback chain so the caller persists the interrupted partial.
                            callback.onComplete(turnText.toString());
                            return;
                        }
                        callback.onToolActivity(req.name(), req.arguments());
                        ToolExecutionResultMessage result = executeWithLoopGuard(req, callCounts);
                        if (result != null) {
                            conversation.add(result);
                            String resultText = result.text() != null ? result.text() : "";
                            boolean success = !resultText.startsWith("Error:") && !resultText.startsWith("BLOCKED:");
                            // No-progress tracking: reset the streak on any success, grow it on failure.
                            if (success) {
                                callCounts.put(NO_PROGRESS_KEY, 0);
                            } else {
                                callCounts.merge(NO_PROGRESS_KEY, 1, Integer::sum);
                            }
                            String summary = resultText.length() > 300
                                    ? resultText.substring(0, 300) + "…"
                                    : resultText;
                            callback.onToolResult(req.name(), success, summary);
                        }
                    }
                    // No-progress guard: after a run of failures with nothing succeeding, nudge the model
                    // ONCE to stop retrying and wrap up, then reset the streak so it isn't re-nagged every
                    // cycle. The overall cycle cap remains the hard backstop.
                    if (callCounts.getOrDefault(NO_PROGRESS_KEY, 0) >= NO_PROGRESS_LIMIT) {
                        AiLog.warn("[AI] 连续 " + callCounts.get(NO_PROGRESS_KEY)
                                + " 次工具失败无进展，注入收敛提示");
                        conversation.add(dev.langchain4j.data.message.UserMessage.from(
                                "[系统提示] 已连续多次工具调用失败、没有取得进展。请停止继续尝试同类操作："
                                + "用你已经获得的信息直接回答，或明确说明卡在哪里、需要用户提供什么，不要再盲目重试。"));
                        callCounts.put(NO_PROGRESS_KEY, 0);
                    }
                    streamTurn(conversation, callback, cycle + 1, callCounts, turnText);
                    return;
                }

                String raw = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "";
                String content = stripLeakedToolMarkup(raw);
                if (content.isEmpty() && !raw.isEmpty() && turnText.length() == 0) {
                    // The whole reply was leaked tool-call markup the provider never parsed into real
                    // calls — tell the user instead of dumping raw <｜…｜> tokens into the chat.
                    content = "（当前模型本轮以文本形式输出了工具调用，但接口未将其解析为真正的调用，因此无法执行。"
                            + "建议在右上角换一个能稳定进行函数调用的模型，或换种说法重试。）";
                }
                appendSegment(turnText, content);
                callback.onComplete(turnText.toString());
            }

            @Override
            public void onError(Throwable error) {
                LlmException wrapped = wrapError(error);
                // Transparent retry for transient failures (429 / 5xx / connection / timeout) that
                // happened BEFORE any token streamed, bounded by MAX_STREAM_RETRIES with backoff.
                if (!tokenSeen.get() && attempt < MAX_STREAM_RETRIES && isRetryable(wrapped)
                        && !callback.isCancelled()) {
                    AiLog.warn("[AI] 模型请求瞬时失败，重试 attempt=" + (attempt + 1) + "/" + MAX_STREAM_RETRIES
                            + "：" + wrapped.getMessage());
                    try {
                        Thread.sleep(500L * (1L << attempt)); // 0.5s, then 1s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        callback.onError(wrapped);
                        return;
                    }
                    streamTurn(conversation, callback, cycle, callCounts, turnText, attempt + 1);
                    return;
                }
                AiLog.warn("[AI] 模型请求失败：" + wrapped.getMessage());
                callback.onError(wrapped);
            }
        });
    }

    private static final int MAX_STREAM_RETRIES = 2; // up to 3 attempts total for a pre-token transient error

    /// Whether a failure is worth a transparent retry: rate limits (429), server-side errors (5xx),
    /// and connection/timeout failures (status 0, no HTTP response). Client errors (400/401/403/404)
    /// are NOT retried — they won't succeed on a re-send.
    private static boolean isRetryable(LlmException e) {
        int s = e.getStatusCode();
        return s == 0 || s == 429 || s == 500 || s == 502 || s == 503 || s == 504;
    }

    /// Sends a non-streaming request via the LangChain4j chat model and
    /// converts the response. Tool calls in the response are executed via
    /// the tool adapter and the assistant's text content is returned.
    ///
    /// @param messages the HMCL conversation history
    /// @return the assistant's text response
    private String doChat(List<LlmMessage> messages) {
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));
        java.util.Map<String, Integer> callCounts = new java.util.HashMap<>();

        for (int cycle = 0; cycle < maxToolCycles; cycle++) {
            evictOldToolResults(conversation);
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(conversation);

            // Final allowed cycle: drop tools so the model must answer in text.
            if (toolAdapter != null && !toolAdapter.buildToolSpecifications().isEmpty()
                    && cycle < maxToolCycles - 1) {
                requestBuilder.toolSpecifications(toolAdapter.buildToolSpecifications());
            }

            ChatResponse response = chatModel.chat(requestBuilder.build());
            AiMessage aiMessage = response.aiMessage();

            if (aiMessage == null) return "";

            // If no tool calls, return the text response (stripped of any leaked tool-call markup).
            if (!aiMessage.hasToolExecutionRequests()) {
                return stripLeakedToolMarkup(aiMessage.text() != null ? aiMessage.text() : "");
            }

            // Add assistant message (with tool requests) to conversation
            conversation.add(aiMessage);

            // Execute every tool request and add exactly one result per
            // request. The adapter never returns null, so every assistant
            // tool-use request gets a matching tool result — required by the
            // OpenAI/Anthropic APIs — even when a tool fails (the failure is
            // returned to the model as an "Error: ..." result so it can retry).
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                conversation.add(executeWithLoopGuard(req, callCounts));
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
        return new LlmException("AI request failed: " + error.getMessage(), extractStatus(error), error);
    }

    /// Best-effort HTTP status from langchain4j's exception hierarchy (walking the cause chain).
    /// langchain4j throws its OWN exception types, not HMCL's LlmException, so without this every
    /// failure looked like status 0 and isRetryable's 429/5xx allowlist was dead (every pre-token
    /// error, even a 401/400, got retried). 0 means unknown/network (genuinely retryable).
    private static int extractStatus(Throwable error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t instanceof dev.langchain4j.exception.HttpException) {
                return ((dev.langchain4j.exception.HttpException) t).statusCode();
            }
            if (t instanceof dev.langchain4j.exception.AuthenticationException) return 401;
            if (t instanceof dev.langchain4j.exception.RateLimitException) return 429;
            if (t instanceof dev.langchain4j.exception.ModelNotFoundException) return 404;
            if (t instanceof dev.langchain4j.exception.InvalidRequestException) return 400;
            if (t instanceof dev.langchain4j.exception.InternalServerException) return 500;
        }
        return 0;
    }
}
