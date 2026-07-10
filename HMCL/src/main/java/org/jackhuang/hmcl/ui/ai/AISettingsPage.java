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
import com.google.gson.reflect.TypeToken;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXPasswordField;
import javafx.scene.control.Hyperlink;
import org.jackhuang.hmcl.ui.construct.HintPane;
import com.jfoenix.controls.JFXSlider;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.AiModelDiscoveryService;
import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProviderDefinition;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.mcp.AiMcpServerConfig;
import org.jackhuang.hmcl.ai.mcp.McpClientManager;
import org.jackhuang.hmcl.ai.ocr.AiOcrConfig;
import org.jackhuang.hmcl.ai.ocr.OcrProvider;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.search.SearchProvider;
import org.jackhuang.hmcl.ai.search.SearchResponse;
import org.jackhuang.hmcl.ai.search.SearxngSearchClient;
import org.jackhuang.hmcl.ai.search.TavilySearchClient;
import org.jackhuang.hmcl.ai.skills.SkillManifest;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.AiToolCatalog;
import org.jackhuang.hmcl.ai.tools.AiToolPermissionStore;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.DialogPane;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.JsonEditorDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class AISettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /// Bounded pool for the batch model-connection-test dialog: selecting many models across
    /// providers used to spawn one unbounded, un-pooled raw Thread per row with no throttling —
    /// a small fixed pool of daemon threads caps concurrency regardless of selection size.
    private static final ExecutorService BATCH_TEST_POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "ai-test-conn");
        t.setDaemon(true);
        return t;
    });
    /// Uniform fixed width for this page's dialog form bodies (single source; native precedent
    /// for fixed dialog-form widths: PromptDialogPane 560, InputDialogPane 400 — 480 sits between).
    private static final double FORM_WIDTH = 480;
    private static final Path MCP_CONFIG_FILE = SettingsManager.localConfigDirectory().resolve("ai-mcp-settings.json");
    private static final Path SEARCH_CONFIG_FILE = SettingsManager.localConfigDirectory().resolve("ai-search-settings.json");
    private static final Path OCR_CONFIG_FILE = SettingsManager.localConfigDirectory().resolve(AiOcrConfig.FILE_NAME);
    private static final Path TOOL_PERMISSION_FILE = SettingsManager.localConfigDirectory().resolve("ai-tool-permissions.json");
    private static final Path SKILLS_DIR = SettingsManager.localConfigDirectory().resolve("ai-skills");

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("ai.settings")));
    private final AiSettings aiSettings;
    private final AiModelDiscoveryService discoveryService;
    private final Runnable onSettingsChanged;

    private final ToolRegistry mcpToolRegistry = new ToolRegistry();
    private final McpClientManager mcpClientManager = new McpClientManager(mcpToolRegistry);
    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final AiToolPermissionStore toolPermissionStore = new AiToolPermissionStore(TOOL_PERMISSION_FILE);
    private final List<AiMcpServerConfig> mcpServers = new ArrayList<>();
    /// Shared with AIMainPage (constructor-injected): the chat page's WebSearchTool/OcrImageTool
    /// hold a reference to THE SAME instance, so edits made here are visible to the tools
    /// immediately. Never reassign these fields — replacing the instance would silently
    /// disconnect the tools again (the original double-instance bug). Edit via setters only.
    private final AiSearchConfig searchConfig;
    private final AiOcrConfig ocrConfig;

    private final TransitionPane transitionPane = new TransitionPane();
    private final TabHeader.Tab<Node> providerTab = new TabHeader.Tab<>("aiProviderTab");
    private final TabHeader.Tab<Node> mcpTab = new TabHeader.Tab<>("aiMcpTab");
    private final TabHeader.Tab<Node> skillsTab = new TabHeader.Tab<>("aiSkillsTab");
    private final TabHeader.Tab<Node> searchTab = new TabHeader.Tab<>("aiSearchTab");
    /// Kept registered (so any code that still selects it directly does not crash) but no longer
    /// reachable from the left nav — see the sidebar construction below.
    private final TabHeader.Tab<Node> ocrTab = new TabHeader.Tab<>("aiOcrTab");
    private final TabHeader.Tab<Node> generalTab = new TabHeader.Tab<>("aiGeneralTab");
    /// Same as {@link #ocrTab}: still registered, no longer reachable from the left nav.
    private final TabHeader.Tab<Node> memoryTab = new TabHeader.Tab<>("aiMemoryTab");
    private final TabHeader.Tab<Node> dataTab = new TabHeader.Tab<>("aiDataTab");
    /// Advanced/developer settings: execution limits, the Auto approval-mode explainer, and the
    /// dangerous developer-only toggles. Content moved here from {@code buildGeneralTab}'s old
    /// "高级与开发者" card as part of the settings-page IA cleanup.
    private final TabHeader.Tab<Node> advancedTab = new TabHeader.Tab<>("aiAdvancedTab");
    private final TabHeader.Tab<Node> helpTab = new TabHeader.Tab<>("aiHelpTab");
    private final TabHeader.Tab<Node> aboutTab = new TabHeader.Tab<>("aiAboutTab");
    private final TabHeader tab;

    private final RadioChoiceList<AiProviderProfile> providerChoices = new RadioChoiceList<>();
    private final RadioChoiceList<AiModelEntry> modelChoices = new RadioChoiceList<>();
    @SuppressWarnings("unused")
    private final WeakListenerHolder holder = new WeakListenerHolder();
    private Label providerFeedback;
    /// The collapsible "服务商配置" / "模型配置" cards, whose headers show the current selection.
    private ComponentSublist providerSublist;
    private ComponentSublist modelSublist;

    public AISettingsPage(AiSettings aiSettings, AiModelDiscoveryService discoveryService, Runnable onSettingsChanged,
                          AiSearchConfig searchConfig, AiOcrConfig ocrConfig) {
        this.aiSettings = aiSettings;
        this.discoveryService = discoveryService;
        this.onSettingsChanged = onSettingsChanged;
        this.searchConfig = searchConfig;
        this.ocrConfig = ocrConfig;

        skillRegistry.setSkillsDir(SKILLS_DIR);
        loadMcpServers();
        loadToolPermissions();
        refreshSkills();

        getStyleClass().add("gray-background");

        providerTab.setNodeSupplier(this::buildProviderTab);
        mcpTab.setNodeSupplier(this::buildMcpTab);
        skillsTab.setNodeSupplier(this::buildSkillsTab);
        searchTab.setNodeSupplier(this::buildSearchTab);
        ocrTab.setNodeSupplier(this::buildOcrTab);
        generalTab.setNodeSupplier(this::buildGeneralTab);
        memoryTab.setNodeSupplier(this::buildMemoryTab);
        dataTab.setNodeSupplier(this::buildDataTab);
        advancedTab.setNodeSupplier(this::buildAdvancedTab);
        helpTab.setNodeSupplier(this::buildHelpTab);
        aboutTab.setNodeSupplier(this::buildAboutTab);

        tab = new TabHeader(transitionPane, providerTab, mcpTab, skillsTab, searchTab, ocrTab, generalTab, memoryTab, dataTab, advancedTab, helpTab, aboutTab);
        tab.select(providerTab, false);

        // NOTE: "图片 OCR" (ocrTab) and "全局记忆" (memoryTab) are deliberately NOT given a
        // navigation entry below anymore — removed from the left nav per the settings-page IA
        // cleanup. Their tabs/content-builders are left in place (unreachable rather than deleted)
        // since the underlying features are still otherwise functional; only navigation to them
        // from here was in scope for this change.
        // 数据设置/高级设置 归入「通用」分组(用户 2026-07-10 真机反馈:单项独占分组太碎),
        // 原 nav.data / nav.advanced 两个分组标题不再使用。
        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("ai.settings.nav.general"))
                .addNavigationDrawerTab(tab, generalTab, i18n("ai.settings.nav.global"), SVG.TUNE)
                .addNavigationDrawerTab(tab, providerTab, i18n("ai.settings.nav.providers"), SVG.DEPLOYED_CODE, SVG.DEPLOYED_CODE_FILL)
                .addNavigationDrawerTab(tab, dataTab, i18n("ai.settings.nav.data_settings"), SVG.FOLDER_OPEN)
                .addNavigationDrawerTab(tab, advancedTab, i18n("ai.settings.nav.advanced_settings"), SVG.SETTINGS, SVG.SETTINGS_FILL)
                .startCategory(i18n("ai.settings.nav.services"))
                .addNavigationDrawerTab(tab, skillsTab, i18n("ai.settings.nav.skills"), SVG.SCRIPT)
                .addNavigationDrawerTab(tab, mcpTab, i18n("ai.settings.nav.mcp"), SVG.SCHEMA, SVG.SCHEMA_FILL)
                .addNavigationDrawerTab(tab, searchTab, i18n("ai.settings.nav.search"), SVG.SEARCH)
                .startCategory(i18n("help").toUpperCase(java.util.Locale.ROOT))
                .addNavigationDrawerTab(tab, helpTab, i18n("ai.settings.nav.help"), SVG.FEEDBACK, SVG.FEEDBACK_FILL)
                .addNavigationDrawerTab(tab, aboutTab, i18n("ai.settings.nav.about"), SVG.INFO, SVG.INFO_FILL);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);
        setCenter(transitionPane);

        providerChoices.selectedValueProperty().addListener((obs, old, profile) -> {
            if (profile != null) {
                aiSettings.setSelectedProfileId(profile.getId());
                saveAiSettings();
                updateProviderDescription();
                refreshModelChoices();
            }
        });

        modelChoices.selectedValueProperty().addListener((obs, old, model) -> {
            AiProviderProfile profile = aiSettings.findSelectedProfile();
            if (model != null && profile != null
                    && !model.getId().equals(profile.getDefaultModelId())) {
                profile.setDefaultModelId(model.getId());
                aiSettings.putProfile(profile);
                saveAiSettings();
                updateModelDescription();
            }
        });
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();
        refreshProviderChoices();
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    /// 使一个 tab 的内容节点失效并（若正显示）立即用 nodeSupplier 重建——
    /// TabHeader.Tab 的节点只建一次，tab.select() 本身并不会刷新已建内容
    /// （TabHeader.select 只在 node == null 时才调 supplier），所以之前那些
    /// "改完数据再 select 一次" 的调用全是假刷新。
    private void invalidateTab(TabHeader.Tab<Node> t) {
        t.setNode(null);
        if (tab.getSelectionModel().getSelectedItem() == t) {
            tab.select(t, false);
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    private Node buildProviderTab() {
        VBox root = createSettingsRoot();
        providerFeedback = new Label();
        providerFeedback.setWrapText(true);
        providerFeedback.getStyleClass().add("subtitle-label");

        providerChoices.setSpacing(6);
        modelChoices.setSpacing(6);

        // ---- 服务商配置：当前提供商卡片（折叠头部显示当前提供商名）----
        providerSublist = new ComponentSublist();
        // Without an explicit title the ComponentSublist header shows its literal default "Group".
        providerSublist.setTitle(i18n("ai.settings.sublist.provider"));
        providerSublist.setHasSubtitle(true);
        LineButton addProviderButton = new LineButton();
        addProviderButton.setTitle(i18n("ai.settings.add_profile"));
        addProviderButton.setLeading(SVG.ADD, 20);
        addProviderButton.setOnAction(e -> createProfile());
        providerSublist.getContent().setAll(providerChoices, addProviderButton);

        // ---- 模型配置：当前模型卡片（按当前提供商过滤）----
        modelSublist = new ComponentSublist();
        // Same "Group" default-title fix as providerSublist above.
        modelSublist.setTitle(i18n("ai.settings.sublist.default_model"));
        modelSublist.setHasSubtitle(true);
        LineButton loadModelButton = new LineButton();
        loadModelButton.setTitle(i18n("ai.settings.load_models"));
        loadModelButton.setLeading(SVG.REFRESH, 20);
        loadModelButton.setOnAction(e -> showLoadModelsDialog());
        LineButton addModelButton = new LineButton();
        addModelButton.setTitle(i18n("ai.settings.add_model"));
        addModelButton.setLeading(SVG.ADD, 20);
        addModelButton.setOnAction(e -> addModel());
        modelSublist.getContent().setAll(modelChoices, loadModelButton, addModelButton);

        refreshProviderChoices();
        refreshModelChoices();

        // Wrap each ComponentSublist in a ComponentList so it gets the native
        // collapsible-card chrome (ComponentSublistWrapper) — same as 设置>默认预设.
        ComponentList providerCard = new ComponentList();
        providerCard.getContent().add(providerSublist);
        ComponentList modelCard = new ComponentList();
        modelCard.getContent().add(modelSublist);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.profiles")),
                providerCard,
                buildModelSectionTitle(),
                modelCard,
                providerFeedback
        );
        return wrapScroll(root);
    }

    /// Builds the "模型配置" category-title row with the connectivity-test (🧪) button on the
    /// right. (Loading models lives as a row inside the model card, above 添加模型.)
    private Node buildModelSectionTitle() {
        Node title = ComponentList.createComponentListTitle(i18n("ai.settings.model_config"));
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        JFXButton testButton = FXUtils.newToggleButton4(SVG.ROCKET_LAUNCH, 16);
        FXUtils.installFastTooltip(testButton, i18n("ai.settings.test_connectivity"));
        testButton.setOnAction(e -> showTestModelsDialog());

        HBox row = new HBox(8, title, spacer, testButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshProviderChoices() {
        List<RadioChoiceList.Choice<AiProviderProfile>> choices = new ArrayList<>();
        for (AiProviderProfile profile : aiSettings.getProfiles()) {
            choices.add(new ProviderChoice(profile));
        }
        providerChoices.setChoices(choices);
        providerChoices.setSelectedValue(aiSettings.findSelectedProfile());
        updateProviderDescription();
    }

    /// Rebuilds the model radio list for the currently selected provider.
    private void refreshModelChoices() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        List<RadioChoiceList.Choice<AiModelEntry>> choices = new ArrayList<>();
        if (profile != null) {
            for (AiModelEntry entry : profile.getModels()) {
                choices.add(new ModelChoice(profile, entry));
            }
        }
        modelChoices.setChoices(choices);
        if (profile != null && profile.getDefaultModelId() != null) {
            AiModelEntry selected = profile.getModel(profile.getDefaultModelId());
            if (selected != null) modelChoices.setSelectedValue(selected);
        }
        updateModelDescription();
    }

    private void updateProviderDescription() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        String name = profile != null ? displayProfileName(profile) : i18n("ai.settings.not_configured");
        if (providerSublist != null) providerSublist.setDescription(name);
        if (providerFeedback != null) {
            providerFeedback.setText(profile == null ? i18n("ai.settings.no_provider_yet") : "");
        }
    }

    private void updateModelDescription() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        String model = profile != null
                ? Objects.toString(profile.getEffectiveModelId(), i18n("ai.settings.not_set"))
                : i18n("ai.settings.not_set");
        if (modelSublist != null) modelSublist.setDescription(model);
    }

    private void createProfile() {
        AiProviderProfile profile = new AiProviderProfile();
        int number = aiSettings.getProfiles().size() + 1;
        profile.setDisplayName(i18n("ai.settings.new_profile_name", number));
        profile.setProtocolFamily(AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        AiProviderDefinition def = AiProviderDefinition.byId(profile.getProtocolFamily());
        if (def != null) profile.setEndpoint(def.getDefaultEndpoint());
        // Do NOT add or persist the profile yet — it is only committed when the user
        // saves the edit dialog (see editProfile). Cancelling "add provider" therefore
        // leaves no empty placeholder profile behind.
        editProfile(profile);
    }

    /// A one-tap provider preset for the add/edit dialog: protocolIndex 0=OpenAI Completions,
    /// 1=OpenAI Reasoning, 2=Anthropic. consoleUrl backs the "获取 API Key" link.
    private record ProviderPreset(String label, int protocolIndex, String endpoint, String consoleUrl) {}

    /// Popular providers, China-reachable ones first (target audience can sign up + pay without a VPN).
    /// Data from the provider-preset research; endpoints are OpenAI-compatible base URLs.
    private static final List<ProviderPreset> PROVIDER_PRESETS = List.of(
            new ProviderPreset("DeepSeek 深度求索", 0, "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
            new ProviderPreset("智谱 GLM (BigModel)", 0, "https://open.bigmodel.cn/api/paas/v4", "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"),
            new ProviderPreset("阿里 通义千问 (百炼)", 0, "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://bailian.console.aliyun.com/?tab=model#/api-key"),
            new ProviderPreset("硅基流动 SiliconFlow", 0, "https://api.siliconflow.cn/v1", "https://cloud.siliconflow.cn/account/ak"),
            new ProviderPreset("月之暗面 Kimi", 0, "https://api.moonshot.cn/v1", "https://platform.moonshot.cn/console/api-keys"),
            new ProviderPreset("火山方舟 / 豆包", 0, "https://ark.cn-beijing.volces.com/api/v3", "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey"),
            new ProviderPreset("百度 文心一言 (千帆)", 0, "https://qianfan.baidubce.com/v2", "https://console.bce.baidu.com/iam/#/iam/apikey/list"),
            new ProviderPreset("MiniMax", 0, "https://api.minimaxi.com/v1", "https://platform.minimaxi.com/user-center/basic-information/interface-key"),
            new ProviderPreset("OpenRouter", 0, "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
            new ProviderPreset("Google Gemini", 0, "https://generativelanguage.googleapis.com/v1beta/openai/", "https://aistudio.google.com/apikey"),
            new ProviderPreset("OpenAI / ChatGPT", 0, "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
            new ProviderPreset("Anthropic Claude", 2, "https://api.anthropic.com", "https://console.anthropic.com/settings/keys"));

    private void editProfile(AiProviderProfile profile) {
        JFXTextField nameField = new JFXTextField(profile.getDisplayName());
        nameField.setPromptText(i18n("ai.settings.profile_name"));
        JFXComboBox<String> protocolBox = new JFXComboBox<>();
        protocolBox.getItems().addAll("OpenAI Completions", "OpenAI Reasoning", "Anthropic");
        protocolBox.getSelectionModel().select(protocolIndexOf(profile.getProtocolFamily()));
        protocolBox.setMaxWidth(Double.MAX_VALUE);
        JFXTextField endpointField = new JFXTextField(profile.getEndpoint());
        endpointField.setPromptText("https://api.example.com/v1");
        MaskedKeyField apiKey = new MaskedKeyField(profile.getApiKey(), "sk-...");

        // "获取 API Key" link under the key field — jumps to the selected preset's console.
        final String[] consoleUrl = { null };
        Hyperlink getKeyLink = new Hyperlink();
        getKeyLink.getStyleClass().add("subtitle-label");
        getKeyLink.setVisible(false);
        getKeyLink.setManaged(false);
        getKeyLink.setOnAction(e -> {
            if (consoleUrl[0] != null && !consoleUrl[0].isBlank()) FXUtils.openLink(consoleUrl[0]);
        });
        VBox apiKeyCell = new VBox(2, apiKey.node, getKeyLink);

        // Quick preset: 3 protocols pinned on top, then popular providers (China-direct first).
        // Picking a provider auto-fills its endpoint + protocol and reveals the 获取 API Key link.
        JFXComboBox<String> presetBox = new JFXComboBox<>();
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.setVisibleRowCount(8);
        presetBox.getItems().add(i18n("ai.settings.preset.placeholder"));
        presetBox.getItems().addAll(
                i18n("ai.settings.preset.protocol_prefix") + "OpenAI Completions",
                i18n("ai.settings.preset.protocol_prefix") + "OpenAI Reasoning",
                i18n("ai.settings.preset.protocol_prefix") + "Anthropic");
        for (ProviderPreset p : PROVIDER_PRESETS) presetBox.getItems().add(p.label());
        presetBox.getSelectionModel().selectFirst();
        presetBox.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            int idx = nv.intValue();
            if (idx >= 1 && idx <= 3) {
                protocolBox.getSelectionModel().select(idx - 1);
            } else if (idx >= 4 && idx - 4 < PROVIDER_PRESETS.size()) {
                ProviderPreset p = PROVIDER_PRESETS.get(idx - 4);
                endpointField.setText(p.endpoint());
                protocolBox.getSelectionModel().select(p.protocolIndex());
                consoleUrl[0] = p.consoleUrl();
                getKeyLink.setText(i18n("ai.settings.get_api_key", p.label()));
                getKeyLink.setVisible(true);
                getKeyLink.setManaged(true);
            }
        });

        JFXCheckBox enabledBox = new JFXCheckBox(i18n("button.enable"));
        enabledBox.setSelected(profile.isEnabled());

        VBox body = new VBox(12, formGrid(
                i18n("ai.settings.preset.label"), presetBox,
                i18n("ai.settings.field.name"), nameField,
                i18n("ai.settings.field.protocol"), protocolBox,
                "Endpoint", endpointField,
                "API Key", apiKeyCell), enabledBox);
        FXUtils.setLimitWidth(body, FORM_WIDTH);

        DialogPane dialog = new DialogPane() {
            @Override
            protected void onAccept() {
                String newName = nameField.getText();
                profile.setDisplayName(newName == null || newName.isBlank() ? displayProfileName(profile) : newName.trim());
                profile.setProtocolFamily(switch (protocolBox.getSelectionModel().getSelectedIndex()) {
                    case 1 -> AiProtocolFamily.OPENAI_REASONING.getId();
                    case 2 -> AiProtocolFamily.ANTHROPIC.getId();
                    default -> AiProtocolFamily.OPENAI_COMPLETIONS.getId();
                });
                profile.setEndpoint(endpointField.getText() != null ? endpointField.getText().trim() : "");
                // Strip ALL whitespace, not just the ends: keys copied from provider consoles
                // often pick up an invisible newline/space mid-string, which turns into a
                // baffling 401 the user cannot see.
                profile.setApiKey(apiKey.getText().replaceAll("\\s+", ""));
                profile.setEnabled(enabledBox.isSelected());
                // Provider config no longer holds model/pricing — those live in 模型配置.
                // Commit only now (on save), and make the just-configured profile active.
                aiSettings.putProfile(profile);
                aiSettings.setSelectedProfileId(profile.getId());
                saveAiSettings();
                refreshProviderChoices();
                refreshModelChoices();
                super.onAccept();
            }
        };
        dialog.setTitle(i18n("ai.settings.edit_profile"));
        dialog.setBody(body);
        Controllers.dialog(dialog);
    }

    /// Maps a protocol-family id to its index in the editor's 协议 dropdown.
    private static int protocolIndexOf(String family) {
        if (AiProtocolFamily.OPENAI_REASONING.getId().equals(family)) return 1;
        if (AiProtocolFamily.ANTHROPIC.getId().equals(family)) return 2;
        return 0;
    }

    private void removeProfile(AiProviderProfile profile) {
        if (aiSettings.getProfiles().size() <= 1) {
            setProviderFeedback(i18n("ai.settings.keep_one_profile"), false);
            return;
        }
        Controllers.confirm(
                i18n("ai.settings.delete_profile.confirm", displayProfileName(profile)),
                i18n("ai.settings.delete_profile.title"),
                () -> {
                    aiSettings.removeProfile(profile.getId());
                    saveAiSettings();
                    refreshProviderChoices();
                },
                null);
    }

    // ---- 模型配置 ----

    /// Opens the model dialog to add a new model to the current provider.
    private void addModel() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        if (profile == null) {
            setProviderFeedback(i18n("ai.settings.add_provider_first"), false);
            return;
        }
        editModel(profile, new AiModelEntry(""));
    }

    /// Opens a custom dialog to configure a single model: an editable id combo (type directly or
    /// pick from the provider's fetched model list — 获取模型列表 lives right beside it) + alias
    /// up top, with collapsible (default-folded) 高级设置 and 定价设置 sections; the 16px-rhythm
    /// spacing keeps the expanded state breathable (2026-07-10 真机反馈：之前太挤).
    ///
    /// 输入/输出模态 text fields were removed outright (2026-07-10): nothing outside this dialog
    /// ever consumed them — capability inference runs entirely off the three "模型能力" checkboxes
    /// below, which are now the single source of truth. The AiModelEntry storage fields stay for
    /// on-disk compatibility (old configs keep loading; values are simply no longer editable).
    private void editModel(AiProviderProfile profile, AiModelEntry entry) {
        // Captured BEFORE the edit so a rename of the default model can move defaultModelId along.
        final String originalId = entry.getId() == null ? "" : entry.getId();
        JFXComboBox<String> idBox = new JFXComboBox<>();
        idBox.setEditable(true);
        idBox.setMaxWidth(Double.MAX_VALUE);
        idBox.setVisibleRowCount(10);
        idBox.setPromptText(i18n("ai.settings.model.id_hint"));
        idBox.setValue(entry.getId());
        idBox.getEditor().setText(entry.getId());

        // 获取模型列表 entry INSIDE the dialog: fetches the provider's model ids in the
        // background and fills the combo's dropdown candidates.
        Label fetchStatus = new Label();
        fetchStatus.getStyleClass().addAll("subtitle-label", "ai-footnote");
        JFXButton fetchBtn = FXUtils.newToggleButton4(SVG.REFRESH, 16);
        FXUtils.installFastTooltip(fetchBtn, i18n("ai.settings.load_models"));
        fetchBtn.setOnAction(e -> {
            fetchStatus.setText(i18n("ai.settings.model.loading"));
            Thread worker = new Thread(() -> {
                try {
                    List<String> ids = discoveryService.discoverModels(profile);
                    Platform.runLater(() -> {
                        String typed = idBox.getEditor().getText();
                        idBox.getItems().setAll(ids);
                        // setAll may clobber the editor via the combo's value sync — restore what
                        // the user had typed so fetching never destroys their input.
                        idBox.getEditor().setText(typed == null ? "" : typed);
                        fetchStatus.setText(i18n("ai.settings.model.loaded_count", ids.size()));
                        if (!ids.isEmpty()) idBox.show();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> fetchStatus.setText(i18n("ai.settings.model.load_failed", ex.getMessage())));
                }
            }, "ai-model-dialog-load");
            worker.setDaemon(true);
            worker.start();
        });
        HBox idCellRow = new HBox(8, idBox, fetchBtn);
        idCellRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(idBox, javafx.scene.layout.Priority.ALWAYS);
        VBox idCell = new VBox(4, idCellRow, fetchStatus);
        idCell.setFillWidth(true);

        JFXTextField aliasField = new JFXTextField(entry.getAlias());
        aliasField.setPromptText(i18n("ai.settings.model.optional"));

        JFXTextField ctxField = new JFXTextField(entry.getContextWindow() > 0 ? String.valueOf(entry.getContextWindow()) : "");
        ctxField.setPromptText(i18n("ai.settings.model.blank_default"));
        JFXTextField maxOutField = new JFXTextField(entry.getMaxOutputTokens() > 0 ? String.valueOf(entry.getMaxOutputTokens()) : "");
        maxOutField.setPromptText(i18n("ai.settings.model.blank_default"));
        JFXTextField tempField = new JFXTextField(entry.hasTemperature() ? String.valueOf(entry.getTemperature()) : "");
        tempField.setPromptText(i18n("ai.settings.model.temperature_hint"));
        JFXTextField reasoningField = new JFXTextField(entry.getReasoningEffort());
        reasoningField.setPromptText(i18n("ai.settings.model.reasoning_hint"));
        GridPane advGrid = new GridPane();
        advGrid.setHgap(16);
        advGrid.setVgap(12);
        ColumnConstraints ac1 = new ColumnConstraints();
        ac1.setPercentWidth(50);
        ac1.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        ColumnConstraints ac2 = new ColumnConstraints();
        ac2.setPercentWidth(50);
        ac2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        advGrid.getColumnConstraints().addAll(ac1, ac2);
        advGrid.add(captionedField(i18n("ai.settings.context_window"), ctxField), 0, 0);
        advGrid.add(captionedField(i18n("ai.settings.model.max_output"), maxOutField), 1, 0);
        advGrid.add(captionedField(i18n("ai.settings.temperature"), tempField), 0, 1);
        advGrid.add(captionedField(i18n("ai.settings.default_reasoning_effort"), reasoningField), 1, 1);
        JFXCheckBox capToolsBox = new JFXCheckBox(i18n("ai.settings.model.cap_tools"));
        capToolsBox.setSelected(entry.isSupportsTools());
        JFXCheckBox capVisionBox = new JFXCheckBox(i18n("ai.settings.model.cap_vision"));
        capVisionBox.setSelected(entry.isSupportsVision());
        JFXCheckBox capReasoningBox = new JFXCheckBox(i18n("ai.settings.model.cap_reasoning"));
        capReasoningBox.setSelected(entry.isSupportsReasoning());
        HBox capRow = new HBox(12, capToolsBox, capVisionBox, capReasoningBox);
        capRow.setAlignment(Pos.CENTER_LEFT);
        advGrid.add(captionedField(i18n("ai.settings.model.capabilities"), capRow), 0, 2, 2, 1);
        advGrid.setPadding(new Insets(4, 0, 4, 0));
        ComponentSublist advPane = new ComponentSublist();
        advPane.setTitle(i18n("ai.settings.advanced"));
        advPane.getContent().setAll(advGrid);

        JFXTextField inField = new JFXTextField(fmtPrice(entry.getInputPricePerMillion()));
        JFXTextField outField = new JFXTextField(fmtPrice(entry.getOutputPricePerMillion()));
        JFXTextField cwField = new JFXTextField(fmtPrice(entry.getCacheWritePricePerMillion()));
        JFXTextField crField = new JFXTextField(fmtPrice(entry.getCacheReadPricePerMillion()));
        // 2x2 grid; each field carries its label as small caption text at the top-left.
        GridPane priceGrid = new GridPane();
        priceGrid.setHgap(16);
        priceGrid.setVgap(12);
        ColumnConstraints pc1 = new ColumnConstraints();
        pc1.setPercentWidth(50);
        pc1.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        ColumnConstraints pc2 = new ColumnConstraints();
        pc2.setPercentWidth(50);
        pc2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        priceGrid.getColumnConstraints().addAll(pc1, pc2);
        priceGrid.add(captionedField(i18n("ai.settings.model.price_in"), inField), 0, 0);
        priceGrid.add(captionedField(i18n("ai.settings.model.price_out"), outField), 1, 0);
        priceGrid.add(captionedField(i18n("ai.settings.model.price_cache_write"), cwField), 0, 1);
        priceGrid.add(captionedField(i18n("ai.settings.model.price_cache_read"), crField), 1, 1);
        priceGrid.setPadding(new Insets(4, 0, 4, 0));
        ComponentSublist pricePane = new ComponentSublist();
        pricePane.setTitle(i18n("ai.settings.model.pricing"));
        pricePane.getContent().setAll(priceGrid);

        // Collapsible (default-folded) 高级/定价 sections, wrapped in a ComponentList for
        // the native collapsible-card chrome.
        ComponentList collapsibles = new ComponentList();
        collapsibles.getContent().addAll(advPane, pricePane);

        // Auto-fill from the bundled model library: when ADDING a new model, once a known model id
        // lands in the combo (typed then focus left, or picked from the fetched dropdown), pre-fill
        // context window / max output / capabilities / pricing so they don't have to look it up.
        // One-shot, only for a new entry.
        if (entry.getId().isEmpty()) {
            final boolean[] filled = {false};
            Runnable autofill = () -> {
                if (filled[0]) return;
                String id = idBox.getEditor().getText() == null ? "" : idBox.getEditor().getText().trim();
                if (id.isEmpty()) return;
                org.jackhuang.hmcl.ai.ModelLibrary.ModelInfo info = org.jackhuang.hmcl.ai.ModelLibrary.find(id);
                if (info == null) return;
                filled[0] = true;
                if (info.getContextWindow() > 0) ctxField.setText(String.valueOf(info.getContextWindow()));
                if (info.getMaxOutput() > 0) maxOutField.setText(String.valueOf(info.getMaxOutput()));
                capToolsBox.setSelected(info.isSupportsTools());
                capVisionBox.setSelected(info.isSupportsVision());
                capReasoningBox.setSelected(info.isSupportsReasoning());
                if (info.getInputPricePerMillion() > 0) inField.setText(fmtPrice(info.getInputPricePerMillion()));
                if (info.getOutputPricePerMillion() > 0) outField.setText(fmtPrice(info.getOutputPricePerMillion()));
                if (info.getCacheWritePricePerMillion() > 0) cwField.setText(fmtPrice(info.getCacheWritePricePerMillion()));
                if (info.getCacheReadPricePerMillion() > 0) crField.setText(fmtPrice(info.getCacheReadPricePerMillion()));
            };
            idBox.getEditor().focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) autofill.run();
            });
            idBox.valueProperty().addListener((obs, old, val) -> autofill.run());
        }

        // 16px 节奏：分区标题/字段块之间留足呼吸感（原 12 太挤——2026-07-10 真机反馈）。
        VBox bodyBox = new VBox(16, formGrid(i18n("ai.settings.model.id"), idCell,
                i18n("ai.settings.model.alias"), aliasField), collapsibles);
        FXUtils.setLimitWidth(bodyBox, FORM_WIDTH);

        DialogPane dialog = new DialogPane() {
            @Override
            protected void onAccept() {
                String editorText = idBox.getEditor().getText();
                String id = editorText == null ? "" : editorText.trim();
                if (id.isEmpty()) {
                    onFailure(i18n("ai.settings.model.id_empty"));
                    return;
                }
                // Renaming (or adding) a model to an id that already belongs to ANOTHER model
                // would make profile.putModel silently produce a duplicate entry — reject and
                // keep the dialog open, same as the empty-id check above.
                boolean duplicate = profile.getModels().stream()
                        .anyMatch(m -> m != entry && id.equals(m.getId()));
                if (duplicate) {
                    onFailure(i18n("ai.settings.model.id_duplicate", id));
                    return;
                }
                entry.setId(id);
                entry.setAlias(aliasField.getText().trim());
                entry.setContextWindow(parseIntSafe(ctxField.getText()));
                entry.setMaxOutputTokens(parseIntSafe(maxOutField.getText()));
                String temp = tempField.getText().trim();
                entry.setTemperature(temp.isEmpty()
                        ? AiModelEntry.TEMPERATURE_UNSET
                        : parseDoubleSafe(temp, AiModelEntry.TEMPERATURE_UNSET));
                entry.setReasoningEffort(reasoningField.getText().trim());
                // 输入/输出模态不再有 UI 入口（能力以三个"模型能力"复选框为唯一事实源）；
                // 存量 entry 里的模态值原样保留，不在这里覆写。
                entry.setSupportsTools(capToolsBox.isSelected());
                entry.setSupportsVision(capVisionBox.isSelected());
                entry.setSupportsReasoning(capReasoningBox.isSelected());
                entry.setInputPricePerMillion(parsePrice(inField.getText()));
                entry.setOutputPricePerMillion(parsePrice(outField.getText()));
                entry.setCacheWritePricePerMillion(parsePrice(cwField.getText()));
                entry.setCacheReadPricePerMillion(parsePrice(crField.getText()));

                profile.putModel(entry);
                if (profile.getDefaultModelId() == null || profile.getDefaultModelId().isEmpty()) {
                    profile.setDefaultModelId(id);
                } else if (profile.getDefaultModelId().equals(originalId) && !originalId.equals(id)) {
                    // The model being renamed IS the default: follow the rename, or every request
                    // would keep sending the old (now nonexistent) model id and 404 forever.
                    profile.setDefaultModelId(id);
                }
                aiSettings.putProfile(profile);
                saveAiSettings();
                refreshProviderChoices();
                refreshModelChoices();
                super.onAccept();
            }
        };
        dialog.setTitle(i18n("ai.settings.model.dialog_title"));
        dialog.setBody(bodyBox);
        Controllers.dialog(dialog);
    }

    /// Builds a two-column (label, field) form grid so labels line up with their inputs.
    private static GridPane formGrid(Object... labelFieldPairs) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(96);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        for (int i = 0; i + 1 < labelFieldPairs.length; i += 2) {
            Label label = new Label((String) labelFieldPairs[i]);
            Node field = (Node) labelFieldPairs[i + 1];
            int row = i / 2;
            grid.add(label, 0, row);
            grid.add(field, 1, row);
            GridPane.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        }
        return grid;
    }

    /// Wraps a field with its label as small caption text at the top-left, for the 2x2
    /// 高级/定价 grids in the model dialog.
    private static VBox captionedField(String caption, javafx.scene.Node field) {
        Label cap = new Label(caption);
        // ai-footnote (10px + variant color) replaces the inline 10px setStyle; the CSS rule
        // lands with the root.css end-state rewrite (B7) — until then the caption renders at
        // subtitle-label size, which is acceptable per the blueprint.
        cap.getStyleClass().addAll("subtitle-label", "ai-footnote");
        if (field instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(4, cap, field);
        box.setFillWidth(true);
        return box;
    }

    private void removeModelEntry(AiProviderProfile profile, AiModelEntry entry) {
        Controllers.confirm(i18n("ai.settings.model.delete_confirm", entry.getDisplayName()),
                i18n("ai.settings.model.delete_title"), () -> {
            profile.removeModel(entry.getId());
            if (entry.getId().equals(profile.getDefaultModelId())) {
                String next = profile.getEffectiveModelId();
                profile.setDefaultModelId(next != null ? next : "");
            }
            aiSettings.putProfile(profile);
            saveAiSettings();
            refreshProviderChoices();
            refreshModelChoices();
        }, null);
    }

    /// 🔄 — opens a multi-provider model-loading dialog. Each provider can fetch its
    /// model list from its API; individual models add via ➕, or all at once via the
    /// provider's ➕. A single search box filters the loaded models across providers.
    private void showLoadModelsDialog() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        if (profile == null) {
            setProviderFeedback(i18n("ai.settings.select_provider_first"), false);
            return;
        }

        List<LoadRow> rows = new ArrayList<>();
        JFXTextField search = new JFXTextField();
        search.setPromptText(i18n("ai.settings.model.search_hint"));
        search.textProperty().addListener((o, ov, nv) -> applyLoadFilter(nv, rows));

        Label status = new Label(i18n("ai.settings.model.loading"));
        status.getStyleClass().add("subtitle-label");

        ComponentList listCard = new ComponentList();
        ScrollPane sp = new ScrollPane(listCard);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        FXUtils.setLimitHeight(sp, 320);

        VBox body = new VBox(10, search, status, sp);
        FXUtils.setLimitWidth(body, FORM_WIDTH);

        // Fetch only the currently-selected provider's models.
        Thread worker = new Thread(() -> {
            try {
                List<String> ids = discoveryService.discoverModels(profile);
                Platform.runLater(() -> {
                    listCard.getContent().clear();
                    for (String id : ids) {
                        HBox row = buildLoadModelRow(profile, id);
                        listCard.getContent().add(row);
                        rows.add(new LoadRow(profile, id, row));
                    }
                    status.setText(i18n("ai.settings.model.loaded_count", ids.size()));
                    applyLoadFilter(search.getText(), rows);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText(i18n("ai.settings.model.load_failed", ex.getMessage())));
            }
        }, "ai-load-models");
        worker.setDaemon(true);
        worker.start();

        DialogPane dialog = new DialogPane() {
            @Override
            protected void onAccept() {
                int added = 0;
                for (LoadRow r : rows) {
                    if (addModelId(profile, r.modelId)) added++;
                }
                persistModelsChange();
                setProviderFeedback(i18n("ai.settings.model.added_count", added, displayProfileName(profile)), true);
                super.onAccept();
            }
        };
        dialog.setTitle(i18n("ai.settings.model.load_dialog_title", displayProfileName(profile)));
        dialog.setBody(body);
        Controllers.dialog(dialog);
    }

    /// Builds one loadable-model row: model id on the left, ➕ to add it on the right.
    private HBox buildLoadModelRow(AiProviderProfile profile, String modelId) {
        Label idLabel = new Label(modelId);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        JFXButton addBtn = FXUtils.newToggleButton4(SVG.ADD, 14);
        FXUtils.installFastTooltip(addBtn, i18n("ai.settings.model.add_this"));
        addBtn.setOnAction(e -> {
            if (addModelId(profile, modelId)) {
                persistModelsChange();
                setProviderFeedback(i18n("ai.settings.model.added_one", modelId), true);
            }
        });
        HBox row = new HBox(8, idLabel, spacer, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 4, 6, 4));
        return row;
    }

    /// Adds a model id to the profile if not already present. Returns whether it was added.
    private boolean addModelId(AiProviderProfile profile, String modelId) {
        if (profile.getModel(modelId) != null) {
            return false;
        }
        profile.putModel(new AiModelEntry(modelId));
        if (profile.getDefaultModelId() == null || profile.getDefaultModelId().isEmpty()) {
            profile.setDefaultModelId(modelId);
        }
        aiSettings.putProfile(profile);
        return true;
    }

    private void persistModelsChange() {
        saveAiSettings();
        refreshProviderChoices();
        refreshModelChoices();
    }

    /// Shows/hides loaded model rows based on the search query (case-insensitive).
    private static void applyLoadFilter(String query, List<LoadRow> rows) {
        String q = query == null ? "" : query.trim().toLowerCase();
        for (LoadRow r : rows) {
            boolean visible = q.isEmpty() || r.modelId.toLowerCase().contains(q);
            r.node.setVisible(visible);
            r.node.setManaged(visible);
        }
    }

    /// 🧪 — opens a multi-provider connectivity-test dialog: a tree of providers and their
    /// models with tri-state provider checkboxes, a select-all box and a search filter.
    /// Default all-selected; "测试" pings each selected provider/model and shows the result.
    private void showTestModelsDialog() {
        List<AiProviderProfile> providers = aiSettings.getProfiles();
        if (providers.isEmpty()) {
            setProviderFeedback(i18n("ai.settings.add_provider_first"), false);
            return;
        }

        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("ai.settings.test_connectivity")));

        List<TestRow> rows = new ArrayList<>();

        JFXCheckBox selectAll = new JFXCheckBox(i18n("button.select_all"));
        selectAll.setAllowIndeterminate(true);
        selectAll.setSelected(true);
        selectAll.setOnAction(e -> {
            // On click: if everything is already selected, clear all; otherwise select all. The
            // per-model listeners then refresh this master's tri-state visual (✓ 全选 / — 半选 / □ 未选).
            boolean allSelected = !rows.isEmpty() && rows.stream().allMatch(r -> r.checkBox.isSelected());
            boolean target = !allSelected;
            for (TestRow r : rows) r.checkBox.setSelected(target);
        });
        JFXTextField search = new JFXTextField();
        search.setPromptText(i18n("ai.settings.model.search_hint"));
        HBox.setHgrow(search, javafx.scene.layout.Priority.ALWAYS);
        search.textProperty().addListener((o, ov, nv) -> {
            String q = nv == null ? "" : nv.trim().toLowerCase();
            for (TestRow r : rows) {
                boolean vis = q.isEmpty() || r.modelId.toLowerCase().contains(q);
                r.node.setVisible(vis);
                r.node.setManaged(vis);
            }
        });
        HBox searchRow = new HBox(10, search, selectAll);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        ComponentList tree = new ComponentList();
        for (AiProviderProfile profile : providers) {
            List<JFXCheckBox> modelBoxes = new ArrayList<>();
            // Native collapsible folder: provider name = sublist title, tri-state checkbox = leading
            // control; model rows are the indented children.
            ComponentSublist folder = new ComponentSublist();
            folder.setTitle(displayProfileName(profile));

            JFXCheckBox providerBox = new JFXCheckBox();
            providerBox.setAllowIndeterminate(true);
            providerBox.setSelected(true);
            providerBox.setOnAction(e -> {
                boolean sel = providerBox.isSelected();
                for (JFXCheckBox mcb : modelBoxes) mcb.setSelected(sel);
            });
            // Clicking the checkbox selects only; it must not toggle the fold.
            providerBox.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
            folder.setLeading(providerBox);

            for (AiModelEntry entry : profile.getModels()) {
                JFXCheckBox mcb = new JFXCheckBox(entry.getDisplayName());
                mcb.setSelected(true);
                Label result = new Label();
                result.getStyleClass().add("subtitle-label");
                javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                HBox row = new HBox(8, mcb, spacer, result);
                row.setAlignment(Pos.CENTER_LEFT);
                // Give the child rows real height so the model isn't squished against the font.
                row.setPadding(new Insets(10, 16, 10, 16));
                folder.getContent().add(row);
                modelBoxes.add(mcb);
                rows.add(new TestRow(profile, entry.getId(), mcb, result, row));
                mcb.selectedProperty().addListener((o, ov, nv) -> {
                    updateProviderBox(providerBox, modelBoxes);
                    updateMasterBox(selectAll, rows);
                });
            }
            if (profile.getModels().isEmpty()) {
                Label none = new Label(i18n("ai.settings.test.no_models"));
                none.getStyleClass().add("subtitle-label");
                none.setPadding(new Insets(10, 16, 10, 16));
                folder.getContent().add(none);
                providerBox.setSelected(false);
            }
            updateProviderBox(providerBox, modelBoxes);
            tree.getContent().add(folder);
        }
        updateMasterBox(selectAll, rows);

        ScrollPane sp = new ScrollPane(tree);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        FXUtils.setLimitHeight(sp, 360);
        // 一行状态文案：全部选中的测试跑完后提示"测试完成"（2026-07-10 真机反馈——之前每行各自
        // 出结果，但没有任何"整批结束了"的信号）。
        Label runStatus = new Label();
        runStatus.getStyleClass().add("subtitle-label");
        VBox body = new VBox(10, searchRow, sp, runStatus);
        FXUtils.setLimitWidth(body, 540);
        layout.setBody(body);

        // pending[0] = 本轮未完成数；generation[0] 区分批次，防止上一轮迟到的结果把新一轮的
        // 计数器错误递减（用户可在跑到一半时再点"测试"）。仅在 FX 线程读写。
        final int[] pending = {0};
        final long[] generation = {0};

        JFXButton testBtn = new JFXButton(i18n("ai.settings.test.run"));
        testBtn.getStyleClass().add("dialog-accept");
        testBtn.setOnAction(e -> {
            generation[0]++;
            final long myGeneration = generation[0];
            pending[0] = (int) rows.stream().filter(r -> r.checkBox.isSelected()).count();
            runStatus.setText(pending[0] == 0 ? "" : i18n("ai.settings.test.batch_running", pending[0]));
            // Runs on the FX thread after EVERY row's outcome (success or failure) lands; when
            // the batch's counter reaches zero, flip the status line to "测试完成". Guarded by
            // generation so a stale row from a superseded run never decrements the new batch.
            Runnable markOneDone = () -> {
                if (generation[0] != myGeneration) return;
                pending[0]--;
                if (pending[0] <= 0) runStatus.setText(i18n("ai.settings.test.done"));
            };
            for (TestRow r : rows) {
                if (!r.checkBox.isSelected()) continue;
                // Reset any previous run's outcome icon/color before re-testing.
                r.result.setText(i18n("ai.settings.test.running"));
                r.result.setGraphic(null);
                r.result.getStyleClass().removeAll("ai-feedback-success", "ai-feedback-error");
                AiProviderProfile p = r.profile;
                String model = r.modelId;
                BATCH_TEST_POOL.submit(() -> {
                    try {
                        long startNanos = System.nanoTime();
                        org.jackhuang.hmcl.ai.agent.ChatAgentFactory.testConnectionSync(
                                p.getEndpoint(), p.getApiKey(), model, p.getProtocolFamily(), 15);
                        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        Platform.runLater(() -> {
                            // SVG icon + themed color class instead of the raw ✓ text character.
                            r.result.setText(elapsedMs + " ms");
                            r.result.setGraphic(SVG.CHECK.createIcon(14));
                            r.result.getStyleClass().add("ai-feedback-success");
                            markOneDone.run();
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            r.result.setText(ex.getMessage());
                            r.result.setGraphic(SVG.CLOSE.createIcon(14));
                            r.result.getStyleClass().add("ai-feedback-error");
                            markOneDone.run();
                        });
                    }
                });
            }
        });
        JFXButton close = new JFXButton(i18n("ai.settings.test.close"));
        close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        layout.setActions(testBtn, close);
        Controllers.dialog(layout);
    }

    /// Updates a provider's tri-state checkbox from its model checkboxes
    /// (none → unchecked, all → checked, some → indeterminate).
    /// Package-private so the FX test can assert the indeterminate (半选) states directly.
    static void updateProviderBox(CheckBox providerBox, List<? extends CheckBox> modelBoxes) {
        if (modelBoxes.isEmpty()) return;
        int selected = 0;
        for (CheckBox c : modelBoxes) if (c.isSelected()) selected++;
        if (selected == 0) {
            providerBox.setIndeterminate(false);
            providerBox.setSelected(false);
        } else if (selected == modelBoxes.size()) {
            providerBox.setIndeterminate(false);
            providerBox.setSelected(true);
        } else {
            providerBox.setIndeterminate(true);
        }
    }

    /// Drives the master "全选" tri-state from the aggregate of all model rows:
    /// ✓ all selected / — some selected (indeterminate) / □ none.
    /// Package-private so the FX test can assert the indeterminate (半选) states directly.
    static void updateMasterBox(CheckBox master, List<TestRow> rows) {
        if (rows.isEmpty()) {
            master.setIndeterminate(false);
            master.setSelected(false);
            return;
        }
        int selected = 0;
        for (TestRow r : rows) if (r.checkBox.isSelected()) selected++;
        if (selected == 0) {
            master.setIndeterminate(false);
            master.setSelected(false);
        } else if (selected == rows.size()) {
            master.setIndeterminate(false);
            master.setSelected(true);
        } else {
            master.setIndeterminate(true);
        }
    }

    /// A password-masked API-key field with a reveal (eye) toggle. Default masked; click the eye to
    /// show/hide. {@link #node} goes into the form; read the value via {@link #getText()}.
    private static final class MaskedKeyField {
        final HBox node;
        private final JFXPasswordField masked;

        MaskedKeyField(String value, String prompt) {
            masked = new JFXPasswordField();
            masked.setText(value == null ? "" : value);
            masked.setPromptText(prompt);
            JFXTextField shown = new JFXTextField();
            shown.setPromptText(prompt);
            shown.textProperty().bindBidirectional(masked.textProperty());
            shown.setManaged(false);
            shown.setVisible(false);
            javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(masked, shown);
            JFXButton eye = new JFXButton();
            eye.setGraphic(SVG.VISIBILITY.createIcon(18));
            eye.setOnAction(e -> {
                boolean show = !shown.isVisible();
                shown.setVisible(show);
                shown.setManaged(show);
                masked.setVisible(!show);
                masked.setManaged(!show);
                eye.setGraphic((show ? SVG.VISIBILITY_OFF : SVG.VISIBILITY).createIcon(18));
            });
            node = new HBox(4, stack, eye);
            node.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(stack, javafx.scene.layout.Priority.ALWAYS);
        }

        String getText() {
            return masked.getText() == null ? "" : masked.getText();
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleSafe(String s, double fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Node buildMcpTab() {
        VBox root = createSettingsRoot();

        // Honest gate: MCP can connect and list a server's tools, but those tools are NOT yet wired
        // into the assistant's tool loop — the agent can't call them. Say so plainly instead of
        // letting users configure something that silently does nothing in chat.
        HintPane experimentalBanner = new HintPane(org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
        experimentalBanner.setText(i18n("ai.settings.mcp.experimental"));
        root.getChildren().add(experimentalBanner);

        ComponentList list = new ComponentList();

        for (AiMcpServerConfig server : mcpServers) {
            LineButton row = new LineButton();
            row.setTitle(server.getDisplayName());
            row.setSubtitle(server.getTransport() + (server.getLastStatus() != null ? " · " + server.getLastStatus() : ""));
            row.setTrailingIcon(SVG.EDIT);
            row.setOnAction(e -> editMcpServer(server));
            list.getContent().add(row);
        }

        LineButton addServer = new LineButton();
        addServer.setTitle(i18n("ai.settings.mcp.add"));
        addServer.setLeading(SVG.ADD, 20);
        addServer.setOnAction(e -> addMcpServer());
        list.getContent().add(addServer);

        root.getChildren().addAll(ComponentList.createComponentListTitle(i18n("ai.settings.nav.mcp")), list);
        return wrapScroll(root);
    }

    private void addMcpServer() {
        // Do NOT pre-add the config: if the user cancels the dialog, a pre-added entry would
        // linger in the list and get persisted as a ghost server by the next save.
        editMcpServer(new AiMcpServerConfig());
    }

    /// Edits {@code server}'s full config as free-form JSON (see {@link McpServerJsonCodec}), the
    /// dialog only closing once the text round-trips through {@link McpServerJsonCodec#validate}
    /// clean — replacing the old fixed 5-question {@link PromptDialogPane} form (which had no way
    /// to edit {@code args}/{@code env}, or in fact {@code autoConnect}/{@code allowedTools}/
    /// {@code exposeResourcesAsTools} either, since that form never asked about them at all).
    private void editMcpServer(AiMcpServerConfig server) {
        String initialJson = McpServerJsonCodec.toJson(server);
        String hint = i18n("ai.settings.mcp.edit_hint");
        JsonEditorDialogPane pane = new JsonEditorDialogPane(i18n("ai.settings.mcp.edit_title"), initialJson, hint,
                McpServerJsonCodec::validate,
                (text, handler) -> {
                    // Re-check here rather than trusting the accept button's enabled state alone:
                    // JsonEditorDialogPane debounces its live validation, so a click landing inside
                    // that window could in principle race a just-typed change.
                    String error = McpServerJsonCodec.validate(text);
                    if (error != null) {
                        handler.reject(error);
                        return;
                    }
                    applyMcpServerEdit(server, text);
                    handler.resolve();
                });
        Controllers.dialog(pane);
    }

    /// Applies a validated MCP-server JSON edit: mutates {@code server}, adds a newly created
    /// server to the list on first confirm, persists, and rebuilds the MCP tab so the change is
    /// visible immediately (see {@link #invalidateTab}). Package-private so the FX test can
    /// exercise the accept path without the full Controllers dialog scaffolding.
    void applyMcpServerEdit(AiMcpServerConfig server, String text) {
        McpServerJsonCodec.apply(server, text);
        if (!mcpServers.contains(server)) {
            mcpServers.add(server); // a newly created server joins the list only on confirm
        }
        saveMcpServers();
        invalidateTab(mcpTab);
    }

    private Node buildSkillsTab() {
        VBox root = createSettingsRoot();

        // ① 工具权限（最常配置，置顶）。全局审批模式选择器已随 SAFE/ASK/YOLO 合并为 Auto 移至
        // "高级设置" tab（见 buildAdvancedTab/buildAutoModeInfoRow）——这里只留下仍然有意义、
        // 逐工具可覆盖的"危险操作二次确认"开关。
        ComponentList permissionCore = new ComponentList();
        permissionCore.getContent().add(buildDangerousConfirmationRow());

        // ② AI 能力与行为：原 buildGeneralTab() 的同名卡片搬到这里，重新定位为这些能力的权限/
        // 开关控制入口——和上面的工具权限、下面的逐工具覆盖表放在同一个 tab 里，而不是散在
        // "全局设置"里。
        ComponentList abilityCard = new ComponentList();
        abilityCard.getContent().add(buildAbilitySublist());

        // ③ 技能。内置技能（isBuiltin()）在这里要完全不可见——不是折叠，是从列表里彻底消失：它们现在
        // 直接从程序内置的 JSON 资源加载到内存，从未落地到 SKILLS_DIR，本来就没有对应的文件夹可给用户
        // 手改/查看；用户自建的技能（仍是 SKILL.md）才在这个列表里可见、可逐条启停。
        //
        // 重扫/启停只就地重建这一张列表的内容（populateSkillList），绝不 invalidateTab 整个 tab——
        // 旧实现整 tab 重建导致用户刚展开的 ComponentSublist（内置工具等折叠卡）被无动画塌缩闪跳
        // （2026-07-10 真机反馈）。
        ComponentList skillList = new ComponentList();
        populateSkillList(skillList, skillRegistry, this::refreshSkills);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.skills.permissions")),
                permissionCore,
                ComponentList.createComponentListTitle(i18n("ai.settings.skills.ability")),
                abilityCard,
                ComponentList.createComponentListTitle(i18n("ai.settings.nav.skills")),
                skillList
        );

        // ④ 逐工具权限覆盖。内置工具(本地/文件系统/搜索)默认折叠收起(高级、罕用);
        //    技能 / MCP 工具来自用户配置,保持可见。
        List<AiToolCatalog.Descriptor> descriptors = buildToolDescriptors();

        ComponentSublist builtinSublist = new ComponentSublist();
        builtinSublist.setTitle(i18n("ai.settings.skills.builtin_tools"));
        builtinSublist.setHasSubtitle(true);
        builtinSublist.setDescription(i18n("ai.settings.skills.builtin_tools.desc"));
        boolean anyBuiltin = false;
        for (AiToolCatalog.Descriptor descriptor : descriptors) {
            ToolSource s = descriptor.source();
            if (s == ToolSource.LOCAL || s == ToolSource.FILESYSTEM || s == ToolSource.SEARCH) {
                builtinSublist.getContent().add(buildToolPermissionRow(descriptor));
                if (PATH_TAKING_TOOLS.contains(descriptor.name())) {
                    builtinSublist.getContent().addAll(buildPathOverrideRows(descriptor.name()));
                }
                anyBuiltin = true;
            }
        }
        if (anyBuiltin) {
            ComponentList builtinCard = new ComponentList();
            builtinCard.getContent().add(builtinSublist);
            root.getChildren().addAll(ComponentList.createComponentListTitle(i18n("ai.settings.skills.builtin_title")), builtinCard);
        }

        for (ToolSource source : new ToolSource[]{ToolSource.SKILL, ToolSource.MCP}) {
            ComponentList group = new ComponentList();
            boolean any = false;
            for (AiToolCatalog.Descriptor descriptor : descriptors) {
                if (descriptor.source() == source) {
                    group.getContent().add(buildToolPermissionRow(descriptor));
                    any = true;
                }
            }
            if (any) {
                root.getChildren().addAll(
                        ComponentList.createComponentListTitle(i18n("ai.settings.skills.source_tools", sourceDisplayName(source))),
                        group);
            }
        }
        return wrapScroll(root);
    }

    /// (Re)builds the user-skill rows IN PLACE inside {@code skillList}: one toggle row per
    /// non-builtin skill, an empty hint when there are none, and the trailing 重新扫描 row.
    /// Rescanning and per-skill enable/disable both re-run THIS method on the same
    /// ComponentList instance — the surrounding tab (and every expanded ComponentSublist in it)
    /// is never rebuilt, so nothing visibly collapses. Static + package-private so the FX test
    /// can exercise the refresh path without constructing the whole settings page.
    static void populateSkillList(ComponentList skillList, SkillRegistry registry, Runnable rescan) {
        skillList.getContent().clear();
        List<SkillManifest> userSkills = registry.list().stream().filter(s -> !s.isBuiltin()).toList();
        for (SkillManifest skill : userSkills) {
            LineButton row = new LineButton();
            row.setTitle(skill.getName() != null ? skill.getName() : "(invalid skill)");
            row.setSubtitle(skill.getDescription() != null ? skill.getDescription() : String.join("; ", skill.getErrors()));
            row.setTrailingIcon(skill.getName() != null && registry.isDisabled(skill.getName()) ? SVG.CHECK : SVG.CHECK_CIRCLE);
            row.setOnAction(e -> {
                if (skill.getName() == null || !skill.isValid()) return;
                if (registry.isDisabled(skill.getName())) registry.enable(skill.getName());
                else registry.disable(skill.getName());
                populateSkillList(skillList, registry, rescan);
            });
            skillList.getContent().add(row);
        }
        if (userSkills.isEmpty()) {
            Label empty = new Label(i18n("ai.settings.skills.empty"));
            empty.setWrapText(true);
            empty.getStyleClass().add("subtitle-label");
            skillList.getContent().add(empty);
        }
        // 重新扫描作为次要动作，放在技能列表末尾
        LineButton reload = new LineButton();
        reload.setTitle(i18n("ai.settings.skills.rescan"));
        reload.setSubtitle(String.valueOf(registry.getSkillsDir()));
        reload.setLeading(SVG.REFRESH, 20);
        reload.setOnAction(e -> {
            rescan.run();
            populateSkillList(skillList, registry, rescan);
        });
        skillList.getContent().add(reload);
    }

    /// Builds the "AI 能力与行为" card — moved here (from the old buildGeneralTab()) as part of the
    /// settings-page IA cleanup, repositioned as the permission/capability control entry point for
    /// the 技能 tab: whether the AI can reach the network, recall memory, auto-match skills, analyze
    /// crashes on its own, and any standing custom instructions it must follow.
    private ComponentSublist buildAbilitySublist() {
        LineButton customInstructions = new LineButton();
        customInstructions.setTitle(i18n("ai.settings.skills.custom_instructions"));
        customInstructions.setSubtitle(aiSettings.getCustomInstructions().isEmpty()
                ? i18n("ai.settings.skills.custom_instructions.unset") : i18n("ai.settings.value_set"));
        customInstructions.setTrailingIcon(SVG.EDIT);
        customInstructions.setOnAction(e -> Controllers.prompt(i18n("ai.settings.skills.custom_instructions.prompt"), (result, handler) -> {
            aiSettings.customInstructionsProperty().set(result.trim());
            saveAiSettings();
            customInstructions.setSubtitle(aiSettings.getCustomInstructions().isEmpty()
                    ? i18n("ai.settings.skills.custom_instructions.unset") : i18n("ai.settings.value_set"));
            handler.resolve();
        }, aiSettings.getCustomInstructions()));

        LineToggleButton crashAnalysis = new LineToggleButton();
        crashAnalysis.setTitle(i18n("ai.settings.auto_crash_analysis"));
        crashAnalysis.setSubtitle(i18n("ai.settings.auto_crash_analysis.desc"));
        crashAnalysis.selectedProperty().bindBidirectional(aiSettings.autoCrashAnalysisEnabledProperty());
        // bindBidirectional only updates the in-memory property — without this listener the
        // toggle silently reverted on restart (nothing ever called save()).
        crashAnalysis.selectedProperty().addListener((o, ov, nv) -> saveAiSettings());

        ComponentSublist abilitySub = new ComponentSublist();
        abilitySub.setTitle(i18n("ai.settings.skills.ability"));
        abilitySub.setHasSubtitle(true);
        abilitySub.setDescription(i18n("ai.settings.skills.ability.desc"));
        // "启用联网工具" moved to the 网络搜索 tab (top row, hot-effective) — 2026-07-10 feedback.
        abilitySub.getContent().setAll(
                disabledToggleRow(i18n("ai.settings.skills.memory"), i18n("ai.settings.skills.memory.desc")),
                disabledToggleRow(i18n("ai.settings.skills.memory_auto"), i18n("ai.settings.skills.memory_auto.desc")),
                toggleRow(i18n("ai.settings.skills.auto_match"), i18n("ai.settings.skills.auto_match.desc"),
                        aiSettings.autoSkillInjectionProperty()),
                crashAnalysis,
                customInstructions);
        return abilitySub;
    }

    private LineToggleButton buildDangerousConfirmationRow() {
        LineToggleButton confirmation = new LineToggleButton();
        confirmation.setTitle(i18n("ai.settings.skills.dangerous_confirm"));
        confirmation.setSubtitle(i18n("ai.settings.skills.dangerous_confirm.desc"));
        confirmation.selectedProperty().bindBidirectional(aiSettings.dangerousActionConfirmationEnabledProperty());
        confirmation.selectedProperty().addListener((obs, old, value) -> saveAiSettings());
        return confirmation;
    }

    private LineSelectButton<AiToolPermissionStore.OverrideMode> buildToolPermissionRow(AiToolCatalog.Descriptor descriptor) {
        LineSelectButton<AiToolPermissionStore.OverrideMode> row = new LineSelectButton<>();
        row.setTitle(descriptor.name());
        row.setSubtitle(descriptor.description() + " · " + sourceDisplayName(descriptor.source()) + " · " + permissionDisplayName(descriptor.permission())
                + " · " + capabilityStatusDisplayName(descriptor.status()));
        row.setItems(List.of(
                AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL,
                AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW
        ));
        row.setNullSafeConverter(mode -> switch (mode) {
            case FOLLOW_GLOBAL -> i18n("ai.settings.skills.override.follow_detail", aiSettings.getApprovalModeEnum().getDisplayName());
            case ALWAYS_ASK -> i18n("ai.settings.skills.override.ask");
            case ALWAYS_ALLOW -> i18n("ai.settings.skills.override.allow_detail");
        });
        row.setValue(toolPermissionStore.getOverride(descriptor.name()));
        row.valueProperty().addListener((obs, old, mode) -> {
            if (mode != null) {
                toolPermissionStore.setOverride(descriptor.name(), mode);
                saveToolPermissions();
            }
        });
        return row;
    }

    /// Tools whose calls take a file-path-like `path` parameter — the ONLY ones a path-glob
    /// override (see {@link AiToolPermissionStore#getOverride(String, String, String)}, Part D)
    /// can ever apply to, since the lookup only consults the rule when the call actually supplies
    /// such a parameter.
    private static final List<String> PATH_TAKING_TOOLS = List.of("read", "write", "edit", "grep", "glob");

    /// Minimal, no-frills editor for one path-taking tool's path-glob override rules (Part D): one
    /// row per existing rule (with a delete action) plus a trailing "add a rule" row that prompts
    /// for a glob pattern and a mode. A path rule is tried FIRST, before the tool-wide override
    /// above, whenever the call's `path` parameter matches — see
    /// {@link AiToolPermissionStore#getOverride(String, String, String)}.
    private List<Node> buildPathOverrideRows(String toolName) {
        List<Node> nodes = new ArrayList<>();
        for (AiToolPermissionStore.PathOverride rule : toolPermissionStore.getPathOverrides(toolName)) {
            LineButton row = new LineButton();
            row.setTitle(i18n("ai.settings.skills.path_rule", rule.glob()));
            row.setSubtitle(i18n("ai.settings.skills.path_rule.desc", pathOverrideModeDisplayName(rule.mode())));
            row.setTrailingIcon(SVG.DELETE);
            row.setOnAction(e -> {
                toolPermissionStore.removePathOverride(toolName, rule.glob());
                saveToolPermissions();
                invalidateTab(skillsTab);
            });
            nodes.add(row);
        }
        LineButton addRule = new LineButton();
        addRule.setTitle(i18n("ai.settings.skills.path_rule.add"));
        addRule.setSubtitle(i18n("ai.settings.skills.path_rule.add_desc", toolName));
        addRule.setLeading(SVG.ADD, 20);
        addRule.setOnAction(e -> {
            PromptDialogPane.Builder builder = new PromptDialogPane.Builder(i18n("ai.settings.skills.path_rule.add_title", toolName), (questions, handler) -> {
                String glob = ((String) questions.get(0).getValue()).trim();
                if (glob.isEmpty()) {
                    handler.reject(i18n("ai.settings.skills.path_rule.glob_empty"));
                    return;
                }
                Integer modeIdx = (Integer) questions.get(1).getValue();
                AiToolPermissionStore.OverrideMode mode = switch (modeIdx == null ? 0 : modeIdx) {
                    case 1 -> AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW;
                    default -> AiToolPermissionStore.OverrideMode.ALWAYS_ASK;
                };
                toolPermissionStore.setPathOverride(toolName, glob, mode);
                saveToolPermissions();
                handler.resolve();
                invalidateTab(skillsTab);
            });
            builder.addQuestion(new PromptDialogPane.Builder.StringQuestion(i18n("ai.settings.skills.path_rule.glob_question"), ""));
            builder.addQuestion(new PromptDialogPane.Builder.CandidatesQuestion(i18n("ai.settings.skills.path_rule.mode_question"),
                    i18n("ai.settings.skills.override.ask"), i18n("ai.settings.skills.path_rule.allow_option")));
            Controllers.prompt(builder);
        });
        nodes.add(addRule);
        return nodes;
    }

    private static String pathOverrideModeDisplayName(AiToolPermissionStore.OverrideMode mode) {
        return switch (mode) {
            case FOLLOW_GLOBAL -> i18n("ai.settings.skills.override.follow");
            case ALWAYS_ASK -> i18n("ai.settings.skills.override.ask");
            case ALWAYS_ALLOW -> i18n("ai.settings.skills.override.allow");
        };
    }

    private Node buildSearchTab() {
        VBox root = createSettingsRoot();

        // ---- 搜索服务（核心 / 必填项排在最前）----
        ComponentList core = new ComponentList();

        // 启用联网工具（从技能 tab 的"AI 能力与行为"卡移入，语义相同）。热生效：AIMainPage 在
        // 构造时通过 WebAccessToolsBinder 监听同一个 webAccessEnabledProperty，开关即时向
        // ToolRegistry 注册/注销 web_search 与 web_fetch —— 不再"重启后生效"。
        core.getContent().add(toggleRow(i18n("ai.settings.search.web_access"),
                i18n("ai.settings.search.web_access.desc"), aiSettings.webAccessEnabledProperty()));

        LineToggleButton enabled = new LineToggleButton();
        enabled.setTitle(i18n("ai.settings.search.enable"));
        enabled.setSubtitle(i18n("ai.settings.search.enable.desc"));
        enabled.setSelected(searchConfig.isEnabled());
        enabled.selectedProperty().addListener((obs, old, val) -> {
            searchConfig.setEnabled(val);
            saveSearchConfig();
        });
        core.getContent().add(enabled);

        LineSelectButton<SearchProvider> provider = new LineSelectButton<>();
        provider.setTitle(i18n("ai.settings.search.provider"));
        provider.setSubtitle(i18n("ai.settings.search.provider.desc"));
        provider.setItems(List.of(SearchProvider.TAVILY, SearchProvider.SEARXNG, SearchProvider.BOCHA, SearchProvider.ZHIPU));
        provider.setNullSafeConverter(SearchProvider::getDisplayName);
        SearchProvider current = SearchProvider.fromId(searchConfig.getProvider().toUpperCase());
        provider.setValue(current != null ? current : SearchProvider.TAVILY);
        core.getContent().add(provider);

        LineButton apiKey = new LineButton();
        apiKey.setTitle("API Key");
        apiKey.setSubtitle(searchConfig.getApiKey().isEmpty() ? i18n("ai.settings.search.key_unset") : i18n("ai.settings.value_set"));
        apiKey.setTrailingIcon(SVG.EDIT);
        apiKey.setOnAction(e -> Controllers.prompt(i18n("ai.settings.search.key_prompt"), (result, handler) -> {
            searchConfig.setApiKey(result.trim());
            saveSearchConfig();
            apiKey.setSubtitle(searchConfig.getApiKey().isEmpty() ? i18n("ai.settings.search.key_unset") : i18n("ai.settings.value_set"));
            handler.resolve();
        }, searchConfig.getApiKey()));
        core.getContent().add(apiKey);

        // 切换服务商时在内部同步默认 endpoint（端点不再作为独立设置项暴露）；API Key 现在按服务商分开存储
        // （见 AiSearchConfig），所以这里还要刷新 apiKey 行的副标题，否则会显示上一个服务商的 key 状态。
        provider.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                searchConfig.setProvider(val.name().toLowerCase());
                if (!val.getDefaultEndpoint().isEmpty()) searchConfig.setEndpoint(val.getDefaultEndpoint());
                saveSearchConfig();
                apiKey.setSubtitle(searchConfig.getApiKey().isEmpty() ? i18n("ai.settings.search.key_unset") : i18n("ai.settings.value_set"));
            }
        });

        // ---- 搜索选项 ----
        ComponentList options = new ComponentList();

        // 结果个数：复用原生 MD3 风格的 JFXSlider，放入原生行的 trailing 槽，行高与列表一致
        LineButton countRow = new LineButton();
        countRow.setTitle(i18n("ai.settings.search.max_results"));
        countRow.setSubtitle(i18n("ai.settings.search.max_results.desc"));
        JFXSlider countSlider = new JFXSlider(1, 50, searchConfig.getMaxResults());
        countSlider.setPrefWidth(160);
        countSlider.setMajorTickUnit(1);
        countSlider.setMinorTickCount(0);
        countSlider.setSnapToTicks(true);
        Label countValue = new Label(String.valueOf(searchConfig.getMaxResults()));
        countValue.setMinWidth(28);
        countValue.setAlignment(Pos.CENTER_RIGHT);
        countSlider.valueProperty().addListener((obs, old, val) -> {
            int v = (int) Math.round(val.doubleValue());
            countValue.setText(String.valueOf(v));
            if (v != searchConfig.getMaxResults()) {
                searchConfig.setMaxResults(v);
                saveSearchConfig();
            }
        });
        HBox countControl = new HBox(8, countSlider, countValue);
        countControl.setAlignment(Pos.CENTER_RIGHT);
        countRow.setTrailingIcon(countControl);
        options.getContent().add(countRow);

        LineButton test = new LineButton();
        test.setTitle(i18n("ai.settings.search.test"));
        test.setSubtitle(i18n("ai.settings.search.test.desc"));
        test.setTrailingIcon(SVG.SEARCH);
        test.setOnAction(e -> Controllers.prompt(i18n("ai.settings.search.test_prompt"), (query, handler) -> {
            // Run the search off the FX thread so the dialog/UI does not freeze while
            // waiting for the network response; report the outcome back on the FX thread.
            String testProvider = searchConfig.getProvider();
            String testEndpoint = searchConfig.getEndpoint();
            String testApiKey = searchConfig.getApiKey();
            int testMaxResults = searchConfig.getMaxResults();
            BATCH_TEST_POOL.submit(() -> {
                try {
                    SearchResponse response = switch (testProvider) {
                        case "searxng" -> new SearxngSearchClient(testEndpoint, testApiKey).search(query, testMaxResults);
                        case "tavily" -> new TavilySearchClient(testEndpoint, testApiKey).search(query, testMaxResults);
                        case "bocha" -> new org.jackhuang.hmcl.ai.search.BochaSearchClient(testApiKey).search(query, testMaxResults);
                        case "zhipu" -> new org.jackhuang.hmcl.ai.search.ZhipuSearchClient(testApiKey).search(query, testMaxResults);
                        default -> throw new UnsupportedOperationException(
                                i18n("ai.settings.search.provider_unavailable", testProvider));
                    };
                    Platform.runLater(() -> {
                        Controllers.showToast(i18n("ai.settings.search.test_ok", response.results().size()));
                        handler.resolve();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> handler.reject(i18n("ai.settings.search.test_failed", ex.getMessage())));
                }
            });
        }, "Minecraft crash report"));
        options.getContent().add(test);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.search.section.service")), core,
                ComponentList.createComponentListTitle(i18n("ai.settings.search.section.options")), options);
        return wrapScroll(root);
    }

    private Node buildOcrTab() {
        VBox root = createSettingsRoot();

        // ---- OCR 服务（核心）----
        ComponentList core = new ComponentList();

        LineToggleButton enabled = new LineToggleButton();
        enabled.setTitle(i18n("ai.settings.ocr.enable"));
        enabled.setSubtitle(i18n("ai.settings.ocr.enable.desc"));
        enabled.setSelected(ocrConfig.isEnabled());
        enabled.selectedProperty().addListener((obs, old, val) -> {
            ocrConfig.setEnabled(val);
            saveOcrConfig();
        });
        core.getContent().add(enabled);

        LineSelectButton<OcrProvider> provider = new LineSelectButton<>();
        provider.setTitle(i18n("ai.settings.ocr.provider"));
        provider.setSubtitle(i18n("ai.settings.ocr.provider.desc"));
        provider.setItems(List.of(OcrProvider.values()));
        provider.setNullSafeConverter(p -> p.getDisplayName() + (p.isImplemented() ? "" : i18n("ai.settings.ocr.pending_suffix")));
        OcrProvider currentProvider = ocrConfig.resolveProvider();
        provider.setValue(currentProvider);
        core.getContent().add(provider);

        // 提供商专属字段卡片，随选择动态重建
        ComponentList providerFields = new ComponentList();
        rebuildOcrProviderFields(providerFields, currentProvider);

        provider.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                ocrConfig.setProvider(val.name());
                // 切换服务商时同步其默认 endpoint（端点在专属字段里仍可改）
                ocrConfig.setEndpoint(val.getDefaultEndpoint());
                saveOcrConfig();
                rebuildOcrProviderFields(providerFields, val);
            }
        });

        // ---- 测试 ----
        ComponentList options = new ComponentList();
        LineButton test = new LineButton();
        test.setTitle(i18n("ai.settings.ocr.test"));
        test.setSubtitle(i18n("ai.settings.ocr.test.desc"));
        test.setTrailingIcon(SVG.LANDSCAPE);
        test.setOnAction(e -> Controllers.prompt(i18n("ai.settings.ocr.test_prompt"), (path, handler) -> {
            String trimmed = path.trim();
            java.nio.file.Path img = java.nio.file.Path.of(trimmed);
            if (!Files.isRegularFile(img)) {
                handler.reject(i18n("ai.settings.ocr.image_not_found", trimmed));
                return;
            }
            OcrProvider p = ocrConfig.resolveProvider();
            org.jackhuang.hmcl.ui.ai.tools.ocr.OcrClient client =
                    org.jackhuang.hmcl.ui.ai.tools.ocr.OcrClientFactory.build(ocrConfig);
            if (client == null) {
                handler.reject(i18n("ai.settings.ocr.provider_unavailable", p.getDisplayName(), p.getNote()));
                return;
            }
            BATCH_TEST_POOL.submit(() -> {
                try {
                    byte[] data = Files.readAllBytes(img);
                    String mime = mimeOf(trimmed);
                    String text = client.recognize(data, mime);
                    Platform.runLater(() -> {
                        Controllers.showToast(i18n("ai.settings.ocr.test_ok", text == null ? 0 : text.length()));
                        handler.resolve();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> handler.reject(i18n("ai.settings.ocr.test_failed", ex.getMessage())));
                }
            });
        }, ""));
        options.getContent().add(test);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.ocr.section.service")), core,
                ComponentList.createComponentListTitle(i18n("ai.settings.profiles")), providerFields,
                ComponentList.createComponentListTitle(i18n("ai.settings.ocr.section.test")), options);
        return wrapScroll(root);
    }

    /// Rebuilds the provider-specific credential rows (API Key / Secret Key / model /
    /// language / endpoint) for the chosen OCR provider, plus a status note.
    private void rebuildOcrProviderFields(ComponentList card, OcrProvider provider) {
        card.getContent().clear();

        LineButton noteRow = new LineButton();
        noteRow.setTitle(i18n("ai.settings.ocr.note"));
        noteRow.setSubtitle((provider.isImplemented() ? i18n("ai.settings.ocr.implemented") : i18n("ai.settings.ocr.preset_only")) + " · " + provider.getNote());
        card.getContent().add(noteRow);

        if (provider.requiresApiKey()) {
            LineButton apiKey = new LineButton();
            apiKey.setTitle("API Key");
            apiKey.setSubtitle(ocrConfig.getApiKey().isEmpty() ? i18n("ai.settings.value_unset") : i18n("ai.settings.value_set"));
            apiKey.setTrailingIcon(SVG.EDIT);
            apiKey.setOnAction(e -> Controllers.prompt("OCR API Key", (result, handler) -> {
                ocrConfig.setApiKey(result.trim());
                saveOcrConfig();
                apiKey.setSubtitle(ocrConfig.getApiKey().isEmpty() ? i18n("ai.settings.value_unset") : i18n("ai.settings.value_set"));
                handler.resolve();
            }, ocrConfig.getApiKey()));
            card.getContent().add(apiKey);
        }

        if (provider.requiresSecret()) {
            LineButton secret = new LineButton();
            secret.setTitle("Secret Key");
            secret.setSubtitle(ocrConfig.getSecretKey().isEmpty() ? i18n("ai.settings.value_unset") : i18n("ai.settings.value_set"));
            secret.setTrailingIcon(SVG.EDIT);
            secret.setOnAction(e -> Controllers.prompt("OCR Secret Key", (result, handler) -> {
                ocrConfig.setSecretKey(result.trim());
                saveOcrConfig();
                secret.setSubtitle(ocrConfig.getSecretKey().isEmpty() ? i18n("ai.settings.value_unset") : i18n("ai.settings.value_set"));
                handler.resolve();
            }, ocrConfig.getSecretKey()));
            card.getContent().add(secret);
        }

        if (provider.requiresModel()) {
            LineButton model = new LineButton();
            model.setTitle(i18n("ai.settings.ocr.vision_model"));
            model.setSubtitle(ocrConfig.getModel().isEmpty() ? i18n("ai.settings.ocr.vision_model.unset") : ocrConfig.getModel());
            model.setTrailingIcon(SVG.EDIT);
            model.setOnAction(e -> Controllers.prompt(i18n("ai.settings.ocr.vision_model.prompt"), (result, handler) -> {
                ocrConfig.setModel(result.trim());
                saveOcrConfig();
                model.setSubtitle(ocrConfig.getModel().isEmpty() ? i18n("ai.settings.ocr.vision_model.unset") : ocrConfig.getModel());
                handler.resolve();
            }, ocrConfig.getModel()));
            card.getContent().add(model);
        }

        if (provider == OcrProvider.OCR_SPACE) {
            LineButton language = new LineButton();
            language.setTitle(i18n("ai.settings.ocr.language"));
            language.setSubtitle(ocrConfig.getLanguage().isEmpty() ? i18n("ai.settings.ocr.language.unset") : ocrConfig.getLanguage());
            language.setTrailingIcon(SVG.EDIT);
            language.setOnAction(e -> Controllers.prompt(i18n("ai.settings.ocr.language.prompt"), (result, handler) -> {
                ocrConfig.setLanguage(result.trim());
                saveOcrConfig();
                language.setSubtitle(ocrConfig.getLanguage().isEmpty() ? i18n("ai.settings.ocr.language.unset") : ocrConfig.getLanguage());
                handler.resolve();
            }, ocrConfig.getLanguage()));
            card.getContent().add(language);
        }

        LineButton endpoint = new LineButton();
        endpoint.setTitle(i18n("ai.settings.ocr.endpoint"));
        endpoint.setSubtitle(ocrConfig.getEndpoint().isEmpty() ? i18n("ai.settings.ocr.endpoint.default") : ocrConfig.getEndpoint());
        endpoint.setTrailingIcon(SVG.EDIT);
        endpoint.setOnAction(e -> Controllers.prompt(i18n("ai.settings.ocr.endpoint.prompt"), (result, handler) -> {
            ocrConfig.setEndpoint(result.trim());
            saveOcrConfig();
            endpoint.setSubtitle(ocrConfig.getEndpoint().isEmpty() ? i18n("ai.settings.ocr.endpoint.default") : ocrConfig.getEndpoint());
            handler.resolve();
        }, ocrConfig.getEndpoint()));
        card.getContent().add(endpoint);
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "image/png";
    }

    // NOTE: OCR config is no longer loaded here — the shared instance is constructor-injected
    // by AIMainPage (the single startup read point). Only saving stays local.
    private void saveOcrConfig() {
        try {
            Files.writeString(OCR_CONFIG_FILE, GSON.toJson(ocrConfig), StandardCharsets.UTF_8);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save OCR config", e);
        }
    }

    private Node buildDataTab() {
        VBox root = createSettingsRoot();
        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.data.section.security")), buildDataSecurityList(),
                ComponentList.createComponentListTitle(i18n("ai.settings.data.section.backup")), buildBackupSettingsList(),
                ComponentList.createComponentListTitle(i18n("ai.settings.data.section.dirs")), buildDataSettingsList(),
                ComponentList.createComponentListTitle(i18n("ai.settings.data.section.cleanup")), buildCleanupSettingsList()
        );
        return wrapScroll(root);
    }

    /// "我的数据与安全" — moved here (from the old buildGeneralTab()) as part of the settings-page
    /// IA cleanup: protecting the user's saves/files belongs with the rest of the data-related
    /// settings, not in a miscellaneous "全局设置" catch-all.
    private ComponentList buildDataSecurityList() {
        ComponentList list = new ComponentList();
        list.getContent().addAll(
                toggleRow(i18n("ai.settings.data.recycle_bin"), i18n("ai.settings.data.recycle_bin.desc"),
                        aiSettings.deleteToRecycleBinProperty()),
                sliderRow(i18n("ai.settings.data.backup_retention"), i18n("ai.settings.data.backup_retention.desc"),
                        aiSettings.worldBackupMaxMbProperty(), 1, 100, i18n("ai.settings.data.backup_retention.unit")),
                buildTraceEnabledRow(),
                buildUploadDiagnosticRow(),
                buildPrivacyNoticeRow());
        return list;
    }

    private Node buildBackupSettingsList() {
        ComponentList list = new ComponentList();

        // Declared first so the 备份/导出 actions below can read its current value;
        // it is added to the list later to keep the original row order.
        LineToggleButton slimBackup = new LineToggleButton();
        slimBackup.setTitle(i18n("ai.settings.data.slim_backup"));
        slimBackup.setSubtitle(i18n("ai.settings.data.slim_backup.desc"));
        slimBackup.setSelected(true);

        LineButton backup = new LineButton();
        backup.setTitle(i18n("ai.settings.data.backup"));
        backup.setSubtitle(i18n("ai.settings.data.backup.desc"));
        backup.setTrailingIcon(SVG.FOLDER_OPEN);
        backup.setOnAction(e -> exportBackup(slimBackup.isSelected(), "hmcl-ae-backup.zip"));
        list.getContent().add(backup);

        LineButton restore = new LineButton();
        restore.setTitle(i18n("ai.settings.data.restore"));
        restore.setSubtitle(i18n("ai.settings.data.restore.desc"));
        restore.setTrailingIcon(SVG.FOLDER_OPEN);
        restore.setOnAction(e -> restoreBackup());
        list.getContent().add(restore);

        list.getContent().add(slimBackup);

        LineButton sessionExport = new LineButton();
        sessionExport.setTitle(i18n("ai.settings.data.export_sessions"));
        sessionExport.setSubtitle(i18n("ai.settings.data.export_sessions.desc"));
        sessionExport.setTrailingIcon(SVG.FOLDER_OPEN);
        sessionExport.setOnAction(e -> exportAllSessions());
        list.getContent().add(sessionExport);

        return list;
    }

    /// Exports ALL conversations to a single file; format follows the chosen extension (.md/.json/.txt).
    private void exportAllSessions() {
        FileChooser fc = new FileChooser();
        fc.setTitle(i18n("ai.settings.data.export_sessions"));
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md"),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter(i18n("ai.settings.data.filter_plain_text"), "*.txt"));
        fc.setInitialFileName("hmcl-ae-conversations.md");
        java.io.File chosen = fc.showSaveDialog(Controllers.getStage());
        if (chosen == null) return;
        Path target = chosen.toPath();
        String lower = chosen.getName().toLowerCase();
        String fmt = lower.endsWith(".json") ? "json" : lower.endsWith(".txt") ? "txt" : "md";
        // Read + format + write OFF the FX thread (a big history froze the UI), and catch
        // EVERYTHING: a corrupt store file throws JsonParseException/NPE, not just IOException —
        // previously that crashed the whole app instead of showing the failure toast.
        Thread exporter = new Thread(() -> {
            try {
                org.jackhuang.hmcl.ai.AiSessionStore store =
                        new org.jackhuang.hmcl.ai.AiSessionStore(SettingsManager.localConfigDirectory());
                store.load();
                java.util.List<org.jackhuang.hmcl.ai.AiSession> sessions = store.listSessions();
                String content;
                if ("json".equals(fmt)) {
                    Path src = SettingsManager.localConfigDirectory().resolve(org.jackhuang.hmcl.ai.AiSessionStore.FILE_NAME);
                    content = Files.exists(src) ? Files.readString(src, StandardCharsets.UTF_8) : "{}";
                } else {
                    content = formatSessions(sessions, "md".equals(fmt));
                }
                Files.writeString(target, content, StandardCharsets.UTF_8);
                javafx.application.Platform.runLater(() ->
                        Controllers.showToast(i18n("ai.settings.data.export_done", sessions.size(), target)));
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                        Controllers.showToast(i18n("ai.settings.data.export_failed", ex.getMessage())));
            }
        }, "ai-session-export");
        exporter.setDaemon(true);
        exporter.start();
    }

    private static String formatSessions(java.util.List<org.jackhuang.hmcl.ai.AiSession> sessions, boolean markdown) {
        StringBuilder sb = new StringBuilder();
        for (org.jackhuang.hmcl.ai.AiSession s : sessions) {
            if (sb.length() > 0) sb.append(markdown ? "\n\n---\n\n" : "\n\n==============================\n\n");
            String title = s.getTitle() == null || s.getTitle().isBlank() ? i18n("ai.settings.data.untitled_session") : s.getTitle();
            sb.append(markdown ? "# " + title + "\n\n" : title + "\n\n");
            for (org.jackhuang.hmcl.ai.llm.LlmMessage m : s.getMessages()) {
                String role = roleLabel(m.getRole());
                String text = m.getContent() == null ? "" : m.getContent();
                if (markdown) {
                    sb.append("**").append(role).append("**:\n\n").append(text).append("\n\n");
                } else {
                    sb.append(role).append(": ").append(text).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private static String roleLabel(String role) {
        if ("user".equals(role)) return i18n("ai.settings.data.role_user");
        if ("assistant".equals(role)) return i18n("ai.settings.data.role_assistant");
        if ("system".equals(role)) return i18n("ai.settings.data.role_system");
        return role == null ? "" : role;
    }

    /// Prompts for a destination and writes an AE data backup (item 1 备份 / 3 导出).
    /// {@code slim=false} exports the full dataset including large folders.
    private void exportBackup(boolean slim, String defaultName) {
        FileChooser fc = new FileChooser();
        fc.setTitle(slim ? i18n("ai.settings.data.backup_title") : i18n("ai.settings.data.export_title"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
        fc.setInitialFileName(defaultName);
        java.io.File chosen = fc.showSaveDialog(Controllers.getStage());
        if (chosen == null) return;
        Path target = chosen.toPath();
        // Zip the whole data dir OFF the FX thread (a large history froze the UI), and catch
        // EVERYTHING, not just IOException — same lessons as exportAllSessions above.
        Thread worker = new Thread(() -> {
            try {
                int n = AiDataBackup.backup(target, slim);
                Platform.runLater(() -> Controllers.showToast(i18n("ai.settings.data.backup_done", n, target)));
            } catch (Exception ex) {
                Platform.runLater(() -> Controllers.showToast(i18n("ai.settings.data.backup_failed", ex.getMessage())));
            }
        }, "ai-data-backup");
        worker.setDaemon(true);
        worker.start();
    }

    /// Prompts for a backup zip and restores it after a confirmation (item 2 恢复).
    private void restoreBackup() {
        FileChooser fc = new FileChooser();
        fc.setTitle(i18n("ai.settings.data.restore_title"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
        java.io.File chosen = fc.showOpenDialog(Controllers.getStage());
        if (chosen == null) return;
        Path source = chosen.toPath();
        Controllers.confirm(
                i18n("ai.settings.data.restore_confirm"),
                i18n("ai.settings.data.restore_confirm_title"),
                () -> {
                    // Unzip OFF the FX thread; catch Exception (not just IOException) — a corrupt
                    // zip throws unchecked exceptions too (same lesson as exportAllSessions).
                    Thread worker = new Thread(() -> {
                        try {
                            int n = AiDataBackup.restore(source);
                            Platform.runLater(() -> Controllers.showToast(i18n("ai.settings.data.restore_done", n)));
                        } catch (Exception ex) {
                            Platform.runLater(() -> Controllers.showToast(i18n("ai.settings.data.restore_failed", ex.getMessage())));
                        }
                    }, "ai-data-restore");
                    worker.setDaemon(true);
                    worker.start();
                },
                null);
    }

    private Node buildDataSettingsList() {
        ComponentList list = new ComponentList();

        LineButton appDataDir = new LineButton();
        appDataDir.setTitle(i18n("ai.settings.data.app_data"));
        appDataDir.setSubtitle(SettingsManager.localConfigDirectory().toString());
        appDataDir.setTrailingIcon(SVG.FOLDER_OPEN);
        appDataDir.setOnAction(e -> FXUtils.openFolder(SettingsManager.localConfigDirectory()));
        list.getContent().add(appDataDir);

        LineButton logDir = new LineButton();
        logDir.setTitle(i18n("ai.settings.data.app_logs"));
        logDir.setSubtitle(SettingsManager.localConfigDirectory().resolve("logs").toString());
        logDir.setTrailingIcon(SVG.FOLDER_OPEN);
        logDir.setOnAction(e -> FXUtils.openFolder(SettingsManager.localConfigDirectory().resolve("logs")));
        list.getContent().add(logDir);

        LineButton skillsDir = new LineButton();
        skillsDir.setTitle(i18n("ai.settings.data.skills_dir"));
        skillsDir.setSubtitle(SKILLS_DIR + i18n("ai.settings.data.skills_dir.note"));
        skillsDir.setTrailingIcon(SVG.FOLDER_OPEN);
        skillsDir.setOnAction(e -> FXUtils.openFolder(SKILLS_DIR));
        list.getContent().add(skillsDir);

        return list;
    }

    private Node buildCleanupSettingsList() {
        ComponentList list = new ComponentList();

        LineButton clearCache = new LineButton();
        clearCache.setTitle(i18n("ai.settings.data.clear_cache"));
        clearCache.setSubtitle(i18n("ai.settings.data.clear_cache.desc"));
        clearCache.setTrailingIcon(SVG.DELETE);
        clearCache.setOnAction(e -> clearCaches());
        list.getContent().add(clearCache);

        return list;
    }

    /// Cache directories under the config dir that are safe to wipe (regenerable).
    /// Never includes ai-settings.json / sessions / skills / memory (= user data).
    private static final List<String> CACHE_DIR_NAMES = List.of("emoji-cache", "cache");

    /// Scans then deletes the regenerable cache directories after a confirmation,
    /// reporting the number of files and bytes freed (item 5 清除缓存).
    private void clearCaches() {
        Path base = SettingsManager.localConfigDirectory();
        List<Path> dirs = new ArrayList<>();
        for (String name : CACHE_DIR_NAMES) {
            Path dir = base.resolve(name);
            if (Files.isDirectory(dir)) dirs.add(dir);
        }
        long[] stat = scanCache(dirs);
        if (stat[0] == 0) {
            Controllers.showToast(i18n("ai.settings.data.no_cache"));
            return;
        }
        Controllers.confirm(
                i18n("ai.settings.data.clear_cache.confirm", stat[0], humanSize(stat[1])),
                i18n("ai.settings.data.clear_cache"),
                () -> {
                    long[] removed = deleteCache(dirs);
                    Controllers.showToast(i18n("ai.settings.data.clear_cache.done", removed[0], humanSize(removed[1])));
                },
                null);
    }

    /// Returns {fileCount, totalBytes} for the regular files under the given dirs.
    private static long[] scanCache(List<Path> dirs) {
        long files = 0, bytes = 0;
        for (Path dir : dirs) {
            try (var stream = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(p)) {
                        files++;
                        try {
                            bytes += Files.size(p);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return new long[]{files, bytes};
    }

    /// Deletes files (then now-empty subdirectories) under the given cache dirs.
    /// Returns {deletedFiles, deletedBytes}.
    private static long[] deleteCache(List<Path> dirs) {
        long files = 0, bytes = 0;
        for (Path dir : dirs) {
            List<Path> all;
            try (var stream = Files.walk(dir)) {
                all = stream.sorted(Comparator.reverseOrder()).toList();
            } catch (IOException e) {
                continue;
            }
            for (Path p : all) {
                try {
                    if (Files.isRegularFile(p)) {
                        long sz;
                        try {
                            sz = Files.size(p);
                        } catch (IOException ignored) {
                            sz = 0;
                        }
                        if (Files.deleteIfExists(p)) {
                            files++;
                            bytes += sz;
                        }
                    } else if (Files.isDirectory(p) && !p.equals(dir)) {
                        Files.deleteIfExists(p); // remove now-empty subdir
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return new long[]{files, bytes};
    }

    /// Formats a byte count as a short human-readable size (B / KB / MB).
    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private Node buildGeneralTab() {
        VBox root = createSettingsRoot();

        // ============ 对话与模型 ============
        // "回复语言" and "AI 标题命名"/"标题命名模型" used to live here — deleted outright (the
        // latter two were dead settings: AiTitleNamingStrategy's AI-powered tier was never more
        // than a scaffolding placeholder, and maybeAutoTitle() never checked the toggle at all; the
        // real feature is "自动命名会话" below, wired to agent.suggestTitle(...)). The "AI 能力与
        // 行为"/"我的数据与安全"/"高级与开发者" cards that used to pile up below this list have
        // moved to the 技能 / 数据设置 / 高级设置 tabs respectively — see buildAbilitySublist(),
        // buildDataSecurityList(), and buildAdvancedTab().
        ComponentList chatList = new ComponentList();

        // 默认推理强度
        LineSelectButton<String> reasoning = new LineSelectButton<>();
        reasoning.setTitle(i18n("ai.settings.default_reasoning_effort"));
        reasoning.setSubtitle(i18n("ai.settings.default_reasoning_effort.desc"));
        reasoning.setItems(List.of("none", "low", "medium", "high", "xhigh", "max"));
        reasoning.setNullSafeConverter(AIMainPage::reasoningEffortLabel); // A12: human-readable names, raw ids stored
        String currentEffort = aiSettings.getReasoningEffort();
        reasoning.setValue(currentEffort.isEmpty() ? "none" : currentEffort);
        reasoning.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                aiSettings.reasoningEffortProperty().set("none".equals(val) ? "" : val);
                saveAiSettings();
            }
        });
        chatList.getContent().add(reasoning);

        // 自动命名会话
        chatList.getContent().add(toggleRow(i18n("ai.settings.auto_title"),
                i18n("ai.settings.auto_title.desc"), aiSettings.autoTitleEnabledProperty()));
        // 自动命名模型：勾选"自动命名会话"后才显示（visible+managed 都绑到开关），选项 = Auto
        // （跟随当前对话模型）+ 全部已配置模型，存储格式 "<profileId>::<modelId>"（空 = Auto）。
        chatList.getContent().add(buildTitleNamingModelRow());
        // 流式输出 / 自动滚动 / 回车发送 等聊天行为已移至「聊天设置」抽屉的「交互」区，不在全局重复。

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.section.chat_model")), chatList);
        return wrapScroll(root);
    }

    /// Builds the "自动命名模型" selector shown under 自动命名会话: Auto (= follow the current
    /// chat model, stored as "") plus every configured model across all providers (stored as
    /// "profileId::modelId" — see {@link AiSettings#resolveTitleNamingModel()}). The row is
    /// visible+managed only while auto-titling is on.
    private LineSelectButton<String> buildTitleNamingModelRow() {
        List<String> items = new ArrayList<>();
        items.add(""); // Auto
        for (AiProviderProfile profile : aiSettings.getProfiles()) {
            for (AiModelEntry entry : profile.getModels()) {
                items.add(profile.getId() + "::" + entry.getId());
            }
        }

        LineSelectButton<String> row = new LineSelectButton<>();
        row.setTitle(i18n("ai.settings.auto_title.model"));
        row.setSubtitle(i18n("ai.settings.auto_title.model.desc"));
        row.setItems(items);
        row.setNullSafeConverter(value -> {
            if (value.isEmpty()) return i18n("ai.settings.auto_title.model.auto");
            int sep = value.indexOf("::");
            String profileId = sep >= 0 ? value.substring(0, sep) : "";
            String modelId = sep >= 0 ? value.substring(sep + 2) : value;
            for (AiProviderProfile profile : aiSettings.getProfiles()) {
                if (profile.getId().equals(profileId)) {
                    AiModelEntry entry = profile.getModel(modelId);
                    return displayProfileName(profile) + " · "
                            + (entry != null ? entry.getDisplayName() : modelId);
                }
            }
            return modelId;
        });
        String current = aiSettings.getTitleNamingModel();
        row.setValue(items.contains(current) ? current : ""); // stale/unknown persisted value → Auto
        row.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                aiSettings.titleNamingModelProperty().set(value);
                saveAiSettings();
            }
        });
        row.visibleProperty().bind(aiSettings.autoTitleEnabledProperty());
        row.managedProperty().bind(aiSettings.autoTitleEnabledProperty());
        return row;
    }

    /// Advanced/developer settings tab: execution limits, dangerous developer-only toggles, and the
    /// Auto approval-mode explainer (see buildAutoModeInfoRow()) — moved here (from the old
    /// buildGeneralTab()'s "高级与开发者" card) as part of the settings-page IA cleanup. This is
    /// also the ONE place the (now-unified) approval mode is surfaced — the duplicate copy that
    /// used to live in the 技能与工具 tab's "工具权限" card has been removed (see buildSkillsTab()).
    private Node buildAdvancedTab() {
        VBox root = createSettingsRoot();

        // 2026-07-10 真机反馈"高级设置太乱"：单张大杂烩卡片重构为分节结构（参照数据设置页的
        // 分节样式）。分组原则：同类相邻、危险项聚拢、开发者项收尾。

        // ① 审批与安全 — 审批模式说明 + 高危红色二次确认（危险项聚拢在最上方，先看到）。
        ComponentList approvalList = new ComponentList();
        approvalList.getContent().addAll(
                buildAutoModeInfoRow(),
                // "危险操作二次确认" itself (the orange, non-critical toggle) lives in the 技能 tab's
                // "工具权限" card (see buildSkillsTab/buildDangerousConfirmationRow) — it's a single
                // copy, not a duplicate, so it stays where it already was rather than moving here too.
                toggleRow(i18n("ai.settings.advanced.critical_confirm"), i18n("ai.settings.advanced.critical_confirm.desc"),
                        aiSettings.criticalConfirmEnabledProperty()));

        // ② 代理循环 — 一轮对话内 agent 循环的预算/压缩类参数。
        ComponentList loopList = new ComponentList();
        loopList.getContent().addAll(
                sliderRow(i18n("ai.settings.advanced.max_tool_cycles"), i18n("ai.settings.advanced.max_tool_cycles.desc"),
                        aiSettings.maxToolCyclesProperty(), 1, 50, ""),
                sliderRow(i18n("ai.settings.advanced.max_context_messages"), i18n("ai.settings.advanced.max_context_messages.desc"),
                        aiSettings.maxContextMessagesProperty(), 0, 100, ""),
                sliderRow(i18n("ai.settings.advanced.tool_result_max"), i18n("ai.settings.advanced.tool_result_max.desc"),
                        aiSettings.toolResultMaxCharsProperty(), 0, 20000, i18n("ai.settings.advanced.unit_chars")),
                toggleRow(i18n("ai.settings.advanced.auto_compact"), i18n("ai.settings.advanced.auto_compact.desc"),
                        aiSettings.autoCompactEnabledProperty()));

        // ③ 模型请求 — 网络请求与花费。
        ComponentList requestList = new ComponentList();
        requestList.getContent().addAll(
                sliderRow(i18n("ai.settings.advanced.request_timeout"), i18n("ai.settings.advanced.request_timeout.desc"),
                        aiSettings.requestTimeoutSecondsProperty(), 15, 600, i18n("ai.settings.advanced.unit_seconds")),
                buildSpendLimitRow());

        // ④ 工具开关 — 默认关闭的可选工具域（NBT / Shell）。
        ComponentList toolsList = new ComponentList();
        toolsList.getContent().addAll(
                toggleRow(i18n("ai.settings.advanced.nbt_tools"), i18n("ai.settings.advanced.nbt_tools.desc"),
                        aiSettings.nbtToolsEnabledProperty()),
                buildShellToolRow());

        // ⑤ 开发者 — 收尾：跳过确认 + 工具调用日志。
        ComponentList developerList = new ComponentList();
        developerList.getContent().addAll(
                buildDangerouslySkipRow(),
                toggleRow(i18n("ai.settings.advanced.tool_call_log"), i18n("ai.settings.advanced.tool_call_log.desc"),
                        aiSettings.toolCallLoggingEnabledProperty()));

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.advanced.section.approval")), approvalList,
                ComponentList.createComponentListTitle(i18n("ai.settings.advanced.section.loop")), loopList,
                ComponentList.createComponentListTitle(i18n("ai.settings.advanced.section.request")), requestList,
                ComponentList.createComponentListTitle(i18n("ai.settings.advanced.section.tools")), toolsList,
                ComponentList.createComponentListTitle(i18n("ai.settings.advanced.section.developer")), developerList);
        return wrapScroll(root);
    }

    /// Replaces the old 3-way 审批模式 selector (SAFE / ASK / YOLO — see {@link AiApprovalMode}'s
    /// own doc for that merge) with a purely informational row: there is nothing left to CHOOSE
    /// (only {@link AiApprovalMode#AUTO} exists), so this just explains what Auto actually does,
    /// tapping through to a longer explanation dialog. The two duplicate approval-mode entries that
    /// used to live in this tab AND in the 技能与工具 tab's "工具权限" card are now this single row.
    private LineButton buildAutoModeInfoRow() {
        LineButton row = new LineButton();
        row.setTitle(i18n("ai.settings.advanced.approval_mode"));
        row.setSubtitle(i18n("ai.settings.advanced.approval_mode.desc"));
        row.setTrailingIcon(SVG.INFO);
        row.setOnAction(e -> Controllers.dialog(
                i18n("ai.settings.advanced.approval_mode.detail"),
                i18n("ai.settings.advanced.approval_mode.title"), MessageType.INFO));
        return row;
    }

    /// Builds the "启用 Shell 工具" toggle. Off by default — see
    /// {@link AiSettings#DEFAULT_SHELL_TOOL_ENABLED}. Turning it ON pops a heads-up confirmation
    /// (not a full red warning like {@link #buildDangerouslySkipRow()} — shell itself isn't
    /// catastrophic, it's just redundant with safer dedicated tools for almost everything);
    /// cancelling reverts the toggle to off.
    private LineToggleButton buildShellToolRow() {
        LineToggleButton t = new LineToggleButton();
        t.setTitle(i18n("ai.settings.advanced.shell"));
        t.setSubtitle(i18n("ai.settings.advanced.shell.desc"));
        t.setSelected(aiSettings.isShellToolEnabled());
        t.selectedProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                Controllers.confirm(
                        i18n("ai.settings.advanced.shell.confirm"),
                        i18n("ai.settings.advanced.shell"),
                        MessageType.WARNING,
                        () -> {
                            aiSettings.shellToolEnabledProperty().set(true);
                            saveAiSettings();
                        },
                        () -> t.setSelected(false)); // cancel → revert to off
            } else {
                aiSettings.shellToolEnabledProperty().set(false);
                saveAiSettings();
            }
        });
        return t;
    }

    /// Builds the 开发者选项 "skip all permission confirmations" toggle. Turning it ON pops a
    /// strong red warning; cancelling reverts the toggle to off.
    private LineToggleButton buildDangerouslySkipRow() {
        LineToggleButton t = new LineToggleButton();
        t.setTitle(i18n("ai.settings.advanced.skip_permissions"));
        t.setSubtitle(i18n("ai.settings.advanced.skip_permissions.desc"));
        t.setSelected(aiSettings.isDangerouslySkipPermissions());
        t.selectedProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                Controllers.confirm(
                        i18n("ai.settings.advanced.skip_permissions.confirm"),
                        i18n("ai.settings.advanced.skip_permissions.title"),
                        MessageType.ERROR,
                        () -> {
                            aiSettings.dangerouslySkipPermissionsProperty().set(true);
                            saveAiSettings();
                        },
                        () -> t.setSelected(false)); // cancel → revert to off
            } else {
                aiSettings.dangerouslySkipPermissionsProperty().set(false);
                saveAiSettings();
            }
        });
        return t;
    }

    // ---- Reusable setting-row helpers (toggle / integer slider) --------------------

    /// Builds a native toggle row bound to a boolean setting; persists on change.
    private LineToggleButton toggleRow(String title, String subtitle, BooleanProperty prop) {
        LineToggleButton t = new LineToggleButton();
        t.setTitle(title);
        t.setSubtitle(subtitle);
        t.selectedProperty().bindBidirectional(prop);
        t.selectedProperty().addListener((o, ov, nv) -> saveAiSettings());
        return t;
    }

    /// Builds a permanently-off, non-interactive toggle row for a feature that's product-disabled
    /// rather than merely defaulted off (currently: global memory) — kept in place, greyed out, so
    /// the setting's existence/intent is still visible instead of silently vanishing.
    private LineToggleButton disabledToggleRow(String title, String subtitle) {
        LineToggleButton t = new LineToggleButton();
        t.setTitle(title);
        t.setSubtitle(subtitle);
        t.setSelected(false);
        t.setDisable(true);
        return t;
    }

    /// Builds a native row with a trailing JFXSlider bound to an integer setting;
    /// shows the live value and persists on change. Reuses the search-tab slider idiom.
    /// Daily AI-spend cap row: reuses {@link #sliderRow} but backs it with the shared SpendTracker
    /// (not an AiSettings property), so setting it persists to the spend file and the chat page's cap
    /// check sees it immediately. Whole-dollar granularity (0 = no limit) is plenty for a safety cap.
    /// A re-viewable "隐私与数据说明" entry (the same notice shown once on first AI use).
    private LineButton buildPrivacyNoticeRow() {
        LineButton row = new LineButton();
        row.setTitle(i18n("ai.settings.data.privacy"));
        row.setSubtitle(i18n("ai.settings.data.privacy.desc"));
        row.setOnAction(e -> AIMainPage.showPrivacyNotice());
        return row;
    }

    /// Toggles whether every turn's full conversation / tool-call trace is written to
    /// `.hmcl/logs/ai-trace/<session>.jsonl`. Flips {@link org.jackhuang.hmcl.ai.trace.TraceRecorder}
    /// live (no restart needed) in addition to persisting the setting.
    private LineToggleButton buildTraceEnabledRow() {
        LineToggleButton t = new LineToggleButton();
        t.setTitle(i18n("ai.settings.data.trace"));
        t.setSubtitle(i18n("ai.settings.data.trace.desc"));
        t.selectedProperty().bindBidirectional(aiSettings.traceEnabledProperty());
        t.selectedProperty().addListener((o, ov, nv) -> {
            org.jackhuang.hmcl.ai.trace.TraceRecorder.setEnabled(nv);
            saveAiSettings();
        });
        return t;
    }

    /// One-tap diagnostic upload: packages the current session's trace (already redacted at
    /// write time) and POSTs it to the feedback endpoint, returning a short reference id.
    /// The actual flow lives in {@link DiagnosticUploadFlow} so the main page's pinned "反馈"
    /// sidebar entry can trigger the identical path without navigating into this page first.
    private LineButton buildUploadDiagnosticRow() {
        LineButton row = new LineButton();
        row.setTitle(i18n("ai.settings.data.upload_diag"));
        row.setSubtitle(i18n("ai.settings.data.upload_diag.desc"));
        row.setTrailingIcon(SVG.FEEDBACK);
        row.setOnAction(e -> DiagnosticUploadFlow.trigger(aiSettings));
        return row;
    }

    private LineButton buildSpendLimitRow() {
        org.jackhuang.hmcl.ai.cost.SpendTracker tracker = AIMainPage.spendTracker();
        IntegerProperty prop = new javafx.beans.property.SimpleIntegerProperty(
                (int) Math.round(tracker.getDailyLimitUsd()));
        prop.addListener((obs, old, val) -> tracker.setDailyLimitUsd(val.doubleValue()));
        return sliderRow(i18n("ai.settings.advanced.spend_limit"), i18n("ai.settings.advanced.spend_limit.desc"),
                prop, 0, 50, i18n("ai.settings.advanced.unit_usd"));
    }

    private LineButton sliderRow(String title, String subtitle, IntegerProperty prop,
                                 int min, int max, String unit) {
        LineButton row = new LineButton();
        row.setTitle(title);
        row.setSubtitle(subtitle);
        int initial = Math.max(min, Math.min(max, prop.get()));
        JFXSlider slider = new JFXSlider(min, max, initial);
        slider.setPrefWidth(160);
        Label value = new Label(initial + unit);
        value.setMinWidth(54);
        value.setAlignment(Pos.CENTER_RIGHT);
        slider.valueProperty().addListener((obs, old, val) -> {
            int v = (int) Math.round(val.doubleValue());
            value.setText(v + unit);
            if (v != prop.get()) {
                prop.set(v);
                saveAiSettings();
            }
        });
        HBox control = new HBox(8, slider, value);
        control.setAlignment(Pos.CENTER_RIGHT);
        row.setTrailingIcon(control);
        return row;
    }

    private Node buildMemoryTab() {
        // Mirror the 帮助/关于 structure (large-title head + native LineButton rows) so the
        // card style and row heights match native HMCL. The global memory is a flat
        // directory of markdown files written by the 记忆 tool (RememberStore); here we
        // surface a count, an open-folder action and the list of entries.
        VBox root = createSettingsRoot();
        Path memDir = SettingsManager.localConfigDirectory().resolve("ai-memory");

        List<RememberStore.Entry> entries = new ArrayList<>();
        try {
            if (Files.isDirectory(memDir)) {
                entries = new RememberStore(memDir).listAll();
            }
        } catch (Exception ignored) {
        }

        ComponentList intro = new ComponentList();
        LineButton head = new LineButton();
        head.setLargeTitle(true);
        head.setLeading(SVG.PACKAGE, 32);
        head.setTitle(i18n("ai.settings.memory.title"));
        head.setSubtitle(i18n("ai.settings.memory.title.desc", entries.size()));
        intro.getContent().add(head);

        ComponentList actions = new ComponentList();
        LineButton openDir = new LineButton();
        openDir.setTitle(i18n("ai.settings.memory.open_dir"));
        openDir.setSubtitle(memDir.toString());
        openDir.setTrailingIcon(SVG.FOLDER_OPEN);
        openDir.setOnAction(e -> FXUtils.openFolder(memDir));
        actions.getContent().add(openDir);
        LineButton reload = new LineButton();
        reload.setTitle(i18n("button.refresh"));
        reload.setSubtitle(i18n("ai.settings.memory.rescan"));
        reload.setLeading(SVG.REFRESH, 20);
        reload.setOnAction(e -> invalidateTab(memoryTab));
        actions.getContent().add(reload);

        ComponentList listCard = new ComponentList();
        if (entries.isEmpty()) {
            Label empty = new Label(i18n("ai.settings.memory.empty"));
            empty.setWrapText(true);
            empty.getStyleClass().add("subtitle-label");
            listCard.getContent().add(empty);
        } else {
            for (RememberStore.Entry entry : entries) {
                Path file = entry.getFile();
                String title = entry.getTitle();
                LineButton row = new LineButton();
                row.setTitle(title != null && !title.isBlank()
                        ? title
                        : (file != null ? file.getFileName().toString() : i18n("ai.settings.memory.untitled")));
                StringBuilder sb = new StringBuilder();
                List<String> tags = entry.getTags();
                if (tags != null && !tags.isEmpty()) sb.append(String.join(", ", tags));
                String created = entry.getCreated();
                if (created != null && !created.isBlank()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(created);
                }
                if (sb.length() == 0 && file != null) sb.append(file.getFileName());
                row.setSubtitle(sb.toString());
                if (file != null && file.getParent() != null) {
                    // Row click opens the memory folder so the user can read/edit the .md file directly
                    // (the store is intentionally a folder of human-editable markdown files).
                    row.setOnAction(e -> FXUtils.openFolder(file.getParent()));
                }
                if (file != null) {
                    final Path entryFile = file;
                    // Native icon-button idiom (toggle-icon4) instead of the ghost class
                    // "ai-memory-delete-btn" that never had a CSS rule.
                    com.jfoenix.controls.JFXButton deleteBtn = FXUtils.newToggleButton4(SVG.DELETE);
                    FXUtils.installFastTooltip(deleteBtn, i18n("ai.settings.memory.delete"));
                    deleteBtn.setOnAction(e -> Controllers.confirm(
                            i18n("ai.settings.memory.delete_confirm"), i18n("ai.settings.memory.delete_title"), () -> {
                                try {
                                    String stem = entryFile.getFileName().toString().replaceAll("\\.md$", "");
                                    new RememberStore(memDir).forget(stem);
                                } catch (Exception ex) {
                                    Controllers.showToast(i18n("ai.settings.memory.delete_failed", ex.getMessage()));
                                }
                                invalidateTab(memoryTab);
                            }, () -> {
                            }));
                    row.setTrailingIcon(deleteBtn);
                } else {
                    row.setTrailingIcon(SVG.FOLDER_OPEN);
                }
                listCard.getContent().add(row);
            }
        }

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.memory.title")), intro,
                ComponentList.createComponentListTitle(i18n("ai.settings.memory.section.actions")), actions,
                ComponentList.createComponentListTitle(i18n("ai.settings.memory.section.entries")), listCard);
        return wrapScroll(root);
    }

    private Node buildHelpTab() {
        VBox root = createSettingsRoot();

        ComponentList intro = new ComponentList();
        LineButton head = new LineButton();
        head.setLargeTitle(true);
        head.setLeading(SVG.FEEDBACK, 32);
        head.setTitle(i18n("ai.settings.help.title"));
        head.setSubtitle(i18n("ai.settings.help.title.desc"));
        intro.getContent().add(head);

        ComponentList notes = new ComponentList();
        LineButton visual = new LineButton();
        visual.setTitle(i18n("ai.settings.help.visual"));
        visual.setSubtitle(i18n("ai.settings.help.visual.desc"));
        LineButton safety = new LineButton();
        safety.setTitle(i18n("ai.settings.help.safety"));
        safety.setSubtitle(i18n("ai.settings.help.safety.desc"));
        notes.getContent().setAll(visual, safety);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.nav.help")),
                intro,
                ComponentList.createComponentListTitle(i18n("ai.settings.help.section.notes")),
                notes);
        return wrapScroll(root);
    }

    private Node buildAboutTab() {
        VBox root = createSettingsRoot();

        ComponentList about = new ComponentList();
        LineButton title = new LineButton();
        title.setLargeTitle(true);
        title.setLeading(SVG.INFO, 32);
        title.setTitle("HMCL-AE");
        title.setSubtitle(i18n("ai.settings.about.tagline"));
        about.getContent().add(title);

        ComponentList info = new ComponentList();
        LineButton impl = new LineButton();
        impl.setTitle(i18n("ai.settings.about.impl"));
        impl.setSubtitle(i18n("ai.settings.about.impl.desc"));
        info.getContent().setAll(impl);

        ComponentList legal = new ComponentList();
        LineButton license = new LineButton();
        license.setTitle(i18n("ai.settings.about.license"));
        license.setSubtitle(i18n("ai.settings.about.license.desc"));
        LineButton openSource = LineButton.createExternalLinkButton("https://github.com/HMCL-dev/HMCL");
        openSource.setTitle(i18n("ai.settings.about.repo"));
        openSource.setSubtitle("github.com/HMCL-dev/HMCL");
        legal.getContent().setAll(license, openSource);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("ai.settings.about.section")),
                about,
                ComponentList.createComponentListTitle(i18n("ai.settings.about.section.impl")),
                info,
                ComponentList.createComponentListTitle(i18n("ai.settings.about.section.legal")),
                legal);
        return wrapScroll(root);
    }

    private VBox createSettingsRoot() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setFillWidth(true);
        return root;
    }

    private ScrollPane wrapScroll(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(scrollPane);
        return scrollPane;
    }

    private void saveAiSettings() {
        try {
            aiSettings.save();
            onSettingsChanged.run();
        } catch (IOException e) {
            setProviderFeedback(i18n("ai.settings.save_failed", e.getMessage()), false);
        }
    }

    private void setProviderFeedback(String message, boolean success) {
        if (providerFeedback == null) return;
        providerFeedback.setText(message);
        providerFeedback.getStyleClass().removeAll("ai-feedback-success", "ai-feedback-error");
        providerFeedback.getStyleClass().add(success ? "ai-feedback-success" : "ai-feedback-error");
    }

    private void loadMcpServers() {
        mcpServers.clear();
        try {
            if (!Files.exists(MCP_CONFIG_FILE)) return;
            String json = Files.readString(MCP_CONFIG_FILE, StandardCharsets.UTF_8);
            List<AiMcpServerConfig> loaded = GSON.fromJson(json, new TypeToken<List<AiMcpServerConfig>>(){}.getType());
            if (loaded != null) mcpServers.addAll(loaded);
        } catch (Exception e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to load MCP server config", e);
        }
    }

    private void saveMcpServers() {
        try {
            Files.writeString(MCP_CONFIG_FILE, GSON.toJson(mcpServers), StandardCharsets.UTF_8);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save MCP server config", e);
        }
    }

    // NOTE: search config is no longer loaded here — the shared instance is constructor-injected
    // by AIMainPage (the single startup read point). Only saving stays local.
    private void saveSearchConfig() {
        try {
            Files.writeString(SEARCH_CONFIG_FILE, GSON.toJson(searchConfig), StandardCharsets.UTF_8);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save search config", e);
        }
    }

    private void loadToolPermissions() {
        try {
            toolPermissionStore.load();
        } catch (Exception e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to load tool permissions", e);
        }
    }

    private void saveToolPermissions() {
        try {
            toolPermissionStore.save();
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("[AI] failed to save tool permissions", e);
        }
    }

    private void refreshSkills() {
        try {
            Files.createDirectories(SKILLS_DIR);
        } catch (IOException ignored) {
        }
        skillRegistry.refresh();
    }

    private List<AiToolCatalog.Descriptor> buildToolDescriptors() {
        // Use the LIVE tool registry (all real registered tools), not the empty MCP registry — so the
        // permission UI reflects what actually exists instead of phantom/renamed entries.
        return AiToolCatalog.descriptorsForRegistry(Controllers.getAiMainPage().getToolRegistry());
    }

    private static String displayProfileName(AiProviderProfile profile) {
        String displayName = profile.getDisplayName();
        if (displayName != null && !displayName.isEmpty()) return displayName;
        return profile.getId().substring(0, Math.min(8, profile.getId().length()));
    }

    /// Parses a price field, returning 0 for blank/invalid input (non-negative).
    private static double parsePrice(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return Math.max(0, Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Formats a price for display, showing a blank field when unset (0).
    private static String fmtPrice(double price) {
        if (price <= 0) return "";
        if (price == Math.rint(price)) return String.valueOf((long) price);
        return String.valueOf(price);
    }

    private static String endpointSuffix(AiProviderProfile profile) {
        String endpoint = profile.getEndpoint();
        return endpoint == null || endpoint.isEmpty() ? "" : " · " + endpoint;
    }

    private static String sourceDisplayName(ToolSource source) {
        return switch (source) {
            case LOCAL -> i18n("ai.settings.tool_source.local");
            case FILESYSTEM -> i18n("ai.settings.tool_source.filesystem");
            case MCP -> "MCP";
            case SEARCH -> i18n("ai.settings.tool_source.search");
            case SKILL -> i18n("ai.settings.tool_source.skill");
        };
    }

    private static String permissionDisplayName(ToolPermission permission) {
        return switch (permission) {
            case READ_ONLY -> i18n("ai.settings.tool_permission.read_only");
            case CONTROLLED_WRITE -> i18n("ai.settings.tool_permission.controlled_write");
            case DANGEROUS_WRITE -> i18n("ai.settings.tool_permission.dangerous_write");
            case EXTERNAL_NETWORK -> i18n("ai.settings.tool_permission.external_network");
        };
    }

    private static String capabilityStatusDisplayName(AiToolCatalog.CapabilityStatus status) {
        return switch (status) {
            case AVAILABLE -> i18n("ai.settings.tool_status.available");
            case REQUIRES_CONTEXT -> i18n("ai.settings.tool_status.requires_context");
            case PLANNED -> i18n("ai.settings.tool_status.planned");
        };
    }

    private final class ProviderChoice extends RadioChoiceList.Choice<AiProviderProfile> {
        private ProviderChoice(AiProviderProfile profile) {
            super(displayProfileName(profile), profile);
            setSubtitle((profile.isEnabled() ? i18n("button.enable") : i18n("button.disable")) + " · " + profile.getProtocolFamily()
                    + " · " + i18n("ai.settings.profile.model_prefix") + Objects.toString(profile.getEffectiveModelId(), i18n("ai.settings.profile.model_manual"))
                    + endpointSuffix(profile));
        }

        @Override
        protected Node createRightNode() {
            JFXButton editButton = FXUtils.newToggleButton4(SVG.EDIT, 14);
            editButton.setOnAction(event -> {
                editProfile(getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(editButton, i18n("ai.settings.edit_profile"));

            JFXButton removeButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER, 14);
            removeButton.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> aiSettings.getProfiles().size() <= 1,
                    providerChoices.selectedValueProperty()));
            removeButton.setOnAction(event -> {
                removeProfile(getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(removeButton, i18n("button.remove"));

            HBox buttons = new HBox(8, editButton, removeButton);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            return buttons;
        }

        @Override
        protected boolean shouldDisableRightNodeWhenUnselected() {
            return false;
        }
    }

    /// A loaded-model row tracked for live search filtering in the load dialog.
    private static final class LoadRow {
        final AiProviderProfile profile;
        final String modelId;
        final javafx.scene.Node node;

        LoadRow(AiProviderProfile profile, String modelId, javafx.scene.Node node) {
            this.profile = profile;
            this.modelId = modelId;
            this.node = node;
        }
    }

    /// A connectivity-test row tracked in the test dialog. Package-private (not private) so the
    /// tri-state FX test can build rows for {@link #updateMasterBox}.
    static final class TestRow {
        final AiProviderProfile profile;
        final String modelId;
        final CheckBox checkBox;
        final Label result;
        final javafx.scene.Node node;

        TestRow(AiProviderProfile profile, String modelId, CheckBox checkBox, Label result, javafx.scene.Node node) {
            this.profile = profile;
            this.modelId = modelId;
            this.checkBox = checkBox;
            this.result = result;
            this.node = node;
        }
    }

    /// A model row: radio (= default model) on the left, edit + delete on the right.
    private final class ModelChoice extends RadioChoiceList.Choice<AiModelEntry> {
        private final AiProviderProfile profile;

        private ModelChoice(AiProviderProfile profile, AiModelEntry entry) {
            super(entry.getDisplayName(), entry);
            this.profile = profile;
        }

        @Override
        protected Node createRightNode() {
            JFXButton editButton = FXUtils.newToggleButton4(SVG.EDIT, 14);
            editButton.setOnAction(event -> {
                editModel(profile, getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(editButton, i18n("ai.settings.model.edit"));

            JFXButton removeButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER, 14);
            removeButton.setOnAction(event -> {
                removeModelEntry(profile, getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(removeButton, i18n("button.remove"));

            HBox buttons = new HBox(8, editButton, removeButton);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            return buttons;
        }

        @Override
        protected boolean shouldDisableRightNodeWhenUnselected() {
            return false;
        }
    }
}
