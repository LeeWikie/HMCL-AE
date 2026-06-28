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
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.AiModelDiscoveryService;
import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProviderDefinition;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.mcp.AiMcpServerConfig;
import org.jackhuang.hmcl.ai.mcp.McpClientManager;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class AISettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path MCP_CONFIG_FILE = SettingsManager.localConfigDirectory().resolve("ai-mcp-settings.json");
    private static final Path SEARCH_CONFIG_FILE = SettingsManager.localConfigDirectory().resolve("ai-search-settings.json");
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
    private AiSearchConfig searchConfig = new AiSearchConfig();

    private final TransitionPane transitionPane = new TransitionPane();
    private final TabHeader.Tab<Node> providerTab = new TabHeader.Tab<>("aiProviderTab");
    private final TabHeader.Tab<Node> mcpTab = new TabHeader.Tab<>("aiMcpTab");
    private final TabHeader.Tab<Node> skillsTab = new TabHeader.Tab<>("aiSkillsTab");
    private final TabHeader.Tab<Node> searchTab = new TabHeader.Tab<>("aiSearchTab");
    private final TabHeader.Tab<Node> generalTab = new TabHeader.Tab<>("aiGeneralTab");
    private final TabHeader.Tab<Node> memoryTab = new TabHeader.Tab<>("aiMemoryTab");
    private final TabHeader.Tab<Node> dataTab = new TabHeader.Tab<>("aiDataTab");
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

    public AISettingsPage(AiSettings aiSettings, AiModelDiscoveryService discoveryService, Runnable onSettingsChanged) {
        this.aiSettings = aiSettings;
        this.discoveryService = discoveryService;
        this.onSettingsChanged = onSettingsChanged;

        skillRegistry.setSkillsDir(SKILLS_DIR);
        loadMcpServers();
        loadSearchConfig();
        loadToolPermissions();
        refreshSkills();

        getStyleClass().add("gray-background");

        providerTab.setNodeSupplier(this::buildProviderTab);
        mcpTab.setNodeSupplier(this::buildMcpTab);
        skillsTab.setNodeSupplier(this::buildSkillsTab);
        searchTab.setNodeSupplier(this::buildSearchTab);
        generalTab.setNodeSupplier(this::buildGeneralTab);
        memoryTab.setNodeSupplier(this::buildMemoryTab);
        dataTab.setNodeSupplier(this::buildDataTab);
        helpTab.setNodeSupplier(this::buildHelpTab);
        aboutTab.setNodeSupplier(this::buildAboutTab);

        tab = new TabHeader(transitionPane, providerTab, mcpTab, skillsTab, searchTab, generalTab, memoryTab, dataTab, helpTab, aboutTab);
        tab.select(providerTab, false);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory("通用")
                .addNavigationDrawerTab(tab, generalTab, "全局设置", SVG.TUNE)
                .addNavigationDrawerTab(tab, providerTab, "模型服务", SVG.DEPLOYED_CODE, SVG.DEPLOYED_CODE_FILL)
                .startCategory("服务")
                .addNavigationDrawerTab(tab, skillsTab, "技能与工具", SVG.SCRIPT)
                .addNavigationDrawerTab(tab, mcpTab, "MCP服务器", SVG.SCHEMA, SVG.SCHEMA_FILL)
                .addNavigationDrawerTab(tab, searchTab, "网络搜索", SVG.SEARCH)
                .startCategory("数据")
                .addNavigationDrawerTab(tab, dataTab, "数据设置", SVG.FOLDER_OPEN)
                .addNavigationDrawerTab(tab, memoryTab, "全局记忆", SVG.PACKAGE)
                .startCategory(i18n("help").toUpperCase(java.util.Locale.ROOT))
                .addNavigationDrawerTab(tab, helpTab, "帮助", SVG.FEEDBACK, SVG.FEEDBACK_FILL)
                .addNavigationDrawerTab(tab, aboutTab, "关于 HMCL-AE", SVG.INFO, SVG.INFO_FILL);
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
        providerSublist.setHasSubtitle(true);
        LineButton addProviderButton = new LineButton();
        addProviderButton.setTitle("添加配置");
        addProviderButton.setLeading(SVG.ADD, 20);
        addProviderButton.setOnAction(e -> createProfile());
        providerSublist.getContent().setAll(providerChoices, addProviderButton);

        // ---- 模型配置：当前模型卡片（按当前提供商过滤）----
        modelSublist = new ComponentSublist();
        modelSublist.setHasSubtitle(true);
        LineButton loadModelButton = new LineButton();
        loadModelButton.setTitle("获取模型列表");
        loadModelButton.setLeading(SVG.REFRESH, 20);
        loadModelButton.setOnAction(e -> showLoadModelsDialog());
        LineButton addModelButton = new LineButton();
        addModelButton.setTitle("添加模型");
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
                ComponentList.createComponentListTitle("服务商配置"),
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
        Node title = ComponentList.createComponentListTitle("模型配置");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        JFXButton testButton = FXUtils.newToggleButton4(SVG.SEARCH, 16);
        FXUtils.installFastTooltip(testButton, "测试连通性");
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
        String name = profile != null ? displayProfileName(profile) : "未配置";
        if (providerSublist != null) providerSublist.setDescription(name);
        if (providerFeedback != null) {
            providerFeedback.setText(profile == null ? "尚未配置任何提供商。" : "");
        }
    }

    private void updateModelDescription() {
        AiProviderProfile profile = aiSettings.findSelectedProfile();
        String model = profile != null ? Objects.toString(profile.getEffectiveModelId(), "未设置") : "未设置";
        if (modelSublist != null) modelSublist.setDescription(model);
    }

    private void createProfile() {
        AiProviderProfile profile = new AiProviderProfile();
        int number = aiSettings.getProfiles().size() + 1;
        profile.setDisplayName("模型服务 " + number);
        profile.setProtocolFamily(AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        AiProviderDefinition def = AiProviderDefinition.byId(profile.getProtocolFamily());
        if (def != null) profile.setEndpoint(def.getDefaultEndpoint());
        // Do NOT add or persist the profile yet — it is only committed when the user
        // saves the edit dialog (see editProfile). Cancelling "add provider" therefore
        // leaves no empty placeholder profile behind.
        editProfile(profile);
    }

    private void editProfile(AiProviderProfile profile) {
        JFXTextField nameField = new JFXTextField(profile.getDisplayName());
        nameField.setPromptText("配置名称");
        JFXComboBox<String> protocolBox = new JFXComboBox<>();
        protocolBox.getItems().addAll("OpenAI Completions", "OpenAI Reasoning", "Anthropic");
        protocolBox.getSelectionModel().select(protocolIndexOf(profile.getProtocolFamily()));
        protocolBox.setMaxWidth(Double.MAX_VALUE);
        JFXTextField endpointField = new JFXTextField(profile.getEndpoint());
        endpointField.setPromptText("https://api.example.com/v1");
        JFXTextField apiKeyField = new JFXTextField(profile.getApiKey());
        apiKeyField.setPromptText("sk-...");
        JFXCheckBox enabledBox = new JFXCheckBox("启用");
        enabledBox.setSelected(profile.isEnabled());

        VBox body = new VBox(12, formGrid(
                "名称", nameField,
                "协议", protocolBox,
                "Endpoint", endpointField,
                "API Key", apiKeyField), enabledBox);
        FXUtils.setLimitWidth(body, 480);

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
                profile.setApiKey(apiKeyField.getText() != null ? apiKeyField.getText().trim() : "");
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
        dialog.setTitle("编辑配置");
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
            setProviderFeedback("至少保留一个模型服务配置。", false);
            return;
        }
        Controllers.confirm(
                "确定删除 " + displayProfileName(profile) + "？",
                "删除模型服务",
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
            setProviderFeedback("请先添加提供商。", false);
            return;
        }
        editModel(profile, new AiModelEntry(""));
    }

    /// Opens a custom dialog to configure a single model: id + alias up top, with
    /// collapsible (default-folded) 高级设置 and 定价设置 sections; labels are aligned
    /// against their fields via a two-column grid.
    private void editModel(AiProviderProfile profile, AiModelEntry entry) {
        JFXTextField idField = new JFXTextField(entry.getId());
        idField.setPromptText("例如 gpt-4.1 / claude-sonnet-4-5 / deepseek-chat");
        JFXTextField aliasField = new JFXTextField(entry.getAlias());
        aliasField.setPromptText("可选");

        JFXTextField ctxField = new JFXTextField(entry.getContextWindow() > 0 ? String.valueOf(entry.getContextWindow()) : "");
        ctxField.setPromptText("留空=默认");
        JFXTextField maxOutField = new JFXTextField(entry.getMaxOutputTokens() > 0 ? String.valueOf(entry.getMaxOutputTokens()) : "");
        maxOutField.setPromptText("留空=默认");
        JFXTextField tempField = new JFXTextField(entry.hasTemperature() ? String.valueOf(entry.getTemperature()) : "");
        tempField.setPromptText("留空=默认，0~2");
        JFXTextField reasoningField = new JFXTextField(entry.getReasoningEffort());
        reasoningField.setPromptText("留空 / none / low / medium / high / xhigh / max");
        GridPane advGrid = new GridPane();
        advGrid.setHgap(10);
        advGrid.setVgap(8);
        ColumnConstraints ac1 = new ColumnConstraints();
        ac1.setPercentWidth(50);
        ac1.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        ColumnConstraints ac2 = new ColumnConstraints();
        ac2.setPercentWidth(50);
        ac2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        advGrid.getColumnConstraints().addAll(ac1, ac2);
        advGrid.add(captionedField("上下文窗口", ctxField), 0, 0);
        advGrid.add(captionedField("最大输出 tokens", maxOutField), 1, 0);
        advGrid.add(captionedField("温度", tempField), 0, 1);
        advGrid.add(captionedField("默认推理强度", reasoningField), 1, 1);
        ComponentSublist advPane = new ComponentSublist();
        advPane.setTitle("高级设置");
        advPane.getContent().setAll(advGrid);

        JFXTextField inField = new JFXTextField(fmtPrice(entry.getInputPricePerMillion()));
        JFXTextField outField = new JFXTextField(fmtPrice(entry.getOutputPricePerMillion()));
        JFXTextField cwField = new JFXTextField(fmtPrice(entry.getCacheWritePricePerMillion()));
        JFXTextField crField = new JFXTextField(fmtPrice(entry.getCacheReadPricePerMillion()));
        // 2x2 grid; each field carries its label as small caption text at the top-left.
        GridPane priceGrid = new GridPane();
        priceGrid.setHgap(10);
        priceGrid.setVgap(8);
        ColumnConstraints pc1 = new ColumnConstraints();
        pc1.setPercentWidth(50);
        pc1.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        ColumnConstraints pc2 = new ColumnConstraints();
        pc2.setPercentWidth(50);
        pc2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        priceGrid.getColumnConstraints().addAll(pc1, pc2);
        priceGrid.add(captionedField("输入单价", inField), 0, 0);
        priceGrid.add(captionedField("输出单价", outField), 1, 0);
        priceGrid.add(captionedField("缓存创建", cwField), 0, 1);
        priceGrid.add(captionedField("缓存读取", crField), 1, 1);
        ComponentSublist pricePane = new ComponentSublist();
        pricePane.setTitle("定价设置（可选）");
        pricePane.getContent().setAll(priceGrid);

        // Collapsible (default-folded) 高级/定价 sections, wrapped in a ComponentList for
        // the native collapsible-card chrome.
        ComponentList collapsibles = new ComponentList();
        collapsibles.getContent().addAll(advPane, pricePane);

        VBox bodyBox = new VBox(12, formGrid("模型 ID", idField, "显示别名", aliasField), collapsibles);
        FXUtils.setLimitWidth(bodyBox, 480);

        DialogPane dialog = new DialogPane() {
            @Override
            protected void onAccept() {
                String id = idField.getText().trim();
                if (id.isEmpty()) {
                    onFailure("模型 ID 不能为空");
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
                entry.setInputPricePerMillion(parsePrice(inField.getText()));
                entry.setOutputPricePerMillion(parsePrice(outField.getText()));
                entry.setCacheWritePricePerMillion(parsePrice(cwField.getText()));
                entry.setCacheReadPricePerMillion(parsePrice(crField.getText()));

                profile.putModel(entry);
                if (profile.getDefaultModelId() == null || profile.getDefaultModelId().isEmpty()) {
                    profile.setDefaultModelId(id);
                }
                aiSettings.putProfile(profile);
                saveAiSettings();
                refreshProviderChoices();
                refreshModelChoices();
                super.onAccept();
            }
        };
        dialog.setTitle("配置模型");
        dialog.setBody(bodyBox);
        Controllers.dialog(dialog);
    }

    /// Builds a two-column (label, field) form grid so labels line up with their inputs.
    private static GridPane formGrid(Object... labelFieldPairs) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
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
        cap.getStyleClass().add("subtitle-label");
        cap.setStyle("-fx-font-size: 10px;");
        if (field instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(2, cap, field);
        box.setFillWidth(true);
        return box;
    }

    private void removeModelEntry(AiProviderProfile profile, AiModelEntry entry) {
        Controllers.confirm("确定删除模型 " + entry.getDisplayName() + "？", "删除模型", () -> {
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
            setProviderFeedback("请先选择提供商。", false);
            return;
        }

        List<LoadRow> rows = new ArrayList<>();
        JFXTextField search = new JFXTextField();
        search.setPromptText("搜索模型...");
        search.textProperty().addListener((o, ov, nv) -> applyLoadFilter(nv, rows));

        Label status = new Label("加载中...");
        status.getStyleClass().add("subtitle-label");

        ComponentList listCard = new ComponentList();
        ScrollPane sp = new ScrollPane(listCard);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        FXUtils.setLimitHeight(sp, 320);

        VBox body = new VBox(10, search, status, sp);
        FXUtils.setLimitWidth(body, 480);

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
                    status.setText("共 " + ids.size() + " 个模型，点 + 添加，或「确定」全部添加");
                    applyLoadFilter(search.getText(), rows);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText("加载失败：" + ex.getMessage()));
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
                setProviderFeedback("已添加 " + added + " 个模型到 " + displayProfileName(profile) + "。", true);
                super.onAccept();
            }
        };
        dialog.setTitle("加载模型 · " + displayProfileName(profile));
        dialog.setBody(body);
        Controllers.dialog(dialog);
    }

    /// Builds one loadable-model row: model id on the left, ➕ to add it on the right.
    private HBox buildLoadModelRow(AiProviderProfile profile, String modelId) {
        Label idLabel = new Label(modelId);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        JFXButton addBtn = FXUtils.newToggleButton4(SVG.ADD, 14);
        FXUtils.installFastTooltip(addBtn, "添加此模型");
        addBtn.setOnAction(e -> {
            if (addModelId(profile, modelId)) {
                persistModelsChange();
                setProviderFeedback("已添加模型 " + modelId + "。", true);
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
            setProviderFeedback("请先添加提供商。", false);
            return;
        }

        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label("测试连通性"));

        List<TestRow> rows = new ArrayList<>();

        JFXCheckBox selectAll = new JFXCheckBox("全选");
        selectAll.setSelected(true);
        selectAll.setOnAction(e -> {
            for (TestRow r : rows) r.checkBox.setSelected(selectAll.isSelected());
        });
        JFXTextField search = new JFXTextField();
        search.setPromptText("搜索模型...");
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

        VBox treeBox = new VBox(10);
        for (AiProviderProfile profile : providers) {
            List<JFXCheckBox> modelBoxes = new ArrayList<>();
            // One native card per provider: provider checkbox header + indented model rows.
            ComponentList card = new ComponentList();

            JFXCheckBox providerBox = new JFXCheckBox(displayProfileName(profile));
            providerBox.setAllowIndeterminate(true);
            providerBox.setSelected(true);
            providerBox.setOnAction(e -> {
                boolean sel = providerBox.isSelected();
                for (JFXCheckBox mcb : modelBoxes) mcb.setSelected(sel);
            });
            card.getContent().add(providerBox);

            for (AiModelEntry entry : profile.getModels()) {
                JFXCheckBox mcb = new JFXCheckBox(entry.getDisplayName());
                mcb.setSelected(true);
                Label result = new Label();
                result.getStyleClass().add("subtitle-label");
                javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                HBox row = new HBox(8, mcb, spacer, result);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(2, 4, 2, 24));
                card.getContent().add(row);
                modelBoxes.add(mcb);
                rows.add(new TestRow(profile, entry.getId(), mcb, result, row));
                mcb.selectedProperty().addListener((o, ov, nv) -> updateProviderBox(providerBox, modelBoxes));
            }
            if (profile.getModels().isEmpty()) {
                Label none = new Label("（无模型，先用 🔄 加载）");
                none.getStyleClass().add("subtitle-label");
                none.setPadding(new Insets(2, 4, 2, 24));
                card.getContent().add(none);
                providerBox.setSelected(false);
            }
            updateProviderBox(providerBox, modelBoxes);
            treeBox.getChildren().add(card);
        }

        ScrollPane sp = new ScrollPane(treeBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        FXUtils.setLimitHeight(sp, 360);
        VBox body = new VBox(10, searchRow, sp);
        FXUtils.setLimitWidth(body, 540);
        layout.setBody(body);

        JFXButton testBtn = new JFXButton("测试");
        testBtn.getStyleClass().add("dialog-accept");
        testBtn.setOnAction(e -> {
            for (TestRow r : rows) {
                if (!r.checkBox.isSelected()) continue;
                r.result.setText("测试中...");
                AiProviderProfile p = r.profile;
                String model = r.modelId;
                Thread worker = new Thread(() -> {
                    try {
                        org.jackhuang.hmcl.ai.agent.ChatAgentFactory.testConnectionSync(
                                p.getEndpoint(), p.getApiKey(), model, p.getProtocolFamily(), 15);
                        Platform.runLater(() -> r.result.setText("✓ 正常"));
                    } catch (Exception ex) {
                        Platform.runLater(() -> r.result.setText("✗ " + ex.getMessage()));
                    }
                }, "ai-test-conn");
                worker.setDaemon(true);
                worker.start();
            }
        });
        JFXButton close = new JFXButton("关闭");
        close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        layout.setActions(testBtn, close);
        Controllers.dialog(layout);
    }

    /// Updates a provider's tri-state checkbox from its model checkboxes
    /// (none → unchecked, all → checked, some → indeterminate).
    private static void updateProviderBox(CheckBox providerBox, List<? extends CheckBox> modelBoxes) {
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
        addServer.setTitle("添加 MCP 服务器");
        addServer.setLeading(SVG.ADD, 20);
        addServer.setOnAction(e -> addMcpServer());
        list.getContent().add(addServer);

        root.getChildren().addAll(ComponentList.createComponentListTitle("MCP服务器"), list);
        return wrapScroll(root);
    }

    private void addMcpServer() {
        AiMcpServerConfig config = new AiMcpServerConfig();
        mcpServers.add(config);
        editMcpServer(config);
    }

    private void editMcpServer(AiMcpServerConfig server) {
        PromptDialogPane.Builder builder = new PromptDialogPane.Builder("编辑 MCP 服务器", (questions, handler) -> {
            server.setDisplayName((String) questions.get(0).getValue());
            Integer transportIdx = (Integer) questions.get(1).getValue();
            server.setTransport(transportIdx != null && transportIdx == 1 ? "http" : "stdio");
            server.setCommand((String) questions.get(2).getValue());
            server.setUrl((String) questions.get(3).getValue());
            server.setEnabled((Boolean) questions.get(4).getValue());
            saveMcpServers();
            handler.resolve();
        });
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion("名称", server.getDisplayName()));
        builder.addQuestion(new PromptDialogPane.Builder.CandidatesQuestion("传输", "stdio", "http"));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion("命令（stdio）", server.getCommand() == null ? "" : server.getCommand()));
        builder.addQuestion(new PromptDialogPane.Builder.StringQuestion("URL（http）", server.getUrl() == null ? "" : server.getUrl()));
        builder.addQuestion(new PromptDialogPane.Builder.BooleanQuestion("启用", server.isEnabled()));
        Controllers.prompt(builder);
    }

    private Node buildSkillsTab() {
        VBox root = createSettingsRoot();

        // ① 工具权限（最常配置，置顶）
        ComponentList permissionCore = new ComponentList();
        permissionCore.getContent().add(buildGlobalApprovalModeRow());
        permissionCore.getContent().add(buildDangerousConfirmationRow());

        // ② 技能
        ComponentList skillList = new ComponentList();
        for (SkillManifest skill : skillRegistry.list()) {
            LineButton row = new LineButton();
            row.setTitle(skill.getName() != null ? skill.getName() : "(invalid skill)");
            row.setSubtitle(skill.getDescription() != null ? skill.getDescription() : String.join("; ", skill.getErrors()));
            row.setTrailingIcon(skill.getName() != null && skillRegistry.isDisabled(skill.getName()) ? SVG.CHECK : SVG.CHECK_CIRCLE);
            row.setOnAction(e -> {
                if (skill.getName() == null || !skill.isValid()) return;
                if (skillRegistry.isDisabled(skill.getName())) skillRegistry.enable(skill.getName());
                else skillRegistry.disable(skill.getName());
                tab.select(skillsTab, false);
            });
            skillList.getContent().add(row);
        }
        if (skillRegistry.list().isEmpty()) {
            Label empty = new Label("技能目录为空。请在 .hmcl/ai-skills/<skill>/SKILL.md 下放置技能。");
            empty.setWrapText(true);
            empty.getStyleClass().add("subtitle-label");
            skillList.getContent().add(empty);
        }
        // 重新扫描作为次要动作，放在技能列表末尾
        LineButton reload = new LineButton();
        reload.setTitle("重新扫描技能目录");
        reload.setSubtitle(SKILLS_DIR.toString());
        reload.setLeading(SVG.REFRESH, 20);
        reload.setOnAction(e -> {
            refreshSkills();
            tab.select(skillsTab, false);
        });
        skillList.getContent().add(reload);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle("工具权限"),
                permissionCore,
                ComponentList.createComponentListTitle("技能"),
                skillList
        );

        // ③ 工具权限 — 按来源分类展示，使内置工具清晰可见（不再埋进单个折叠项）。
        List<AiToolCatalog.Descriptor> descriptors = buildToolDescriptors();
        ToolSource[] categoryOrder = {
                ToolSource.LOCAL, ToolSource.FILESYSTEM, ToolSource.SEARCH, ToolSource.SKILL, ToolSource.MCP
        };
        for (ToolSource source : categoryOrder) {
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
                        ComponentList.createComponentListTitle(sourceDisplayName(source) + "工具"),
                        group);
            }
        }
        return wrapScroll(root);
    }

    private LineSelectButton<AiApprovalMode> buildGlobalApprovalModeRow() {
        LineSelectButton<AiApprovalMode> approvalMode = new LineSelectButton<>();
        approvalMode.setTitle(i18n("ai.settings.approval_mode"));
        approvalMode.setSubtitle("作为所有工具的默认权限；单个工具可覆盖为 safe / ask / yolo");
        approvalMode.setItems(List.of(AiApprovalMode.SAFE, AiApprovalMode.ASK, AiApprovalMode.YOLO));
        approvalMode.setNullSafeConverter(mode -> switch (mode) {
            case SAFE -> i18n("ai.settings.approval_mode_safe");
            case ASK -> i18n("ai.settings.approval_mode_ask");
            case YOLO -> i18n("ai.settings.approval_mode_yolo");
        });
        approvalMode.setValue(aiSettings.getApprovalModeEnum());
        approvalMode.valueProperty().addListener((obs, old, mode) -> {
            if (mode != null) {
                aiSettings.approvalModeProperty().set(mode.getId());
                saveAiSettings();
                tab.select(skillsTab, false);
            }
        });
        return approvalMode;
    }

    private LineToggleButton buildDangerousConfirmationRow() {
        LineToggleButton confirmation = new LineToggleButton();
        confirmation.setTitle("危险操作二次确认");
        confirmation.setSubtitle("即使工具被设为 yolo，危险写入仍保留额外安全闸门");
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
                AiToolPermissionStore.OverrideMode.SAFE,
                AiToolPermissionStore.OverrideMode.ASK,
                AiToolPermissionStore.OverrideMode.YOLO
        ));
        row.setNullSafeConverter(mode -> switch (mode) {
            case FOLLOW_GLOBAL -> "跟随全局（" + aiSettings.getApprovalModeEnum().getDisplayName() + "）";
            case SAFE -> "Safe";
            case ASK -> "Ask";
            case YOLO -> "YOLO";
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

    private Node buildSearchTab() {
        VBox root = createSettingsRoot();

        // ---- 搜索服务（核心 / 必填项排在最前）----
        ComponentList core = new ComponentList();

        LineToggleButton enabled = new LineToggleButton();
        enabled.setTitle("启用联网搜索");
        enabled.setSubtitle("将联网搜索暴露为独立 Search Tool");
        enabled.setSelected(searchConfig.isEnabled());
        enabled.selectedProperty().addListener((obs, old, val) -> {
            searchConfig.setEnabled(val);
            saveSearchConfig();
        });
        core.getContent().add(enabled);

        LineSelectButton<SearchProvider> provider = new LineSelectButton<>();
        provider.setTitle("搜索服务商");
        provider.setSubtitle("选择搜索服务提供商");
        provider.setItems(List.of(SearchProvider.TAVILY, SearchProvider.SEARXNG, SearchProvider.EXA, SearchProvider.BOCHA, SearchProvider.ZHIPU, SearchProvider.LOCAL));
        provider.setNullSafeConverter(SearchProvider::getDisplayName);
        SearchProvider current = SearchProvider.fromId(searchConfig.getProvider().toUpperCase());
        provider.setValue(current != null ? current : SearchProvider.TAVILY);
        core.getContent().add(provider);

        LineButton apiKey = new LineButton();
        apiKey.setTitle("API Key");
        apiKey.setSubtitle(searchConfig.getApiKey().isEmpty() ? "未设置（多数服务商必填）" : "已设置");
        apiKey.setTrailingIcon(SVG.EDIT);
        apiKey.setOnAction(e -> Controllers.prompt("搜索 API Key", (result, handler) -> {
            searchConfig.setApiKey(result.trim());
            saveSearchConfig();
            apiKey.setSubtitle(searchConfig.getApiKey().isEmpty() ? "未设置（多数服务商必填）" : "已设置");
            handler.resolve();
        }, searchConfig.getApiKey()));
        core.getContent().add(apiKey);

        // 切换服务商时在内部同步默认 endpoint（端点不再作为独立设置项暴露）
        provider.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                searchConfig.setProvider(val.name().toLowerCase());
                if (!val.getDefaultEndpoint().isEmpty()) searchConfig.setEndpoint(val.getDefaultEndpoint());
                saveSearchConfig();
            }
        });

        // ---- 搜索选项 ----
        ComponentList options = new ComponentList();

        // 结果个数：复用原生 MD3 风格的 JFXSlider，放入原生行的 trailing 槽，行高与列表一致
        LineButton countRow = new LineButton();
        countRow.setTitle("搜索结果个数");
        countRow.setSubtitle("默认返回结果数量");
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
        test.setTitle("测试搜索");
        test.setSubtitle("用当前服务商与 API Key 发起一次测试查询");
        test.setTrailingIcon(SVG.SEARCH);
        test.setOnAction(e -> Controllers.prompt("测试搜索关键词", (query, handler) -> {
            // Run the search off the FX thread so the dialog/UI does not freeze while
            // waiting for the network response; report the outcome back on the FX thread.
            String testProvider = searchConfig.getProvider();
            String testEndpoint = searchConfig.getEndpoint();
            String testApiKey = searchConfig.getApiKey();
            int testMaxResults = searchConfig.getMaxResults();
            Thread worker = new Thread(() -> {
                try {
                    SearchResponse response = switch (testProvider) {
                        case "searxng" -> new SearxngSearchClient(testEndpoint, testApiKey).search(query, testMaxResults);
                        default -> new TavilySearchClient(testEndpoint, testApiKey).search(query, testMaxResults);
                    };
                    Platform.runLater(() -> {
                        Controllers.showToast("测试搜索成功：" + response.results().size() + " 条结果");
                        handler.resolve();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> handler.reject("搜索失败：" + ex.getMessage()));
                }
            }, "ai-search-test");
            worker.setDaemon(true);
            worker.start();
        }, "Minecraft crash report"));
        options.getContent().add(test);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle("搜索服务"), core,
                ComponentList.createComponentListTitle("搜索选项"), options);
        return wrapScroll(root);
    }

    private Node buildDataTab() {
        VBox root = createSettingsRoot();
        root.getChildren().addAll(
                ComponentList.createComponentListTitle("数据备份与恢复"), buildBackupSettingsList(),
                ComponentList.createComponentListTitle("数据目录"), buildDataSettingsList(),
                ComponentList.createComponentListTitle("清理"), buildCleanupSettingsList()
        );
        return wrapScroll(root);
    }

    private Node buildBackupSettingsList() {
        ComponentList list = new ComponentList();

        LineButton backup = new LineButton();
        backup.setTitle("备份");
        backup.setSubtitle("备份聊天记录、设置、技能与搜索/MCP配置（开发中）");
        backup.setTrailingIcon(SVG.FOLDER_OPEN);
        backup.setOnAction(e -> Controllers.showToast("数据备份入口正在开发中。"));
        list.getContent().add(backup);

        LineButton restore = new LineButton();
        restore.setTitle("恢复");
        restore.setSubtitle("从备份文件恢复 HMCL-AE 数据（开发中）");
        restore.setTrailingIcon(SVG.FOLDER_OPEN);
        restore.setOnAction(e -> Controllers.showToast("数据恢复入口正在开发中。"));
        list.getContent().add(restore);

        LineToggleButton slimBackup = new LineToggleButton();
        slimBackup.setTitle("精简备份");
        slimBackup.setSubtitle("备份时跳过图片、知识库等大文件，仅保留聊天记录和设置");
        slimBackup.setSelected(true);
        list.getContent().add(slimBackup);

        LineButton fileExport = new LineButton();
        fileExport.setTitle("导出为文件");
        fileExport.setSubtitle("导出到本地文件（开发中）");
        fileExport.setTrailingIcon(SVG.FOLDER_OPEN);
        fileExport.setOnAction(e -> Controllers.showToast("导出文件入口正在开发中。"));
        list.getContent().add(fileExport);

        return list;
    }

    private Node buildDataSettingsList() {
        ComponentList list = new ComponentList();

        LineButton appDataDir = new LineButton();
        appDataDir.setTitle("应用数据");
        appDataDir.setSubtitle(SettingsManager.localConfigDirectory().toString());
        appDataDir.setTrailingIcon(SVG.FOLDER_OPEN);
        appDataDir.setOnAction(e -> FXUtils.openFolder(SettingsManager.localConfigDirectory()));
        list.getContent().add(appDataDir);

        LineButton logDir = new LineButton();
        logDir.setTitle("应用日志");
        logDir.setSubtitle(SettingsManager.localConfigDirectory().resolve("logs").toString());
        logDir.setTrailingIcon(SVG.FOLDER_OPEN);
        logDir.setOnAction(e -> FXUtils.openFolder(SettingsManager.localConfigDirectory().resolve("logs")));
        list.getContent().add(logDir);

        LineButton skillsDir = new LineButton();
        skillsDir.setTitle("知识库 / 技能目录");
        skillsDir.setSubtitle(SKILLS_DIR.toString());
        skillsDir.setTrailingIcon(SVG.FOLDER_OPEN);
        skillsDir.setOnAction(e -> FXUtils.openFolder(SKILLS_DIR));
        list.getContent().add(skillsDir);

        return list;
    }

    private Node buildCleanupSettingsList() {
        ComponentList list = new ComponentList();

        LineButton knowledgeFiles = new LineButton();
        knowledgeFiles.setTitle("知识库文件");
        knowledgeFiles.setSubtitle("查看或删除技能/知识库文件（开发中）");
        knowledgeFiles.setTrailingIcon(SVG.DELETE);
        knowledgeFiles.setOnAction(e -> Controllers.showToast("知识库文件管理正在开发中。"));
        list.getContent().add(knowledgeFiles);

        LineButton clearCache = new LineButton();
        clearCache.setTitle("清除缓存");
        clearCache.setSubtitle("清除模型列表缓存、搜索缓存以及自定义请求头等临时数据（开发中）");
        clearCache.setTrailingIcon(SVG.DELETE);
        clearCache.setOnAction(e -> Controllers.showToast("缓存清理入口已预留，后续接入真实统计与清理逻辑。"));
        list.getContent().add(clearCache);

        return list;
    }

    private Node buildGeneralTab() {
        VBox root = createSettingsRoot();
        ComponentList list = new ComponentList();

        LineToggleButton titleNaming = new LineToggleButton();
        titleNaming.setTitle(i18n("ai.settings.title_naming"));
        titleNaming.setSubtitle(i18n("ai.settings.title_naming.desc"));
        titleNaming.selectedProperty().bindBidirectional(aiSettings.titleNamingEnabledProperty());
        list.getContent().add(titleNaming);

        LineSelectButton<String> titleNamingModel = new LineSelectButton<>();
        titleNamingModel.setTitle(i18n("ai.settings.title_naming_model"));
        titleNamingModel.setSubtitle("为空时使用当前默认模型");
        List<String> modelChoices = buildModelChoices(true);
        titleNamingModel.setItems(modelChoices);
        titleNamingModel.setNullSafeConverter(value -> value.isEmpty() ? "Auto" : value);
        titleNamingModel.setValue(aiSettings.getTitleNamingModelId().isEmpty() ? "" : aiSettings.getTitleNamingModelId());
        titleNamingModel.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                aiSettings.titleNamingModelIdProperty().set(value);
                saveAiSettings();
            }
        });
        list.getContent().add(titleNamingModel);

        LineToggleButton crashAnalysis = new LineToggleButton();
        crashAnalysis.setTitle(i18n("ai.settings.auto_crash_analysis"));
        crashAnalysis.setSubtitle(i18n("ai.settings.auto_crash_analysis.desc"));
        crashAnalysis.selectedProperty().bindBidirectional(aiSettings.autoCrashAnalysisEnabledProperty());
        list.getContent().add(crashAnalysis);

        root.getChildren().addAll(ComponentList.createComponentListTitle(i18n("ai.settings.global")), list);
        return wrapScroll(root);
    }

    private Node buildMemoryTab() {
        // Mirror the 帮助/关于 structure exactly (large-title head + native LineButton rows)
        // so the card style and row heights match native HMCL.
        VBox root = createSettingsRoot();

        ComponentList intro = new ComponentList();
        LineButton head = new LineButton();
        head.setLargeTitle(true);
        head.setLeading(SVG.PACKAGE, 32);
        head.setTitle("全局记忆");
        head.setSubtitle("正在开发中 · 当前不提供持久化编辑入口。");
        intro.getContent().add(head);

        ComponentList notes = new ComponentList();
        LineButton shortTerm = new LineButton();
        shortTerm.setTitle("短期上下文");
        shortTerm.setSubtitle("现阶段只有会话级短期上下文裁剪。");
        LineButton planned = new LineButton();
        planned.setTitle("规划中");
        planned.setSubtitle("后续会加入摘要记忆、用户偏好记忆和可删除的长期记忆。");
        LineButton privacy = new LineButton();
        privacy.setTitle("隐私");
        privacy.setSubtitle("开发完成前不会静默保存敏感文件内容。");
        notes.getContent().setAll(shortTerm, planned, privacy);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle("全局记忆"), intro,
                ComponentList.createComponentListTitle("说明"), notes);
        return wrapScroll(root);
    }

    private Node buildHelpTab() {
        VBox root = createSettingsRoot();

        ComponentList intro = new ComponentList();
        LineButton head = new LineButton();
        head.setLargeTitle(true);
        head.setLeading(SVG.FEEDBACK, 32);
        head.setTitle("AI 助手帮助");
        head.setSubtitle("为 HMCL 集成 AI Agent：对话、日志/崩溃分析、模型服务、工具链、MCP、Skills 与搜索。");
        intro.getContent().add(head);

        ComponentList notes = new ComponentList();
        LineButton visual = new LineButton();
        visual.setTitle("视觉原则");
        visual.setSubtitle("外部客户端只作功能参考，视觉一律通过 HMCL 原生组件表达。");
        LineButton safety = new LineButton();
        safety.setTitle("安全策略");
        safety.setSubtitle("文件系统与危险工具遵循保守安全策略。");
        notes.getContent().setAll(visual, safety);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle("帮助"),
                intro,
                ComponentList.createComponentListTitle("说明"),
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
        title.setSubtitle("Agent Experience · 面向 HMCL 的 AI 助手增强");
        about.getContent().add(title);

        ComponentList info = new ComponentList();
        LineButton impl = new LineButton();
        impl.setTitle("原生实现");
        impl.setSubtitle("基于 HMCL JavaFX 原生 UI 构建。");
        info.getContent().setAll(impl);

        ComponentList legal = new ComponentList();
        LineButton license = new LineButton();
        license.setTitle("版权");
        license.setSubtitle("HMCL 版权所有 © huangyuhui 及贡献者，基于 GPLv3 分发。");
        LineButton openSource = LineButton.createExternalLinkButton("https://github.com/HMCL-dev/HMCL");
        openSource.setTitle("开源仓库");
        openSource.setSubtitle("github.com/HMCL-dev/HMCL");
        legal.getContent().setAll(license, openSource);

        root.getChildren().addAll(
                ComponentList.createComponentListTitle("关于"),
                about,
                ComponentList.createComponentListTitle("实现"),
                info,
                ComponentList.createComponentListTitle("法律"),
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

    private List<String> buildModelChoices(boolean includeAuto) {
        List<String> choices = new ArrayList<>();
        if (includeAuto) choices.add("");
        for (AiProviderProfile profile : aiSettings.getProfiles()) {
            if (!profile.isEnabled()) continue;
            String defaultModel = profile.getDefaultModelId();
            if (defaultModel != null && !defaultModel.isBlank()) {
                choices.add(profile.getDisplayName() + " / " + defaultModel);
            }
            for (String model : profile.getCachedModels()) {
                String display = profile.getDisplayName() + " / " + profile.getModelAliasOrId(model);
                if (!choices.contains(display)) choices.add(display);
            }
        }
        return choices;
    }

    private void loadMcpServers() {
        mcpServers.clear();
        try {
            if (!Files.exists(MCP_CONFIG_FILE)) return;
            String json = Files.readString(MCP_CONFIG_FILE, StandardCharsets.UTF_8);
            List<AiMcpServerConfig> loaded = GSON.fromJson(json, new TypeToken<List<AiMcpServerConfig>>(){}.getType());
            if (loaded != null) mcpServers.addAll(loaded);
        } catch (Exception ignored) {
        }
    }

    private void saveMcpServers() {
        try {
            Files.writeString(MCP_CONFIG_FILE, GSON.toJson(mcpServers), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void loadSearchConfig() {
        try {
            if (!Files.exists(SEARCH_CONFIG_FILE)) return;
            String json = Files.readString(SEARCH_CONFIG_FILE, StandardCharsets.UTF_8);
            AiSearchConfig loaded = GSON.fromJson(json, AiSearchConfig.class);
            if (loaded != null) searchConfig = loaded;
        } catch (Exception ignored) {
        }
    }

    private void saveSearchConfig() {
        try {
            Files.writeString(SEARCH_CONFIG_FILE, GSON.toJson(searchConfig), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void loadToolPermissions() {
        try {
            toolPermissionStore.load();
        } catch (Exception ignored) {
        }
    }

    private void saveToolPermissions() {
        try {
            toolPermissionStore.save();
        } catch (IOException ignored) {
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
        return AiToolCatalog.descriptorsForRegistry(mcpToolRegistry);
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
            case LOCAL -> "本地";
            case FILESYSTEM -> "文件系统";
            case MCP -> "MCP";
            case SEARCH -> "搜索";
            case SKILL -> "技能";
        };
    }

    private static String permissionDisplayName(ToolPermission permission) {
        return switch (permission) {
            case READ_ONLY -> "只读";
            case CONTROLLED_WRITE -> "受控写入";
            case DANGEROUS_WRITE -> "危险写入";
            case EXTERNAL_NETWORK -> "外部网络";
        };
    }

    private static String capabilityStatusDisplayName(AiToolCatalog.CapabilityStatus status) {
        return switch (status) {
            case AVAILABLE -> "可用";
            case REQUIRES_CONTEXT -> "需要上下文";
            case PLANNED -> "计划中";
        };
    }

    private final class ProviderChoice extends RadioChoiceList.Choice<AiProviderProfile> {
        private ProviderChoice(AiProviderProfile profile) {
            super(displayProfileName(profile), profile);
            setSubtitle((profile.isEnabled() ? "启用" : "禁用") + " · " + profile.getProtocolFamily()
                    + " · 模型：" + Objects.toString(profile.getEffectiveModelId(), "手动填写")
                    + endpointSuffix(profile));
        }

        @Override
        protected Node createRightNode() {
            JFXButton editButton = FXUtils.newToggleButton4(SVG.EDIT, 14);
            editButton.setOnAction(event -> {
                editProfile(getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(editButton, "编辑配置");

            JFXButton removeButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER, 14);
            removeButton.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> aiSettings.getProfiles().size() <= 1,
                    providerChoices.selectedValueProperty()));
            removeButton.setOnAction(event -> {
                removeProfile(getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(removeButton, "删除");

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

    /// A connectivity-test row tracked in the test dialog.
    private static final class TestRow {
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
            FXUtils.installFastTooltip(editButton, "编辑模型");

            JFXButton removeButton = FXUtils.newToggleButton4(SVG.DELETE_FOREVER, 14);
            removeButton.setOnAction(event -> {
                removeModelEntry(profile, getValue());
                event.consume();
            });
            FXUtils.installFastTooltip(removeButton, "删除");

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
