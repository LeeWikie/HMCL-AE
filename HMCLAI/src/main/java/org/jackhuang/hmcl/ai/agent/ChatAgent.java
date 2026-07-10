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

    /// Skill names whose triggers/BM25 matched THIS turn's user message — rendered by
    /// {@link AiPromptBuilder#buildVolatileSuffix} as the current turn's short
    /// {@code <runtime-guard type="skill_hint">} nudge. Deliberately NOT sticky across turns any
    /// more (the sticky set + eviction cap died with the "hit → inject full playbook, keep it for
    /// the session" design it existed for): a nudge is advice about ONE message, and any playbook
    /// the model chose to load via {@code load_skill} lives on in the conversation itself for as
    /// long as the tool result stays in context — re-loading is one cheap read-only call.
    /// Overwritten at the start of every turn; only touched on the agent's single-thread executor.
    private java.util.List<String> turnSkillHints = java.util.List.of();

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

    /// Shuts down this agent's dedicated single-thread executor. Call this when the agent is
    /// discarded (e.g. evicted from a session cache) — {@code newSingleThreadExecutor}'s core
    /// thread never times out on its own, so a discarded agent whose executor is never shut down
    /// permanently leaks one blocked worker thread. Idempotent; safe to call more than once.
    public void shutdown() {
        executor.shutdownNow();
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best-effort: the client's own shutdown (if any) must never prevent the agent
                // itself from being discarded.
            }
        }
    }

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
    /// non-streaming model call, then replaces the session history with that summary
    /// followed by the raw tail of the last {@value #COMPACT_KEEP_TURNS} turns.
    ///
    /// Runs on the agent's executor (off the FX thread). On success the session is
    /// {@link AiSession#clear() cleared}, an assistant message holding the summary is
    /// added, and the most recent {@value #COMPACT_KEEP_TURNS} turns (see
    /// {@link #lastRawTurns}) are re-appended verbatim — including their persisted
    /// {@link LlmMessage.ToolPayload tool records} — so a summary that compresses or
    /// loses fidelity on the last file diff/tool result doesn't destroy the only copy of
    /// it. The returned future completes with the raw summary text. If the conversation
    /// is empty (or the model returns nothing) the session is left untouched and the
    /// future completes with an empty string.
    public CompletableFuture<String> compact() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String summary = doCompact("【上下文已压缩】");
                lastPromptTokens = 0; // history was just replaced; don't let a stale size trigger an immediate auto-compact
                return summary;
            } catch (LlmException e) { throw new RuntimeException(e); }
        }, executor);
    }

    /// Number of most-recent turns kept RAW (not folded into the summary) across a compaction —
    /// see {@link #lastRawTurns}. A fixed small constant is deliberate: this is meant to stop the
    /// last turn or two's tool output/diffs from being lossily paraphrased, not to implement a
    /// token-budget-aware sliding window.
    private static final int COMPACT_KEEP_TURNS = 2;

    /// Model-education line prefixed to every compaction summary (borrow-list E1): without it,
    /// seeing its history abruptly replaced by a summary reads to the model like an alarm —
    /// inducing "wrap up quickly" behaviour — when it is actually routine housekeeping. Rides
    /// inside the same assistant summary message (manual /compact and auto-compact both pass
    /// through {@link #doCompact}); the system prompt's runtime-harness block teaches the same
    /// fact statically, this repeats it exactly where the model encounters the summary.
    /// Package-private for the compaction test suite.
    static final String COMPACT_EDUCATION_NOTE =
            "(This summary replaced older context as routine housekeeping. It is NOT a signal to "
                    + "hurry or wrap up — continue the task normally at full quality.)";

    /// Runs the single summarisation call and replaces the session history with the summary
    /// (prefixed by {@code header} — the manual /compact path and the automatic path use different
    /// headers so the user can tell which happened) followed by the raw tail of the last
    /// {@link #COMPACT_KEEP_TURNS} turns. Returns the raw summary text, or an empty string when
    /// the history is empty or the model returns nothing (session left untouched).
    private String doCompact(String header) throws LlmException {
        List<LlmMessage> history = session.getMessages();
        if (history.isEmpty()) {
            return "";
        }
        List<LlmMessage> request = new ArrayList<>(history.size() + 2);
        request.add(new LlmMessage("system", COMPACT_SYSTEM_PROMPT));
        for (LlmMessage m : history) {
            if (!m.isToolRecord()) { // tool records are UI artifacts, not conversation content
                request.add(m);
            }
        }
        request.add(new LlmMessage("user",
                "请把以上整段对话压缩成续写式摘要（目标 / 已完成 / 关键发现 / 下一步），只输出摘要本身。"));
        String summary = client.sendMessage(Collections.unmodifiableList(request)).join();
        if (summary == null || summary.isBlank()) {
            return "";
        }
        summary = summary.strip();
        // Snapshot the raw tail BEFORE clearing — session.clear() empties the session's own
        // backing list, but `history` (and the sublist view over it) is an independent copy
        // (see AiSession#getMessages), so the LlmMessage instances — including their
        // ToolPayload — stay intact to re-append after the summary.
        List<LlmMessage> keepRaw = lastRawTurns(history, COMPACT_KEEP_TURNS);
        session.clear();
        session.addMessage(new LlmMessage("assistant",
                header + "\n" + COMPACT_EDUCATION_NOTE + "\n" + summary));
        for (LlmMessage m : keepRaw) {
            session.addMessage(m);
        }
        return summary;
    }

    /// Returns the tail of {@code history} covering its last {@code keepTurns} turns, verbatim
    /// (same {@link LlmMessage} instances — tool records keep their {@link LlmMessage.ToolPayload}
    /// intact). A turn is a user message (any {@code "user"}-role message, including synthetic
    /// {@link LlmMessage#KIND_EVENT} ones) together with everything that follows it up to — but
    /// not including — the next user message (its tool records and assistant reply/segments).
    ///
    /// If {@code history} has fewer than {@code keepTurns} user messages, the whole history from
    /// the first turn onward is returned (nothing to summarise away). Any messages BEFORE the
    /// first user-role message (there normally are none — the session never stores the system
    /// prompt) are excluded either way.
    private static List<LlmMessage> lastRawTurns(List<LlmMessage> history, int keepTurns) {
        if (keepTurns <= 0 || history.isEmpty()) {
            return Collections.emptyList();
        }
        int turnsSeen = 0;
        int start = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                start = i;
                turnsSeen++;
                if (turnsSeen == keepTurns) {
                    break;
                }
            }
        }
        return turnsSeen == 0 ? Collections.emptyList() : history.subList(start, history.size());
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
        return resolveContextWindow(settings);
    }

    /// Static variant of {@link #resolveContextWindow()} for callers that only have the
    /// settings (e.g. the factory wiring the adapter's context budget).
    public static int resolveContextWindow(AiSettings settings) {
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
        // Sum the two halves independently (rather than calling the combined build()) so this
        // stays accurate even if buildMessages()'s split placement changes — e.g. the volatile
        // suffix living in the wire-only copy of the last message, not the system prompt.
        int chars = promptBuilder != null
                ? promptBuilder.buildStablePrefix().length() + promptBuilder.buildVolatileSuffix(turnSkillHints).length()
                : FALLBACK_SYSTEM_PROMPT.length();
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
        return suggestTitle(firstUser, firstAssistant, null);
    }

    /// Same as {@link #suggestTitle(String, String)} but lets the caller supply a dedicated
    /// client for the title call ("自动命名模型" — e.g. a cheaper/faster model than the chat
    /// one). `null` = Auto: use this agent's own chat client.
    public CompletableFuture<String> suggestTitle(String firstUser, @Nullable String firstAssistant,
                                                  @Nullable AiChatClient titleClient) {
        final AiChatClient titleCallClient = titleClient != null ? titleClient : client;
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
                String title = titleCallClient.sendMessage(Collections.unmodifiableList(request)).join();
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
        return sendStreaming(userInput, null, callback, cancelled);
    }

    /// @param kind optional presentation kind persisted on the user message (e.g.
    ///             {@link LlmMessage#KIND_EVENT} for a synthetic auto-continue / crash-report
    ///             turn): the model sees a normal user turn, the UI renders an event pill.
    public CompletableFuture<Void> sendStreaming(String userInput, @Nullable String kind,
                                                 LlmStreamCallback callback,
                                                 @Nullable java.util.function.BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            try { doSendStreaming(userInput, kind, callback, cancelled); }
            catch (LlmException e) { throw new RuntimeException(e); }
            return null;
        }, executor);
    }

    /// Matches {@code userInput} against skill triggers/BM25 and records the hits as THIS turn's
    /// nudge candidates ({@link #turnSkillHints}), replacing whatever the previous turn matched —
    /// the nudge is per-turn advice, not conversation state (see the field's doc for why the old
    /// sticky set is gone). Runs on the agent executor before each turn's request is built.
    private void updateTurnSkillHints(String userInput) {
        if (promptBuilder == null) {
            return;
        }
        turnSkillHints = promptBuilder.matchSkills(userInput);
    }

    private String doSend(String userInput) throws LlmException {
        // Auto-compact (if near the context limit) BEFORE the new user message is added, so the
        // message just typed is never folded into the summary and lost as a distinct turn.
        maybeAutoCompact();
        updateTurnSkillHints(userInput);
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

    private void doSendStreaming(String userInput, @Nullable String kind, LlmStreamCallback callback,
                                 @Nullable java.util.function.BooleanSupplier cancelled) throws LlmException {
        // See doSend: compact before adding the user message so it isn't summarised away.
        maybeAutoCompact();
        updateTurnSkillHints(userInput);
        // Groups this turn's messages (user input + tool records + assistant reply) for replay.
        final String turnId = java.util.UUID.randomUUID().toString();
        LlmMessage userMessage = new LlmMessage("user", userInput);
        userMessage.setTurnId(turnId);
        userMessage.setKind(kind);
        session.addMessage(userMessage);

        // Raw streamed text of this turn. Appended on the adapter's callback thread, read from the
        // FX thread by persistInterrupted() — guard every access with `synchronized (full)`.
        final StringBuilder full = new StringBuilder();
        // Reasoning/"thinking" text (models that expose it, e.g. DeepSeek-R1) accumulated across the
        // turn, persisted on the assistant message so a reloaded session can show a collapsed card.
        final StringBuilder reasoning = new StringBuilder();
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
            LlmMessage interrupted = new LlmMessage("assistant",
                    partial.isEmpty() ? "（本回合已被用户中断，未产出内容）"
                            : partial + "\n\n（本回合已被用户中断）");
            interrupted.setTurnId(turnId);
            interrupted.setModel(settings.getModel());
            session.addMessage(interrupted);
        };
        interruptPersist.set(persister);

        // Tag this turn's full trace (request/response/tool/guard events) with session + turn id.
        client.beginTurn(session.getId(), turnId);
        client.sendMessageStreaming(buildMessages(), new LlmStreamCallback() {
            @Nullable private LlmUsage usage;
            private boolean firstUsageCaptured = false;
            /// Arguments of the most recent onToolActivity, consumed by the matching
            /// onToolResult (the adapter runs tools serially, so activity→result pairs
            /// never interleave within a turn).
            @Nullable private String lastToolArgs;
            /// The most recently persisted segment message (see onSegmentComplete), so onComplete
            /// can attach the final usage report to it instead of writing a duplicate message.
            @Nullable private LlmMessage lastSegmentMessage;
            /// How much of `reasoning` has already been attached to a segment — each new segment
            /// gets only the DELTA since the last one, never the whole accumulator, so a multi-cycle
            /// turn's reasoning is neither duplicated across segments nor dropped after the first.
            private int reasoningAttachedThrough = 0;
            @Override public void onToken(String t) {
                synchronized (full) { full.append(t); }
                callback.onToken(t);
            }
            @Override public void onReasoningToken(String t) {
                synchronized (reasoning) { reasoning.append(t); }
                callback.onReasoningToken(t);
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
                lastToolArgs = args;
                callback.onToolActivity(name, args);
            }
            @Override public void onToolResult(String name, boolean success, String summary) {
                // Persist a structured record of every tool invocation so reloading the session
                // rebuilds the tool cards (previously tool activity vanished once the turn ended
                // — nothing was persisted, which was reported as "工具卡片完成后消失").
                LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
                payload.name = name;
                payload.argsJson = abbreviate(lastToolArgs, 2000);
                payload.resultText = abbreviate(summary, 4000);
                payload.success = success;
                lastToolArgs = null;
                session.addMessage(LlmMessage.toolRecord(payload, turnId));
                callback.onToolResult(name, success, summary);
            }
            @Override public boolean isCancelled() { return cancelled != null && cancelled.getAsBoolean(); }
            @Override public void onSegmentComplete(String segment) {
                if (segment == null || segment.isBlank()) {
                    return;
                }
                if ((cancelled != null && cancelled.getAsBoolean()) || interruptWritten.get()) {
                    // Turn is being/was stopped — leave this to the interrupted-partial persister
                    // instead of also writing a normal segment message for it.
                    return;
                }
                // Persisting each cycle's segment AS ITS OWN message (instead of waiting for
                // onComplete to persist one combined blob) is what keeps a reloaded session's
                // bubbles matching what the live stream actually rendered — multiple bubbles during
                // streaming were previously getting collapsed into one on reload.
                LlmMessage segMessage = new LlmMessage("assistant", segment);
                segMessage.setTurnId(turnId);
                segMessage.setModel(settings.getModel());
                if (usage != null) {
                    segMessage.setUsage(usage);
                }
                String reasoningDelta;
                synchronized (reasoning) {
                    reasoningDelta = reasoning.substring(Math.min(reasoningAttachedThrough, reasoning.length())).strip();
                    reasoningAttachedThrough = reasoning.length();
                }
                if (!reasoningDelta.isEmpty()) {
                    segMessage.setReasoning(reasoningDelta);
                }
                session.addMessage(segMessage);
                lastSegmentMessage = segMessage;
            }
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
                if (lastSegmentMessage == null) {
                    // No segment was ever persisted via onSegmentComplete (e.g. the adapter reported
                    // no text through that path at all) — fall back to the old single-message
                    // behaviour so a turn never silently produces zero messages.
                    if (!f.isBlank()) {
                        LlmMessage aiMessage = new LlmMessage("assistant", f);
                        if (usage != null) {
                            aiMessage.setUsage(usage);
                        }
                        String r;
                        synchronized (reasoning) { r = reasoning.toString().strip(); }
                        if (!r.isEmpty()) {
                            aiMessage.setReasoning(r);
                        }
                        aiMessage.setTurnId(turnId);
                        aiMessage.setModel(settings.getModel());
                        session.addMessage(aiMessage);
                    }
                } else if (usage != null) {
                    // Refresh usage on the last segment in case a later report arrived after it was
                    // first persisted (usage is reported once per model call / cycle).
                    lastSegmentMessage.setUsage(usage);
                }
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

    /// Fraction of the context window the REQUEST may fill (the rest is head-room for the reply).
    private static final double REQUEST_BUDGET_RATIO = 0.8;

    /// Truncates {@code s} to at most {@code max} chars (marker appended); null-safe.
    @Nullable
    private static String abbreviate(@Nullable String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…[truncated]";
    }

    private List<LlmMessage> buildMessages() {
        List<LlmMessage> all = new ArrayList<>();
        String stablePrefix = promptBuilder != null ? promptBuilder.buildStablePrefix() : FALLBACK_SYSTEM_PROMPT;
        all.add(new LlmMessage("system", stablePrefix));
        // Tool records are history/UI artifacts — never sent to the model (a bare "tool" role
        // without the provider's tool-call plumbing would be rejected by the APIs).
        List<LlmMessage> outbound = new ArrayList<>();
        for (LlmMessage m : session.getMessages()) {
            if (!m.isToolRecord()) {
                outbound.add(m);
            }
        }
        List<LlmMessage> trimmed = trimToRequestBudget(outbound, stablePrefix.length());
        attachVolatileSuffix(trimmed);
        all.addAll(trimmed);
        return Collections.unmodifiableList(all);
    }

    /// Appends {@link AiPromptBuilder#buildVolatileSuffix} to the LAST message of {@code trimmed}
    /// (the current turn's freshly-added user message) — everything that changes turn-to-turn
    /// (runtime context, the per-turn skill nudge, policy, language directive, …) lives here instead
    /// of the system message, so a byte changing there doesn't invalidate a provider's prefix-hash
    /// cache over {@link #buildMessages()}'s stable system prompt + the older, unchanging turns of
    /// conversation history.
    ///
    /// Mutates {@code trimmed} IN PLACE (replacing its last element's reference) but never touches
    /// the underlying {@link LlmMessage} object itself — that object may be the SAME instance held
    /// by {@code session.getMessages()}, and mutating it would leak this synthetic wire-only block
    /// into the chat UI and permanently bake a stale runtime snapshot into persisted history.
    private void attachVolatileSuffix(List<LlmMessage> trimmed) {
        if (promptBuilder == null || trimmed.isEmpty()) {
            return;
        }
        int lastIndex = trimmed.size() - 1;
        LlmMessage original = trimmed.get(lastIndex);
        // Pass the CURRENT turn's raw text so buildVolatileSuffix can decide whether dev mode
        // (a literal "[Dev]" tag — see AiPromptBuilder#isDevModeTriggered) fires THIS turn only;
        // like `turnSkillHints` (this turn's skill-nudge candidates), it is strictly per-turn.
        String volatileSuffix = promptBuilder.buildVolatileSuffix(turnSkillHints, original.getContent());
        if (volatileSuffix.isBlank()) {
            return;
        }
        LlmMessage wireOnly = new LlmMessage(original.getRole(),
                original.getContent() + "\n\n<turn-context>\n" + volatileSuffix + "\n</turn-context>");
        wireOnly.setKind(original.getKind());
        wireOnly.setTurnId(original.getTurnId());
        trimmed.set(lastIndex, wireOnly);
    }

    /// Returns the NEWEST tail of {@code history} that fits the request token budget
    /// (context window × {@value #REQUEST_BUDGET_RATIO}, minus the system prompt), never
    /// fewer than the last two messages. This is a request-scope view only: the stored
    /// session keeps its full history — an earlier design pruned the session itself and
    /// persisted the truncated list, permanently deleting the earliest messages from disk.
    private List<LlmMessage> trimToRequestBudget(List<LlmMessage> history, int promptChars) {
        int budgetTokens = (int) (resolveContextWindow() * REQUEST_BUDGET_RATIO);
        long budgetChars = (long) budgetTokens * CHARS_PER_TOKEN - promptChars;
        long chars = 0;
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String c = history.get(i).getContent();
            chars += c != null ? c.length() : 0;
            if (chars > budgetChars && history.size() - i > 2) {
                start = i + 1;
                break;
            }
        }
        return start == 0 ? history : history.subList(start, history.size());
    }

    public void clearSession() { session.clear(); }
}
