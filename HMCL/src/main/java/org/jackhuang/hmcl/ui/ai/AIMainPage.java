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
package org.jackhuang.hmcl.ui.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPopup;
import com.jfoenix.controls.JFXProgressBar;
import com.jfoenix.controls.JFXRadioButton;
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.controls.JFXTextField;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.AiModelDiscoveryService;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.agent.AiPromptBuilder;
import org.jackhuang.hmcl.ai.agent.AiTitleNamingStrategy;
import org.jackhuang.hmcl.ai.agent.ChatAgent;
import org.jackhuang.hmcl.ai.agent.ChatAgentFactory;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.cost.SpendTracker;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jackhuang.hmcl.ai.tools.EditTool;
import org.jackhuang.hmcl.ai.tools.FileReadTool;
import org.jackhuang.hmcl.ai.tools.GameContextTool;
import org.jackhuang.hmcl.ai.tools.GlobTool;
import org.jackhuang.hmcl.ai.tools.GrepTool;
import org.jackhuang.hmcl.ai.tools.ShellTool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.WebFetchTool;
import org.jackhuang.hmcl.ai.tools.WriteFileTool;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.MenuSeparator;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The main AI chat page with multi-session support, a structured chat workspace,
/// and an in-page settings panel with provider profile management.
///
/// ## Layout
///
/// - **Left sidebar**: "New Chat" button, scrollable session list with delete-on-hover,
///   and a Settings entry at the bottom (sibling navigation target alongside sessions).
/// - **Main header**: current session title and provider/model metadata.
/// - **Center**: scrollable message list with suggestion chips as empty-state,
///   plus a typing indicator status bar below.
/// - **Bottom composer**: text input field and Send button.
///
/// ## Views
///
/// The center area uses a {@link StackPane} to switch between the chat view and
/// the in-page settings panel. Selecting Settings in the sidebar shows the settings
/// subview; selecting any session returns to the chat view. Save does not
/// auto-return — the user stays in settings until they choose a session.
///
/// ## Provider profiles
///
/// Settings use a provider profile manager: list existing profiles, add/edit/delete.
/// Creating a profile starts by choosing a protocol family (OpenAI Completions,
/// OpenAI Reasoning, Anthropic). Each profile stores endpoint, API key,
/// and an optional default model.
///
/// ## Global AI settings
///
/// A dedicated section below the provider-profile form exposes global AI behaviour
/// flags backed by {@link AiSettings}: auto session naming, auto log/crash analysis,
/// tool-call display, and the (single, Auto) approval mode. The approval mode is
/// also reflected as a badge in the chat header subtitle row.
///
/// ## Model discovery
///
/// When endpoint and API key are available, the user can trigger model discovery
/// through {@link AiModelDiscoveryService}. Discovered models populate a dropdown
/// alongside a manual-entry fallback. When a model id matches a preset in the
/// internal capability library, preset defaults for context window / max output
/// are auto-applied.
///
/// ## Suggestions
///
/// When a session has no messages, suggestion chips appear as starter prompts.
///
/// ## Session auto-titling
///
/// The first user message in a new session updates the session title automatically.
///
/// @see AiSessionStore
/// @see ChatAgentFactory
/// @see AiProviderProfile
@NotNullByDefault
public final class AIMainPage extends DecoratorAnimatedPage implements DecoratorPage {

    /// Max width of a chat bubble / card (user message, AI message, tool card, reasoning card,
    /// todo card, …). Messages render one per row (never side-by-side), so this doesn't need to
    /// reserve mirrored empty space the way a two-column layout would — raised from the original
    /// 480px, which read as a narrow column with a large empty gutter on wide windows.
    /// 未来 A4(气泡宽度绑定视口自适应)的单一改造点:全部气泡/卡片宽度(含
    /// MarkdownMessageView 的工厂参数)都从这里派生,改这一处即可整体换轨。
    static final double AI_BUBBLE_MAX_WIDTH = 720;

    // ---- State ----

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("ai.title")));
    private final AiSettings aiSettings;
    private final AiSessionStore sessionStore;
    private final AiModelDiscoveryService discoveryService = new AiModelDiscoveryService();

    private final Map<String, ChatAgent> agentCache = new HashMap<>();
    private final ToolRegistry toolRegistry = new ToolRegistry();

    /// Session ids whose agent eviction was requested MID-STREAM (settings changed while that
    /// agent's turn was still running) and therefore deferred to {@link #exitStreamingState()}.
    /// FX-thread confined (written by {@link #clearAgentCache()}, drained by
    /// {@code exitStreamingState()}, both only ever run on the FX thread), so no concurrent
    /// container is needed.
    private final java.util.Set<String> deferredEvictions = new java.util.HashSet<>();

    /// Evicts and shuts down a single cached agent (its dedicated single-thread executor never
    /// times out on its own, so a discarded-but-not-shutdown agent leaks a blocked worker thread).
    private void evictAgent(String sessionId) {
        ChatAgent agent = agentCache.remove(sessionId);
        if (agent != null) {
            agent.shutdown();
        }
    }

    /// Shuts down every cached agent, then clears the cache — same rationale as {@link #evictAgent}.
    ///
    /// Exception: the agent CURRENTLY STREAMING a response is never shut down here —
    /// {@code shutdownNow()} would interrupt its executor thread mid-turn and surface as a bogus
    /// "connection failed" error bubble the instant the user touched any setting. That agent is
    /// recorded in {@link #deferredEvictions} instead and evicted by {@link #exitStreamingState()}:
    /// the in-flight turn finishes under the OLD settings, and the NEXT turn gets a fresh agent
    /// built from the new ones — the only semantics that never interrupts the user.
    private void clearAgentCache() {
        String streaming = (currentResponse != null) ? streamSessionId : null;
        java.util.Iterator<Map.Entry<String, ChatAgent>> it = agentCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ChatAgent> e = it.next();
            if (e.getKey().equals(streaming)) {
                deferredEvictions.add(e.getKey());
                continue;
            }
            e.getValue().shutdown();
            it.remove();
        }
    }

    /// The live registry of all registered agent tools — the single source of truth the settings
    /// page reads to show the real tool/permission list (instead of a stale hard-coded catalog).
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private final GameContextTool gameContextTool = new GameContextTool();
    /// Skill registry shared with the chat agent's prompt — seeded with built-in skills
    /// (config-hmcl-ae, …) and refreshed from .hmcl/ai-skills so the agent's system prompt
    /// lists available skills and their files.
    private final org.jackhuang.hmcl.ai.skills.SkillRegistry skillRegistry = new org.jackhuang.hmcl.ai.skills.SkillRegistry();
    // Filesystem tools whose allowed roots are widened to the current game directory.
    private FileReadTool fileReadTool;
    private WriteFileTool fileWriteTool;
    private EditTool editTool;
    private GrepTool grepTool;
    private GlobTool globTool;
    /// Domain tool merging local instance/mod/world/content-management actions; needs
    /// `refreshRunDir()` on every instance switch (see {@link #refreshGameContext()}).
    private org.jackhuang.hmcl.ui.ai.tools.InstanceTool instanceTool;

    // ---- Sidebar elements ----

    /// Node-properties key used to associate a sidebar item with its session id,
    /// so the active highlight can be updated without rebuilding the list.
    private static final String SESSION_ID_KEY = "ai.sessionId";

    private final VBox sessionListBox = new VBox(2);
    @Nullable
    private AdvancedListBox sidebarScrollPane;
    @Nullable
    private VBox sidebarRoot;

    // ---- View state ----

    // ---- Main content elements ----

    private final StackPane chatSettingsStack = new StackPane();
    private final VBox chatView = new VBox();

    // ---- Header ----

    private final Label headerTitle = new Label();
    private final Label headerSubtitle = new Label();
    private final Label approvalBadge = new Label();
    /// Header badge shown while plan mode (read-only-until-approved) is active.
    private final Label planBadge = new Label("计划模式");

    /// When true, write/install/delete/launch/shell tools are gated off for the next
    /// response so the agent only investigates and proposes a plan. Toggled by /plan.
    private boolean planMode = false;
    /// Tool names this page disabled for the current plan-gated response, restored when it ends.
    private final List<String> planDisabledTools = new ArrayList<>();

    /// Pinned container above the scrollable messages that holds the live TODO card.
    private final VBox todoCardContainer = new VBox();
    /// The single reusable TODO card; (re)built on each todo_write call.
    @Nullable private VBox todoCard;
    /// Whether the todo item rows are shown (vs. just the "任务清单 (n/m)" header). Defaults to
    /// expanded — unlike the jobs pane, the checklist is usually short and worth seeing at a
    /// glance — but the header is clickable to collapse it (previously there was no way to at all).
    private boolean todoExpanded = true;

    // ---- Toolbar ----

    private final LineSelectButton<String> modelSelector = new LineSelectButton<>();
    /// Re-entrancy latch for {@link #setupModelSelector()}: true while the refresh itself is
    /// writing items/value into {@link #modelSelector}, so the (single, installed-once) value
    /// listener can tell a programmatic refresh apart from a real user selection and skip the
    /// side effects (clearAgentCache + settings write) that must only follow a user action.
    private boolean modelSelectorUpdating = false;
    // ---- Messages ----

    // Spacing 4 keeps same-turn nodes (subordinate cards + answer bubble) visually grouped; turn
    // separation comes from the user bubble's extra 8px top margin (VS §1.4: 4 + 8 = 12 net).
    private final VBox messageList = new VBox(4);
    private final ScrollPane scrollPane = new ScrollPane(messageList);
    private final Label statusLabel = new Label();
    /// Single-line strip above the conversation echoing the most recent legacy tool message —
    /// downgraded from a stacked panel to one caption line with no container fill (VS §3.3 末).
    private final Label toolActivityLabel = new Label();

    private final VBox emptyState = new VBox(12);
    /// Suggestion chips block of the empty state — visible only when a usable model is configured.
    private VBox suggestionsBox;
    /// "No model service configured" hint + CTA block of the empty state (A14) — the mutually
    /// exclusive sibling of {@link #suggestionsBox}, visible when nothing usable is configured.
    private VBox noProviderBox;

    // ---- Composer ----

    /// Multi-line auto-growing composer (was a single-line TextField — long/pasted text was
    /// squashed onto one line and Shift+Enter could not insert a newline despite the settings
    /// copy promising it).
    private final TextArea inputField = new TextArea();
    /// True while a Chinese/Japanese IME composition is in progress: the Enter that commits
    /// the composition must NEVER be treated as "send" (it used to fire half-typed pinyin).
    private boolean imeComposing;
    /// Per-session composer drafts, saved on session switch and restored on return.
    private final java.util.Map<String, String> sessionDrafts = new HashMap<>();
    /// The session whose draft the composer currently holds (for save-on-switch).
    @Nullable
    private String draftSessionId;
    private JFXButton sendBtn;
    /// Reasoning-effort popup button in the composer (a field so tests can drive the menu).
    private JFXButton thinkBtn;

    /// Panel shown above the input field when the agent calls the `ask` tool: renders the
    /// structured questions and a confirm button. Hidden/unmanaged when no question is pending.
    private final VBox askPanel = new VBox(8);
    /// The in-flight `ask` future (completed on confirm; cancelled on stop/session switch, or by
    /// {@link org.jackhuang.hmcl.ui.ai.tools.AskTool} itself if its wait times out unanswered).
    @Nullable
    private volatile java.util.concurrent.CompletableFuture<java.util.List<String>> activeAsk;

    /// Bookkeeping for the currently-displayed dangerous/critical confirm dialog (see
    /// {@link #showConfirmDialog}), mirroring {@link #activeAsk} for the exact same reason: the
    /// tool layer's {@code confirm()} call blocks the streaming client's OWN callback thread — NOT
    /// the executor thread wrapped by {@link #currentResponse} — so cancelling
    /// {@code currentResponse} alone never unblocks it. Without tracking this, the dialog (and its
    /// blocked agent thread) would keep running for up to the full 120s/180s timeout after the user
    /// pressed Stop, and — because {@link #exitStreamingState()} frees the app to start a brand-new
    /// turn immediately — a fresh dialog could stack on top of it, letting the stale one resurface
    /// and be answered completely out of context once the new one is dismissed.
    private static final class PendingConfirm {
        final java.util.concurrent.CompletableFuture<Boolean> future;
        final java.util.concurrent.atomic.AtomicReference<javafx.scene.layout.Region> paneRef;
        /// {@link #responseGeneration} at the moment this dialog was raised — lets a confirm
        /// belonging to an already-abandoned turn be told apart from one still legitimately
        /// pending for the CURRENT turn.
        final int generation;

        PendingConfirm(java.util.concurrent.CompletableFuture<Boolean> future,
                       java.util.concurrent.atomic.AtomicReference<javafx.scene.layout.Region> paneRef,
                       int generation) {
            this.future = future;
            this.paneRef = paneRef;
            this.generation = generation;
        }
    }

    /// The dangerous/critical confirm dialog currently awaiting the user's answer, or
    /// {@code null} when none is pending. Set just before showing the dialog and cleared once it's
    /// answered/cancelled/timed out — see {@link #showConfirmDialog} and
    /// {@link #cancelActiveConfirm()}.
    @Nullable
    private volatile PendingConfirm activeConfirm;

    // ---- Typing indicator ----

    private String typingBaseText = "";
    private final Timeline typingTimeline = new Timeline(
            new KeyFrame(javafx.util.Duration.ZERO, e -> statusLabel.setText(typingBaseText + ".")),
            new KeyFrame(javafx.util.Duration.millis(400), e -> statusLabel.setText(typingBaseText + "..")),
            new KeyFrame(javafx.util.Duration.millis(800), e -> statusLabel.setText(typingBaseText + "..."))
    );

    /// The current streaming AI *text segment* bubble (null between turns, i.e. while a tool
    /// card is the last thing appended). A new bubble is created when text resumes after a tool.
    @Nullable
    private Label streamingBubble;

    /// Id of the session whose response is currently streaming (null when idle). Distinct from the
    /// on-screen session: the user may switch away mid-stream, so the Send/Stop button and the
    /// sidebar "生成中…" indicator must track the STREAMING session, not the visible one.
    private String streamSessionId;

    /// Live reasoning ("思考过程") card for the in-flight turn, appended token by token; null when
    /// the turn has produced no reasoning yet. Reset at the start of each turn / session load.
    private ReasoningCard reasoningLiveCard;

    /// Collapsible card showing a model's reasoning/"thinking" (e.g. DeepSeek-R1's reasoning_content):
    /// expanded while it streams, collapsed once the answer starts or on reload. Self-contained so it
    /// can be reused by both the live stream path and the session-reload path.
    /// Package-private (not private) so the FX component test can drive it directly.
    static final class ReasoningCard extends VBox {
        private final Label content = new Label();
        private final CollapseHeader header;
        private final StringBuilder text = new StringBuilder();

        ReasoningCard(String initial, boolean expanded) {
            // Shares .ai-tool-card with ToolCard/ToolCallGroupCard: the three inline subordinate
            // cards keep a byte-identical box model (§B B2 constraint) from a single CSS rule.
            getStyleClass().add("ai-tool-card");
            setSpacing(4);
            setMaxWidth(AI_BUBBLE_MAX_WIDTH - 16); // 704 — unified subordinate-card width (VS §3.3)
            content.setWrapText(true);
            content.setMaxWidth(AI_BUBBLE_MAX_WIDTH - 16);
            content.getStyleClass().add("ai-caption"); // rule lands with the B7 css rewrite
            if (initial != null) {
                text.append(initial);
                content.setText(initial);
            }
            header = new CollapseHeader("思考过程"); // TODO(i18n)
            content.visibleProperty().bind(header.expandedProperty());
            content.managedProperty().bind(header.expandedProperty());
            getChildren().addAll(header, content);
            setExpanded(expanded);
        }

        void append(String token) {
            text.append(token);
            content.setText(text.toString());
        }

        /// Thin wrapper kept for the streaming path (collapse the card once the visible answer
        /// starts) and for tests — the expand state itself lives on the header.
        void setExpanded(boolean expanded) {
            header.setExpanded(expanded);
        }
    }

    /// Tool-call cards awaiting their result, keyed by tool name (FIFO per name) so onToolResult
    /// can find the matching card appended by onToolActivity.
    private final java.util.Map<String, java.util.Deque<ToolCard>> pendingToolCards = new java.util.HashMap<>();

    /// The tool card currently executing (set on tool-call, cleared on its result). Live download
    /// / install progress from {@link org.jackhuang.hmcl.ai.tools.ToolProgress} is routed here, so
    /// the chat shows a real-time progress bar instead of freezing. Tools run sequentially, so a
    /// single "active" card is sufficient. JavaFX-thread confined.
    @Nullable
    private ToolCard activeToolCard;

    /// The group card currently absorbing an uninterrupted run of tool calls (3rd+ consecutive
    /// call onward), or {@code null} when the current run hasn't been promoted to a group yet
    /// (0 or 1 calls so far). See {@link #appendToolCard} / {@link #endToolCallRun}.
    @Nullable
    private ToolCallGroupCard activeToolGroup;
    /// The single standalone tool card added so far in the current run, before a 2nd call arrives
    /// to justify grouping — and the wrapper HBox it was placed in, so it can be pulled back out
    /// of {@link #messageList} and re-parented into a new group. Both null once a run reaches 0,
    /// 2+ (grouped), or ends.
    @Nullable
    private ToolCard lastSoloToolCard;
    @Nullable
    private HBox lastSoloToolCardWrapper;

    /// Whether the message view should auto-scroll to the bottom on new content. Set false when
    /// the user scrolls up to read history; restored when they scroll back to the bottom.
    private boolean stickToBottom = true;

    /// The in-flight streaming response future (for stop/abort), and a generation counter
    /// used to ignore callbacks from a response the user has stopped.
    @Nullable
    private java.util.concurrent.CompletableFuture<Void> currentResponse;
    // volatile: onError()/the .exceptionally() handler read this on a background (langchain4j
    // callback) thread before re-checking it a second time inside showAiError()'s Platform.runLater
    // block — without volatile there is no cross-thread visibility guarantee that either read
    // observes a concurrent update from the FX thread (e.g. stopResponse()'s responseGeneration++).
    private volatile int responseGeneration = 0;
    /// Cancellation flag for the in-flight turn; set true by stopResponse() so the agent drops the
    /// streamed reply instead of persisting it after the user pressed Stop.
    @Nullable
    private java.util.concurrent.atomic.AtomicBoolean currentCancelled;
    /// The agent serving the in-flight streaming turn, so stopResponse() can ask it to persist
    /// the interrupted partial reply immediately (instead of whenever the abandoned stream ends).
    @Nullable
    private ChatAgent currentStreamAgent;

    /// The currently-open thinking-level popup, tracked to prevent stacking duplicates.
    @Nullable
    private JFXPopup thinkingPopup;

    /// Per-tool/per-action confirm-dialog overrides written by the "remember this choice" checkbox
    /// on the non-critical confirm dialog — see {@link #showConfirmDialog}. Points at the SAME file
    /// the AI settings page's own per-tool permission list reads/writes
    /// ({@code ai-tool-permissions.json}), so a choice remembered here shows up there too, and vice
    /// versa. Re-{@code load()}ed immediately before every write so a concurrent edit made through
    /// that page's own (separate in-memory) instance isn't silently clobbered.
    private final org.jackhuang.hmcl.ai.tools.AiToolPermissionStore toolPermissionStore =
            new org.jackhuang.hmcl.ai.tools.AiToolPermissionStore(
                    SettingsManager.localConfigDirectory().resolve("ai-tool-permissions.json"));


    // ---- Chat settings ----

    private final ChatSettings chatSettings;
    private static final Gson CHAT_SETTINGS_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String CHAT_SETTINGS_FILE = "ai-chat-settings.json";
    private static final String SEARCH_CONFIG_FILE = "ai-search-settings.json";
    private static final String OCR_CONFIG_FILE = org.jackhuang.hmcl.ai.ocr.AiOcrConfig.FILE_NAME;
    /// Web-search config, loaded from disk and shared by the web_search tool + the prompt.
    private final org.jackhuang.hmcl.ai.search.AiSearchConfig searchConfig;
    /// OCR config, loaded from disk and shared by the ocr_image tool.
    private final org.jackhuang.hmcl.ai.ocr.AiOcrConfig ocrConfig;
    /// Global memory store (created in registerTools); shared with the prompt builder for auto-recall.
    private org.jackhuang.hmcl.ai.remember.RememberStore rememberStore;

    @Nullable
    private VBox chatSettingsDrawer;
    @Nullable
    private StackPane chatSettingsBackdrop;

    // ---- Session management ----

    // ---- Search dialog ----

    /// Double-open guard for the cross-session search dialog (blueprint B5): rapid clicks on
    /// the toolbar button / repeated `/sessions` must not stack a second dialog. Reset by the
    /// dialog's close callback ([AiSearchDialog#onDialogClosed]).
    private boolean searchDialogShowing = false;

    // ---- File upload ----

    @Nullable
    private VBox fileChipArea;
    private javafx.scene.layout.FlowPane fileChipFlow;
    /// Files attached to the NEXT message. Content is read at send time and appended to the
    /// outbound prompt; removing a chip only removes that file (never touches the draft text).
    private final java.util.List<Path> attachedFiles = new java.util.ArrayList<>();
    /// Live "background tasks" pull-up above the composer: a thin header (后台任务 + running count)
    /// that unfurls upward into a compact per-category job list.
    @Nullable
    private VBox jobsPane;
    private VBox jobsListContainer;
    private Label jobsCountLabel;
    private javafx.scene.Node jobsToggleIcon;
    private boolean jobsExpanded = false;
    @Nullable
    private Timeline jobsAnimation;
    /// Consecutive auto-continue turns without a real user message; capped to stop a runaway
    /// background-job ↔ auto-continue loop from silently burning tokens. Reset on a real user send.
    private static final int AUTO_CONTINUE_LIMIT = 5;
    private int autoContinueDepth = 0;
    /// Background jobs whose completion arrived while a turn was streaming; drained one-at-a-time by
    /// exitStreamingState() so a mid-turn completion still auto-continues instead of being dropped.
    private final java.util.ArrayDeque<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> pendingCompletions = new java.util.ArrayDeque<>();
    /// External prompts (diagnostics/crash hand-offs) that arrived while a turn was streaming;
    /// previously they were silently dropped. Drained one-at-a-time by {@link #exitStreamingState()}
    /// — BEFORE {@link #pendingCompletions}, since a user-visible diagnostic request outranks a
    /// background-job receipt.
    private final java.util.ArrayDeque<String> pendingExternalPrompts = new java.util.ArrayDeque<>();
    /// Whether the turn currently being sent/streamed is a synthetic event turn (see
    /// {@link #isPossiblyUnattended}) rather than a direct, just-now user message. Set at the start
    /// of {@link #sendText} for every turn; read from the agent's background thread via
    /// {@link #isPossiblyUnattended}, so this must stay {@code volatile}.
    private volatile boolean currentTurnUnattended = false;

    // ---- Autocomplete ----

    @Nullable
    private VBox autocompletePopup;
    private final List<String> autocompleteItems = new ArrayList<>();
    private int autocompleteSelectedIndex = -1;

    // ---- Constructor ----

    public AIMainPage() {
        this.aiSettings = new AiSettings(SettingsManager.localConfigDirectory());
        try {
            aiSettings.load();
        } catch (Exception ignored) {
        }
        // Picks up any per-tool/action/path overrides already persisted (e.g. via the AI settings
        // page's own separate in-memory instance of this same file) so the very first agent built
        // this run resolves them from the start, not only after this page's own "remember this
        // choice" checkbox happens to reload+save the file (see rememberConfirmDecision).
        try {
            toolPermissionStore.load();
        } catch (Exception ignored) {
        }

        // Observability: record a complete per-session agent trace (full messages / raw response /
        // finishReason / complete tool IO / guard decisions) to an INDEPENDENT jsonl under
        // .hmcl/logs/ai-trace/ — the truth record the summarised [AI] log and mutable session store
        // cannot provide. Default on in this Alpha; a settings toggle can disable it.
        org.jackhuang.hmcl.ai.trace.TraceRecorder.configure(
                SettingsManager.localConfigDirectory().resolve("logs").resolve("ai-trace"),
                aiSettings.isTraceEnabled());

        // First-use dialogs (risk notice → privacy consent) are shown CHAINED at the end of the
        // constructor — see runFirstUseDialogs(); queueing them separately stacked two modal
        // dialogs on top of each other on a fresh install.

        // Warm the model library off the FX thread so the first model-dialog lookup (which auto-fills
        // context/pricing/modalities) doesn't stall the UI parsing the bundled JSON on the FX thread.
        Thread modelLibPreload = new Thread(() -> {
            try {
                org.jackhuang.hmcl.ai.ModelLibrary.getInstance();
            } catch (Throwable ignored) {
            }
        }, "model-library-preload");
        modelLibPreload.setDaemon(true);
        modelLibPreload.start();

        this.sessionStore = new AiSessionStore(SettingsManager.localConfigDirectory());
        // On a corrupt/unreadable store the damaged file is set aside (never overwritten by the
        // save below) and the user is told where it went; previously the load failure was
        // swallowed and the empty store was saved right over the whole conversation history.
        java.nio.file.Path corruptSessionStore = sessionStore.loadOrQuarantine();
        if (corruptSessionStore != null) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "[AI] session store was corrupt; preserved at " + corruptSessionStore);
            final String preservedAt = corruptSessionStore.toString();
            Platform.runLater(() -> addSystemMessage(
                    "会话记录文件损坏，未能加载历史对话。原文件已保留为：" + preservedAt
                            + "（不会被覆盖，如需找回可以把它发给 AI 修复）。"));
        }

        // Saves are asynchronous now (see persistStore() → AiSessionStore.saveAsync); flush one
        // last synchronous snapshot on normal JVM exit so a save still sitting in the queue can't
        // be lost. save() is synchronized, so this simply serialises with any in-flight async
        // save and the LAST write is always the final in-memory state.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sessionStore.save();
            } catch (Exception ignored) {
            }
        }, "ai-session-save-flush"));

        this.chatSettings = loadChatSettings();
        this.searchConfig = loadSearchConfig();
        this.ocrConfig = loadOcrConfig();

        if (sessionStore.getCurrentSession() == null) {
            sessionStore.createSession();
            persistStore();
        }

        typingTimeline.setCycleCount(Timeline.INDEFINITE);
        // no getStyleClass().add("gray-background") here — DecoratorAnimatedPage already adds it

        buildSidebar();
        buildChatView();
        buildChatSettingsDrawer();
        buildLayout();

        registerTools();
        installToolProgressListener();

        // Seed built-in skills and load the skill list so the agent's prompt knows them.
        skillRegistry.setSkillsDir(SettingsManager.localConfigDirectory().resolve("ai-skills"));
        try {
            skillRegistry.refresh();
        } catch (Exception ignored) {
        }

        applyChatSettings();

        // The model-switch listener is installed exactly ONCE here — setupModelSelector() used to
        // re-register it on every refresh, accumulating listeners that each re-cleared the agent
        // cache and re-saved the settings whenever the refresh itself touched the value (P2).
        installModelSelectorListener();
        refreshModelSelector();

        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            // Establish the draft-ownership invariant from the start: draftSessionId is ALWAYS
            // the session whose text the composer currently holds (see stashDraftFor).
            draftSessionId = current.getId();
            loadSessionMessages(current);
            updateHeader(current);
        }

        // First-run dialogs (risk notice → privacy consent), chained so they can never stack.
        // Deferred so they appear over the ready scene, not mid-construction.
        Platform.runLater(this::runFirstUseDialogs);
    }

    /// Shows the first-use dialogs IN SEQUENCE: the test-phase risk notice first, and the privacy
    /// disclosure only after it is acknowledged (or immediately when the notice was already
    /// accepted on an earlier run). Both were previously queued in the same runLater window and
    /// piled on top of each other as two stacked modal dialogs on a fresh install (bug 7.15).
    private void runFirstUseDialogs() {
        showAiRiskNotice(AIMainPage::maybeShowPrivacyConsent);
    }

    private static final String PRIVACY_TEXT =
            "使用 AI 助手时，你的对话内容会通过网络发送给你所配置的 AI 服务提供商，用于生成回复。\n\n"
            + "根据你启用的功能，发送的内容可能包含：你输入的问题；所选实例 / 模组 / 世界等游戏信息；"
            + "你主动让 AI 读取的文件或日志；剪贴板文本；以及截图（仅当你使用相关功能时）。\n\n"
            + "这些数据由第三方 AI 提供商按其各自的隐私政策处理。此外，HMCL-AE 本身默认会在本机记录一份完整的"
            + "对话与工具调用记录（trace，用于故障排查，可在 AI 设置里关闭），只保存在本地；"
            + "你也可以在需要反馈问题时手动一键将其发送给开发者用于诊断，这是唯一的额外上传途径，不会自动上传。\n\n"
            + "点击「确定」表示你已阅读并同意上述数据处理方式；若不同意，请勿使用 AI 助手。";

    private static java.nio.file.Path privacyConsentFile() {
        return SettingsManager.localConfigDirectory().resolve("ai-privacy-consent");
    }

    static boolean hasPrivacyConsent() {
        return java.nio.file.Files.exists(privacyConsentFile());
    }

    /// First-run privacy disclosure: shown once until the user acknowledges (which writes a marker).
    static void maybeShowPrivacyConsent() {
        if (hasPrivacyConsent()) {
            return;
        }
        requestPrivacyConsent(() -> {
        });
    }

    /// Shows the privacy dialog and, if the user acknowledges it, writes the consent marker and
    /// runs {@code onAccepted}. Unlike {@link #maybeShowPrivacyConsent}, this is NOT gated on
    /// {@link #hasPrivacyConsent()} — it's the re-prompt path a caller blocked on that check (e.g.
    /// the diagnostic-upload trigger in {@code AISettingsPage}) uses to let the user grant consent
    /// on the spot and immediately retry, instead of the marker being a dead end once it's missing.
    static void requestPrivacyConsent(Runnable onAccepted) {
        Controllers.dialog(PRIVACY_TEXT, "AI 隐私与数据说明",
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO, () -> {
                    try {
                        java.nio.file.Files.writeString(privacyConsentFile(),
                                java.time.Instant.now().toString(),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                    }
                    onAccepted.run();
                });
    }

    /// Re-viewable privacy notice (does not change the consent marker), for the settings entry.
    static void showPrivacyNotice() {
        Controllers.dialog(PRIVACY_TEXT, "AI 隐私与数据说明",
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO);
    }

    /// Registers all AI tools in the shared tool registry.
    private void registerTools() {
        // General-purpose toolset: read / write / edit / grep / glob / shell /
        // web_fetch. Narrow bespoke wrappers (crash analysis, log reader, mod toggle, file
        // backup) are intentionally dropped — that knowledge belongs in the system prompt,
        // which teaches the agent which files/paths to use with these generic tools.
        Path configRoot = SettingsManager.localConfigDirectory();
        fileReadTool = new FileReadTool(configRoot);
        fileWriteTool = new WriteFileTool(configRoot);
        editTool = new EditTool(configRoot);
        grepTool = new GrepTool(configRoot);
        globTool = new GlobTool(configRoot);
        // Widen each filesystem tool to the HMCL home (game directory added in refreshGameContext).
        fileReadTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        fileWriteTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        editTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        grepTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        globTool.addRoot(Metadata.HMCL_LOCAL_HOME);

        toolRegistry.register(fileReadTool);
        toolRegistry.register(fileWriteTool);
        toolRegistry.register(editTool);
        toolRegistry.register(grepTool);
        toolRegistry.register(globTool);
        if (aiSettings.isShellToolEnabled()) {
            toolRegistry.register(new ShellTool());
        }
        if (aiSettings.isWebAccessEnabled()) {
            toolRegistry.register(new WebFetchTool());
            toolRegistry.register(new org.jackhuang.hmcl.ai.search.WebSearchTool(searchConfig));
        }
        toolRegistry.register(gameContextTool);
        // Local instance/mod/world/content-management domain (merged facade — see InstanceTool).
        instanceTool = new org.jackhuang.hmcl.ui.ai.tools.InstanceTool(
                aiSettings::isDeleteToRecycleBin, aiSettings::getWorldBackupRetention, aiSettings::isNbtToolsEnabled);
        toolRegistry.register(instanceTool);
        // Runtime instance state: list (+ running flag) / launch / stop.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.GameTool(aiSettings::getWorldBackupRetention));
        // External content search (Modrinth/CurseForge/version manifest) — install/list-local
        // live on InstanceTool.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchTool());
        // Accounts (reuse HMCL's account system — never shell out / hand-edit for login).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.AccountTool());
        // Background jobs: query/cancel long-running tasks started with background=true (AiJobManager).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.JobTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.AskTool(this::showAskPanel));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool(this::updateTodoCard));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.KnownErrorMatcherTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListLocalIpTool());
        toolRegistry.register(new org.jackhuang.hmcl.ai.tools.SleepTool());
        toolRegistry.register(new org.jackhuang.hmcl.ai.tools.LoadSkillTool(skillRegistry));
        // Global memory (file-based store; remember/recall across conversations).
        rememberStore = new org.jackhuang.hmcl.ai.remember.RememberStore(
                        SettingsManager.localConfigDirectory().resolve("ai-memory"));
        if (aiSettings.isMemoryEnabled()) {
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RememberTool(rememberStore));
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RecallTool(rememberStore));
        }
        // Diagnostics (reuse HMCL SystemInfo hardware detection).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SystemInfoTool());
        // OCR a screenshot/image into text (crash/error shots) — backend chosen in AI 设置 > OCR.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.OcrImageTool(ocrConfig));
        // Convenience (clipboard for pasted crash logs / errors, conversation export, prompt presets).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CopyToClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.PromptLibraryTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ExportConversationTool(sessionStore));
        // Save NBT editing (read/write save & player NBT data; writes are backup-gated +
        // path-confined + atomic, and trigger the red critical confirmation via CriticalOperations).
        // Hidden by default like global memory; see AiSettings#isNbtToolsEnabled().
        if (aiSettings.isNbtToolsEnabled()) {
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.NbtTool());
            // worlds_info lives on InstanceTool (its execute() re-checks isNbtToolsEnabled()
            // itself since that one action, unlike the rest of the domain, reads NBT).
        }
        // Wire the currently-selected Minecraft run directory into the filesystem tools.
        // Refreshed again before each send so the tools always target the selected instance.
        refreshGameContext();
    }

    /// Resolves the currently-selected Minecraft run directory and widens the filesystem
    /// tools' allowed roots to include it, so the agent can read/search game files and logs.
    private void refreshGameContext() {
        Path runDir = resolveCurrentGameDir();
        gameContextTool.setGameDir(runDir);
        // Surface the selected instance + version-isolation state to the agent.
        try {
            Profile profile = Profiles.getSelectedProfile();
            String sel = profile != null ? Profiles.getSelectedInstance(profile) : null;
            boolean isolated = runDir != null && profile != null
                    && !runDir.equals(profile.getRepository().getBaseDirectory());
            gameContextTool.setInstanceInfo(sel, isolated);
        } catch (Throwable ignored) {
            gameContextTool.setInstanceInfo(null, false);
        }
        if (runDir != null) {
            fileReadTool.addRoot(runDir);
            fileWriteTool.addRoot(runDir);
            editTool.addRoot(runDir);
            grepTool.addRoot(runDir);
            globTool.addRoot(runDir);
            // mods_install needs the current instance's run dir; re-target on every switch.
            instanceTool.refreshRunDir(runDir);
        }
    }

    /// Returns the run directory of the currently-selected instance, falling back to
    /// the profile's base directory, or `null` when no profile/repository is available.
    @Nullable
    private static Path resolveCurrentGameDir() {
        try {
            Profile profile = Profiles.getSelectedProfile();
            if (profile == null) {
                return null;
            }
            var repository = profile.getRepository();
            String version = Profiles.getSelectedInstance(profile);
            if (version != null && repository.hasVersion(version)) {
                return repository.getRunDirectory(version);
            }
            return repository.getBaseDirectory();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- Layout assembly ----

    private void buildLayout() {
        chatSettingsStack.getChildren().setAll(chatView);
        chatSettingsStack.getStyleClass().add("ai-chat-stack");

        StackPane centerWithDrawer = new StackPane(chatSettingsStack);

        // Backdrop (semi-transparent overlay behind drawer)
        chatSettingsBackdrop = new StackPane();
        chatSettingsBackdrop.getStyleClass().add("ai-backdrop"); // shared scrim (C-19)
        chatSettingsBackdrop.setVisible(false);
        chatSettingsBackdrop.setOnMouseClicked(e -> hideChatSettingsDrawer());

        if (chatSettingsDrawer != null) {
            StackPane.setAlignment(chatSettingsDrawer, Pos.CENTER_RIGHT);
        }

        if (chatSettingsDrawer != null) {
            centerWithDrawer.getChildren().addAll(chatSettingsBackdrop, chatSettingsDrawer);
        }

        setCenter(centerWithDrawer);
        showChatView();
    }

    // ---- Sidebar ----

    private void buildSidebar() {
        sessionListBox.setPadding(new Insets(0, 0, 4, 0));

        sidebarRoot = new VBox();
        // Match the chat view's 12px vertical inset so the bottom "AI Settings" entry
        // lines up with the bottom edge of the right-hand conversation card.
        sidebarRoot.setPadding(new Insets(0, 0, 12, 0));
        VBox.setVgrow(sidebarRoot, Priority.ALWAYS);
        setLeft(sidebarRoot);
        rebuildChatSidebar();
    }

    private void rebuildChatSidebar() {
        if (sidebarRoot == null) return;
        sidebarRoot.getChildren().clear();

        AdvancedListItem newChatItem = new AdvancedListItem();
        newChatItem.getStyleClass().add("navigation-drawer-item");
        newChatItem.setTitle(i18n("ai.new_conversation"));
        newChatItem.setLeftIcon(SVG.ADD);
        newChatItem.setOnAction(e -> createSession());

        // "New Chat" pinned at the top: fixed height, never scrolls with the session list.
        // Same container (AdvancedListBox) the New Chat row originally lived in, so its padding
        // and top position stay identical to before — just pinned and fixed-height.
        AdvancedListBox topBox = new AdvancedListBox();
        topBox.add(newChatItem);
        VBox.setVgrow(topBox, Priority.NEVER);
        topBox.setMinHeight(Region.USE_PREF_SIZE);
        topBox.setMaxHeight(Region.USE_PREF_SIZE);

        sidebarScrollPane = new AdvancedListBox();
        sidebarScrollPane.add(sessionListBox);
        VBox.setVgrow(sidebarScrollPane, Priority.ALWAYS);
        // Shrink below content height so only the session list scrolls, between the pinned
        // "New Chat" (top) and "AI settings" (bottom) rows.
        sidebarScrollPane.setMinHeight(0);

        AdvancedListItem feedbackItem = new AdvancedListItem();
        feedbackItem.getStyleClass().add("navigation-drawer-item");
        feedbackItem.setTitle(i18n("ai.feedback"));
        feedbackItem.setLeftIcon(SVG.FEEDBACK);
        // Direct-invoke the exact same upload flow AISettingsPage's "上传诊断信息" row uses —
        // no navigation into AISettingsPage first. See DiagnosticUploadFlow for why this is a
        // shared helper rather than being re-implemented here.
        feedbackItem.setOnAction(e -> DiagnosticUploadFlow.trigger(aiSettings));

        AdvancedListItem settingsItem = new AdvancedListItem();
        settingsItem.getStyleClass().add("navigation-drawer-item");
        settingsItem.setTitle(i18n("ai.settings"));
        settingsItem.setLeftIcon(SVG.TUNE);
        settingsItem.setOnAction(e -> openSettingsPage());

        // Pin "反馈" and "AI settings" as fixed-height rows at the very bottom: they never grow
        // or shrink, so however long the session list gets, the scroll area above absorbs the
        // change and these entries always keep their own dedicated, full-size space.
        //
        // Deliberately a plain VBox — NOT an AdvancedListBox (which wraps a ScrollPane) — even
        // though this container only ever holds a couple of fixed rows. AdvancedListBox's own
        // ScrollPane toggles its vertical scrollbar to AS_NEEDED whenever
        // `container.getHeight() > getHeight()` on mouse-enter (see AdvancedListBox's
        // MOUSE_ENTERED filter); with -fx-snap-to-pixel disabled on .scroll-pane (root.css) that
        // comparison is one stray sub-pixel away from spuriously tripping for a fixed set of
        // rows that were never meant to scroll in the first place — the reported "翻页"/unwanted
        // side scrollbar. A bare VBox has no scrollbar machinery at all, so this class of bug is
        // structurally impossible here, and it still gets the identical 12px top inset by reusing
        // the same "advanced-list-box-content" style class AdvancedListBox applies to its inner
        // content VBox.
        VBox bottomBox = new VBox();
        bottomBox.getStyleClass().add("advanced-list-box-content");
        bottomBox.getChildren().addAll(feedbackItem, settingsItem);
        VBox.setVgrow(bottomBox, Priority.NEVER);
        bottomBox.setMinHeight(Region.USE_PREF_SIZE);
        bottomBox.setMaxHeight(Region.USE_PREF_SIZE);
        sidebarRoot.getChildren().addAll(topBox, sidebarScrollPane, bottomBox);
        refreshSessionList();
    }

    /// Navigates to the AI settings page. Single entry point shared by the sidebar "AI 设置" row
    /// and the empty state's "配置模型服务" CTA (A14) — keeps the constructor wiring (shared
    /// search/OCR config instances, refresh callback) in exactly one place.
    private void openSettingsPage() {
        Controllers.navigate(new AISettingsPage(
                aiSettings,
                discoveryService,
                () -> {
                    clearAgentCache();
                    refreshModelSelector();
                    AiSession current = sessionStore.getCurrentSession();
                    if (current != null) {
                        updateHeader(current);
                    }
                    // The empty state's chips/CTA split depends on whether a usable model exists —
                    // re-evaluate after settings edits so configuring a provider retires the CTA.
                    updateEmptyState();
                },
                // Share THE instances the chat tools hold (WebSearchTool/OcrImageTool), so edits
                // made in the settings page take effect in chat immediately — the settings page
                // used to load its own copies and the tools never saw any change.
                this.searchConfig,
                this.ocrConfig
        ));
    }

    private void refreshSessionList() {
        ObservableList<Node> children = sessionListBox.getChildren();
        children.clear();

        String currentId = sessionStore.getCurrentSessionId();
        List<AiSession> sessions = sessionStore.listSessions();

        for (AiSession session : sessions) {
            boolean isActive = session.getId().equals(currentId);
            children.add(buildSessionItem(session, isActive));
        }
    }

    /// Updates the active highlight of existing sidebar session items in place,
    /// matching each item's stored session id against the current session.
    private void updateSessionHighlight(String currentId) {
        for (Node node : sessionListBox.getChildren()) {
            if (node instanceof AdvancedListItem item) {
                item.setActive(currentId.equals(item.getProperties().get(SESSION_ID_KEY)));
            }
        }
    }

    private AdvancedListItem buildSessionItem(AiSession session, boolean isActive) {
        String labelText = (session.getTitle() != null && !session.getTitle().isEmpty())
                ? session.getTitle() : i18n("ai.session.untitled");

        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(labelText);
        boolean streamingHere = currentResponse != null && session.getId().equals(streamSessionId);
        item.setSubtitle(streamingHere ? "正在生成…" : relativeTime(session.getUpdatedAt()));
        if (streamingHere) {
            // Swap the chat icon for a small JFoenix spinner while this session streams (keeps the
            // "⋯" row menu intact, unlike overriding the right graphic).
            com.jfoenix.controls.JFXSpinner spinner = new com.jfoenix.controls.JFXSpinner();
            spinner.setRadius(8);
            item.setLeftGraphic(spinner);
        } else {
            // Pinned state is expressed by the left icon (pin vs chat), not a "📌 " title prefix.
            item.setLeftIcon(session.isPinned() ? SVG.KEEP : SVG.CHAT);
        }
        item.setActive(isActive);
        item.getStyleClass().add("navigation-drawer-item");
        item.getProperties().put(SESSION_ID_KEY, session.getId());
        FXUtils.installFastTooltip(item, labelText);
        // "⋯" menu instead of a bare delete icon: every chat client lets you RENAME a session;
        // deleting the whole session used to be the only way to fix a bad auto-title.
        item.setRightAction(SVG.MORE_VERT, () -> showSessionMenu(item, session));
        item.setOnAction(e -> {
            String id = session.getId();
            sessionStore.setCurrentSessionId(id);
            AiSession current = sessionStore.getCurrentSession();
            if (current != null) {
                loadSession(current);
                updateHeader(current);
            }
            // Update the active highlight in place instead of rebuilding the whole
            // sidebar — rebuilding destroyed the clicked item mid-ripple, cutting the
            // ripple animation to a few frames.
            updateSessionHighlight(id);
            persistStore();
        });

        return item;
    }

    /// Rename / pin / delete menu for a sidebar session row — a native PopupMenu +
    /// IconedMenuItem (same form language as VersionPage's browse/management menus),
    /// replacing the previous hand-rolled VBox of bare JFXButtons.
    private void showSessionMenu(Node anchor, AiSession session) {
        PopupMenu menu = new PopupMenu();
        JFXPopup popup = new JFXPopup(menu);

        String title = session.getTitle() == null || session.getTitle().isBlank()
                ? i18n("ai.session.untitled") : session.getTitle();

        menu.getContent().setAll(
                new IconedMenuItem(SVG.EDIT, "重命名", () -> Controllers.prompt("重命名会话", (result, handler) -> { // TODO(i18n)
                    String name = result == null ? "" : result.trim();
                    if (!name.isEmpty()) {
                        session.setTitle(name);
                        persistStore();
                        refreshSessionList();
                        AiSession current = sessionStore.getCurrentSession();
                        if (current == session) {
                            updateHeader(session);
                        }
                    }
                    handler.resolve();
                }, session.getTitle() == null ? "" : session.getTitle()), popup),
                new IconedMenuItem(SVG.KEEP, session.isPinned() ? "取消置顶" : "置顶", () -> { // TODO(i18n)
                    session.setPinned(!session.isPinned());
                    persistStore();
                    refreshSessionList();
                }, popup),
                new MenuSeparator(),
                new IconedMenuItem(SVG.DELETE, "删除", () -> Controllers.confirm( // TODO(i18n)
                        "删除会话「" + title + "」？此操作不可恢复。", // TODO(i18n)
                        i18n("button.remove"),
                        () -> deleteSession(session.getId()),
                        null), popup));

        popup.show(anchor, JFXPopup.PopupVPosition.TOP, JFXPopup.PopupHPosition.RIGHT, 0, 0);
    }

    /// Compact relative time for the session list subtitle (今天 HH:mm / 昨天 / M月d日).
    private static String relativeTime(java.time.Instant instant) {
        if (instant == null) {
            return "";
        }
        java.time.LocalDate date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now();
        if (date.equals(today)) {
            return instant.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (date.equals(today.minusDays(1))) {
            return "昨天";
        }
        if (date.getYear() == today.getYear()) {
            return date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"));
        }
        return date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"));
    }

    private void deleteSession(String sessionId) {
        // Deleting the session whose response is still streaming used to evict (shutdownNow) its
        // agent without ever clearing currentResponse — if the interrupt never reached
        // onError/onComplete, isStreaming() stayed true forever and the Send button was dead for
        // the rest of the run. Stop the stream PROPERLY first (persistInterrupted +
        // exitStreamingState + generation bump), then delete.
        if (sessionId.equals(streamSessionId) && isStreaming()) {
            stopResponse();
        }
        String currentId = sessionStore.getCurrentSessionId();
        sessionStore.deleteSession(sessionId);
        evictAgent(sessionId);

        if (sessionId.equals(currentId)) {
            resetConversationView(); // discard any view state left over from the deleted session
            AiSession newCurrent = sessionStore.getCurrentSession();
            if (newCurrent != null) {
                loadSessionMessages(newCurrent);
                updateHeader(newCurrent);
            } else {
                headerTitle.setText(i18n("ai.title"));
                headerSubtitle.setText("");
            }
            updateEmptyState();
        }

        refreshSessionList();
        persistStore();
    }

    // ---- View switching ----

    private void showChatView() {
        chatView.setVisible(true);
        chatView.setManaged(true);
        state.set(State.fromTitle(i18n("ai.title")));
        refreshModelSelector();
        rebuildChatSidebar();
        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            updateHeader(current);
        } else {
            headerTitle.setText(i18n("ai.title"));
            headerSubtitle.setText("");
        }
        updateApprovalBadge();
        approvalBadge.setVisible(true);
        approvalBadge.setManaged(true);
    }


    @Override
    public boolean back() {
        return true;
    }


    // ---- Chat view ----

    private void buildChatView() {
        chatView.setSpacing(10);
        chatView.setPadding(new Insets(10)); // native card-list rhythm (VS §1.4)

        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().addAll("edge-to-edge", "ai-messages-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        FXUtils.smoothScrolling(scrollPane);
        // Stick-to-bottom: a wheel-up means the user is reading history, so stop auto-pinning;
        // wheeling back down to the bottom resumes it. Driven by user wheel events only (not
        // content-growth vvalue drift), so streaming never fights the user's scroll.
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() > 0) {
                stickToBottom = false;
            } else if (e.getDeltaY() < 0 && scrollPane.getVvalue() >= 0.97) {
                stickToBottom = true;
            }
        });

        messageList.setPadding(new Insets(16, 16, 24, 16));
        messageList.getStyleClass().add("ai-message-list");

        buildEmptyState();

        // The @/ autocomplete popup floats as an overlay at the bottom of the message
        // area, so it pops out separately above the input instead of taking layout space
        // in the composer (which previously lifted the whole input bar and shifted the
        // think / attach / send buttons toward the middle).
        autocompletePopup = buildAutocompletePopup();
        StackPane messagesArea = new StackPane(scrollPane, emptyState, autocompletePopup);
        StackPane.setAlignment(autocompletePopup, Pos.BOTTOM_LEFT);
        StackPane.setMargin(autocompletePopup, new Insets(0, 0, 6, 12));
        messagesArea.getStyleClass().add("ai-messages-area");
        VBox.setVgrow(messagesArea, Priority.ALWAYS);
        // Let the scrollable message area absorb all vertical shrink so the header and
        // composer keep their size when the window is small.
        messagesArea.setMinHeight(0);

        // Drag-and-drop file support on the chat area
        messagesArea.setOnDragOver(this::handleDragOver);
        messagesArea.setOnDragDropped(this::handleDragDropped);

        statusLabel.setVisible(false);
        // Don't reserve a layout slot while hidden — otherwise its padded height sits as an invisible
        // band between the conversation and the composer (it stays managed even when invisible).
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        statusLabel.getStyleClass().add("ai-typing-indicator");

        toolActivityLabel.getStyleClass().add("ai-caption"); // rule lands with the B7 css rewrite
        toolActivityLabel.setPadding(new Insets(4, 12, 4, 12)); // old .ai-tool-activity padding, moved to code
        toolActivityLabel.setVisible(false);
        toolActivityLabel.setManaged(false);

        todoCardContainer.getStyleClass().add("ai-todo-container");
        todoCardContainer.setVisible(false);
        todoCardContainer.setManaged(false);

        VBox conversationCard = new VBox(toolActivityLabel, todoCardContainer, messagesArea, statusLabel, buildComposer());
        conversationCard.getStyleClass().addAll("card-no-padding", "ai-conversation-card");
        VBox.setVgrow(conversationCard, Priority.ALWAYS);

        Node headerNode = buildHeaderNode();
        headerNode.getStyleClass().add("card-no-padding");

        chatView.getChildren().setAll(headerNode, conversationCard);
        chatView.getStyleClass().add("ai-chat-view");

        updateEmptyState();
    }

    private void buildEmptyState() {
        SVGContainer aiIcon = SVG.AI.createIcon(48);
        aiIcon.setOpacity(0.3);

        Label emptyTitle = new Label("有什么可以帮忙？"); // TODO(i18n)
        emptyTitle.getStyleClass().add("title-label");

        Label emptyText = new Label(i18n("ai.input_placeholder"));
        emptyText.getStyleClass().add("subtitle-label");

        javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane();
        chips.setHgap(8);
        chips.setVgap(8);
        chips.setAlignment(Pos.CENTER);
        chips.setMaxWidth(520);
        chips.setPrefWrapLength(520);

        String[] suggestions = {
                i18n("ai.suggestion.crash"),
                i18n("ai.suggestion.logs"),
                i18n("ai.suggestion.optimize"),
                i18n("ai.suggestion.mods"),
                i18n("ai.suggestion.help"),
                i18n("ai.suggestion.setup"),
                i18n("ai.suggestion.performance"),
                i18n("ai.suggestion.config")
        };

        for (String suggestion : suggestions) {
            JFXButton chip = new JFXButton(suggestion);
            chip.getStyleClass().add("jfx-button-border");
            chip.setOnAction(e -> {
                inputField.setText(suggestion);
                sendMessage();
            });
            chips.getChildren().add(chip);
        }

        Label suggestionsLabel = new Label(i18n("ai.empty_suggestions"));
        suggestionsLabel.getStyleClass().add("ai-suggestions-label");

        suggestionsBox = new VBox(8, suggestionsLabel, chips);
        suggestionsBox.setAlignment(Pos.CENTER);

        // A14: with no usable model service configured, the suggestion chips would all dead-end —
        // show an actionable hint + CTA into the settings page instead (mutually exclusive with
        // the chips; see updateEmptyState).
        HintPane noProviderHint = new HintPane(org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO);
        noProviderHint.setText("尚未配置模型服务，完成配置后即可开始对话。"); // TODO(i18n)
        JFXButton configureBtn = FXUtils.newRaisedButton("配置模型服务"); // TODO(i18n)
        configureBtn.setOnAction(e -> openSettingsPage());
        noProviderBox = new VBox(8, noProviderHint, configureBtn);
        noProviderBox.setAlignment(Pos.CENTER);
        noProviderBox.setMaxWidth(420);
        noProviderBox.setVisible(false);
        noProviderBox.setManaged(false);

        emptyState.getChildren().setAll(aiIcon, emptyTitle, emptyText, suggestionsBox, noProviderBox);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(40));
    }

    /// Same criterion {@link #setupModelSelector()} uses to decide whether it has anything to
    /// offer (an ENABLED profile with at least one cached model) — deliberately NOT
    /// `findSelectedProfile() != null`, so the empty state and the model selector can never
    /// disagree about whether the launcher is usable (C-16).
    private boolean hasConfiguredModel() {
        for (AiProviderProfile p : aiSettings.getProfiles()) {
            if (p.isEnabled() && !p.getCachedModels().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void updateEmptyState() {
        boolean hasMessages = !messageList.getChildren().isEmpty();
        emptyState.setVisible(!hasMessages);
        emptyState.setManaged(!hasMessages);
        if (suggestionsBox != null && noProviderBox != null) {
            boolean configured = hasConfiguredModel();
            suggestionsBox.setVisible(configured);
            suggestionsBox.setManaged(configured);
            noProviderBox.setVisible(!configured);
            noProviderBox.setManaged(!configured);
        }
    }

    // ---- Header ----

    private Node buildHeaderNode() {
        headerTitle.getStyleClass().add("ai-header-title"); // 15px bold lives in the CSS rule now
        headerSubtitle.getStyleClass().add("ai-header-subtitle");
        approvalBadge.getStyleClass().add("ai-approval-badge");
        approvalBadge.setVisible(false);
        approvalBadge.setManaged(false);
        planBadge.getStyleClass().addAll("ai-approval-badge", "ai-plan-mode-badge");
        planBadge.setVisible(false);
        planBadge.setManaged(false);

        HBox subtitleRow = new HBox(6, headerSubtitle, approvalBadge, planBadge);
        subtitleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2, headerTitle, subtitleRow);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        // Model selector — shows alias only; full "Provider / Model" in dropdown
        // Size to the model name within sensible bounds instead of a hard 190px cap.
        modelSelector.setMinWidth(120);
        modelSelector.setMaxWidth(280);
        modelSelector.getStyleClass().add("ai-header-selector");
        setupModelSelector();

        JFXButton searchBtn = FXUtils.newToggleButton4(SVG.SEARCH, 16);
        FXUtils.installFastTooltip(searchBtn, "搜索会话"); // TODO(i18n)
        searchBtn.setOnAction(e -> openSearchDialog());

        JFXButton chatSettingsBtn = FXUtils.newToggleButton4(SVG.TUNE, 16);
        FXUtils.installFastTooltip(chatSettingsBtn, "聊天设置"); // TODO(i18n)
        chatSettingsBtn.setOnAction(e -> showChatSettingsDrawer());

        HBox toolbarControls = new HBox(6, modelSelector, searchBtn, chatSettingsBtn);
        toolbarControls.setAlignment(Pos.CENTER_RIGHT);

        StackPane headerAvatar = new StackPane(SVG.AI.createIcon(18));
        headerAvatar.getStyleClass().add("ai-header-avatar");
        headerAvatar.setMinSize(34, 34);
        headerAvatar.setMaxSize(34, 34);

        HBox titleArea = new HBox(10, headerAvatar, titleBox);
        titleArea.setAlignment(Pos.CENTER_LEFT);

        HBox headerBox = new HBox(titleArea, toolbarControls);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(8, 16, 8, 16)); // 8-point grid (VS §1.4)
        headerBox.getStyleClass().add("ai-main-header");
        HBox.setHgrow(titleArea, Priority.ALWAYS);
        // Never let the header be squeezed away when the window gets short — only the
        // (scrollable) message area should yield vertical space.
        headerBox.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        return headerBox;
    }

    private void setupModelSelector() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (AiProviderProfile p : aiSettings.getProfiles()) {
            if (!p.isEnabled()) continue;
            for (String m : p.getCachedModels()) {
                labels.add(p.getDisplayName() + " / " + p.getModelAliasOrId(m));
            }
        }
        if (labels.isEmpty()) labels.add(i18n("ai.model.select"));
        // Latch the (installed-once) value listener out while this refresh writes items/value:
        // the setValue below fires listeners exactly like a user pick, and before P2 every
        // refresh both re-triggered clearAgentCache()+persistAiSettings() AND re-registered a
        // brand-new listener, so the side effects multiplied with every refresh.
        modelSelectorUpdating = true;
        try {
            modelSelector.setItems(labels);
            // Collapsed button shows only the model name; the dropdown shows the provider
            // as a second line below each model so the full "Provider / Model" is only
            // revealed when the menu is pulled out.
            modelSelector.setNullSafeConverter(AIMainPage::modelPartOf);
            modelSelector.setDescriptionConverter(AIMainPage::providerPartOf);

            AiProviderProfile active = aiSettings.findSelectedProfile();
            if (active != null && active.getDefaultModelId() != null) {
                String alias = active.getModelAliasOrId(active.getDefaultModelId());
                modelSelector.setValue(active.getDisplayName() + " / " + alias);
            } else if (!labels.isEmpty()) {
                modelSelector.setValue(labels.get(0));
            }
        } finally {
            modelSelectorUpdating = false;
        }
    }

    /// Installs the model-switch listener on {@link #modelSelector} — called exactly ONCE from
    /// the constructor. It used to live inside {@link #setupModelSelector()}, which runs on every
    /// profile refresh / view switch, so listeners accumulated without bound (P2).
    private void installModelSelectorListener() {
        modelSelector.valueProperty().addListener((obs, old, val) -> {
            if (modelSelectorUpdating) return; // programmatic refresh, not a user selection
            if (val == null) return;
            String[] parts = val.split(" / ", 2);
            if (parts.length != 2) return;
            for (AiProviderProfile p : aiSettings.getProfiles()) {
                if (!p.isEnabled() || !p.getDisplayName().equals(parts[0])) continue;
                for (String m : p.getCachedModels()) {
                    if (p.getModelAliasOrId(m).equals(parts[1]) || m.equals(parts[1])) {
                        aiSettings.setSelectedProfileId(p.getId());
                        p.setDefaultModelId(m);
                        clearAgentCache();
                        persistAiSettings();
                        // After deleting the last session there is no current session — the
                        // model switch above still applies; just skip the header refresh (an
                        // unguarded call NPE'd on the FX thread here).
                        AiSession headerSession = sessionStore.getCurrentSession();
                        if (headerSession != null) {
                            updateHeader(headerSession);
                        }
                        return;
                    }
                }
            }
        });
    }

    /// Called when provider/model list changes to refresh the header selectors.
    private void refreshModelSelector() {
        setupModelSelector();
    }

    /// Returns the model portion (after " / ") of a "Provider / Model" label,
    /// or the whole label when it has no separator (e.g. the empty-state hint).
    private static String modelPartOf(String label) {
        int idx = label.indexOf(" / ");
        return idx >= 0 ? label.substring(idx + 3) : label;
    }

    /// Returns the provider portion (before " / ") of a "Provider / Model" label,
    /// or an empty string when it has no separator (so no subtitle is shown).
    private static String providerPartOf(String label) {
        int idx = label.indexOf(" / ");
        return idx >= 0 ? label.substring(0, idx) : "";
    }

    /// Human-readable display name for a raw reasoning-effort level id (A12). Display layer
    /// ONLY — the stored value stays the raw English id, so serialisation is untouched. Shared
    /// with AISettingsPage's "默认推理强度" row (package-private on purpose).
    static String reasoningEffortLabel(String level) {
        if (level == null) return "";
        return switch (level) { // TODO(i18n): ai.reasoning.effort.* keys, stage C
            case "", "none" -> "不思考";
            case "low" -> "快速";
            case "medium" -> "平衡";
            case "high" -> "深入";
            case "xhigh" -> "更深入";
            case "max" -> "极限";
            default -> level;
        };
    }



    private void updateHeader(AiSession session) {
        String title = session.getTitle();
        headerTitle.setText((title != null && !title.isEmpty())
                ? title : i18n("ai.session.untitled"));

        AiProviderProfile active = aiSettings.findSelectedProfile();
        if (active != null) {
            StringBuilder sb = new StringBuilder();
            String displayName = active.getDisplayName();
            if (displayName != null && !displayName.isEmpty()) {
                sb.append(displayName);
            } else {
                sb.append(active.getProtocolFamily());
            }
            String defaultModel = active.getDefaultModelId();
            if (defaultModel != null && !defaultModel.isEmpty()) {
                sb.append(" \u00b7 ").append(defaultModel);
            }
            headerSubtitle.setText(sb.toString());
        } else {
            headerSubtitle.setText("");
        }

        updateApprovalBadge();
    }

    /// Refreshes the approval badge. There is only one approval mode now ({@link AiApprovalMode#AUTO}
    /// — see its own doc for the SAFE/ASK/YOLO merge this replaced), so this no longer varies by
    /// mode; it just shows the fixed "Auto" label.
    private void updateApprovalBadge() {
        approvalBadge.setText(i18n("ai.settings.approval_badge_auto"));
        approvalBadge.setVisible(true);
        approvalBadge.setManaged(true);
    }

    // ---- Session management ----

    private void createSession() {
        // If the current session is already brand-new and empty, don't stack another empty one
        // (repeated clicks used to pile up identical "New Chat" rows) — just focus the composer.
        AiSession existing = sessionStore.getCurrentSession();
        if (existing != null && existing.getMessages().isEmpty() && !isStreaming()) {
            showChatView();
            inputField.requestFocus();
            return;
        }
        sessionStore.createSession();
        persistStore();
        resetConversationView(); // discard any view state left over from the previous session
        updateEmptyState();
        refreshSessionList();
        showChatView();

        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            // The composer must now hold the NEW session's (empty) draft; the text the user had
            // typed stays stashed under the session it was written in (bug 7.9: it used to keep
            // being attributed to the OLD session while the user kept typing in the new one).
            restoreDraftFor(current.getId());
            updateHeader(current);
        }
        inputField.requestFocus();
    }

    /// Clears the current conversation's messages in-place (the `/clear` command).
    private void clearCurrentConversation() {
        AiSession current = sessionStore.getCurrentSession();
        if (current == null) return;
        current.clear();
        evictAgent(current.getId());
        resetConversationView(); // discard any view state left over from the cleared conversation
        updateToolActivityVisibility();
        updateEmptyState();
        persistStore();
    }

    /// Resets every piece of per-conversation view state in one place (extracted from four call
    /// sites that each hand-maintained the same 8-field block and drifted — bug 8.4). Call-site
    /// specific extras (cancelActiveAsk, stickToBottom, updateEmptyState timing, …) deliberately
    /// stay at the call sites.
    private void resetConversationView() {
        hideTodoCard();
        messageList.getChildren().clear();
        toolActivityLabel.setText("");
        streamingBubble = null;
        reasoningLiveCard = null;
        pendingToolCards.clear();
        activeToolCard = null;
        endToolCallRun(); // discard any tool-card grouping state along with the cards themselves
    }

    private boolean isPlanMode() {
        return planMode;
    }

    /// Shows/hides the plan-mode header badge to match {@link #planMode}.
    private void updatePlanBadge() {
        planBadge.setVisible(planMode);
        planBadge.setManaged(planMode);
    }

    /// Merged domain facades whose actions span MULTIPLE permission levels (e.g. `instance`'s
    /// `action=list` is READ_ONLY but `action=delete` is DANGEROUS_WRITE) — deliberately EXCLUDED
    /// from {@link #applyPlanGating}'s whole-tool disable below. {@link ToolRegistry#getPermission}
    /// (the no-arg resolution used by that loop) only ever reports these tools' WORST-CASE
    /// permission ({@code ToolSpec#getMaxPermission()}), since none of them override the no-arg
    /// {@code ToolSpec#getPermission()} the loop actually consults. Disabling them wholesale would
    /// therefore also take out every one of their READ_ONLY actions (`instance(list)`,
    /// `search(mods)`, `nbt(read)`, `job(list)`, `account(list)`, `game(list)`, ...) — defeating
    /// Plan Mode's own "stay read-only, keep investigating" purpose. Instead, the real per-action
    /// permission is enforced at the actual call site: {@code AiExecutionPolicy.check}'s
    /// {@code planMode} parameter (threaded through by {@code ChatAgentFactory.build} /
    /// {@code LangChain4jToolAdapter.execute}, which already resolves permission per-action via
    /// {@code ToolSpec#getPermission(Map)}) BLOCKs any CONTROLLED_WRITE/DANGEROUS_WRITE call from
    /// these tools while Plan Mode is active and lets READ_ONLY actions straight through.
    private static final java.util.Set<String> PLAN_GATING_PER_ACTION_TOOLS = java.util.Set.of(
            "instance", "game", "search", "account", "nbt", "job");

    /// Before a plan-mode response, disables every SINGLE-PERMISSION write-capable tool
    /// (CONTROLLED_WRITE / DANGEROUS_WRITE, e.g. `write`/`edit`/`shell`) except `ask`, so the agent
    /// can only investigate and propose. The merged domain facades in
    /// {@link #PLAN_GATING_PER_ACTION_TOOLS} are deliberately left enabled — see that field's doc —
    /// their write actions are instead blocked per-call by {@code AiExecutionPolicy.check}. The
    /// disabled names are remembered and restored by {@link #restorePlanGating()}.
    private void applyPlanGating() {
        planDisabledTools.clear();
        if (!planMode) return;
        for (org.jackhuang.hmcl.ai.tools.Tool t : toolRegistry.listAll()) {
            String name = t.getName();
            if ("ask".equals(name) || PLAN_GATING_PER_ACTION_TOOLS.contains(name)) continue;
            org.jackhuang.hmcl.ai.tools.ToolPermission p = toolRegistry.getPermission(name);
            if ((p == org.jackhuang.hmcl.ai.tools.ToolPermission.CONTROLLED_WRITE
                    || p == org.jackhuang.hmcl.ai.tools.ToolPermission.DANGEROUS_WRITE)
                    && !toolRegistry.isDisabled(name)) {
                toolRegistry.disable(name);
                planDisabledTools.add(name);
            }
        }
    }

    /// Re-enables whatever {@link #applyPlanGating()} disabled. Safe to call repeatedly.
    private void restorePlanGating() {
        for (String name : planDisabledTools) {
            toolRegistry.enable(name);
        }
        planDisabledTools.clear();
    }

    /// Compresses the current conversation into a continuation-style summary (off the FX
    /// thread), then rebuilds the view from the now-summarised session. Used by /compact.
    private void compactConversation() {
        AiSession current = sessionStore.getCurrentSession();
        if (current == null) return;
        if (current.getMessages().isEmpty()) {
            addSystemMessage("当前对话为空，无需压缩。");
            return;
        }
        ChatAgent agent = getOrCreateChatAgent();
        if (agent == null) {
            addSystemMessage(i18n("ai.error.no_endpoint"));
            return;
        }
        final AiSession target = current;
        setStatus("正在压缩上下文…");
        agent.compact().whenComplete((summary, err) -> Platform.runLater(() -> {
            setStatus(null);
            if (err != null) {
                Throwable cause = err;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                addSystemMessage("压缩失败：" + (cause.getMessage() != null
                        ? cause.getMessage() : cause.getClass().getSimpleName()));
                return;
            }
            // The agent has already cleared the session and stored the summary message.
            evictAgent(target.getId());
            if (sessionStore.getCurrentSession() == target) {
                loadSessionMessages(target);
            }
            persistStore();
        }));
    }

    /// Builds a one-line summary of the active provider/model for the `/model` command.
    private String currentModelSummary() {
        AiProviderProfile active = aiSettings.findSelectedProfile();
        if (active == null) {
            return i18n("ai.command.model_none");
        }
        String model = active.getDefaultModelId();
        return i18n("ai.command.model_current",
                active.getDisplayName(),
                model != null && !model.isEmpty() ? model : "-");
    }

    // ---- Composer ----

    private HBox buildComposer() {
        inputField.setPromptText(i18n("ai.input_placeholder"));
        inputField.getStyleClass().add("ai-input-field");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setWrapText(true);
        // Auto-grow between 1 line (34px) and ~8 lines (180px); beyond that it scrolls inside.
        // Height is derived from an offscreen Text node mirroring the TextArea's wrapping width —
        // the previous approach counted only literal '\n' characters, so a single long paragraph
        // with no manual newline stayed pinned at 1 line height and could only be read by
        // scrolling INSIDE the box ("一行文字只靠滚轮会丢失阅读的连续性"); WRAPPED lines never
        // grew the box. A Text node's layout bounds reflect real wrapping without needing to be
        // attached to the scene graph. Since this Text node is never added to the scene graph,
        // CSS (including inline setStyle) never applies to it — so its font is read directly off
        // inputField (which IS in the live scene and has real, CSS-resolved font info) each time
        // the height is recomputed, instead of guessing a literal font-size that could drift from
        // .ai-input-field's actual CSS (notably for a CJK-capable custom font-family).
        inputField.setMinHeight(34);
        inputField.setPrefHeight(34);
        inputField.setMaxHeight(180);
        javafx.scene.text.Text inputHeightMeasurer = new javafx.scene.text.Text();
        // The wrapping width subtracts the TextArea's REAL insets (CSS border + padding) instead
        // of a -24 magic number, so a border/padding change in .ai-input-field can never desync
        // the measurement (bug-hunt 4.2). Insets resolve only after the first CSS pass, hence the
        // insetsProperty dependency below.
        inputHeightMeasurer.wrappingWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> {
                    Insets insets = inputField.getInsets();
                    return Math.max(50, inputField.getWidth() - insets.getLeft() - insets.getRight());
                }, inputField.widthProperty(), inputField.insetsProperty()));
        Runnable recomputeInputHeight = () -> {
            inputHeightMeasurer.setFont(inputField.getFont()); // real, CSS-resolved font — not a guess
            String t = inputField.getText();
            inputHeightMeasurer.setText(t == null || t.isEmpty() ? " " : t);
            double measured = inputHeightMeasurer.getLayoutBounds().getHeight() + 16;
            inputField.setPrefHeight(Math.min(180, Math.max(34, measured)));
        };
        inputField.textProperty().addListener((o, ov, nv) -> recomputeInputHeight.run());
        inputField.widthProperty().addListener((o, ov, nv) -> recomputeInputHeight.run());
        inputField.insetsProperty().addListener((o, ov, nv) -> recomputeInputHeight.run());
        // Track IME composition so the Enter that commits pinyin never sends a half-typed message.
        inputField.addEventFilter(javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                e -> imeComposing = e.getComposed() != null && !e.getComposed().isEmpty());

        // Autocomplete + Enter/Shift+Enter key handling
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleInputKeyPress);

        sendBtn = FXUtils.newRaisedButton(i18n("ai.send"));
        // While a response is streaming the button becomes a Stop button.
        sendBtn.setOnAction(e -> {
            if (isStreaming()) {
                // Stop only the response of the session on screen; if another session is the one
                // streaming, don't kill it from here — tell the user where to stop it.
                if (isStreamingCurrentSession()) stopResponse();
                else Controllers.showToast("另一个会话正在生成回复，切回那个会话可以停止它");
                return;
            }
            sendMessage();
        });
        sendBtn.setDefaultButton(true);

        JFXButton attachBtn = FXUtils.newToggleButton4(SVG.FILE_OPEN, 16);
        attachBtn.setOnAction(e -> handleFileUpload());
        FXUtils.installFastTooltip(attachBtn, i18n("ai.attach"));

        // Attachment chip area (shown above the input while files are attached). One chip per
        // file, each with its own remove button — attachments live in `attachedFiles`, NOT in
        // the input text (stuffing file content into the composer destroyed drafts and capped
        // everything at one file).
        fileChipArea = new VBox(4);
        fileChipArea.setVisible(false);
        fileChipArea.setManaged(false);
        fileChipArea.getStyleClass().add("ai-file-chip-area");

        fileChipFlow = new javafx.scene.layout.FlowPane(6, 4);
        fileChipArea.getChildren().add(fileChipFlow);

        // The @/ autocomplete popup lives as an overlay in the message area (created in
        // buildChatView); it deliberately takes no layout space here so the input bar
        // never grows and the buttons stay put.
        askPanel.getStyleClass().add("ai-ask-panel");
        askPanel.setVisible(false);
        askPanel.setManaged(false);
        // Background-tasks pull-up: a thin header above the composer (后台任务 + running count) that
        // unfurls UPWARD into a compact per-category list when clicked. Only shown when busy.
        jobsListContainer = new VBox(2);
        jobsListContainer.getStyleClass().add("ai-jobs-list");
        FXUtils.setOverflowHidden(jobsListContainer);
        setJobsListHeightLimit(0);
        jobsListContainer.setManaged(false);
        jobsListContainer.setVisible(false);

        jobsToggleIcon = SVG.KEYBOARD_ARROW_UP.createIcon(16);
        jobsToggleIcon.setMouseTransparent(true);
        Label jobsTitle = new Label("后台任务");
        jobsTitle.getStyleClass().add("ai-jobs-title");
        Region jobsSpacer = new Region();
        HBox.setHgrow(jobsSpacer, Priority.ALWAYS);
        jobsCountLabel = new Label();
        jobsCountLabel.getStyleClass().add("ai-jobs-count");
        HBox jobsHeader = new HBox(6, jobsToggleIcon, jobsTitle, jobsSpacer, jobsCountLabel);
        jobsHeader.getStyleClass().add("ai-jobs-header");
        jobsHeader.setAlignment(Pos.CENTER_LEFT);
        FXUtils.onClicked(jobsHeader, this::toggleJobsPane);

        jobsPane = new VBox(2, jobsListContainer, jobsHeader);  // list above header → unfurls upward
        jobsPane.getStyleClass().add("ai-jobs-pane");
        jobsPane.setVisible(false);
        jobsPane.setManaged(false);
        org.jackhuang.hmcl.ai.tools.AiJobManager.getInstance()
                .addChangeListener(() -> Platform.runLater(this::refreshJobsPane));
        // Auto-continue: when a background job of the current session finishes while idle, feed its
        // result back so the AI carries on (the chosen "auto-continue" behaviour).
        org.jackhuang.hmcl.ai.tools.AiJobManager.getInstance()
                .addCompletionListener(job -> Platform.runLater(() -> onBackgroundJobComplete(job)));
        refreshJobsPane();

        VBox composerInner = new VBox(4, jobsPane, askPanel, fileChipArea, inputField);
        composerInner.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(inputField, Priority.NEVER);

        HBox inputBar = new HBox(8);
        // Bottom-align so the think / attach / send buttons stay level with the input
        // even when the file-chip row appears above it.
        inputBar.setAlignment(Pos.BOTTOM_LEFT);
        inputBar.setPadding(new Insets(8, 16, 12, 16));
        inputBar.getStyleClass().add("ai-input-bar");
        // The composer must never be squeezed away in a short window.
        inputBar.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Thinking level popup button — circular, left of the input
        thinkBtn = FXUtils.newToggleButton4(SVG.LIGHTBULB, 16);

        String currentThink = aiSettings.getReasoningEffort().isEmpty() ? "none" : aiSettings.getReasoningEffort();
        FXUtils.installFastTooltip(thinkBtn, "思考强度：" + reasoningEffortLabel(currentThink)); // TODO(i18n)
        thinkBtn.setOnAction(e -> {
            // Toggle: if the popup is already open, close it instead of stacking another.
            if (thinkingPopup != null && thinkingPopup.isShowing()) {
                thinkingPopup.hide();
                return;
            }
            String[] levels = {"none", "low", "medium", "high", "xhigh", "max"};
            String cur = aiSettings.getReasoningEffort().isEmpty() ? "none" : aiSettings.getReasoningEffort();
            // Native PopupMenu + IconedMenuItem (A12/C-02): rows show the human-readable effort
            // name, the ACTIVE level carries a check icon, and each row's tooltip reveals the raw
            // level id (e.g. "reasoning_effort: high") for troubleshooting.
            PopupMenu menu = new PopupMenu();
            JFXPopup popup = new JFXPopup(menu);
            for (String level : levels) {
                menu.getContent().add(new IconedMenuItem(level.equals(cur) ? SVG.CHECK : null,
                        reasoningEffortLabel(level), () -> {
                    aiSettings.reasoningEffortProperty().set(level);
                    // AiSettings has no auto-save — without this the picked level silently
                    // reverted on restart (P6/C-17).
                    persistAiSettings();
                    FXUtils.installFastTooltip(thinkBtn, "思考强度：" + reasoningEffortLabel(level)); // TODO(i18n)
                }, popup).addTooltip("reasoning_effort: " + level));
            }
            thinkingPopup = popup;
            JFXPopup.PopupVPosition vPosition = FXUtils.determineOptimalPopupPosition(thinkBtn, thinkingPopup);
            thinkingPopup.show(thinkBtn, vPosition, JFXPopup.PopupHPosition.LEFT,
                    0,
                    vPosition == JFXPopup.PopupVPosition.TOP ? thinkBtn.getHeight() : -thinkBtn.getHeight(),
                    true);
        });

        inputBar.getChildren().setAll(thinkBtn, composerInner, attachBtn, sendBtn);
        HBox.setHgrow(composerInner, Priority.ALWAYS);
        return inputBar;
    }

    // ---- Search dialog ----

    /// Opens the cross-session search dialog (native DialogPane, blueprint B5 / CP §1).
    /// The old hand-built full-screen overlay is gone; search / navigate / select logic
    /// lives unchanged in [AiSearchDialog], and choosing a result funnels back into
    /// [#switchToSessionAndScroll]. A fresh dialog instance per open means state
    /// (query text, results, selection) always starts clean.
    private void openSearchDialog() {
        if (searchDialogShowing) return; // double-open guard
        searchDialogShowing = true;
        Controllers.dialog(new AiSearchDialog(
                sessionStore::listSessions,
                this::switchToSessionAndScroll,
                () -> searchDialogShowing = false));
    }

    /// Switches to the given session id and scrolls to a message containing
    /// the given text if possible.
    private void switchToSessionAndScroll(String sessionId, String matchingText) {
        sessionStore.setCurrentSessionId(sessionId);
        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            // This path calls loadSessionMessages directly (no debounce), so it must do the same
            // draft hand-over loadSession does — it used to skip it entirely (bug 7.9).
            restoreDraftFor(current.getId());
            loadSessionMessages(current);
            updateHeader(current);
        }
        refreshSessionList();
        showChatView();

        // Attempt to scroll to the matching message
        Platform.runLater(() -> {
            if (matchingText == null || matchingText.isEmpty()) return;
            ObservableList<Node> children = messageList.getChildren();
            for (int i = 0; i < children.size(); i++) {
                Node node = children.get(i);
                // Bubble wrappers/card rows are HBoxes, but a bubble with an action bar lives in
                // a VBox `.ai-msg-block` since BF A2 — match any pane so those still highlight.
                if (node instanceof javafx.scene.layout.Pane wrapper) {
                    if (nodeContainsText(wrapper, matchingText)) {
                        // Scroll so this node is visible
                        double targetV = (double) i / Math.max(1, children.size());
                        scrollPane.setVvalue(targetV);
                        // Highlight briefly
                        wrapper.getStyleClass().add("ai-search-match-highlight");
                        Timeline clearHighlight = new Timeline(new KeyFrame(Duration.seconds(2),
                                e2 -> wrapper.getStyleClass().remove("ai-search-match-highlight")));
                        clearHighlight.play();
                        break;
                    }
                }
            }
        });
        persistStore();
    }

    /// Recursively checks whether the given node tree contains the search text.
    private static boolean nodeContainsText(Node node, String text) {
        if (node instanceof Label label && label.getText() != null) {
            return label.getText().toLowerCase().contains(text.toLowerCase());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (nodeContainsText(child, text)) return true;
            }
        }
        return false;
    }

    // ---- File upload ----

    /// Opens a FileChooser for crash log files, reads the selected file content,
    /// and displays a file chip above the input area.
    private void handleFileUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(i18n("ai.attach.select"));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(i18n("ai.attach.crash_logs"), "*.txt", "*.log", "*.crash"),
                new FileChooser.ExtensionFilter(i18n("ai.attach.all_files"), "*.*")
        );

        java.io.File file = fileChooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        attachFile(file.toPath());
    }

    /// Adds a file to the pending attachments (content is read at SEND time, never dumped into
    /// the composer — the old flow overwrote whatever the user was typing).
    private void attachFile(Path path) {
        if (path == null || attachedFiles.contains(path)) {
            return;
        }
        attachedFiles.add(path);
        rebuildFileChips();
    }

    private void rebuildFileChips() {
        if (fileChipFlow == null || fileChipArea == null) {
            return;
        }
        fileChipFlow.getChildren().clear();
        for (Path p : attachedFiles) {
            Label name = new Label(p.getFileName().toString());
            name.getStyleClass().add("ai-file-chip");
            JFXButton remove = new JFXButton();
            remove.setGraphic(SVG.CLOSE.createIcon(12));
            remove.getStyleClass().add("toggle-icon-tiny"); // native 15px icon button
            remove.setOnAction(e -> {
                attachedFiles.remove(p); // removes ONLY this file; the draft text is untouched
                rebuildFileChips();
            });
            HBox chip = new HBox(4, name, remove);
            chip.setAlignment(Pos.CENTER_LEFT);
            fileChipFlow.getChildren().add(chip);
        }
        boolean any = !attachedFiles.isEmpty();
        fileChipArea.setVisible(any);
        fileChipArea.setManaged(any);
    }

    /// Maximum characters of one attachment appended to the outbound prompt.
    private static final int ATTACHMENT_MAX_CHARS = 200_000;

    /// Reads only the first {@link #ATTACHMENT_MAX_CHARS} characters of a file (streaming; stops
    /// reading the moment the cap is hit) — `Files.readString` used to pull a multi-hundred-MB
    /// log fully into memory just to throw most of it away. Package-private for the unit test.
    static String readAttachmentHead(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                int remain = ATTACHMENT_MAX_CHARS - sb.length();
                if (n >= remain) {
                    sb.append(buf, 0, remain);
                    sb.append("\n…（文件过大，已截断）");
                    break;
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /// Appends the given attachments' (truncated) content to the outbound prompt text. Runs on a
    /// background thread (see sendMessage) — it must NOT touch page state, hence the explicit
    /// file-list parameter. Unreadable files are reported inline instead of failing the send.
    static String buildAttachmentText(String text, List<Path> files) {
        if (files.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (Path p : files) {
            sb.append("\n\n[附件: ").append(p.getFileName()).append("]\n");
            try {
                sb.append(readAttachmentHead(p));
            } catch (IOException ex) {
                sb.append("（读取失败：").append(ex.getMessage()).append("）");
            }
        }
        return sb.toString();
    }

    /// Clears all pending attachments (the composer draft is never touched — the old
    /// implementation wiped the whole input box whenever it contained "[File:").
    private void clearFileChip() {
        attachedFiles.clear();
        rebuildFileChips();
    }

    /// Text-like file extensions accepted by drag-and-drop attachment (single whitelist shared by
    /// {@link #handleDragOver} and {@link #handleDragDropped}, which used to carry two separate,
    /// narrower inline lists that had already drifted apart — bug 7.16). The FileChooser button
    /// deliberately keeps its `*.*` fallback filter: a file the user EXPLICITLY picked is trusted,
    /// and the read side truncates oversized content anyway (see readAttachmentHead).
    private static final java.util.Set<String> ATTACHABLE_EXTENSIONS = java.util.Set.of(
            "txt", "log", "crash", "json", "toml", "cfg", "properties", "yml", "yaml", "md", "csv");

    /// Whether a dragged file's extension is on {@link #ATTACHABLE_EXTENSIONS}.
    private static boolean isAttachable(java.io.File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ATTACHABLE_EXTENSIONS.contains(
                name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /// Handles drag-over events on the chat area for file drag-and-drop.
    private void handleDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles() && db.getFiles().stream().anyMatch(AIMainPage::isAttachable)) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    /// Handles drag-dropped files on the chat area: every matching file becomes an attachment
    /// chip (multiple files supported; the composer draft is never touched).
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            for (java.io.File dropped : db.getFiles()) {
                if (isAttachable(dropped)) {
                    attachFile(dropped.toPath());
                    success = true;
                }
            }
            if (!success) {
                Controllers.showToast("仅支持文本类文件（.log/.txt/.json 等）"); // TODO(i18n)
            }
        }
        event.setDropCompleted(success);
        event.consume();
    }

    // ---- Autocomplete ----

    /// Builds the autocomplete popup that appears above the input field for
    /// slash commands and at-mentions.
    private VBox buildAutocompletePopup() {
        VBox popup = new VBox(2);
        popup.setMaxHeight(180);
        popup.getStyleClass().add("ai-autocomplete-popup");
        popup.setVisible(false);
        popup.setManaged(false);
        return popup;
    }

    /// Handles key presses in the input field for autocomplete triggers.
    private void handleInputKeyPress(KeyEvent event) {
        // Enter behaviour (only when the autocomplete popup is not capturing Enter):
        //   回车发送 on:  Enter=send, Shift+Enter=newline, Ctrl+Enter=send
        //   回车发送 off: Enter=newline, Ctrl+Enter=send
        // During an IME composition the Enter only commits the pinyin — never send.
        if (event.getCode() == KeyCode.ENTER && (autocompletePopup == null || !autocompletePopup.isVisible())) {
            if (imeComposing) {
                return; // commit the composition; the TextArea handles it
            }
            boolean ctrl = event.isControlDown() || event.isMetaDown();
            if (ctrl) { // Ctrl+Enter always sends
                sendMessage();
                event.consume();
                return;
            }
            if (event.isShiftDown()) {
                return; // Shift+Enter always inserts a newline (TextArea default)
            }
            if (aiSettings.isSendOnEnter()) {
                sendMessage();
                event.consume();
            }
            // 回车发送 off: fall through, plain Enter inserts a newline.
            return;
        }

        if (autocompletePopup == null) return;

        if (autocompletePopup.isVisible()) {
            if (event.getCode() == KeyCode.ESCAPE) {
                hideAutocomplete();
                event.consume();
                return;
            } else if (event.getCode() == KeyCode.DOWN) {
                navigateAutocomplete(1);
                event.consume();
                return;
            } else if (event.getCode() == KeyCode.UP) {
                navigateAutocomplete(-1);
                event.consume();
                return;
            } else if (event.getCode() == KeyCode.ENTER) {
                applyAutocompleteSelection();
                event.consume();
                return;
            } else if (event.getCode() == KeyCode.TAB) {
                applyAutocompleteSelection();
                event.consume();
                return;
            }
        }

        Platform.runLater(() -> {
            if (!chatSettings.showShortcutMenu) {
                hideAutocomplete();
                return;
            }

            String text = inputField.getText();
            if (text == null || text.isEmpty()) {
                hideAutocomplete();
                return;
            }

            int caretPos = inputField.getCaretPosition();
            String textBeforeCaret = text.substring(0, Math.min(caretPos, text.length()));

            int lastSlash = -1;
            int lastAt = -1;

            for (int i = textBeforeCaret.length() - 1; i >= 0; i--) {
                char c = textBeforeCaret.charAt(i);
                if (c == '/' && (i == 0 || textBeforeCaret.charAt(i - 1) == ' ' || textBeforeCaret.charAt(i - 1) == '\n')) {
                    lastSlash = i;
                    break;
                }
            }

            for (int i = textBeforeCaret.length() - 1; i >= 0; i--) {
                char c = textBeforeCaret.charAt(i);
                if (c == '@' && (i == 0 || textBeforeCaret.charAt(i - 1) == ' ' || textBeforeCaret.charAt(i - 1) == '\n')) {
                    lastAt = i;
                    break;
                }
            }

            if (lastSlash > lastAt) {
                handleSlashAutocomplete(textBeforeCaret.substring(lastSlash));
            } else if (lastAt > lastSlash) {
                String afterAt = textBeforeCaret.substring(lastAt + 1);
                handleAtAutocomplete(afterAt);
            } else {
                hideAutocomplete();
            }
        });
    }

    /// Slash commands offered in autocomplete and listed by /help, each with a
    /// one-line Chinese description. This single table drives both the popup and
    /// the /help output so they never drift apart.
    private static final java.util.LinkedHashMap<String, String> SLASH_COMMANDS = new java.util.LinkedHashMap<>();
    static {
        // HMCLAI can't reach HMCLCore's Logger directly; route its AI-model API log lines (request /
        // response / timing / token usage / retry / failure) into the application log here.
        org.jackhuang.hmcl.ai.util.AiLog.setSink((warn, msg) -> {
            if (warn) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(msg);
            } else {
                org.jackhuang.hmcl.util.logging.Logger.LOG.info(msg);
            }
        });

        SLASH_COMMANDS.put("/new", "新建一个会话");
        SLASH_COMMANDS.put("/clear", "清空当前对话上下文");
        SLASH_COMMANDS.put("/compact", "把当前对话压缩成摘要以节省 token");
        SLASH_COMMANDS.put("/sessions", "搜索并切换历史会话");
        SLASH_COMMANDS.put("/import", "从导出的 JSON 导入会话（不覆盖现有）");
        SLASH_COMMANDS.put("/skills", "列出已启用的技能");
        SLASH_COMMANDS.put("/plan", "切换计划模式（只读分析，批准前不改动）");
        SLASH_COMMANDS.put("/model", "显示当前模型");
        SLASH_COMMANDS.put("/crash", "分析最新的崩溃报告");
        SLASH_COMMANDS.put("/log", "读取并分析最新的游戏日志");
        SLASH_COMMANDS.put("/help", "显示命令帮助");
    }

    /// Builds the /help text from {@link #SLASH_COMMANDS}.
    private static String buildSlashHelpText() {
        StringBuilder sb = new StringBuilder("可用命令：");
        for (java.util.Map.Entry<String, String> e : SLASH_COMMANDS.entrySet()) {
            sb.append('\n').append(e.getKey()).append(" — ").append(e.getValue());
        }
        return sb.toString();
    }

    /// Filters and displays slash command suggestions.
    private void handleSlashAutocomplete(String prefix) {
        autocompleteItems.clear();
        String lowerPrefix = prefix.toLowerCase();
        for (String cmd : SLASH_COMMANDS.keySet()) {
            if (cmd.toLowerCase().startsWith(lowerPrefix)) {
                autocompleteItems.add(cmd);
            }
        }

        if (autocompleteItems.isEmpty()) {
            hideAutocomplete();
            return;
        }

        // First item pre-selected: Enter picks it right away (an unselected list silently
        // swallowed the Enter that was meant to run the visible command).
        autocompleteSelectedIndex = 0;
        refreshAutocompletePopup();
    }

    /// Filters and displays at-mention suggestions (file and session references).
    private void handleAtAutocomplete(String partial) {
        List<String> suggestions = new ArrayList<>();
        String lowerPartial = partial.toLowerCase();

        for (AiSession session : sessionStore.listSessions()) {
            String title = session.getTitle();
            if (title != null && title.toLowerCase().contains(lowerPartial)) {
                suggestions.add("@session:" + session.getId().substring(0, Math.min(8, session.getId().length())));
            }
        }

        autocompleteItems.clear();
        autocompleteItems.addAll(suggestions);

        if (autocompleteItems.isEmpty()) {
            hideAutocomplete();
            return;
        }

        autocompleteSelectedIndex = 0; // see handleSlashAutocomplete
        refreshAutocompletePopup();
    }

    /// Refreshes the autocomplete popup content with current filtered items.
    private void refreshAutocompletePopup() {
        if (autocompletePopup == null) return;
        autocompletePopup.getChildren().clear();

        for (int i = 0; i < autocompleteItems.size(); i++) {
            String item = autocompleteItems.get(i);
            int index = i;
            String display = SLASH_COMMANDS.containsKey(item)
                    ? item + "  —  " + SLASH_COMMANDS.get(item) : item;
            Label itemLabel = new Label(display);
            // Shared list-row family with the search dialog's result rows (B5 / CP §1);
            // padding comes from the .ai-list-row rule (author css beats a code setter).
            itemLabel.getStyleClass().add("ai-list-row");
            if (i == autocompleteSelectedIndex) {
                itemLabel.getStyleClass().add("ai-list-row-selected");
            }
            itemLabel.setMaxWidth(Double.MAX_VALUE);
            itemLabel.setOnMouseClicked(e -> {
                autocompleteSelectedIndex = index;
                applyAutocompleteSelection();
            });
            autocompletePopup.getChildren().add(itemLabel);
        }

        autocompletePopup.setVisible(true);
        autocompletePopup.setManaged(true);
    }

    /// Navigates the autocomplete selection by the given delta (wrapping 0..size-1 — there is
    /// no "nothing selected" stop anymore).
    private void navigateAutocomplete(int delta) {
        if (autocompleteItems.isEmpty()) return;
        int size = autocompleteItems.size();
        autocompleteSelectedIndex = ((autocompleteSelectedIndex + delta) % size + size) % size;
        refreshAutocompletePopup();
    }

    /// Applies the currently selected autocomplete item to the input field.
    private void applyAutocompleteSelection() {
        if (autocompleteSelectedIndex < 0 || autocompleteSelectedIndex >= autocompleteItems.size()) return;
        String selected = autocompleteItems.get(autocompleteSelectedIndex);

        String text = inputField.getText();
        if (text == null) return;

        int caretPos = inputField.getCaretPosition();
        String textBeforeCaret = text.substring(0, Math.min(caretPos, text.length()));

        if (textBeforeCaret.startsWith("/")) {
            if (text.strip().equals(selected)) {
                // The command is already fully typed — Enter should RUN it, not re-insert it.
                hideAutocomplete();
                sendMessage();
                return;
            }
            inputField.setText(selected + " ");
            inputField.positionCaret(selected.length() + 1);
        } else {
            int atIndex = textBeforeCaret.lastIndexOf('@');
            if (atIndex >= 0) {
                String beforeAt = text.substring(0, atIndex);
                String afterCaret = text.substring(Math.min(caretPos, text.length()));
                inputField.setText(beforeAt + "@" + selected.substring(selected.indexOf(':') + 1) + " " + afterCaret);
                inputField.positionCaret((beforeAt + "@" + selected.substring(selected.indexOf(':') + 1) + " ").length());
            }
        }

        hideAutocomplete();
    }

    /// Hides the autocomplete popup.
    private void hideAutocomplete() {
        if (autocompletePopup == null) return;
        autocompletePopup.setVisible(false);
        autocompletePopup.setManaged(false);
        autocompleteItems.clear();
        autocompleteSelectedIndex = -1;
    }

    @Nullable
    private ChatAgent getOrCreateChatAgent() {
        AiSession session = sessionStore.getCurrentSession();
        if (session == null) {
            return null;
        }
        return agentCache.computeIfAbsent(session.getId(),
                id -> {
                    AiPromptBuilder pb = new AiPromptBuilder(aiSettings, toolRegistry,
                            skillRegistry,
                            searchConfig, this::isPlanMode, rememberStore);
                    // 开发者选项 · --dangerously-skip: when on, pass NO confirm handlers (neither the
                    // dangerous nor the red critical gate) so nothing ever prompts. The real flag is
                    // now also threaded straight into AiExecutionPolicy's own bypass field by
                    // ChatAgentFactory.build (see its own comment) so a null handler here can never
                    // be reached on an ASK/BLOCK decision in the first place.
                    boolean skipAll = aiSettings.isDangerouslySkipPermissions();
                    // Part A: thread the per-tool/action(/path) override store into the real
                    // per-call decision (see LangChain4jToolAdapter#execute), instead of only ever
                    // consulting the fixed global AiSettings fields. Part E: also thread isPlanMode
                    // so a CONTROLLED_WRITE/DANGEROUS_WRITE call is BLOCKed while Plan Mode is
                    // active, evaluated per actual call rather than by wholesale-disabling whole
                    // tools up front (see applyPlanGating()). Auto-mode merge: also thread
                    // isPossiblyUnattended so a DANGEROUS_WRITE/CRITICAL call is hard-BLOCKed —
                    // never merely asked — while the CURRENT turn may be running with nobody
                    // watching (see AiExecutionPolicy's class doc).
                    return ChatAgentFactory.build(aiSettings, session, toolRegistry, pb,
                            skipAll ? null : this::confirmDangerousOperation,
                            (skipAll || !aiSettings.isCriticalConfirmEnabled()) ? null : this::confirmCriticalOperation,
                            toolPermissionStore, this::isPlanMode, this::isPossiblyUnattended);
                });
    }

    /// Whether the CURRENT turn may be running unattended — i.e. it was not triggered by a direct,
    /// just-now user message, but by a synthetic event turn (a background-job auto-continuation, or
    /// an external prompt fed in by another part of the launcher, e.g. the crash window — see
    /// {@link #submitExternalPrompt}). Reflects {@link #currentTurnUnattended}, set in
    /// {@link #sendText} for every turn as it starts.
    ///
    /// ## Why treat EVERY synthetic turn as possibly unattended
    ///
    /// The one case we can be certain a human is at the keyboard RIGHT NOW is the composer's own
    /// Send button being pressed. Every other path that can start a turn — most importantly the
    /// auto-continuation fired automatically once a background job finishes (see
    /// {@link #onBackgroundJobComplete}) — can just as easily fire while the user has stepped away;
    /// that is in fact the whole point of background jobs (“the chat stays usable” while the user
    /// does something else). Treating a genuinely-attended external prompt (e.g. the crash window,
    /// where the user is looking right at a dialog) as "possibly unattended" too is a conservative
    /// false positive: at worst it turns a dangerous operation from "ask" into "blocked, try again
    /// once you send a real message" — never a silent auto-allow. That asymmetry (over-blocking is
    /// recoverable, silently running a destructive command unattended is not) is why this signal is
    /// deliberately coarse rather than trying to thread a precise "is a human looking at the screen"
    /// signal through every external caller.
    private boolean isPossiblyUnattended() {
        return currentTurnUnattended;
    }

    /// Blocking confirmation used by the tool layer before a dangerous (non-critical) operation
    /// runs. Invoked on the agent's background thread: shows a dialog on the FX thread and waits
    /// for the user's answer (denying on timeout/error so the agent can never hang). Offers the
    /// "remember this choice" checkbox — see {@link #showConfirmDialog}.
    private boolean confirmDangerousOperation(String toolName, String summary) {
        return showConfirmDialog(toolName, extractActionForRemember(summary),
                i18n("ai.confirm.dangerous.title"), i18n("ai.confirm.dangerous.text", summary),
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.QUESTION,
                120, true);
    }

    /// Second-tier CRITICAL confirmation (red) for catastrophic operations — deleting a
    /// world/instance, editing save/NBT data, deleting backups, or removing files under
    /// saves/playerdata/.minecraft. Shown IN ADDITION to (and after) the normal confirm, right
    /// before execution. This handler is never even called while the turn may be unattended — the
    /// tool layer refuses the operation outright before reaching it in that case, see
    /// {@code AiExecutionPolicy}'s class doc. Denies on timeout/error so nothing hangs.
    /// NEVER offers the "remember this choice" checkbox — this tier is deliberately never
    /// skippable via a remembered preference, see {@link #showConfirmDialog}.
    private boolean confirmCriticalOperation(String toolName, String summary) {
        // Severity is carried by the dialog's native red ERROR icon, not "⛔" characters.
        return showConfirmDialog(toolName, null, "高危操作 · 二次确认", // TODO(i18n)
                "高危操作，可能不可恢复！请仔细确认：\n\n" + summary // TODO(i18n)
                        + "\n\n这可能永久修改或删除你的存档/玩家数据/备份。确定要继续吗？",
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR,
                180, false);
    }

    /// Shared plumbing for {@link #confirmDangerousOperation} and {@link #confirmCriticalOperation}:
    /// shows a Yes/No dialog on the FX thread and blocks the calling (agent tool) thread until it
    /// is answered, declined on timeout, or auto-declined because the turn that raised it was
    /// already abandoned.
    ///
    /// Registers itself as {@link #activeConfirm} so {@link #stopResponse()} and a session switch
    /// ({@link #loadSessionMessages}) can decline it and dismiss the dialog immediately — instead
    /// of leaving this thread blocked here for up to the full timeout after the user has moved on,
    /// during which a stale dialog could stack under a brand-new one and resurface answerable out
    /// of context. See the {@link #activeConfirm} field doc for the full story.
    ///
    /// `myGeneration` is captured on the CALLING thread — i.e. as of the instant the tool actually
    /// asked to confirm — BEFORE anything is scheduled on the FX thread. Comparing it against the
    /// live {@link #responseGeneration} once the FX thread actually gets to build the dialog closes
    /// a TOCTOU race: if the user hit Stop in the gap between the tool call starting and the dialog
    /// being built, the turn is already abandoned and the dialog is skipped entirely instead of
    /// flashing up a prompt nobody is expecting any more.
    ///
    /// If the dialog's turn belongs to a DIFFERENT, still-legitimately-streaming (merely
    /// backgrounded) session than the one on screen, the dialog is prefixed with that session's
    /// name so it can't be mistaken for something concerning whatever the user is currently looking
    /// at — it stays fully answerable (declining a real pending operation just because the user
    /// glanced at another session would be a regression of its own).
    ///
    /// @param action        best-effort tool action for the "remember" override's scoping (see
    ///                      {@link #extractActionForRemember}), or {@code null} to scope tool-wide.
    ///                      Unused when {@code allowRemember} is false.
    /// @param messageType   dialog severity icon — QUESTION for the normal tier, ERROR (red) for
    ///                      the critical tier (replaces the former "⛔" title/body characters).
    /// @param allowRemember whether to offer the "remember this choice" checkbox — MUST be
    ///                      {@code false} for the critical/red tier, which is never skippable.
    private boolean showConfirmDialog(String toolName, @Nullable String action, String dialogTitle,
                                       String dialogText,
                                       org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType messageType,
                                       long timeoutSeconds, boolean allowRemember) {
        final int myGeneration = responseGeneration;
        java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<javafx.scene.layout.Region> paneRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Platform.runLater(() -> {
            try {
                if (myGeneration != responseGeneration) {
                    // The turn that wanted this confirmation was already stopped/superseded before
                    // we even got to build the dialog — auto-decline silently, no dialog shown.
                    future.complete(false);
                    return;
                }
                cancelActiveConfirm(); // at most one confirm dialog should ever be live at once

                String title = dialogTitle;
                String text = dialogText;
                AiSession displayed = sessionStore.getCurrentSession();
                if (streamSessionId != null && (displayed == null || !streamSessionId.equals(displayed.getId()))) {
                    // This turn belongs to a session other than the one on screen. A stopped turn
                    // was already filtered out above, so this one is still legitimately running in
                    // the background — tag it rather than auto-declining it.
                    AiSession origin = sessionStore.getSession(streamSessionId);
                    String label = (origin != null && origin.getTitle() != null && !origin.getTitle().isBlank())
                            ? origin.getTitle() : streamSessionId;
                    title = i18n("ai.confirm.other_session.title", title);
                    text = i18n("ai.confirm.other_session.text", label, text);
                }

                org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder builder =
                        new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                                text, title, messageType);
                CheckBox rememberBox = null;
                if (allowRemember) {
                    rememberBox = new CheckBox(i18n("ai.confirm.remember"));
                    builder.extraContent(rememberBox);
                }
                final CheckBox remember = rememberBox;

                java.util.concurrent.atomic.AtomicReference<PendingConfirm> pendingRef = new java.util.concurrent.atomic.AtomicReference<>();
                org.jackhuang.hmcl.ui.construct.MessageDialogPane pane = builder
                        .yesOrNo(
                                () -> {
                                    if (remember != null && remember.isSelected()) {
                                        rememberConfirmDecision(toolName, action, true);
                                    }
                                    if (activeConfirm == pendingRef.get()) activeConfirm = null;
                                    future.complete(true);
                                },
                                () -> {
                                    if (remember != null && remember.isSelected()) {
                                        rememberConfirmDecision(toolName, action, false);
                                    }
                                    if (activeConfirm == pendingRef.get()) activeConfirm = null;
                                    future.complete(false);
                                })
                        .build();
                paneRef.set(pane);
                PendingConfirm pending = new PendingConfirm(future, paneRef, myGeneration);
                pendingRef.set(pending);
                activeConfirm = pending;
                Controllers.dialog(pane);
            } catch (Throwable t) {
                future.complete(false);
            }
        });
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            closeStaleConfirmDialog(paneRef);
            return false;
        }
    }

    /// Best-effort extraction of a merged domain tool's `action` parameter from the free-text
    /// `summary` the tool layer hands to {@link #confirmDangerousOperation} — used ONLY to scope
    /// the "remember this choice" override more tightly than tool-wide.
    /// {@link org.jackhuang.hmcl.ai.tools.ToolConfirmHandler}'s signature is fixed at
    /// {@code (toolName, summary)} with no structured action field, so this parses the same
    /// `Map.toString()` shape `summarizeForConfirm` falls back to when the call has no
    /// `command`/`query` parameter (`{action=delete, id=...}`). Returns {@code null} — falling back
    /// to a tool-wide override — whenever the pattern isn't found (e.g. shell-style tools that show
    /// the raw command instead), rather than guessing.
    @Nullable
    private static String extractActionForRemember(@Nullable String summary) {
        if (summary == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[{,]\\s*action=([^,}]+)").matcher(summary);
        if (!m.find()) return null;
        String action = m.group(1).trim();
        return action.isEmpty() ? null : action;
    }

    /// Persists the "remember this choice" checkbox's answer into {@link #toolPermissionStore} so a
    /// future call to the exact same tool (and, when known, action) can be resolved without asking
    /// again — consulted by {@code LangChain4jToolAdapter#execute} on every call via the
    /// {@code AiToolPermissionStore} passed into {@link ChatAgentFactory#build}. Reloads the store
    /// immediately before writing so a concurrent edit made through the AI settings page's own
    /// (separate in-memory) instance of this same file isn't clobbered.
    ///
    /// There is no dedicated "always deny" override value (see
    /// {@link org.jackhuang.hmcl.ai.tools.AiToolPermissionStore.OverrideMode}) — an approval records
    /// {@code ALWAYS_ALLOW}; a decline is recorded as {@code ALWAYS_ASK}, the most conservative
    /// available override (and one that a possibly-unattended BLOCK can still never be relaxed by
    /// regardless — see {@code AiToolPermissionStore.OverrideMode#apply}).
    private void rememberConfirmDecision(String toolName, @Nullable String action, boolean approved) {
        try {
            toolPermissionStore.load();
            org.jackhuang.hmcl.ai.tools.AiToolPermissionStore.OverrideMode mode = approved
                    ? org.jackhuang.hmcl.ai.tools.AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW
                    : org.jackhuang.hmcl.ai.tools.AiToolPermissionStore.OverrideMode.ALWAYS_ASK;
            if (action != null) {
                toolPermissionStore.setOverride(toolName, action, mode);
            } else {
                toolPermissionStore.setOverride(toolName, mode);
            }
            toolPermissionStore.save();
        } catch (Exception e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "[AI] failed to persist remembered tool confirm choice for " + toolName, e);
        }
    }

    /// Cancels any pending dangerous/critical confirm dialog (so the tool-execution thread blocked
    /// in {@link #showConfirmDialog} unblocks immediately as a decline) and dismisses its dialog.
    /// Called on stop and before showing a NEW confirm; deliberately NOT on session switch — a
    /// cross-session confirm stays answerable (see showConfirmDialog's owner labelling). This is
    /// where it differs from {@link #cancelActiveAsk()}, which IS also cancelled on session switch;
    /// the tracking rationale is the same though: see the {@link #activeConfirm} field doc.
    private void cancelActiveConfirm() {
        PendingConfirm pending = activeConfirm;
        activeConfirm = null;
        if (pending != null && !pending.future.isDone()) {
            pending.future.complete(false);
            closeStaleConfirmDialog(pending.paneRef);
        }
    }

    /// Dismisses a confirm dialog on the FX thread after its future is no longer being awaited
    /// (timed out, or explicitly cancelled by {@link #cancelActiveConfirm()}) — the dialog would
    /// otherwise remain visible and clickable forever, a dead relic indistinguishable from a
    /// still-pending prompt. Best-effort: `paneRef` may still be unset if runLater above hasn't
    /// executed yet or failed before building the pane.
    private static void closeStaleConfirmDialog(java.util.concurrent.atomic.AtomicReference<javafx.scene.layout.Region> paneRef) {
        Platform.runLater(() -> {
            javafx.scene.layout.Region pane = paneRef.get();
            if (pane != null) {
                try {
                    org.jackhuang.hmcl.ui.DialogUtils.close(pane);
                } catch (Throwable ignored) {
                    // best-effort: the dialog may already be closed (e.g. the user clicked it in
                    // the same instant the timeout/cancellation fired) — nothing more to do either way.
                }
            }
        });
    }

    /// Guards rapid session switching so the UI stays responsive: quick successive clicks are
    /// debounced and a load that is already in flight for a given session id is not restarted.
    @Nullable
    private CompletableFuture<Void> pendingSessionLoad;
    @Nullable
    private String loadingSessionId;

    /// Stashes the composer's CURRENT text as {@code sessionId}'s draft (blank text removes the
    /// stale draft). Field invariant maintained together with {@link #restoreDraftFor}:
    /// {@link #draftSessionId} is ALWAYS the session whose text the composer currently holds.
    private void stashDraftFor(@Nullable String sessionId) {
        if (sessionId == null) {
            return;
        }
        String draft = inputField.getText();
        if (draft == null || draft.isBlank()) {
            sessionDrafts.remove(sessionId);
        } else {
            sessionDrafts.put(sessionId, draft);
        }
    }

    /// Hands the composer over to {@code sessionId}: stashes the leaving session's draft, then
    /// restores (possibly empty) {@code sessionId}'s. The single draft hand-over used by ALL
    /// session-switch paths (loadSession / createSession / switchToSessionAndScroll) — bug 7.9
    /// was two of them skipping it, leaving drafts attributed to the wrong session.
    private void restoreDraftFor(String sessionId) {
        if (sessionId.equals(draftSessionId)) {
            return; // composer already holds this session's draft
        }
        stashDraftFor(draftSessionId);
        inputField.setText(sessionDrafts.getOrDefault(sessionId, ""));
        draftSessionId = sessionId;
    }

    private void loadSession(AiSession session) {
        if (session == null) {
            return;
        }
        String id = session.getId();
        // Per-session drafts: stash whatever is in the composer for the session we're leaving,
        // restore the (possibly empty) draft of the one we're entering.
        restoreDraftFor(id);
        // Already loading this session — don't restart.
        if (loadingSessionId != null && loadingSessionId.equals(id) && pendingSessionLoad != null && !pendingSessionLoad.isDone()) {
            return;
        }
        // Cancel the previous load if a different session was requested.
        if (pendingSessionLoad != null && !pendingSessionLoad.isDone()) {
            pendingSessionLoad.cancel(true);
        }
        loadingSessionId = id;
        pendingSessionLoad = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(60); // brief debounce so rapid clicks coalesce
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> {
                if (sessionStore.getCurrentSession() != session) return; // moved on already
                loadSessionMessages(session);
                updateHeader(session);
            });
        });
    }

    private void loadSessionMessages(AiSession session) {
        cancelActiveAsk(); // a pending question can't be answered for a backgrounded session
        resetConversationView(); // discard any view state left over from a previous session's render
        stickToBottom = true; // a freshly-opened session starts pinned to the latest message
        int index = 0;
        for (LlmMessage msg : session.getMessages()) {
            String role = msg.getRole();
            // Imported/legacy/provider-mangled messages can carry a null content (importFromJson
            // does not validate the field) — render them as empty rather than NPE'ing the whole
            // session load (P1).
            String content = msg.getContent() == null ? "" : msg.getContent();
            if (msg.isToolRecord()) {
                // Persisted record of one tool invocation — rebuild the completed tool card so
                // history shows what the AI actually did (cards used to vanish on reload).
                // NOT followed by endToolCallRun(): consecutive tool records must keep extending
                // the same run/group, exactly like the live path.
                if (aiSettings.isToolCallDisplayEnabled()) {
                    addPersistedToolCard(msg.getToolPayload());
                }
            } else if (msg.isEvent()) {
                // Synthetic turn (background-job auto-continue / crash injection): a neutral
                // event pill, NOT a user bubble, and no copy/edit/resend action bar.
                addEventPill(content);
                endToolCallRun();
            } else if ("user".equals(role)) {
                addUserBubble(content, true);
                attachMessageActions(content, role, index);
                endToolCallRun();
            } else if ("assistant".equals(role)) {
                // Reasoning/"思考过程" that came with this answer: a collapsed card above the bubble.
                String reasoningText = msg.getReasoning();
                if (reasoningText != null && !reasoningText.isBlank()) {
                    messageList.getChildren().add(wrapCard(new ReasoningCard(reasoningText, false)));
                }
                createAiBubble(content, msg.getUsage());
                attachMessageActions(content, role, index);
                endToolCallRun();
            } else if (isToolMessage(content)) {
                if (aiSettings.isToolCallDisplayEnabled()) {
                    addToolMessage(content);
                }
                endToolCallRun();
            } else {
                addSystemMessage(content);
                endToolCallRun();
            }
            index++;
        }
        updateToolActivityVisibility();
        updateEmptyState();
        scrollToBottom();
        updateSendButtonMode(); // reflect whether THIS (now visible) session is the streaming one
        // A background-job completion that was set aside because the user was viewing another
        // session is delivered once its own session is on screen and idle again.
        if (!isStreaming() && !pendingCompletions.isEmpty()) {
            java.util.Iterator<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> it = pendingCompletions.iterator();
            while (it.hasNext()) {
                org.jackhuang.hmcl.ai.tools.AiJobManager.Job j = it.next();
                if (session.getId().equals(j.getSessionId())) {
                    it.remove();
                    Platform.runLater(() -> onBackgroundJobComplete(j));
                    break;
                }
            }
        }
    }

    /// Trims a tool argument/result string for a single log line.
    private static String abbreviateLog(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
    }

    /// Returns true when the message content appears to be a tool execution
    /// result stored by the ChatAgent. Null-safe (imported messages may lack content — P1);
    /// package-private for the unit test.
    static boolean isToolMessage(@Nullable String content) {
        return content != null && content.startsWith("Tool result for ");
    }

    /// Appends a row of small icon-only action buttons below the message bubble most recently
    /// added to {@link #messageList}, bound to {@code index} in the session history.
    /// Left-to-right: copy, regenerate, branch, delete. User messages also show edit and resend.
    private void attachMessageActions(String content, String role, int index) {
        var children = messageList.getChildren();
        if (children.isEmpty()) {
            return;
        }
        Node bubble = children.get(children.size() - 1);
        HBox bar = new HBox(4);
        bar.getStyleClass().add("ai-bubble-actions");

        JFXButton copy = smallIcon(SVG.CONTENT_COPY, i18n("ai.msg.copy"),
                () -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(content == null ? "" : content);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                });
        bar.getChildren().add(copy);

        boolean isUser = "user".equals(role);
        if (isUser) {
            JFXButton edit = smallIcon(SVG.EDIT, i18n("ai.msg.edit"),
                    () -> startInlineEdit(bubble, index, content));
            bar.getChildren().add(edit);
        }

        JFXButton regenerate = smallIcon(SVG.REFRESH, i18n("ai.msg.resend"),
                () -> {
                    if (isUser) resendUserMessage(index, content);
                    else regenerateFrom(index);
                });
        bar.getChildren().add(regenerate);

        JFXButton branch = smallIcon(SVG.SCHEMA, i18n("ai.msg.branch"),
                () -> branchFrom(index));
        bar.getChildren().add(branch);

        JFXButton del = smallIcon(SVG.DELETE_FOREVER, i18n("ai.msg.delete"),
                () -> deleteMessageAt(index));
        bar.getChildren().add(del);

        // Align actions to the same side as the bubble: right for user, left for AI.
        HBox row = new HBox(bar);
        row.setAlignment(isUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        // Match the bubble-wrapper's padding (4 16 4 16) so the icon bar sits directly below
        // the bubble's horizontal extent — the left edge of the AI bubble starts at 16px, the
        // right edge of the user bubble ends at 16px from the message-list edge.
        row.setPadding(new Insets(0, 16, 6, 16));

        // BF A2 / C-04: the bubble wrapper and its action row are fused into one `.ai-msg-block`
        // so the row can hover-reveal purely in CSS (JavaFX has no sibling selector; the rules
        // touch only -fx-opacity, so layout never shifts and the buttons stay Tab-reachable).
        // Red line A1 (§0-1): the block holds EXACTLY this bubble wrapper and its action row —
        // reasoning/tool/group cards remain flat siblings of messageList, never pulled in here.
        children.remove(children.size() - 1);
        VBox block = new VBox(bubble, row);
        block.getStyleClass().add("ai-msg-block");
        // A user bubble carries the per-turn 8px top margin (VS §1.4); once the wrapper moves
        // inside the block the margin must move to the messageList child — the block — or it
        // would only open a gap INSIDE the block instead of separating it from the previous turn.
        Insets turnMargin = VBox.getMargin(bubble);
        if (turnMargin != null) {
            VBox.setMargin(bubble, null);
            VBox.setMargin(block, turnMargin);
        }
        children.add(block);
    }

    private static JFXButton smallIcon(SVG icon, String tooltip, Runnable action) {
        JFXButton btn = FXUtils.newToggleButton4(icon, 16);
        FXUtils.installFastTooltip(btn, tooltip);
        btn.setOnAction(e -> action.run());
        return btn;
    }

    /// Forks the conversation into a new session containing everything up to {@code index}.
    /// Message-edit actions (branch/regenerate/edit/resend/delete) mutate and truncate the session;
    /// running one while a response is streaming corrupts the in-flight history (orphaned reply,
    /// dropped resend). Block them until the user stops the current response.
    private boolean blockedWhileStreaming() {
        if (isStreaming()) {
            Controllers.showToast("请先停止当前回复，再编辑/重发/删除消息");
            return true;
        }
        return false;
    }

    private void branchFrom(int index) {
        if (blockedWhileStreaming()) return;
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) return;
        AiSession branch = sessionStore.createBranch(cur, index, cur.getTitle() + "（分支）");
        persistStore();
        refreshSessionList();
        loadSessionMessages(branch);
    }

    /// Regenerate the AI response from a given assistant message index: drop the assistant
    /// and everything after it, then resend the preceding user message.
    private void regenerateFrom(int index) {
        if (blockedWhileStreaming()) return;
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) {
            return;
        }
        // Walk backwards for the preceding user prompt.
        java.util.List<org.jackhuang.hmcl.ai.llm.LlmMessage> msgs = cur.getMessages();
        String prompt = "";
        for (int i = index - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).getRole())) {
                prompt = msgs.get(i).getContent();
                index = i;
                break;
            }
        }
        if (prompt.isEmpty()) {
            // No preceding user message (e.g. the lone summary left by /compact): there is
            // nothing to regenerate FROM — truncating here silently wiped the whole session.
            Controllers.showToast("这条消息没有对应的提问，无法重新生成");
            return;
        }
        cur.truncateFrom(index);
        persistStore();
        loadSessionMessages(cur);
        sendText(prompt, null);
    }

    /// Deletes a single message from the session and re-renders, so it disappears from context.
    private void deleteMessageAt(int index) {
        if (blockedWhileStreaming()) return;
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) {
            return;
        }
        cur.removeAt(index);
        persistStore();
        loadSessionMessages(cur);
    }

    /// Inline-edit a user message: turn its bubble into an editable box with 取消/确定, hiding that
    /// message's action bar. 取消 restores the conversation untouched; 确定 truncates from this
    /// message and resends the revised text so the agent regenerates from here. (Previously this
    /// deleted the message + its reply and dumped the text back into the composer — wrong.)
    private void startInlineEdit(Node bubble, int index, String content) {
        if (blockedWhileStreaming()) return;
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) {
            return;
        }
        var children = messageList.getChildren();
        // Since BF A2 the bubble wrapper lives inside an `.ai-msg-block` (bubble + action row),
        // so the direct child of messageList to swap out is the enclosing block, not the wrapper
        // itself — climb from the wrapper to whichever ancestor is the messageList child.
        Node host = bubble;
        while (host != null && host.getParent() != messageList) {
            host = host.getParent();
        }
        int pos = host == null ? -1 : children.indexOf(host);
        if (pos < 0) {
            return;
        }

        JFXTextArea editor = new JFXTextArea(content == null ? "" : content);
        editor.setWrapText(true);
        int lines = content == null ? 1 : content.split("\n", -1).length;
        editor.setPrefRowCount(Math.min(10, Math.max(2, lines)));
        editor.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        editor.getStyleClass().add("ai-inline-edit");

        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("dialog-cancel"); // native dialog-button styling
        JFXButton confirm = new JFXButton("确定");
        confirm.getStyleClass().add("dialog-accept");
        HBox btnRow = new HBox(8, cancel, confirm);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox editBox = new VBox(6, editor, btnRow);
        editBox.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        editBox.getStyleClass().add("ai-inline-edit-box");
        HBox wrapper = new HBox(editBox);
        wrapper.setAlignment(Pos.CENTER_RIGHT);
        wrapper.setPadding(new Insets(2, 16, 2, 16));

        // Replacing the whole `.ai-msg-block` swaps out the bubble AND its action bar in one go —
        // the bar must not stay interactable while editing (取消/确定 rebuild the list anyway).
        children.set(pos, wrapper);
        editor.requestFocus();
        editor.positionCaret(editor.getText().length());

        // Re-check the streaming guard INSIDE the handlers: a new turn can start while the edit
        // box is open (background-job auto-continue), and confirming then would truncate the
        // history mid-stream without resending — orphaned reply + lost message.
        cancel.setOnAction(e -> {
            if (blockedWhileStreaming()) return;
            loadSessionMessages(cur);
        });
        confirm.setOnAction(e -> {
            if (blockedWhileStreaming()) return;
            String edited = editor.getText() == null ? "" : editor.getText().trim();
            if (edited.isEmpty()) {
                loadSessionMessages(cur);
                return;
            }
            cur.truncateFrom(index);
            persistStore();
            loadSessionMessages(cur);
            sendText(edited, null);
        });
    }

    /// Regenerate from a user message: drop it and everything after, then resend it as-is.
    private void resendUserMessage(int index, String content) {
        if (blockedWhileStreaming()) return;
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) {
            return;
        }
        cur.truncateFrom(index);
        persistStore();
        loadSessionMessages(cur);
        sendText(content == null ? "" : content, null);
    }

    /// Imports sessions from a previously exported store JSON (导出全部会话 → *.json) into the LIVE
    /// store, so it can't be clobbered by a later autosave. Existing sessions are never overwritten;
    /// only new ones are added. Wired to the `/import` slash command.
    private void importSessions() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("导入会话");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("会话 JSON (*.json)", "*.json"));
        java.io.File chosen = fc.showOpenDialog(Controllers.getStage());
        if (chosen == null) return;
        try {
            String json = java.nio.file.Files.readString(chosen.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            int added = sessionStore.importFromJson(json);
            persistStore();
            refreshSessionList();
            Controllers.showToast(added > 0
                    ? "已导入 " + added + " 个会话"
                    : "没有可导入的新会话（可能都已存在，或文件不含会话）");
        } catch (Exception ex) {
            Controllers.showToast("导入失败：" + (ex.getMessage() != null ? ex.getMessage() : "文件格式不正确"));
        }
    }

    private void sendMessage() {
        if (isStreaming()) {
            // A response is in flight. For ANOTHER session, don't silently swallow the message —
            // say why (we don't run two streams at once yet). For THIS session the button already
            // acts as Stop, so the message can't be sent either — but staying completely silent
            // here reads as "the app is frozen" to a user who reflexively types a follow-up/change
            // of mind (e.g. while an `ask` panel is waiting for them) instead of clicking Stop;
            // say so and leave their typed text in the box so nothing is lost.
            Controllers.showToast(isStreamingCurrentSession()
                    ? "AI 正在处理上一条消息，暂时无法发送 — 如果上面有问题在等你回答，请先在那里作答；要打断的话点一下发送按钮（此时是“停止”）"
                    : "另一个会话正在生成回复，切回那个会话可以停止它，或稍候再发");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        // Slash commands: handle locally, or expand into a tool-triggering prompt.
        String command = text.split("\\s+", 2)[0].toLowerCase();
        switch (command) {
            case "/clear" -> {
                inputField.clear();
                clearCurrentConversation();
                return;
            }
            case "/new" -> {
                inputField.clear();
                createSession();
                return;
            }
            case "/sessions" -> {
                inputField.clear();
                openSearchDialog();
                return;
            }
            case "/import" -> {
                inputField.clear();
                importSessions();
                return;
            }
            case "/skills" -> {
                inputField.clear();
                String summary = skillRegistry.summarizeEnabled();
                addSystemMessage(summary.startsWith("(no")
                        ? "当前没有已启用的技能。" : "已启用的技能：\n" + summary);
                return;
            }
            case "/compact" -> {
                inputField.clear();
                compactConversation();
                return;
            }
            case "/plan" -> {
                inputField.clear();
                planMode = !planMode;
                updatePlanBadge();
                addSystemMessage(planMode
                        ? "已开启计划模式：我会只读分析并先给出分步计划，批准前不做任何改动（写入/安装/删除/启动等工具已暂时禁用）。再次输入 /plan 退出。"
                        : "已关闭计划模式，现在可以正常执行操作。");
                return;
            }
            case "/help" -> {
                inputField.clear();
                addSystemMessage(buildSlashHelpText());
                return;
            }
            case "/model" -> {
                inputField.clear();
                addSystemMessage(currentModelSummary());
                return;
            }
            case "/crash" -> text = i18n("ai.command.crash_prompt");
            case "/log" -> text = i18n("ai.command.log_prompt");
            default -> {
                // Not a recognized command — send as normal text.
            }
        }

        if (spendTracker().isOverLimit()) {
            warnSpendLimitReached();
            return;
        }
        inputField.clear();
        if (attachedFiles.isEmpty()) {
            sendText(text, null);
            return;
        }
        // Attachment content is read OFF the FX thread — a dropped 100MB log used to freeze the
        // whole window for the duration of a synchronous readString right here (7a).
        final String userText = text;
        final List<Path> files = List.copyOf(attachedFiles);
        clearFileChip();
        Thread reader = new Thread(() -> {
            String full = buildAttachmentText(userText, files);
            Platform.runLater(() -> sendText(full, null));
        }, "ai-attachment-read");
        reader.setDaemon(true);
        reader.start();
    }

    /// Single toast for "daily AI spend cap reached" — the copy previously lived duplicated in
    /// two call sites and had already started to drift (bug 8.2).
    private void warnSpendLimitReached() {
        Controllers.showToast("已达今日 AI 花费上限（约 $"
                + String.format(java.util.Locale.ROOT, "%.2f", spendTracker().getDailyLimitUsd())
                + "）。可在 AI 设置里调高上限，或明天再用。"); // TODO(i18n)
    }

    /// Core send path shared by the composer ({@link #sendMessage}) and external/synthetic
    /// prompts ({@link #submitExternalPrompt}). External prompts no longer go THROUGH the
    /// composer — they used to overwrite whatever draft the user was typing and send it away.
    ///
    /// @param kind `null` for a normal user message, or {@link LlmMessage#KIND_EVENT} for a
    ///             synthetic turn (rendered as a neutral event pill, not a user bubble).
    private void sendText(String text, @Nullable String kind) {
        if (isStreaming()) {
            // Callers that pre-check isStreaming (the composer) never reach this; the ones that
            // don't (resend/regenerate/inline-edit, the async attachment-read hand-off) used to
            // drop the message with zero feedback.
            Controllers.showToast("上一条回复仍在进行，本条消息未发送"); // TODO(i18n)
            return;
        }
        if (text == null || text.isBlank()) return;
        text = text.trim();

        // The composer's own sendMessage() already checks this before clearing the input field (so
        // a declined send doesn't lose the user's draft) — but resendUserMessage/regenerateFrom/the
        // inline-edit-confirm path/submitExternalPrompt (background-job auto-continue) all call
        // straight into this method and had NO daily-spend-cap check of their own. Re-checking here
        // makes every path that can start a new turn subject to the same cap; the auto-continue
        // loop is naturally bounded too since AUTO_CONTINUE_LIMIT still trips once enough
        // no-op attempts accumulate.
        if (spendTracker().isOverLimit()) {
            warnSpendLimitReached();
            return;
        }

        boolean event = LlmMessage.KIND_EVENT.equals(kind);
        // A real user message resets the auto-continue depth guard; synthetic event turns
        // (background-job auto-continue) deliberately do NOT, so a runaway loop can't be masked.
        if (!event) {
            autoContinueDepth = 0;
        }
        // See #isPossiblyUnattended's own doc: a synthetic event turn is flagged as possibly
        // unattended for the whole turn (read on the agent's background thread while tools run),
        // a direct user-composer send is not.
        currentTurnUnattended = event;

        AiSession session = sessionStore.getCurrentSession();
        if (session == null) return;

        // Keep the game-directory tools pointed at the instance the user currently
        // has selected (it may have changed since the page or agent was created).
        refreshGameContext();

        // Auto-title from first message
        if (session.getMessages().isEmpty() && !event) {
            String title = text.length() > 50 ? text.substring(0, 47) + "..." : text;
            session.setTitle(title);
            updateHeader(session);
            refreshSessionList();
        }

        if (event) {
            addEventPill(text);
        } else {
            addUserBubble(text);
        }
        persistStore();

        ChatAgent agent = getOrCreateChatAgent();
        if (agent == null) {
            addSystemMessage(i18n("ai.error.no_endpoint"));
            return;
        }

        setStatus("");
        startAiResponse(agent, session, text, kind);
    }

    private void startAiResponse(ChatAgent agent, AiSession session, String userInput, @Nullable String kind) {
        // Render a whole turn as an in-order sequence appended to the end of the list:
        // text segment -> tool card -> text segment -> ... No pre-created bottom bubble.
        streamingBubble = null;
        reasoningLiveCard = null;
        pendingToolCards.clear();
        activeToolCard = null;

        // Plan mode: gate off write-capable tools for this response; restored in
        // exitStreamingState() (reached on completion, stop, and error).
        applyPlanGating();

        // Bind this stream to the session it belongs to. If the user switches to another
        // session mid-stream, callbacks must NOT touch the (now different) message view —
        // otherwise A's tokens leak into B and scrollToBottom fights the user (jitter/freeze).
        final AiSession streamSession = session;
        streamSessionId = streamSession.getId();

        final int generation = ++responseGeneration;
        StringBuilder fullContent = new StringBuilder();
        // Text of the current segment bubble (reset whenever a new segment starts).
        StringBuilder segment = new StringBuilder();
        // Holds provider-reported usage (if any) so onComplete can render the footer. Written on
        // the provider's callback thread, read inside onComplete's FX runnable — AtomicReference
        // for the cross-thread visibility a plain one-element array never guaranteed (2-1).
        final java.util.concurrent.atomic.AtomicReference<LlmUsage> usageHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        currentCancelled = cancelled;
        currentStreamAgent = agent;
        currentResponse = agent.sendStreaming(userInput, kind,
                createStreamCallback(agent, streamSession, generation, fullContent, segment, usageHolder),
                cancelled::get).exceptionally(ex -> {
            if (generation != responseGeneration) return null; // stopped; ignore
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            LlmException error = cause instanceof LlmException
                    ? (LlmException) cause
                    : new LlmException(cause.getMessage() != null ? cause.getMessage() : "Connection failed", 0, cause);
            showAiError(streamingBubble, segment, error, streamSession, generation);
            return null;
        });

        enterStreamingState();
    }

    /// Builds the production streaming callback for one turn. Extracted from
    /// {@link #startAiResponse} and package-private so the FX test can drive the EXACT rendering
    /// pipeline (token batching, segment/tool interleaving) without a live model behind it.
    LlmStreamCallback createStreamCallback(@Nullable ChatAgent agent, AiSession streamSession, int generation,
                                           StringBuilder fullContent, StringBuilder segment,
                                           java.util.concurrent.atomic.AtomicReference<LlmUsage> usageHolder) {
        // ---- P12: token batching ----
        // Every token used to schedule its own Platform.runLater doing a full segment.toString()
        // setText (O(n²) text churn per turn) plus a second runLater from scrollToBottom. Tokens
        // now land in a buffer and at most ONE drain is queued at a time, so the FX thread
        // re-renders at most once per pulse no matter how fast the provider streams.
        //
        // Ordering argument (why batching can't reorder against tool cards / completion): the
        // provider invokes ALL callbacks on one thread, so a drain queued by onToken is enqueued
        // on the FX queue BEFORE any onToolActivity/onComplete runnable submitted after it —
        // FIFO guarantees `segment` already holds every earlier token when a finalize runs.
        // Buffers are written on the callback thread and drained under their own lock.
        final StringBuilder tokenBuffer = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean tokenFlushScheduled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final StringBuilder reasoningBuffer = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean reasoningFlushScheduled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                fullContent.append(token);
                synchronized (tokenBuffer) {
                    tokenBuffer.append(token);
                }
                if (!tokenFlushScheduled.compareAndSet(false, true)) {
                    return; // a drain is already queued; it will pick this token up
                }
                Platform.runLater(() -> {
                    tokenFlushScheduled.set(false);
                    String chunk;
                    synchronized (tokenBuffer) {
                        chunk = tokenBuffer.toString();
                        tokenBuffer.setLength(0);
                    }
                    if (chunk.isEmpty()) return;
                    // The discard branches below drop the chunk exactly like the old per-token
                    // path did — a stale-generation / backgrounded-session token never entered
                    // `segment` before either.
                    if (generation != responseGeneration) return; // stopped/superseded
                    if (sessionStore.getCurrentSession() != streamSession) return; // viewing another session
                    if (streamingBubble == null) {
                        // Text resumed after a tool call (or first text): start a new segment.
                        // The visible answer is starting, so collapse the reasoning card out of the way.
                        if (reasoningLiveCard != null) {
                            reasoningLiveCard.setExpanded(false);
                        }
                        streamingBubble = createAiBubble("");
                        // A cached wrapper is invalidated on every streamed append — worse than no
                        // cache at all; finalizeAiBubble switches it back on (P12).
                        setWrapperCache(streamingBubble, false);
                        segment.setLength(0);
                        endToolCallRun();
                    }
                    segment.append(chunk);
                    streamingBubble.setText(segment.toString());
                    scrollToBottom();
                });
            }

            @Override
            public void onReasoningToken(String token) {
                // Same batching as onToken (independent buffer/flag) — reasoning streams are
                // usually LONGER than the visible answer, so this is the bigger win.
                synchronized (reasoningBuffer) {
                    reasoningBuffer.append(token);
                }
                if (!reasoningFlushScheduled.compareAndSet(false, true)) {
                    return;
                }
                Platform.runLater(() -> {
                    reasoningFlushScheduled.set(false);
                    String chunk;
                    synchronized (reasoningBuffer) {
                        chunk = reasoningBuffer.toString();
                        reasoningBuffer.setLength(0);
                    }
                    if (chunk.isEmpty()) return;
                    if (generation != responseGeneration) return; // stopped/superseded
                    if (sessionStore.getCurrentSession() != streamSession) return; // viewing another session
                    if (reasoningLiveCard == null) {
                        // First reasoning token of the turn: create the card (expanded) above the answer.
                        reasoningLiveCard = new ReasoningCard("", true);
                        messageList.getChildren().add(wrapCard(reasoningLiveCard));
                    }
                    reasoningLiveCard.append(chunk);
                    scrollToBottom();
                });
            }

            @Override
            public void onUsage(LlmUsage usage) {
                usageHolder.set(usage);
            }

            @Override
            public void onToolActivity(String toolName, String arguments) {
                if (aiSettings.isToolCallLoggingEnabled()) {
                    org.jackhuang.hmcl.util.logging.Logger.LOG.info("[AI] tool call: " + toolName
                            + " args=" + abbreviateLog(arguments));
                }
                Platform.runLater(() -> {
                    if (generation != responseGeneration) return;
                    if (sessionStore.getCurrentSession() != streamSession) return; // viewing another session
                    // Close off the current text segment so the tool card lands after it.
                    if (streamingBubble != null) {
                        finalizeAiBubble(streamingBubble, segment.toString(), null, false);
                        streamingBubble = null;
                    }
                    if (!aiSettings.isToolCallDisplayEnabled()) return;
                    ToolCard card = new ToolCard(toolName);
                    appendToolCard(card);
                    pendingToolCards.computeIfAbsent(toolName, k -> new java.util.ArrayDeque<>()).addLast(card);
                    // Route live download / install progress to this card until it finishes.
                    activeToolCard = card;
                    scrollToBottom();
                });
            }

            @Override
            public void onToolResult(String toolName, boolean success, String resultSummary) {
                if (aiSettings.isToolCallLoggingEnabled()) {
                    org.jackhuang.hmcl.util.logging.Logger.LOG.info("[AI] tool result: " + toolName + " -> "
                            + (success ? "ok" : "FAILED") + " | " + abbreviateLog(resultSummary));
                }
                Platform.runLater(() -> {
                    if (generation != responseGeneration) return;
                    if (sessionStore.getCurrentSession() != streamSession) return; // viewing another session
                    java.util.Deque<ToolCard> dq = pendingToolCards.get(toolName);
                    ToolCard card = dq != null ? dq.pollFirst() : null;
                    if (card != null) {
                        card.complete(success, resultSummary);
                        scrollToBottom();
                    }
                    activeToolCard = null;
                });
            }

            @Override
            public void onComplete(String completeText) {
                Platform.runLater(() -> {
                    if (generation != responseGeneration) return; // stopped by the user
                    // Render the answer only into its own session's view; but always release the
                    // global streaming state (Stop button) and persist, even if completed in the
                    // background while the user is viewing another session.
                    if (sessionStore.getCurrentSession() == streamSession) {
                        // Finalize the LAST segment bubble with its own segment text only — the
                        // full turn text glued earlier segments into it (duplicating them on
                        // screen for an instant); the reload below renders the canonical view.
                        if (streamingBubble != null) {
                            finalizeAiBubble(streamingBubble, segment.toString(), usageHolder.get(), true);
                            streamingBubble = null;
                        }
                        setStatus(null);
                    }
                    exitStreamingState();
                    persistStore();
                    // Accrue this response's estimated cost against the daily spend cap, warning near it.
                    double turnCost = estimateCost(usageHolder.get());
                    if (turnCost > 0) {
                        spendTracker().record(turnCost);
                        maybeWarnSpend();
                    }
                    // Re-render the finished conversation so the just-completed messages get their
                    // action icons (copy/edit/resend/branch/delete) immediately — live-streamed
                    // bubbles are rendered without an action bar; only the reload path attaches one.
                    if (sessionStore.getCurrentSession() == streamSession) {
                        loadSessionMessages(streamSession);
                    }
                    maybeAutoTitle(agent, streamSession);
                });
            }

            @Override
            public void onError(LlmException error) {
                if (generation != responseGeneration) return;
                // Pass the CURRENT segment, not the whole turn text: writing the full text into
                // the last segment bubble duplicated the already-finalized earlier segments.
                showAiError(streamingBubble, segment, error, streamSession, generation);
            }
        };
    }

    private boolean isStreaming() {
        return currentResponse != null;
    }

    /// True when a response is streaming AND it belongs to the session currently on screen — so the
    /// Send button should act as Stop, and Stop only affects the visible session.
    private boolean isStreamingCurrentSession() {
        if (currentResponse == null || streamSessionId == null) return false;
        AiSession cur = sessionStore.getCurrentSession();
        return cur != null && streamSessionId.equals(cur.getId());
    }

    /// Sets the Send button to Stop only while THIS session's response streams; otherwise Send.
    /// Called on stream start/end and on every session switch, so switching away from a streaming
    /// session restores a normal Send button instead of a Stop that would kill the other session.
    private void updateSendButtonMode() {
        if (isStreamingCurrentSession()) {
            sendBtn.setText(i18n("ai.stop"));
            if (!sendBtn.getStyleClass().contains("ai-stop-btn")) {
                sendBtn.getStyleClass().add("ai-stop-btn");
            }
        } else {
            sendBtn.setText(i18n("ai.send"));
            sendBtn.getStyleClass().remove("ai-stop-btn");
        }
    }

    /// Auto-continuation hook. When a background job belonging to the CURRENT session finishes while
    /// no response is streaming, feed its result back as a follow-up so the AI carries on. Jobs from
    /// other sessions (or completions arriving mid-turn) are left for the task panel / check_job.
    private void onBackgroundJobComplete(org.jackhuang.hmcl.ai.tools.AiJobManager.Job job) {
        AiSession current = sessionStore.getCurrentSession();
        if (current == null || job.getSessionId() == null || !job.getSessionId().equals(current.getId())) {
            // Belongs to a session the user is not viewing (e.g. drained from the queue after
            // they switched away): put it BACK so it is delivered when its session is reopened
            // — consuming it here silently killed that session's auto-continue for good.
            if (job.getSessionId() != null
                    && job.getStatus() != org.jackhuang.hmcl.ai.tools.AiJobManager.Status.CANCELLED
                    && !pendingCompletions.contains(job)) {
                pendingCompletions.add(job);
            }
            return;
        }
        if (job.getStatus() == org.jackhuang.hmcl.ai.tools.AiJobManager.Status.CANCELLED) {
            return; // the user cancelled it — never auto-continue
        }
        if (isStreaming()) {
            // Finished mid-turn: queue it; exitStreamingState() drains the queue when the turn ends,
            // so a completion arriving during a stream is no longer silently dropped.
            if (!pendingCompletions.contains(job)) {
                pendingCompletions.add(job);
            }
            return;
        }
        // Coalesce: a parallel install burst finishes as a stream of completions — deliver them as
        // ONE follow-up turn instead of a dozen "延迟回执" turns (real-session failure mode). Pull
        // every other queued completion of THIS session into the same batch.
        java.util.List<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> batch = new java.util.ArrayList<>();
        batch.add(job);
        java.util.Iterator<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> it = pendingCompletions.iterator();
        while (it.hasNext()) {
            org.jackhuang.hmcl.ai.tools.AiJobManager.Job queued = it.next();
            if (current.getId().equals(queued.getSessionId())) {
                it.remove();
                if (!batch.contains(queued)
                        && queued.getStatus() != org.jackhuang.hmcl.ai.tools.AiJobManager.Status.CANCELLED
                        && queued.getStatus() != org.jackhuang.hmcl.ai.tools.AiJobManager.Status.RUNNING) {
                    batch.add(queued);
                }
            }
        }
        // Drop jobs whose terminal outcome the model already saw via check_job / list_jobs —
        // re-announcing them is exactly the junk-turn spam this path used to produce.
        batch.removeIf(org.jackhuang.hmcl.ai.tools.AiJobManager.Job::isAcknowledged);
        batch.removeIf(j -> j.getStatus() != org.jackhuang.hmcl.ai.tools.AiJobManager.Status.SUCCEEDED
                && j.getStatus() != org.jackhuang.hmcl.ai.tools.AiJobManager.Status.FAILED);
        if (batch.isEmpty()) {
            return;
        }
        if (autoContinueDepth >= AUTO_CONTINUE_LIMIT) {
            addSystemMessage("后台任务「" + batch.get(0).getLabel() + "」等 " + batch.size()
                    + " 项已结束，但连续自动继续已达上限，已暂停以免空转。发送任意消息让我接着处理。");
            return;
        }
        autoContinueDepth++;
        submitExternalPrompt(buildCompletionPrompt(batch));
    }

    /// Renders one auto-continue prompt for a batch of finished jobs: single-job keeps the old
    /// compact shape; multi-job lists every outcome once and tells the model to handle them in
    /// ONE reply instead of acknowledging each individually.
    private static String buildCompletionPrompt(
            java.util.List<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> batch) {
        if (batch.size() == 1) {
            org.jackhuang.hmcl.ai.tools.AiJobManager.Job job = batch.get(0);
            String status = job.getStatus() == org.jackhuang.hmcl.ai.tools.AiJobManager.Status.SUCCEEDED
                    ? "已完成" : "失败";
            String detail = jobDetail(job, 2000);
            return "（后台任务 #" + job.getId() + "「" + job.getLabel() + "」" + status + "）"
                    + (detail.isEmpty() ? "" : "结果：\n" + detail)
                    + "\n请据此继续。";
        }
        StringBuilder sb = new StringBuilder("（").append(batch.size()).append(" 个后台任务已结束）\n");
        for (org.jackhuang.hmcl.ai.tools.AiJobManager.Job job : batch) {
            String status = job.getStatus() == org.jackhuang.hmcl.ai.tools.AiJobManager.Status.SUCCEEDED
                    ? "已完成" : "失败";
            sb.append("#").append(job.getId()).append("「").append(job.getLabel()).append("」")
                    .append(status);
            String detail = jobDetail(job, 400);
            if (!detail.isEmpty()) {
                sb.append("：").append(detail.replace('\n', ' '));
            }
            sb.append('\n');
        }
        sb.append("请据此一次性继续，不要逐个复述。");
        return sb.toString();
    }

    /// The job's result output (or error), trimmed to {@code max} chars.
    private static String jobDetail(org.jackhuang.hmcl.ai.tools.AiJobManager.Job job, int max) {
        org.jackhuang.hmcl.ai.tools.ToolResult result = job.getResult();
        String detail = result != null && result.getOutput() != null && !result.getOutput().isEmpty()
                ? result.getOutput()
                : (job.getError() != null ? job.getError() : "");
        return detail.length() > max ? detail.substring(0, max) + "…" : detail;
    }

    /// Rebuilds the live background-tasks panel above the composer: one row per RUNNING job with its
    /// label + a cancel button. Hidden when nothing is running. Always called on the FX thread.
    private void refreshJobsPane() {
        if (jobsPane == null) {
            return;
        }
        AiSession current = sessionStore.getCurrentSession();
        java.util.List<org.jackhuang.hmcl.ai.tools.AiJobManager.Job> jobList = current != null
                ? org.jackhuang.hmcl.ai.tools.AiJobManager.getInstance().listBySession(current.getId())
                : java.util.Collections.emptyList();

        // Group by tool name (category): track running/total per category, collect running ids to cancel.
        java.util.LinkedHashMap<String, int[]> stats = new java.util.LinkedHashMap<>();          // cat -> [running,total]
        java.util.LinkedHashMap<String, java.util.List<String>> runningIds = new java.util.LinkedHashMap<>();
        int totalRunning = 0;
        for (org.jackhuang.hmcl.ai.tools.AiJobManager.Job job : jobList) {
            String cat = job.getToolName();
            int[] s = stats.computeIfAbsent(cat, k -> new int[2]);
            s[1]++;
            if (job.getStatus() == org.jackhuang.hmcl.ai.tools.AiJobManager.Status.RUNNING) {
                s[0]++;
                totalRunning++;
                runningIds.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(job.getId());
            }
        }

        jobsListContainer.getChildren().clear();
        for (java.util.Map.Entry<String, int[]> e : stats.entrySet()) {
            int run = e.getValue()[0], total = e.getValue()[1];
            if (run == 0) {
                continue;   // only show categories with active work
            }
            Label name = new Label(e.getKey());
            name.getStyleClass().add("ai-job-cat");
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            // Append a suffix so "2/10" reads as "2 of 10 running", not a countdown — mirrors
            // JobProgressBadge's "done/total 已完成" pattern for the same run/total ambiguity.
            Label count = new Label(run + "/" + total + " 运行中");
            count.getStyleClass().add("ai-job-count");
            JFXButton cancelBtn = new JFXButton("取消");
            cancelBtn.getStyleClass().add("ai-job-cancel-btn");
            java.util.List<String> ids = runningIds.getOrDefault(e.getKey(), java.util.Collections.emptyList());
            cancelBtn.setOnAction(ev -> ids.forEach(id -> org.jackhuang.hmcl.ai.tools.AiJobManager.getInstance().cancel(id)));
            HBox row = new HBox(8, name, count, cancelBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("ai-job-row");
            jobsListContainer.getChildren().add(row);
        }

        boolean any = totalRunning > 0;
        jobsPane.setVisible(any);
        jobsPane.setManaged(any);
        jobsCountLabel.setText(totalRunning + " 运行中");
        if (!any) {
            // Nothing running → collapse and reset so the next busy spell starts compact.
            jobsExpanded = false;
            if (jobsAnimation != null) jobsAnimation.stop();
            setJobsListHeightLimit(0);
            jobsListContainer.setManaged(false);
            jobsListContainer.setVisible(false);
            jobsToggleIcon.setRotate(0);
        } else if (jobsExpanded && jobsListContainer.isManaged()) {
            // Keep the unfurled height in sync as jobs come and go. prefHeightProperty is NEVER
            // pinned (see setJobsListHeightLimit), so prefHeight(w) always reflects the container's
            // TRUE current content height here, not a stale value from an earlier animation/limit.
            Platform.runLater(() -> {
                double w = jobsListContainer.getWidth() > 0 ? jobsListContainer.getWidth() : jobsPane.getWidth();
                setJobsListHeightLimit(jobsListContainer.prefHeight(w));
            });
        }
    }

    /// Sets ONLY min/max height on the jobs list container — deliberately NOT
    /// {@link FXUtils#setLimitHeight}, which also pins prefHeight to the same literal. Pinning
    /// prefHeight here was the root cause of the "面板打开后看不见内容" bug: once pinned, later
    /// {@code prefHeight(width)} queries return that STALE literal instead of recomputing from the
    /// container's actual children, so a reopen after content changed could animate to a stale
    /// (often zero) height. Leaving prefHeight at {@code Region.USE_COMPUTED_SIZE} keeps it always
    /// truthful.
    private void setJobsListHeightLimit(double height) {
        jobsListContainer.setMinHeight(height);
        jobsListContainer.setMaxHeight(height);
    }

    /// Toggles the background-tasks pull-up open/closed with a height + chevron animation.
    private void toggleJobsPane() {
        jobsExpanded = !jobsExpanded;
        if (jobsAnimation != null) {
            jobsAnimation.stop();
        }
        if (jobsExpanded) {
            jobsListContainer.setManaged(true);
            jobsListContainer.setVisible(true);
            Platform.runLater(() -> {
                jobsListContainer.applyCss();
                double w = jobsListContainer.getWidth() > 0 ? jobsListContainer.getWidth() : jobsPane.getWidth();
                animateJobsList(jobsListContainer.prefHeight(w), -180);
            });
        } else {
            animateJobsList(0, 0);
        }
    }

    private void animateJobsList(double targetHeight, double iconRotate) {
        // Deliberately tweens ONLY min/maxHeight — never prefHeightProperty (see
        // setJobsListHeightLimit's javadoc for why pinning it caused the container to go blank).
        javafx.animation.Interpolator ease = javafx.animation.Interpolator.EASE_BOTH;
        jobsAnimation = new Timeline(new KeyFrame(javafx.util.Duration.millis(180),
                new javafx.animation.KeyValue(jobsListContainer.minHeightProperty(), targetHeight, ease),
                new javafx.animation.KeyValue(jobsListContainer.maxHeightProperty(), targetHeight, ease),
                new javafx.animation.KeyValue(jobsToggleIcon.rotateProperty(), iconRotate, ease)));
        jobsAnimation.setOnFinished(e -> {
            if (!jobsExpanded) {
                jobsListContainer.setManaged(false);
                jobsListContainer.setVisible(false);
            }
        });
        jobsAnimation.play();
    }

    /// Entry point for other parts of the launcher (e.g. the game crash window) and the
    /// background-job auto-continue to hand the AI a prompt. The model sees a normal user turn,
    /// but the UI renders a neutral event pill — and the composer is left alone (this used to
    /// overwrite whatever draft the user was typing and send it away with the prompt).
    /// Runs on the FX thread; ignored if a response is already streaming.
    public void submitExternalPrompt(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            if (isStreaming()) {
                // Queue instead of silently dropping (bug 7.6): exitStreamingState() delivers the
                // next queued prompt once the in-flight turn ends.
                pendingExternalPrompts.addLast(text);
                Controllers.showToast("AI 正在回复，你的诊断请求已排队，完成后自动发送"); // TODO(i18n)
                return;
            }
            sendText(text, LlmMessage.KIND_EVENT);
        });
    }

    /// Shows the one-time test-phase risk notice (forced 5s countdown). On acknowledgement the
    /// preference is persisted so it never shows again. No external project names in the copy.
    /// {@code next} runs after acknowledgement — or immediately when the notice was already
    /// accepted — so a follow-up dialog (privacy consent) is CHAINED instead of stacked.
    private void showAiRiskNotice(Runnable next) {
        if (aiSettings.isAiRiskNoticeAccepted()) {
            next.run();
            return;
        }
        String text = "HMCL-AE 的 AI 助手目前处于测试阶段。\n\n"
                + "它可以代你执行下载、安装、修改与删除文件、编辑甚至删除存档等操作。"
                + "请务必对重要数据（尤其是存档）先做好备份，并在每次确认弹窗里看清要执行的操作再放行。\n\n"
                + "测试阶段可能出现错误或意外结果，请自行评估并承担风险。";
        Controllers.confirmWithCountdown(text, "AI 助手 · 测试阶段须知", 5,
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING,
                () -> {
                    aiSettings.setAiRiskNoticeAccepted(true);
                    persistAiSettings();
                    next.run();
                },
                null);
    }

    /// After the first user+assistant exchange, asks the model for a concise title (one cheap
    /// non-streaming call) and replaces the naive first-message truncation. No-op if disabled,
    /// not the first exchange, or the model returns nothing.
    private void maybeAutoTitle(ChatAgent agent, AiSession session) {
        if (agent == null || session == null || !aiSettings.isAutoTitleEnabled()) return;
        java.util.List<org.jackhuang.hmcl.ai.llm.LlmMessage> msgs = session.getMessages();
        if (msgs.size() != 2) return; // only right after the very first user+assistant exchange
        String firstUser = null, firstAssistant = null;
        for (org.jackhuang.hmcl.ai.llm.LlmMessage m : msgs) {
            if ("user".equals(m.getRole()) && firstUser == null) firstUser = m.getContent();
            else if ("assistant".equals(m.getRole()) && firstAssistant == null) firstAssistant = m.getContent();
        }
        if (firstUser == null || firstUser.isBlank()) return;
        agent.suggestTitle(firstUser, firstAssistant).thenAccept(title -> Platform.runLater(() -> {
            if (title == null || title.isBlank()) return;
            session.setTitle(title);
            if (sessionStore.getCurrentSession() == session) updateHeader(session);
            refreshSessionList();
            persistStore();
        }));
    }

    /// Switches the send button into Stop mode while a response streams.
    private void enterStreamingState() {
        updateSendButtonMode();
        refreshSessionList(); // show the "生成中…" indicator on the streaming session's row
    }

    /// Restores the send button after a response finishes or is stopped.
    private void exitStreamingState() {
        currentResponse = null;
        streamSessionId = null;
        // Agents whose eviction was deferred mid-stream (settings changed while their turn ran —
        // see clearAgentCache) are idle now; evict them so the NEXT turn builds a fresh agent
        // from the new settings.
        if (!deferredEvictions.isEmpty()) {
            for (String id : new java.util.ArrayList<>(deferredEvictions)) {
                evictAgent(id);
            }
            deferredEvictions.clear();
        }
        updateSendButtonMode();
        refreshSessionList(); // clear the "生成中…" indicator
        // Restore any tools plan mode disabled for the just-finished response.
        restorePlanGating();
        // Deliver ONE queued follow-up now that we're idle (it starts its own turn, whose own
        // exitStreamingState naturally serializes the rest). A user-facing external prompt
        // (diagnostics hand-off, bug 7.6) outranks a background-job receipt, so the two queues
        // are drained mutually exclusively with the external one first.
        if (!pendingExternalPrompts.isEmpty()) {
            String nextPrompt = pendingExternalPrompts.pollFirst();
            Platform.runLater(() -> submitExternalPrompt(nextPrompt));
        } else if (!pendingCompletions.isEmpty()) {
            org.jackhuang.hmcl.ai.tools.AiJobManager.Job next = pendingCompletions.poll();
            Platform.runLater(() -> onBackgroundJobComplete(next));
        }
    }

    /// Stops the in-flight response: invalidates its callbacks, best-effort aborts the
    /// request, finalizes whatever was streamed so far, and resets the button.
    /// {@link org.jackhuang.hmcl.ui.ai.tools.AskTool.AskUiHandler} implementation: called on the
    /// agent's background thread, it renders the question panel on the FX thread and returns a
    /// future the background thread blocks on (bounded by {@link org.jackhuang.hmcl.ui.ai.tools.AskTool}'s
    /// own timeout). Completed when the user confirms; cancelled by {@link #cancelActiveAsk()} on
    /// stop / session switch, OR by {@code AskTool} itself if the wait times out with nobody
    /// answering.
    private java.util.concurrent.CompletableFuture<java.util.List<String>> showAskPanel(
            java.util.List<org.jackhuang.hmcl.ui.ai.tools.AskTool.Question> questions) {
        java.util.concurrent.CompletableFuture<java.util.List<String>> future =
                new java.util.concurrent.CompletableFuture<>();
        // The confirm button and cancelActiveAsk() both dismiss the panel themselves before
        // completing the future — but AskTool can ALSO complete (cancel) this future entirely on
        // its own, from the agent's background thread, when its wait times out with nobody
        // answering. That path has no other way to reach the panel, so watch the future here and
        // dismiss it ourselves whenever it finishes and this is still the active ask; the
        // `activeAsk == future` guard makes this a no-op for the two paths that already dismissed
        // it themselves (they null out activeAsk first).
        future.whenComplete((answers, ex) -> Platform.runLater(() -> {
            if (activeAsk == future) {
                activeAsk = null;
                hideAskPanel();
            }
        }));
        Platform.runLater(() -> {
            cancelActiveAsk();
            activeAsk = future;
            askPanel.getChildren().clear();

            // If the asking turn belongs to a session OTHER than the one on screen, prefix the
            // panel title with that session's name — same treatment as showConfirmDialog's owner
            // labelling, and for the same reason: an unmarked question reads as being about
            // whatever the user is currently looking at (bug 7.8).
            String askSourcePrefix = "";
            AiSession displayed = sessionStore.getCurrentSession();
            if (streamSessionId != null && (displayed == null || !streamSessionId.equals(displayed.getId()))) {
                AiSession askOwner = sessionStore.getSession(streamSessionId);
                String ownerTitle = (askOwner != null && askOwner.getTitle() != null && !askOwner.getTitle().isBlank())
                        ? askOwner.getTitle() : streamSessionId;
                askSourcePrefix = "[" + ownerTitle + "] "; // TODO(i18n)
            }
            final String titlePrefix = askSourcePrefix;

            Label title = new Label(titlePrefix + i18n("ai.ask.title"));
            title.getStyleClass().add("ai-ask-title");

            int n = questions.size();
            // Build every question's control once up front, so answers persist while the user
            // navigates back and forth between steps (only the displayed question changes).
            java.util.List<VBox> qBoxes = new java.util.ArrayList<>(n);
            java.util.List<java.util.function.Supplier<String>> collectors = new java.util.ArrayList<>(n);
            for (org.jackhuang.hmcl.ui.ai.tools.AskTool.Question q : questions) {
                VBox qBox = new VBox(4);
                qBox.getStyleClass().add("ai-ask-question");
                Label qLabel = new Label(q.question());
                qLabel.setWrapText(true);
                qLabel.getStyleClass().add("ai-ask-q-label");
                qBox.getChildren().add(qLabel);
                collectors.add(buildAskControl(q, qBox));
                qBoxes.add(qBox);
            }

            // One-question-at-a-time wizard: a body that shows the current step + a nav bar.
            VBox body = new VBox();
            body.getStyleClass().add("ai-ask-body");

            JFXButton back = FXUtils.newBorderButton(i18n("ai.ask.back"));
            JFXButton next = FXUtils.newBorderButton(i18n("ai.ask.next"));
            JFXButton confirm = FXUtils.newRaisedButton(i18n("ai.ask.confirm"));
            // While the question panel is up, Enter must submit (the confirm button) and must
            // NOT trigger the Send/Stop button — pressing Enter used to fire Stop (the default
            // button during streaming) and cancel the whole response.
            confirm.setDefaultButton(true);
            sendBtn.setDefaultButton(false);
            Region navSpacer = new Region();
            HBox.setHgrow(navSpacer, Priority.ALWAYS);
            HBox nav = new HBox(8, back, navSpacer, next, confirm);
            nav.getStyleClass().add("ai-ask-nav-bar");

            int[] current = {0};
            Runnable render = () -> {
                int i = current[0];
                body.getChildren().setAll(qBoxes.get(i));
                title.setText(titlePrefix + (n > 1
                        ? i18n("ai.ask.title") + "  (" + (i + 1) + "/" + n + ")" : i18n("ai.ask.title")));
                back.setVisible(i > 0);
                back.setManaged(i > 0);
                boolean last = i == n - 1;
                next.setVisible(!last);
                next.setManaged(!last);
                confirm.setVisible(last);
                confirm.setManaged(last);
            };
            back.setOnAction(e -> { if (current[0] > 0) { current[0]--; render.run(); } });
            next.setOnAction(e -> { if (current[0] < n - 1) { current[0]++; render.run(); } });
            confirm.setOnAction(e -> {
                if (activeAsk != future) return;
                java.util.List<String> answers = new java.util.ArrayList<>();
                for (java.util.function.Supplier<String> c : collectors) answers.add(c.get());
                activeAsk = null;
                hideAskPanel();
                future.complete(answers);
            });

            askPanel.getChildren().addAll(title, body, nav);
            render.run();
            askPanel.setManaged(true);
            askPanel.setVisible(true);
            scrollToBottom();
        });
        return future;
    }

    /// Builds the input control(s) for one question into {@code qBox} and returns a supplier that
    /// reads the user's answer as a string (multi-choice answers are comma-joined).
    private java.util.function.Supplier<String> buildAskControl(
            org.jackhuang.hmcl.ui.ai.tools.AskTool.Question q, VBox qBox) {
        // Hard rule: EVERY question is options + an always-present "自定义" choice; the custom
        // text field stays hidden until that choice is picked. No standalone free-text question.
        boolean multi = "multi".equals(q.type());
        JFXTextField customField = new JFXTextField();
        customField.setPromptText(i18n("ai.ask.custom_hint"));
        customField.getStyleClass().add("ai-ask-custom-field");
        customField.setVisible(false);
        customField.setManaged(false);

        if (multi) {
            java.util.List<JFXCheckBox> boxes = new java.util.ArrayList<>();
            for (String opt : q.options()) {
                JFXCheckBox cb = new JFXCheckBox(opt);
                cb.getStyleClass().add("ai-ask-option");
                qBox.getChildren().add(cb);
                boxes.add(cb);
            }
            JFXCheckBox customCb = new JFXCheckBox(i18n("ai.ask.custom"));
            customCb.getStyleClass().add("ai-ask-option");
            customCb.selectedProperty().addListener((o, ov, nv) -> {
                customField.setVisible(nv);
                customField.setManaged(nv);
                if (nv) customField.requestFocus();
            });
            qBox.getChildren().addAll(customCb, customField);
            return () -> {
                java.util.List<String> sel = new java.util.ArrayList<>();
                for (JFXCheckBox cb : boxes) if (cb.isSelected()) sel.add(cb.getText());
                if (customCb.isSelected() && customField.getText() != null && !customField.getText().isBlank()) {
                    sel.add(customField.getText().trim());
                }
                return String.join(", ", sel);
            };
        } else {
            javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
            for (String opt : q.options()) {
                JFXRadioButton rb = new JFXRadioButton(opt);
                rb.setToggleGroup(group);
                rb.getStyleClass().add("ai-ask-option");
                qBox.getChildren().add(rb);
            }
            JFXRadioButton customRb = new JFXRadioButton(i18n("ai.ask.custom"));
            customRb.setToggleGroup(group);
            customRb.getStyleClass().add("ai-ask-option");
            customRb.selectedProperty().addListener((o, ov, nv) -> {
                customField.setVisible(nv);
                customField.setManaged(nv);
                if (nv) customField.requestFocus();
            });
            qBox.getChildren().addAll(customRb, customField);
            // Default to the first concrete option, or the custom choice if none were given.
            if (!group.getToggles().isEmpty()) group.selectToggle(group.getToggles().get(0));
            return () -> {
                javafx.scene.control.Toggle sel = group.getSelectedToggle();
                if (sel == null) return "";
                if (sel == customRb) {
                    return customField.getText() == null ? "" : customField.getText().trim();
                }
                return ((JFXRadioButton) sel).getText();
            };
        }
    }

    private void hideAskPanel() {
        askPanel.getChildren().clear();
        askPanel.setVisible(false);
        askPanel.setManaged(false);
        sendBtn.setDefaultButton(true); // restore Enter→Send now the question panel is gone
    }

    /// Cancels any pending `ask` (so AskTool's blocked background thread unblocks with a failure)
    /// and hides the panel. Called on stop and on session switch.
    private void cancelActiveAsk() {
        java.util.concurrent.CompletableFuture<java.util.List<String>> f = activeAsk;
        activeAsk = null;
        if (f != null && !f.isDone()) {
            f.completeExceptionally(new java.util.concurrent.CancellationException("cancelled"));
        }
        hideAskPanel();
    }

    private void stopResponse() {
        responseGeneration++; // invalidate any further callbacks from the stopped response
        if (currentCancelled != null) {
            currentCancelled.set(true); // tell the agent NOT to persist the dropped reply
        }
        if (currentStreamAgent != null) {
            // Persist the interrupted partial (with its marker) RIGHT NOW, so it can't race with
            // the user's next message — previously it was written whenever the abandoned HTTP
            // stream happened to end, which could insert it out of order or after a restart.
            currentStreamAgent.persistInterrupted();
            currentStreamAgent = null;
        }
        cancelActiveAsk();
        // Mirrors cancelActiveAsk() above for the exact same reason: a pending dangerous/critical
        // confirm dialog blocks the STREAMING CLIENT'S OWN callback thread, not the executor thread
        // wrapped by currentResponse, so future.cancel(true) below never unblocks it on its own —
        // without this call the dialog (and its blocked agent thread) would keep running for up to
        // the full 120s/180s timeout after the user pressed Stop, and could still be answered once
        // the button frees up and the user starts a brand-new turn. See the activeConfirm field doc.
        cancelActiveConfirm();
        java.util.concurrent.CompletableFuture<Void> future = currentResponse;
        if (future != null) {
            future.cancel(true);
        }
        Label bubble = streamingBubble;
        streamingBubble = null;
        if (bubble != null) {
            String partial = bubble.getText();
            if (partial == null || partial.isBlank()) {
                bubble.setText(i18n("ai.stopped"));
            } else {
                finalizeAiBubble(bubble, partial, null);
            }
        }
        exitStreamingState();
        setStatus(null);
        // persistInterrupted() above already wrote the partial into the session synchronously,
        // so this single save puts it on disk — no need to hope a later pulse catches it.
        persistStore();
    }

    private void showAiError(@Nullable Label aiBubble, StringBuilder fullContent, LlmException error,
                             @Nullable AiSession owner, int generation) {
        Platform.runLater(() -> {
            // Re-check on the FX thread: the caller's own check ran on a background (langchain4j
            // callback) thread and may be stale by the time this runnable actually executes — the
            // user could have pressed Stop (or started a NEW turn) in between, bumping
            // responseGeneration again. Every other terminal callback in this same streaming setup
            // (onToken/onReasoningToken/onToolActivity/onToolResult/onComplete) already re-checks
            // its captured `generation` as the first statement inside its own Platform.runLater —
            // this mirrors that same pattern for the error path.
            if (generation != responseGeneration) {
                return;
            }
            streamingBubble = null;
            exitStreamingState();
            // If the failed stream belongs to a session the user is no longer viewing, release the
            // streaming state (above) but don't render the error into the other session's view.
            if (owner != null && sessionStore.getCurrentSession() != owner) {
                persistStore();
                return;
            }
            // The error may arrive between turns (current text segment already finalized, or none
            // yet); ensure there is a bubble to attach the partial text + error styling to.
            Label bubble = aiBubble;
            if (bubble == null) {
                bubble = createAiBubble("");
            }
            if (fullContent.length() > 0) {
                bubble.setText(fullContent.toString());
            }
            final Label target = bubble;
            int statusCode = error.getStatusCode();
            String message;
            if (statusCode == 401) {
                message = i18n("ai.error.auth_failed");
            } else if (statusCode == 0) {
                if (error.getCause() instanceof HttpTimeoutException) {
                    message = i18n("ai.error.timeout");
                } else {
                    message = i18n("ai.error.connection_failed");
                }
            } else {
                message = i18n("ai.error.api_error", error.getMessage());
            }
            setStatus(message);
            // A13: the error is a structured panel (red icon + "回复失败" title + message body)
            // appended below the partial text inside the same bubble column, instead of tinting
            // the partial-content label itself.
            if (fullContent.length() == 0) {
                // No partial text arrived — hide the empty label so it can't render as a bare pill.
                target.setVisible(false);
                target.setManaged(false);
            }
            Label errTitle = new Label("回复失败"); // TODO(i18n)
            errTitle.getStyleClass().add("ai-caption-bold"); // rule lands with the B7 css rewrite
            Label errBody = new Label(message);
            errBody.setWrapText(true);
            VBox errCol = new VBox(2, errTitle, errBody);
            HBox errorBox = new HBox(8, SVG.ERROR.createIcon(16), errCol);
            errorBox.setAlignment(Pos.TOP_LEFT);
            errorBox.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
            errorBox.getStyleClass().addAll("ai-bubble", "ai-bubble-error");
            if (target.getParent() instanceof VBox bubbleBox) {
                bubbleBox.getChildren().add(errorBox);
            } else {
                messageList.getChildren().add(wrapBubble(errorBox, Pos.CENTER_LEFT));
            }
            // Offer a one-click retry of the failed turn (transient 429 / timeout / connection
            // drop) — a native border button in its own aligned row (2-13/7.11: it used to be a
            // bare unpositioned JFXButton behind an always-true instanceof condition).
            JFXButton retryBtn = FXUtils.newBorderButton("重试"); // TODO(i18n)
            retryBtn.setGraphic(SVG.REFRESH.createIcon(14));
            HBox retryRow = new HBox(retryBtn);
            retryRow.setAlignment(Pos.CENTER_LEFT);
            retryRow.setPadding(new Insets(0, 16, 6, 16));
            retryBtn.setOnAction(e -> {
                messageList.getChildren().remove(retryRow);
                retryLastTurn();
            });
            messageList.getChildren().add(retryRow);
            scrollToBottom();
            persistStore();
        });
    }

    /// Re-sends the last user turn after a failed response (the failed assistant reply was never
    /// persisted, so the last stored message is the user turn that triggered it).
    private void retryLastTurn() {
        AiSession cur = sessionStore.getCurrentSession();
        if (cur == null) {
            return;
        }
        java.util.List<org.jackhuang.hmcl.ai.llm.LlmMessage> msgs = cur.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).getRole())) {
                resendUserMessage(i, msgs.get(i).getContent());
                return;
            }
        }
    }

    private void setStatus(@Nullable String text) {
        typingTimeline.stop();
        if (text == null) {
            statusLabel.setVisible(false);
        } else if (text.isEmpty()) {
            typingBaseText = i18n("ai.analyzing");
            statusLabel.setText(typingBaseText);
            statusLabel.setVisible(true);
            typingTimeline.play();
        } else {
            statusLabel.setText(text);
            statusLabel.setVisible(true);
        }
    }

    /// The single UI entry point for saving the session store. Asynchronous since 7c: the full
    /// JSON serialisation + write used to run right here on the FX thread on every hot-path call
    /// (each send/complete/stop/switch). saveAsync coalesces bursts onto one background writer and
    /// logs its own failures; a shutdown hook registered in the constructor flushes the final
    /// synchronous snapshot on normal exit.
    private void persistStore() {
        sessionStore.saveAsync();
    }

    /// Mirrors {@link #persistStore()} for {@code aiSettings}: a silently swallowed save here
    /// left the user believing a settings/model change had stuck when it only lived in memory.
    private void persistAiSettings() {
        try {
            aiSettings.save();
        } catch (Exception e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save ai settings", e);
        }
    }

    /// Builds the small role-name label shown above a bubble.
    private static Label bubbleName(String name, boolean alignRight) {
        Label nameLabel = new Label(name);
        nameLabel.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        if (alignRight) {
            nameLabel.setAlignment(Pos.CENTER_RIGHT);
        }
        nameLabel.getStyleClass().add("ai-bubble-name");
        return nameLabel;
    }

    /// Builds a bubble's text content: a colour-emoji TextFlow when colour emoji is enabled
    /// and the text contains emoji, otherwise a plain wrapped Label. Both carry the given
    /// bubble style classes.
    private static Node bubbleTextNode(@Nullable String text, String... styleClasses) {
        if (text == null) {
            text = ""; // EmojiImages.toNodes below does not tolerate null (P1)
        }
        if (EmojiImages.isEnabled() && EmojiImages.containsEmoji(text)) {
            javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
            flow.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
            flow.getChildren().addAll(EmojiImages.toNodes(text, 14));
            flow.getStyleClass().addAll(styleClasses);
            return flow;
        }
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    /// Wraps bubble content in a horizontally-aligned row with consistent padding.
    /// The padding lives HERE, not in CSS: the old `.ai-bubble-wrapper { -fx-padding: 4 16 4 16 }`
    /// rule silently overrode whatever the code set (author CSS beats code setters) — the rule is
    /// gone and this is now the single source of the wrapper padding (VS §4.3-1).
    private static HBox wrapBubble(Node content, Pos align) {
        HBox wrapper = new HBox(content);
        wrapper.setAlignment(align);
        wrapper.setPadding(new Insets(4, 16, 4, 16));
        wrapper.getStyleClass().add("ai-bubble-wrapper");
        // Rasterise each finished bubble so scrolling a long conversation reuses the cached
        // bitmap (a pure translate) instead of repainting every TextFlow — the default cache
        // hint re-renders at quality on real transforms, so there is no blur on resize.
        wrapper.setCache(true);
        return wrapper;
    }

    /// Wraps an inline subordinate card (reasoning / tool / tool group) in a left-aligned row
    /// mirroring {@link #wrapBubble}, with two deliberate differences that express the card's
    /// subordination to the adjacent AI answer (VS §3.3): the card grows to fill the column
    /// width (up to its own 704px max, so all three card types render equally wide instead of
    /// shrinking to content), and the left inset is 16px deeper than the bubble column.
    /// Deliberately NO `.ai-bubble-wrapper` style class — that class is the marker
    /// {@link #setWrapperCache} climbs for, and card wrappers manage their own cache here.
    private static HBox wrapCard(Region card) {
        HBox wrapper = new HBox(card);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(2, 16, 2, 32));
        HBox.setHgrow(card, Priority.ALWAYS);
        wrapper.setCache(true); // same rasterise-once treatment as wrapBubble
        return wrapper;
    }

    /// Toggles the bitmap cache of the `.ai-bubble-wrapper` row enclosing {@code bubble}. While a
    /// bubble is still STREAMING, every appended chunk invalidates the cached bitmap — paying the
    /// rasterisation cost per frame for nothing — so the streaming path switches its wrapper's
    /// cache off and {@link #finalizeAiBubble} switches it back on for cheap scrolling (P12).
    private static void setWrapperCache(Node bubble, boolean cache) {
        for (Node n = bubble; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("ai-bubble-wrapper")) {
                n.setCache(cache);
                return;
            }
        }
    }

    private void addUserBubble(String text) {
        addUserBubble(text, false);
    }

    private void addUserBubble(String text, boolean quiet) {
        Node content = bubbleTextNode(text, "ai-bubble", "ai-bubble-user");

        VBox bubbleBox = new VBox(2, bubbleName(chatSettings.userName, true), content);
        bubbleBox.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        bubbleBox.setAlignment(Pos.CENTER_RIGHT);

        HBox wrapper = wrapBubble(bubbleBox, Pos.CENTER_RIGHT);
        // A user bubble opens a new turn: an extra 8px top margin over the 4px list spacing
        // yields the 12px turn separation of the VS §1.4 rhythm. attachMessageActions transfers
        // this margin onto the `.ai-msg-block` when it fuses the wrapper with its action row.
        VBox.setMargin(wrapper, new Insets(8, 0, 0, 0));
        messageList.getChildren().add(wrapper);
        if (!quiet) {
            updateEmptyState();
            scrollToBottom();
        }
    }

    private Label createAiBubble(String text) {
        return createAiBubble(text, null);
    }

    private Label createAiBubble(String text, @Nullable LlmUsage usage) {
        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        content.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");

        VBox bubbleBox = new VBox(2, bubbleName("AI", false), content);
        bubbleBox.setMaxWidth(AI_BUBBLE_MAX_WIDTH);

        // When markdown is available, render it as the bubble and keep the plain
        // Label hidden behind it as the streaming text target.
        if (!text.isEmpty() && chatSettings.markdownRender) {
            MarkdownMessageView mdView = MarkdownMessageView.create(text, AI_BUBBLE_MAX_WIDTH - 10);
            if (mdView != null) {
                content.setVisible(false);
                content.setManaged(false);
                mdView.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");
                bubbleBox.getChildren().add(bubbleBox.getChildren().indexOf(content), mdView);
            }
        }

        Node usageFooter = buildUsageFooter(text, usage);
        if (usageFooter != null) {
            bubbleBox.getChildren().add(usageFooter);
        }

        messageList.getChildren().add(wrapBubble(bubbleBox, Pos.CENTER_LEFT));
        updateEmptyState();
        scrollToBottom();
        return content;
    }

    /// Finalizes a streamed AI bubble once the response completes: swaps the plain
    /// streaming Label for a Markdown view (when enabled) and appends the token-usage
    /// footer. Mirrors {@link #createAiBubble(String, LlmUsage)} so a just-streamed
    /// bubble matches one rendered from a reloaded session.
    private void finalizeAiBubble(Label aiBubble, String completeText, @Nullable LlmUsage usage) {
        finalizeAiBubble(aiBubble, completeText, usage, true);
    }

    /// @param withFooter whether to append the token-usage footer; false for intermediate
    ///                   text segments of a multi-turn (tool-using) response.
    private void finalizeAiBubble(Label aiBubble, String completeText, @Nullable LlmUsage usage, boolean withFooter) {
        if (!(aiBubble.getParent() instanceof VBox bubbleBox)) {
            return;
        }
        if (!completeText.isEmpty() && chatSettings.markdownRender) {
            MarkdownMessageView mdView = MarkdownMessageView.create(completeText, AI_BUBBLE_MAX_WIDTH - 10);
            if (mdView != null) {
                aiBubble.setVisible(false);
                aiBubble.setManaged(false);
                mdView.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");
                bubbleBox.getChildren().add(bubbleBox.getChildren().indexOf(aiBubble), mdView);
            } else {
                aiBubble.setText(completeText);
            }
        }
        if (withFooter) {
            Node footer = buildUsageFooter(completeText, usage);
            if (footer != null) {
                bubbleBox.getChildren().add(footer);
            }
        }
        // The bubble is done growing — re-enable the wrapper's bitmap cache the streaming path
        // switched off (see setWrapperCache).
        setWrapperCache(aiBubble, true);
    }

    /// Builds the per-message token-usage footer shown under an AI bubble.
    ///
    /// Returns `null` when usage display is disabled, or when there is neither
    /// provider-reported usage nor an estimate to show. When the provider did
    /// not report usage and estimation is enabled, the completion tokens are
    /// estimated from the response text.
    @Nullable
    private Node buildUsageFooter(String aiText, @Nullable LlmUsage usage) {
        if (!chatSettings.showUsage) {
            return null;
        }
        LlmUsage effective = usage;
        if (effective == null || !effective.hasData()) {
            if (!chatSettings.estimateTokens || aiText.isEmpty()) {
                return null;
            }
            effective = LlmUsage.estimate("", aiText);
        }

        Label footer = new Label(formatUsage(effective));
        footer.getStyleClass().add("ai-usage-footer");
        return footer;
    }

    /// Formats a usage instance into a localized footer string, appending the
    /// estimated cost when cost display is enabled and the active profile has pricing.
    /// Shared cross-session daily spend tracker (backs the cost cap + warning). Lazily bound to the
    /// config file so the chat page and the settings page act on one instance.
    private static SpendTracker spendTrackerInstance;

    static synchronized SpendTracker spendTracker() {
        if (spendTrackerInstance == null) {
            spendTrackerInstance = new SpendTracker(
                    SettingsManager.localConfigDirectory().resolve("ai-spend.json"));
        }
        return spendTrackerInstance;
    }

    private boolean spendWarned80 = false;

    /// Estimated USD cost of one response from its token usage and the active model's pricing, or 0
    /// when usage or pricing is unavailable.
    private double estimateCost(@Nullable LlmUsage usage) {
        if (usage == null || !usage.hasData()) {
            return 0.0;
        }
        AiProviderProfile active = aiSettings.findSelectedProfile();
        String modelId = active != null ? active.getDefaultModelId() : null;
        AiModelEntry model = (active != null && modelId != null) ? active.getModel(modelId) : null;
        return model != null && model.hasPricing()
                ? model.computeCost(usage.getPromptTokens(), usage.getCompletionTokens(), 0, 0)
                : 0.0;
    }

    /// Toasts once when today's spend crosses 80% of the daily limit; re-arms when a new day resets it.
    private void maybeWarnSpend() {
        double ratio = spendTracker().todayUsageRatio();
        if (ratio >= 0.8 && ratio < 1.0) {
            if (!spendWarned80) {
                spendWarned80 = true;
                Controllers.showToast("今日 AI 估算花费已用约 " + Math.round(ratio * 100)
                        + "%（上限 $" + String.format(java.util.Locale.ROOT, "%.2f",
                        spendTracker().getDailyLimitUsd()) + "）");
            }
        } else if (ratio < 0.8) {
            spendWarned80 = false;
        }
    }

    private String formatUsage(LlmUsage usage) {
        if (usage.isEstimated()) {
            return i18n("ai.usage.estimated", String.valueOf(usage.getTotalTokens()));
        }
        String text = i18n("ai.usage.detail",
                String.valueOf(usage.getTotalTokens()),
                String.valueOf(usage.getPromptTokens()),
                String.valueOf(usage.getCompletionTokens()));
        if (chatSettings.showCost) {
            double cost = estimateCost(usage);
            if (cost > 0) {
                text += i18n("ai.usage.cost", String.format(java.util.Locale.ROOT, "%.6f", cost));
            }
        }
        return text;
    }

    private void addSystemMessage(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-system");

        messageList.getChildren().add(wrapBubble(label, Pos.CENTER_LEFT));
        updateEmptyState();
        scrollToBottom();
    }

    /// Renders a synthetic event turn (background-job auto-continue, crash injection…) as a
    /// small centered pill — the model sees it as a user turn, but showing it as a user bubble
    /// confused people ("I never typed that") and its copy button yielded internal glue text.
    private void addEventPill(String text) {
        String full = text == null ? "" : text;
        int nl = full.indexOf('\n');
        String headline = (nl >= 0 ? full.substring(0, nl) : full).strip();
        if (headline.length() > 80) {
            headline = headline.substring(0, 80) + "…";
        }
        Label pill = new Label(headline);
        pill.getStyleClass().add("ai-event-pill");
        pill.setWrapText(true);
        pill.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        if (!full.equals(headline)) {
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(
                    full.length() > 1000 ? full.substring(0, 1000) + "…" : full);
            tip.setWrapText(true);
            tip.setMaxWidth(520);
            pill.setTooltip(tip);
        }
        HBox row = new HBox(pill);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(4, 16, 4, 16));
        messageList.getChildren().add(row);
        updateEmptyState();
        scrollToBottom();
    }

    /// Rebuilds a completed tool card from a persisted {@link LlmMessage.ToolPayload} when a
    /// session is (re)loaded, so history keeps showing what the AI actually did.
    private void addPersistedToolCard(@Nullable LlmMessage.ToolPayload payload) {
        if (payload == null || payload.name == null) {
            return;
        }
        ToolCard card = new ToolCard(payload.name);
        card.complete(payload.success, payload.resultText == null ? "" : payload.resultText);
        appendToolCard(card);
    }

    /// {@link org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoUiHandler}: renders the agent's
    /// latest TODO checklist as a pinned card above the messages. Called on the agent's
    /// background thread, so it marshals onto the FX thread.
    private void updateTodoCard(List<org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoItem> todos) {
        Platform.runLater(() -> {
            // todo_write only ever happens inside a streaming turn — if that turn's session is
            // NOT the one on screen, don't overwrite the visible session's checklist (bug 7.7).
            AiSession cur = sessionStore.getCurrentSession();
            if (streamSessionId != null && (cur == null || !streamSessionId.equals(cur.getId()))) {
                return;
            }
            todoCardContainer.getChildren().clear();
            if (todos == null || todos.isEmpty()) {
                hideTodoCard();
                return;
            }
            long done = todos.stream().filter(t -> "done".equals(t.status())).count();

            // Shared collapsible header ("任务清单 (n/m)"), same form language as the inline
            // reasoning/tool cards. The card is rebuilt on every todo_write, so the header/body
            // pair is rebuilt too — todoExpanded carries the expand state across rebuilds
            // (§B B7: the collapse capability must not regress).
            CollapseHeader header = new CollapseHeader("任务清单 (" + done + "/" + todos.size() + ")"); // TODO(i18n)

            VBox body = new VBox(4);
            body.getStyleClass().add("ai-todo-body");
            for (org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoItem t : todos) {
                String status = t.status();
                Label row = new Label(t.content());
                row.setWrapText(true);
                row.setMaxWidth(AI_BUBBLE_MAX_WIDTH - 40);
                row.setGraphicTextGap(6);
                // Status icon + themed colour classes replace the old ✓/▶/○ characters and the
                // hard-coded per-theme-hostile setStyle colours (CP §6-3 / VS §3.4 / C-07).
                switch (status) {
                    case "done" -> {
                        row.setGraphic(SVG.CHECK.createIcon(14));
                        row.getStyleClass().add("ai-todo-done");
                    }
                    case "in_progress" -> {
                        row.setGraphic(SVG.PLAY_ARROW.createIcon(14));
                        row.getStyleClass().add("ai-todo-in-progress");
                    }
                    default -> {
                        row.setGraphic(SVG.RADIO_BUTTON_UNCHECKED.createIcon(14));
                        row.getStyleClass().add("subtitle-label"); // pending: native muted text
                    }
                }
                body.getChildren().add(row);
            }
            body.visibleProperty().bind(header.expandedProperty());
            body.managedProperty().bind(header.expandedProperty());
            header.setExpanded(todoExpanded);
            header.expandedProperty().addListener((obs, was, is) -> todoExpanded = is);

            VBox card = new VBox(4, header, body);
            // Deliberately NOT .ai-tool-card (opaque background, meant for an in-conversation
            // card) — this pinned banner sits full-width above the messages like the jobs pane,
            // so it shares .ai-jobs-pane's translucent/borderless treatment, matching its sibling
            // rather than reading as a disconnected box ("边缘和界面相当割裂").
            card.getStyleClass().add("ai-jobs-pane");
            card.setPadding(new Insets(8, 12, 8, 12));

            todoCard = card;
            todoCardContainer.getChildren().add(card);
            todoCardContainer.setVisible(true);
            todoCardContainer.setManaged(true);
        });
    }

    /// Hides and clears the pinned TODO card (e.g. on session switch / clear).
    private void hideTodoCard() {
        todoCard = null;
        todoCardContainer.getChildren().clear();
        todoCardContainer.setVisible(false);
        todoCardContainer.setManaged(false);
    }

    /// A tool-call card shown inline in the conversation, in chronological order: it appears
    /// when the agent invokes a tool ("调用中") and is updated in place when the tool finishes
    /// ("已完成"/"失败"). The result text is collapsible — click the header to expand it.
    static final class ToolCard extends VBox {
        private final CollapseHeader header;
        private final Label result = new Label();
        private final String toolName;
        /// Set once complete() stores a non-blank result. Gates the result binding so clicking
        /// the header of a card with nothing to show keeps doing nothing (pre-CollapseHeader
        /// behavior), and the chevron only appears once there is something to expand.
        private final BooleanProperty hasResult = new SimpleBooleanProperty(false);

        /// Live progress UI (hidden until the first progress event, removed once the tool finishes).
        private final JFXProgressBar progressBar = new JFXProgressBar();
        private final Label progressLabel = new Label();
        private final VBox progressBox = new VBox(2, progressLabel, progressBar);
        private boolean finished = false;

        ToolCard(String toolName) {
            super(2);
            this.toolName = toolName;
            getStyleClass().add("ai-tool-card");
            setMaxWidth(AI_BUBBLE_MAX_WIDTH - 16); // 704 — unified subordinate-card width (VS §3.3)

            // Whole-row hot zone replaces the old "only the header Label is clickable" hitbox.
            // The chevron stays hidden until complete() delivers an expandable result, so a
            // running/plain card doesn't advertise a toggle it does not have.
            header = new CollapseHeader(i18n("ai.tool.calling", toolName));
            header.getTitleLabel().setWrapText(true);
            header.getChevron().setVisible(false);
            header.getChevron().setManaged(false);

            result.setWrapText(true);
            result.setMaxWidth(AI_BUBBLE_MAX_WIDTH - 40);
            result.getStyleClass().add("ai-tool-card-result");
            result.visibleProperty().bind(header.expandedProperty().and(hasResult));
            result.managedProperty().bind(result.visibleProperty());

            progressLabel.setWrapText(true);
            progressLabel.setMaxWidth(AI_BUBBLE_MAX_WIDTH - 40);
            progressLabel.getStyleClass().add("ai-tool-card-result");
            progressBar.setPrefWidth(AI_BUBBLE_MAX_WIDTH - 40);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBox.setVisible(false);
            progressBox.setManaged(false);

            getChildren().addAll(header, progressBox, result);
        }

        /// Applies a live progress update. {@code fraction < 0} renders an indeterminate bar.
        /// JavaFX thread only. No-op after the tool has finished.
        void updateProgress(double fraction, @Nullable String message) {
            if (finished) {
                return;
            }
            if (!progressBox.isVisible()) {
                progressBox.setVisible(true);
                progressBox.setManaged(true);
            }
            progressBar.setProgress(fraction < 0 || Double.isNaN(fraction) ? -1.0 : Math.min(fraction, 1.0));
            String pct = (fraction >= 0 && !Double.isNaN(fraction))
                    ? " " + Math.round(fraction * 100) + "%" : "";
            progressLabel.setText((message == null || message.isBlank() ? "" : message.strip()) + pct);
        }

        /// Updates the card once its tool finishes; stores the (collapsible) result text.
        void complete(boolean success, @Nullable String summary) {
            finished = true;
            progressBox.setVisible(false);
            progressBox.setManaged(false);
            header.getTitleLabel().setText(i18n(success ? "ai.tool.done" : "ai.tool.failed", toolName));
            getStyleClass().add(success ? "ai-tool-card-ok" : "ai-tool-card-fail");
            if (summary != null && !summary.isBlank()) {
                String text = summary.strip();
                // UI-side hard cap (BF 2-3): a Label with hundreds of KB stalls layout. The full
                // result is still persisted and visible in the ai-trace log; this only trims what
                // the collapsible card renders.
                if (text.length() > 4000) {
                    text = text.substring(0, 4000) + "\n…（结果过长，已截断显示，完整内容见 ai-trace 日志）"; // TODO(i18n)
                }
                result.setText(text);
                hasResult.set(true);
                // Freshly-completed cards start collapsed, exactly like the old click-to-reveal
                // Label — even if the whole-row hot zone was toggled while the tool was running.
                header.setExpanded(false);
                header.getChevron().setVisible(true);
                header.getChevron().setManaged(true);
            }
        }
    }

    /// Collapses a RUN of consecutive tool calls (no assistant text between them) into one card
    /// with a "已调用 N 个工具" summary header, so a turn that calls e.g. `instance` ten times in
    /// a row shows one collapsible row instead of ten separate cards stacked in the conversation
    /// ("连续的工具调用需要收纳在一起"). Individual {@link ToolCard}s are unchanged — they just
    /// live inside this card's body instead of directly in {@link #messageList} once a run reaches
    /// 2+ calls (see {@link #appendToolCard}, which decides when to promote a solo card into one
    /// of these).
    static final class ToolCallGroupCard extends VBox {
        private final CollapseHeader header;
        private final VBox body = new VBox(2);
        private int count = 0;

        ToolCallGroupCard() {
            super(2);
            getStyleClass().add("ai-tool-card");
            setMaxWidth(AI_BUBBLE_MAX_WIDTH - 16); // 704 — unified subordinate-card width (VS §3.3)

            header = new CollapseHeader("已调用 0 个工具"); // placeholder, overwritten by the 1st add() // TODO(i18n)
            body.visibleProperty().bind(header.expandedProperty()); // starts collapsed (default false)
            body.managedProperty().bind(header.expandedProperty());
            getChildren().addAll(header, body);
        }

        void add(ToolCard card) {
            body.getChildren().add(card);
            count++;
            header.getTitleLabel().setText("已调用 " + count + " 个工具"); // TODO(i18n)
        }
    }

    /// Places a new tool-call card into the conversation, grouping it with the immediately
    /// preceding tool call(s) if this is part of the same uninterrupted run — see
    /// {@link ToolCallGroupCard}. A single isolated call stays a plain standalone card (no group
    /// wrapper, no visual change from before); a run is only promoted to a group once its 2nd call
    /// arrives, at which point the first card is pulled out of its own wrapper and re-parented into
    /// a new group alongside this one. Used by both the live-streaming path (`onToolActivity`) and
    /// the persisted-session reload path (`addPersistedToolCard`), so a reloaded session's grouping
    /// always matches what was shown live — call {@link #endToolCallRun()} at the same points in
    /// both paths (new text segment / non-tool message) to close off a run.
    private void appendToolCard(ToolCard card) {
        if (activeToolGroup != null) {
            activeToolGroup.add(card);
        } else if (lastSoloToolCard != null) {
            ToolCallGroupCard group = new ToolCallGroupCard();
            messageList.getChildren().remove(lastSoloToolCardWrapper);
            lastSoloToolCardWrapper.getChildren().remove(lastSoloToolCard);
            group.add(lastSoloToolCard);
            group.add(card);
            messageList.getChildren().add(wrapCard(group));
            activeToolGroup = group;
            lastSoloToolCard = null;
            lastSoloToolCardWrapper = null;
        } else {
            HBox wrapper = wrapCard(card);
            messageList.getChildren().add(wrapper);
            lastSoloToolCard = card;
            lastSoloToolCardWrapper = wrapper;
        }
    }

    /// Ends the current tool-call run (if any), so the next tool card starts a fresh group/solo
    /// card instead of extending this one. Call whenever a non-tool-call message (assistant text,
    /// user message, event pill) is about to be shown, in both the live and reload paths.
    private void endToolCallRun() {
        activeToolGroup = null;
        lastSoloToolCard = null;
        lastSoloToolCardWrapper = null;
    }

    /// Subscribes the chat view to the decoupled {@link org.jackhuang.hmcl.ai.tools.ToolProgress}
    /// bus so long-running download / install tools render a live progress card instead of
    /// appearing frozen. Events may arrive on any thread, so we marshal onto the JavaFX thread
    /// and route them to the {@link #activeToolCard} of the in-flight tool call.
    private void installToolProgressListener() {
        org.jackhuang.hmcl.ai.tools.ToolProgress.setListener(event -> Platform.runLater(() -> {
            ToolCard card = activeToolCard;
            if (card == null) {
                return; // no tool card on screen (display disabled, or between turns)
            }
            if (event.done()) {
                // The terminal state is rendered by ToolCard.complete() via onToolResult; just
                // collapse the in-flight bar so a finished card never shows a stale progress.
                card.updateProgress(event.success() ? 1.0 : -1.0, event.message());
            } else {
                card.updateProgress(event.fraction(), event.message());
            }
            scrollToBottom();
        }));
    }

    /// Adds a tool event entry to the message list with tool-specific styling
    /// and logs it to the tool activity area.
    private void addToolMessage(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(AI_BUBBLE_MAX_WIDTH);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-tool");

        messageList.getChildren().add(wrapBubble(label, Pos.CENTER_LEFT));

        toolActivityLabel.setText(text);
        updateToolActivityVisibility();
        updateEmptyState();
        scrollToBottom();
    }

    /// Shows or hides the tool activity strip based on whether it has anything to echo.
    private void updateToolActivityVisibility() {
        boolean hasItems = !toolActivityLabel.getText().isEmpty();
        toolActivityLabel.setVisible(hasItems);
        toolActivityLabel.setManaged(hasItems);
    }

    private void scrollToBottom() {
        if (!stickToBottom || !aiSettings.isAutoScrollEnabled()) return;
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    // ---- Chat Settings Persistence ----

    /// Loads chat settings from the JSON file on disk, returning defaults if the file is absent or corrupt.
    private ChatSettings loadChatSettings() {
        Path filePath = SettingsManager.localConfigDirectory().resolve(CHAT_SETTINGS_FILE);
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            ChatSettings loaded = CHAT_SETTINGS_GSON.fromJson(json, ChatSettings.class);
            return loaded != null ? loaded : new ChatSettings();
        } catch (NoSuchFileException e) {
            return new ChatSettings();
        } catch (IOException | JsonParseException e) {
            return new ChatSettings();
        }
    }

    /// Loads the web-search config from disk (defaults — disabled — if absent or corrupt).
    private org.jackhuang.hmcl.ai.search.AiSearchConfig loadSearchConfig() {
        Path filePath = SettingsManager.localConfigDirectory().resolve(SEARCH_CONFIG_FILE);
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            org.jackhuang.hmcl.ai.search.AiSearchConfig loaded =
                    CHAT_SETTINGS_GSON.fromJson(json, org.jackhuang.hmcl.ai.search.AiSearchConfig.class);
            return loaded != null ? loaded : new org.jackhuang.hmcl.ai.search.AiSearchConfig();
        } catch (NoSuchFileException e) {
            return new org.jackhuang.hmcl.ai.search.AiSearchConfig();
        } catch (IOException | JsonParseException e) {
            return new org.jackhuang.hmcl.ai.search.AiSearchConfig();
        }
    }

    /// Loads the OCR config from disk (defaults — disabled — if absent or corrupt).
    private org.jackhuang.hmcl.ai.ocr.AiOcrConfig loadOcrConfig() {
        Path filePath = SettingsManager.localConfigDirectory().resolve(OCR_CONFIG_FILE);
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            org.jackhuang.hmcl.ai.ocr.AiOcrConfig loaded =
                    CHAT_SETTINGS_GSON.fromJson(json, org.jackhuang.hmcl.ai.ocr.AiOcrConfig.class);
            return loaded != null ? loaded : new org.jackhuang.hmcl.ai.ocr.AiOcrConfig();
        } catch (NoSuchFileException e) {
            return new org.jackhuang.hmcl.ai.ocr.AiOcrConfig();
        } catch (IOException | JsonParseException e) {
            return new org.jackhuang.hmcl.ai.ocr.AiOcrConfig();
        }
    }

    /// Persists the current chat settings to the JSON file.
    private void saveChatSettings() {
        Path filePath = SettingsManager.localConfigDirectory().resolve(CHAT_SETTINGS_FILE);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, CHAT_SETTINGS_GSON.toJson(chatSettings), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    // ---- Chat Settings Drawer ----

    /// Builds the right-side chat settings drawer panel once during construction.
    /// The drawer starts hidden; it is toggled by the toolbar settings button.
    private void buildChatSettingsDrawer() {
        chatSettingsDrawer = new VBox();
        chatSettingsDrawer.setPrefWidth(280);
        chatSettingsDrawer.setMinWidth(280);
        chatSettingsDrawer.setMaxWidth(280);
        chatSettingsDrawer.getStyleClass().add("ai-chat-settings-drawer");
        chatSettingsDrawer.setVisible(false);
        chatSettingsDrawer.setManaged(false);

        // Header: title + close button (drawer title is the same 15px-bold tier as the page header)
        Label headerLabel = new Label(i18n("ai.chat.settings"));
        headerLabel.getStyleClass().add("ai-header-title");

        JFXButton closeBtn = FXUtils.newToggleButton4(SVG.CLOSE, 18);
        closeBtn.setOnAction(e -> hideChatSettingsDrawer());

        HBox header = new HBox(headerLabel, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 16, 10, 16));
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        // Body: native settings lists. Spacing lives HERE now — the former
        // .ai-chat-settings-body rule (spacing 12) that silently overrode it is gone.
        VBox body = new VBox(12);
        body.setPadding(new Insets(4, 14, 14, 14));
        body.getChildren().setAll(buildChatSettingsContent());

        ScrollPane scrollBody = new ScrollPane(body);
        scrollBody.setFitToWidth(true);
        scrollBody.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollBody, Priority.ALWAYS);

        chatSettingsDrawer.getChildren().addAll(header, scrollBody);
    }

    /// bindBidirectional only syncs MEMORY — AiSettings has no auto-save, so a drawer toggle that
    /// stops here silently reverts on restart (P6; same trap AISettingsPage#buildAbilitySublist
    /// documents). The save listener hangs off the SETTINGS property, not the button: the bind's
    /// initial button-side sync then never triggers a redundant write, and a change made from the
    /// settings page merely saves once more (idempotent).
    private void bindPersisted(LineToggleButton btn, javafx.beans.property.BooleanProperty prop) {
        btn.selectedProperty().bindBidirectional(prop);
        prop.addListener((o, ov, nv) -> persistAiSettings());
    }

    /// Builds the chat-settings drawer content using native HMCL list components.
    /// Only settings that actually affect rendering are kept; dead options were removed.
    private List<Node> buildChatSettingsContent() {
        // ---- 显示 ----
        ComponentList display = new ComponentList();

        LineButton userName = new LineButton();
        userName.setTitle("用户名");
        userName.setSubtitle(chatSettings.userName);
        userName.setTrailingIcon(SVG.EDIT);
        userName.setOnAction(e -> Controllers.prompt("用户名", (result, handler) -> {
            String name = result.trim();
            chatSettings.userName = name.isEmpty() ? "用户" : name;
            saveChatSettings();
            userName.setSubtitle(chatSettings.userName);
            refreshMessageList();
            handler.resolve();
        }, chatSettings.userName));
        display.getContent().add(userName);

        LineSelectButton<String> msgStyle = new LineSelectButton<>();
        msgStyle.setTitle("消息样式");
        msgStyle.setItems(List.of("bubble", "flat"));
        msgStyle.setNullSafeConverter(v -> "flat".equals(v) ? "平铺" : "气泡");
        msgStyle.setValue("flat".equals(chatSettings.messageStyle) ? "flat" : "bubble");
        msgStyle.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                chatSettings.messageStyle = nv;
                saveChatSettings();
                applyCssToMessageList();
            }
        });
        display.getContent().add(msgStyle);

        LineSelectButton<String> fontSize = new LineSelectButton<>();
        fontSize.setTitle("字号");
        fontSize.setItems(List.of("small", "normal", "large"));
        fontSize.setNullSafeConverter(v -> switch (v) {
            case "small" -> "小";
            case "large" -> "大";
            default -> "正常";
        });
        fontSize.setValue(chatSettings.fontSize);
        fontSize.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                chatSettings.fontSize = nv;
                saveChatSettings();
                applyCssToMessageList();
            }
        });
        display.getContent().add(fontSize);

        LineToggleButton markdown = new LineToggleButton();
        markdown.setTitle("Markdown 渲染");
        markdown.setSubtitle("渲染 AI 回复中的 Markdown 格式");
        markdown.setSelected(chatSettings.markdownRender);
        markdown.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.markdownRender = nv;
            saveChatSettings();
            refreshMessageList();
        });
        display.getContent().add(markdown);

        LineToggleButton toolCalls = new LineToggleButton();
        toolCalls.setTitle(i18n("ai.settings.tool_call_display"));
        toolCalls.setSubtitle(i18n("ai.settings.tool_call_display.desc"));
        bindPersisted(toolCalls, aiSettings.toolCallDisplayEnabledProperty());
        display.getContent().add(toolCalls);

        LineToggleButton colorEmoji = new LineToggleButton();
        colorEmoji.setTitle("彩色 Emoji（联网）");
        colorEmoji.setSubtitle("聊天内联渲染彩色 emoji；首次使用时从 Twemoji 仓库联网下载并缓存");
        colorEmoji.setSelected(chatSettings.colorEmoji);
        colorEmoji.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.colorEmoji = nv;
            EmojiImages.setEnabled(nv);
            saveChatSettings();
            refreshMessageList();
        });
        display.getContent().add(colorEmoji);

        // ---- 用量 ----
        ComponentList usage = new ComponentList();

        LineToggleButton showUsage = new LineToggleButton();
        showUsage.setTitle("显示用量");
        showUsage.setSubtitle("在 AI 回复下方显示 token 用量");
        showUsage.setSelected(chatSettings.showUsage);
        showUsage.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.showUsage = nv;
            saveChatSettings();
            refreshMessageList();
        });
        usage.getContent().add(showUsage);

        LineToggleButton estimate = new LineToggleButton();
        estimate.setTitle("估算 Token");
        estimate.setSubtitle("无服务商用量数据时按字符估算");
        estimate.setSelected(chatSettings.estimateTokens);
        estimate.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.estimateTokens = nv;
            saveChatSettings();
            refreshMessageList();
        });
        usage.getContent().add(estimate);

        LineToggleButton cost = new LineToggleButton();
        cost.setTitle("显示花费");
        cost.setSubtitle("按模型计费估算并显示花费（需在模型高级设置中填写单价）");
        cost.setSelected(chatSettings.showCost);
        cost.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.showCost = nv;
            saveChatSettings();
            refreshMessageList();
        });
        usage.getContent().add(cost);

        // ---- 交互 ----
        ComponentList interaction = new ComponentList();

        LineToggleButton stream = new LineToggleButton();
        stream.setTitle("流式输出");
        stream.setSubtitle("逐字显示模型回答，关闭后等待完整响应");
        bindPersisted(stream, aiSettings.streamProperty());
        interaction.getContent().add(stream);

        LineToggleButton shortcut = new LineToggleButton();
        shortcut.setTitle("显示快捷菜单");
        shortcut.setSubtitle("输入框显示斜杠命令等快捷菜单");
        shortcut.setSelected(chatSettings.showShortcutMenu);
        shortcut.selectedProperty().addListener((o, ov, nv) -> {
            chatSettings.showShortcutMenu = nv;
            saveChatSettings();
        });
        interaction.getContent().add(shortcut);

        LineToggleButton enterSend = new LineToggleButton();
        enterSend.setTitle("回车发送");
        enterSend.setSubtitle("开：Enter 发送、Shift+Enter 换行；关：Ctrl+Enter 发送");
        bindPersisted(enterSend, aiSettings.sendOnEnterProperty());
        interaction.getContent().add(enterSend);

        LineToggleButton autoScroll = new LineToggleButton();
        autoScroll.setTitle("自动滚动到底部");
        autoScroll.setSubtitle("有新消息时自动滚到底（手动上滑时暂停）");
        bindPersisted(autoScroll, aiSettings.autoScrollEnabledProperty());
        interaction.getContent().add(autoScroll);

        return List.of(
                ComponentList.createComponentListTitle("显示"), display,
                ComponentList.createComponentListTitle("用量"), usage,
                ComponentList.createComponentListTitle("交互"), interaction);
    }

    /// Shows the chat settings drawer sliding in from the right with a backdrop overlay.
    private void showChatSettingsDrawer() {
        if (chatSettingsDrawer == null || chatSettingsBackdrop == null) return;
        chatSettingsDrawer.setTranslateX(chatSettingsDrawer.getPrefWidth());
        chatSettingsDrawer.setVisible(true);
        chatSettingsDrawer.setManaged(true);
        chatSettingsBackdrop.setVisible(true);
        chatSettingsBackdrop.setOpacity(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), chatSettingsBackdrop);
        fadeIn.setToValue(1);

        javafx.animation.TranslateTransition slideIn =
                new javafx.animation.TranslateTransition(Duration.millis(250), chatSettingsDrawer);
        slideIn.setToX(0);

        fadeIn.play();
        slideIn.play();
    }

    /// Hides the chat settings drawer sliding out to the right.
    private void hideChatSettingsDrawer() {
        if (chatSettingsDrawer == null || chatSettingsBackdrop == null) return;

        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), chatSettingsBackdrop);
        fadeOut.setToValue(0);

        javafx.animation.TranslateTransition slideOut =
                new javafx.animation.TranslateTransition(Duration.millis(250), chatSettingsDrawer);
        slideOut.setToX(chatSettingsDrawer.getPrefWidth());
        slideOut.setOnFinished(e -> {
            chatSettingsDrawer.setVisible(false);
            chatSettingsDrawer.setManaged(false);
            chatSettingsBackdrop.setVisible(false);
        });

        fadeOut.play();
        slideOut.play();
    }

    // ---- Chat Settings Application ----

    /// Applies current chat settings to the UI on initial load.
    private void applyChatSettings() {
        EmojiImages.setEnabled(chatSettings.colorEmoji);
        applyCssToMessageList();
    }

    /// Updates the message list CSS classes based on current font-size setting.
    private void applyCssToMessageList() {
        messageList.getStyleClass().removeAll("ai-chat-font-small", "ai-chat-font-normal", "ai-chat-font-large");
        messageList.getStyleClass().add("ai-chat-font-" + chatSettings.fontSize);
        messageList.getStyleClass().remove("ai-msg-flat");
        if ("flat".equals(chatSettings.messageStyle)) {
            messageList.getStyleClass().add("ai-msg-flat");
        }
    }

    /// Re-renders all messages to pick up the current user-name setting.
    private void refreshMessageList() {
        // Never rebuild the view mid-stream: loadSessionMessages cancels a pending ask (the
        // agent then receives a bogus "user cancelled") and wipes the live streaming bubbles /
        // tool cards. Display-setting changes simply take effect at the next render.
        if (isStreaming()) {
            return;
        }
        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            loadSessionMessages(current);
        }
    }

    // ---- Session Multi-Select Management ----

    /// Persisted chat settings DTO loaded from / saved to ai-chat-settings.json.
    @NotNullByDefault
    static final class ChatSettings {
        @SerializedName("userName")
        String userName = "\u7528\u6237";

        @SerializedName("messageStyle")
        String messageStyle = "bubble";

        @SerializedName("fontSize")
        String fontSize = "normal";

        @SerializedName("markdownRender")
        boolean markdownRender = true;

        @SerializedName("colorEmoji")
        boolean colorEmoji = false;

        @SerializedName("showUsage")
        boolean showUsage = true;

        @SerializedName("estimateTokens")
        boolean estimateTokens = false;

        @SerializedName("showCost")
        boolean showCost = false;

        @SerializedName("showShortcutMenu")
        boolean showShortcutMenu = true;
    }

    // ---- DecoratorPage implementation ----

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
