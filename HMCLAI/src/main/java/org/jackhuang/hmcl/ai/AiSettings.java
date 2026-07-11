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
package org.jackhuang.hmcl.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import javafx.beans.property.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/// Owns the AI configuration settings and handles persistence to a dedicated JSON file
/// under the `.hmcl/` config directory. The API key is stored with Base64 encoding
/// on disk but kept as plain text in memory for direct use by the AI client.
///
/// ## Multi-profile support
///
/// As of the provider-profile refactor, settings maintain a list of
/// {@link AiProviderProfile} instances and a selected profile id. When old
/// single-provider settings are encountered on disk, they are automatically
/// migrated into a single initial provider profile.
///
/// ## Backward compatibility
///
/// The existing JavaFX properties (endpoint, apiKey, model, provider, etc.)
/// remain available for UI binding. On load they are populated from the
/// currently selected profile; on save they are written back to that
/// profile before persistence.
///
/// The settings file path is `{configDir}/ai-settings.json`.
@NotNullByDefault
public final class AiSettings {

    /// The file name used for persisting AI settings.
    public static final String FILE_NAME = "ai-settings.json";

    /// JSON DTO used for serialization.
    ///
    /// Old single-provider fields are kept as nullable boxed types so they
    /// can be omitted (serialized as `null`) after migration to the
    /// multi-profile format.
    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class PersistedData {
        @SerializedName("endpoint")
        @Nullable
        private String endpoint = LlmConfig.DEFAULT_ENDPOINT;

        @SerializedName("apiKey")
        @Nullable
        private String apiKey = "";

        @SerializedName("model")
        @Nullable
        private String model = LlmConfig.DEFAULT_MODEL;

        @SerializedName("provider")
        @Nullable
        private String provider = LlmConfig.DEFAULT_PROVIDER;

        @SerializedName("maxTokens")
        private int maxTokens = LlmConfig.DEFAULT_MAX_TOKENS;

        @SerializedName("temperature")
        private double temperature = LlmConfig.DEFAULT_TEMPERATURE;

        @SerializedName("contextWindow")
        private int contextWindow = LlmConfig.DEFAULT_CONTEXT_WINDOW;

        @SerializedName("maxOutputTokens")
        private int maxOutputTokens = LlmConfig.DEFAULT_MAX_TOKENS;

        @SerializedName("topP")
        private double topP = LlmConfig.DEFAULT_TOP_P;

        @SerializedName("presencePenalty")
        private double presencePenalty = LlmConfig.DEFAULT_PRESENCE_PENALTY;

        @SerializedName("frequencyPenalty")
        private double frequencyPenalty = LlmConfig.DEFAULT_FREQUENCY_PENALTY;

        @SerializedName("seed")
        @Nullable
        private Long seed = null;

        @SerializedName("reasoningEffort")
        @Nullable
        private String reasoningEffort = null;

        @SerializedName("stream")
        private boolean stream = LlmConfig.DEFAULT_STREAM;

        @SerializedName("stopSequences")
        @Nullable
        private List<String> stopSequences = null;

        @SerializedName("profiles")
        @Nullable
        private List<AiProviderProfile> profiles = null;

        @SerializedName("selectedProfileId")
        @Nullable
        private String selectedProfileId = null;

        @SerializedName("autoLogAnalysisEnabled")
        private boolean autoLogAnalysisEnabled = DEFAULT_AUTO_LOG_ANALYSIS_ENABLED;

        @SerializedName("autoCrashAnalysisEnabled")
        private boolean autoCrashAnalysisEnabled = DEFAULT_AUTO_CRASH_ANALYSIS_ENABLED;

        @SerializedName("toolCallDisplayEnabled")
        private boolean toolCallDisplayEnabled = DEFAULT_TOOL_CALL_DISPLAY_ENABLED;

        @SerializedName("approvalMode")
        private String approvalMode = DEFAULT_APPROVAL_MODE;

        @SerializedName("dangerousActionConfirmationEnabled")
        private boolean dangerousActionConfirmationEnabled = DEFAULT_DANGEROUS_ACTION_CONFIRMATION_ENABLED;

        // ---- Extended global options (agent behaviour / safety / UI) ----

        @SerializedName("maxToolCycles")
        private int maxToolCycles = DEFAULT_MAX_TOOL_CYCLES;

        @SerializedName("maxContextMessages")
        private int maxContextMessages = DEFAULT_MAX_CONTEXT_MESSAGES;

        @SerializedName("toolResultMaxChars")
        private int toolResultMaxChars = DEFAULT_TOOL_RESULT_MAX_CHARS;

        @SerializedName("requestTimeoutSeconds")
        private int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;

        @SerializedName("shellToolEnabled")
        private boolean shellToolEnabled = DEFAULT_SHELL_TOOL_ENABLED;

        @SerializedName("webAccessEnabled")
        private boolean webAccessEnabled = DEFAULT_WEB_ACCESS_ENABLED;

        @SerializedName("autoScrollEnabled")
        private boolean autoScrollEnabled = DEFAULT_AUTO_SCROLL_ENABLED;

        @SerializedName("sendOnEnter")
        private boolean sendOnEnter = DEFAULT_SEND_ON_ENTER;

        @SerializedName("criticalConfirmEnabled")
        private boolean criticalConfirmEnabled = DEFAULT_CRITICAL_CONFIRM_ENABLED;

        @SerializedName("memoryEnabled")
        private boolean memoryEnabled = DEFAULT_MEMORY_ENABLED;

        @SerializedName("dangerouslySkipPermissions")
        private boolean dangerouslySkipPermissions = DEFAULT_DANGEROUSLY_SKIP_PERMISSIONS;

        @SerializedName("nbtToolsEnabled")
        private boolean nbtToolsEnabled = DEFAULT_NBT_TOOLS_ENABLED;

        @SerializedName("autoTitleEnabled")
        private boolean autoTitleEnabled = DEFAULT_AUTO_TITLE_ENABLED;

        /// `"<profileId>::<modelId>"` (or a bare model id); `null`/blank = Auto
        /// (follow the current chat model). See [AiSettings#resolveTitleNamingModel()].
        @SerializedName("titleNamingModel")
        @Nullable
        private String titleNamingModel = null;

        @SerializedName("deleteToRecycleBin")
        private boolean deleteToRecycleBin = DEFAULT_DELETE_TO_RECYCLE_BIN;

        @SerializedName("aiRiskNoticeAccepted")
        private boolean aiRiskNoticeAccepted = DEFAULT_AI_RISK_NOTICE_ACCEPTED;

        @SerializedName("customInstructions")
        @Nullable
        private String customInstructions = "";

        @SerializedName("responseLanguage")
        private String responseLanguage = DEFAULT_RESPONSE_LANGUAGE;

        @SerializedName("autoRecallMemory")
        private boolean autoRecallMemory = DEFAULT_AUTO_RECALL_MEMORY;

        @SerializedName("autoSkillInjection")
        private boolean autoSkillInjection = DEFAULT_AUTO_SKILL_INJECTION;

        @SerializedName("traceEnabled")
        private boolean traceEnabled = DEFAULT_TRACE_ENABLED;

        @SerializedName("worldBackupMaxMb")
        private int worldBackupMaxMb = DEFAULT_WORLD_BACKUP_MAX_MB;

        @SerializedName("autoCompactEnabled")
        private boolean autoCompactEnabled = DEFAULT_AUTO_COMPACT_ENABLED;
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path filePath;

    // Core properties
    private final StringProperty endpoint;
    private final StringProperty apiKey;
    private final StringProperty model;
    private final StringProperty provider;
    private final IntegerProperty maxTokens;
    private final DoubleProperty temperature;

    // Advanced properties
    private final IntegerProperty contextWindow;
    private final IntegerProperty maxOutputTokens;
    private final DoubleProperty topP;
    private final DoubleProperty presencePenalty;
    private final DoubleProperty frequencyPenalty;
    private final ObjectProperty<@Nullable Long> seed;
    private final StringProperty reasoningEffort;
    private final BooleanProperty stream;
    private final BooleanProperty autoLogAnalysisEnabled;
    private final BooleanProperty autoCrashAnalysisEnabled;
    private final BooleanProperty toolCallDisplayEnabled;
    private final StringProperty approvalMode;
    private final BooleanProperty dangerousActionConfirmationEnabled;

    // Extended global options
    private final IntegerProperty maxToolCycles;
    private final IntegerProperty maxContextMessages;
    private final IntegerProperty toolResultMaxChars;
    private final IntegerProperty requestTimeoutSeconds;
    private final BooleanProperty shellToolEnabled;
    private final BooleanProperty webAccessEnabled;
    private final BooleanProperty autoScrollEnabled;
    private final BooleanProperty sendOnEnter;
    private final BooleanProperty criticalConfirmEnabled;
    private final BooleanProperty memoryEnabled;
    private final BooleanProperty dangerouslySkipPermissions;
    private final BooleanProperty nbtToolsEnabled;
    private final BooleanProperty autoTitleEnabled;
    private final StringProperty titleNamingModel;
    private final BooleanProperty deleteToRecycleBin;
    private final BooleanProperty aiRiskNoticeAccepted;
    private final StringProperty customInstructions;
    private final StringProperty responseLanguage;
    private final BooleanProperty autoRecallMemory;
    private final BooleanProperty autoSkillInjection;
    private final BooleanProperty traceEnabled;

    // World backup engine
    private final IntegerProperty worldBackupMaxMb;

    // Context management
    private final BooleanProperty autoCompactEnabled;

    // Complex values (list-based, no JavaFX property)
    private volatile List<String> stopSequences = Collections.emptyList();

    // Multi-profile state
    private final List<AiProviderProfile> profiles = new ArrayList<>();
    @Nullable
    private String selectedProfileId = null;

    /// Default value for the auto log-analysis enabled flag.
    static final boolean DEFAULT_AUTO_LOG_ANALYSIS_ENABLED = true;

    /// Default value for the auto crash-analysis enabled flag.
    static final boolean DEFAULT_AUTO_CRASH_ANALYSIS_ENABLED = true;

    /// Default value for the tool-call display enabled flag.
    static final boolean DEFAULT_TOOL_CALL_DISPLAY_ENABLED = true;

    /// Default approval mode id (`"auto"` — see {@link AiApprovalMode}'s doc for the SAFE/ASK/YOLO
    /// merge this replaced; old persisted values of `"safe"`/`"ask"`/`"yolo"` still load fine via
    /// {@link AiApprovalMode#fromId(String)}).
    static final String DEFAULT_APPROVAL_MODE = "auto";

    /// Default value for the dangerous-action confirmation enabled flag.
    static final boolean DEFAULT_DANGEROUS_ACTION_CONFIRMATION_ENABLED = true;

    /// Default maximum tool-call cycles per turn (runaway backstop).
    public static final int DEFAULT_MAX_TOOL_CYCLES = 25;

    /// Default maximum number of recent conversation messages sent to the model
    /// (`0` = unlimited; the leading system message is always kept).
    public static final int DEFAULT_MAX_CONTEXT_MESSAGES = 0;

    /// Default maximum characters of a single tool result fed back to the model.
    /// `0` means unlimited (no truncation — the user's explicit opt-out); the default caps a
    /// single result at 20,000 characters so one huge read can't crowd the context window.
    public static final int DEFAULT_TOOL_RESULT_MAX_CHARS = 20_000;

    /// Default per-request timeout in seconds.
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;

    /// Default value for the shell-tool enabled flag. Off by default: every routine operation
    /// (mod toggle, memory/Java, world backups, accounts, …) now has a dedicated tool that's
    /// safer and more reliable than a hand-rolled shell command — shell is left for genuine edge
    /// cases the user can opt into.
    public static final boolean DEFAULT_SHELL_TOOL_ENABLED = false;

    /// Default value for the web-access (search/fetch) tools enabled flag.
    public static final boolean DEFAULT_WEB_ACCESS_ENABLED = true;

    /// Default value for the auto-scroll-to-bottom flag.
    public static final boolean DEFAULT_AUTO_SCROLL_ENABLED = true;

    /// Default value for the send-on-Enter flag (Enter sends; off → Ctrl+Enter sends).
    public static final boolean DEFAULT_SEND_ON_ENTER = true;

    /// Default for the red second-tier critical confirmation (on).
    public static final boolean DEFAULT_CRITICAL_CONFIRM_ENABLED = true;

    /// Default for the global memory feature (remember/recall): OFF by default; the user opts in.
    public static final boolean DEFAULT_MEMORY_ENABLED = false;

    /// Default for the developer-only bypass that skips ALL permission confirmations
    /// (dangerous + red critical). Off by default; only the 开发者选项 toggle turns it on.
    public static final boolean DEFAULT_DANGEROUSLY_SKIP_PERMISSIONS = false;

    /// Default for the (high-risk) save-NBT editing tool suite being enabled.
    /// Default OFF, like global memory — NBT editing is high-risk (direct save/player-data
    /// writes) and niche enough that it should be an opt-in the user consciously reaches for
    /// in AI 设置, not something on by default.
    public static final boolean DEFAULT_NBT_TOOLS_ENABLED = false;

    /// Default for auto-generating a short conversation title from the opening exchange.
    public static final boolean DEFAULT_AUTO_TITLE_ENABLED = true;

    /// Default for routing AI deletions (worlds etc.) to the OS recycle bin instead of permanent delete.
    public static final boolean DEFAULT_DELETE_TO_RECYCLE_BIN = true;

    /// Whether the user has acknowledged the one-time AI test-phase risk notice (shown on first use).
    public static final boolean DEFAULT_AI_RISK_NOTICE_ACCEPTED = false;

    /// Default reply-language mode (`"auto"` = follow the user's language).
    /// Other accepted values: `"zh"` (always 简体中文), `"en"` (always English).
    public static final String DEFAULT_RESPONSE_LANGUAGE = "auto";

    /// Default value for the auto-recall-memory flag (off by default).
    public static final boolean DEFAULT_AUTO_RECALL_MEMORY = false;

    /// Default value for automatic skill playbook injection (on by default — this is the
    /// weak-model path: trigger-matched SKILL.md bodies are inlined into the prompt so the
    /// model does not have to decide to read them first).
    public static final boolean DEFAULT_AUTO_SKILL_INJECTION = true;

    /// Whether the complete per-session agent trace is recorded (default on; the truth record used
    /// for post-mortem debugging and one-tap diagnostic upload).
    public static final boolean DEFAULT_TRACE_ENABLED = true;

    /// Default per-world cap (in megabytes) for the total size of retained world-backup
    /// snapshots. Oldest snapshots are pruned once the total exceeds the cap; the newest
    /// snapshot is always kept even when it alone exceeds it.
    public static final int DEFAULT_WORLD_BACKUP_MAX_MB = 10;

    /// Default for auto-compacting the conversation as it nears the active model's context
    /// window, so long tool-heavy chats never hard-overflow (on by default; the user can opt out).
    public static final boolean DEFAULT_AUTO_COMPACT_ENABLED = true;

    /// Creates an instance bound to the given config directory.
    ///
    /// @param configDir the `.hmcl/` directory path; the settings file will be
    ///                  written as `{configDir}/ai-settings.json`
    public AiSettings(Path configDir) {
        this.filePath = configDir.resolve(FILE_NAME);

        this.endpoint = new SimpleStringProperty(this, "endpoint", LlmConfig.DEFAULT_ENDPOINT);
        this.apiKey = new SimpleStringProperty(this, "apiKey", LlmConfig.DEFAULT_API_KEY);
        this.model = new SimpleStringProperty(this, "model", LlmConfig.DEFAULT_MODEL);
        this.provider = new SimpleStringProperty(this, "provider", LlmConfig.DEFAULT_PROVIDER);
        this.maxTokens = new SimpleIntegerProperty(this, "maxTokens", LlmConfig.DEFAULT_MAX_TOKENS);
        this.temperature = new SimpleDoubleProperty(this, "temperature", LlmConfig.DEFAULT_TEMPERATURE);
        this.contextWindow = new SimpleIntegerProperty(this, "contextWindow", LlmConfig.DEFAULT_CONTEXT_WINDOW);
        this.maxOutputTokens = new SimpleIntegerProperty(this, "maxOutputTokens", LlmConfig.DEFAULT_MAX_TOKENS);
        this.topP = new SimpleDoubleProperty(this, "topP", LlmConfig.DEFAULT_TOP_P);
        this.presencePenalty = new SimpleDoubleProperty(this, "presencePenalty", LlmConfig.DEFAULT_PRESENCE_PENALTY);
        this.frequencyPenalty = new SimpleDoubleProperty(this, "frequencyPenalty", LlmConfig.DEFAULT_FREQUENCY_PENALTY);
        this.seed = new SimpleObjectProperty<>(this, "seed", null);
        this.reasoningEffort = new SimpleStringProperty(this, "reasoningEffort", "");
        this.stream = new SimpleBooleanProperty(this, "stream", LlmConfig.DEFAULT_STREAM);
        this.autoLogAnalysisEnabled = new SimpleBooleanProperty(this, "autoLogAnalysisEnabled", DEFAULT_AUTO_LOG_ANALYSIS_ENABLED);
        this.autoCrashAnalysisEnabled = new SimpleBooleanProperty(this, "autoCrashAnalysisEnabled", DEFAULT_AUTO_CRASH_ANALYSIS_ENABLED);
        this.toolCallDisplayEnabled = new SimpleBooleanProperty(this, "toolCallDisplayEnabled", DEFAULT_TOOL_CALL_DISPLAY_ENABLED);
        this.approvalMode = new SimpleStringProperty(this, "approvalMode", DEFAULT_APPROVAL_MODE);
        this.dangerousActionConfirmationEnabled = new SimpleBooleanProperty(this, "dangerousActionConfirmationEnabled", DEFAULT_DANGEROUS_ACTION_CONFIRMATION_ENABLED);
        this.maxToolCycles = new SimpleIntegerProperty(this, "maxToolCycles", DEFAULT_MAX_TOOL_CYCLES);
        this.maxContextMessages = new SimpleIntegerProperty(this, "maxContextMessages", DEFAULT_MAX_CONTEXT_MESSAGES);
        this.toolResultMaxChars = new SimpleIntegerProperty(this, "toolResultMaxChars", DEFAULT_TOOL_RESULT_MAX_CHARS);
        this.requestTimeoutSeconds = new SimpleIntegerProperty(this, "requestTimeoutSeconds", DEFAULT_REQUEST_TIMEOUT_SECONDS);
        this.shellToolEnabled = new SimpleBooleanProperty(this, "shellToolEnabled", DEFAULT_SHELL_TOOL_ENABLED);
        this.webAccessEnabled = new SimpleBooleanProperty(this, "webAccessEnabled", DEFAULT_WEB_ACCESS_ENABLED);
        this.autoScrollEnabled = new SimpleBooleanProperty(this, "autoScrollEnabled", DEFAULT_AUTO_SCROLL_ENABLED);
        this.sendOnEnter = new SimpleBooleanProperty(this, "sendOnEnter", DEFAULT_SEND_ON_ENTER);
        this.criticalConfirmEnabled = new SimpleBooleanProperty(this, "criticalConfirmEnabled", DEFAULT_CRITICAL_CONFIRM_ENABLED);
        this.memoryEnabled = new SimpleBooleanProperty(this, "memoryEnabled", DEFAULT_MEMORY_ENABLED);
        this.dangerouslySkipPermissions = new SimpleBooleanProperty(this, "dangerouslySkipPermissions", DEFAULT_DANGEROUSLY_SKIP_PERMISSIONS);
        this.nbtToolsEnabled = new SimpleBooleanProperty(this, "nbtToolsEnabled", DEFAULT_NBT_TOOLS_ENABLED);
        this.autoTitleEnabled = new SimpleBooleanProperty(this, "autoTitleEnabled", DEFAULT_AUTO_TITLE_ENABLED);
        this.titleNamingModel = new SimpleStringProperty(this, "titleNamingModel", "");
        this.deleteToRecycleBin = new SimpleBooleanProperty(this, "deleteToRecycleBin", DEFAULT_DELETE_TO_RECYCLE_BIN);
        this.aiRiskNoticeAccepted = new SimpleBooleanProperty(this, "aiRiskNoticeAccepted", DEFAULT_AI_RISK_NOTICE_ACCEPTED);
        this.customInstructions = new SimpleStringProperty(this, "customInstructions", "");
        this.responseLanguage = new SimpleStringProperty(this, "responseLanguage", DEFAULT_RESPONSE_LANGUAGE);
        this.autoRecallMemory = new SimpleBooleanProperty(this, "autoRecallMemory", DEFAULT_AUTO_RECALL_MEMORY);
        this.autoSkillInjection = new SimpleBooleanProperty(this, "autoSkillInjection", DEFAULT_AUTO_SKILL_INJECTION);
        this.traceEnabled = new SimpleBooleanProperty(this, "traceEnabled", DEFAULT_TRACE_ENABLED);
        this.worldBackupMaxMb = new SimpleIntegerProperty(this, "worldBackupMaxMb", DEFAULT_WORLD_BACKUP_MAX_MB);
        this.autoCompactEnabled = new SimpleBooleanProperty(this, "autoCompactEnabled", DEFAULT_AUTO_COMPACT_ENABLED);
    }

    // ---- Property accessors (for UI binding) ---------------------------------------

    /// Returns the endpoint property used for chat completion requests.
    public StringProperty endpointProperty() {
        return endpoint;
    }

    /// Returns the API key property sent with requests to the model endpoint.
    public StringProperty apiKeyProperty() {
        return apiKey;
    }

    /// Returns the model name property requested from the endpoint.
    public StringProperty modelProperty() {
        return model;
    }

    /// Returns the provider identifier property.
    public StringProperty providerProperty() {
        return provider;
    }

    /// Returns the maximum tokens property requested from the model.
    public IntegerProperty maxTokensProperty() {
        return maxTokens;
    }

    /// Returns the sampling temperature property used for generation.
    public DoubleProperty temperatureProperty() {
        return temperature;
    }

    /// Returns the context window size property in tokens.
    public IntegerProperty contextWindowProperty() {
        return contextWindow;
    }

    /// Returns the maximum output tokens property.
    public IntegerProperty maxOutputTokensProperty() {
        return maxOutputTokens;
    }

    /// Returns the top-p (nucleus sampling) property.
    public DoubleProperty topPProperty() {
        return topP;
    }

    /// Returns the presence penalty property.
    public DoubleProperty presencePenaltyProperty() {
        return presencePenalty;
    }

    /// Returns the frequency penalty property.
    public DoubleProperty frequencyPenaltyProperty() {
        return frequencyPenalty;
    }

    /// Returns the seed property for deterministic sampling (nullable).
    public ObjectProperty<@Nullable Long> seedProperty() {
        return seed;
    }

    /// Returns the reasoning effort property (nullable string).
    public StringProperty reasoningEffortProperty() {
        return reasoningEffort;
    }

    /// Returns the stream flag property.
    public BooleanProperty streamProperty() {
        return stream;
    }

    /// Returns the auto log-analysis enabled flag property.
    public BooleanProperty autoLogAnalysisEnabledProperty() {
        return autoLogAnalysisEnabled;
    }

    /// Returns the auto crash-analysis enabled flag property.
    public BooleanProperty autoCrashAnalysisEnabledProperty() {
        return autoCrashAnalysisEnabled;
    }

    /// Returns the tool-call display enabled flag property.
    public BooleanProperty toolCallDisplayEnabledProperty() {
        return toolCallDisplayEnabled;
    }

    /// Returns the approval mode property (stores the id of an {@link AiApprovalMode}).
    public StringProperty approvalModeProperty() {
        return approvalMode;
    }

    /// Returns the dangerous-action confirmation enabled flag property.
    public BooleanProperty dangerousActionConfirmationEnabledProperty() {
        return dangerousActionConfirmationEnabled;
    }

    /// Returns the maximum tool-call cycles property.
    public IntegerProperty maxToolCyclesProperty() {
        return maxToolCycles;
    }

    /// Returns the maximum context-messages property (`0` = unlimited).
    public IntegerProperty maxContextMessagesProperty() {
        return maxContextMessages;
    }

    /// Returns the tool-result max-chars property (`0` = unlimited).
    public IntegerProperty toolResultMaxCharsProperty() {
        return toolResultMaxChars;
    }

    /// Returns the per-request timeout (seconds) property.
    public IntegerProperty requestTimeoutSecondsProperty() {
        return requestTimeoutSeconds;
    }

    /// Returns the shell-tool enabled flag property.
    public BooleanProperty shellToolEnabledProperty() {
        return shellToolEnabled;
    }

    /// Returns the web-access tools enabled flag property.
    public BooleanProperty webAccessEnabledProperty() {
        return webAccessEnabled;
    }


    /// Returns the auto-scroll enabled flag property.
    public BooleanProperty autoScrollEnabledProperty() {
        return autoScrollEnabled;
    }

    /// Returns the send-on-Enter flag property.
    public BooleanProperty sendOnEnterProperty() {
        return sendOnEnter;
    }

    public BooleanProperty criticalConfirmEnabledProperty() {
        return criticalConfirmEnabled;
    }

    public BooleanProperty autoTitleEnabledProperty() {
        return autoTitleEnabled;
    }

    /// Returns the title-naming model property. Value format: `"<profileId>::<modelId>"`
    /// (preferred; unambiguous when two providers configure the same model id) or a bare
    /// model id; blank = Auto (follow the current chat model).
    public StringProperty titleNamingModelProperty() {
        return titleNamingModel;
    }

    /// Returns the raw title-naming model value (blank = Auto).
    public String getTitleNamingModel() {
        String v = titleNamingModel.get();
        return v == null ? "" : v;
    }

    /// A resolved title-naming selection: the provider profile plus the model id on it.
    public record TitleNamingSelection(AiProviderProfile profile, String modelId) {
    }

    /// Resolves {@link #getTitleNamingModel()} against the configured provider profiles.
    ///
    /// Returns `null` for Auto — i.e. when the value is blank, or when it points at a
    /// profile/model that no longer exists (a stale selection silently falls back to Auto
    /// instead of failing every title call). Accepts both the `"<profileId>::<modelId>"`
    /// form and a bare model id (first profile carrying that model wins).
    @Nullable
    public TitleNamingSelection resolveTitleNamingModel() {
        String value = getTitleNamingModel().trim();
        if (value.isEmpty()) {
            return null;
        }
        String profileId = null;
        String modelId = value;
        int sep = value.indexOf("::");
        if (sep >= 0) {
            profileId = value.substring(0, sep);
            modelId = value.substring(sep + 2);
        }
        if (modelId.isEmpty()) {
            return null;
        }
        synchronized (profiles) {
            if (profileId != null) {
                for (AiProviderProfile p : profiles) {
                    if (p.getId().equals(profileId)) {
                        return p.getModel(modelId) != null ? new TitleNamingSelection(p, modelId) : null;
                    }
                }
                return null;
            }
            for (AiProviderProfile p : profiles) {
                if (p.getModel(modelId) != null) {
                    return new TitleNamingSelection(p, modelId);
                }
            }
        }
        return null;
    }

    public BooleanProperty deleteToRecycleBinProperty() {
        return deleteToRecycleBin;
    }

    public BooleanProperty aiRiskNoticeAcceptedProperty() {
        return aiRiskNoticeAccepted;
    }

    public BooleanProperty nbtToolsEnabledProperty() {
        return nbtToolsEnabled;
    }

    public BooleanProperty memoryEnabledProperty() {
        return memoryEnabled;
    }

    /// Returns the developer-only "skip all permission confirmations" flag property.
    public BooleanProperty dangerouslySkipPermissionsProperty() {
        return dangerouslySkipPermissions;
    }

    /// Returns the user custom-instructions property (appended to the system prompt).
    public StringProperty customInstructionsProperty() {
        return customInstructions;
    }

    /// Returns the reply-language mode property (`auto` / `zh` / `en`).
    public StringProperty responseLanguageProperty() {
        return responseLanguage;
    }

    /// Returns the auto-recall-memory flag property.
    public BooleanProperty autoRecallMemoryProperty() {
        return autoRecallMemory;
    }

    /// Returns the auto-skill-injection flag property.
    public BooleanProperty autoSkillInjectionProperty() {
        return autoSkillInjection;
    }

    /// Returns the per-world backup total-size cap property (megabytes).
    public IntegerProperty worldBackupMaxMbProperty() {
        return worldBackupMaxMb;
    }

    /// Returns the auto-compact-near-context-limit flag property.
    public BooleanProperty autoCompactEnabledProperty() {
        return autoCompactEnabled;
    }

    // ---- Convenience value accessors -----------------------------------------------

    /// Returns the current endpoint value.
    public String getEndpoint() {
        return endpoint.get();
    }

    /// Returns the current plain-text API key value.
    public String getApiKey() {
        return apiKey.get();
    }

    /// Returns the current model name value.
    public String getModel() {
        return model.get();
    }

    /// Returns the current provider identifier value.
    public String getProvider() {
        return provider.get();
    }

    /// Returns the current max tokens value.
    public int getMaxTokens() {
        return maxTokens.get();
    }

    /// Returns the current temperature value.
    public double getTemperature() {
        return temperature.get();
    }

    /// Returns the current context window value.
    public int getContextWindow() {
        return contextWindow.get();
    }

    /// Returns the current max output tokens value.
    public int getMaxOutputTokens() {
        return maxOutputTokens.get();
    }

    /// Returns the current top-p value.
    public double getTopP() {
        return topP.get();
    }

    /// Returns the current presence penalty value.
    public double getPresencePenalty() {
        return presencePenalty.get();
    }

    /// Returns the current frequency penalty value.
    public double getFrequencyPenalty() {
        return frequencyPenalty.get();
    }

    /// Returns the current seed value, or `null`.
    @Nullable
    public Long getSeed() {
        return seed.get();
    }

    /// Returns the current reasoning effort value, or empty string if not set.
    public String getReasoningEffort() {
        return reasoningEffort.get();
    }

    /// Returns whether streaming is enabled.
    public boolean isStream() {
        return stream.get();
    }

    /// Returns whether automatic log analysis is enabled.
    public boolean isAutoLogAnalysisEnabled() {
        return autoLogAnalysisEnabled.get();
    }

    /// Returns whether automatic crash analysis is enabled.
    public boolean isAutoCrashAnalysisEnabled() {
        return autoCrashAnalysisEnabled.get();
    }

    /// Returns whether tool-call display is enabled.
    public boolean isToolCallDisplayEnabled() {
        return toolCallDisplayEnabled.get();
    }

    /// Returns the current approval mode id string.
    public String getApprovalMode() {
        return approvalMode.get();
    }

    /// Returns the approval mode as an {@link AiApprovalMode} enum value (currently always
    /// {@link AiApprovalMode#AUTO} — see its doc for the SAFE/ASK/YOLO merge this replaced).
    ///
    /// Prior to that merge, the developer-only {@link #isDangerouslySkipPermissions()} bypass was
    /// faked here by reporting the most permissive mode (YOLO) so the execution policy would
    /// auto-allow every call — because the policy layer's OWN `dangerouslySkipPermissions`
    /// constructor flag was never actually threaded through from settings. That flag IS now passed
    /// straight into {@code AiExecutionPolicy}'s real bypass field by {@code ChatAgentFactory.build}
    /// (see its own comment), which is the correct fix now that there is no more permissive mode
    /// left to fake it with — so this method no longer needs to special-case it at all.
    public AiApprovalMode getApprovalModeEnum() {
        return AiApprovalMode.fromId(approvalMode.get());
    }

    /// Returns whether dangerous-action confirmation is enabled.
    public boolean isDangerousActionConfirmationEnabled() {
        return dangerousActionConfirmationEnabled.get();
    }

    /// Returns the maximum tool-call cycles per turn.
    public int getMaxToolCycles() {
        return maxToolCycles.get();
    }

    /// Returns the maximum recent context messages sent to the model (`0` = unlimited).
    public int getMaxContextMessages() {
        return maxContextMessages.get();
    }

    /// Returns the maximum characters of a single tool result fed back (`0` = unlimited).
    public int getToolResultMaxChars() {
        return toolResultMaxChars.get();
    }

    /// Returns the per-request timeout in seconds.
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds.get();
    }

    /// Returns whether the shell tool is enabled.
    public boolean isShellToolEnabled() {
        return shellToolEnabled.get();
    }

    /// Returns whether web-access tools (search/fetch) are enabled.
    public boolean isWebAccessEnabled() {
        return webAccessEnabled.get();
    }


    /// Returns whether auto-scroll-to-bottom is enabled.
    public boolean isAutoScrollEnabled() {
        return autoScrollEnabled.get();
    }

    /// Returns whether Enter sends the message (off → Ctrl+Enter sends).
    public boolean isSendOnEnter() {
        return sendOnEnter.get();
    }

    public boolean isCriticalConfirmEnabled() {
        return criticalConfirmEnabled.get();
    }

    public boolean isAutoTitleEnabled() {
        return autoTitleEnabled.get();
    }

    public boolean isDeleteToRecycleBin() {
        return deleteToRecycleBin.get();
    }

    public boolean isAiRiskNoticeAccepted() {
        return aiRiskNoticeAccepted.get();
    }

    public void setAiRiskNoticeAccepted(boolean v) {
        aiRiskNoticeAccepted.set(v);
    }

    public boolean isNbtToolsEnabled() {
        return nbtToolsEnabled.get();
    }

    /// Global memory (remember/recall) is currently product-disabled — "待开发", not just
    /// defaulted off. Force {@code false} regardless of the underlying (still fully persisted,
    /// still user-toggleable in storage) property, so every call site — tool registration, prompt
    /// injection, settings UI — is disabled from this one place without needing to be individually
    /// audited. Re-enabling the feature later is a one-line revert here.
    public boolean isMemoryEnabled() {
        return false;
    }

    /// Returns whether ALL permission confirmations are bypassed (developer-only).
    public boolean isDangerouslySkipPermissions() {
        return dangerouslySkipPermissions.get();
    }

    /// Returns the user custom-instructions text (may be empty).
    public String getCustomInstructions() {
        return customInstructions.get();
    }

    /// Returns the reply-language mode (`auto` / `zh` / `en`).
    public String getResponseLanguage() {
        return responseLanguage.get();
    }

    /// Returns whether auto-recall-memory injection is enabled. Force-disabled alongside
    /// {@link #isMemoryEnabled()} — see its javadoc.
    public boolean isAutoRecallMemory() {
        return false;
    }

    /// Returns whether trigger-matched skill playbooks are auto-injected into the prompt.
    public boolean isAutoSkillInjection() {
        return autoSkillInjection.get();
    }

    public BooleanProperty traceEnabledProperty() {
        return traceEnabled;
    }

    /// Returns whether the complete per-session agent trace is recorded.
    public boolean isTraceEnabled() {
        return traceEnabled.get();
    }

    /// Returns the per-world backup total-size cap in megabytes.
    public int getWorldBackupMaxMb() {
        return worldBackupMaxMb.get();
    }

    /// Returns whether the conversation is auto-compacted as it approaches the model's
    /// context window (see {@link org.jackhuang.hmcl.ai.agent.ChatAgent}).
    public boolean isAutoCompactEnabled() {
        return autoCompactEnabled.get();
    }

    /// Returns the current stop sequences list (unmodifiable snapshot).
    public List<String> getStopSequences() {
        return Collections.unmodifiableList(stopSequences);
    }

    /// Replaces the stop sequences list with a defensive copy.
    ///
    /// @param sequences the new stop sequences; if `null`, an empty list is used
    public void setStopSequences(@Nullable List<String> sequences) {
        if (sequences == null || sequences.isEmpty()) {
            this.stopSequences = Collections.emptyList();
        } else {
            this.stopSequences = Collections.unmodifiableList(new ArrayList<>(sequences));
        }
    }

    // ---- Multi-profile management --------------------------------------------------

    /// Returns the provider profiles list (unmodifiable snapshot).
    public List<AiProviderProfile> getProfiles() {
        synchronized (profiles) {
            return Collections.unmodifiableList(new ArrayList<>(profiles));
        }
    }

    /// Replaces the entire profiles list with a defensive copy.
    ///
    /// @param newProfiles the new profiles list
    public void setProfiles(List<AiProviderProfile> newProfiles) {
        synchronized (profiles) {
            profiles.clear();
            profiles.addAll(newProfiles);
        }
        ensureSelectedProfileValid();
    }

    /// Adds or replaces a profile. If a profile with the same id already
    /// exists, it is replaced; otherwise the new profile is appended.
    ///
    /// @param profile the profile to add or update
    public void putProfile(AiProviderProfile profile) {
        synchronized (profiles) {
            boolean replaced = false;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(profile.getId())) {
                    profiles.set(i, profile);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                profiles.add(profile);
            }
        }
        if (selectedProfileId == null) {
            selectedProfileId = profile.getId();
        }
        // Refresh the derived global request-config FROM the profile whenever the
        // active profile is added or edited, so newly-entered endpoint/API key take
        // effect immediately and the values are never lost on the next save.
        AiProviderProfile active = findSelectedProfile();
        if (active != null && active.getId().equals(profile.getId())) {
            applyProfileToProperties(profile);
        }
    }

    /// Removes a profile by id.
    ///
    /// @param id the profile id to remove
    /// @return `true` if a profile was removed
    public boolean removeProfile(String id) {
        synchronized (profiles) {
            boolean removed = profiles.removeIf(p -> p.getId().equals(id));
            if (removed) {
                ensureSelectedProfileValid();
            }
            return removed;
        }
    }

    /// Returns the currently selected profile id, or `null`.
    @Nullable
    public String getSelectedProfileId() {
        return selectedProfileId;
    }

    /// Sets the selected profile id and applies its properties to the
    /// JavaFX property fields.
    ///
    /// @param id the profile id to select; if `null`, the first profile is used
    public void setSelectedProfileId(@Nullable String id) {
        this.selectedProfileId = id;
        AiProviderProfile profile = findSelectedProfile();
        if (profile != null) {
            applyProfileToProperties(profile);
        }
    }

    /// Finds the profile matching the selected id, or the first enabled
    /// profile, or the first profile, or `null`.
    @Nullable
    public AiProviderProfile findSelectedProfile() {
        synchronized (profiles) {
            if (selectedProfileId != null) {
                for (AiProviderProfile p : profiles) {
                    if (p.getId().equals(selectedProfileId)) {
                        return p;
                    }
                }
            }
            for (AiProviderProfile p : profiles) {
                if (p.isEnabled()) {
                    return p;
                }
            }
            if (!profiles.isEmpty()) {
                return profiles.get(0);
            }
        }
        return null;
    }

    /// Ensures the selected profile id points to a valid profile. If the
    /// current selection is invalid, the first enabled profile (or first
    /// profile) is auto-selected.
    private void ensureSelectedProfileValid() {
        synchronized (profiles) {
            AiProviderProfile found = findSelectedProfile();
            if (found != null) {
                selectedProfileId = found.getId();
            } else {
                selectedProfileId = null;
            }
        }
    }

    /// Copies values from the given profile into the JavaFX properties.
    private void applyProfileToProperties(AiProviderProfile profile) {
        String normalizedEndpoint = AiEndpointNormalizer.normalize(
                profile.getEndpoint(), profile.getProtocolFamily());
        endpoint.set(normalizedEndpoint != null ? normalizedEndpoint : LlmConfig.DEFAULT_ENDPOINT);
        apiKey.set(profile.getApiKey() != null ? profile.getApiKey() : "");
        String effectiveModel = profile.getEffectiveModelId();
        model.set(effectiveModel != null ? effectiveModel : LlmConfig.DEFAULT_MODEL);
        provider.set(profile.getProtocolFamily());
    }

    // ---- Single-source request-config derivation --------------------------------------------

    /// The fully-resolved per-request parameters for the CURRENTLY-selected provider profile and its
    /// effective model. This is THE one place that reconciles the two configuration sources the
    /// settings expose — the provider profile + its per-model {@link AiModelEntry} overrides
    /// (source B) and the flat global fields (source A) — so a per-model value can never be silently
    /// dropped on the way to a request. Everything downstream reads through here:
    /// {@code ChatAgentFactory.buildConfig} (the request), {@code ChatAgent.resolveContextWindow}
    /// (compaction/eviction budget), and the composer's context ring — one口径, so the three can
    /// never disagree.
    ///
    /// @param modelId         the model id actually requested — the selected profile's effective
    ///                        model (its `defaultModelId`, else its first configured model); falls
    ///                        back to the flat {@link #getModel()} cache only when no profile exists
    ///                        yet (migration / bare tests)
    /// @param temperature     per-model override when set, else the global temperature
    /// @param maxOutputTokens per-model override when > 0, else the global max-output cap
    /// @param contextWindow   per-model override (> 0) → {@link ModelLibrary} catalog (> 0) →
    ///                        global {@link #getContextWindow()} (> 0) → 128k default
    /// @param reasoningEffort per-model override when set, else the global reasoning effort;
    ///                        normalised so `""`/`"none"` collapse to {@code null} (parameter omitted)
    public record EffectiveModelConfig(String modelId, double temperature, int maxOutputTokens,
                                       int contextWindow, @Nullable String reasoningEffort) {
    }

    /// Resolves the {@link EffectiveModelConfig} for the current selection — see its own doc. Reads
    /// the selected profile directly (not via the flat `model` cache), so it stays correct even if a
    /// caller mutated the profile's default model without going back through
    /// {@link #setSelectedProfileId} to re-apply it into the flat properties.
    public EffectiveModelConfig resolveEffectiveModelConfig() {
        AiProviderProfile profile = findSelectedProfile();
        String modelId = profile != null ? profile.getEffectiveModelId() : null;
        if (modelId == null || modelId.isEmpty()) {
            // No profile / empty profile: fall back to the flat model. It is a DERIVED CACHE of the
            // selected profile's effective model (kept in sync by applyProfileToProperties), never a
            // second independent source of truth — this branch only carries the legacy no-profiles
            // case (fresh install before migration, or bare unit tests).
            modelId = getModel();
        }
        if (modelId == null || modelId.isEmpty()) {
            modelId = LlmConfig.DEFAULT_MODEL;
        }
        AiModelEntry entry = profile != null ? profile.getModel(modelId) : null;
        ModelLibrary.ModelInfo lib = ModelLibrary.find(modelId);

        double temperature = entry != null && entry.hasTemperature()
                ? entry.getTemperature() : getTemperature();
        int maxOutput = entry != null && entry.getMaxOutputTokens() > 0
                ? entry.getMaxOutputTokens() : effectiveGlobalMaxOutput();
        int contextWindow = resolveContextWindow(entry, lib);
        String reasoning = resolveReasoningEffort(entry);
        return new EffectiveModelConfig(modelId, temperature, maxOutput, contextWindow, reasoning);
    }

    /// The global output-token cap: the dedicated max-output field when configured, else the older
    /// {@code maxTokens} field (both default to 4096) — so whichever the user's persisted file
    /// carries, a sane positive cap is always produced.
    private int effectiveGlobalMaxOutput() {
        int mo = getMaxOutputTokens();
        return mo > 0 ? mo : getMaxTokens();
    }

    /// Unified context-window resolution shared by request side and UI (spec §3.2③): per-model entry
    /// override → {@link ModelLibrary} catalog value → global setting → 128k default. Kept here (not
    /// in ChatAgent) so the request budget, the compaction/eviction budget, and the composer ring
    /// all read the identical number.
    private int resolveContextWindow(@Nullable AiModelEntry entry, ModelLibrary.@Nullable ModelInfo lib) {
        if (entry != null && entry.getContextWindow() > 0) {
            return entry.getContextWindow();
        }
        if (lib != null && lib.getContextWindow() > 0) {
            return lib.getContextWindow();
        }
        int configured = getContextWindow();
        return configured > 0 ? configured : LlmConfig.DEFAULT_CONTEXT_WINDOW;
    }

    /// Resolves the reasoning-effort level to send: per-model entry override when non-blank, else the
    /// global reasoning effort. Normalised so a blank value or the literal {@code "none"} both
    /// collapse to {@code null} — meaning "omit the reasoning parameter", which keeps non-reasoning
    /// models and the connection test unaffected. The returned value is HMCL's own level string
    /// (`low`/`medium`/`high`/`xhigh`/`max`); mapping it to each provider's concrete request
    /// parameter is the model factory's job.
    @Nullable
    private String resolveReasoningEffort(@Nullable AiModelEntry entry) {
        String raw = entry != null && !entry.getReasoningEffort().isEmpty()
                ? entry.getReasoningEffort() : getReasoningEffort();
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        return raw.isEmpty() || "none".equalsIgnoreCase(raw) ? null : raw;
    }

    // ---- Persistence ---------------------------------------------------------------

    /// Loads settings from the JSON file. If the file does not exist or is
    /// unreadable, the current (default) values are left unchanged.
    ///
    /// When old single-provider data is present on disk and no profiles list
    /// exists, the old data is automatically migrated into a single initial
    /// provider profile.
    ///
    /// The persisted API key is Base64-decoded before being set in memory.
    ///
    /// @throws IOException if an I/O error occurs while reading
    /// @throws JsonParseException if the file content is not valid JSON
    public void load() throws IOException {
        String json;
        try {
            json = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        }

        PersistedData data = GSON.fromJson(json, PersistedData.class);
        // An empty / whitespace / literal "null" file deserializes to null — keep current defaults
        // instead of NPE-ing (which previously bubbled up and silently wiped all AI config).
        if (data == null) {
            return;
        }

        if (data.profiles != null && !data.profiles.isEmpty()) {
            synchronized (profiles) {
                profiles.clear();
                profiles.addAll(data.profiles);
            }
            decodeProfileApiKeys();
            selectedProfileId = data.selectedProfileId;
            AiProviderProfile active = findSelectedProfile();
            if (active != null) {
                applyProfileToProperties(active);
            }
        } else if (hasLegacyData(data)) {
            AiProviderProfile migrated = migrateLegacyData(data);
            synchronized (profiles) {
                profiles.clear();
                profiles.add(migrated);
            }
            // The migrated profile still holds the Base64-encoded key; decode it like the profiles
            // branch does, otherwise the next save re-encodes it (base64(base64)) and corrupts the key.
            decodeProfileApiKeys();
            selectedProfileId = migrated.getId();
            applyProfileToProperties(migrated);
        }

        setLegacyFieldsFromData(data);
        syncStopSequencesFromData(data);
        decodeApiKeyFromData(data);
    }

    /// Saves the current settings to the JSON file.
    ///
    /// Current property values are synced back into the selected profile
    /// before serialization. The API key is Base64-encoded before being
    /// written. The parent directories of the settings file are created if
    /// they do not exist.
    ///
    /// @throws IOException if an I/O error occurs while writing
    public void save() throws IOException {
        Files.createDirectories(filePath.getParent());

        // NOTE: provider profiles are the source of truth. We deliberately do NOT copy
        // the (legacy, possibly stale) global endpoint/apiKey/provider properties back
        // into the selected profile here — doing so previously clobbered freshly-edited
        // provider endpoint/API keys, making them "disappear" on the next save.
        // The global request-config is instead refreshed FROM the profile in putProfile
        // and on selection/load via applyProfileToProperties.

        PersistedData data = new PersistedData();
        data.maxTokens = maxTokens.get();
        data.temperature = temperature.get();
        data.contextWindow = contextWindow.get();
        data.maxOutputTokens = maxOutputTokens.get();
        data.topP = topP.get();
        data.presencePenalty = presencePenalty.get();
        data.frequencyPenalty = frequencyPenalty.get();
        data.seed = seed.get();
        data.reasoningEffort = reasoningEffort.get().isEmpty() ? null : reasoningEffort.get();
        data.stream = stream.get();
        data.stopSequences = stopSequences.isEmpty() ? null : new ArrayList<>(stopSequences);
        data.autoLogAnalysisEnabled = autoLogAnalysisEnabled.get();
        data.autoCrashAnalysisEnabled = autoCrashAnalysisEnabled.get();
        data.toolCallDisplayEnabled = toolCallDisplayEnabled.get();
        data.approvalMode = approvalMode.get();
        data.dangerousActionConfirmationEnabled = dangerousActionConfirmationEnabled.get();
        data.maxToolCycles = maxToolCycles.get();
        data.maxContextMessages = maxContextMessages.get();
        data.toolResultMaxChars = toolResultMaxChars.get();
        data.requestTimeoutSeconds = requestTimeoutSeconds.get();
        data.shellToolEnabled = shellToolEnabled.get();
        data.webAccessEnabled = webAccessEnabled.get();
        data.autoScrollEnabled = autoScrollEnabled.get();
        data.sendOnEnter = sendOnEnter.get();
        data.criticalConfirmEnabled = criticalConfirmEnabled.get();
        data.memoryEnabled = memoryEnabled.get();
        data.dangerouslySkipPermissions = dangerouslySkipPermissions.get();
        data.nbtToolsEnabled = nbtToolsEnabled.get();
        data.autoTitleEnabled = autoTitleEnabled.get();
        data.titleNamingModel = getTitleNamingModel().isBlank() ? null : getTitleNamingModel();
        data.deleteToRecycleBin = deleteToRecycleBin.get();
        data.aiRiskNoticeAccepted = aiRiskNoticeAccepted.get();
        data.customInstructions = customInstructions.get();
        data.responseLanguage = responseLanguage.get();
        data.autoRecallMemory = autoRecallMemory.get();
        data.autoSkillInjection = autoSkillInjection.get();
        data.traceEnabled = traceEnabled.get();
        data.worldBackupMaxMb = worldBackupMaxMb.get();
        data.autoCompactEnabled = autoCompactEnabled.get();

        synchronized (profiles) {
            if (!profiles.isEmpty()) {
                data.profiles = encodeProfilesForSave();
                data.selectedProfileId = selectedProfileId;
                data.endpoint = null;
                data.apiKey = null;
                data.model = null;
                data.provider = null;
            } else {
                data.profiles = null;
                data.selectedProfileId = null;
                data.endpoint = endpoint.get();
                data.model = model.get();
                data.provider = provider.get();
                String plainKey = apiKey.get();
                if (plainKey != null && !plainKey.isEmpty()) {
                    data.apiKey = Base64.getEncoder().encodeToString(
                            plainKey.getBytes(StandardCharsets.UTF_8));
                } else {
                    data.apiKey = "";
                }
            }
        }

        String json = GSON.toJson(data);
        // Atomic write (same pattern as AiSessionStore.save): stage to a temp sibling then move
        // into place, so a crash / full disk / power loss mid-write can never leave a truncated
        // ai-settings.json — which would silently reset EVERY provider profile and API key the
        // next time anything saved over it.
        Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, filePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Returns a deep copy of the profiles list with API keys Base64-encoded
    /// for persistence.
    private List<AiProviderProfile> encodeProfilesForSave() {
        List<AiProviderProfile> copy = new ArrayList<>(profiles.size());
        for (AiProviderProfile original : profiles) {
            AiProviderProfile p = new AiProviderProfile(
                    original.getId(), original.getDisplayName(),
                    original.getProtocolFamily(), original.getEndpoint(),
                    original.getApiKey(), original.getDefaultModelId(),
                    new ArrayList<>(original.getCachedModels()),
                    original.isEnabled());
            // Preserve the FULL per-model config (alias/pricing/capabilities/overrides) on save —
            // the id-only ctor above would otherwise drop everything but the model ids.
            p.setModels(original.getModels());
            String plainKey = p.getApiKey();
            if (plainKey != null && !plainKey.isEmpty()) {
                p.setApiKey(Base64.getEncoder().encodeToString(
                        plainKey.getBytes(StandardCharsets.UTF_8)));
            }
            copy.add(p);
        }
        return copy;
    }

    /// Decodes Base64-encoded API keys in all loaded profiles back to plain
    /// text for in-memory use.
    private void decodeProfileApiKeys() {
        for (AiProviderProfile profile : profiles) {
            String encodedKey = profile.getApiKey();
            if (encodedKey != null && !encodedKey.isEmpty()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(encodedKey);
                    profile.setApiKey(new String(decoded, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    profile.setApiKey("");
                }
            }
        }
    }
    /// single-provider fields that should trigger migration.
    private static boolean hasLegacyData(PersistedData data) {
        if (data.endpoint != null && !data.endpoint.isEmpty()
                && !data.endpoint.equals(LlmConfig.DEFAULT_ENDPOINT)) {
            return true;
        }
        if (data.apiKey != null && !data.apiKey.isEmpty()) {
            return true;
        }
        if (data.model != null && !data.model.isEmpty()
                && !data.model.equals(LlmConfig.DEFAULT_MODEL)) {
            return true;
        }
        if (data.provider != null && !data.provider.isEmpty()
                && !data.provider.equals(LlmConfig.DEFAULT_PROVIDER)) {
            return true;
        }
        return false;
    }

    /// Creates a new provider profile from old single-provider settings data.
    private static AiProviderProfile migrateLegacyData(PersistedData data) {
        String endpoint = data.endpoint != null ? data.endpoint : LlmConfig.DEFAULT_ENDPOINT;
        String apiKey = data.apiKey != null ? data.apiKey : "";
        String model = data.model != null ? data.model : LlmConfig.DEFAULT_MODEL;
        String provider = data.provider != null ? data.provider : LlmConfig.DEFAULT_PROVIDER;

        String protocolFamily = mapLegacyProviderToFamily(provider);

        AiProviderProfile profile = new AiProviderProfile(
                UUID.randomUUID().toString(),
                "Migrated Provider",
                protocolFamily,
                endpoint,
                "",
                model,
                new ArrayList<>(),
                true
        );

        // ApiKey is set separately because the persisted value is Base64-encoded;
        // the caller decodes it after construction.
        profile.setApiKey(apiKey);

        return profile;
    }

    /// Maps an old provider id to the closest protocol family.
    private static String mapLegacyProviderToFamily(String providerId) {
        if ("anthropic".equals(providerId)) {
            return AiProtocolFamily.ANTHROPIC.getId();
        }
        // RESTAPI is deprecated and unsupported at runtime; map every other legacy
        // provider (including unknown ones) to the OpenAI-compatible family so no
        // migrated profile lands in an unusable protocol family.
        return AiProtocolFamily.OPENAI_COMPLETIONS.getId();
    }

    /// Applies old-style fields from the persisted data to the JavaFX properties.
    /// This handles the case where profiles exist but old fields also carry
    /// values (transitional state).
    private void setLegacyFieldsFromData(PersistedData data) {
        if (data.maxTokens > 0) maxTokens.set(data.maxTokens);
        if (data.temperature > 0) temperature.set(data.temperature);
        if (data.contextWindow > 0) contextWindow.set(data.contextWindow);
        if (data.maxOutputTokens > 0) maxOutputTokens.set(data.maxOutputTokens);
        if (data.topP > 0) topP.set(data.topP);
        presencePenalty.set(data.presencePenalty);
        frequencyPenalty.set(data.frequencyPenalty);
        seed.set(data.seed);
        reasoningEffort.set(data.reasoningEffort != null ? data.reasoningEffort : "");
        stream.set(data.stream);
        autoLogAnalysisEnabled.set(data.autoLogAnalysisEnabled);
        autoCrashAnalysisEnabled.set(data.autoCrashAnalysisEnabled);
        toolCallDisplayEnabled.set(data.toolCallDisplayEnabled);
        approvalMode.set(data.approvalMode != null ? data.approvalMode : DEFAULT_APPROVAL_MODE);
        dangerousActionConfirmationEnabled.set(data.dangerousActionConfirmationEnabled);
        // Absent fields keep their PersistedData initializer defaults (Gson does not
        // null/zero them), so these can be applied directly.
        maxToolCycles.set(data.maxToolCycles > 0 ? data.maxToolCycles : DEFAULT_MAX_TOOL_CYCLES);
        maxContextMessages.set(Math.max(0, data.maxContextMessages));
        toolResultMaxChars.set(Math.max(0, data.toolResultMaxChars));
        requestTimeoutSeconds.set(data.requestTimeoutSeconds > 0 ? data.requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS);
        shellToolEnabled.set(data.shellToolEnabled);
        webAccessEnabled.set(data.webAccessEnabled);
        autoScrollEnabled.set(data.autoScrollEnabled);
        sendOnEnter.set(data.sendOnEnter);
        criticalConfirmEnabled.set(data.criticalConfirmEnabled);
        memoryEnabled.set(data.memoryEnabled);
        dangerouslySkipPermissions.set(data.dangerouslySkipPermissions);
        nbtToolsEnabled.set(data.nbtToolsEnabled);
        autoTitleEnabled.set(data.autoTitleEnabled);
        titleNamingModel.set(data.titleNamingModel != null ? data.titleNamingModel : "");
        deleteToRecycleBin.set(data.deleteToRecycleBin);
        aiRiskNoticeAccepted.set(data.aiRiskNoticeAccepted);
        customInstructions.set(data.customInstructions != null ? data.customInstructions : "");
        responseLanguage.set(data.responseLanguage != null && !data.responseLanguage.isEmpty()
                ? data.responseLanguage : DEFAULT_RESPONSE_LANGUAGE);
        autoRecallMemory.set(data.autoRecallMemory);
        autoSkillInjection.set(data.autoSkillInjection);
        traceEnabled.set(data.traceEnabled);
        worldBackupMaxMb.set(data.worldBackupMaxMb > 0 ? data.worldBackupMaxMb : DEFAULT_WORLD_BACKUP_MAX_MB);
        autoCompactEnabled.set(data.autoCompactEnabled);
    }

    private void syncStopSequencesFromData(PersistedData data) {
        if (data.stopSequences != null) {
            this.stopSequences = Collections.unmodifiableList(new ArrayList<>(data.stopSequences));
        }
    }

    private void decodeApiKeyFromData(PersistedData data) {
        if (data.apiKey != null && !data.apiKey.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(data.apiKey);
                apiKey.set(new String(decoded, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                apiKey.set("");
            }
        }
    }
}
