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
package org.jackhuang.hmcl.ai.kb;

import com.google.gson.annotations.SerializedName;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Knowledge-base (RAG) configuration, persisted to its own {@code ai-kb-config.json} exactly like
/// {@link org.jackhuang.hmcl.ai.search.AiSearchConfig} — one cohesive observable config object the
/// settings UI edits and the tool binder gates on, so toggling the KB registers/unregisters the
/// {@code kb_search} tool on the next turn with no restart.
///
/// Persistence uses plain Gson fields; only {@link #enabled} carries a lazy {@code transient}
/// JavaFX property — the single field that needs a live cross-component observable (for the
/// tool-registration bind), mirroring {@code AiSearchConfig.enabledProperty}. Every other field is
/// a plain getter/setter the settings rows read/write and persist on change.
///
/// The embedding-model linkage (KB → configured embedding provider) is AstrBot's design, expressed
/// here as {@link #embeddingModelRef} ({@code "<profileId>::<modelId>"}) resolved leniently against
/// the provider profiles by {@link #resolveEmbeddingModel(AiSettings)}.
@NotNullByDefault
public final class AiKbConfig {

    @SerializedName("enabled")
    private boolean enabled = false;

    /// Observable mirror of {@link #enabled}, created lazily and kept in lock-step by
    /// {@link #setEnabled(boolean)}. {@code transient} so Gson never serializes the property object;
    /// the persisted source of truth stays the plain {@link #enabled} field.
    private transient @Nullable BooleanProperty enabledProperty;

    @SerializedName("sourceMode")
    private KbSourceMode sourceMode = KbSourceMode.REMOTE_HTTP;

    @SerializedName("endpoint")
    private String endpoint = "https://agentexperience.online";

    @SerializedName("localIndexPath")
    private String localIndexPath = "";

    /// {@code "<profileId>::<modelId>"} of the embedding model this KB uses to embed queries — the
    /// AstrBot-style linkage from a knowledge base to a configured embedding provider. Blank = none
    /// picked. Resolved leniently (a stale/deleted reference falls back to {@code null}).
    @SerializedName("embeddingModelRef")
    private String embeddingModelRef = "";

    @SerializedName("topK")
    private int topK = 5;

    @SerializedName("fusionTopK")
    private int fusionTopK = 20;

    // ---- enabled (mirrors AiSearchConfig's field + lazy observable pattern) ----

    public boolean isEnabled() {
        return enabledProperty != null ? enabledProperty.get() : enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabledProperty != null) enabledProperty.set(enabled);
    }

    /// Live observable of the KB-enabled state. The chat page binds {@code kb_search} registration
    /// to this (ANDed with {@link #isValid(AiSettings)}); the settings toggle mutates the same
    /// property through {@link #setEnabled(boolean)}, so the two stay in sync with no restart.
    public ObservableValue<Boolean> enabledProperty() {
        if (enabledProperty == null) {
            enabledProperty = new SimpleBooleanProperty(this, "enabled", enabled);
        }
        return enabledProperty;
    }

    // ---- source mode (Gson-serialized by enum name; unknown/null tolerated) ----

    public KbSourceMode getSourceMode() {
        return sourceMode == null ? KbSourceMode.REMOTE_HTTP : sourceMode;
    }

    public void setSourceMode(@Nullable KbSourceMode mode) {
        this.sourceMode = mode == null ? KbSourceMode.REMOTE_HTTP : mode;
    }

    // ---- endpoint / local index path (trimmed, never null) ----

    public String getEndpoint() {
        return endpoint == null ? "" : endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public String getLocalIndexPath() {
        return localIndexPath == null ? "" : localIndexPath;
    }

    public void setLocalIndexPath(String localIndexPath) {
        this.localIndexPath = localIndexPath == null ? "" : localIndexPath.trim();
    }

    // ---- embedding model reference ----

    public String getEmbeddingModelRef() {
        return embeddingModelRef == null ? "" : embeddingModelRef;
    }

    public void setEmbeddingModelRef(@Nullable String ref) {
        this.embeddingModelRef = ref == null ? "" : ref.trim();
    }

    // ---- retrieval params (soft-clamped like AiModelEntry's setters) ----

    public int getTopK() {
        // Clamp in the getter too, not only the setter: Gson assigns the field directly (bypassing
        // the setter), so a hand-edited config with e.g. topK=999 must still read back as a sane
        // value rather than sending an absurd count downstream.
        return Math.max(1, Math.min(20, topK));
    }

    public void setTopK(int topK) {
        this.topK = Math.max(1, Math.min(20, topK));
    }

    public int getFusionTopK() {
        return Math.max(1, Math.min(50, fusionTopK));
    }

    public void setFusionTopK(int fusionTopK) {
        this.fusionTopK = Math.max(1, Math.min(50, fusionTopK));
    }

    // ---- resolution + validation ----

    /// A resolved embedding-model selection: the provider profile plus the model entry on it.
    public record ResolvedEmbeddingModel(AiProviderProfile profile, AiModelEntry model) {
    }

    /// Resolves {@link #getEmbeddingModelRef()} against {@code settings}' provider profiles, mirroring
    /// {@link AiSettings#resolveTitleNamingModel()}: accepts {@code "<profileId>::<modelId>"} or a bare
    /// model id (first profile carrying it wins), and returns {@code null} for a blank or stale/deleted
    /// reference rather than throwing — so a since-deleted model just reads as "not configured".
    public @Nullable ResolvedEmbeddingModel resolveEmbeddingModel(AiSettings settings) {
        String value = getEmbeddingModelRef();
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
        for (AiProviderProfile p : settings.getProfiles()) {
            if (profileId != null && !p.getId().equals(profileId)) {
                continue;
            }
            for (AiModelEntry e : p.getModels()) {
                if (modelId.equals(e.getId())) {
                    return new ResolvedEmbeddingModel(p, e);
                }
            }
            if (profileId != null) {
                // The named profile exists but no longer carries this model → stale selection.
                return null;
            }
        }
        return null;
    }

    /// Whether the KB is configured well enough to register the retrieval tool. The registration
    /// gate ANDs this with {@link #isEnabled()} (see {@code AIMainPage.registerTools}). REMOTE_HTTP
    /// needs a non-blank endpoint; LOCAL_INDEX needs both an index path and a resolvable embedding
    /// model (the picked model must exist, since the app embeds the query with it in that mode).
    public boolean isValid(AiSettings settings) {
        switch (getSourceMode()) {
            case REMOTE_HTTP:
                return !getEndpoint().isEmpty();
            case LOCAL_INDEX:
                return !getLocalIndexPath().isEmpty() && resolveEmbeddingModel(settings) != null;
            default:
                return false;
        }
    }
}
