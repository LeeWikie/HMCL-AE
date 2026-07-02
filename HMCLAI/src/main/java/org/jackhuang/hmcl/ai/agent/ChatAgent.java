package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.ModelLibrary;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Orchestrates a single chat session by building the conversation context,
/// dispatching requests to the LLM, and storing responses.
///
/// Tool execution is handled transparently by the {@link AiChatClient} adapter
/// (via LangChain4j native function calling).
///
/// ## Streaming
///
/// Streaming uses the client's native streaming API; tokens are forwarded to
/// the UI callback as they arrive.
///
/// ## Prompt
///
/// The system prompt is built dynamically by {@link AiPromptBuilder} so that
/// enabled tools, search, skills, and policies are reflected in the model's
/// understanding.
@NotNullByDefault
public final class ChatAgent {

    private static final String FALLBACK_SYSTEM_PROMPT =
            "You are an AI assistant for Hello Minecraft! Launcher. "
            + "You can help users with Minecraft setup, mod management, "
            + "game launching issues, and log/crash analysis. "
            + "Use the provided tools when appropriate.";

    /// Fraction of the active model's context window at which the running conversation is
    /// auto-compacted before the NEXT turn, so long tool-heavy chats never hard-overflow.
    private static final double AUTO_COMPACT_RATIO = 0.9;

    /// Context window (tokens) used when neither the model library nor the user settings know it.
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /// Rough chars-per-token heuristic for the local context-size estimate (matches AiSession).
    private static final int CHARS_PER_TOKEN = 4;

    private final AiChatClient client;
    private final AiSession session;
    /// Shared daemon pool for ancillary, session-read-only model calls (e.g. title suggestion) so
    /// they never occupy the per-agent single-thread executor that serves the user's actual turns.
    private static final ExecutorService ANCILLARY = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-agent-ancillary");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService executor;
    private final AiSettings settings;
    private final AiPromptBuilder promptBuilder;

    /// Provider-reported prompt (input) token count of the FIRST model call of the most recent
    /// streaming turn — i.e. the size of the persisted context (system + history + user) before
    /// any in-turn tool churn, which is the best proxy for what carries into the next turn.
    /// Written on the adapter's streaming callback thread, read on the agent executor;
    /// {@code volatile} for cross-thread visibility. Reset to {@code 0} after a compaction.
    private volatile int lastPromptTokens = 0;

    public ChatAgent(AiChatClient client, AiSession session, AiSettings settings,
                     AiPromptBuilder promptBuilder) {
        this.client = client;
        this.session = session;
        this.settings = settings;
        this.promptBuilder = promptBuilder;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chat-agent");
            t.setDaemon(true);
            return t;
        });
    }

    public AiSession getSession() { return session; }
    public AiSettings getSettings() { return settings; }

    /// System instruction used to summarise the running conversation into a compact,
    /// continuation-style brief so the chat can continue with far fewer tokens.
    private static final String COMPACT_SYSTEM_PROMPT = String.join("\n",
            "You compress a long assistant/user conversation into a concise, continuation-style summary",
            "so the chat can continue with far less context. Reply in the user's language (Chinese if the",
            "conversation is mainly Chinese). Output ONLY the summary, structured with these headings:",
            "目标 (what the user ultimately wants) / 已完成 (what has been done, including tool results and",
            "concrete facts discovered: instance names, versions, mod lists, accounts, paths) / 关键发现",
            "(important findings, decisions, user preferences, constraints) / 下一步 (the immediate next",
            "action to take). Be specific and preserve names/versions/IDs; drop chit-chat and verbose logs.");

    /// Compresses the current session into a continuation-style summary using a single
    /// non-streaming model call, then replaces the session history with that summary.
    ///
    /// Runs on the agent's executor (off the FX thread). On success the session is
    /// {@link AiSession#clear() cleared} and a single assistant message holding the
    /// summary is added; the returned future completes with the raw summary text. If
    /// the conversation is empty (or the model returns nothing) the session is left
    /// untouched and the future completes with an empty string.
    public CompletableFuture<String> compact() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String summary = doCompact("【上下文已压缩】");
                lastPromptTokens = 0; // history was just replaced; don't let a stale size trigger an immediate auto-compact
                return summary;
            } catch (LlmException e) { throw new RuntimeException(e); }
        }, executor);
    }

    /// Runs the single summarisation call and replaces the session history with the summary,
    /// prefixed by {@code header} (the manual /compact path and the automatic path use different
    /// headers so the user can tell which happened). Returns the raw summary text, or an empty
    /// string when the history is empty or the model returns nothing (session left untouched).
    private String doCompact(String header) throws LlmException {
        List<LlmMessage> history = session.getMessages();
        if (history.isEmpty()) {
            return "";
        }
        List<LlmMessage> request = new ArrayList<>(history.size() + 2);
        request.add(new LlmMessage("system", COMPACT_SYSTEM_PROMPT));
        request.addAll(history);
        request.add(new LlmMessage("user",
                "请把以上整段对话压缩成续写式摘要（目标 / 已完成 / 关键发现 / 下一步），只输出摘要本身。"));
        String summary = client.sendMessage(Collections.unmodifiableList(request)).join();
        if (summary == null || summary.isBlank()) {
            return "";
        }
        summary = summary.strip();
        session.clear();
        session.addMessage(new LlmMessage("assistant", header + "\n" + summary));
        return summary;
    }

    /// If auto-compaction is enabled and the running conversation has reached
    /// {@link #AUTO_COMPACT_RATIO} of the active model's context window, compresses the session
    /// into a continuation-style summary BEFORE the next turn is built, so long tool-heavy chats
    /// never hard-overflow the window.
    ///
    /// ## Threading / no-deadlock
    ///
    /// This runs INLINE on the agent's single-thread executor (the caller — {@link #doSend} /
    /// {@link #doSendStreaming} — is already executing on it), reusing the same synchronous
    /// {@link #doCompact(String)} the manual /compact path uses. It must NOT call the async
    /// {@link #compact()} (which submits to the same single-thread executor and would self-
    /// deadlock). {@link #doCompact} delegates the one summarisation call to the chat client,
    /// which runs it on ITS OWN executor, so the {@code join()} inside is safe from here.
    ///
    /// A compaction failure is swallowed: it must never abort the user's actual message.
    private void maybeAutoCompact() {
        if (!settings.isAutoCompactEnabled()) {
            return;
        }
        int threshold = (int) (resolveContextWindow() * AUTO_COMPACT_RATIO);
        if (estimateContextTokens() < threshold) {
            return;
        }
        try {
            String summary = doCompact("【上下文已自动压缩】");
            if (!summary.isEmpty()) {
                // Next turn repopulates lastPromptTokens from fresh provider usage; reset so a
                // turn that reports no usage can't immediately re-trigger on the stale figure.
                lastPromptTokens = 0;
            }
        } catch (LlmException | RuntimeException e) {
            // Best-effort: proceed with the user's turn even if summarisation failed.
        }
    }

    /// Resolves the active model's context window in tokens. The {@link ModelLibrary} entry for
    /// the configured model wins (authoritative per-model value), then the user-configured
    /// {@link AiSettings#getContextWindow()}, then a {@value #DEFAULT_CONTEXT_WINDOW}-token fallback.
    private int resolveContextWindow() {
        ModelLibrary.ModelInfo info = ModelLibrary.find(settings.getModel());
        if (info != null && info.getContextWindow() > 0) {
            return info.getContextWindow();
        }
        int configured = settings.getContextWindow();
        return configured > 0 ? configured : DEFAULT_CONTEXT_WINDOW;
    }

    /// Estimates the token size of the prompt the NEXT turn would send: the system prompt plus
    /// the stored conversation. Uses the larger of a char-count heuristic and the last provider-
    /// reported prompt-token count, so it stays robust whether or not the provider reports usage
    /// (the non-streaming path reports none, so the heuristic carries it there).
    private int estimateContextTokens() {
        String prompt = promptBuilder != null ? promptBuilder.build() : FALLBACK_SYSTEM_PROMPT;
        int chars = prompt.length();
        for (LlmMessage m : session.getMessages()) {
            String c = m.getContent();
            if (c != null) {
                chars += c.length();
            }
        }
        return Math.max(chars / CHARS_PER_TOKEN, lastPromptTokens);
    }

    /// System instruction used to derive a short conversation title from the opening exchange.
    private static final String TITLE_SYSTEM_PROMPT = String.join("\n",
            "You generate a very short title for a conversation, summarising what the user wants.",
            "Reply in the user's language (Chinese if the conversation is mainly Chinese): 4-10 Chinese",
            "characters, or 2-6 words in English. Output ONLY the title text — no quotes, no surrounding",
            "punctuation, no prefix like '标题:', no explanation, a single line, at most 24 characters.");

    /// Derives a concise title for this conversation from its opening exchange using a single
    /// non-streaming model call (no tools). Runs off the FX thread on the agent executor.
    ///
    /// Completes with a trimmed, single-line title (quotes/punctuation stripped, length-capped),
    /// or an empty string if the model returns nothing or the call fails — callers should keep
    /// their existing title in that case.
    public CompletableFuture<String> suggestTitle(String firstUser, @Nullable String firstAssistant) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder ctx = new StringBuilder("用户：").append(firstUser.strip());
                if (firstAssistant != null && !firstAssistant.isBlank()) {
                    String a = firstAssistant.strip();
                    if (a.length() > 300) a = a.substring(0, 300);
                    ctx.append("\n助手：").append(a);
                }
                ctx.append("\n\n只输出这段对话的简短标题。");
                List<LlmMessage> request = new ArrayList<>(2);
                request.add(new LlmMessage("system", TITLE_SYSTEM_PROMPT));
                request.add(new LlmMessage("user", ctx.toString()));
                String title = client.sendMessage(Collections.unmodifiableList(request)).join();
                if (title == null) {
                    return "";
                }
                title = title.strip();
                int nl = title.indexOf('\n');
                if (nl >= 0) {
                    title = title.substring(0, nl).strip();
                }
                // Strip a leading "标题:"/"Title:" label and any wrapping quotes the model may add.
                title = title.replaceFirst("(?i)^\\s*(标题|title)\\s*[:：]\\s*", "");
                title = title.replaceAll("^[\"'`「『《]+", "").replaceAll("[\"'`」』》]+$", "").strip();
                if (title.length() > 24) {
                    title = title.substring(0, 24).strip();
                }
                return title;
            } catch (RuntimeException e) {
                return "";
            }
        }, ANCILLARY); // off the per-agent executor: titling must not delay the user's next turn
    }

    public CompletableFuture<String> send(String userInput) {
        return CompletableFuture.supplyAsync(() -> {
            try { return doSend(userInput); }
            catch (LlmException e) { throw new RuntimeException(e); }
        }, executor);
    }

    public CompletableFuture<Void> sendStreaming(String userInput, LlmStreamCallback callback) {
        return sendStreaming(userInput, callback, null);
    }

    /// @param cancelled when it reports {@code true} (the user pressed Stop), the streamed assistant
    ///                  reply is NOT written to the session. The underlying HTTP request has already
    ///                  been sent, so it cannot be un-billed — this just prevents persisting an
    ///                  unwanted reply and lets the UI move on.
    public CompletableFuture<Void> sendStreaming(String userInput, LlmStreamCallback callback,
                                                 @Nullable java.util.function.BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            try { doSendStreaming(userInput, callback, cancelled); }
            catch (LlmException e) { throw new RuntimeException(e); }
            return null;
        }, executor);
    }

    private String doSend(String userInput) throws LlmException {
        // Auto-compact (if near the context limit) BEFORE the new user message is added, so the
        // message just typed is never folded into the summary and lost as a distinct turn.
        maybeAutoCompact();
        session.addMessage(new LlmMessage("user", userInput));
        String response = client.sendMessage(buildMessages()).join();
        session.addMessage(new LlmMessage("assistant", response));
        return response;
    }

    /// One-shot persister for the in-flight streaming turn's interrupted partial reply.
    /// Set at the start of each streaming turn, cleared when the turn terminates normally.
    /// {@link #persistInterrupted()} runs it immediately (from the UI's Stop handler) so the
    /// interruption marker lands in the session RIGHT AWAY instead of whenever the abandoned
    /// HTTP stream happens to end — which raced with the user's next message and inserted the
    /// marker out of order.
    private final java.util.concurrent.atomic.AtomicReference<Runnable> interruptPersist =
            new java.util.concurrent.atomic.AtomicReference<>();

    /// Immediately persists the current turn's partial reply with its interruption marker
    /// (idempotent — the late onComplete of the abandoned stream will detect the write and skip).
    /// Call from the Stop handler right after setting the cancel flag. No-op when no turn is live.
    public void persistInterrupted() {
        Runnable r = interruptPersist.get();
        if (r != null) {
            r.run();
        }
    }

    private void doSendStreaming(String userInput, LlmStreamCallback callback,
                                 @Nullable java.util.function.BooleanSupplier cancelled) throws LlmException {
        // See doSend: compact before adding the user message so it isn't summarised away.
        maybeAutoCompact();
        session.addMessage(new LlmMessage("user", userInput));

        // Raw streamed text of this turn. Appended on the adapter's callback thread, read from the
        // FX thread by persistInterrupted() — guard every access with `synchronized (full)`.
        final StringBuilder full = new StringBuilder();
        // Ensures the interrupted-partial message is written EXACTLY once, whether the Stop
        // handler (persistInterrupted) or the abandoned stream's own onComplete gets there first.
        final java.util.concurrent.atomic.AtomicBoolean interruptWritten =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable persister = () -> {
            if (!interruptWritten.compareAndSet(false, true)) {
                return;
            }
            String partial;
            synchronized (full) {
                partial = full.toString().strip();
            }
            // KEEP whatever the assistant already produced so the conversation stays coherent —
            // dropping it left two consecutive user messages and the model forgot the work it had
            // just started (a following "继续" had no context). Roles keep alternating.
            session.addMessage(new LlmMessage("assistant",
                    partial.isEmpty() ? "（本回合已被用户中断，未产出内容）"
                            : partial + "\n\n（本回合已被用户中断）"));
        };
        interruptPersist.set(persister);

        client.sendMessageStreaming(buildMessages(), new LlmStreamCallback() {
            @Nullable private LlmUsage usage;
            private boolean firstUsageCaptured = false;
            @Override public void onToken(String t) {
                synchronized (full) { full.append(t); }
                callback.onToken(t);
            }
            @Override public void onUsage(LlmUsage u) {
                // The first model call of a turn reports the input-token count of the persisted
                // context (system + history + this user message), before any in-turn tool churn —
                // record it as the running context size driving the next turn's compaction check.
                if (!firstUsageCaptured && u.getPromptTokens() > 0) {
                    lastPromptTokens = u.getPromptTokens();
                    firstUsageCaptured = true;
                }
                this.usage = u;
                callback.onUsage(u);
            }
            @Override public void onToolActivity(String name, String args) {
                // Segment boundary: keep the raw accumulator readable if this partial text is
                // later persisted as an interrupted reply (the adapter's own turn accumulator
                // handles separators for the normal completion path).
                synchronized (full) {
                    if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') {
                        full.append("\n\n");
                    }
                }
                callback.onToolActivity(name, args);
            }
            @Override public void onToolResult(String name, boolean success, String summary) { callback.onToolResult(name, success, summary); }
            @Override public boolean isCancelled() { return cancelled != null && cancelled.getAsBoolean(); }
            @Override public void onComplete(String c) {
                interruptPersist.compareAndSet(persister, null);
                if ((cancelled != null && cancelled.getAsBoolean()) || interruptWritten.get()) {
                    // The user stopped this turn: persist the partial with its interruption marker
                    // (idempotent — usually already written by persistInterrupted from the Stop
                    // handler, in which case this is a no-op).
                    persister.run();
                    return;
                }
                // The adapter's completion text is authoritative: it is the whole turn with leaked
                // tool markup stripped and segments separated (the raw token accumulator has
                // neither). Fall back to the raw text only when the adapter gave none.
                String f;
                if (c != null && !c.isEmpty()) {
                    f = c;
                } else {
                    synchronized (full) { f = full.toString(); }
                }
                LlmMessage aiMessage = new LlmMessage("assistant", f);
                if (usage != null) {
                    aiMessage.setUsage(usage);
                }
                session.addMessage(aiMessage);
                callback.onComplete(f);
            }
            @Override public void onError(LlmException e) {
                interruptPersist.compareAndSet(persister, null);
                if ((cancelled != null && cancelled.getAsBoolean()) || interruptWritten.get()) {
                    // Turn was stopped by the user; a late error from the abandoned stream is not
                    // worth surfacing. Make sure the interrupted partial is persisted and finish.
                    persister.run();
                    return;
                }
                callback.onError(e);
            }
        });
    }

    private List<LlmMessage> buildMessages() {
        List<LlmMessage> all = new ArrayList<>();
        String prompt = promptBuilder != null ? promptBuilder.build() : FALLBACK_SYSTEM_PROMPT;
        all.add(new LlmMessage("system", prompt));
        all.addAll(session.getMessages());
        return Collections.unmodifiableList(all);
    }

    public void clearSession() { session.clear(); }
}
