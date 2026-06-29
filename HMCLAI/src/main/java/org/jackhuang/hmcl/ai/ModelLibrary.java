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
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// A read-only catalog of well-known model metadata (context window, max output,
/// modalities, pricing) keyed by raw model id.
///
/// The "add a model" flow uses this to **auto-fill** an {@link AiModelEntry}'s
/// advanced/pricing fields the moment the user types or selects a model id, so the
/// user doesn't have to know that, say, `gpt-4.1` has a ~1M-token context window or
/// that `claude-opus-4-8` costs $5/$25 per million tokens.
///
/// ## Lookup is STRICT
///
/// {@link #lookup(String)} returns an entry only on an **exact** id match, and
/// `null` otherwise. This is a deliberate product decision: a fuzzy/prefix match
/// would silently mis-fill metadata for the wrong model variant. When the lookup
/// misses, the caller is expected to fall back to safe defaults (128k context,
/// 4096 max output) rather than guessing.
///
/// To cover the common case where users may enter either an alias (`claude-opus-4-8`)
/// or a dated snapshot id (`claude-opus-4-5-20251101`), the bundled catalog seeds
/// **both** as separate entries with identical metadata.
///
/// ## Data source
///
/// On first use the catalog is loaded from the bundled classpath resource
/// {@value #BUNDLED_RESOURCE}. If that resource is missing or malformed the catalog
/// degrades to empty — every lookup returns `null` and callers fall back to defaults,
/// so a broken resource never breaks model creation.
///
/// ## Designed for a future silent startup-update
///
/// Model ids, context windows, and prices change often, so the bundled JSON will go
/// stale. The class is built so a future background task can refresh the catalog
/// **without** an app release:
///
///  1. On startup, kick off an async fetch of an updated `model-library.json` from a
///     remote endpoint (or a CDN mirror), guarded by an ETag / `schemaVersion` check.
///  2. Persist the downloaded copy to a cache file under the launcher data directory
///     (so it survives restarts and works offline).
///  3. Call {@link #installUpdate(Reader)} with the downloaded JSON. It merges the
///     update **over** the bundled baseline (so a partial remote file never drops
///     entries) and atomically swaps the live instance — concurrent {@link #lookup}
///     callers either see the old or the new map, never a half-built one.
///
/// At app boot the orchestrator would prefer the cached copy if present (load it via
/// {@link #installUpdate(Reader)} before the first lookup), otherwise the bundled
/// baseline is used until the network fetch completes.
///
/// ## Thread safety
///
/// The live instance is held in a `volatile` field and its backing map is immutable,
/// so reads are lock-free and {@link #installUpdate(Reader)} is safe to call from a
/// background thread at any time.
@NotNullByDefault
public final class ModelLibrary {

    /// Classpath location of the bundled catalog.
    public static final String BUNDLED_RESOURCE = "/assets/ai/model-library.json";

    private static final Gson GSON = new Gson();

    /// The live instance. `volatile` so a background {@link #installUpdate} swap is
    /// visible to lookup callers without locking.
    private static volatile ModelLibrary instance;

    /// Lazily-loaded immutable copy of the bundled baseline, reused when merging updates.
    private static volatile @Nullable Map<String, ModelInfo> bundledBaseline;

    private final Map<String, ModelInfo> models;

    private ModelLibrary(Map<String, ModelInfo> models) {
        this.models = models;
    }

    /// Returns the live catalog instance, loading the bundled baseline on first use.
    public static ModelLibrary getInstance() {
        ModelLibrary local = instance;
        if (local == null) {
            synchronized (ModelLibrary.class) {
                local = instance;
                if (local == null) {
                    local = new ModelLibrary(bundledBaseline());
                    instance = local;
                }
            }
        }
        return local;
    }

    /// Looks up metadata for an exact model id. Returns `null` when there is no
    /// strict match (the caller should then apply its own defaults).
    @Nullable
    public ModelInfo lookup(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        return models.get(modelId);
    }

    /// Static convenience for {@code getInstance().lookup(modelId)}.
    @Nullable
    public static ModelInfo find(String modelId) {
        return getInstance().lookup(modelId);
    }

    /// Returns an unmodifiable view of every known model id (e.g. for autocomplete).
    public java.util.Set<String> knownModelIds() {
        return models.keySet();
    }

    /// Replaces the live catalog with the bundled baseline merged with the given JSON
    /// update. Intended for a future startup silent-update (see the class docs). The
    /// update wins on key collisions; entries only present in the baseline are kept.
    /// A malformed or empty update is ignored, leaving the current catalog untouched.
    public static void installUpdate(Reader json) {
        Map<String, ModelInfo> updated = parse(json);
        if (updated.isEmpty()) {
            return;
        }
        Map<String, ModelInfo> merged = new LinkedHashMap<>(bundledBaseline());
        merged.putAll(updated);
        instance = new ModelLibrary(Collections.unmodifiableMap(merged));
    }

    private static Map<String, ModelInfo> bundledBaseline() {
        Map<String, ModelInfo> local = bundledBaseline;
        if (local == null) {
            synchronized (ModelLibrary.class) {
                local = bundledBaseline;
                if (local == null) {
                    local = loadBundled();
                    bundledBaseline = local;
                }
            }
        }
        return local;
    }

    private static Map<String, ModelInfo> loadBundled() {
        try (InputStream in = ModelLibrary.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A broken bundled resource must never break model creation; fall back to
            // an empty catalog so every lookup misses and callers use their defaults.
            return Collections.emptyMap();
        }
    }

    private static Map<String, ModelInfo> parse(Reader json) {
        try {
            Catalog catalog = GSON.fromJson(json, Catalog.class);
            if (catalog == null || catalog.models == null || catalog.models.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, ModelInfo> result = new LinkedHashMap<>();
            for (Map.Entry<String, ModelInfo> entry : catalog.models.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isEmpty() && entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException e) {
            // JsonParseException (a RuntimeException) and any malformed-data error: degrade to empty.
            return Collections.emptyMap();
        }
    }

    /// JSON wrapper: a `schemaVersion` (for future silent-update compatibility checks)
    /// and the id -> metadata map.
    private static final class Catalog {
        @SerializedName("schemaVersion")
        int schemaVersion;

        @SerializedName("models")
        @Nullable
        Map<String, ModelInfo> models;
    }

    /// Immutable metadata for one model. Field names intentionally mirror
    /// {@link AiModelEntry} so the add-model flow can copy values across directly.
    /// Sentinel "unknown" values match {@link AiModelEntry}'s conventions: `0` for the
    /// integer/price fields means "not known — use the global/provider default".
    @NotNullByDefault
    public static final class ModelInfo {

        @SerializedName("contextWindow")
        private int contextWindow;

        @SerializedName("maxOutput")
        private int maxOutput;

        @SerializedName("inputModalities")
        private String inputModalities = "text";

        @SerializedName("outputModalities")
        private String outputModalities = "text";

        @SerializedName("inputPricePerMillion")
        private double inputPricePerMillion;

        @SerializedName("outputPricePerMillion")
        private double outputPricePerMillion;

        @SerializedName("cacheWritePricePerMillion")
        private double cacheWritePricePerMillion;

        @SerializedName("cacheReadPricePerMillion")
        private double cacheReadPricePerMillion;

        @SerializedName("supportsTools")
        private boolean supportsTools = true;

        @SerializedName("supportsVision")
        private boolean supportsVision;

        @SerializedName("supportsReasoning")
        private boolean supportsReasoning;

        /// No-arg constructor so Gson applies the field defaults above.
        public ModelInfo() {
        }

        /// Context window in tokens (`0` = unknown).
        public int getContextWindow() {
            return contextWindow;
        }

        /// Maximum output tokens (`0` = unknown).
        public int getMaxOutput() {
            return maxOutput;
        }

        /// Comma-separated input modalities, e.g. `"text"` or `"text,image"`.
        public String getInputModalities() {
            return inputModalities == null || inputModalities.isBlank() ? "text" : inputModalities;
        }

        /// Comma-separated output modalities, e.g. `"text"`.
        public String getOutputModalities() {
            return outputModalities == null || outputModalities.isBlank() ? "text" : outputModalities;
        }

        /// Input price per million tokens (`0` = unknown).
        public double getInputPricePerMillion() {
            return inputPricePerMillion;
        }

        /// Output price per million tokens (`0` = unknown).
        public double getOutputPricePerMillion() {
            return outputPricePerMillion;
        }

        /// Cache-write price per million tokens (`0` = unknown).
        public double getCacheWritePricePerMillion() {
            return cacheWritePricePerMillion;
        }

        /// Cache-read price per million tokens (`0` = unknown).
        public double getCacheReadPricePerMillion() {
            return cacheReadPricePerMillion;
        }

        /// Whether the model supports native tool/function calling.
        public boolean isSupportsTools() {
            return supportsTools;
        }

        /// Whether the model can read images (vision input).
        public boolean isSupportsVision() {
            return supportsVision;
        }

        /// Whether the model exposes a reasoning/thinking mode.
        public boolean isSupportsReasoning() {
            return supportsReasoning;
        }

        /// Whether any non-zero price is known for this model.
        public boolean hasPricing() {
            return inputPricePerMillion > 0 || outputPricePerMillion > 0
                    || cacheWritePricePerMillion > 0 || cacheReadPricePerMillion > 0;
        }
    }
}
