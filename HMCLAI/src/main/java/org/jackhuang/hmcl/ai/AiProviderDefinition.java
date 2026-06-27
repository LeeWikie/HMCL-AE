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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Metadata definition for a single AI provider.
///
/// Each provider has a unique {@link #id}, a human-readable {@link #displayName},
/// a default chat completions endpoint, and flags that inform the UI which
/// advanced parameters the provider supports.
///
/// Instances are immutable value objects built via a compact schema-driven
/// approach — the hardcoded registry returned by {@link #registry()} is the
/// single source of truth for the first-pass provider set.
@NotNullByDefault
public final class AiProviderDefinition {

    private final String id;
    private final String displayName;
    private final String defaultEndpoint;
    private final boolean supportsReasoning;
    private final boolean supportsSeed;
    private final boolean supportsStopSequences;
    private final boolean requiresApiKey;
    private final List<AiModelPreset> modelPresets;

    /// Creates a provider definition.
    ///
    /// @param id                    unique provider identifier (e.g. "openai")
    /// @param displayName           human-readable name (e.g. "OpenAI")
    /// @param defaultEndpoint       default chat completions endpoint
    /// @param supportsReasoning     whether this provider supports reasoning effort
    /// @param supportsSeed          whether this provider supports the seed parameter
    /// @param supportsStopSequences whether this provider supports stop sequences
    /// @param requiresApiKey        whether this provider requires an API key
    /// @param modelPresets          the default model presets for this provider
    public AiProviderDefinition(String id, String displayName, String defaultEndpoint,
                                boolean supportsReasoning, boolean supportsSeed,
                                boolean supportsStopSequences, boolean requiresApiKey,
                                List<AiModelPreset> modelPresets) {
        this.id = id;
        this.displayName = displayName;
        this.defaultEndpoint = defaultEndpoint;
        this.supportsReasoning = supportsReasoning;
        this.supportsSeed = supportsSeed;
        this.supportsStopSequences = supportsStopSequences;
        this.requiresApiKey = requiresApiKey;
        this.modelPresets = Collections.unmodifiableList(modelPresets);
    }

    /// Returns the unique provider id.
    public String getId() {
        return id;
    }

    /// Returns the human-readable display name.
    public String getDisplayName() {
        return displayName;
    }

    /// Returns the default chat completions endpoint.
    public String getDefaultEndpoint() {
        return defaultEndpoint;
    }

    /// Returns whether this provider supports the reasoning effort parameter.
    public boolean supportsReasoning() {
        return supportsReasoning;
    }

    /// Returns whether this provider supports the seed parameter.
    public boolean supportsSeed() {
        return supportsSeed;
    }

    /// Returns whether this provider supports stop sequences.
    public boolean supportsStopSequences() {
        return supportsStopSequences;
    }

    /// Returns whether this provider requires an API key.
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /// Returns the default model presets for this provider (unmodifiable).
    public List<AiModelPreset> getModelPresets() {
        return modelPresets;
    }

    /// Looks up a provider by id, returning `null` if not found.
    ///
    /// @param id the provider identifier
    /// @return the matching definition, or `null`
    @Nullable
    public static AiProviderDefinition byId(String id) {
        return registry().get(id);
    }

    // ---- Hardcoded first-pass provider registry ---------------------------------

    /// Returns the first-pass provider registry, keyed by provider id.
    ///
    /// The map preserves insertion order so UIs can iterate in a predictable sequence.
    public static Map<String, AiProviderDefinition> registry() {
        // Lazy-initialized via a static holder idiom for thread safety.
        return RegistryHolder.REGISTRY;
    }

    private static final class RegistryHolder {
        static final Map<String, AiProviderDefinition> REGISTRY = buildRegistry();
    }

    private static Map<String, AiProviderDefinition> buildRegistry() {
        Map<String, AiProviderDefinition> map = new LinkedHashMap<>();

        // --- OpenAI ---
        map.put("openai", new AiProviderDefinition(
                "openai", "OpenAI",
                "https://api.openai.com/v1/chat/completions",
                true, true, true, true,
                List.of(
                        new AiModelPreset("openai", "GPT-4o", "gpt-4o", 128000, 16384, false),
                        new AiModelPreset("openai", "GPT-4o Mini", "gpt-4o-mini", 128000, 16384, false),
                        new AiModelPreset("openai", "GPT-4.1", "gpt-4.1", 1048576, 32768, false),
                        new AiModelPreset("openai", "GPT-4.1 Mini", "gpt-4.1-mini", 1048576, 16384, false),
                        new AiModelPreset("openai", "GPT-4.1 Nano", "gpt-4.1-nano", 1048576, 16384, false),
                        new AiModelPreset("openai", "o4-mini", "o4-mini", 200000, 100000, true),
                        new AiModelPreset("openai", "o3", "o3", 200000, 100000, true)
                )
        ));

        // --- Anthropic ---
        map.put("anthropic", new AiProviderDefinition(
                "anthropic", "Anthropic",
                "https://api.anthropic.com/v1/messages",
                false, false, true, true,
                List.of(
                        new AiModelPreset("anthropic", "Claude 4 Sonnet", "claude-sonnet-4-20250514", 200000, 64000, false),
                        new AiModelPreset("anthropic", "Claude 3.7 Sonnet", "claude-3-7-sonnet-20250219", 200000, 64000, false),
                        new AiModelPreset("anthropic", "Claude 3.5 Haiku", "claude-3-5-haiku-20241022", 200000, 8192, false)
                )
        ));

        // --- Google Gemini ---
        map.put("google", new AiProviderDefinition(
                "google", "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                false, false, true, true,
                List.of(
                        new AiModelPreset("google", "Gemini 2.5 Pro", "gemini-2.5-pro", 1048576, 65536, false),
                        new AiModelPreset("google", "Gemini 2.5 Flash", "gemini-2.5-flash", 1048576, 65536, false),
                        new AiModelPreset("google", "Gemini 2.0 Flash", "gemini-2.0-flash", 1048576, 8192, false)
                )
        ));

        // --- DeepSeek ---
        map.put("deepseek", new AiProviderDefinition(
                "deepseek", "DeepSeek",
                "https://api.deepseek.com/v1/chat/completions",
                false, false, true, true,
                List.of(
                        new AiModelPreset("deepseek", "DeepSeek V3", "deepseek-chat", 65536, 8192, false),
                        new AiModelPreset("deepseek", "DeepSeek R1", "deepseek-reasoner", 65536, 8192, true)
                )
        ));

        // --- Ollama ---
        map.put("ollama", new AiProviderDefinition(
                "ollama", "Ollama",
                "http://localhost:11434/v1/chat/completions",
                false, true, true, false,
                List.of(
                        new AiModelPreset("ollama", "Llama 3.3", "llama3.3", 128000, 8192, false),
                        new AiModelPreset("ollama", "Mistral", "mistral", 32768, 8192, false),
                        new AiModelPreset("ollama", "Gemma 3", "gemma3", 32768, 8192, false)
                )
        ));

        // --- OpenAI-Compatible Custom ---
        map.put("custom", new AiProviderDefinition(
                "custom", "OpenAI-Compatible Custom",
                "",
                false, true, true, false,
                List.of(
                        new AiModelPreset("custom", "Custom Model", "", 0, 0, false)
                )
        ));

        return Collections.unmodifiableMap(map);
    }
}
