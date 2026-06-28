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

        @SerializedName("titleNamingEnabled")
        private boolean titleNamingEnabled = DEFAULT_TITLE_NAMING_ENABLED;

        @SerializedName("titleNamingModelId")
        @Nullable
        private String titleNamingModelId = null;

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
    private final BooleanProperty titleNamingEnabled;
    private final StringProperty titleNamingModelId;
    private final BooleanProperty autoLogAnalysisEnabled;
    private final BooleanProperty autoCrashAnalysisEnabled;
    private final BooleanProperty toolCallDisplayEnabled;
    private final StringProperty approvalMode;
    private final BooleanProperty dangerousActionConfirmationEnabled;

    // Complex values (list-based, no JavaFX property)
    private volatile List<String> stopSequences = Collections.emptyList();

    // Multi-profile state
    private final List<AiProviderProfile> profiles = new ArrayList<>();
    @Nullable
    private String selectedProfileId = null;

    /// Default value for the title-naming enabled flag.
    static final boolean DEFAULT_TITLE_NAMING_ENABLED = true;

    /// Default value for the auto log-analysis enabled flag.
    static final boolean DEFAULT_AUTO_LOG_ANALYSIS_ENABLED = true;

    /// Default value for the auto crash-analysis enabled flag.
    static final boolean DEFAULT_AUTO_CRASH_ANALYSIS_ENABLED = true;

    /// Default value for the tool-call display enabled flag.
    static final boolean DEFAULT_TOOL_CALL_DISPLAY_ENABLED = true;

    /// Default approval mode id (`"safe"`).
    static final String DEFAULT_APPROVAL_MODE = "safe";

    /// Default value for the dangerous-action confirmation enabled flag.
    static final boolean DEFAULT_DANGEROUS_ACTION_CONFIRMATION_ENABLED = true;

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
        this.titleNamingEnabled = new SimpleBooleanProperty(this, "titleNamingEnabled", DEFAULT_TITLE_NAMING_ENABLED);
        this.titleNamingModelId = new SimpleStringProperty(this, "titleNamingModelId", "");
        this.autoLogAnalysisEnabled = new SimpleBooleanProperty(this, "autoLogAnalysisEnabled", DEFAULT_AUTO_LOG_ANALYSIS_ENABLED);
        this.autoCrashAnalysisEnabled = new SimpleBooleanProperty(this, "autoCrashAnalysisEnabled", DEFAULT_AUTO_CRASH_ANALYSIS_ENABLED);
        this.toolCallDisplayEnabled = new SimpleBooleanProperty(this, "toolCallDisplayEnabled", DEFAULT_TOOL_CALL_DISPLAY_ENABLED);
        this.approvalMode = new SimpleStringProperty(this, "approvalMode", DEFAULT_APPROVAL_MODE);
        this.dangerousActionConfirmationEnabled = new SimpleBooleanProperty(this, "dangerousActionConfirmationEnabled", DEFAULT_DANGEROUS_ACTION_CONFIRMATION_ENABLED);
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

    /// Returns the title-naming enabled flag property.
    public BooleanProperty titleNamingEnabledProperty() {
        return titleNamingEnabled;
    }

    /// Returns the title-naming model id property (nullable string).
    public StringProperty titleNamingModelIdProperty() {
        return titleNamingModelId;
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

    /// Returns whether AI-powered title naming is enabled.
    public boolean isTitleNamingEnabled() {
        return titleNamingEnabled.get();
    }

    /// Returns the optional title-naming model id, or empty string if not set.
    public String getTitleNamingModelId() {
        return titleNamingModelId.get();
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

    /// Returns the approval mode as an {@link AiApprovalMode} enum value.
    public AiApprovalMode getApprovalModeEnum() {
        return AiApprovalMode.fromId(approvalMode.get());
    }

    /// Returns whether dangerous-action confirmation is enabled.
    public boolean isDangerousActionConfirmationEnabled() {
        return dangerousActionConfirmationEnabled.get();
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
        data.titleNamingEnabled = titleNamingEnabled.get();
        data.titleNamingModelId = titleNamingModelId.get().isEmpty() ? null : titleNamingModelId.get();
        data.autoLogAnalysisEnabled = autoLogAnalysisEnabled.get();
        data.autoCrashAnalysisEnabled = autoCrashAnalysisEnabled.get();
        data.toolCallDisplayEnabled = toolCallDisplayEnabled.get();
        data.approvalMode = approvalMode.get();
        data.dangerousActionConfirmationEnabled = dangerousActionConfirmationEnabled.get();

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
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
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
        titleNamingEnabled.set(data.titleNamingEnabled);
        titleNamingModelId.set(data.titleNamingModelId != null ? data.titleNamingModelId : "");
        autoLogAnalysisEnabled.set(data.autoLogAnalysisEnabled);
        autoCrashAnalysisEnabled.set(data.autoCrashAnalysisEnabled);
        toolCallDisplayEnabled.set(data.toolCallDisplayEnabled);
        approvalMode.set(data.approvalMode != null ? data.approvalMode : DEFAULT_APPROVAL_MODE);
        dangerousActionConfirmationEnabled.set(data.dangerousActionConfirmationEnabled);
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
