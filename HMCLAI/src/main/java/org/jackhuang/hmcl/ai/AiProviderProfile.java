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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// A user-managed provider profile representing a single AI API backend
/// with its connection details, protocol family, and cached discovered models.
///
/// Each profile stores:
/// - a unique id
/// - a human-readable display name
/// - the protocol family determining endpoint auto-completion and model-discovery
///   behaviour
/// - the service endpoint (before normalization)
/// - the API key (plain text in memory, Base64-encoded on disk)
/// - an optional default model id
/// - a cached list of discovered model ids
/// - an enabled flag
///
/// A single profile can host multiple models — the default model id is merely a
/// preselection hint for the UI.
@NotNullByDefault
public final class AiProviderProfile {

    @SerializedName("id")
    private final String id;

    @SerializedName("displayName")
    private String displayName;

    @SerializedName("protocolFamily")
    private String protocolFamily;

    @SerializedName("endpoint")
    private String endpoint;

    @SerializedName("apiKey")
    private String apiKey;

    @SerializedName("defaultModelId")
    @Nullable
    private String defaultModelId;

    @SerializedName("cachedModels")
    private List<String> cachedModels;

    @SerializedName("modelAliases")
    private Map<String, String> modelAliases;

    @SerializedName("enabled")
    private boolean enabled;

    /// Rich per-model configuration. Supersedes [`cachedModels`]/[`modelAliases`],
    /// which are migrated into this list on first access and then cleared.
    @SerializedName("models")
    @Nullable
    private List<AiModelEntry> models;

    /// Creates a profile with a generated UUID.
    public AiProviderProfile() {
        this(UUID.randomUUID().toString(), "", AiProtocolFamily.OPENAI_COMPLETIONS.getId(),
                "", "", null, new ArrayList<>(), true);
    }

    /// Creates a profile with explicit field values.
    ///
    /// @param id              unique profile identifier
    /// @param displayName     human-readable display name
    /// @param protocolFamily  protocol family id string (see {@link AiProtocolFamily#getId()})
    /// @param endpoint        the service endpoint (before normalization)
    /// @param apiKey          the API key in plain text
    /// @param defaultModelId  optional default model id to preselect
    /// @param cachedModels    list of model ids previously discovered for this provider
    /// @param enabled         whether this profile is active
    public AiProviderProfile(String id, String displayName, String protocolFamily,
                             String endpoint, String apiKey,
                             @Nullable String defaultModelId,
                             List<String> cachedModels, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.protocolFamily = protocolFamily;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.defaultModelId = defaultModelId;
        this.cachedModels = new ArrayList<>(cachedModels);
        this.enabled = enabled;
    }

    /// Returns the unique profile identifier.
    public String getId() {
        return id;
    }

    /// Returns the human-readable display name.
    public String getDisplayName() {
        return displayName;
    }

    /// Sets the human-readable display name.
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /// Returns the protocol family id string.
    public String getProtocolFamily() {
        return protocolFamily;
    }

    /// Sets the protocol family id string.
    public void setProtocolFamily(String protocolFamily) {
        this.protocolFamily = protocolFamily;
    }

    /// Returns the service endpoint string (before normalization).
    public String getEndpoint() {
        return endpoint;
    }

    /// Sets the service endpoint string.
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /// Returns the API key in plain text.
    public String getApiKey() {
        return apiKey;
    }

    /// Sets the API key in plain text.
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /// Returns the optional default model id, or `null`.
    @Nullable
    public String getDefaultModelId() {
        return defaultModelId;
    }

    /// Sets the optional default model id.
    public void setDefaultModelId(@Nullable String defaultModelId) {
        this.defaultModelId = defaultModelId;
    }

    /// Lazily migrates the legacy `cachedModels`/`modelAliases` data into the rich
    /// {@link #models} list (once), then returns the live list.
    private List<AiModelEntry> models() {
        if (models == null) {
            models = new ArrayList<>();
        }
        if (models.isEmpty() && cachedModels != null && !cachedModels.isEmpty()) {
            for (String id : cachedModels) {
                AiModelEntry entry = new AiModelEntry(id);
                if (modelAliases != null) {
                    String alias = modelAliases.get(id);
                    if (alias != null && !alias.isEmpty()) entry.setAlias(alias);
                }
                models.add(entry);
            }
            // Drop the legacy fields so they are not re-serialized as stale data.
            cachedModels = new ArrayList<>();
            modelAliases = null;
        }
        return models;
    }

    /// Returns the rich per-model entries (unmodifiable snapshot).
    public List<AiModelEntry> getModels() {
        return Collections.unmodifiableList(new ArrayList<>(models()));
    }

    /// Returns the entry for the given model id, or `null`.
    @Nullable
    public AiModelEntry getModel(String modelId) {
        for (AiModelEntry entry : models()) {
            if (entry.getId().equals(modelId)) return entry;
        }
        return null;
    }

    /// Adds or replaces a model entry (matched by id).
    public void putModel(AiModelEntry entry) {
        List<AiModelEntry> list = models();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(entry.getId())) {
                list.set(i, entry);
                return;
            }
        }
        list.add(entry);
    }

    /// Removes the model entry with the given id.
    public void removeModel(String modelId) {
        models().removeIf(e -> e.getId().equals(modelId));
    }

    /// Returns the model ids (defensive copy), derived from the rich entries.
    public List<String> getCachedModels() {
        List<String> ids = new ArrayList<>();
        for (AiModelEntry entry : models()) {
            ids.add(entry.getId());
        }
        return Collections.unmodifiableList(ids);
    }

    /// Syncs the model entries to the given id list: adds new ids (preserving the
    /// config of ids that already exist) and drops ids no longer present.
    public void setCachedModels(List<String> modelIds) {
        List<AiModelEntry> list = models();
        List<AiModelEntry> result = new ArrayList<>();
        for (String id : modelIds) {
            AiModelEntry existing = null;
            for (AiModelEntry entry : list) {
                if (entry.getId().equals(id)) {
                    existing = entry;
                    break;
                }
            }
            result.add(existing != null ? existing : new AiModelEntry(id));
        }
        list.clear();
        list.addAll(result);
    }

    /// Sets a model alias on the matching entry (creating the entry if needed).
    public void setModelAlias(String modelId, String alias) {
        AiModelEntry entry = getModel(modelId);
        if (entry == null) {
            entry = new AiModelEntry(modelId);
            models().add(entry);
        }
        entry.setAlias(alias);
    }

    /// Returns the alias for a model, or the model id if no alias is set.
    public String getModelAliasOrId(String modelId) {
        AiModelEntry entry = getModel(modelId);
        return entry != null ? entry.getDisplayName() : modelId;
    }

    /// Returns whether this profile is enabled.
    public boolean isEnabled() {
        return enabled;
    }

    /// Sets the enabled flag.
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /// Returns the effective model id — the default model if set, otherwise
    /// the first cached model, or `null` if nothing is available.
    @Nullable
    public String getEffectiveModelId() {
        if (defaultModelId != null && !defaultModelId.isEmpty()) {
            return defaultModelId;
        }
        List<AiModelEntry> list = models();
        if (!list.isEmpty()) {
            return list.get(0).getId();
        }
        return null;
    }
}
