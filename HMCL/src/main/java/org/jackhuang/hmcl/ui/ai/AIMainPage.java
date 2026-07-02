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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
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
/// flags backed by {@link AiSettings}: title naming, auto log/crash analysis,
/// tool-call display, and approval mode (Safe / Ask / YOLO). The approval mode is
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

    // ---- State ----

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("ai.title")));
    private final AiSettings aiSettings;
    private final AiSessionStore sessionStore;
    private final AiModelDiscoveryService discoveryService = new AiModelDiscoveryService();

    private final Map<String, ChatAgent> agentCache = new HashMap<>();
    private final ToolRegistry toolRegistry = new ToolRegistry();

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

    // ---- Toolbar ----

    private final LineSelectButton<String> modelSelector = new LineSelectButton<>();
    // ---- Messages ----

    private final VBox messageList = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane(messageList);
    private final Label statusLabel = new Label();
    private final VBox toolActivityBox = new VBox(2);

    private final VBox emptyState = new VBox(12);

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

    /// Panel shown above the input field when the agent calls the `ask` tool: renders the
    /// structured questions and a confirm button. Hidden/unmanaged when no question is pending.
    private final VBox askPanel = new VBox(8);
    /// The in-flight `ask` future (completed on confirm, cancelled on stop/session switch).
    @Nullable
    private volatile java.util.concurrent.CompletableFuture<java.util.List<String>> activeAsk;

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

    /// Tool-call cards awaiting their result, keyed by tool name (FIFO per name) so onToolResult
    /// can find the matching card appended by onToolActivity.
    private final java.util.Map<String, java.util.Deque<ToolCard>> pendingToolCards = new java.util.HashMap<>();

    /// The tool card currently executing (set on tool-call, cleared on its result). Live download
    /// / install progress from {@link org.jackhuang.hmcl.ai.tools.ToolProgress} is routed here, so
    /// the chat shows a real-time progress bar instead of freezing. Tools run sequentially, so a
    /// single "active" card is sufficient. JavaFX-thread confined.
    @Nullable
    private ToolCard activeToolCard;

    /// Whether the message view should auto-scroll to the bottom on new content. Set false when
    /// the user scrolls up to read history; restored when they scroll back to the bottom.
    private boolean stickToBottom = true;

    /// The in-flight streaming response future (for stop/abort), and a generation counter
    /// used to ignore callbacks from a response the user has stopped.
    @Nullable
    private java.util.concurrent.CompletableFuture<Void> currentResponse;
    private int responseGeneration = 0;
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

    // ---- Search overlay ----

    @Nullable
    private StackPane searchOverlay;
    @Nullable
    private TextField searchField;
    @Nullable
    private VBox searchResultsBox;
    @Nullable
    private Label searchStatusLabel;
    @Nullable
    private Label searchEmptyLabel;
    private final List<SearchResult> searchResults = new ArrayList<>();
    private int searchSelectedIndex = -1;

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

    // ---- Autocomplete ----

    @Nullable
    private VBox autocompletePopup;
    private final List<String> autocompleteItems = new ArrayList<>();
    private int autocompleteSelectedIndex = -1;

    /// Represents a single cross-session search result.
    private static final class SearchResult {
        final AiSession session;
        final String matchingLine;

        SearchResult(AiSession session, String matchingLine) {
            this.session = session;
            this.matchingLine = matchingLine;
        }
    }

    // ---- Constructor ----

    public AIMainPage() {
        this.aiSettings = new AiSettings(SettingsManager.localConfigDirectory());
        try {
            aiSettings.load();
        } catch (Exception ignored) {
        }

        // First-use test-phase risk notice (one-time, must be acknowledged). Deferred so the page is shown.
        if (!aiSettings.isAiRiskNoticeAccepted()) {
            Platform.runLater(this::showAiRiskNotice);
        }

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

        this.chatSettings = loadChatSettings();
        this.searchConfig = loadSearchConfig();
        this.ocrConfig = loadOcrConfig();

        if (sessionStore.getCurrentSession() == null) {
            sessionStore.createSession();
            try {
                sessionStore.save();
            } catch (Exception ignored) {
            }
        }

        typingTimeline.setCycleCount(Timeline.INDEFINITE);
        getStyleClass().add("gray-background");

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

        refreshModelSelector();
        

        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            loadSessionMessages(current);
            updateHeader(current);
        }
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
        // HMCL-operation tools (let the agent actually install/launch), reusing HMCL APIs.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListInstancesTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListGameVersionsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchModsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.LaunchInstanceTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallLoaderTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.AskTool(this::showAskPanel));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool(this::updateTodoCard));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.KnownErrorMatcherTool());
        toolRegistry.register(new org.jackhuang.hmcl.ai.tools.SleepTool());
        // Content management (reuse HMCL remote repos + download/install pipeline).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchResourcePacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallResourcePackTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchShadersTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallShaderTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchModpacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallModpackTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchWorldsTool());
        // Instance lifecycle (rename/duplicate are reversible; delete is confirm-gated).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.EditInstanceTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.DeleteInstanceTool(aiSettings::isDeleteToRecycleBin));
        // Accounts (reuse HMCL's account system — never shell out / hand-edit for login).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListAccountsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.AddOfflineAccountTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SelectAccountTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.MicrosoftLoginTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SetSkinTool());
        // Java runtimes (reuse HMCL JavaManager — don't probe `java -version` via shell).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListJavaTool());
        // Global memory (file-based store; remember/recall across conversations).
        rememberStore = new org.jackhuang.hmcl.ai.remember.RememberStore(
                        SettingsManager.localConfigDirectory().resolve("ai-memory"));
        if (aiSettings.isMemoryEnabled()) {
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RememberTool(rememberStore));
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RecallTool(rememberStore));
        }
        // Mod management (reuse repository mods dir; toggle by renaming, not shell).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListModsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ToggleModTool());
        // World/options/folder utilities (reuse repository run dir + native FXUtils.openFolder).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.BackupWorldTool());
        // Versioned world-backup engine (timestamped full-copy snapshots + retention N).
        // Honest: full-copy, not incremental/git — see WorldBackupManager. restore is red-critical.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CreateWorldBackupTool(aiSettings::getWorldBackupRetention));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListWorldBackupsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RestoreWorldBackupTool(aiSettings::getWorldBackupRetention));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadGameOptionsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SetGameOptionTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.OpenGameFolderTool());
        // Diagnostics (reuse HMCL SystemInfo hardware detection; screenshots listing).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SystemInfoTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListScreenshotsTool());
        // OCR a screenshot/image into text (crash/error shots) — backend chosen in AI 设置 > OCR.
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.OcrImageTool(ocrConfig));
        // Convenience (clipboard for pasted crash logs / errors, conversation export, prompt presets).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CopyToClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.PromptLibraryTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ExportConversationTool(sessionStore));
        // Local content visibility (read-only snapshots of what the user already has).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListWorldsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListServersTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListResourcePacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstanceDetailsTool());
        // World / datapack management (reuse repository run dir; delete is confirm-gated).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListDatapacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallDatapackTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.DeleteWorldTool(aiSettings::isDeleteToRecycleBin));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ImportWorldTool());
        // Mod info / updates / modpack export (reuse ModManager / RemoteAddonRepository / export task).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.GetModInfoTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CheckModUpdatesTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.DeleteModTool(aiSettings::isDeleteToRecycleBin));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.UpdateModTool(aiSettings::isDeleteToRecycleBin));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ExportModpackTool());
        // Server / Java / instance runtime (SLP ping, JavaManager download, GameSettings memory, log cleanup).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.PingServerTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.DownloadJavaTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SetInstanceMemoryTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CleanLogsTool());
        // Background jobs: query/cancel long-running tasks started with background=true (AiJobManager).
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListJobsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CheckJobTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CancelJobTool());
        // Save NBT editing (read/write save & player NBT data; writes are backup-gated +
        // path-confined + atomic, and trigger the red critical confirmation via CriticalOperations).
        if (aiSettings.isNbtToolsEnabled()) {
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadNbtTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.GetNbtTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SetNbtTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ComputeOfflineUuidTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CopyPlayerDataTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.TransferInventoryTool());
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadWorldInfoTool());
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
            // install_mod needs the current instance's run dir; re-register on refresh.
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallModTool(runDir));
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
        chatSettingsStack.getChildren().setAll(chatView, buildSearchOverlay());
        chatSettingsStack.getStyleClass().add("ai-chat-stack");

        StackPane centerWithDrawer = new StackPane(chatSettingsStack);

        // Backdrop (semi-transparent overlay behind drawer)
        chatSettingsBackdrop = new StackPane();
        chatSettingsBackdrop.getStyleClass().add("ai-chat-settings-backdrop");
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

        AdvancedListItem settingsItem = new AdvancedListItem();
        settingsItem.getStyleClass().add("navigation-drawer-item");
        settingsItem.setTitle(i18n("ai.settings"));
        settingsItem.setLeftIcon(SVG.TUNE);
        settingsItem.setOnAction(e -> Controllers.navigate(new AISettingsPage(
                aiSettings,
                discoveryService,
                () -> {
                    agentCache.clear();
                    refreshModelSelector();
                    AiSession current = sessionStore.getCurrentSession();
                    if (current != null) {
                        updateHeader(current);
                    }
                }
        )));

        // Pin "AI settings" as a fixed-height row at the very bottom: it never grows or
        // shrinks, so however long the session list gets, the scroll area above absorbs the
        // change and this entry always keeps its own dedicated, full-size space.
        AdvancedListBox bottomBox = new AdvancedListBox();
        bottomBox.add(settingsItem);
        VBox.setVgrow(bottomBox, Priority.NEVER);
        bottomBox.setMinHeight(Region.USE_PREF_SIZE);
        bottomBox.setMaxHeight(Region.USE_PREF_SIZE);
        sidebarRoot.getChildren().addAll(topBox, sidebarScrollPane, bottomBox);
        refreshSessionList();
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
        item.setTitle(session.isPinned() ? "📌 " + labelText : labelText);
        item.setSubtitle(relativeTime(session.getUpdatedAt()));
        item.setLeftIcon(SVG.CHAT);
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
            try {
                sessionStore.save();
            } catch (Exception ignored) {
            }
        });

        return item;
    }

    /// Rename / pin / delete menu for a sidebar session row.
    private void showSessionMenu(Node anchor, AiSession session) {
        VBox box = new VBox();
        box.getStyleClass().add("ai-session-menu");
        JFXPopup popup = new JFXPopup(box);

        java.util.function.BiConsumer<String, Runnable> addItem = (text, action) -> {
            JFXButton b = new JFXButton(text);
            b.setMaxWidth(Double.MAX_VALUE);
            b.getStyleClass().add("ai-session-menu-item");
            b.setOnAction(e -> {
                popup.hide();
                action.run();
            });
            box.getChildren().add(b);
        };

        addItem.accept("重命名", () -> Controllers.prompt("重命名会话", (result, handler) -> {
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
        }, session.getTitle() == null ? "" : session.getTitle()));

        addItem.accept(session.isPinned() ? "取消置顶" : "置顶", () -> {
            session.setPinned(!session.isPinned());
            persistStore();
            refreshSessionList();
        });

        String title = session.getTitle() == null || session.getTitle().isBlank()
                ? i18n("ai.session.untitled") : session.getTitle();
        addItem.accept("删除", () -> Controllers.confirm(
                "删除会话「" + title + "」？此操作不可恢复。",
                i18n("button.remove"),
                () -> deleteSession(session.getId()),
                null));

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
        String currentId = sessionStore.getCurrentSessionId();
        sessionStore.deleteSession(sessionId);
        agentCache.remove(sessionId);

        if (sessionId.equals(currentId)) {
            messageList.getChildren().clear();
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
        try {
            sessionStore.save();
        } catch (Exception ignored) {
        }
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
        chatView.setSpacing(12);
        chatView.setPadding(new Insets(12));

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

        toolActivityBox.getStyleClass().add("ai-tool-activity");
        toolActivityBox.setVisible(false);
        toolActivityBox.setManaged(false);

        Label toolActivityLabel = new Label(i18n("ai.tool.activity"));
        toolActivityLabel.getStyleClass().add("ai-tool-activity-label");
        toolActivityBox.getChildren().add(toolActivityLabel);

        todoCardContainer.getStyleClass().add("ai-todo-container");
        todoCardContainer.setVisible(false);
        todoCardContainer.setManaged(false);

        VBox conversationCard = new VBox(toolActivityBox, todoCardContainer, messagesArea, statusLabel, buildComposer());
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

        Label emptyText = new Label(i18n("ai.input_placeholder"));
        emptyText.getStyleClass().add("ai-empty-text");

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
            Label chip = new Label(suggestion);
            chip.getStyleClass().add("ai-suggestion-chip");
            chip.setOnMouseClicked(e -> {
                inputField.setText(suggestion);
                sendMessage();
            });
            chips.getChildren().add(chip);
        }

        Label suggestionsLabel = new Label(i18n("ai.empty_suggestions"));
        suggestionsLabel.getStyleClass().add("ai-suggestions-label");

        VBox suggestionsBox = new VBox(4, suggestionsLabel, chips);
        suggestionsBox.setAlignment(Pos.CENTER);

        emptyState.getChildren().setAll(aiIcon, emptyText, suggestionsBox);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("ai-empty-state");
    }

    private void updateEmptyState() {
        boolean hasMessages = !messageList.getChildren().isEmpty();
        emptyState.setVisible(!hasMessages);
        emptyState.setManaged(!hasMessages);
    }

    // ---- Header ----

    private Node buildHeaderNode() {
        headerTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        headerTitle.getStyleClass().add("ai-header-title");
        headerSubtitle.getStyleClass().add("ai-header-subtitle");
        approvalBadge.getStyleClass().add("ai-approval-badge");
        approvalBadge.setVisible(false);
        approvalBadge.setManaged(false);
        planBadge.getStyleClass().addAll("ai-approval-badge", "ai-approval-badge-ask");
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

        JFXButton searchBtn = new JFXButton();
        searchBtn.setGraphic(SVG.SEARCH.createIcon(16));
        searchBtn.getStyleClass().add("ai-toolbar-icon-btn");
        searchBtn.setOnAction(e -> showSearchOverlay());

        JFXButton chatSettingsBtn = new JFXButton();
        chatSettingsBtn.setGraphic(SVG.TUNE.createIcon(16));
        chatSettingsBtn.getStyleClass().add("ai-toolbar-icon-btn");
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
        headerBox.setPadding(new Insets(9, 14, 9, 14));
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

        modelSelector.valueProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            String[] parts = val.split(" / ", 2);
            if (parts.length != 2) return;
            for (AiProviderProfile p : aiSettings.getProfiles()) {
                if (!p.isEnabled() || !p.getDisplayName().equals(parts[0])) continue;
                for (String m : p.getCachedModels()) {
                    if (p.getModelAliasOrId(m).equals(parts[1]) || m.equals(parts[1])) {
                        aiSettings.setSelectedProfileId(p.getId());
                        p.setDefaultModelId(m);
                        agentCache.clear();
                        try { aiSettings.save(); } catch (Exception ignored) {}
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

    /// Refreshes the approval badge based on the current approval mode setting.
    private void updateApprovalBadge() {
        AiApprovalMode mode = aiSettings.getApprovalModeEnum();
        approvalBadge.getStyleClass().removeAll(
                "ai-approval-badge-safe", "ai-approval-badge-ask", "ai-approval-badge-yolo");
        switch (mode) {
            case SAFE -> {
                approvalBadge.setText(i18n("ai.settings.approval_badge_safe"));
                approvalBadge.getStyleClass().add("ai-approval-badge-safe");
            }
            case ASK -> {
                approvalBadge.setText(i18n("ai.settings.approval_badge_ask"));
                approvalBadge.getStyleClass().add("ai-approval-badge-ask");
            }
            case YOLO -> {
                approvalBadge.setText(i18n("ai.settings.approval_badge_yolo"));
                approvalBadge.getStyleClass().add("ai-approval-badge-yolo");
            }
        }
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
        try {
            sessionStore.save();
        } catch (Exception ignored) {
        }
        hideTodoCard();
        messageList.getChildren().clear();
        updateEmptyState();
        refreshSessionList();
        showChatView();

        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            updateHeader(current);
        }
        inputField.requestFocus();
    }

    /// Clears the current conversation's messages in-place (the `/clear` command).
    private void clearCurrentConversation() {
        AiSession current = sessionStore.getCurrentSession();
        if (current == null) return;
        current.clear();
        agentCache.remove(current.getId());
        hideTodoCard();
        messageList.getChildren().clear();
        toolActivityBox.getChildren().clear();
        updateToolActivityVisibility();
        updateEmptyState();
        persistStore();
    }

    private boolean isPlanMode() {
        return planMode;
    }

    /// Shows/hides the plan-mode header badge to match {@link #planMode}.
    private void updatePlanBadge() {
        planBadge.setVisible(planMode);
        planBadge.setManaged(planMode);
    }

    /// Before a plan-mode response, disables every write-capable tool (CONTROLLED_WRITE /
    /// DANGEROUS_WRITE) except `ask`, so the agent can only investigate and propose. The
    /// disabled names are remembered and restored by {@link #restorePlanGating()}.
    private void applyPlanGating() {
        planDisabledTools.clear();
        if (!planMode) return;
        for (org.jackhuang.hmcl.ai.tools.Tool t : toolRegistry.listAll()) {
            String name = t.getName();
            if ("ask".equals(name)) continue;
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
            agentCache.remove(target.getId());
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
        inputField.setMinHeight(34);
        inputField.setPrefHeight(34);
        inputField.setMaxHeight(180);
        inputField.textProperty().addListener((o, ov, nv) -> {
            int lines = nv == null || nv.isEmpty() ? 1 : (int) nv.chars().filter(c -> c == '\n').count() + 1;
            inputField.setPrefHeight(Math.min(180, Math.max(34, 14 + lines * 20)));
        });
        // Track IME composition so the Enter that commits pinyin never sends a half-typed message.
        inputField.addEventFilter(javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                e -> imeComposing = e.getComposed() != null && !e.getComposed().isEmpty());

        // Autocomplete + Enter/Shift+Enter key handling
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleInputKeyPress);

        sendBtn = FXUtils.newRaisedButton(i18n("ai.send"));
        sendBtn.getStyleClass().add("ai-send-btn");
        // While a response is streaming the button becomes a Stop button.
        sendBtn.setOnAction(e -> {
            if (isStreaming()) stopResponse();
            else sendMessage();
        });
        sendBtn.setDefaultButton(true);

        JFXButton attachBtn = new JFXButton();
        attachBtn.setGraphic(SVG.FILE_OPEN.createIcon(16));
        attachBtn.getStyleClass().add("ai-input-attach-btn");
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
        FXUtils.setLimitHeight(jobsListContainer, 0);
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
        JFXButton thinkBtn = new JFXButton();
        thinkBtn.getStyleClass().add("ai-toolbar-icon-btn");
        javafx.scene.shape.SVGPath bulbIcon = new javafx.scene.shape.SVGPath();
        // Material outline (hollow) lightbulb
        bulbIcon.setContent("M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7zm2.85 11.1l-.85.6V16h-4v-2.3l-.85-.6C7.8 12.16 7 10.63 7 9c0-2.76 2.24-5 5-5s5 2.24 5 5c0 1.63-.8 3.16-2.15 4.1z");
        thinkBtn.setGraphic(bulbIcon);

        String currentThink = aiSettings.getReasoningEffort().isEmpty() ? "none" : aiSettings.getReasoningEffort();
        FXUtils.installFastTooltip(thinkBtn, "思考: " + currentThink);
        thinkBtn.setOnAction(e -> {
            // Toggle: if the popup is already open, close it instead of stacking another.
            if (thinkingPopup != null && thinkingPopup.isShowing()) {
                thinkingPopup.hide();
                return;
            }
            String[] levels = {"none", "low", "medium", "high", "xhigh", "max"};
            String cur = aiSettings.getReasoningEffort().isEmpty() ? "none" : aiSettings.getReasoningEffort();
            VBox popup = new VBox(2);
            popup.getStyleClass().add("ai-model-picker");
            popup.setPadding(new Insets(4));
            popup.setPrefWidth(150);  // compact: a 6-item effort picker shouldn't be a full nav-width menu
            for (String level : levels) {
                Label lbl = new Label(level);
                lbl.setMaxWidth(Double.MAX_VALUE);
                lbl.setPadding(new Insets(6, 14, 6, 12));  // small row height, not the big nav-drawer item
                if (level.equals(cur)) {
                    lbl.getStyleClass().add("title-label");  // highlight the active level
                }
                org.jackhuang.hmcl.ui.construct.RipplerContainer row = new org.jackhuang.hmcl.ui.construct.RipplerContainer(lbl);
                FXUtils.onClicked(row, () -> {
                    aiSettings.reasoningEffortProperty().set(level);
                    FXUtils.installFastTooltip(thinkBtn, "思考: " + level);
                    if (thinkingPopup != null) {
                        thinkingPopup.hide();
                    }
                });
                popup.getChildren().add(row);
            }
            thinkingPopup = new JFXPopup(popup);
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

    // ---- Search overlay ----

    /// Builds the cross-session search overlay that appears over the center area.
    /// The overlay is hidden by default and shown via the toolbar search button.
    private StackPane buildSearchOverlay() {
        // Semi-transparent backdrop
        StackPane backdrop = new StackPane();
        backdrop.getStyleClass().add("ai-search-backdrop");

        // Search dialog
        VBox dialog = new VBox(8);
        dialog.setMaxWidth(560);
        dialog.setMaxHeight(480);
        dialog.setPadding(new Insets(16));
        dialog.getStyleClass().add("ai-search-dialog");
        dialog.setOnMouseClicked(e -> e.consume()); // prevent clicks on dialog from closing overlay

        Label titleLabel = new Label(i18n("ai.search"));
        titleLabel.getStyleClass().add("ai-search-title");

        JFXButton searchCloseBtn = new JFXButton();
        searchCloseBtn.setGraphic(SVG.CLOSE.createIcon(16));
        searchCloseBtn.getStyleClass().add("ai-search-close-btn");
        searchCloseBtn.setOnAction(e -> hideSearchOverlay());

        HBox titleRow = new HBox(titleLabel, searchCloseBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("ai-search-title-row");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        searchField = new TextField();
        searchField.setPromptText(i18n("ai.search.prompt"));
        searchField.getStyleClass().add("ai-search-field");
        searchField.textProperty().addListener((obs, old, val) -> performSearch(val));
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideSearchOverlay();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                navigateSearchResults(1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                navigateSearchResults(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                selectSearchResult();
                e.consume();
            }
        });

        HBox searchInputRow = new HBox(8,
                SVG.SEARCH.createIcon(16),
                searchField);
        searchInputRow.setAlignment(Pos.CENTER_LEFT);
        searchInputRow.getStyleClass().add("ai-search-input-row");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchEmptyLabel = new Label(i18n("ai.search.empty"));
        searchEmptyLabel.getStyleClass().add("ai-search-empty");
        searchEmptyLabel.setVisible(true);

        searchStatusLabel = new Label();
        searchStatusLabel.getStyleClass().add("ai-search-status");
        searchStatusLabel.setVisible(false);

        searchResultsBox = new VBox(2);
        searchResultsBox.getStyleClass().add("ai-search-results");

        ScrollPane resultsScroll = new ScrollPane(new StackPane(searchResultsBox, searchEmptyLabel));
        resultsScroll.setFitToWidth(true);
        resultsScroll.getStyleClass().addAll("edge-to-edge", "ai-search-scroll");
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);

        dialog.getChildren().setAll(titleRow, searchInputRow, resultsScroll, searchStatusLabel);

        // Center the dialog inside the overlay
        StackPane dialogWrapper = new StackPane(dialog);
        dialogWrapper.setAlignment(Pos.CENTER);
        dialogWrapper.setPadding(new Insets(40));
        dialogWrapper.setOnMouseClicked(e -> hideSearchOverlay()); // clicking around dialog closes

        searchOverlay = new StackPane(backdrop, dialogWrapper);
        searchOverlay.setVisible(false);
        searchOverlay.setManaged(false);
        StackPane.setAlignment(dialogWrapper, Pos.CENTER);

        return searchOverlay;
    }

    /// Shows the search overlay with a fade-in animation.
    private void showSearchOverlay() {
        if (searchOverlay == null) return;
        searchOverlay.setVisible(true);
        searchOverlay.setManaged(true);
        searchOverlay.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), searchOverlay);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
        if (searchField != null) {
            Platform.runLater(() -> searchField.requestFocus());
        }
    }

    /// Hides the search overlay with a fade-out animation.
    private void hideSearchOverlay() {
        if (searchOverlay == null) return;
        FadeTransition ft = new FadeTransition(Duration.millis(150), searchOverlay);
        ft.setFromValue(1);
        ft.setToValue(0);
        ft.setOnFinished(e -> {
            searchOverlay.setVisible(false);
            searchOverlay.setManaged(false);
            if (searchField != null) searchField.clear();
            if (searchResultsBox != null) searchResultsBox.getChildren().clear();
            searchResults.clear();
            searchSelectedIndex = -1;
            if (searchStatusLabel != null) searchStatusLabel.setVisible(false);
            if (searchEmptyLabel != null) searchEmptyLabel.setVisible(true);
        });
        ft.play();
    }

    /// Performs a full-text search across all sessions' titles and message content.
    ///
    /// Filters against the given query (case-insensitive containment match) and
    /// updates the results list in the overlay.
    private void performSearch(String query) {
        if (searchResultsBox == null || searchStatusLabel == null) return;
        searchResultsBox.getChildren().clear();
        searchResults.clear();
        searchSelectedIndex = -1;

        if (query == null || query.trim().isEmpty()) {
            searchStatusLabel.setVisible(false);
            if (searchEmptyLabel != null) searchEmptyLabel.setVisible(true);
            return;
        }

        if (searchEmptyLabel != null) searchEmptyLabel.setVisible(false);

        String lowerQuery = query.toLowerCase();
        List<AiSession> allSessions = sessionStore.listSessions();

        for (AiSession session : allSessions) {
            // Search in title
            String title = session.getTitle();
            boolean titleMatch = title != null && title.toLowerCase().contains(lowerQuery);

            // Search in messages
            List<LlmMessage> messages = session.getMessages();
            for (int i = 0; i < messages.size(); i++) {
                LlmMessage msg = messages.get(i);
                String content = msg.getContent();
                if (content != null) {
                    int idx = content.toLowerCase().indexOf(lowerQuery);
                    if (idx >= 0) {
                        // Extract a preview line around the match
                        int start = Math.max(0, idx - 30);
                        int end = Math.min(content.length(), idx + lowerQuery.length() + 40);
                        String preview = (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
                        searchResults.add(new SearchResult(session, preview));
                        break; // One result per session
                    }
                }
            }

            // If title matched but no message matched, still add the title result
            if (titleMatch && searchResults.stream().noneMatch(r -> r.session.getId().equals(session.getId()))) {
                searchResults.add(new SearchResult(session, title));
            }
        }

        if (searchResults.isEmpty()) {
            searchStatusLabel.setText(i18n("ai.search.no_results", query));
            searchStatusLabel.setVisible(true);
        } else {
            searchStatusLabel.setVisible(false);
            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult result = searchResults.get(i);
                int index = i;
                Node item = buildSearchResultItem(result, index);
                searchResultsBox.getChildren().add(item);
            }
        }
    }

    /// Builds a single clickable search result row with session title and
    /// a truncated matching-message preview.
    private Node buildSearchResultItem(SearchResult result, int index) {
        AiSession session = result.session;
        String title = session.getTitle();
        if (title == null || title.isEmpty()) {
            title = i18n("ai.session.untitled");
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("ai-search-result-title");

        Label previewLabel = new Label(result.matchingLine);
        previewLabel.getStyleClass().add("ai-search-result-preview");
        previewLabel.setMaxWidth(500);
        previewLabel.setWrapText(true);

        VBox row = new VBox(2, titleLabel, previewLabel);
        row.setPadding(new Insets(6, 10, 6, 10));
        row.getStyleClass().add("ai-search-result-item");
        if (index == searchSelectedIndex) {
            row.getStyleClass().add("ai-search-result-item-selected");
        }
        row.setOnMouseClicked(e -> {
            switchToSessionAndScroll(session.getId(), result.matchingLine);
            hideSearchOverlay();
        });

        return row;
    }

    /// Navigates the search results list by the given delta (-1 for up, +1 for down).
    private void navigateSearchResults(int delta) {
        if (searchResults.isEmpty()) return;
        int newIndex = searchSelectedIndex + delta;
        if (newIndex < 0) newIndex = searchResults.size() - 1;
        if (newIndex >= searchResults.size()) newIndex = 0;
        searchSelectedIndex = newIndex;

        if (searchResultsBox != null) {
            ObservableList<Node> children = searchResultsBox.getChildren();
            for (int i = 0; i < children.size(); i++) {
                Node node = children.get(i);
                node.getStyleClass().remove("ai-search-result-item-selected");
                if (i == searchSelectedIndex) {
                    node.getStyleClass().add("ai-search-result-item-selected");
                }
            }
        }
    }

    /// Selects the currently highlighted search result and navigates to it.
    private void selectSearchResult() {
        if (searchSelectedIndex >= 0 && searchSelectedIndex < searchResults.size()) {
            SearchResult result = searchResults.get(searchSelectedIndex);
            switchToSessionAndScroll(result.session.getId(), result.matchingLine);
            hideSearchOverlay();
        }
    }

    /// Switches to the given session id and scrolls to a message containing
    /// the given text if possible.
    private void switchToSessionAndScroll(String sessionId, String matchingText) {
        sessionStore.setCurrentSessionId(sessionId);
        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
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
                if (node instanceof HBox wrapper) {
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
        try {
            sessionStore.save();
        } catch (Exception ignored) {
        }
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
            remove.getStyleClass().add("ai-file-chip-clear-btn");
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

    /// Reads the pending attachments and appends their content to the outbound prompt text.
    /// Unreadable files are reported inline instead of failing the send.
    private String appendAttachments(String text) {
        if (attachedFiles.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (Path p : attachedFiles) {
            sb.append("\n\n[附件: ").append(p.getFileName()).append("]\n");
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content.length() > ATTACHMENT_MAX_CHARS) {
                    content = content.substring(0, ATTACHMENT_MAX_CHARS)
                            + "\n…（文件过大，已截断）";
                }
                sb.append(content);
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

    /// Handles drag-over events on the chat area for file drag-and-drop.
    private void handleDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            boolean accepted = db.getFiles().stream().anyMatch(f -> {
                String name = f.getName().toLowerCase();
                return name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".crash");
            });
            if (accepted) {
                event.acceptTransferModes(TransferMode.COPY);
            }
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
                String name = dropped.getName().toLowerCase();
                if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".crash")) {
                    attachFile(dropped.toPath());
                    success = true;
                }
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
        SLASH_COMMANDS.put("/new", "新建一个会话");
        SLASH_COMMANDS.put("/clear", "清空当前对话上下文");
        SLASH_COMMANDS.put("/compact", "把当前对话压缩成摘要以节省 token");
        SLASH_COMMANDS.put("/sessions", "搜索并切换历史会话");
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
            itemLabel.getStyleClass().add("ai-autocomplete-item");
            if (i == autocompleteSelectedIndex) {
                itemLabel.getStyleClass().add("ai-autocomplete-item-selected");
            }
            itemLabel.setMaxWidth(Double.MAX_VALUE);
            itemLabel.setPadding(new Insets(4, 10, 4, 10));
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
                    // dangerous nor the red critical gate) so nothing ever prompts. The effective
                    // approval mode is also forced to YOLO via AiSettings.getApprovalModeEnum(), so the
                    // policy auto-allows every tool call (otherwise a null handler on an ASK decision
                    // would BLOCK instead of skip).
                    boolean skipAll = aiSettings.isDangerouslySkipPermissions();
                    return ChatAgentFactory.build(aiSettings, session, toolRegistry, pb,
                            skipAll ? null : this::confirmDangerousOperation,
                            (skipAll || !aiSettings.isCriticalConfirmEnabled()) ? null : this::confirmCriticalOperation);
                });
    }

    /// Blocking confirmation used by the tool layer before a dangerous operation runs.
    /// Invoked on the agent's background thread: shows a dialog on the FX thread and waits
    /// for the user's answer (denying on timeout/error so the agent can never hang).
    private boolean confirmDangerousOperation(String toolName, String summary) {
        java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Controllers.confirm(
                        i18n("ai.confirm.dangerous.text", summary),
                        i18n("ai.confirm.dangerous.title"),
                        () -> future.complete(true),
                        () -> future.complete(false));
            } catch (Throwable t) {
                future.complete(false);
            }
        });
        try {
            return future.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return false;
        }
    }

    /// Second-tier CRITICAL confirmation (red) for catastrophic operations — deleting a
    /// world/instance, editing save/NBT data, deleting backups, or removing files under
    /// saves/playerdata/.minecraft. Shown IN ADDITION to (and after) the normal confirm,
    /// right before execution, even in YOLO mode. Denies on timeout/error so nothing hangs.
    private boolean confirmCriticalOperation(String toolName, String summary) {
        java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Controllers.confirm(
                        "⛔ 高危操作，可能不可恢复！请仔细确认：\n\n" + summary
                                + "\n\n这可能永久修改或删除你的存档/玩家数据/备份。确定要继续吗？",
                        "⛔ 高危操作 · 二次确认",
                        () -> future.complete(true),
                        () -> future.complete(false));
            } catch (Throwable t) {
                future.complete(false);
            }
        });
        try {
            return future.get(180, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return false;
        }
    }

    /// Guards rapid session switching so the UI stays responsive: quick successive clicks are
    /// debounced and a load that is already in flight for a given session id is not restarted.
    @Nullable
    private CompletableFuture<Void> pendingSessionLoad;
    @Nullable
    private String loadingSessionId;

    private void loadSession(AiSession session) {
        if (session == null) {
            return;
        }
        String id = session.getId();
        // Per-session drafts: stash whatever is in the composer for the session we're leaving,
        // restore the (possibly empty) draft of the one we're entering.
        if (!id.equals(draftSessionId)) {
            if (draftSessionId != null) {
                String draft = inputField.getText();
                if (draft == null || draft.isBlank()) {
                    sessionDrafts.remove(draftSessionId);
                } else {
                    sessionDrafts.put(draftSessionId, draft);
                }
            }
            inputField.setText(sessionDrafts.getOrDefault(id, ""));
            draftSessionId = id;
        }
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
        hideTodoCard(); // the pinned TODO list is per-turn UI, not part of session history
        messageList.getChildren().clear();
        toolActivityBox.getChildren().clear();
        streamingBubble = null;
        pendingToolCards.clear();
        activeToolCard = null;
        stickToBottom = true; // a freshly-opened session starts pinned to the latest message
        int index = 0;
        for (LlmMessage msg : session.getMessages()) {
            String role = msg.getRole();
            if (msg.isToolRecord()) {
                // Persisted record of one tool invocation — rebuild the completed tool card so
                // history shows what the AI actually did (cards used to vanish on reload).
                if (aiSettings.isToolCallDisplayEnabled()) {
                    addPersistedToolCard(msg.getToolPayload());
                }
            } else if (msg.isEvent()) {
                // Synthetic turn (background-job auto-continue / crash injection): a neutral
                // event pill, NOT a user bubble, and no copy/edit/resend action bar.
                addEventPill(msg.getContent());
            } else if ("user".equals(role)) {
                addUserBubble(msg.getContent(), true);
                attachMessageActions(msg.getContent(), role, index);
            } else if ("assistant".equals(role)) {
                createAiBubble(msg.getContent(), msg.getUsage());
                attachMessageActions(msg.getContent(), role, index);
            } else if (isToolMessage(msg.getContent())) {
                if (aiSettings.isToolCallDisplayEnabled()) {
                    addToolMessage(msg.getContent());
                }
            } else {
                addSystemMessage(msg.getContent());
            }
            index++;
        }
        updateToolActivityVisibility();
        updateEmptyState();
        scrollToBottom();
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
    /// result stored by the ChatAgent.
    private static boolean isToolMessage(String content) {
        return content.startsWith("Tool result for ");
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
        messageList.getChildren().add(row);
    }

    private static JFXButton smallIcon(SVG icon, String tooltip, Runnable action) {
        JFXButton btn = new JFXButton();
        btn.setGraphic(icon.createIcon(16));
        btn.getStyleClass().add("ai-bubble-action-btn");
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
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
        AiSession branch = sessionStore.createBranch(cur, index, cur.getTitle() + " ✦");
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
        int pos = children.indexOf(bubble);
        if (pos < 0) {
            return;
        }

        JFXTextArea editor = new JFXTextArea(content == null ? "" : content);
        editor.setWrapText(true);
        int lines = content == null ? 1 : content.split("\n", -1).length;
        editor.setPrefRowCount(Math.min(10, Math.max(2, lines)));
        editor.setMaxWidth(480);
        editor.getStyleClass().add("ai-inline-edit");

        JFXButton cancel = new JFXButton("取消");
        cancel.getStyleClass().add("ai-inline-edit-cancel");
        JFXButton confirm = new JFXButton("确定");
        confirm.getStyleClass().add("ai-inline-edit-confirm");
        HBox btnRow = new HBox(8, cancel, confirm);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox editBox = new VBox(6, editor, btnRow);
        editBox.setMaxWidth(480);
        editBox.getStyleClass().add("ai-inline-edit-box");
        HBox wrapper = new HBox(editBox);
        wrapper.setAlignment(Pos.CENTER_RIGHT);
        wrapper.setPadding(new Insets(2, 16, 2, 16));

        // Hide this message's action bar (the row right after the bubble) while editing.
        Node actionRow = (pos + 1 < children.size()) ? children.get(pos + 1) : null;
        if (actionRow != null) {
            actionRow.setVisible(false);
            actionRow.setManaged(false);
        }
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

    private void sendMessage() {
        if (isStreaming()) return; // a response is in flight; the button acts as Stop instead
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
                showSearchOverlay();
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

        inputField.clear();
        sendText(appendAttachments(text), null);
        clearFileChip();
    }

    /// Core send path shared by the composer ({@link #sendMessage}) and external/synthetic
    /// prompts ({@link #submitExternalPrompt}). External prompts no longer go THROUGH the
    /// composer — they used to overwrite whatever draft the user was typing and send it away.
    ///
    /// @param kind `null` for a normal user message, or {@link LlmMessage#KIND_EVENT} for a
    ///             synthetic turn (rendered as a neutral event pill, not a user bubble).
    private void sendText(String text, @Nullable String kind) {
        if (isStreaming()) return;
        if (text == null || text.isBlank()) return;
        text = text.trim();

        boolean event = LlmMessage.KIND_EVENT.equals(kind);
        // A real user message resets the auto-continue depth guard; synthetic event turns
        // (background-job auto-continue) deliberately do NOT, so a runaway loop can't be masked.
        if (!event) {
            autoContinueDepth = 0;
        }

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
        pendingToolCards.clear();
        activeToolCard = null;

        // Plan mode: gate off write-capable tools for this response; restored in
        // exitStreamingState() (reached on completion, stop, and error).
        applyPlanGating();

        // Bind this stream to the session it belongs to. If the user switches to another
        // session mid-stream, callbacks must NOT touch the (now different) message view —
        // otherwise A's tokens leak into B and scrollToBottom fights the user (jitter/freeze).
        final AiSession streamSession = session;

        final int generation = ++responseGeneration;
        StringBuilder fullContent = new StringBuilder();
        // Text of the current segment bubble (reset whenever a new segment starts).
        StringBuilder segment = new StringBuilder();
        // Holds provider-reported usage (if any) so onComplete can render the footer.
        LlmUsage[] usageHolder = {null};
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        currentCancelled = cancelled;
        currentStreamAgent = agent;
        currentResponse = agent.sendStreaming(userInput, kind, new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                fullContent.append(token);
                Platform.runLater(() -> {
                    if (generation != responseGeneration) return; // stopped/superseded
                    if (sessionStore.getCurrentSession() != streamSession) return; // viewing another session
                    if (streamingBubble == null) {
                        // Text resumed after a tool call (or first text): start a new segment.
                        streamingBubble = createAiBubble("");
                        segment.setLength(0);
                    }
                    segment.append(token);
                    streamingBubble.setText(segment.toString());
                    scrollToBottom();
                });
            }

            @Override
            public void onUsage(LlmUsage usage) {
                usageHolder[0] = usage;
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
                    messageList.getChildren().add(wrapBubble(card, Pos.CENTER_LEFT));
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
                            finalizeAiBubble(streamingBubble, segment.toString(), usageHolder[0], true);
                            streamingBubble = null;
                        }
                        setStatus(null);
                    }
                    exitStreamingState();
                    persistStore();
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
                showAiError(streamingBubble, segment, error, streamSession);
            }
        }, cancelled::get).exceptionally(ex -> {
            if (generation != responseGeneration) return null; // stopped; ignore
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            LlmException error = cause instanceof LlmException
                    ? (LlmException) cause
                    : new LlmException(cause.getMessage() != null ? cause.getMessage() : "Connection failed", 0, cause);
            showAiError(streamingBubble, segment, error, streamSession);
            return null;
        });

        enterStreamingState();
    }

    private boolean isStreaming() {
        return currentResponse != null;
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
        String status;
        switch (job.getStatus()) {
            case SUCCEEDED: status = "已完成"; break;
            case FAILED: status = "失败"; break;
            default: return; // not terminal — ignore
        }
        org.jackhuang.hmcl.ai.tools.ToolResult result = job.getResult();
        String detail = result != null && result.getOutput() != null && !result.getOutput().isEmpty()
                ? result.getOutput()
                : (job.getError() != null ? job.getError() : "");
        if (detail.length() > 2000) {
            detail = detail.substring(0, 2000) + "…";
        }
        if (autoContinueDepth >= AUTO_CONTINUE_LIMIT) {
            addSystemMessage("后台任务「" + job.getLabel() + "」" + status
                    + "，但连续自动继续已达上限，已暂停以免空转。发送任意消息让我接着处理。");
            return;
        }
        autoContinueDepth++;
        String prompt = "（后台任务 #" + job.getId() + "「" + job.getLabel() + "」" + status + "）"
                + (detail.isEmpty() ? "" : "结果：\n" + detail)
                + "\n请据此继续。";
        submitExternalPrompt(prompt);
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
            Label count = new Label(run + "/" + total);
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
            FXUtils.setLimitHeight(jobsListContainer, 0);
            jobsListContainer.setManaged(false);
            jobsListContainer.setVisible(false);
            jobsToggleIcon.setRotate(0);
        } else if (jobsExpanded && jobsListContainer.isManaged()) {
            // Keep the unfurled height in sync as jobs come and go.
            Platform.runLater(() -> {
                double w = jobsListContainer.getWidth() > 0 ? jobsListContainer.getWidth() : jobsPane.getWidth();
                FXUtils.setLimitHeight(jobsListContainer, jobsListContainer.prefHeight(w));
            });
        }
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
        javafx.animation.Interpolator ease = javafx.animation.Interpolator.EASE_BOTH;
        jobsAnimation = new Timeline(new KeyFrame(javafx.util.Duration.millis(180),
                new javafx.animation.KeyValue(jobsListContainer.minHeightProperty(), targetHeight, ease),
                new javafx.animation.KeyValue(jobsListContainer.prefHeightProperty(), targetHeight, ease),
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
                return;
            }
            sendText(text, LlmMessage.KIND_EVENT);
        });
    }

    /// Shows the one-time test-phase risk notice (forced 5s countdown). On acknowledgement the
    /// preference is persisted so it never shows again. No external project names in the copy.
    private void showAiRiskNotice() {
        if (aiSettings.isAiRiskNoticeAccepted()) {
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
                    try {
                        aiSettings.save();
                    } catch (Exception ignored) {
                    }
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
        sendBtn.setText(i18n("ai.stop"));
        if (!sendBtn.getStyleClass().contains("ai-stop-btn")) {
            sendBtn.getStyleClass().add("ai-stop-btn");
        }
    }

    /// Restores the send button after a response finishes or is stopped.
    private void exitStreamingState() {
        currentResponse = null;
        sendBtn.setText(i18n("ai.send"));
        sendBtn.getStyleClass().remove("ai-stop-btn");
        // Restore any tools plan mode disabled for the just-finished response.
        restorePlanGating();
        // A background job that finished while this turn was streaming was queued; handle the next
        // one now that we're idle (it starts its own turn, which naturally serializes the rest).
        if (!pendingCompletions.isEmpty()) {
            org.jackhuang.hmcl.ai.tools.AiJobManager.Job next = pendingCompletions.poll();
            Platform.runLater(() -> onBackgroundJobComplete(next));
        }
    }

    /// Stops the in-flight response: invalidates its callbacks, best-effort aborts the
    /// request, finalizes whatever was streamed so far, and resets the button.
    /// {@link org.jackhuang.hmcl.ui.ai.tools.AskTool.AskUiHandler} implementation: called on the
    /// agent's background thread, it renders the question panel on the FX thread and returns a
    /// future the background thread blocks on. Completed when the user confirms; cancelled by
    /// {@link #cancelActiveAsk()} on stop / session switch.
    private java.util.concurrent.CompletableFuture<java.util.List<String>> showAskPanel(
            java.util.List<org.jackhuang.hmcl.ui.ai.tools.AskTool.Question> questions) {
        java.util.concurrent.CompletableFuture<java.util.List<String>> future =
                new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            cancelActiveAsk();
            activeAsk = future;
            askPanel.getChildren().clear();

            Label title = new Label(i18n("ai.ask.title"));
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

            JFXButton back = new JFXButton(i18n("ai.ask.back"));
            back.getStyleClass().add("ai-ask-nav");
            JFXButton next = new JFXButton(i18n("ai.ask.next"));
            next.getStyleClass().add("ai-ask-nav");
            JFXButton confirm = new JFXButton(i18n("ai.ask.confirm"));
            confirm.getStyleClass().add("ai-ask-confirm");
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
                title.setText(n > 1 ? i18n("ai.ask.title") + "  (" + (i + 1) + "/" + n + ")" : i18n("ai.ask.title"));
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
                             @Nullable AiSession owner) {
        Platform.runLater(() -> {
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
            target.getStyleClass().add("ai-bubble-error");
            // Offer a one-click retry of the failed turn (transient 429 / timeout / connection drop).
            JFXButton retryBtn = new JFXButton("重试");
            retryBtn.getStyleClass().add("ai-retry-btn");
            retryBtn.setOnAction(e -> {
                if (target.getParent() instanceof javafx.scene.Parent) {
                    messageList.getChildren().remove(retryBtn);
                }
                retryLastTurn();
            });
            messageList.getChildren().add(retryBtn);
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

    private void persistStore() {
        try {
            sessionStore.save();
        } catch (Exception e) {
            // Persistence failures must at least be observable — a silently swallowed save left
            // the user believing the conversation was on disk when it only lived in memory.
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save session store", e);
        }
    }

    /// Builds the small role-name label shown above a bubble.
    private static Label bubbleName(String name, boolean alignRight) {
        Label nameLabel = new Label(name);
        nameLabel.setMaxWidth(480);
        if (alignRight) {
            nameLabel.setAlignment(Pos.CENTER_RIGHT);
        }
        nameLabel.getStyleClass().add("ai-bubble-name");
        return nameLabel;
    }

    /// Builds a bubble's text content: a colour-emoji TextFlow when colour emoji is enabled
    /// and the text contains emoji, otherwise a plain wrapped Label. Both carry the given
    /// bubble style classes.
    private static Node bubbleTextNode(String text, String... styleClasses) {
        if (EmojiImages.isEnabled() && EmojiImages.containsEmoji(text)) {
            javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
            flow.setMaxWidth(480);
            flow.getChildren().addAll(EmojiImages.toNodes(text, 14));
            flow.getStyleClass().addAll(styleClasses);
            return flow;
        }
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    /// Wraps bubble content in a horizontally-aligned row with consistent padding.
    private static HBox wrapBubble(Node content, Pos align) {
        HBox wrapper = new HBox(content);
        wrapper.setAlignment(align);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");
        // Rasterise each finished bubble so scrolling a long conversation reuses the cached
        // bitmap (a pure translate) instead of repainting every TextFlow — the default cache
        // hint re-renders at quality on real transforms, so there is no blur on resize.
        wrapper.setCache(true);
        return wrapper;
    }

    private void addUserBubble(String text) {
        addUserBubble(text, false);
    }

    private void addUserBubble(String text, boolean quiet) {
        Node content = bubbleTextNode(text, "ai-bubble", "ai-bubble-user");

        VBox bubbleBox = new VBox(2, bubbleName(chatSettings.userName, true), content);
        bubbleBox.setMaxWidth(480);
        bubbleBox.setAlignment(Pos.CENTER_RIGHT);

        messageList.getChildren().add(wrapBubble(bubbleBox, Pos.CENTER_RIGHT));
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
        content.setMaxWidth(480);
        content.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");

        VBox bubbleBox = new VBox(2, bubbleName("AI", false), content);
        bubbleBox.setMaxWidth(480);

        // When markdown is available, render it as the bubble and keep the plain
        // Label hidden behind it as the streaming text target.
        if (!text.isEmpty() && chatSettings.markdownRender) {
            MarkdownMessageView mdView = MarkdownMessageView.create(text);
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
            MarkdownMessageView mdView = MarkdownMessageView.create(completeText);
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
    private String formatUsage(LlmUsage usage) {
        if (usage.isEstimated()) {
            return i18n("ai.usage.estimated", String.valueOf(usage.getTotalTokens()));
        }
        String text = i18n("ai.usage.detail",
                String.valueOf(usage.getTotalTokens()),
                String.valueOf(usage.getPromptTokens()),
                String.valueOf(usage.getCompletionTokens()));
        if (chatSettings.showCost) {
            AiProviderProfile active = aiSettings.findSelectedProfile();
            String modelId = active != null ? active.getDefaultModelId() : null;
            AiModelEntry model = modelId != null ? active.getModel(modelId) : null;
            if (model != null && model.hasPricing()) {
                double cost = model.computeCost(
                        usage.getPromptTokens(), usage.getCompletionTokens(), 0, 0);
                text += i18n("ai.usage.cost", String.format(java.util.Locale.ROOT, "%.6f", cost));
            }
        }
        return text;
    }

    private void addSystemMessage(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
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
        pill.setMaxWidth(480);
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
        messageList.getChildren().add(wrapBubble(card, Pos.CENTER_LEFT));
    }

    /// {@link org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoUiHandler}: renders the agent's
    /// latest TODO checklist as a pinned card above the messages. Called on the agent's
    /// background thread, so it marshals onto the FX thread.
    private void updateTodoCard(List<org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoItem> todos) {
        Platform.runLater(() -> {
            todoCardContainer.getChildren().clear();
            if (todos == null || todos.isEmpty()) {
                hideTodoCard();
                return;
            }
            VBox card = new VBox(4);
            card.getStyleClass().addAll("ai-bubble", "ai-tool-card", "ai-todo-card");
            card.setPadding(new Insets(10, 12, 10, 12));

            long done = todos.stream().filter(t -> "done".equals(t.status())).count();
            Label title = new Label("任务清单 (" + done + "/" + todos.size() + ")");
            title.getStyleClass().add("ai-tool-card-header");
            title.setStyle("-fx-font-weight: bold;");
            card.getChildren().add(title);

            for (org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool.TodoItem t : todos) {
                String status = t.status();
                String mark = switch (status) {
                    case "done" -> "✓";
                    case "in_progress" -> "▶";
                    default -> "○";
                };
                Label row = new Label(mark + "  " + t.content());
                row.setWrapText(true);
                row.setMaxWidth(440);
                switch (status) {
                    case "done" -> row.setStyle("-fx-text-fill: #6aa84f;");
                    case "in_progress" -> row.setStyle("-fx-font-weight: bold; -fx-text-fill: #4285f4;");
                    default -> { /* pending: default styling */ }
                }
                card.getChildren().add(row);
            }

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
    private final class ToolCard extends VBox {
        private final Label header = new Label();
        private final Label result = new Label();
        private final String toolName;

        /// Live progress UI (hidden until the first progress event, removed once the tool finishes).
        private final JFXProgressBar progressBar = new JFXProgressBar();
        private final Label progressLabel = new Label();
        private final VBox progressBox = new VBox(2, progressLabel, progressBar);
        private boolean finished = false;

        ToolCard(String toolName) {
            super(2);
            this.toolName = toolName;
            getStyleClass().addAll("ai-bubble", "ai-bubble-tool", "ai-tool-card");
            setMaxWidth(480);

            header.setText(i18n("ai.tool.calling", toolName));
            header.setWrapText(true);
            header.getStyleClass().add("ai-tool-card-header");

            result.setWrapText(true);
            result.setMaxWidth(440);
            result.getStyleClass().add("ai-tool-card-result");
            result.setVisible(false);
            result.setManaged(false);

            progressLabel.setWrapText(true);
            progressLabel.setMaxWidth(440);
            progressLabel.getStyleClass().add("ai-tool-card-result");
            progressBar.setPrefWidth(440);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBox.setVisible(false);
            progressBox.setManaged(false);

            header.setOnMouseClicked(e -> {
                String r = result.getText();
                if (r != null && !r.isEmpty()) {
                    boolean show = !result.isVisible();
                    result.setVisible(show);
                    result.setManaged(show);
                }
            });

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
            header.setText(i18n(success ? "ai.tool.done" : "ai.tool.failed", toolName));
            getStyleClass().add(success ? "ai-tool-card-ok" : "ai-tool-card-fail");
            if (summary != null && !summary.isBlank()) {
                result.setText(summary.strip());
                header.getStyleClass().add("ai-tool-card-expandable");
            }
        }
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
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-tool");

        messageList.getChildren().add(wrapBubble(label, Pos.CENTER_LEFT));

        Label activityItem = new Label(text);
        activityItem.getStyleClass().add("ai-tool-activity-item");
        toolActivityBox.getChildren().add(activityItem);
        updateToolActivityVisibility();
        updateEmptyState();
        scrollToBottom();
    }

    /// Shows or hides the tool activity panel based on whether it has entries.
    private void updateToolActivityVisibility() {
        boolean hasItems = !toolActivityBox.getChildren().isEmpty();
        toolActivityBox.setVisible(hasItems);
        toolActivityBox.setManaged(hasItems);
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

        // Header: title + close button
        Label headerLabel = new Label(i18n("ai.chat.settings"));
        headerLabel.getStyleClass().add("ai-chat-settings-header-title");

        JFXButton closeBtn = new JFXButton();
        closeBtn.setGraphic(SVG.CLOSE.createIcon(18));
        closeBtn.getStyleClass().add("jfx-tool-bar-button");  // native round-ripple icon button
        closeBtn.setOnAction(e -> hideChatSettingsDrawer());

        HBox header = new HBox(headerLabel, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 14, 12, 14));
        header.getStyleClass().add("ai-chat-settings-header");
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        // Body: native settings lists
        VBox body = new VBox(10);
        body.setPadding(new Insets(4, 14, 14, 14));
        body.getStyleClass().add("ai-chat-settings-body");
        body.getChildren().setAll(buildChatSettingsContent());

        ScrollPane scrollBody = new ScrollPane(body);
        scrollBody.setFitToWidth(true);
        scrollBody.getStyleClass().add("edge-to-edge");
        scrollBody.getStyleClass().add("ai-chat-settings-scroll");
        VBox.setVgrow(scrollBody, Priority.ALWAYS);

        chatSettingsDrawer.getChildren().addAll(header, scrollBody);
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
        toolCalls.selectedProperty().bindBidirectional(aiSettings.toolCallDisplayEnabledProperty());
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
        stream.selectedProperty().bindBidirectional(aiSettings.streamProperty());
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
        enterSend.selectedProperty().bindBidirectional(aiSettings.sendOnEnterProperty());
        interaction.getContent().add(enterSend);

        LineToggleButton autoScroll = new LineToggleButton();
        autoScroll.setTitle("自动滚动到底部");
        autoScroll.setSubtitle("有新消息时自动滚到底（手动上滑时暂停）");
        autoScroll.selectedProperty().bindBidirectional(aiSettings.autoScrollEnabledProperty());
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
