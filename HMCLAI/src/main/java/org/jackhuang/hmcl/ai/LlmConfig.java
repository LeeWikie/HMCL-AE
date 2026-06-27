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

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/// Stores the OpenAI-compatible connection and generation defaults for the HMCL AI client.
///
/// The instance is effectively immutable from the caller's perspective: values are supplied through
/// the constructor, and the class exposes read-only accessors for each property.
///
/// The field set supports provider-driven advanced parameters needed by upcoming UI iterations
/// (provider selector, collapsible advanced panel). Defaults are chosen to be safe for
/// the widest set of compatible backends.
@NotNullByDefault
public final class LlmConfig {
    /// The default OpenAI-compatible chat completions endpoint.
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    /// The default API key value when no key has been configured.
    public static final String DEFAULT_API_KEY = "";

    /// The default model name used for new AI configurations.
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /// The default provider identifier.
    public static final String DEFAULT_PROVIDER = "openai";

    /// The default maximum number of generated tokens.
    public static final int DEFAULT_MAX_TOKENS = 4096;

    /// The default sampling temperature.
    public static final double DEFAULT_TEMPERATURE = 0.7d;

    /// The default request timeout.
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /// Default context window size in tokens.
    public static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /// Default top-p (nucleus sampling).
    public static final double DEFAULT_TOP_P = 1.0d;

    /// Default presence penalty.
    public static final double DEFAULT_PRESENCE_PENALTY = 0.0d;

    /// Default frequency penalty.
    public static final double DEFAULT_FREQUENCY_PENALTY = 0.0d;

    /// Default stream flag.
    public static final boolean DEFAULT_STREAM = true;

    // ---- Fields ----

    /// The endpoint used for chat completion requests.
    private final String endpoint;

    /// The API key sent with requests to the model endpoint.
    private final String apiKey;

    /// The model name requested from the endpoint.
    private final String model;

    /// The provider identifier (e.g. "openai", "anthropic", "google").
    private final String provider;

    /// The maximum number of tokens requested from the model.
    private final int maxTokens;

    /// The sampling temperature used for generation.
    private final double temperature;

    /// The maximum time allowed for a request.
    private final Duration timeout;

    /// The context window size in tokens.
    private final int contextWindow;

    /// The maximum output tokens for a single completion.
    private final int maxOutputTokens;

    /// Top-p (nucleus sampling) parameter, between 0 and 1.
    private final double topP;

    /// Presence penalty, between -2.0 and 2.0.
    private final double presencePenalty;

    /// Frequency penalty, between -2.0 and 2.0.
    private final double frequencyPenalty;

    /// Optional seed for deterministic sampling.
    @Nullable
    private final Long seed;

    /// Optional reasoning effort hint (e.g. "low", "medium", "high").
    @Nullable
    private final String reasoningEffort;

    /// Whether to use streaming responses.
    private final boolean stream;

    /// Optional stop sequences list.
    private final List<String> stopSequences;

    /// Creates a configuration with the MVP defaults.
    public LlmConfig() {
        this(DEFAULT_ENDPOINT, DEFAULT_API_KEY, DEFAULT_MODEL, DEFAULT_PROVIDER,
                DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, DEFAULT_TIMEOUT,
                DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_TOKENS, DEFAULT_TOP_P,
                DEFAULT_PRESENCE_PENALTY, DEFAULT_FREQUENCY_PENALTY,
                null, null, DEFAULT_STREAM, Collections.emptyList());
    }

    /// Creates a configuration with explicit basic values (backwards-compatible).
    ///
    /// @param endpoint    the endpoint used for chat completion requests
    /// @param apiKey      the API key sent with requests to the model endpoint
    /// @param model       the model name requested from the endpoint
    /// @param maxTokens   the maximum number of tokens requested from the model
    /// @param temperature the sampling temperature used for generation
    /// @param timeout     the maximum time allowed for a request
    public LlmConfig(String endpoint, String apiKey, String model,
                     int maxTokens, double temperature, Duration timeout) {
        this(endpoint, apiKey, model, DEFAULT_PROVIDER, maxTokens, temperature, timeout,
                DEFAULT_CONTEXT_WINDOW, maxTokens, DEFAULT_TOP_P,
                DEFAULT_PRESENCE_PENALTY, DEFAULT_FREQUENCY_PENALTY,
                null, null, DEFAULT_STREAM, Collections.emptyList());
    }

    /// Creates a configuration with explicit basic values and provider.
    ///
    /// @param endpoint    the endpoint used for chat completion requests
    /// @param apiKey      the API key sent with requests to the model endpoint
    /// @param model       the model name requested from the endpoint
    /// @param provider    the provider identifier
    /// @param maxTokens   the maximum number of tokens requested from the model
    /// @param temperature the sampling temperature used for generation
    /// @param timeout     the maximum time allowed for a request
    public LlmConfig(String endpoint, String apiKey, String model, String provider,
                     int maxTokens, double temperature, Duration timeout) {
        this(endpoint, apiKey, model, provider, maxTokens, temperature, timeout,
                DEFAULT_CONTEXT_WINDOW, maxTokens, DEFAULT_TOP_P,
                DEFAULT_PRESENCE_PENALTY, DEFAULT_FREQUENCY_PENALTY,
                null, null, DEFAULT_STREAM, Collections.emptyList());
    }

    /// Full constructor with all configuration parameters.
    ///
    /// @param endpoint         the endpoint used for chat completion requests
    /// @param apiKey           the API key sent with requests to the model endpoint
    /// @param model            the model name requested from the endpoint
    /// @param provider         the provider identifier
    /// @param maxTokens        the maximum number of tokens requested from the model
    /// @param temperature      the sampling temperature used for generation
    /// @param timeout          the maximum time allowed for a request
    /// @param contextWindow    the context window size in tokens
    /// @param maxOutputTokens  the maximum output tokens for a single completion
    /// @param topP             top-p (nucleus sampling) parameter
    /// @param presencePenalty  presence penalty
    /// @param frequencyPenalty frequency penalty
    /// @param seed             optional seed for deterministic sampling
    /// @param reasoningEffort  optional reasoning effort hint
    /// @param stream           whether to use streaming responses
    /// @param stopSequences    optional stop sequences list; defensive copy is made
    @SuppressWarnings("ConstructorParametersCount")
    public LlmConfig(String endpoint, String apiKey, String model, String provider,
                     int maxTokens, double temperature, Duration timeout,
                     int contextWindow, int maxOutputTokens, double topP,
                     double presencePenalty, double frequencyPenalty,
                     @Nullable Long seed, @Nullable String reasoningEffort,
                     boolean stream, List<String> stopSequences) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.provider = provider;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeout = timeout;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.topP = topP;
        this.presencePenalty = presencePenalty;
        this.frequencyPenalty = frequencyPenalty;
        this.seed = seed;
        this.reasoningEffort = reasoningEffort;
        this.stream = stream;
        this.stopSequences = Collections.unmodifiableList(stopSequences);
    }

    /// Returns the endpoint used for chat completion requests.
    public String getEndpoint() {
        return endpoint;
    }

    /// Returns the API key sent with requests to the model endpoint.
    public String getApiKey() {
        return apiKey;
    }

    /// Returns the model name requested from the endpoint.
    public String getModel() {
        return model;
    }

    /// Returns the provider identifier.
    public String getProvider() {
        return provider;
    }

    /// Returns the maximum number of tokens requested from the model.
    public int getMaxTokens() {
        return maxTokens;
    }

    /// Returns the sampling temperature used for generation.
    public double getTemperature() {
        return temperature;
    }

    /// Returns the maximum time allowed for a request.
    public Duration getTimeout() {
        return timeout;
    }

    /// Returns the context window size in tokens.
    public int getContextWindow() {
        return contextWindow;
    }

    /// Returns the maximum output tokens for a single completion.
    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /// Returns top-p (nucleus sampling) parameter.
    public double getTopP() {
        return topP;
    }

    /// Returns the presence penalty.
    public double getPresencePenalty() {
        return presencePenalty;
    }

    /// Returns the frequency penalty.
    public double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /// Returns the optional seed for deterministic sampling, or `null`.
    @Nullable
    public Long getSeed() {
        return seed;
    }

    /// Returns the optional reasoning effort hint, or `null`.
    @Nullable
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    /// Returns whether streaming responses are enabled.
    public boolean isStream() {
        return stream;
    }

    /// Returns the stop sequences list (unmodifiable).
    public List<String> getStopSequences() {
        return stopSequences;
    }
}
