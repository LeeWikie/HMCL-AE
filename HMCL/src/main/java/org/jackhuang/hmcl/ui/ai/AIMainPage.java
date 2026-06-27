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
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXPopup;
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
import javafx.scene.control.Separator;
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
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.ai.AiEndpointNormalizer;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.AiModelDiscoveryService;
import org.jackhuang.hmcl.ai.AiModelPreset;
import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiProviderDefinition;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.agent.ChatAgent;
import org.jackhuang.hmcl.ai.agent.ChatAgentFactory;
import org.jackhuang.hmcl.ai.agent.AiTitleNamingStrategy;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.markdown.MarkdownRenderer;
import org.jackhuang.hmcl.ai.tools.CrashAnalyzerTool;
import org.jackhuang.hmcl.ai.tools.FileBackupTool;
import org.jackhuang.hmcl.ai.tools.ModToggleTool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
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
import java.util.concurrent.TimeoutException;

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
/// OpenAI Reasoning, Anthropic, REST API). Each profile stores endpoint, API key,
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

    // ---- Sidebar elements ----

    private final VBox sessionListBox = new VBox(2);
    @Nullable
    private AdvancedListBox sidebarScrollPane;
    @Nullable
    private VBox sidebarRoot;

    // ---- View state ----

    /// Whether the settings view is currently shown.
    private boolean settingsActive = false;

    // ---- Main content elements ----

    private final StackPane chatSettingsStack = new StackPane();
    private final VBox chatView = new VBox();
    private final VBox settingsView = new VBox();

    // ---- Header ----

    private final Label headerTitle = new Label();
    private final Label headerSubtitle = new Label();
    private final Label approvalBadge = new Label();

    // ---- Toolbar ----

    private final Label currentModelLabel = new Label();
    private final Label currentThinkingLabel = new Label();
    // ---- Messages ----

    private final VBox messageList = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane(messageList);
    private final Label statusLabel = new Label();
    private final VBox toolActivityBox = new VBox(2);

    private final VBox emptyState = new VBox(12);

    // ---- Composer ----

    private final TextField inputField = new TextField();
    private JFXButton sendBtn;

    // ---- Typing indicator ----

    private String typingBaseText = "";
    private final Timeline typingTimeline = new Timeline(
            new KeyFrame(javafx.util.Duration.ZERO, e -> statusLabel.setText(typingBaseText + ".")),
            new KeyFrame(javafx.util.Duration.millis(400), e -> statusLabel.setText(typingBaseText + "..")),
            new KeyFrame(javafx.util.Duration.millis(800), e -> statusLabel.setText(typingBaseText + "..."))
    );

    @Nullable
    private Label streamingBubble;

    // ---- Settings panel fields ----

    @Nullable
    private VBox settingsProfileListBox;
    @Nullable
    private ComboBox<String> settingsModelCombo;
    @Nullable
    private JFXTextField settingsEndpointField;
    @Nullable
    private JFXPasswordField settingsApiKeyField;
    @Nullable
    private JFXTextField settingsProfileNameField;
    @Nullable
    private ComboBox<String> settingsProtocolCombo;
    @Nullable
    private VBox settingsAdvancedBox;
    @Nullable
    private JFXTextField settingsContextWindowField;
    @Nullable
    private JFXTextField settingsMaxOutputField;
    @Nullable
    private JFXTextField settingsTemperatureField;
    @Nullable
    private JFXTextField settingsTopPField;
    @Nullable
    private JFXTextField settingsPresencePenaltyField;
    @Nullable
    private JFXTextField settingsFrequencyPenaltyField;
    @Nullable
    private JFXTextField settingsSeedField;
    @Nullable
    private JFXTextField settingsReasoningEffortField;
    @Nullable
    private Label settingsFeedbackLabel;
    @Nullable
    private JFXButton settingsTestBtn;
    @Nullable
    private Label settingsDiscoverFeedback;
    private final Map<String, VBox> settingsTabs = new HashMap<>();
    private final Map<String, AdvancedListItem> settingsNavItems = new HashMap<>();
    @Nullable
    private StackPane settingsBodyStack;
    private String activeSettingsTab = "provider";
    @Nullable
    private StackPane providerModalOverlay;
    @Nullable
    private VBox providerModalCard;

    // ---- Global AI settings controls ----

    @Nullable
    private CheckBox settingsTitleNamingCheck;
    @Nullable
    private CheckBox settingsAutoCrashAnalysisCheck;
    @Nullable
    private CheckBox settingsToolCallDisplayCheck;
    @Nullable
    private ComboBox<String> settingsApprovalModeCombo;
    @Nullable
    private ComboBox<String> settingsDefaultChatModelCombo;
    @Nullable
    private ComboBox<String> settingsTitleNamingModelCombo;

    @Nullable
    private String editingProfileId;
    @Nullable
    private String expandedProfileId;

    // ---- Chat settings ----

    private final ChatSettings chatSettings;
    private static final Gson CHAT_SETTINGS_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String CHAT_SETTINGS_FILE = "ai-chat-settings.json";

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
    @Nullable
    private Label fileChip;
    @Nullable
    private Path selectedFilePath;

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

        this.sessionStore = new AiSessionStore(SettingsManager.localConfigDirectory());
        try {
            sessionStore.load();
        } catch (Exception ignored) {
        }

        this.chatSettings = loadChatSettings();

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
        buildSettingsView();
        initProfileFormFields();
        buildProviderModal();
        buildChatSettingsDrawer();
        buildLayout();

        registerTools();

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
        toolRegistry.register(new CrashAnalyzerTool());
        toolRegistry.register(new FileBackupTool());
        toolRegistry.register(new ModToggleTool());
        // LogReaderTool requires game directory context; registered lazily when available
    }

    // ---- Layout assembly ----

    private void buildLayout() {
        chatSettingsStack.getChildren().setAll(chatView, settingsView, buildSearchOverlay());
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

        // Provider modal overlay (topmost layer)
        if (providerModalOverlay != null) {
            centerWithDrawer.getChildren().addAll(chatSettingsBackdrop, chatSettingsDrawer != null ? chatSettingsDrawer : new Region(), providerModalOverlay);
        } else {
            if (chatSettingsDrawer != null) {
                centerWithDrawer.getChildren().addAll(chatSettingsBackdrop, chatSettingsDrawer);
            }
        }

        setCenter(centerWithDrawer);
        showChatView();
    }

    // ---- Sidebar ----

    private void buildSidebar() {
        sessionListBox.setPadding(new Insets(0, 0, 4, 0));

        sidebarRoot = new VBox();
        VBox.setVgrow(sidebarRoot, Priority.ALWAYS);
        setLeft(sidebarRoot);
        rebuildChatSidebar();
    }

    private void rebuildChatSidebar() {
        if (sidebarRoot == null) return;
        sidebarRoot.getChildren().clear();

        JFXButton newChatBtn = FXUtils.newRaisedButton(i18n("ai.new_conversation"));
        newChatBtn.getStyleClass().add("ai-sidebar-new-btn");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setPadding(new Insets(6, 12, 6, 12));
        newChatBtn.setOnAction(e -> createSession());

        newChatBtn.setPadding(new Insets(0, 8, 4, 8));

        sidebarScrollPane = new AdvancedListBox();
        sidebarScrollPane.add(newChatBtn);
        sidebarScrollPane.add(sessionListBox);
        VBox.setVgrow(sidebarScrollPane, Priority.ALWAYS);

        AdvancedListItem settingsItem = new AdvancedListItem();
        settingsItem.getStyleClass().add("navigation-drawer-item");
        settingsItem.setTitle(i18n("ai.settings"));
        settingsItem.setLeftIcon(SVG.TUNE);
        settingsItem.setOnAction(e -> showSettingsView());

        sidebarRoot.getChildren().addAll(sidebarScrollPane, settingsItem);
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

    private AdvancedListItem buildSessionItem(AiSession session, boolean isActive) {
        String labelText = (session.getTitle() != null && !session.getTitle().isEmpty())
                ? session.getTitle() : i18n("ai.session.untitled");

        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(labelText);
        item.setActive(isActive);
        item.getStyleClass().add("ai-session-item");
        item.setRightAction(SVG.DELETE, () -> deleteSession(session.getId()));
        item.setOnAction(e -> {
            sessionStore.setCurrentSessionId(session.getId());
            AiSession current = sessionStore.getCurrentSession();
            if (current != null) {
                loadSessionMessages(current);
                updateHeader(current);
            }
            refreshSessionList();
            showChatView();
            try {
                sessionStore.save();
            } catch (Exception ignored) {
            }
        });

        return item;
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
        settingsView.setVisible(false);
        settingsView.setManaged(false);
        settingsActive = false;
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

    private void showSettingsView() {
        chatView.setVisible(false);
        chatView.setManaged(false);
        settingsView.setVisible(true);
        settingsView.setManaged(true);
        settingsActive = true;
        state.set(new State(i18n("ai.settings"), null, true, false, true));
        // Transform sidebar into settings tab navigation
        if (sidebarRoot != null) {
            sidebarRoot.getChildren().clear();
            AdvancedListBox settingsListBox = new AdvancedListBox();

            settingsNavItems.clear();
            settingsNavItems.put("provider", createSettingsNavItem("模型服务", SVG.DEPLOYED_CODE, "provider", settingsListBox));
            settingsNavItems.put("general", createSettingsNavItem("常规设置", SVG.TUNE, "general", settingsListBox));
            settingsNavItems.put("mcp", createSettingsNavItem("MCP服务器", SVG.SCHEMA, "mcp", settingsListBox));
            settingsNavItems.put("skills", createSettingsNavItem("技能", SVG.SCRIPT, "skills", settingsListBox));
            settingsNavItems.put("search", createSettingsNavItem("网络搜索", SVG.SEARCH, "search", settingsListBox));
            settingsNavItems.put("memory", createSettingsNavItem("全局记忆", SVG.PACKAGE, "memory", settingsListBox));
            VBox.setVgrow(settingsListBox, Priority.ALWAYS);

            // Bottom fixed items: 帮助, 关于
            AdvancedListBox bottomItems = new AdvancedListBox();
            settingsNavItems.put("help", createSettingsNavItem("帮助", SVG.FEEDBACK, "help", bottomItems));
            settingsNavItems.put("about", createSettingsNavItem("关于", SVG.INFO_FILL, "about", bottomItems));

            sidebarRoot.getChildren().addAll(settingsListBox, bottomItems);
        }
        headerTitle.setText(i18n("ai.settings"));
        headerSubtitle.setText("");
        approvalBadge.setVisible(false);
        approvalBadge.setManaged(false);
        refreshProfileList();
        switchSettingsTab(activeSettingsTab);
    }

    @Override
    public boolean back() {
        if (settingsActive) {
            showChatView();
            return false;
        }
        return true;
    }

    // ---- Provider Modal ----

    private void buildProviderModal() {
        StackPane backdrop = new StackPane();
        backdrop.getStyleClass().add("ai-modal-backdrop");
        backdrop.setOnMouseClicked(e -> hideProviderModal());

        VBox card = new VBox(12);
        card.setMaxWidth(460);
        card.setMaxHeight(400);
        card.getStyleClass().add("ai-modal-card");
        card.setPadding(new Insets(20));
        card.setOnMouseClicked(e -> e.consume());

        Label modalTitle = new Label(i18n("ai.settings.add_profile"));
        modalTitle.getStyleClass().add("ai-modal-title");

        JFXButton closeBtn = new JFXButton("✕");
        closeBtn.getStyleClass().add("ai-modal-close-btn");
        closeBtn.setOnAction(e -> hideProviderModal());

        HBox header = new HBox(modalTitle, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modalTitle, Priority.ALWAYS);

        Label nameLabel = new Label(i18n("ai.settings.profile_name"));
        nameLabel.getStyleClass().add("ai-settings-label");

        Label protocolLabel = new Label(i18n("ai.settings.protocol_family"));
        protocolLabel.getStyleClass().add("ai-settings-label");

        Label endpointLabel = new Label(i18n("ai.settings.endpoint"));
        endpointLabel.getStyleClass().add("ai-settings-label");

        Label apiKeyLabel = new Label(i18n("ai.settings.api_key"));
        apiKeyLabel.getStyleClass().add("ai-settings-label");

        VBox body = new VBox(8,
                nameLabel, settingsProfileNameField,
                protocolLabel, settingsProtocolCombo,
                endpointLabel, settingsEndpointField,
                apiKeyLabel, settingsApiKeyField,
                settingsFeedbackLabel);
        body.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane scrollBody = new ScrollPane(body);
        scrollBody.setFitToWidth(true);
        scrollBody.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollBody, Priority.ALWAYS);

        settingsTestBtn = new JFXButton(i18n("ai.settings.test"));
        settingsTestBtn.getStyleClass().add("suggested");
        settingsTestBtn.setOnAction(e -> testConnection());

        JFXButton saveBtn = new JFXButton(i18n("button.save"));
        saveBtn.getStyleClass().add("suggested");
        saveBtn.setOnAction(e -> { saveCurrentProfile(); saveSettings(); hideProviderModal(); });

        JFXButton cancelBtn = new JFXButton(i18n("button.cancel"));
        cancelBtn.getStyleClass().add("ai-profile-item-btn");
        cancelBtn.setOnAction(e -> hideProviderModal());

        HBox footer = new HBox(8, settingsTestBtn, cancelBtn, saveBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(settingsTestBtn, Priority.ALWAYS);
        footer.getStyleClass().add("ai-modal-footer");

        card.getChildren().setAll(header, scrollBody, footer);
        providerModalCard = card;

        providerModalOverlay = new StackPane(backdrop, card);
        providerModalOverlay.setVisible(false);
        providerModalOverlay.setManaged(false);
        StackPane.setAlignment(card, Pos.CENTER);
    }

    private void showProviderModal(@Nullable String profileId) {
        if (providerModalOverlay == null) return;
        editingProfileId = profileId;
        if (profileId != null) {
            AiProviderProfile profile = findProfileById(profileId);
            if (profile != null) {
                settingsProfileNameField.setText(profile.getDisplayName());
                settingsProtocolCombo.setValue(profile.getProtocolFamily());
                settingsEndpointField.setText(profile.getEndpoint());
                settingsApiKeyField.setText(profile.getApiKey());
            }
        } else {
            settingsProfileNameField.setText("");
            settingsProtocolCombo.setValue(AiProtocolFamily.OPENAI_COMPLETIONS.getId());
            settingsEndpointField.setText("");
            settingsApiKeyField.setText("");
        }
        if (settingsFeedbackLabel != null) settingsFeedbackLabel.setText("");
        providerModalOverlay.setVisible(true);
        providerModalOverlay.setManaged(true);
    }

    private void hideProviderModal() {
        if (providerModalOverlay == null) return;
        providerModalOverlay.setVisible(false);
        providerModalOverlay.setManaged(false);
        editingProfileId = null;
        refreshProfileList();
        refreshModelSelector();
    }

    // ---- Settings panel ----

    private void buildSettingsView() {
        settingsView.setPadding(new Insets(16, 24, 16, 24));
        settingsView.setSpacing(12);
        settingsView.getStyleClass().add("ai-settings-view");

        Label settingsHeader = new Label(i18n("ai.settings"));
        settingsHeader.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        settingsHeader.getStyleClass().add("ai-settings-header");

        // ---- Tab: 模型服务 ----
        Label profilesSectionTitle = new Label(i18n("ai.settings.profiles"));
        profilesSectionTitle.getStyleClass().add("ai-settings-section-title");

        settingsProfileListBox = new VBox(4);
        settingsProfileListBox.getStyleClass().add("ai-profile-list");

        JFXButton addProfileBtn = new JFXButton(i18n("ai.settings.add_profile"));
        addProfileBtn.getStyleClass().add("suggested");
        addProfileBtn.setOnAction(e -> showProviderModal(null));

        settingsFeedbackLabel = new Label();
        settingsFeedbackLabel.setWrapText(true);

        JFXButton saveBtn = new JFXButton(i18n("button.save"));
        saveBtn.getStyleClass().add("suggested");
        saveBtn.setOnAction(e -> saveSettings());

        HBox actionRow = new HBox(8, addProfileBtn, saveBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox profilesSection = new VBox(8);
        profilesSection.getStyleClass().add("ai-settings-section");
        profilesSection.getChildren().setAll(profilesSectionTitle, settingsProfileListBox, settingsFeedbackLabel, actionRow);

        VBox providerTab = new VBox(16, profilesSection);

        // ---- Tab: 常规设置 ----
        VBox globalAISection = buildGlobalAISettingsSection();
        VBox generalTab = new VBox(16, globalAISection);

        VBox mcpTab = buildPlaceholderSettingsTab("MCP服务器", "MCP 服务器配置将在这里管理。");
        VBox skillsTab = buildPlaceholderSettingsTab("技能", "技能配置将在这里管理。");
        VBox searchTab = buildPlaceholderSettingsTab("网络搜索", "网络搜索配置将在这里管理。");
        VBox memoryTab = buildPlaceholderSettingsTab("全局记忆", "全局记忆配置将在这里管理。");
        VBox helpTab = buildHelpTab();
        VBox aboutTab = buildAboutTab();

        settingsTabs.clear();
        settingsTabs.put("provider", providerTab);
        settingsTabs.put("general", generalTab);
        settingsTabs.put("mcp", mcpTab);
        settingsTabs.put("skills", skillsTab);
        settingsTabs.put("search", searchTab);
        settingsTabs.put("memory", memoryTab);
        settingsTabs.put("help", helpTab);
        settingsTabs.put("about", aboutTab);

        settingsBodyStack = new StackPane();
        settingsBodyStack.getStyleClass().add("ai-settings-body-stack");
        settingsTabs.values().forEach(tab -> {
            tab.setVisible(false);
            tab.setManaged(false);
            settingsBodyStack.getChildren().add(tab);
        });

        ScrollPane settingsScroll = new ScrollPane(settingsBodyStack);
        settingsScroll.setFitToWidth(true);
        settingsScroll.getStyleClass().addAll("edge-to-edge", "ai-settings-scroll");
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);

        settingsView.getChildren().setAll(settingsHeader, settingsScroll);
    }

    private void switchSettingsTab(String key) {
        activeSettingsTab = key;
        if (settingsBodyStack != null) {
            settingsTabs.forEach((tabKey, tab) -> {
                boolean active = tabKey.equals(key);
                tab.setVisible(active);
                tab.setManaged(active);
            });
        }
        settingsNavItems.forEach((navKey, item) -> item.setActive(navKey.equals(key)));
    }

    private AdvancedListItem createSettingsNavItem(String title, SVG icon, String key, AdvancedListBox parent) {
        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("navigation-drawer-item");
        item.setTitle(title);
        item.setLeftIcon(icon);
        item.setOnAction(e -> switchSettingsTab(key));
        parent.add(item);
        return item;
    }

    private VBox buildHelpTab() {
        VBox tab = new VBox(12);
        tab.getStyleClass().add("ai-settings-section");

        Label header = new Label("AI 助手帮助");
        header.getStyleClass().add("ai-settings-section-title");

        Label desc = new Label("HMCL-AE 为启动器集成了 AI 助手，支持对话、崩溃分析、模组管理等。\n\n"
                + "快速开始：\n"
                + "1. 在「模型服务」中添加一个提供商（OpenAI / DeepSeek / Ollama 等）\n"
                + "2. 配置 API 端点和密钥\n"
                + "3. 返回对话界面即可开始使用\n\n"
                + "详细文档请访问 GitHub 仓库：");
        desc.setWrapText(true);
        desc.getStyleClass().add("ai-header-subtitle");

        Label repoLink = new Label("github.com/HMCL-dev/HMCL");
        repoLink.getStyleClass().add("ai-help-link");
        repoLink.setStyle("-fx-text-fill: -monet-primary; -fx-underline: true; -fx-cursor: hand;");
        repoLink.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        java.net.URI.create("https://github.com/HMCL-dev/HMCL"));
            } catch (Exception ignored) {
            }
        });

        tab.getChildren().addAll(header, desc, repoLink);
        return tab;
    }

    private VBox buildAboutTab() {
        VBox tab = new VBox(12);
        tab.getStyleClass().add("ai-settings-section");

        Label header = new Label("关于 AI 助手");
        header.getStyleClass().add("ai-settings-section-title");

        Label desc = new Label("HMCL-AE (Agent Experience)\n"
                + "为 HMCL 启动器集成的 AI 助手功能\n\n"
                + "HMCL 版权所有 © huangyuhui 及贡献者\n"
                + "基于 GPLv3 许可证分发");
        desc.setWrapText(true);
        desc.getStyleClass().add("ai-header-subtitle");

        tab.getChildren().addAll(header, desc);
        return tab;
    }

    private VBox buildPlaceholderSettingsTab(String title, String description) {
        VBox tab = new VBox(12);
        tab.getStyleClass().add("ai-settings-section");
        Label header = new Label(title);
        header.getStyleClass().add("ai-settings-section-title");
        Label desc = new Label(description);
        desc.getStyleClass().add("ai-header-subtitle");
        desc.setWrapText(true);
        tab.getChildren().addAll(header, desc);
        return tab;
    }

    private void initProfileFormFields() {
        settingsProfileNameField = new JFXTextField();
        settingsProfileNameField.setPromptText("My Provider");
        settingsProfileNameField.setMaxWidth(Double.MAX_VALUE);

        settingsProtocolCombo = new ComboBox<>();
        settingsProtocolCombo.setPromptText(i18n("ai.settings.select_protocol"));
        settingsProtocolCombo.setMaxWidth(Double.MAX_VALUE);
        settingsProtocolCombo.getStyleClass().add("ai-settings-combo");
        for (AiProtocolFamily family : AiProtocolFamily.values()) {
            settingsProtocolCombo.getItems().add(family.getId());
        }
        settingsProtocolCombo.setOnAction(e -> {
            String protocolId = settingsProtocolCombo.getValue();
            if (protocolId != null) {
                AiProtocolFamily family = AiProtocolFamily.fromId(protocolId);
                if (family != null) {
                    String currentEndpoint = settingsEndpointField.getText();
                    if (currentEndpoint == null || currentEndpoint.isEmpty()) {
                        AiProviderDefinition def = AiProviderDefinition.byId(protocolId);
                        if (def != null) {
                            settingsEndpointField.setText(def.getDefaultEndpoint());
                        }
                    }
                }
            }
        });

        settingsEndpointField = new JFXTextField();
        settingsEndpointField.setPromptText("api.openai.com");
        settingsEndpointField.setMaxWidth(Double.MAX_VALUE);

        settingsApiKeyField = new JFXPasswordField();
        settingsApiKeyField.setPromptText("sk-...");
        settingsApiKeyField.setMaxWidth(Double.MAX_VALUE);

        settingsModelCombo = new ComboBox<>();
        settingsModelCombo.setEditable(true);
        settingsModelCombo.setPromptText(LlmConfig.DEFAULT_MODEL);
        settingsModelCombo.setMaxWidth(Double.MAX_VALUE);
        settingsModelCombo.getStyleClass().add("ai-settings-combo");

        settingsDiscoverFeedback = new Label();
        settingsDiscoverFeedback.setWrapText(true);
    }

    /// mirroring the HTML prototype style.
    private VBox buildModelConfigSection() {
        VBox section = new VBox(8);
        section.getStyleClass().add("ai-settings-section");

        Label sectionTitle = new Label(i18n("ai.settings.model_config"));
        sectionTitle.getStyleClass().add("ai-settings-section-title");

        // Collapsible advanced toggle
        Label advancedToggle = new Label("▶ " + i18n("ai.settings.advanced"));
        advancedToggle.getStyleClass().add("ai-advanced-toggle");
        advancedToggle.setPadding(new Insets(4, 0, 0, 0));

        settingsAdvancedBox = new VBox(8);
        settingsAdvancedBox.setPadding(new Insets(4, 0, 0, 0));
        settingsAdvancedBox.setVisible(false);
        settingsAdvancedBox.setManaged(false);

        advancedToggle.setOnMouseClicked(e -> {
            boolean visible = !settingsAdvancedBox.isVisible();
            settingsAdvancedBox.setVisible(visible);
            settingsAdvancedBox.setManaged(visible);
            advancedToggle.setText((visible ? "▼ " : "▶ ") + i18n("ai.settings.advanced"));
        });

        Label contextLabel = new Label(i18n("ai.settings.context_window"));
        contextLabel.getStyleClass().add("ai-settings-label");
        settingsContextWindowField = new JFXTextField();
        settingsContextWindowField.setPromptText(String.valueOf(LlmConfig.DEFAULT_CONTEXT_WINDOW));
        settingsContextWindowField.setMaxWidth(Double.MAX_VALUE);

        Label maxOutputLabel = new Label(i18n("ai.settings.max_output_tokens"));
        maxOutputLabel.getStyleClass().add("ai-settings-label");
        settingsMaxOutputField = new JFXTextField();
        settingsMaxOutputField.setPromptText(String.valueOf(LlmConfig.DEFAULT_MAX_TOKENS));
        settingsMaxOutputField.setMaxWidth(Double.MAX_VALUE);

        Label tempLabel = new Label(i18n("ai.settings.temperature"));
        tempLabel.getStyleClass().add("ai-settings-label");
        settingsTemperatureField = new JFXTextField();
        settingsTemperatureField.setPromptText(String.valueOf(LlmConfig.DEFAULT_TEMPERATURE));
        settingsTemperatureField.setMaxWidth(Double.MAX_VALUE);

        Label topPLabel = new Label(i18n("ai.settings.top_p"));
        topPLabel.getStyleClass().add("ai-settings-label");
        settingsTopPField = new JFXTextField();
        settingsTopPField.setPromptText(String.valueOf(LlmConfig.DEFAULT_TOP_P));
        settingsTopPField.setMaxWidth(Double.MAX_VALUE);

        Label presenceLabel = new Label(i18n("ai.settings.presence_penalty"));
        presenceLabel.getStyleClass().add("ai-settings-label");
        settingsPresencePenaltyField = new JFXTextField();
        settingsPresencePenaltyField.setPromptText(String.valueOf(LlmConfig.DEFAULT_PRESENCE_PENALTY));
        settingsPresencePenaltyField.setMaxWidth(Double.MAX_VALUE);

        Label freqLabel = new Label(i18n("ai.settings.frequency_penalty"));
        freqLabel.getStyleClass().add("ai-settings-label");
        settingsFrequencyPenaltyField = new JFXTextField();
        settingsFrequencyPenaltyField.setPromptText(String.valueOf(LlmConfig.DEFAULT_FREQUENCY_PENALTY));
        settingsFrequencyPenaltyField.setMaxWidth(Double.MAX_VALUE);

        Label seedLabel = new Label(i18n("ai.settings.seed"));
        seedLabel.getStyleClass().add("ai-settings-label");
        settingsSeedField = new JFXTextField();
        settingsSeedField.setPromptText("");
        settingsSeedField.setMaxWidth(Double.MAX_VALUE);

        Label reasoningLabel = new Label("默认思考等级");
        reasoningLabel.getStyleClass().add("ai-settings-label");
        settingsReasoningEffortField = new JFXTextField(); // placeholder, not used anymore
        ComboBox<String> reasoningCombo = new ComboBox<>();
        reasoningCombo.getItems().setAll("none", "low", "medium", "high", "xhigh", "max");
        reasoningCombo.setValue("none");
        reasoningCombo.setMaxWidth(Double.MAX_VALUE);
        reasoningCombo.getStyleClass().add("ai-toolbar-selector");

        settingsAdvancedBox.getChildren().setAll(
                contextLabel, settingsContextWindowField,
                maxOutputLabel, settingsMaxOutputField,
                tempLabel, settingsTemperatureField,
                topPLabel, settingsTopPField,
                reasoningLabel, reasoningCombo
        );

        section.getChildren().setAll(sectionTitle, advancedToggle, settingsAdvancedBox);
        return section;
    }

    /// Builds the global AI behavior settings section with toggles and an
    /// approval mode selector. Settings are backed by {@link AiSettings}
    /// JavaFX properties and persist automatically.
    private VBox buildGlobalAISettingsSection() {
        VBox section = new VBox(10);
        section.getStyleClass().addAll("ai-settings-section", "ai-global-section");

        Label sectionLabel = new Label(i18n("ai.settings.global"));
        sectionLabel.getStyleClass().addAll("ai-settings-section-title", "ai-global-section-label");

        // Title naming toggle
        settingsTitleNamingCheck = new CheckBox(i18n("ai.settings.title_naming"));
        settingsTitleNamingCheck.setSelected(aiSettings.isTitleNamingEnabled());
        settingsTitleNamingCheck.selectedProperty().bindBidirectional(
                aiSettings.titleNamingEnabledProperty());
        Label titleNamingDesc = new Label(i18n("ai.settings.title_naming.desc"));
        titleNamingDesc.getStyleClass().add("ai-global-toggle-desc");

        // Title naming model selector
        Label titleModelLabel = new Label(i18n("ai.settings.title_naming_model"));
        titleModelLabel.getStyleClass().add("ai-settings-label");
        settingsTitleNamingModelCombo = new ComboBox<>();
        settingsTitleNamingModelCombo.setPromptText("Auto (uses default)");
        settingsTitleNamingModelCombo.setMaxWidth(Double.MAX_VALUE);
        settingsTitleNamingModelCombo.getStyleClass().add("ai-toolbar-selector");
        settingsTitleNamingModelCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) aiSettings.titleNamingModelIdProperty().set(val);
        });

        VBox titleNamingRow = new VBox(4,
                settingsTitleNamingCheck, titleNamingDesc,
                titleModelLabel, settingsTitleNamingModelCombo);

        // Auto crash analysis toggle
        settingsAutoCrashAnalysisCheck = new CheckBox(i18n("ai.settings.auto_crash_analysis"));
        settingsAutoCrashAnalysisCheck.setSelected(aiSettings.isAutoCrashAnalysisEnabled());
        settingsAutoCrashAnalysisCheck.selectedProperty().bindBidirectional(
                aiSettings.autoCrashAnalysisEnabledProperty());
        Label crashAnalysisDesc = new Label(i18n("ai.settings.auto_crash_analysis.desc"));
        crashAnalysisDesc.getStyleClass().add("ai-global-toggle-desc");

        // Tool call display toggle
        settingsToolCallDisplayCheck = new CheckBox(i18n("ai.settings.tool_call_display"));
        settingsToolCallDisplayCheck.setSelected(aiSettings.isToolCallDisplayEnabled());
        settingsToolCallDisplayCheck.selectedProperty().bindBidirectional(
                aiSettings.toolCallDisplayEnabledProperty());
        Label toolCallDesc = new Label(i18n("ai.settings.tool_call_display.desc"));
        toolCallDesc.getStyleClass().add("ai-global-toggle-desc");

        // Approval mode selector
        Label approvalLabel = new Label(i18n("ai.settings.approval_mode"));
        approvalLabel.getStyleClass().add("ai-settings-label");
        Label approvalDesc = new Label(i18n("ai.settings.approval_mode.desc"));
        approvalDesc.getStyleClass().add("ai-global-toggle-desc");
        approvalDesc.setPadding(new Insets(0, 0, 4, 0));

        settingsApprovalModeCombo = new ComboBox<>();
        settingsApprovalModeCombo.getStyleClass().add("ai-global-combo");
        settingsApprovalModeCombo.getItems().setAll(
                i18n("ai.settings.approval_mode_safe"),
                i18n("ai.settings.approval_mode_ask"),
                i18n("ai.settings.approval_mode_yolo"));
        selectApprovalModeComboItem(aiSettings.getApprovalMode());
        settingsApprovalModeCombo.setOnAction(e -> {
            int idx = settingsApprovalModeCombo.getSelectionModel().getSelectedIndex();
            AiApprovalMode mode = switch (idx) {
                case 0 -> AiApprovalMode.SAFE;
                case 1 -> AiApprovalMode.ASK;
                case 2 -> AiApprovalMode.YOLO;
                default -> AiApprovalMode.SAFE;
            };
            aiSettings.approvalModeProperty().set(mode.getId());
        });

        VBox approvalRow = new VBox(2, approvalLabel, approvalDesc, settingsApprovalModeCombo);

        section.getChildren().setAll(
                sectionLabel,
                titleNamingRow,
                settingsAutoCrashAnalysisCheck, crashAnalysisDesc,
                settingsToolCallDisplayCheck, toolCallDesc,
                approvalRow
        );

        return section;
    }

    /// Selects the combo box item matching the given approval mode id.
    private void selectApprovalModeComboItem(String modeId) {
        if (settingsApprovalModeCombo == null) return;
        AiApprovalMode mode = AiApprovalMode.fromId(modeId);
        int idx = switch (mode) {
            case SAFE -> 0;
            case ASK -> 1;
            case YOLO -> 2;
        };
        if (idx >= 0 && idx < settingsApprovalModeCombo.getItems().size()) {
            settingsApprovalModeCombo.getSelectionModel().select(idx);
        }
    }

    // ---- Profile list management ----

    private void refreshProfileList() {
        if (settingsProfileListBox == null) return;

        ObservableList<Node> children = settingsProfileListBox.getChildren();
        children.clear();

        List<AiProviderProfile> profiles = aiSettings.getProfiles();
        String selectedId = aiSettings.getSelectedProfileId();

        for (AiProviderProfile profile : profiles) {
            boolean isActive = profile.getId().equals(selectedId);
            children.add(buildProfileListItem(profile, isActive));

            // Show model list when expanded
            if (profile.getId().equals(expandedProfileId)) {
                VBox modelSection = buildProfileModelSection(profile);
                children.add(modelSection);
            }
        }
    }

    private VBox buildProfileModelSection(AiProviderProfile profile) {
        VBox section = new VBox(4);
        section.setPadding(new Insets(4, 0, 8, 24));
        section.getStyleClass().add("ai-profile-model-section");

        List<String> models = profile.getCachedModels();
        for (String modelId : models) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));

            Label modelName = new Label(modelId);
            modelName.setMaxWidth(Double.MAX_VALUE);
            modelName.getStyleClass().add("ai-profile-name");
            HBox.setHgrow(modelName, Priority.ALWAYS);

            JFXButton delModelBtn = new JFXButton("删除");
            delModelBtn.getStyleClass().addAll("ai-profile-item-btn", "danger");
            delModelBtn.setOnAction(ev -> {
                profile.getCachedModels().remove(modelId);
                try { aiSettings.save(); } catch (Exception ignored) {}
                refreshProfileList();
                ev.consume();
            });

            row.getChildren().addAll(modelName, delModelBtn);
            section.getChildren().add(row);
        }

        JFXButton addModelBtn = new JFXButton("+ 添加模型");
        addModelBtn.getStyleClass().add("ai-add-model-btn");
        addModelBtn.setMaxWidth(Double.MAX_VALUE);
        addModelBtn.setOnAction(e -> showModelModal(profile.getId(), null));
        section.getChildren().add(addModelBtn);

        return section;
    }

    private void showModelModal(String profileId, @Nullable String modelId) {
        AiProviderProfile profile = findProfileById(profileId);
        if (profile == null) return;

        StackPane backdrop = new StackPane();
        backdrop.getStyleClass().add("ai-modal-backdrop");

        VBox card = new VBox(12);
        card.setMaxWidth(460);
        card.setMaxHeight(500);
        card.getStyleClass().add("ai-modal-card");
        card.setPadding(new Insets(20));
        card.setOnMouseClicked(e -> e.consume());

        Label title = new Label(modelId != null ? "编辑模型" : "添加模型");
        title.getStyleClass().add("ai-modal-title");

        JFXButton closeBtn = new JFXButton("✕");
        closeBtn.getStyleClass().add("ai-modal-close-btn");

        HBox header = new HBox(title, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        // Model ID with discover button
        Label idLabel = new Label("模型 ID");
        idLabel.getStyleClass().add("ai-settings-label");
        TextField modelIdField = new TextField();
        modelIdField.setPromptText("gpt-4o");
        modelIdField.setMaxWidth(Double.MAX_VALUE);
        if (modelId != null) modelIdField.setText(modelId);

        Label aliasLabel = new Label("别名");
        aliasLabel.getStyleClass().add("ai-settings-label");
        TextField modelAliasField = new TextField();
        modelAliasField.setPromptText("GPT-4o 快速");
        modelAliasField.setMaxWidth(Double.MAX_VALUE);
        if (modelId != null) {
            String existingAlias = profile.getModelAliases().get(modelId);
            if (existingAlias != null) modelAliasField.setText(existingAlias);
        }
        ComboBox<String> discoveredModelsCombo = new ComboBox<>();
        discoveredModelsCombo.setPromptText("从服务器获取模型...");
        discoveredModelsCombo.setMaxWidth(Double.MAX_VALUE);
        discoveredModelsCombo.getStyleClass().add("ai-toolbar-selector");

        JFXButton fetchBtn = new JFXButton("获取模型");
        fetchBtn.getStyleClass().add("ai-discover-btn");
        Label discoverFeedback = new Label();
        discoverFeedback.setWrapText(true);

        fetchBtn.setOnAction(ev -> {
            discoverFeedback.setText("正在获取...");
            discoverFeedback.setStyle("");
            AiProviderProfile tempProfile = new AiProviderProfile();
            tempProfile.setEndpoint(profile.getEndpoint());
            tempProfile.setApiKey(profile.getApiKey());
            tempProfile.setProtocolFamily(profile.getProtocolFamily());

            CompletableFuture.runAsync(() -> {
                try {
                    List<String> models = discoveryService.discoverModels(tempProfile);
                    Platform.runLater(() -> {
                        discoveredModelsCombo.getItems().setAll(models);
                        discoverFeedback.setText("发现 " + models.size() + " 个模型");
                        discoverFeedback.setStyle("-fx-text-fill: green;");
                    });
                } catch (InterruptedException ex) {
                    Platform.runLater(() -> {
                        discoverFeedback.setText("超时");
                        discoverFeedback.setStyle("-fx-text-fill: red;");
                    });
                    Thread.currentThread().interrupt();
                } catch (IOException ex) {
                    Platform.runLater(() -> {
                        discoverFeedback.setText("连接失败");
                        discoverFeedback.setStyle("-fx-text-fill: red;");
                    });
                }
            });
        });

        discoveredModelsCombo.setOnAction(ev -> {
            String selected = discoveredModelsCombo.getValue();
            if (selected != null && !selected.isEmpty()) {
                modelIdField.setText(selected);
            }
        });
        HBox modelRow = new HBox(6, modelIdField, fetchBtn);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        // Reasoning combo
        Label reasoningLabel = new Label("默认思考等级");
        reasoningLabel.getStyleClass().add("ai-settings-label");
        ComboBox<String> reasoningCombo = new ComboBox<>();
        reasoningCombo.getItems().setAll("none", "low", "medium", "high", "xhigh", "max");
        reasoningCombo.setValue("none");
        reasoningCombo.setMaxWidth(Double.MAX_VALUE);
        reasoningCombo.getStyleClass().add("ai-toolbar-selector");

        // Stream toggle
        CheckBox streamCheck = new CheckBox("启用流式输出");
        streamCheck.setSelected(true);

        // Advanced collapsible
        Label advancedToggle = new Label("▶ 高级设置");
        advancedToggle.getStyleClass().add("ai-advanced-toggle");
        VBox advancedBox = new VBox(8);
        advancedBox.setVisible(false);
        advancedBox.setManaged(false);

        advancedToggle.setOnMouseClicked(e -> {
            boolean vis = !advancedBox.isVisible();
            advancedBox.setVisible(vis);
            advancedBox.setManaged(vis);
            advancedToggle.setText((vis ? "▼ " : "▶ ") + "高级设置");
        });

        TextField ctxWin = new TextField();
        ctxWin.setPromptText("128000");
        TextField maxOut = new TextField();
        maxOut.setPromptText("4096");
        TextField temp = new TextField();
        temp.setPromptText("0.7");
        TextField topP = new TextField();
        topP.setPromptText("1.0");

        advancedBox.getChildren().setAll(
                new Label("上下文窗口"), ctxWin,
                new Label("最大输出 Token"), maxOut,
                new Label("温度"), temp,
                new Label("Top P"), topP
        );

        VBox body = new VBox(8,
                idLabel, modelRow,
                aliasLabel, modelAliasField,
                discoveredModelsCombo, discoverFeedback,
                reasoningLabel, reasoningCombo,
                streamCheck,
                advancedToggle, advancedBox);
        body.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane scrollBody = new ScrollPane(body);
        scrollBody.setFitToWidth(true);
        scrollBody.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scrollBody, Priority.ALWAYS);

        JFXButton saveBtn = new JFXButton("保存");
        saveBtn.getStyleClass().add("suggested");
        saveBtn.setStyle("-fx-background-color: -monet-primary-container; -fx-text-fill: -monet-on-primary-container;");

        JFXButton cancelBtn = new JFXButton("取消");
        cancelBtn.getStyleClass().add("ai-profile-item-btn");

        HBox footer = new HBox(8, cancelBtn, saveBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("ai-modal-footer");

        card.getChildren().setAll(header, scrollBody, footer);

        StackPane overlay = new StackPane(backdrop, card);
        overlay.setVisible(true);
        StackPane.setAlignment(card, Pos.CENTER);

        // Add to layout
        StackPane root = (StackPane) getCenter();
        if (root != null) {
            root.getChildren().add(overlay);
        }

        Runnable close = () -> {
            overlay.setVisible(false);
            if (root != null) root.getChildren().remove(overlay);
        };
        backdrop.setOnMouseClicked(e -> close.run());
        closeBtn.setOnAction(e -> close.run());
        cancelBtn.setOnAction(e -> close.run());

        saveBtn.setOnAction(e -> {
            String newModelId = modelIdField.getText().trim();
            if (newModelId.isEmpty()) return;

            List<String> models = new ArrayList<>(profile.getCachedModels());
            if (modelId != null) models.remove(modelId);
            if (!models.contains(newModelId)) models.add(newModelId);
            profile.setCachedModels(models);
            if (profile.getDefaultModelId() == null || profile.getDefaultModelId().isEmpty()
                    || profile.getDefaultModelId().equals(modelId)) {
                profile.setDefaultModelId(newModelId);
            }
            aiSettings.putProfile(profile);
            try { aiSettings.save(); } catch (Exception ignored) {}
            refreshProfileList();
            refreshModelSelector();
            close.run();
        });
    }

    private AdvancedListItem buildProfileListItem(AiProviderProfile profile, boolean isActive) {
        String displayName = profile.getDisplayName();
        String label = (displayName != null && !displayName.isEmpty())
                ? displayName
                : profile.getId().substring(0, Math.min(8, profile.getId().length()));

        String endpoint = profile.getEndpoint();
        String metaText = profile.getProtocolFamily();
        if (endpoint != null && !endpoint.isEmpty()) {
            metaText += " \u00b7 " + endpoint;
        }

        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("ai-profile-item");
        item.setTitle(label);
        item.setSubtitle(metaText);
        item.setActive(isActive);
        item.getStyleClass().add("profile-list-item");

        JFXButton editBtn = new JFXButton(i18n("ai.profile.edit"));
        editBtn.getStyleClass().add("ai-profile-item-btn");
        editBtn.setOnAction(ev -> { showProviderModal(profile.getId()); ev.consume(); });

        JFXButton delBtn = new JFXButton(i18n("ai.profile.delete"));
        delBtn.getStyleClass().addAll("ai-profile-item-btn", "danger");
        delBtn.setOnAction(ev -> { aiSettings.removeProfile(profile.getId()); try { aiSettings.save(); } catch (Exception ignored) {} refreshProfileList(); refreshModelSelector(); ev.consume(); });

        HBox btns = new HBox(4, editBtn, delBtn);
        btns.setAlignment(Pos.CENTER);
        item.setRightGraphic(btns);

        item.setOnAction(e -> {
            aiSettings.setSelectedProfileId(profile.getId());
            if (profile.getId().equals(expandedProfileId)) {
                expandedProfileId = null;
            } else {
                expandedProfileId = profile.getId();
            }
            refreshProfileList();
        });

        return item;
    }

    private void saveCurrentProfile() {
        String name = settingsProfileNameField.getText();
        String protocol = settingsProtocolCombo.getValue();
        String endpoint = settingsEndpointField.getText();
        String apiKey = settingsApiKeyField.getText();

        if (protocol == null || protocol.isEmpty()) {
            setFeedback(i18n("ai.settings.save_failed", "Protocol family is required"), false);
            return;
        }

        AiProviderProfile profile;
        if (editingProfileId != null) {
            profile = findProfileById(editingProfileId);
            if (profile == null) {
                setFeedback(i18n("ai.settings.save_failed", "Profile not found"), false);
                return;
            }
        } else {
            profile = new AiProviderProfile();
        }

        profile.setDisplayName(name != null ? name : "");
        profile.setProtocolFamily(protocol);
        profile.setEndpoint(endpoint != null ? endpoint : "");
        profile.setApiKey(apiKey != null ? apiKey : "");

        aiSettings.putProfile(profile);
        refreshProfileList();
        setFeedback(i18n("ai.settings.saved"), true);
    }

    @Nullable
    private AiProviderProfile findProfileById(String id) {
        for (AiProviderProfile p : aiSettings.getProfiles()) {
            if (p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    private void populateAdvancedFor(String protocolFamily, @Nullable String modelId) {
        if (settingsContextWindowField == null) return;

        if (modelId != null) {
            AiProviderDefinition def = AiProviderDefinition.byId(protocolFamily);
            if (def != null) {
                for (AiModelPreset preset : def.getModelPresets()) {
                    if (preset.getModelId().equals(modelId)) {
                        settingsContextWindowField.setText(String.valueOf(preset.getDefaultContextWindow()));
                        settingsMaxOutputField.setText(String.valueOf(preset.getDefaultMaxOutput()));
                        if (settingsReasoningEffortField != null) {
                            settingsReasoningEffortField.setDisable(!preset.supportsReasoning());
                            if (!preset.supportsReasoning()) {
                                settingsReasoningEffortField.setText("");
                            }
                        }
                        return;
                    }
                }
            }
        }
        clearAdvancedFields();
    }

    private void clearAdvancedFields() {
        if (settingsContextWindowField == null) return;
        settingsContextWindowField.setText(String.valueOf(LlmConfig.DEFAULT_CONTEXT_WINDOW));
        settingsMaxOutputField.setText(String.valueOf(LlmConfig.DEFAULT_MAX_TOKENS));
        settingsTemperatureField.setText(String.valueOf(LlmConfig.DEFAULT_TEMPERATURE));
        settingsTopPField.setText(String.valueOf(LlmConfig.DEFAULT_TOP_P));
        settingsPresencePenaltyField.setText(String.valueOf(LlmConfig.DEFAULT_PRESENCE_PENALTY));
        settingsFrequencyPenaltyField.setText(String.valueOf(LlmConfig.DEFAULT_FREQUENCY_PENALTY));
        settingsSeedField.setText("");
        settingsReasoningEffortField.setText("");
        settingsReasoningEffortField.setDisable(false);
    }

    // ---- Model discovery ----

    private void discoverModels() {
        if (settingsModelCombo == null || settingsDiscoverFeedback == null) return;

        String endpoint = settingsEndpointField.getText();
        String apiKey = settingsApiKeyField.getText();

        if (endpoint == null || endpoint.isEmpty()) {
            settingsDiscoverFeedback.setText(i18n("ai.error.no_endpoint"));
            settingsDiscoverFeedback.setStyle("-fx-text-fill: red;");
            return;
        }

        AiProviderProfile tempProfile = new AiProviderProfile();
        tempProfile.setEndpoint(endpoint);
        tempProfile.setApiKey(apiKey != null ? apiKey : "");
        String protocol = settingsProtocolCombo.getValue();
        if (protocol != null) {
            tempProfile.setProtocolFamily(protocol);
        }

        settingsDiscoverFeedback.setText(i18n("ai.settings.discovering"));
        settingsDiscoverFeedback.setStyle("");

        CompletableFuture.runAsync(() -> {
            try {
                List<String> models = discoveryService.discoverModels(tempProfile);
                Platform.runLater(() -> {
                    if (models.isEmpty()) {
                        settingsDiscoverFeedback.setText(i18n("ai.settings.no_models_found"));
                        settingsDiscoverFeedback.setStyle("-fx-text-fill: orange;");
                    } else {
                        String currentModel = settingsModelCombo.getValue();
                        settingsModelCombo.getItems().clear();
                        settingsModelCombo.getItems().addAll(models);
                        if (currentModel != null && !currentModel.isEmpty()) {
                            settingsModelCombo.setValue(currentModel);
                            if (protocol != null) {
                                populateAdvancedFor(protocol, currentModel);
                            }
                        }
                        settingsDiscoverFeedback.setText(i18n("ai.settings.models_discovered", models.size()));
                        settingsDiscoverFeedback.setStyle("-fx-text-fill: green;");
                    }
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    settingsDiscoverFeedback.setText(i18n("ai.error.timeout"));
                    settingsDiscoverFeedback.setStyle("-fx-text-fill: red;");
                });
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                Platform.runLater(() -> {
                    settingsDiscoverFeedback.setText(i18n("ai.error.connection_failed"));
                    settingsDiscoverFeedback.setStyle("-fx-text-fill: red;");
                });
            }
        });
    }

    // ---- Test connection ----

    private void testConnection() {
        if (settingsTestBtn == null || settingsFeedbackLabel == null) return;

        AiSettings testSettings = new AiSettings(SettingsManager.localConfigDirectory());
        applySelectedProfileToTestSettings(testSettings);

        settingsTestBtn.setDisable(true);
        settingsFeedbackLabel.setText(i18n("ai.settings.testing"));
        settingsFeedbackLabel.setStyle("");

        CompletableFuture.runAsync(() -> {
            try {
                String result = ChatAgentFactory.testConnectionSync(testSettings, 10);
                Platform.runLater(() -> {
                    settingsTestBtn.setDisable(false);
                    settingsFeedbackLabel.setText(i18n("ai.settings.test_ok", result));
                    settingsFeedbackLabel.setStyle("-fx-text-fill: green;");
                });
            } catch (LlmException e) {
                Platform.runLater(() -> {
                    settingsTestBtn.setDisable(false);
                    int code = e.getStatusCode();
                    String message;
                    if (code == 401) {
                        message = i18n("ai.error.auth_failed");
                    } else {
                        message = i18n("ai.error.api_error", e.getMessage());
                    }
                    settingsFeedbackLabel.setText(message);
                    settingsFeedbackLabel.setStyle("-fx-text-fill: red;");
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    settingsTestBtn.setDisable(false);
                    settingsFeedbackLabel.setText(i18n("ai.error.timeout"));
                    settingsFeedbackLabel.setStyle("-fx-text-fill: red;");
                });
                Thread.currentThread().interrupt();
            } catch (TimeoutException e) {
                Platform.runLater(() -> {
                    settingsTestBtn.setDisable(false);
                    settingsFeedbackLabel.setText(i18n("ai.error.timeout"));
                    settingsFeedbackLabel.setStyle("-fx-text-fill: red;");
                });
            }
        });
    }

    private void applySelectedProfileToTestSettings(AiSettings target) {
        if (settingsProtocolCombo == null) return;

        String protocol = settingsProtocolCombo.getValue();
        if (protocol != null && !protocol.isEmpty()) {
            target.providerProperty().set(protocol);
        }

        String endpoint = settingsEndpointField.getText();
        if (endpoint != null && !endpoint.isEmpty()) {
            String normalized = AiEndpointNormalizer.normalize(endpoint,
                    protocol != null ? protocol : "");
            target.endpointProperty().set(normalized != null ? normalized : endpoint);
        }

        target.apiKeyProperty().set(settingsApiKeyField.getText());

        String model = settingsModelCombo.getValue();
        if (model != null && !model.isEmpty()) {
            target.modelProperty().set(model);
        }
    }

    // ---- Save settings ----

    private void saveSettings() {
        try {
            aiSettings.save();
        } catch (IOException ex) {
            setFeedback(i18n("ai.settings.save_failed", ex.getMessage()), false);
            return;
        }

        agentCache.clear();
        setFeedback(i18n("ai.settings.saved"), true);
    }

    private void setFeedback(String message, boolean success) {
        if (settingsFeedbackLabel == null) return;
        settingsFeedbackLabel.setText(message);
        settingsFeedbackLabel.setStyle(success
                ? "-fx-text-fill: green;" : "-fx-text-fill: red;");
    }

    // ---- Chat view ----

    private void buildChatView() {
        chatView.setSpacing(0);

        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().addAll("edge-to-edge", "ai-messages-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        messageList.setPadding(new Insets(16, 16, 24, 16));

        buildEmptyState();

        StackPane messagesArea = new StackPane(scrollPane, emptyState);
        VBox.setVgrow(messagesArea, Priority.ALWAYS);

        // Drag-and-drop file support on the chat area
        messagesArea.setOnDragOver(this::handleDragOver);
        messagesArea.setOnDragDropped(this::handleDragDropped);

        statusLabel.setVisible(false);
        statusLabel.getStyleClass().add("ai-typing-indicator");

        toolActivityBox.getStyleClass().add("ai-tool-activity");
        toolActivityBox.setVisible(false);
        toolActivityBox.setManaged(false);

        Label toolActivityLabel = new Label(i18n("ai.tool.activity"));
        toolActivityLabel.getStyleClass().add("ai-tool-activity-label");
        toolActivityBox.getChildren().add(toolActivityLabel);

        chatView.getChildren().setAll(
                buildHeaderNode(),
                toolActivityBox,
                messagesArea,
                statusLabel,
                buildComposer()
        );
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

        HBox subtitleRow = new HBox(6, headerSubtitle, approvalBadge);
        subtitleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2, headerTitle, subtitleRow);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        // Model selector button with popup
        JFXButton modelBtn = new JFXButton();
        modelBtn.getStyleClass().add("ai-model-btn");
        modelBtn.setOnAction(e -> showModelPickerPopup(modelBtn));

        // Thinking level label
        currentThinkingLabel.getStyleClass().add("ai-thinking-badge");
        currentThinkingLabel.setOnMouseClicked(e -> cycleThinkingLevel());

        JFXButton searchBtn = new JFXButton();
        searchBtn.setGraphic(SVG.SEARCH.createIcon(16));
        searchBtn.getStyleClass().add("ai-toolbar-icon-btn");
        searchBtn.setOnAction(e -> showSearchOverlay());
        FXUtils.installFastTooltip(searchBtn, i18n("ai.search"));

        JFXButton chatSettingsBtn = new JFXButton();
        chatSettingsBtn.setGraphic(SVG.TUNE.createIcon(16));
        chatSettingsBtn.getStyleClass().add("ai-toolbar-icon-btn");
        chatSettingsBtn.setOnAction(e -> showChatSettingsDrawer());
        FXUtils.installFastTooltip(chatSettingsBtn, i18n("ai.chat.settings"));

        HBox toolbarControls = new HBox(6, modelBtn, currentThinkingLabel, searchBtn, chatSettingsBtn);
        toolbarControls.setAlignment(Pos.CENTER_RIGHT);

        HBox headerBox = new HBox(titleBox, toolbarControls);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(10, 16, 10, 16));
        headerBox.getStyleClass().add("ai-main-header");
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        updateModelButton(modelBtn);
        return headerBox;
    }

    private void updateModelButton(JFXButton modelBtn) {
        AiProviderProfile active = aiSettings.findSelectedProfile();
        if (active != null && active.getDefaultModelId() != null) {
            String alias = active.getModelAliasOrId(active.getDefaultModelId());
            modelBtn.setText(alias);
            modelBtn.setGraphic(SVG.DEPLOYED_CODE.createIcon(14));
        } else {
            modelBtn.setText(i18n("ai.model.select"));
            modelBtn.setGraphic(SVG.ADD.createIcon(14));
        }
        currentThinkingLabel.setText(aiSettings.getReasoningEffort().isEmpty() ? "none" : aiSettings.getReasoningEffort());
    }

    private void showModelPickerPopup(JFXButton anchor) {
        VBox popupContent = new VBox(4);
        popupContent.setPadding(new Insets(8));
        popupContent.getStyleClass().add("ai-model-picker");

        for (AiProviderProfile provider : aiSettings.getProfiles()) {
            if (!provider.isEnabled()) continue;
            Label providerHeader = new Label(provider.getDisplayName());
            providerHeader.getStyleClass().addAll("ai-settings-section-title", "ai-model-picker-provider");

            VBox modelsBox = new VBox(2);
            java.util.List<String> models = provider.getCachedModels();
            if (models.isEmpty()) {
                Label emptyLabel = new Label("（无模型）");
                emptyLabel.setStyle("-fx-text-fill: -monet-on-surface-variant; -fx-font-size: 11px; -fx-padding: 2 8 2 8;");
                modelsBox.getChildren().add(emptyLabel);
            }
            for (String modelId : models) {
                String alias = provider.getModelAliasOrId(modelId);
                AdvancedListItem item = new AdvancedListItem();
                item.setTitle(alias);
                item.setSubtitle(modelId.equals(alias) ? "" : modelId);
                item.getStyleClass().add("navigation-drawer-item");
                item.setActive(modelId.equals(provider.getDefaultModelId()));
                item.setOnAction(e -> {
                    aiSettings.setSelectedProfileId(provider.getId());
                    provider.setDefaultModelId(modelId);
                    agentCache.clear();
                    try { aiSettings.save(); } catch (Exception ignored) {}
                    updateHeader(sessionStore.getCurrentSession());
                    updateModelButton(anchor);
                    // Close popup
                    if (item.getScene() != null && item.getScene().getWindow() != null) {
                        // The popup is shown via JFXPopup or similar - just hide it
                    }
                });
                modelsBox.getChildren().add(item);
            }

            JFXButton addModelBtn = new JFXButton("+ 添加模型");
            addModelBtn.getStyleClass().add("ai-add-model-btn");
            addModelBtn.setMaxWidth(Double.MAX_VALUE);
            addModelBtn.setOnAction(e -> showModelModal(provider.getId(), null));

            VBox providerSection = new VBox(4, providerHeader, modelsBox, addModelBtn);
            popupContent.getChildren().add(providerSection);
        }

        ScrollPane scroll = new ScrollPane(popupContent);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(400);
        scroll.setMinWidth(260);
        scroll.getStyleClass().add("edge-to-edge");

        JFXPopup popup = new JFXPopup(scroll);
        // Wire close on model select
        popupContent.getChildren().forEach(ps -> {
            if (ps instanceof VBox) {
                ((VBox) ps).getChildren().forEach(c -> {
                    if (c instanceof AdvancedListItem item && item.getOnAction() != null) {
                        var orig = item.getOnAction();
                        item.setOnAction(e -> {
                            orig.handle(e);
                            popup.hide();
                        });
                    }
                });
            }
        });
        popup.show(anchor, JFXPopup.PopupVPosition.TOP, JFXPopup.PopupHPosition.RIGHT, 0, 0);
    }

    private void cycleThinkingLevel() {
        String current = aiSettings.getReasoningEffort();
        String[] levels = {"none", "low", "medium", "high", "xhigh", "max"};
        int idx = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i].equals(current)) { idx = (i + 1) % levels.length; break; }
        }
        aiSettings.reasoningEffortProperty().set(levels[idx]);
        currentThinkingLabel.setText(levels[idx]);
    }

    /// Populates model button text from current state. Called on show/cfg change.
    private void refreshModelSelector() {
        // The button text is updated via updateModelButton when needed
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
        sessionStore.createSession();
        try {
            sessionStore.save();
        } catch (Exception ignored) {
        }
        messageList.getChildren().clear();
        updateEmptyState();
        refreshSessionList();
        showChatView();

        AiSession current = sessionStore.getCurrentSession();
        if (current != null) {
            updateHeader(current);
        }
    }

    // ---- Composer ----

    private HBox buildComposer() {
        inputField.setPromptText(i18n("ai.input_placeholder"));
        inputField.getStyleClass().add("ai-input-field");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnAction(e -> sendMessage());

        // Autocomplete key listener
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleInputKeyPress);

        sendBtn = FXUtils.newRaisedButton(i18n("ai.send"));
        sendBtn.getStyleClass().add("ai-send-btn");
        sendBtn.setOnAction(e -> sendMessage());
        sendBtn.setDefaultButton(true);

        JFXButton attachBtn = new JFXButton();
        attachBtn.setGraphic(SVG.FILE_OPEN.createIcon(16));
        attachBtn.getStyleClass().add("ai-input-attach-btn");
        attachBtn.setOnAction(e -> handleFileUpload());
        FXUtils.installFastTooltip(attachBtn, i18n("ai.attach"));

        // File chip area (shown above input when a file is selected)
        fileChipArea = new VBox(4);
        fileChipArea.setVisible(false);
        fileChipArea.setManaged(false);
        fileChipArea.getStyleClass().add("ai-file-chip-area");

        fileChip = new Label();
        fileChip.getStyleClass().add("ai-file-chip");

        JFXButton clearChipBtn = new JFXButton();
        clearChipBtn.setGraphic(SVG.CLOSE.createIcon(12));
        clearChipBtn.getStyleClass().add("ai-file-chip-clear-btn");
        clearChipBtn.setOnAction(e -> clearFileChip());

        HBox chipRow = new HBox(4, fileChip, clearChipBtn);
        chipRow.setAlignment(Pos.CENTER_LEFT);
        fileChipArea.getChildren().add(chipRow);

        // Autocomplete popup — appears above the input, extending upward
        autocompletePopup = buildAutocompletePopup();

        // VBox: popup (hidden by default) above file chip + input
        VBox composerInner = new VBox(4, autocompletePopup, fileChipArea, inputField);
        composerInner.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(inputField, Priority.NEVER);

        HBox inputBar = new HBox(8);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.setPadding(new Insets(8, 16, 12, 16));
        inputBar.getStyleClass().add("ai-input-bar");
        inputBar.getChildren().setAll(composerInner, attachBtn, sendBtn);
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

        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            selectedFilePath = file.toPath();
            if (fileChip != null && fileChipArea != null) {
                fileChip.setText(file.getName());
                fileChipArea.setVisible(true);
                fileChipArea.setManaged(true);
            }
            String currentText = inputField.getText();
            if (currentText != null && !currentText.isEmpty()) {
                inputField.setText(currentText + "\n\n[File: " + file.getName() + "]\n" + content);
            } else {
                inputField.setText(content);
            }
        } catch (IOException ex) {
            if (fileChipArea != null) {
                fileChipArea.setVisible(false);
                fileChipArea.setManaged(false);
            }
        }
    }

    /// Clears the file chip and removes the associated file content from the input.
    private void clearFileChip() {
        selectedFilePath = null;
        if (fileChipArea != null) {
            fileChipArea.setVisible(false);
            fileChipArea.setManaged(false);
        }
        if (fileChip != null) {
            fileChip.setText("");
        }
        String currentText = inputField.getText();
        if (currentText != null && currentText.contains("[File:")) {
            inputField.clear();
        }
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

    /// Handles drag-dropped files on the chat area by reading the first matching file.
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles() && !db.getFiles().isEmpty()) {
            java.io.File dropped = db.getFiles().get(0);
            try {
                String content = Files.readString(dropped.toPath(), StandardCharsets.UTF_8);
                selectedFilePath = dropped.toPath();
                if (fileChip != null && fileChipArea != null) {
                    fileChip.setText(dropped.getName());
                    fileChipArea.setVisible(true);
                    fileChipArea.setManaged(true);
                }
                String currentText = inputField.getText();
                if (currentText != null && !currentText.isEmpty()) {
                    inputField.setText(currentText + "\n\n[File: " + dropped.getName() + "]\n" + content);
                } else {
                    inputField.setText(content);
                }
                success = true;
            } catch (IOException ignored) {
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

    /// Filters and displays slash command suggestions.
    private void handleSlashAutocomplete(String prefix) {
        List<String> commands = List.of(
                "/crash", "/log", "/help", "/clear", "/model"
        );

        autocompleteItems.clear();
        String lowerPrefix = prefix.toLowerCase();
        for (String cmd : commands) {
            if (cmd.toLowerCase().startsWith(lowerPrefix)) {
                autocompleteItems.add(cmd);
            }
        }

        if (autocompleteItems.isEmpty()) {
            hideAutocomplete();
            return;
        }

        autocompleteSelectedIndex = -1;
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

        autocompleteSelectedIndex = -1;
        refreshAutocompletePopup();
    }

    /// Refreshes the autocomplete popup content with current filtered items.
    private void refreshAutocompletePopup() {
        if (autocompletePopup == null) return;
        autocompletePopup.getChildren().clear();

        for (int i = 0; i < autocompleteItems.size(); i++) {
            String item = autocompleteItems.get(i);
            int index = i;
            Label itemLabel = new Label(item);
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

    /// Navigates the autocomplete selection by the given delta.
    private void navigateAutocomplete(int delta) {
        if (autocompleteItems.isEmpty()) return;
        int newIndex = autocompleteSelectedIndex + delta;
        if (newIndex < -1) newIndex = autocompleteItems.size() - 1;
        if (newIndex >= autocompleteItems.size()) newIndex = -1;
        autocompleteSelectedIndex = newIndex;
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
                id -> ChatAgentFactory.build(aiSettings, session, toolRegistry));
    }

    private void loadSessionMessages(AiSession session) {
        messageList.getChildren().clear();
        toolActivityBox.getChildren().clear();
        for (LlmMessage msg : session.getMessages()) {
            String role = msg.getRole();
            if ("user".equals(role)) {
                addUserBubbleQuiet(msg.getContent());
            } else if ("assistant".equals(role)) {
                createAiBubble(msg.getContent());
            } else if (isToolMessage(msg.getContent())) {
                if (aiSettings.isToolCallDisplayEnabled()) {
                    addToolMessage(msg.getContent());
                }
            } else {
                addSystemMessage(msg.getContent());
            }
        }
        updateToolActivityVisibility();
        updateEmptyState();
        scrollToBottom();
    }

    /// Returns true when the message content appears to be a tool execution
    /// result stored by the ChatAgent.
    private static boolean isToolMessage(String content) {
        return content.startsWith("Tool result for ");
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        AiSession session = sessionStore.getCurrentSession();
        if (session == null) return;

        // Auto-title from first message
        if (session.getMessages().isEmpty()) {
            String title = text.length() > 50 ? text.substring(0, 47) + "..." : text;
            session.setTitle(title);
            updateHeader(session);
            refreshSessionList();
        }

        inputField.clear();
        addUserBubble(text);

        ToolResult crashPreAnalysis = preAnalyzeCrashInput(text);
        if (crashPreAnalysis != null && crashPreAnalysis.isSuccess()) {
            addSystemMessage(crashPreAnalysis.getOutput());
            if (crashPreAnalysis.getOutput().contains("Diagnosis Confidence: LOW")) {
                addSystemMessage(i18n("ai.crash.escalate"));
            }
        }

        persistStore();

        ChatAgent agent = getOrCreateChatAgent();
        if (agent == null) {
            addSystemMessage(i18n("ai.error.no_endpoint"));
            return;
        }

        setStatus("");
        startAiResponse(agent, session, text);
        clearFileChip();
    }

    /// Performs a local crash-log pre-analysis when the current input likely contains a crash report.
    @Nullable
    private ToolResult preAnalyzeCrashInput(String text) {
        if (!isLikelyCrashInput(text)) {
            return null;
        }
        return new CrashAnalyzerTool().execute(Map.of("crash_text", text));
    }

    /// Heuristically detects whether the current input is probably a Minecraft crash log.
    private boolean isLikelyCrashInput(String text) {
        if (selectedFilePath != null) {
            String name = selectedFilePath.getFileName().toString().toLowerCase();
            if (name.endsWith(".crash") || name.endsWith(".log") || name.endsWith(".txt")) {
                return true;
            }
        }
        String lower = text.toLowerCase();
        return lower.contains("exception")
                || lower.contains("crash report")
                || lower.contains("mod list")
                || lower.contains("has failed to load correctly")
                || lower.contains("no such method error")
                || lower.contains("nullpointerexception")
                || lower.contains("outofmemoryerror");
    }

    private void startAiResponse(ChatAgent agent, AiSession session, String userInput) {
        Label aiBubble = createAiBubble("");
        streamingBubble = aiBubble;

        StringBuilder fullContent = new StringBuilder();
        agent.sendStreaming(userInput, new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                fullContent.append(token);
                Platform.runLater(() -> {
                    aiBubble.setText(fullContent.toString());
                    scrollToBottom();
                });
            }

            @Override
            public void onComplete(String completeText) {
                Platform.runLater(() -> {
                    streamingBubble = null;
                    // Swap Label with MarkdownMessageView when appropriate
                    if (chatSettings.markdownRender && !completeText.isEmpty()
                            && MarkdownRenderer.containsMarkdownSyntax(completeText)) {
                        MarkdownMessageView mdView = MarkdownMessageView.create(completeText);
                        if (mdView != null) {
                            aiBubble.setVisible(false);
                            aiBubble.setManaged(false);
                            // Insert above the hidden Label; bubbleBox gets bubble styling
                            if (aiBubble.getParent() instanceof VBox bubbleBox) {
                                bubbleBox.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");
                                bubbleBox.getChildren().add(bubbleBox.getChildren().indexOf(aiBubble), mdView);
                            }
                        }
                    }
                    setStatus(null);
                    persistStore();
                });
            }

            @Override
            public void onError(LlmException error) {
                showAiError(aiBubble, fullContent, error);
            }
        }).exceptionally(ex -> {
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            LlmException error = cause instanceof LlmException
                    ? (LlmException) cause
                    : new LlmException(cause.getMessage() != null ? cause.getMessage() : "Connection failed", 0, cause);
            showAiError(aiBubble, fullContent, error);
            return null;
        });
    }

    private void showAiError(Label aiBubble, StringBuilder fullContent, LlmException error) {
        Platform.runLater(() -> {
            streamingBubble = null;
            if (fullContent.length() > 0) {
                aiBubble.setText(fullContent.toString());
            }
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
            aiBubble.getStyleClass().add("ai-bubble-error");
            scrollToBottom();
            persistStore();
        });
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
        } catch (Exception ignored) {
        }
    }

    private void addUserBubble(String text) {
        String userName = chatSettings.userName;
        Label nameLabel = new Label(userName);
        nameLabel.setMaxWidth(480);
        nameLabel.setAlignment(Pos.CENTER_RIGHT);
        nameLabel.getStyleClass().add("ai-bubble-name");

        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-user");

        VBox bubbleBox = new VBox(2, nameLabel, label);
        bubbleBox.setMaxWidth(480);
        bubbleBox.setAlignment(Pos.CENTER_RIGHT);

        HBox wrapper = new HBox(bubbleBox);
        wrapper.setAlignment(Pos.CENTER_RIGHT);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");


        messageList.getChildren().add(wrapper);
        updateEmptyState();
        scrollToBottom();
    }

    private void addUserBubbleQuiet(String text) {
        String userName = chatSettings.userName;
        Label nameLabel = new Label(userName);
        nameLabel.setMaxWidth(480);
        nameLabel.setAlignment(Pos.CENTER_RIGHT);
        nameLabel.getStyleClass().add("ai-bubble-name");

        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-user");

        VBox bubbleBox = new VBox(2, nameLabel, label);
        bubbleBox.setMaxWidth(480);
        bubbleBox.setAlignment(Pos.CENTER_RIGHT);

        HBox wrapper = new HBox(bubbleBox);
        wrapper.setAlignment(Pos.CENTER_RIGHT);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");


        messageList.getChildren().add(wrapper);
    }

    private Label createAiBubble(String text) {
        Label nameLabel = new Label("AI");
        nameLabel.setMaxWidth(480);
        nameLabel.getStyleClass().add("ai-bubble-name");

        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");

        VBox bubbleBox;
        if (!text.isEmpty() && chatSettings.markdownRender) {
            MarkdownMessageView mdView = MarkdownMessageView.create(text);
            if (mdView != null) {
                label.setVisible(false);
                label.setManaged(false);
                bubbleBox = new VBox(2, nameLabel, mdView);
                bubbleBox.getStyleClass().addAll("ai-bubble", "ai-bubble-ai");
            } else {
                bubbleBox = new VBox(2, nameLabel, label);
            }
        } else {
            bubbleBox = new VBox(2, nameLabel, label);
        }
        bubbleBox.setMaxWidth(480);

        HBox wrapper = new HBox(bubbleBox);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");

        messageList.getChildren().add(wrapper);
        updateEmptyState();
        scrollToBottom();
        return label;
    }

    private void addSystemMessage(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-system");

        HBox wrapper = new HBox(label);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");


        messageList.getChildren().add(wrapper);
        updateEmptyState();
        scrollToBottom();
    }

    /// Adds a tool event entry to the message list with tool-specific styling
    /// and logs it to the tool activity area.
    private void addToolMessage(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(480);
        label.getStyleClass().addAll("ai-bubble", "ai-bubble-tool");

        HBox wrapper = new HBox(label);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.getStyleClass().add("ai-bubble-wrapper");


        messageList.getChildren().add(wrapper);

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

        JFXButton closeBtn = new JFXButton("\u2715");
        closeBtn.getStyleClass().add("ai-chat-settings-close-btn");
        closeBtn.setOnAction(e -> hideChatSettingsDrawer());

        HBox header = new HBox(headerLabel, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 14, 12, 14));
        header.getStyleClass().add("ai-chat-settings-header");
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        // Body: scrollable settings sections
        VBox body = new VBox(8);
        body.setPadding(new Insets(0, 14, 14, 14));
        body.getStyleClass().add("ai-chat-settings-body");

        body.getChildren().add(buildMessageSettingsSection());
        body.getChildren().add(buildInputSettingsSection());

        ScrollPane scrollBody = new ScrollPane(body);
        scrollBody.setFitToWidth(true);
        scrollBody.getStyleClass().add("edge-to-edge");
        scrollBody.getStyleClass().add("ai-chat-settings-scroll");
        VBox.setVgrow(scrollBody, Priority.ALWAYS);

        chatSettingsDrawer.getChildren().addAll(header, scrollBody);
    }

    /// Builds the Message Settings section of the drawer.
    private VBox buildMessageSettingsSection() {
        VBox section = new VBox(6);
        section.getStyleClass().add("ai-chat-settings-section");

        Label title = new Label(i18n("ai.chat.settings.messages"));
        title.getStyleClass().add("ai-chat-settings-section-title");

        JFXButton toggleBtn = new JFXButton();
        toggleBtn.setGraphic(SVG.ARROW_DROP_DOWN.createIcon(12));
        toggleBtn.getStyleClass().add("ai-chat-settings-toggle-btn");

        HBox titleBox = new HBox(title, toggleBtn);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getStyleClass().add("ai-chat-settings-section-header");
        HBox.setHgrow(title, Priority.ALWAYS);

        VBox content = new VBox(6);
        content.getStyleClass().add("ai-chat-settings-section-content");

        // User name
        TextField userNameField = new TextField(chatSettings.userName);
        userNameField.setPromptText("\u6211");
        userNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            chatSettings.userName = newVal != null ? newVal : "\u6211";
            saveChatSettings();
            refreshMessageList();
        });

        HBox userNameRow = new HBox(8, new Label(i18n("ai.chat.settings.user_name")), userNameField);
        userNameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(userNameField, Priority.ALWAYS);
        userNameField.setMaxWidth(Double.MAX_VALUE);

        // Show usage info
        ComboBox<String> showUsageCombo = new ComboBox<>();
        showUsageCombo.getItems().addAll(i18n("ai.chat.settings.show"), i18n("ai.chat.settings.hide"));
        showUsageCombo.setValue(chatSettings.showUsage ? i18n("ai.chat.settings.show") : i18n("ai.chat.settings.hide"));
        showUsageCombo.setOnAction(e -> {
            chatSettings.showUsage = i18n("ai.chat.settings.show").equals(showUsageCombo.getValue());
            saveChatSettings();
            refreshMessageList();
        });

        HBox usageRow = new HBox(8, new Label(i18n("ai.chat.settings.show_usage")), showUsageCombo);
        usageRow.setAlignment(Pos.CENTER_LEFT);

        // Show tool calls
        CheckBox showToolsCheck = new CheckBox();
        showToolsCheck.setSelected(chatSettings.showToolCalls);
        showToolsCheck.setOnAction(e -> {
            chatSettings.showToolCalls = showToolsCheck.isSelected();
            saveChatSettings();
            refreshMessageList();
        });

        HBox toolsRow = new HBox(8, new Label(i18n("ai.chat.settings.show_tool_calls")), showToolsCheck);
        toolsRow.setAlignment(Pos.CENTER_LEFT);

        // Thinking fold mode
        ComboBox<String> thinkingFoldCombo = new ComboBox<>();
        thinkingFoldCombo.getItems().addAll("auto", "always-show", "always-fold");
        thinkingFoldCombo.setValue(chatSettings.thinkingFoldMode);
        thinkingFoldCombo.setOnAction(e -> {
            chatSettings.thinkingFoldMode = thinkingFoldCombo.getValue() != null ? thinkingFoldCombo.getValue() : "auto";
            saveChatSettings();
            refreshMessageList();
        });

        HBox thinkingRow = new HBox(8, new Label(i18n("ai.chat.settings.thinking_fold_mode")), thinkingFoldCombo);
        thinkingRow.setAlignment(Pos.CENTER_LEFT);

        // Message style
        ComboBox<String> msgStyleCombo = new ComboBox<>();
        msgStyleCombo.getItems().addAll(i18n("ai.chat.settings.style_bubble"), i18n("ai.chat.settings.style_flat"));
        msgStyleCombo.setValue("bubble".equals(chatSettings.messageStyle) ? i18n("ai.chat.settings.style_bubble") : i18n("ai.chat.settings.style_flat"));
        msgStyleCombo.setOnAction(e -> {
            chatSettings.messageStyle = i18n("ai.chat.settings.style_bubble").equals(msgStyleCombo.getValue()) ? "bubble" : "flat";
            saveChatSettings();
            applyCssToMessageList();
        });

        HBox styleRow = new HBox(8, new Label(i18n("ai.chat.settings.message_style")), msgStyleCombo);
        styleRow.setAlignment(Pos.CENTER_LEFT);

        // Show nav buttons
        CheckBox navButtonsCheck = new CheckBox();
        navButtonsCheck.setSelected(chatSettings.showNavButtons);
        navButtonsCheck.setOnAction(e -> {
            chatSettings.showNavButtons = navButtonsCheck.isSelected();
            saveChatSettings();
            refreshMessageList();
        });

        HBox navRow = new HBox(8, new Label(i18n("ai.chat.settings.show_nav_buttons")), navButtonsCheck);
        navRow.setAlignment(Pos.CENTER_LEFT);

        // Font size
        ComboBox<String> fontSizeCombo = new ComboBox<>();
        fontSizeCombo.getItems().addAll(
                i18n("ai.chat.settings.font_small"),
                i18n("ai.chat.settings.font_normal"),
                i18n("ai.chat.settings.font_large"));
        fontSizeCombo.setValue(mapFontSizeToLabel(chatSettings.fontSize));
        fontSizeCombo.setOnAction(e -> {
            chatSettings.fontSize = mapLabelToFontSize(fontSizeCombo.getValue());
            saveChatSettings();
            applyCssToMessageList();
        });

        HBox fontSizeRow = new HBox(8, new Label(i18n("ai.chat.settings.font_size")), fontSizeCombo);
        fontSizeRow.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(
                userNameRow, usageRow, toolsRow, thinkingRow,
                styleRow, navRow, fontSizeRow);

        titleBox.setOnMouseClicked(e -> {
            boolean isVisible = content.isVisible();
            content.setVisible(!isVisible);
            content.setManaged(!isVisible);
            if (!isVisible) {
                toggleBtn.setGraphic(SVG.ARROW_DROP_DOWN.createIcon(12));
            } else {
                toggleBtn.setGraphic(SVG.ARROW_FORWARD.createIcon(12));
            }
        });

        section.getChildren().addAll(titleBox, content);
        return section;
    }

    /// Builds the Input Settings section of the drawer.
    private VBox buildInputSettingsSection() {
        VBox section = new VBox(6);
        section.getStyleClass().add("ai-chat-settings-section");

        Label title = new Label(i18n("ai.chat.settings.input"));
        title.getStyleClass().add("ai-chat-settings-section-title");

        JFXButton toggleBtn = new JFXButton();
        toggleBtn.setGraphic(SVG.ARROW_DROP_DOWN.createIcon(12));
        toggleBtn.getStyleClass().add("ai-chat-settings-toggle-btn");

        HBox titleBox = new HBox(title, toggleBtn);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getStyleClass().add("ai-chat-settings-section-header");
        HBox.setHgrow(title, Priority.ALWAYS);

        VBox content = new VBox(6);
        content.getStyleClass().add("ai-chat-settings-section-content");

        // Estimate tokens
        CheckBox estimateTokensCheck = new CheckBox();
        estimateTokensCheck.setSelected(chatSettings.estimateTokens);
        estimateTokensCheck.setOnAction(e -> {
            chatSettings.estimateTokens = estimateTokensCheck.isSelected();
            saveChatSettings();
        });

        HBox estimateRow = new HBox(8, new Label(i18n("ai.chat.settings.estimate_tokens")), estimateTokensCheck);
        estimateRow.setAlignment(Pos.CENTER_LEFT);

        // Paste as file
        CheckBox pasteFileCheck = new CheckBox();
        pasteFileCheck.setSelected(chatSettings.pasteAsFile);
        pasteFileCheck.setOnAction(e -> {
            chatSettings.pasteAsFile = pasteFileCheck.isSelected();
            saveChatSettings();
        });

        HBox pasteRow = new HBox(8, new Label(i18n("ai.chat.settings.paste_as_file")), pasteFileCheck);
        pasteRow.setAlignment(Pos.CENTER_LEFT);

        // Markdown render
        CheckBox mdRenderCheck = new CheckBox();
        mdRenderCheck.setSelected(chatSettings.markdownRender);
        mdRenderCheck.setOnAction(e -> {
            chatSettings.markdownRender = mdRenderCheck.isSelected();
            saveChatSettings();
            refreshMessageList();
        });

        HBox mdRow = new HBox(8, new Label(i18n("ai.chat.settings.markdown_render")), mdRenderCheck);
        mdRow.setAlignment(Pos.CENTER_LEFT);

        // Quick translate
        CheckBox quickTranslateCheck = new CheckBox();
        quickTranslateCheck.setSelected(chatSettings.quickTranslate);
        quickTranslateCheck.setOnAction(e -> {
            chatSettings.quickTranslate = quickTranslateCheck.isSelected();
            saveChatSettings();
        });

        HBox translateRow = new HBox(8, new Label(i18n("ai.chat.settings.quick_translate")), quickTranslateCheck);
        translateRow.setAlignment(Pos.CENTER_LEFT);

        // Confirm dialog
        CheckBox confirmDialogCheck = new CheckBox();
        confirmDialogCheck.setSelected(chatSettings.confirmDialog);
        confirmDialogCheck.setOnAction(e -> {
            chatSettings.confirmDialog = confirmDialogCheck.isSelected();
            saveChatSettings();
        });

        HBox confirmRow = new HBox(8, new Label(i18n("ai.chat.settings.confirm_dialog")), confirmDialogCheck);
        confirmRow.setAlignment(Pos.CENTER_LEFT);

        // Show shortcut menu
        CheckBox shortcutMenuCheck = new CheckBox();
        shortcutMenuCheck.setSelected(chatSettings.showShortcutMenu);
        shortcutMenuCheck.setOnAction(e -> {
            chatSettings.showShortcutMenu = shortcutMenuCheck.isSelected();
            saveChatSettings();
        });

        HBox shortcutRow = new HBox(8, new Label(i18n("ai.chat.settings.show_shortcut_menu")), shortcutMenuCheck);
        shortcutRow.setAlignment(Pos.CENTER_LEFT);

        // Delete confirm
        CheckBox deleteConfirmCheck = new CheckBox();
        deleteConfirmCheck.setSelected(chatSettings.deleteConfirm);
        deleteConfirmCheck.setOnAction(e -> {
            chatSettings.deleteConfirm = deleteConfirmCheck.isSelected();
            saveChatSettings();
        });

        HBox deleteConfirmRow = new HBox(8, new Label(i18n("ai.chat.settings.delete_confirm")), deleteConfirmCheck);
        deleteConfirmRow.setAlignment(Pos.CENTER_LEFT);

        // Regenerate confirm
        CheckBox regenerateConfirmCheck = new CheckBox();
        regenerateConfirmCheck.setSelected(chatSettings.regenerateConfirm);
        regenerateConfirmCheck.setOnAction(e -> {
            chatSettings.regenerateConfirm = regenerateConfirmCheck.isSelected();
            saveChatSettings();
        });

        HBox regenerateRow = new HBox(8, new Label(i18n("ai.chat.settings.regenerate_confirm")), regenerateConfirmCheck);
        regenerateRow.setAlignment(Pos.CENTER_LEFT);

        // Send shortcut
        ComboBox<String> sendShortcutCombo = new ComboBox<>();
        sendShortcutCombo.getItems().addAll("Enter", "Ctrl+Enter");
        sendShortcutCombo.setValue(chatSettings.sendShortcut);
        sendShortcutCombo.setOnAction(e -> {
            chatSettings.sendShortcut = sendShortcutCombo.getValue() != null ? sendShortcutCombo.getValue() : "Enter";
            saveChatSettings();
        });

        HBox sendRow = new HBox(8, new Label(i18n("ai.chat.settings.send_shortcut")), sendShortcutCombo);
        sendRow.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(
                estimateRow, pasteRow, mdRow, translateRow,
                confirmRow, shortcutRow, deleteConfirmRow, regenerateRow, sendRow);

        titleBox.setOnMouseClicked(e -> {
            boolean isVisible = content.isVisible();
            content.setVisible(!isVisible);
            content.setManaged(!isVisible);
            if (!isVisible) {
                toggleBtn.setGraphic(SVG.ARROW_DROP_DOWN.createIcon(12));
            } else {
                toggleBtn.setGraphic(SVG.ARROW_FORWARD.createIcon(12));
            }
        });

        section.getChildren().addAll(titleBox, content);
        return section;
    }

    /// Maps the internal font-size key to a localised label.
    private String mapFontSizeToLabel(String fontSize) {
        if ("small".equals(fontSize)) return i18n("ai.chat.settings.font_small");
        if ("large".equals(fontSize)) return i18n("ai.chat.settings.font_large");
        return i18n("ai.chat.settings.font_normal");
    }

    /// Maps a localised label back to the internal font-size key.
    private String mapLabelToFontSize(@Nullable String label) {
        if (i18n("ai.chat.settings.font_small").equals(label)) return "small";
        if (i18n("ai.chat.settings.font_large").equals(label)) return "large";
        return "normal";
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
        applyCssToMessageList();
    }

    /// Updates the message list CSS classes based on current font-size setting.
    private void applyCssToMessageList() {
        messageList.getStyleClass().removeAll("ai-chat-font-small", "ai-chat-font-normal", "ai-chat-font-large");
        messageList.getStyleClass().add("ai-chat-font-" + chatSettings.fontSize);
    }

    /// Re-renders all messages to pick up the current user-name setting.
    private void refreshMessageList() {
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
        String userName = "\u6211";

        @SerializedName("showUsage")
        boolean showUsage = true;

        @SerializedName("showToolCalls")
        boolean showToolCalls = true;

        @SerializedName("thinkingFoldMode")
        String thinkingFoldMode = "auto";

        @SerializedName("messageStyle")
        String messageStyle = "bubble";

        @SerializedName("showNavButtons")
        boolean showNavButtons = false;

        @SerializedName("fontSize")
        String fontSize = "normal";

        @SerializedName("estimateTokens")
        boolean estimateTokens = false;

        @SerializedName("pasteAsFile")
        boolean pasteAsFile = false;

        @SerializedName("markdownRender")
        boolean markdownRender = true;

        @SerializedName("quickTranslate")
        boolean quickTranslate = false;

        @SerializedName("confirmDialog")
        boolean confirmDialog = true;

        @SerializedName("showShortcutMenu")
        boolean showShortcutMenu = true;

        @SerializedName("deleteConfirm")
        boolean deleteConfirm = true;

        @SerializedName("regenerateConfirm")
        boolean regenerateConfirm = true;

        @SerializedName("sendShortcut")
        String sendShortcut = "Enter";
    }

    // ---- DecoratorPage implementation ----

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
