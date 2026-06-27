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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A model preset definition providing sensible defaults for a specific
/// provider/model combination.
///
/// Each preset includes the provider id, a human-readable display name,
/// the raw model identifier string, default context window size, maximum
/// output tokens, and whether the model supports reasoning.
///
/// Instances are immutable and typically constructed as part of
/// {@link AiProviderDefinition} model presets lists.
@NotNullByDefault
public final class AiModelPreset {

    private final String provider;
    private final String displayName;
    private final String modelId;
    private final int defaultContextWindow;
    private final int defaultMaxOutput;
    private final boolean supportsReasoning;

    /// Creates a model preset definition.
    ///
    /// @param provider             the provider id (e.g. "openai")
    /// @param displayName          human-readable display name (e.g. "GPT-4o")
    /// @param modelId              raw model identifier string (e.g. "gpt-4o")
    /// @param defaultContextWindow default context window size in tokens
    /// @param defaultMaxOutput     default maximum output tokens
    /// @param supportsReasoning    whether this model supports reasoning effort
    public AiModelPreset(String provider, String displayName, String modelId,
                         int defaultContextWindow, int defaultMaxOutput,
                         boolean supportsReasoning) {
        this.provider = provider;
        this.displayName = displayName;
        this.modelId = modelId;
        this.defaultContextWindow = defaultContextWindow;
        this.defaultMaxOutput = defaultMaxOutput;
        this.supportsReasoning = supportsReasoning;
    }

    /// Returns the provider id.
    public String getProvider() {
        return provider;
    }

    /// Returns the human-readable display name.
    public String getDisplayName() {
        return displayName;
    }

    /// Returns the raw model identifier string.
    public String getModelId() {
        return modelId;
    }

    /// Returns the default context window size in tokens.
    public int getDefaultContextWindow() {
        return defaultContextWindow;
    }

    /// Returns the default maximum output tokens.
    public int getDefaultMaxOutput() {
        return defaultMaxOutput;
    }

    /// Returns whether this model supports reasoning.
    public boolean supportsReasoning() {
        return supportsReasoning;
    }

    /// Searches the entire provider registry for a model preset whose
    /// {@link #getModelId()} matches the given model id string.
    ///
    /// When a known preset is found, the caller can hydrate its capability
    /// defaults (context window, max output, reasoning support). Unknown
    /// model ids return `null` — they remain usable as custom models.
    ///
    /// @param modelId the raw model identifier string to look up
    /// @return the matching preset, or `null`
    @Nullable
    public static AiModelPreset findByModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        for (AiProviderDefinition provider : AiProviderDefinition.registry().values()) {
            for (AiModelPreset preset : provider.getModelPresets()) {
                if (preset.modelId.equals(modelId)) {
                    return preset;
                }
            }
        }
        return null;
    }
}
