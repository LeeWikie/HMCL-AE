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
import dev.langchain4j.agent.tool.ToolSpecification;
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
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.trace.TraceContext;
import org.jackhuang.hmcl.ai.trace.TraceRecorder;
import org.jackhuang.hmcl.ai.util.AiLog;
import dev.langchain4j.model.output.TokenUsage;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
public final class LangChain4jChatAdapter implements AiChatClient, AutoCloseable {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    @Nullable
    private final LangChain4jToolAdapter toolAdapter;
    private final ExecutorService executor;

    /// Trace context for the CURRENT streaming turn, set by {@link #beginTurn} just before
    /// {@link #sendMessageStreaming}. Snapshotted into a local at the start of the streaming call
    /// so a later turn overwriting this field can't corrupt an in-flight turn's trace tags.
    /// {@link org.jackhuang.hmcl.ai.trace.TraceContext#NONE} = not tracing.
    private volatile org.jackhuang.hmcl.ai.trace.TraceContext pendingTrace =
            org.jackhuang.hmcl.ai.trace.TraceContext.NONE;

    @Override
    public void beginTurn(@Nullable String sessionId, @Nullable String turnId) {
        this.pendingTrace = new TraceContext(sessionId, turnId);
    }

    /// Records one trace event for the given turn context (no-op when not tracing).
    private static void trace(TraceContext tc, JsonObject event) {
        if (tc.active()) {
            TraceRecorder.record(tc.sessionId(), event);
        }
    }

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

    /// Shuts down this adapter's dedicated cached thread pool. Call this when the adapter (and
    /// whatever {@link org.jackhuang.hmcl.ai.agent.ChatAgent} wraps it) is discarded — a cached
    /// pool's idle threads self-terminate after the default 60s keepAlive so this isn't a hard
    /// leak, but shutting it down explicitly avoids unmanaged thread churn from short-lived
    /// adapters (e.g. one per connection test). Idempotent; safe to call more than once.
    @Override
    public void close() {
        executor.shutdownNow();
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

    /// Display name of the active model, purely for {@code AiLog} lines. Set once by
    /// {@code ChatAgentFactory} right after construction; {@code ""} until then.
    private volatile String modelName = "";

    public void setModelName(String modelName) {
        this.modelName = modelName != null ? modelName : "";
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
    ///
    /// When over budget and a trace session is active, the FULL text is offloaded to disk (see
    /// {@link TraceRecorder#resolveToolOutputFile}) and the model gets a head/tail preview plus the
    /// absolute path instead of a blind substring cut — a long crash log or mod list no longer loses
    /// its middle permanently, the model can `read`/`grep` the file back if it turns out to matter.
    /// Falls back to the old substring-only truncation when there's no session to offload against or
    /// the write fails.
    private ToolExecutionResultMessage truncateToolResult(ToolExecutionRequest req,
                                                          ToolExecutionResultMessage result,
                                                          TraceContext tc, int cycle, int callIndex) {
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
        Path offloadFile = TraceRecorder.resolveToolOutputFile(
                tc != null ? tc.sessionId() : null, tc != null ? tc.turnId() : null, req.name(), cycle, callIndex);
        if (offloadFile != null) {
            try {
                Files.createDirectories(offloadFile.getParent());
                // Redact secrets before they hit disk — mirrors TraceRecorder.record(), which redacts
                // every event written to the .jsonl trace living right next to this offload file; this
                // file otherwise carried the full, unredacted text with no equivalent guarantee.
                Files.writeString(offloadFile, org.jackhuang.hmcl.ai.util.Redactor.redact(text), StandardCharsets.UTF_8);
                int edge = Math.max(200, limit / 4);
                String head = text.substring(0, Math.min(edge, text.length()));
                String tail = text.length() > edge ? text.substring(Math.max(edge, text.length() - edge)) : "";
                String preview = head
                        + "\n…[" + (text.length() - head.length() - tail.length()) + " chars omitted]…\n"
                        + tail
                        + "\n\n[full output (" + text.length() + " chars) saved to " + offloadFile
                        + " — use read/grep on that path if you need the omitted part]";
                return ToolExecutionResultMessage.from(req, preview);
            } catch (IOException e) {
                AiLog.warn("[AI] 工具输出落盘失败，回退为截断：" + e.getMessage());
                // fall through to the substring-only truncation below
            }
        }
        String truncated = text.substring(0, limit)
                + "\n…[truncated " + (text.length() - limit) + " chars by tool-result limit —"
                + " re-run the tool with narrower arguments (e.g. read with startLine/maxLines)"
                + " if you need the rest]";
        return ToolExecutionResultMessage.from(req, truncated);
    }

    /// Placeholder that replaces an evicted old tool result. Kept short and explicit so the
    /// model knows the data is re-obtainable rather than mysteriously missing. Public because
    /// {@code AiPromptBuilder} embeds this exact text in its one-time "context housekeeping is
    /// not an error" education block (borrow-list E1) — referencing the constant keeps the taught
    /// text from ever drifting from the injected one.
    public static final String EVICTED_TOOL_RESULT =
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
        // Snapshot the trace context now so a later turn overwriting pendingTrace can't retag this one.
        org.jackhuang.hmcl.ai.trace.TraceContext tc = pendingTrace;
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));
        streamTurn(conversation, callback, 0, new LoopGuardState(), new StringBuilder(), tc);
    }

    /// After this many identical (tool, raw-arguments) calls in one turn, stop actually running
    /// the tool and feed back a BLOCKED notice instead — breaks the model out of a loop where it
    /// keeps re-issuing the same failing WRITE call until the cycle cap is hit. Narrowed (borrow-list
    /// 2.1) to only pre-execution-block {@link ToolPermission#CONTROLLED_WRITE}/
    /// {@link ToolPermission#DANGEROUS_WRITE} tools — a write is worth stopping before a 3rd
    /// identical attempt; a read-only call (including check_job/list_jobs polling) is harmless to
    /// actually execute and is instead caught, if it's truly going nowhere, by
    /// {@link #LOOP_SIGNATURE_WINDOW}.
    private static final int DUP_CALL_LIMIT = 3;

    /// After this many CONSECUTIVE non-progress tool results in one turn (failures, or "technically
    /// successful but zero new information" results — see {@link #isWaitNoOpResult}), inject a
    /// one-off guidance nudge telling the model to stop flailing and either answer with what it has
    /// or ask the user — catches "no progress" loops that keep varying arguments so the identical-call
    /// guard never trips.
    private static final int NO_PROGRESS_LIMIT = 6;

    /// Sliding window size for the general loop-signature detector (borrow-list 2.1): how many of
    /// the most recent EXECUTED tool-call signatures are kept for repeat counting.
    private static final int LOOP_SIGNATURE_WINDOW = 10;
    /// Repeat counts within the window that trigger an escalating soft nudge (wording gets firmer
    /// each step) before {@link #LOOP_SIGNATURE_HARD_STOP} force-ends the turn.
    private static final java.util.Set<Integer> LOOP_SIGNATURE_SOFT_THRESHOLDS = java.util.Set.of(3, 4, 5);
    private static final int LOOP_SIGNATURE_HARD_STOP = 6;

    /// Todo-discipline guard (a real trace's own failure mode — see {@link LoopGuardState
    /// #outstandingTodoContents}): after this many tool-call CYCLES with at least one still-
    /// unfinished todo item and no {@code todo_write} call touching the list at all, nudge the
    /// model to go back and reconcile the checklist with reality. Deliberately generous — the same
    /// trace that motivated this shows a HEALTHY gap between todo_write calls can legitimately span
    /// well over a dozen tool-call rows (installing a whole batch of mods is one checklist phase),
    /// so this must not fire on ordinary long single-phase work, only on a phase that drags on for
    /// most of the turn's cycle budget with the checklist never revisited.
    private static final int TODO_STALE_CYCLE_LIMIT = 15;

    /// Cap on how many times the todo-discipline nudges (silent-discard + stale) may fire in one
    /// turn — mirrors {@link #VERIFY_ON_STOP_LIMIT}'s "still let the model finish eventually" stance.
    private static final int TODO_NUDGE_LIMIT = 2;

    /// Runs the actual tool.execute() work for an all-READ_ONLY batch of 2+ independent calls
    /// within one cycle concurrently (see the tool-execution loop in {@link #streamTurn}) — a real
    /// trace found several independent `search` calls in one response running strictly
    /// back-to-back, each paying its own full network round-trip serially. Cached + daemon: this
    /// is bursty I/O-bound work, not a fixed worker pool, and must never block JVM shutdown.
    private static final ExecutorService PARALLEL_TOOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-parallel-tool-exec");
        t.setDaemon(true);
        return t;
    });

    /// Tool names that are declared {@link ToolPermission#READ_ONLY} (correctly — they don't
    /// mutate host state) but must NEVER be dispatched via the parallel batch path because they
    /// drive single-instance, stateful UI: `ask` (see {@code AIMainPage}'s single `activeAsk`
    /// future / `askPanel` widget field) shows one question panel at a time and cancels whatever
    /// panel is already pending before showing a new one — running 2+ `ask` calls from one
    /// response concurrently would have the second call's cancellation silently kill the first's
    /// still-pending answer. A batch containing any of these names always falls back to the
    /// sequential path, which already handles them correctly one at a time.
    private static final java.util.Set<String> PARALLEL_UNSAFE_TOOL_NAMES = java.util.Set.of("ask");

    /// Per-turn mutable state threaded through the recursive {@link #streamTurn}/
    /// {@link #executeWithLoopGuard} calls — replaces what used to be a bare fingerprint-count
    /// {@code Map}. A fresh instance is created per {@link #sendMessageStreaming}/{@code doChat} call
    /// (i.e. scoped to one turn, not one session). Package-private (not {@code private}) so tests
    /// can drive the ledger directly — mirrors {@link #stripLeakedToolMarkup}.
    static final class LoopGuardState {
        /// Exact (tool+raw-arguments) fingerprint → repeat count. Only consulted for the
        /// pre-execution dup block on write-permission tools (see {@link #DUP_CALL_LIMIT}).
        final java.util.Map<String, Integer> fingerprintCounts = new java.util.HashMap<>();
        /// Operations the user explicitly DECLINED this turn (borrow-list A1): a refusal is a
        /// terminal state, so a repeat of the same (tool, canonical-arguments) call — argument
        /// order/decoration perturbations included — is short-circuited pre-execution instead of
        /// re-opening the confirmation dialog until {@link #DUP_CALL_LIMIT} finally trips. Lives
        /// beside {@link #fingerprintCounts}; per-turn like everything else here, because the user
        /// may change their mind on their next real message.
        final TerminalDenialRegistry terminalDenials = new TerminalDenialRegistry();
        /// Sliding window of the last {@link #LOOP_SIGNATURE_WINDOW} executed-call signatures,
        /// oldest first (see {@link #buildLoopSignature}).
        final java.util.ArrayDeque<String> signatureWindow = new java.util.ArrayDeque<>();
        /// Consecutive tool results that made no real progress this turn.
        int noProgressStreak = 0;
        /// Ledger of outstanding "tool+target" signatures (see {@link #riskyWriteSignature}) for
        /// successful CONTROLLED_WRITE/DANGEROUS_WRITE domain-tool calls (generic write/edit
        /// excluded — see {@link #isVerifiableRiskyWrite}) that have NOT yet been followed by a
        /// plausibly-verifying READ_ONLY call (see {@link #clearVerifiedEntries}). Replaces what
        /// used to be a single per-turn boolean — that flag was cleared by ANY successful
        /// READ_ONLY call anywhere later in the turn, so a multi-write turn that only re-checked
        /// the LAST write silently counted every earlier write as "verified" too. Drives the
        /// verify-on-stop nudge (borrow-list 2.13 / failure mode E): nudge while this is non-empty.
        final java.util.Set<String> outstandingRiskyWrites = new java.util.LinkedHashSet<>();
        /// How many times the verify-on-stop nudge has already fired this turn (capped by
        /// {@link #VERIFY_ON_STOP_LIMIT}).
        int verifyNudgeCount = 0;
        /// Todo-discipline ledger (real-trace failure mode — see the walkthrough this borrow-list
        /// item cites): the CONTENT of every item from the model's most recent successful
        /// {@code todo_write} call that was NOT yet marked "done". Replaced wholesale on each new
        /// successful {@code todo_write} call (see {@link #updateTodoLedger}) — the point isn't the
        /// set itself but what {@code updateTodoLedger} returns when an old entry silently vanishes
        /// instead of surviving into the new list (as still-outstanding OR as "done"): that's the
        /// real trace's bug, a 9-item checklist abandoned mid-task and replaced by an unrelated new
        /// one, its unfinished items — some already actually done by then — never checked off.
        final java.util.LinkedHashSet<String> outstandingTodoContents = new java.util.LinkedHashSet<>();
        /// Tool-call CYCLES since the last successful {@code todo_write} call, while
        /// {@link #outstandingTodoContents} is non-empty. Reset to 0 by every successful
        /// {@code todo_write} call (whether or not it drops anything); only ticks up while there is
        /// at least one outstanding item for the model to plausibly be neglecting. Drives the
        /// {@link #TODO_STALE_CYCLE_LIMIT} nudge.
        int cyclesSinceTodoUpdate = 0;
        /// How many times either todo-discipline nudge (silent-discard or stale) has already fired
        /// this turn (capped by {@link #TODO_NUDGE_LIMIT}).
        int todoNudgeCount = 0;
    }

    /// Cap on how many times {@link #VERIFY_ON_STOP_LIMIT verify-on-stop} may force an extra cycle
    /// in one turn — a model that keeps re-triggering it (e.g. genuinely can't verify) must still be
    /// allowed to finish rather than loop forever.
    private static final int VERIFY_ON_STOP_LIMIT = 2;

    /// Whether a successful tool result should count as a "risky write that needs verifying" for
    /// the verify-on-stop guard. Narrowed to CONTROLLED_WRITE/DANGEROUS_WRITE domain tools — the
    /// generic {@code write}/{@code edit} filesystem tools are excluded because they're a catch-all
    /// (scratch files, docs, notes) where blanket verification would be noisy for little value; the
    /// net is meant to catch things like "changed instance memory but never confirmed it landed".
    private static boolean isVerifiableRiskyWrite(String toolName, ToolPermission permission) {
        boolean writeLike = permission == ToolPermission.CONTROLLED_WRITE
                || permission == ToolPermission.DANGEROUS_WRITE;
        return writeLike && !"write".equals(toolName) && !"edit".equals(toolName);
    }

    /// Argument keys, in priority order, treated as identifying the specific resource a domain-tool
    /// call acts on (see {@link #extractRiskyWriteTarget}). Drawn from the real merged domain tools'
    /// declared schemas (InstanceTool/NbtTool/AccountTool/JobTool/GameTool/SetSkinTool) — narrower
    /// resource identifiers (a specific mod/world/job) are listed before the broader "instance" one,
    /// since a call that names both is acting on the narrower resource.
    private static final List<String> RISKY_WRITE_TARGET_KEYS = List.of(
            "mod", "world", "jobId", "job", "account", "uuid", "username", "path", "instance", "id", "name");

    /// Best-effort "which specific resource does this call touch" extraction from a tool call's raw
    /// JSON arguments, used to scope the verify-on-stop ledger (see {@link LoopGuardState
    /// #outstandingRiskyWrites}) below whole-tool granularity — e.g. distinguishing a write to
    /// instance "A" from one to instance "B" made via the same domain tool name. Returns `""`
    /// (untargeted — matches broadly, see {@link #clearVerifiedEntries}) when the arguments are
    /// missing/unparseable or none of {@link #RISKY_WRITE_TARGET_KEYS} is present (e.g. a bare
    /// `list`-style call with no target at all).
    ///
    /// Package-private (not {@code private}) so tests can exercise the matching heuristic directly —
    /// mirrors {@link #stripLeakedToolMarkup}.
    static String extractRiskyWriteTarget(@Nullable String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            Object parsed = SIGNATURE_GSON.fromJson(argumentsJson, Object.class);
            if (parsed instanceof java.util.Map<?, ?> map) {
                for (String key : RISKY_WRITE_TARGET_KEYS) {
                    Object v = map.get(key);
                    if (v instanceof String s && !s.isBlank()) {
                        return key + "=" + s;
                    }
                }
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            // Unparseable arguments: fall through to the untargeted "" — matches broadly rather
            // than never being clearable.
        }
        return "";
    }

    /// Ledger key for one risky-write call: tool name + its extracted target (see
    /// {@link #extractRiskyWriteTarget}). Two writes to DIFFERENT targets via the same tool name
    /// get distinct entries; repeat writes to the SAME target collapse into one (a {@code Set}, not
    /// a list — a single later verifying read is enough for however many times that same target was
    /// written this turn). Package-private for direct test coverage.
    static String riskyWriteSignature(String toolName, @Nullable String argumentsJson) {
        return toolName + "|" + extractRiskyWriteTarget(argumentsJson);
    }

    /// Removes every ledger entry that this successful READ_ONLY call plausibly verifies: same tool
    /// name, AND either side is untargeted (a targetless write, e.g. "create", or a targetless read,
    /// e.g. a bare "list" that surveys everything for that tool — either is treated as verifying
    /// broadly) OR both sides name the SAME target. A read of instance "B" does NOT clear an
    /// outstanding write to instance "A" made via the same tool — this is the crux of the fix: the
    /// old single-boolean version cleared on ANY successful READ_ONLY call anywhere in the turn,
    /// regardless of tool or target. Package-private for direct test coverage.
    static void clearVerifiedEntries(LoopGuardState state, String readToolName, @Nullable String readArguments) {
        if (state.outstandingRiskyWrites.isEmpty()) {
            return;
        }
        String readTarget = extractRiskyWriteTarget(readArguments);
        state.outstandingRiskyWrites.removeIf(signature -> {
            int sep = signature.indexOf('|');
            String writeTool = signature.substring(0, sep);
            String writeTarget = signature.substring(sep + 1);
            if (!writeTool.equals(readToolName)) {
                return false;
            }
            return writeTarget.isEmpty() || readTarget.isEmpty() || writeTarget.equals(readTarget);
        });
    }

    /// Parses a {@code todo_write} call's raw arguments JSON into an ordered content → status map.
    /// The tool's own schema (see HMCL's {@code TodoWriteTool}) nests a JSON-ARRAY-ENCODED STRING
    /// inside the top-level arguments object — {@code {"todos": "[{\"content\":...,\"status\":...}]"}}
    /// — because the structured-schema parser only supports flat fields; this unwraps both layers.
    /// Also tolerates a provider that skips the string-encoding and sends `todos` as a real nested
    /// array directly. Returns an empty map for anything unparseable/absent, matching
    /// {@link #extractRiskyWriteTarget}'s "fall back broad rather than crash" stance.
    ///
    /// Deliberately reimplements this parse here rather than depending on HMCL's {@code TodoWriteTool}
    /// — HMCLAI (this module) is the JavaFX-free AI core that HMCL (the UI module) depends ON, never
    /// the other way around, so it cannot reference a UI-module class.
    ///
    /// Package-private for direct test coverage — mirrors {@link #extractRiskyWriteTarget}.
    static java.util.LinkedHashMap<String, String> parseTodoWriteArguments(@Nullable String argumentsJson) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return result;
        }
        try {
            Object parsed = SIGNATURE_GSON.fromJson(argumentsJson, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?> outer)) {
                return result;
            }
            Object todosField = outer.get("todos");
            List<?> list;
            if (todosField instanceof String s && !s.isBlank()) {
                Object inner = SIGNATURE_GSON.fromJson(s, Object.class);
                if (!(inner instanceof List<?> l)) {
                    return result;
                }
                list = l;
            } else if (todosField instanceof List<?> l2) {
                list = l2;
            } else {
                return result;
            }
            for (Object item : list) {
                if (!(item instanceof java.util.Map<?, ?> m)) {
                    continue;
                }
                Object content = m.get("content");
                if (content instanceof String c && !c.isBlank()) {
                    Object status = m.get("status");
                    result.put(c.strip(), status instanceof String st ? st : "pending");
                }
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            // Unparseable: fall through to the empty map already built (possibly partial, but a
            // partial/garbled todo_write call is exactly when we least want to trust its content).
            return new java.util.LinkedHashMap<>();
        }
        return result;
    }

    /// Applies a SUCCESSFUL {@code todo_write} call to {@link LoopGuardState#outstandingTodoContents}
    /// and returns the item CONTENTS that were outstanding (not "done") in the PREVIOUS list but are
    /// silently missing from the new one entirely — neither carried over as still-outstanding NOR
    /// marked "done". That's the real trace's bug (see {@link LoopGuardState#outstandingTodoContents}'s
    /// doc): a checklist abandoned mid-task and replaced wholesale, so items that were actually
    /// finished by then never got checked off, and items still pending just vanished with no
    /// handoff. A legitimate status update (still-outstanding item now "done", or unchanged) is NOT
    /// a drop — only content that disappears from the list altogether counts.
    ///
    /// Always updates the ledger and resets {@link LoopGuardState#cyclesSinceTodoUpdate} to 0,
    /// regardless of whether anything was dropped — a call that touches the list at all means the
    /// model didn't neglect it this cycle, even if what it did with the content is itself wrong (the
    /// caller nudges separately for that). Package-private for direct test coverage.
    static List<String> updateTodoLedger(LoopGuardState state, @Nullable String argumentsJson) {
        java.util.LinkedHashMap<String, String> newTodos = parseTodoWriteArguments(argumentsJson);
        List<String> dropped = new ArrayList<>();
        for (String oldContent : state.outstandingTodoContents) {
            if (!newTodos.containsKey(oldContent)) {
                dropped.add(oldContent);
            }
        }
        state.outstandingTodoContents.clear();
        for (java.util.Map.Entry<String, String> e : newTodos.entrySet()) {
            if (!"done".equals(e.getValue())) {
                state.outstandingTodoContents.add(e.getKey());
            }
        }
        state.cyclesSinceTodoUpdate = 0;
        return dropped;
    }

    /// A "technically successful" result that carries zero new information for the no-progress
    /// guard: check_job reporting the job is still running, or sleep reporting it waited. Both are
    /// legitimate, non-error tool results (the generic Error:/BLOCKED: prefix check alone would
    /// treat them as "success"), but neither moves the task forward — without this carve-out, a
    /// model alternating sleep+check_job forever never trips {@link #NO_PROGRESS_LIMIT} because
    /// every individual call "succeeds".
    private static boolean isWaitNoOpResult(String toolName, String resultText) {
        if ("sleep".equals(toolName)) {
            return true;
        }
        // check_job used to be its own tool name; it's now the `job` domain tool's `check` action.
        // Only that action's result text ever contains the marker (list/cancel never do), so
        // gating on the marker text alone (once the tool name matches) is still precise.
        return "job".equals(toolName) && resultText.contains(AiJobManager.STILL_RUNNING_MARKER);
    }

    /// Package-private (not {@code private}): {@link TerminalDenialRegistry} reuses this instance
    /// and {@link #sortKeysForSignature} for its own canonical argument serialization.
    static final com.google.gson.Gson SIGNATURE_GSON = new com.google.gson.Gson();

    /// Builds a loop-detection signature for one EXECUTED tool call: tool name + JSON-key-sorted
    /// arguments + the result text, both with every digit run blanked out. Blanking digits is
    /// essential — e.g. check_job's "Still running (Ns elapsed)" text embeds a live elapsed-seconds
    /// count that would otherwise make every poll's signature unique and defeat the detector
    /// entirely; the same blanking on the arguments side collapses sleep(5)/sleep(10)/sleep(15) into
    /// one signature too. Sorting object keys collapses equivalent calls the model happened to
    /// re-order. Never fed back to a tool or the model — signature-only.
    private static String buildLoopSignature(String toolName, @Nullable String arguments, String resultText) {
        return toolName + "|" + normalizeForSignature(arguments) + "|" + normalizeForSignature(resultText);
    }

    private static String normalizeForSignature(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            Object parsed = SIGNATURE_GSON.fromJson(text, Object.class);
            String canonical = parsed != null ? SIGNATURE_GSON.toJson(sortKeysForSignature(parsed)) : text;
            return canonical.replaceAll("\\d+", "#");
        } catch (com.google.gson.JsonSyntaxException e) {
            return text.replaceAll("\\d+", "#");
        }
    }

    /// Recursively rewrites parsed JSON (Gson's raw {@code Object} tree of Map/List/primitives)
    /// into a form with every {@code Map}'s keys in sorted order, so {@link #SIGNATURE_GSON}
    /// serialises it back deterministically regardless of the original key order.
    /// Package-private: also reused by {@link TerminalDenialRegistry}.
    static Object sortKeysForSignature(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortKeysForSignature(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list.size());
            for (Object item : list) {
                sorted.add(sortKeysForSignature(item));
            }
            return sorted;
        }
        return value;
    }

    /// Escalating-severity nudge text for a loop-signature repeat within
    /// {@link #LOOP_SIGNATURE_SOFT_THRESHOLDS} (firmer wording as the count climbs toward the hard
    /// stop).
    private static String loopSignatureSoftWarning(String toolName, int repeatCount) {
        String severity = switch (repeatCount) {
            case 3 -> "注意";
            case 4 -> "警告";
            default -> "强烈警告";
        };
        return "[系统提示·" + severity + "] 你已经用非常相似的方式调用 '" + toolName + "' 工具 " + repeatCount
                + " 次，结果模式基本相同，看起来没有取得实质进展。请停下来换一种思路："
                + "要么直接结束本轮用已有信息回答，要么明确告诉用户卡在哪里、需要什么，不要再重复同类调用。";
    }

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

    private ToolExecutionResultMessage executeWithLoopGuard(ToolExecutionRequest req,
                                                            LoopGuardState state,
                                                            TraceContext tc, int cycle, int callIndex) {
        try {
            return executeWithLoopGuardUnsafe(req, state, tc, cycle, callIndex);
        } catch (Throwable t) {
            // Defense in depth: this method runs inside CompletableFuture.runAsync() for the
            // parallel READ_ONLY batch (see streamTurn below) — an uncaught exception here would
            // poison CompletableFuture.allOf(...).join() for the WHOLE batch, silently discarding
            // every sibling call's already-computed result even though those siblings ran their real
            // I/O and were never cancelled. It also covers resolvePermission(req) below, which —
            // unlike LangChain4jToolAdapter.execute()'s own disciplined catch(Throwable) — had no
            // guard of its own. Mirrors that same "never let a tool exception escape un-normalized"
            // philosophy so this safety net is HMCL's own code, not incidental upstream behavior.
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AiLog.warn("[AI] executeWithLoopGuard 异常：" + req.name() + " " + t);
            return ToolExecutionResultMessage.from(req,
                    "Error: internal exception while resolving/executing tool '" + req.name() + "': "
                            + (t.getMessage() != null && !t.getMessage().isBlank()
                                    ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    private ToolExecutionResultMessage executeWithLoopGuardUnsafe(ToolExecutionRequest req,
                                                            LoopGuardState state,
                                                            TraceContext tc, int cycle, int callIndex) {
        // Terminal-denial short circuit (borrow-list A1): if the USER already declined this exact
        // operation this turn, refuse it pre-execution — no tool run, and crucially no repeat
        // confirmation dialog. Checked BEFORE the dup counter below: a refusal is terminal on the
        // FIRST occurrence, not after "counting to 3", and the canonicalized signature also
        // catches argument-order perturbations the raw-JSON fingerprint below would miss.
        if (state.terminalDenials.isDenied(req.name(), req.arguments())) {
            ToolExecutionResultMessage blocked = ToolExecutionResultMessage.from(req,
                    TerminalDenialRegistry.SHORT_CIRCUIT_TEXT);
            trace(tc, TraceEvents.guard(tc, cycle, "TERMINAL_DENIAL", req.name()));
            trace(tc, TraceEvents.tool(tc, cycle, callIndex, req.id(), req.name(), req.arguments(),
                    blocked.text(), false, false));
            return blocked;
        }
        // Exact-repeat PRE-execution block, narrowed to write-permission tools (see DUP_CALL_LIMIT's
        // doc) — read-only calls (including check_job/list_jobs polling, whose intended use IS
        // re-issuing the exact same call) skip this and fall through to actually running; if a
        // read-only call is truly going nowhere, the post-execution signature detector below catches it.
        ToolPermission permission = toolAdapter.resolvePermission(req);
        boolean writeLike = permission == ToolPermission.CONTROLLED_WRITE
                || permission == ToolPermission.DANGEROUS_WRITE;
        if (writeLike) {
            String fingerprint = req.name() + "|" + (req.arguments() == null ? "" : req.arguments());
            int count = state.fingerprintCounts.merge(fingerprint, 1, Integer::sum);
            if (count >= DUP_CALL_LIMIT) {
                ToolExecutionResultMessage blocked = ToolExecutionResultMessage.from(req,
                        "BLOCKED: you have already made this exact '" + req.name() + "' call "
                        + (count - 1) + " times this turn. Do NOT repeat the identical call — change the"
                        + " arguments, use a different tool, or answer with the information you already"
                        + " have. If you are waiting on a background job, poll it with check_job instead.");
                trace(tc, TraceEvents.guard(tc, cycle, "DUP_BLOCKED", req.name() + " x" + (count - 1)));
                trace(tc, TraceEvents.tool(tc, cycle, callIndex, req.id(), req.name(), req.arguments(), blocked.text(), false, false));
                return blocked;
            }
        }
        // Capture the COMPLETE tool result for the trace BEFORE any truncation the model sees.
        ToolExecutionResultMessage raw = toolAdapter.execute(req);
        String fullText = raw != null && raw.text() != null ? raw.text() : "";
        boolean success = !fullText.startsWith("Error:") && !fullText.startsWith("BLOCKED:");
        // Record a user refusal as a TERMINAL state for this turn (see the pre-execution short
        // circuit above). Thread-safe registry: a parallel READ_ONLY batch can contain a
        // force-confirmed MCP call whose decline lands here from a pool thread.
        if (TerminalDenialRegistry.isUserDenialResult(fullText)) {
            state.terminalDenials.recordDenial(req.name(), req.arguments());
        }
        ToolExecutionResultMessage forModel = truncateToolResult(req, raw, tc, cycle, callIndex);
        boolean truncatedForModel = forModel != raw; // truncateToolResult returns the same instance if within budget
        trace(tc, TraceEvents.tool(tc, cycle, callIndex, req.id(), req.name(), req.arguments(), fullText, success, truncatedForModel));
        return forModel;
    }

    /// Resolves a tool call's permission defensively: a {@link ToolSpec#getPermission(Map)}
    /// override is arbitrary tool code and — unlike {@link LangChain4jToolAdapter#execute}, which
    /// deliberately catches {@code Throwable} — {@link LangChain4jToolAdapter#resolvePermission} has
    /// no guard of its own. Used at call sites OUTSIDE {@link #executeWithLoopGuard} (which has its
    /// own try/catch above), so a resolution failure fails toward the SAFER branch at each call site
    /// (treated as "not READ_ONLY" for the parallel-batch safety check; treated as "no permission
    /// data" — never a verifiable risky write — for the verify-on-stop bookkeeping) instead of
    /// throwing out of the streaming response handler.
    @Nullable
    private ToolPermission safeResolvePermission(ToolExecutionRequest req) {
        try {
            return toolAdapter.resolvePermission(req);
        } catch (RuntimeException e) {
            AiLog.warn("[AI] resolvePermission 异常，按不安全/未知处理：" + req.name() + " " + e);
            return null;
        }
    }

    /// One turn of the streaming agent loop: stream the model's response (with tools
    /// attached); if it asks for tool calls, execute them, append the results, and recurse;
    /// otherwise finish. Mirrors Pi's loop — keep going until the model stops calling tools.
    ///
    /// `state` tracks the exact-fingerprint dup counts, the loop-signature window, and the
    /// no-progress streak for this turn — see {@link LoopGuardState}.
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
                            LoopGuardState state, StringBuilder turnText,
                            org.jackhuang.hmcl.ai.trace.TraceContext tc) {
        streamTurn(conversation, callback, cycle, state, turnText, 0, tc);
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
                            LoopGuardState state, StringBuilder turnText, int attempt,
                            org.jackhuang.hmcl.ai.trace.TraceContext tc) {
        if (callback.isCancelled()) {
            // User pressed Stop — abort instead of issuing another model call / tool cycle, but
            // still terminate the callback chain so the partial reply gets persisted.
            //
            // Dangling-tool_use audit (borrow-list E3, verified 2026-07): abandoning `conversation`
            // here (and at the two in-batch cancellation exits below) can leave an AiMessage whose
            // tool calls have no paired results — but ONLY inside this turn-local list, which is
            // discarded with the turn. Persistence stores plain-text LlmMessages (assistant
            // segments + UI-only tool records), and ChatAgent.buildMessages() filters tool records
            // off the wire entirely, so no provider ever sees an unpaired tool_use on the next
            // request. No opencode-style "synthesize an output-error result" backfill is needed.
            trace(tc, org.jackhuang.hmcl.ai.langchain4j.TraceEvents.guard(tc, cycle, "CANCELLED", null));
            callback.onComplete(turnText.toString());
            return;
        }
        if (cycle >= maxToolCycles) {
            trace(tc, org.jackhuang.hmcl.ai.langchain4j.TraceEvents.guard(tc, cycle, "CYCLE_CAP",
                    "maxToolCycles=" + maxToolCycles));
            forceFinishTurn(conversation, callback, turnText, tc, cycle, ForceFinishCause.CYCLE_CAP);
            return;
        }

        // In-turn context editing: fold the oldest tool results into placeholders once the
        // accumulated tool loop no longer fits the model's window (they'd otherwise be
        // re-sent in full on every remaining cycle).
        evictOldToolResults(conversation);

        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(conversation);
        // Built once per cycle and reused below — buildToolSpecifications() re-parses every
        // registered tool's JSON input schema via Gson, so calling it 2-3 times per cycle (once
        // for the allowTools check, once for the request, once for the trace) was avoidable work.
        List<ToolSpecification> toolSpecs = toolAdapter != null ? toolAdapter.buildToolSpecifications() : List.of();
        // On the final allowed cycle, drop the tools so the model is forced to produce a
        // text answer summarising progress instead of requesting yet another tool call
        // (which would otherwise be silently dropped as an empty reply).
        boolean allowTools = !toolSpecs.isEmpty() && cycle < maxToolCycles - 1;
        if (allowTools) {
            requestBuilder.toolSpecifications(toolSpecs);
        }

        // Tracks whether any token has streamed yet: a transient error before the first token can be
        // retried transparently (nothing to roll back), but a mid-stream error must never be retried.
        final java.util.concurrent.atomic.AtomicBoolean tokenSeen = new java.util.concurrent.atomic.AtomicBoolean(false);
        final long startNanos = System.nanoTime();
        AiLog.info("[AI] 模型请求 cycle=" + cycle + " attempt=" + attempt + " model=" + modelName
                + " 消息数=" + conversation.size() + " 工具=" + (allowTools ? "on" : "off"));
        // Trace: the COMPLETE request (full conversation incl. system prompt + tool schemas).
        trace(tc, TraceEvents.request(tc, cycle, attempt, conversation,
                allowTools ? toolSpecs : null, allowTools));
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
                AiLog.info("[AI] 模型响应 cycle=" + cycle + " model=" + modelName + " 耗时=" + elapsedMs + "ms tokens(in/out/total)="
                        + (usage != null ? usage.inputTokenCount() + "/" + usage.outputTokenCount()
                        + "/" + usage.totalTokenCount() : "n/a")
                        + " 工具调用=" + (aiMessage != null && aiMessage.hasToolExecutionRequests()
                        ? aiMessage.toolExecutionRequests().size() : 0));
                // Trace: the raw response — text, tool calls (raw args), usage, and the finishReason
                // (checked below for LENGTH, which blocks this message's tool calls from running).
                String finishReason = response.finishReason() != null ? response.finishReason().name() : null;
                trace(tc, TraceEvents.response(tc, cycle, aiMessage, finishReason,
                        usage != null ? usage.inputTokenCount() : null,
                        usage != null ? usage.outputTokenCount() : null,
                        usage != null ? usage.totalTokenCount() : null,
                        elapsedMs));
                if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                    // Tool-call turn: record it, run each tool, feed results back, loop.
                    // Any prose the model emitted before its tool calls becomes a finished segment.
                    String preToolSegment = stripLeakedToolMarkup(
                            aiMessage.text() != null ? aiMessage.text() : "");
                    appendSegment(turnText, preToolSegment);
                    if (!preToolSegment.isBlank()) {
                        callback.onSegmentComplete(preToolSegment.strip());
                    }
                    conversation.add(aiMessage);
                    // A response cut off mid-generation (finishReason=LENGTH) may have truncated the
                    // JSON arguments of any of these tool calls — running them risks silently acting on
                    // corrupted parameters (e.g. a half-written path or id). Block ALL tool calls in
                    // this message rather than gamble on which ones happened to land intact.
                    boolean lengthTruncated = "LENGTH".equals(finishReason);
                    // allowTools=false only controls whether we OFFERED tool schemas this cycle — it
                    // does not stop a provider that ignores that and returns tool_calls anyway (this is
                    // the final, tools-withheld cycle right before the hard CYCLE_CAP). Block those too
                    // instead of silently letting "one more" tool call slip past the intended cap.
                    boolean toolsWereWithheld = !allowTools;
                    if (lengthTruncated) {
                        trace(tc, TraceEvents.guard(tc, cycle, "LENGTH_TRUNCATED",
                                aiMessage.toolExecutionRequests().size() + " tool call(s) blocked"));
                    } else if (toolsWereWithheld) {
                        trace(tc, TraceEvents.guard(tc, cycle, "TOOLS_WITHHELD_IGNORED",
                                aiMessage.toolExecutionRequests().size() + " tool call(s) blocked"));
                    }
                    boolean synthetic = lengthTruncated || toolsWereWithheld;
                    List<ToolExecutionRequest> reqs = aiMessage.toolExecutionRequests();
                    // Whether this WHOLE batch is safe to run concurrently: only when every call is
                    // READ_ONLY (a real trace found several independent `search` calls in one
                    // response running strictly back-to-back — "不知道这几个Search是不是并行的，
                    // 如果不是那问题就严重了"). Deliberately excludes any batch containing a
                    // write-permission call: executeWithLoopGuard's pre-execution dup-block records
                    // a fingerprint as a side effect of checking it, so two near-identical WRITE
                    // calls dispatched at once could both pass the "not yet seen" check before
                    // either records its fingerprint — a race the sequential loop never had. A
                    // batch of all-READ_ONLY calls never touches that fingerprint map at all (see
                    // executeWithLoopGuard), so concurrent dispatch has no such hazard.
                    //
                    // Also excludes any batch containing a PARALLEL_UNSAFE_TOOL_NAME, even though
                    // such tools are declared READ_ONLY (they don't write host state, so the
                    // permission label is correct) — `ask` is UI-stateful in a way the READ_ONLY
                    // label doesn't capture: AIMainPage keeps a single `activeAsk` future / one
                    // `askPanel` widget, and showAskPanel() calls cancelActiveAsk() first. Two
                    // concurrent `ask` calls in one batch would race: the second call's
                    // showAskPanel() cancels the first's still-pending future (completed
                    // exceptionally as "the user cancelled"), silently dropping the first question
                    // even though the user never cancelled anything. Falling back to the sequential
                    // path handles multiple `ask` calls correctly — one panel shown and answered at
                    // a time.
                    boolean parallelSafe = !synthetic && reqs.size() > 1
                            && reqs.stream().allMatch(r -> safeResolvePermission(r) == ToolPermission.READ_ONLY)
                            && reqs.stream().noneMatch(r -> PARALLEL_UNSAFE_TOOL_NAMES.contains(r.name()));
                    ToolExecutionResultMessage[] results = new ToolExecutionResultMessage[reqs.size()];
                    if (parallelSafe) {
                        if (callback.isCancelled()) {
                            callback.onComplete(turnText.toString());
                            return;
                        }
                        // All calls are "starting at once", so announce them all up front, in
                        // request order — onToolResult below is still delivered in that SAME order
                        // once every future completes, regardless of actual completion order, so
                        // the UI's per-tool-name FIFO card correlation (pendingToolCards) sees the
                        // exact same activity→result pairing sequence as the sequential path.
                        for (ToolExecutionRequest req : reqs) {
                            callback.onToolActivity(req.name(), req.arguments());
                        }
                        List<CompletableFuture<Void>> futures = new ArrayList<>(reqs.size());
                        for (int i = 0; i < reqs.size(); i++) {
                            int idx = i;
                            ToolExecutionRequest req = reqs.get(i);
                            futures.add(CompletableFuture.runAsync(
                                    () -> results[idx] = executeWithLoopGuard(req, state, tc, cycle, idx),
                                    PARALLEL_TOOL_EXECUTOR));
                        }
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    } else {
                        // Original strictly-sequential path — unchanged behaviour (including the
                        // per-call cancellation check) for any batch containing a write-permission
                        // call, or just a single call.
                        for (int i = 0; i < reqs.size(); i++) {
                            if (callback.isCancelled()) {
                                callback.onComplete(turnText.toString());
                                return;
                            }
                            ToolExecutionRequest req = reqs.get(i);
                            callback.onToolActivity(req.name(), req.arguments());
                            if (lengthTruncated) {
                                results[i] = ToolExecutionResultMessage.from(req,
                                        "BLOCKED: your response was truncated (finishReason=LENGTH) before this "
                                        + "could be trusted — its arguments may be incomplete or corrupted, so it "
                                        + "was NOT executed. Re-emit your full response, including this tool call, "
                                        + "completely and without truncation.");
                                trace(tc, TraceEvents.tool(tc, cycle, i, req.id(), req.name(), req.arguments(), results[i].text(),
                                        false, false));
                            } else if (toolsWereWithheld) {
                                results[i] = ToolExecutionResultMessage.from(req,
                                        "BLOCKED: tools were disabled for this cycle (you are one cycle away from "
                                        + "the tool-call limit) — this call was NOT executed. Answer in text now: "
                                        + "summarize what you completed, what's left, and any suggestion.");
                                trace(tc, TraceEvents.tool(tc, cycle, i, req.id(), req.name(), req.arguments(), results[i].text(),
                                        false, false));
                            } else {
                                results[i] = executeWithLoopGuard(req, state, tc, cycle, i);
                            }
                        }
                    }

                    // Bookkeeping + result callbacks + loop-signature detection ALWAYS run
                    // sequentially, in original request order, regardless of whether execution
                    // above ran in parallel — this keeps LoopGuardState mutation single-threaded
                    // and deterministic; only the actual (network/disk) tool.execute() work above
                    // may have happened concurrently.
                    boolean hardLoopStop = false;
                    // Which tool tripped the hard stop and how many identical signatures it racked
                    // up — carried to the user-facing "继续还是停?" confirm below (H4).
                    String hardLoopTool = null;
                    int hardLoopRepeats = 0;
                    // Whether a successful todo_write call landed THIS cycle — drives the staleness
                    // half of the todo-discipline guard below (see LoopGuardState#cyclesSinceTodoUpdate).
                    boolean todoTouchedThisCycle = false;
                    for (int i = 0; i < reqs.size(); i++) {
                        ToolExecutionRequest req = reqs.get(i);
                        ToolExecutionResultMessage result = results[i];
                        if (result != null) {
                            conversation.add(result);
                            String resultText = result.text() != null ? result.text() : "";
                            boolean success = !resultText.startsWith("Error:") && !resultText.startsWith("BLOCKED:");
                            // No-progress tracking: a "successful but zero new information" result
                            // (check_job still-running, sleep waited) now counts as non-progress too —
                            // this was the blind spot that let sleep+check_job polling loops run
                            // forever without ever tripping NO_PROGRESS_LIMIT.
                            boolean isWaitNoOp = !synthetic && isWaitNoOpResult(req.name(), resultText);
                            if (success && !isWaitNoOp) {
                                state.noProgressStreak = 0;
                            } else {
                                state.noProgressStreak++;
                            }
                            // Verify-on-stop bookkeeping: a successful risky write adds its
                            // (tool, target) signature to the ledger; a successful READ_ONLY call
                            // clears only the entries it plausibly verifies (see
                            // clearVerifiedEntries) — not the whole ledger.
                            if (success && !synthetic) {
                                ToolPermission resultPermission = safeResolvePermission(req);
                                if (isVerifiableRiskyWrite(req.name(), resultPermission)) {
                                    state.outstandingRiskyWrites.add(riskyWriteSignature(req.name(), req.arguments()));
                                } else if (resultPermission == ToolPermission.READ_ONLY) {
                                    clearVerifiedEntries(state, req.name(), req.arguments());
                                }
                                // Todo-discipline bookkeeping (real-trace failure mode — see
                                // LoopGuardState#outstandingTodoContents): a successful todo_write call
                                // always touches the ledger and resets the staleness counter, AND
                                // separately flags any item that vanished from the list entirely instead
                                // of being carried over or marked done — the "abandon a 9-item checklist,
                                // replace it wholesale" bug this guard exists to catch.
                                if ("todo_write".equals(req.name())) {
                                    todoTouchedThisCycle = true;
                                    List<String> dropped = updateTodoLedger(state, req.arguments());
                                    if (!dropped.isEmpty() && state.todoNudgeCount < TODO_NUDGE_LIMIT) {
                                        state.todoNudgeCount++;
                                        trace(tc, TraceEvents.guard(tc, cycle, "TODO_SILENT_DISCARD",
                                                "nudge#" + state.todoNudgeCount + " dropped=" + dropped.size()));
                                        conversation.add(GuardMessageFormatter.guardMessage("todo_silent_discard",
                                                "[系统提示] 刚才这次 todo_write 调用让旧清单里 " + dropped.size()
                                                + " 项内容凭空消失了（既没有标记为 done，也没有保留在新清单里）："
                                                + String.join("；", dropped) + "。如果这些其实已经做完了，请更新清单把它们标为"
                                                + " done；如果计划变了但还没做完，也要把它们保留在清单里——不要悄悄丢弃"
                                                + "重开一份新清单。"));
                                    }
                                }
                            }
                            String summary = resultText.length() > 300
                                    ? resultText.substring(0, 300) + "…"
                                    : resultText;
                            callback.onToolResult(req.name(), success, summary);

                            // General loop-signature detection: only for calls that actually ran
                            // (skip synthetic LENGTH/withheld blocks, already traced above).
                            if (!synthetic) {
                                String signature = buildLoopSignature(req.name(), req.arguments(), resultText);
                                state.signatureWindow.addLast(signature);
                                while (state.signatureWindow.size() > LOOP_SIGNATURE_WINDOW) {
                                    state.signatureWindow.removeFirst();
                                }
                                long repeatCount = state.signatureWindow.stream().filter(signature::equals).count();
                                if (repeatCount >= LOOP_SIGNATURE_HARD_STOP) {
                                    AiLog.warn("[AI] 工具 " + req.name() + " 疑似原地打转（" + repeatCount
                                            + " 次同签名），本轮强制收尾");
                                    trace(tc, TraceEvents.guard(tc, cycle, "LOOP_SIGNATURE_HARD_STOP",
                                            req.name() + " x" + repeatCount));
                                    hardLoopStop = true;
                                    hardLoopTool = req.name();
                                    hardLoopRepeats = (int) repeatCount;
                                } else if (LOOP_SIGNATURE_SOFT_THRESHOLDS.contains((int) repeatCount)) {
                                    trace(tc, TraceEvents.guard(tc, cycle, "LOOP_SIGNATURE_SOFT",
                                            req.name() + " x" + repeatCount));
                                    conversation.add(GuardMessageFormatter.guardMessage("loop_warning",
                                            loopSignatureSoftWarning(req.name(), (int) repeatCount)));
                                }
                            }
                        }
                    }
                    if (hardLoopStop) {
                        // Doom-loop hard stop (borrow-list A2): the turn is now REALLY force-ended
                        // — tools withdrawn, one final text-only request — instead of the model
                        // being merely lectured and left holding the execution reins.
                        // H4（第二波）：强制收尾前先把决定权交给用户 —— AIMainPage 通过
                        // setLoopHardStopConfirmer 注入阻塞式确认弹窗（与 confirmDangerousOperation
                        // 同一套模态管线）：“模型似乎卡在重复调用，是否继续？”。用户选“是”则清空
                        // 签名窗口并放行下一轮；选“否”/超时/无 UI（confirmer 为 null，如纯单测环境）
                        // 均维持原有强制收尾，行为完全向后兼容。
                        LoopHardStopConfirmer confirmer = loopHardStopConfirmer;
                        boolean userContinues = false;
                        if (confirmer != null && !callback.isCancelled()) {
                            try {
                                userContinues = confirmer.confirmContinue(hardLoopTool, hardLoopRepeats);
                            } catch (Throwable t) {
                                AiLog.warn("[AI] doom-loop 继续确认失败，按“停止”处理：" + t.getMessage());
                            }
                        }
                        if (userContinues && !callback.isCancelled()) {
                            state.signatureWindow.clear();
                            trace(tc, TraceEvents.guard(tc, cycle, "LOOP_HARD_STOP_OVERRIDE",
                                    hardLoopTool + " x" + hardLoopRepeats + " user-approved continue"));
                            conversation.add(GuardMessageFormatter.guardMessage("loop_override",
                                    "[系统提示] 检测到你反复以几乎相同的方式调用工具 " + hardLoopTool
                                    + "（已 " + hardLoopRepeats + " 次同签名、结果基本相同），用户确认允许你继续。"
                                    + "请换一种思路、参数或工具再试——不要原样重复同一调用。"));
                            streamTurn(conversation, callback, cycle + 1, state, turnText, tc);
                            return;
                        }
                        forceFinishTurn(conversation, callback, turnText, tc, cycle, ForceFinishCause.DOOM_LOOP);
                        return;
                    }
                    // No-progress guard: after a run of non-progress results, nudge the model ONCE to
                    // stop retrying and wrap up, then reset the streak so it isn't re-nagged every
                    // cycle. The overall cycle cap remains the hard backstop.
                    if (state.noProgressStreak >= NO_PROGRESS_LIMIT) {
                        AiLog.warn("[AI] 连续 " + state.noProgressStreak + " 次工具调用无进展，注入收敛提示");
                        trace(tc, TraceEvents.guard(tc, cycle, "NO_PROGRESS",
                                "consecutiveNonProgress=" + state.noProgressStreak));
                        conversation.add(GuardMessageFormatter.guardMessage("no_progress",
                                "[系统提示] 已连续多次工具调用失败、没有取得进展。请停止继续尝试同类操作："
                                + "用你已经获得的信息直接回答，或明确说明卡在哪里、需要用户提供什么，不要再盲目重试。"));
                        state.noProgressStreak = 0;
                    }
                    // Todo-discipline staleness guard (the other half of the real-trace failure mode —
                    // see LoopGuardState#cyclesSinceTodoUpdate): only ticks while there is at least one
                    // outstanding item AND this cycle's tool calls didn't touch the list at all.
                    // Deliberately generous (TODO_STALE_CYCLE_LIMIT) — a single healthy phase can
                    // legitimately span many tool-call cycles.
                    if (!todoTouchedThisCycle && !state.outstandingTodoContents.isEmpty()) {
                        state.cyclesSinceTodoUpdate++;
                        if (state.cyclesSinceTodoUpdate >= TODO_STALE_CYCLE_LIMIT
                                && state.todoNudgeCount < TODO_NUDGE_LIMIT) {
                            state.todoNudgeCount++;
                            state.cyclesSinceTodoUpdate = 0;
                            trace(tc, TraceEvents.guard(tc, cycle, "TODO_STALE",
                                    "nudge#" + state.todoNudgeCount
                                    + " outstanding=" + state.outstandingTodoContents.size()));
                            conversation.add(GuardMessageFormatter.guardMessage("todo_stale",
                                    "[系统提示] TODO 清单已经有一段时间没有更新了，但还有 "
                                    + state.outstandingTodoContents.size() + " 项没有勾选完成。如果其中有已经做完的，"
                                    + "回去调用 todo_write 把它们标为 done；如果计划变了，也要用 todo_write 更新现有"
                                    + "清单（保留已完成项），而不是放着不管或悄悄换一份新清单。"));
                        }
                    }
                    streamTurn(conversation, callback, cycle + 1, state, turnText, tc);
                    return;
                }

                String raw = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "";
                String content = stripLeakedToolMarkup(raw);
                if (content.isEmpty() && !raw.isEmpty() && turnText.length() == 0) {
                    // The whole reply was leaked tool-call markup the provider never parsed into real
                    // calls — tell the user instead of dumping raw <｜…｜> tokens into the chat.
                    content = "（当前模型本轮以文本形式输出了工具调用，但接口未将其解析为真正的调用，因此无法执行。"
                            + "建议在右上角换一个能稳定进行函数调用的模型，或换种说法重试。）";
                } else if (content.isEmpty() && raw.isEmpty() && turnText.length() == 0) {
                    // No tool calls AND genuinely empty text — without this, ChatAgent.onComplete
                    // (which only persists non-blank text) would silently produce ZERO assistant
                    // message and ZERO visible feedback for this turn.
                    content = "（模型本轮没有返回任何内容，也没有调用工具。请重试，或换一个模型。）";
                }
                appendSegment(turnText, content);
                if (!content.isBlank()) {
                    callback.onSegmentComplete(content.strip());
                }
                // Verify-on-stop (borrow-list 2.13 / failure mode E): about to finish this turn after
                // successfully writing something risky (instance config, mod state, NBT, ...) with no
                // read-only call afterward confirming it actually landed. Force one more cycle instead
                // of letting an unverified "should be fixed now" reach the user. Capped and
                // self-clearing so a model that keeps re-triggering this can still finish eventually.
                if (!state.outstandingRiskyWrites.isEmpty() && state.verifyNudgeCount < VERIFY_ON_STOP_LIMIT) {
                    state.verifyNudgeCount++;
                    state.outstandingRiskyWrites.clear();
                    trace(tc, TraceEvents.guard(tc, cycle, "VERIFY_ON_STOP", "nudge#" + state.verifyNudgeCount));
                    conversation.add(GuardMessageFormatter.guardMessage("verify_on_stop",
                            "[系统提示] 你刚才修改了配置/状态，但还没有用只读工具验证改动确实生效。"
                            + "请先验证（比如重新读取刚改的内容、或用对应的list/details类工具确认），再收工。"));
                    streamTurn(conversation, callback, cycle + 1, state, turnText, tc);
                    return;
                }
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
                            + " status=" + wrapped.getStatusCode() + "：" + wrapped.getMessage());
                    trace(tc, TraceEvents.guard(tc, cycle, "RETRY",
                            "attempt=" + (attempt + 1) + " " + wrapped.getMessage()));
                    try {
                        Thread.sleep(500L * (1L << attempt)); // 0.5s, then 1s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        callback.onError(wrapped);
                        return;
                    }
                    streamTurn(conversation, callback, cycle, state, turnText, attempt + 1, tc);
                    return;
                }
                AiLog.warn("[AI] 模型请求失败 status=" + wrapped.getStatusCode() + "：" + wrapped.getMessage());
                trace(tc, TraceEvents.guard(tc, cycle, "ERROR", wrapped.getMessage()));
                callback.onError(wrapped);
            }
        });
    }

    /// WHY a turn is being force-finished — the two causes deserve DIFFERENT wording (borrow-list
    /// A8's follow-up note): "you hit the 25-cycle budget" and "you were caught repeating the same
    /// call in a loop" call for different closing summaries and different user advice.
    enum ForceFinishCause { CYCLE_CAP, DOOM_LOOP }

    /// H4（第二波）：doom-loop 硬停前的用户确认钩子。UI 侧（AIMainPage）注入一个阻塞式实现——
    /// 在 FX 线程弹 “模型似乎卡在重复调用，是否继续？” 的确认框并阻塞本（流式回调）线程等待答案，
    /// 与 confirmDangerousOperation 完全同一套模态管线（超时/Stop/会话切换都会自动以 false 收场）。
    /// 返回 true = 用户放行：签名窗口清空、模型获得下一轮；false = 维持强制收尾。
    @FunctionalInterface
    public interface LoopHardStopConfirmer {
        boolean confirmContinue(String toolName, int repeatCount);
    }

    /// The UI-injected doom-loop confirmer, or {@code null} when no UI is attached (unit tests,
    /// headless) — null keeps the pre-H4 behaviour: hard stop with no question asked.
    private static volatile @Nullable LoopHardStopConfirmer loopHardStopConfirmer;

    /// Installs (or clears, with {@code null}) the doom-loop hard-stop confirmer. Static because
    /// the confirm dialog surface is process-global (one AIMainPage), mirroring how ToolProgress
    /// decouples the UI from this module; the last registration wins.
    public static void setLoopHardStopConfirmer(@Nullable LoopHardStopConfirmer confirmer) {
        loopHardStopConfirmer = confirmer;
    }

    /// Soft landing at the hard {@link #maxToolCycles} cap (borrow-list 2.4) or on a doom-loop
    /// hard stop (borrow-list A2). The old behaviour ended the turn with a canned Chinese
    /// paragraph and zero model involvement. This gives the model ONE final, tool-free request to
    /// summarise what it finished / didn't / suggests in its own words — a real closing reply
    /// beats a robotic cutoff message. Falls back to the canned message if this final request
    /// itself errors or comes back empty. The closing instruction is attributed precisely to its
    /// {@code cause} and rides the {@link GuardMessageFormatter} identity channel.
    private void forceFinishTurn(List<ChatMessage> conversation, LlmStreamCallback callback,
                                 StringBuilder turnText, org.jackhuang.hmcl.ai.trace.TraceContext tc, int cycle,
                                 ForceFinishCause cause) {
        trace(tc, TraceEvents.guard(tc, cycle, "FORCE_FINISH",
                cause == ForceFinishCause.DOOM_LOOP
                        ? "doom-loop hard stop" : "maxToolCycles=" + maxToolCycles));
        String cannedFallback = cause == ForceFinishCause.DOOM_LOOP
                ? "（检测到模型反复以几乎相同的方式调用同一工具且没有进展，本轮已强制停止。"
                        + "建议：换种说法重试、补充缺少的信息，或在右上角换一个更强的模型。）"
                : "（已连续调用工具 " + maxToolCycles
                        + " 轮仍未完成，已停止以避免无限空转。建议：换种说法或补充信息，必要时在右上角换一个更强的模型；也可在「高级」里调整工具调用轮数上限。）";
        conversation.add(GuardMessageFormatter.guardMessage("force_finish",
                cause == ForceFinishCause.DOOM_LOOP
                        ? "[系统提示] 检测到你反复以几乎相同的方式调用同一工具、得到基本相同的结果（疑似原地打转），"
                                + "本轮已强制进入收尾，工具已禁用。请直接用文字总结：已完成什么、卡在哪里、"
                                + "你需要用户提供什么——不要再尝试调用任何工具。"
                        : "[系统提示] 已连续调用工具 " + maxToolCycles + " 轮，工具已禁用。"
                                + "请直接用文字总结：已完成什么、未完成什么、你的建议——不要再尝试调用任何工具。"));
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(conversation);
        // Deliberately no .toolSpecifications(...) — this really is the model's last, text-only word.
        streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (token != null && !token.isEmpty()) {
                    callback.onToken(token);
                }
            }

            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                // No reasoning UI for this synthetic closing request — keep it simple.
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage aiMessage = response.aiMessage();
                String content = stripLeakedToolMarkup(
                        aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "");
                String finalSegment = content.isBlank() ? cannedFallback : content;
                appendSegment(turnText, finalSegment);
                callback.onSegmentComplete(finalSegment.strip());
                callback.onComplete(turnText.toString());
            }

            @Override
            public void onError(Throwable error) {
                AiLog.warn("[AI] 轮次上限收尾请求失败，回退为固定文案：" + error.getMessage());
                appendSegment(turnText, cannedFallback);
                callback.onSegmentComplete(cannedFallback.strip());
                callback.onComplete(turnText.toString());
            }
        });
    }

    private static final int MAX_STREAM_RETRIES = 2; // up to 3 attempts total for a pre-token transient error

    /// Whether a failure is worth a transparent retry — delegated to the shared
    /// {@link org.jackhuang.hmcl.ai.net.HttpRetryClassifier} (borrow-list A5: this table used to
    /// live only here while the search/HTTP tools had none; it is now the one source of truth).
    private static boolean isRetryable(LlmException e) {
        return org.jackhuang.hmcl.ai.net.HttpRetryClassifier.isRetryableStatus(e.getStatusCode());
    }

    /// Sends a non-streaming request via the LangChain4j chat model and
    /// converts the response. Tool calls in the response are executed via
    /// the tool adapter and the assistant's text content is returned.
    ///
    /// @param messages the HMCL conversation history
    /// @return the assistant's text response
    private String doChat(List<LlmMessage> messages) {
        List<ChatMessage> conversation = new ArrayList<>(applyContextLimit(convertMessages(messages)));
        LoopGuardState state = new LoopGuardState();

        for (int cycle = 0; cycle < maxToolCycles; cycle++) {
            evictOldToolResults(conversation);
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(conversation);

            // Built once per cycle and reused below (see the streamTurn() equivalent for why).
            List<ToolSpecification> toolSpecs = toolAdapter != null ? toolAdapter.buildToolSpecifications() : List.of();
            // Final allowed cycle: drop tools so the model must answer in text.
            boolean allowTools = !toolSpecs.isEmpty() && cycle < maxToolCycles - 1;
            if (allowTools) {
                requestBuilder.toolSpecifications(toolSpecs);
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
            //
            // allowTools=false only controls whether we OFFERED tool schemas this cycle (or
            // whether a toolAdapter exists at all, e.g. the compact/title/testConnection callers
            // of doChat() that pass toolAdapter=null) — it does not stop a provider that returns
            // tool_calls anyway. Substitute a synthetic BLOCKED result instead of calling
            // executeWithLoopGuard(), which would otherwise NPE on toolAdapter.resolvePermission(req)
            // when toolAdapter is null. Mirrors streamTurn()'s toolsWereWithheld handling.
            boolean toolsWereWithheld = !allowTools;
            int callIndex = 0;
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                if (toolsWereWithheld) {
                    conversation.add(ToolExecutionResultMessage.from(req,
                            "BLOCKED: tools are not available in this context — this call was NOT "
                            + "executed. Answer in text now."));
                    callIndex++;
                    continue;
                }
                // Non-streaming path (compact/title/testConnection) — not an agent turn, not traced.
                conversation.add(executeWithLoopGuard(req, state, TraceContext.NONE, cycle, callIndex++));
            }
        }

        return ""; // exceeded max cycles
    }

    /// Converts a list of HMCL [`LlmMessage`] objects to LangChain4j
    /// [`ChatMessage`] objects.
    ///
    /// @param messages the HMCL messages to convert
    /// @return a list of LangChain4j chat messages
    /// Converts, ALSO merging any run of consecutive `assistant`-role messages into one
    /// {@link AiMessage} (blank-line joined, same separator {@code appendSegment} uses). A turn now
    /// persists one {@link LlmMessage} per completed cycle instead of one for the whole turn (so a
    /// reloaded session's bubbles match what streamed live) — but providers are handed back this
    /// turn's history on the NEXT request, and at least Anthropic's API rejects consecutive
    /// same-role messages with nothing between them. Merging here keeps the WIRE format exactly one
    /// logical assistant turn, independent of how many bubbles it was split into for display.
    static List<ChatMessage> convertMessages(List<LlmMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        StringBuilder pendingAssistant = null;
        for (LlmMessage msg : messages) {
            if ("assistant".equals(msg.getRole())) {
                String content = msg.getContent() != null ? msg.getContent() : "";
                if (pendingAssistant == null) {
                    pendingAssistant = new StringBuilder();
                } else if (pendingAssistant.length() > 0 && !content.isEmpty()) {
                    pendingAssistant.append("\n\n");
                }
                pendingAssistant.append(content);
                continue;
            }
            if (pendingAssistant != null) {
                result.add(AiMessage.from(pendingAssistant.toString()));
                pendingAssistant = null;
            }
            result.add(convertMessage(msg));
        }
        if (pendingAssistant != null) {
            result.add(AiMessage.from(pendingAssistant.toString()));
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

    /// Best-effort HTTP status from langchain4j's exception hierarchy — extracted to the shared
    /// {@link org.jackhuang.hmcl.ai.net.HttpRetryClassifier#extractStatus} (borrow-list A5) so
    /// the search/HTTP tool layer can reuse the same walk; this thin alias keeps
    /// {@link #wrapError}'s call site unchanged.
    private static int extractStatus(Throwable error) {
        return org.jackhuang.hmcl.ai.net.HttpRetryClassifier.extractStatus(error);
    }
}
