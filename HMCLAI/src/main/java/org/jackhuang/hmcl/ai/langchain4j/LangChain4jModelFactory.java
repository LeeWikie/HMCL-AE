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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/// Builds LangChain4j chat and streaming models from HMCL's
/// [`LlmConfig`][org.jackhuang.hmcl.ai.LlmConfig], handling the
/// mapping between HMCL configuration values and LangChain4j builder
/// parameters.
///
/// The endpoint stored in LlmConfig is a full chat-completions URL
/// (e.g. `https://api.openai.com/v1/chat/completions`). This factory
/// strips the path suffix to derive the base URL required by LangChain4j's
/// `OpenAiChatModel.baseUrl()`.
@NotNullByDefault
public final class LangChain4jModelFactory {

    /// The chat-completions path suffix stripped from endpoints to derive
    /// the base URL.
    private static final String CHAT_PATH = "/chat/completions";

    /// Whether to enable LangChain4j request/response logging (default: false).
    private final boolean logRequests;
    private final boolean logResponses;

    /// Creates a factory with logging disabled.
    public LangChain4jModelFactory() {
        this(false, false);
    }

    /// Creates a factory with explicit logging flags.
    ///
    /// @param logRequests  whether to log outgoing requests
    /// @param logResponses whether to log incoming responses
    public LangChain4jModelFactory(boolean logRequests, boolean logResponses) {
        this.logRequests = logRequests;
        this.logResponses = logResponses;
    }

    /// Builds a LangChain4j HTTP client builder whose underlying JDK [`HttpClient`] honours
    /// HMCL's globally-configured proxy and proxy credentials.
    ///
    /// LangChain4j's default JDK client is built from a bare `HttpClient.newBuilder()`, and the
    /// JDK `HttpClient` consults neither the default [`ProxySelector`] nor
    /// `Authenticator.setDefault(...)` unless told to explicitly — so without this hook the main
    /// chat traffic (streaming and non-streaming, OpenAI-compatible and Anthropic alike)
    /// bypasses the user's proxy entirely and fails for many users, especially in CN. Same
    /// idiom as `WebFetchTool`/`SearxngSearchClient`.
    ///
    /// LangChain4j applies each model's own connect/read timeout on top of this builder
    /// (`JdkHttpClient` only overrides `connectTimeout` when one is set on the LangChain4j
    /// side), so timeouts configured via [`LlmConfig`] are unaffected.
    ///
    /// The proxy authenticator is attached conditionally via
    /// [`ProxyAuthenticatorHolder#configure`]: attaching one unconditionally would make the
    /// JDK client fail bare 401 responses (no `WWW-Authenticate` header — the norm for LLM
    /// APIs rejecting an API key) with `IOException` instead of surfacing them.
    static JdkHttpClientBuilder proxyAwareHttpClientBuilder() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
                        .proxy(ProxySelector.getDefault())));
    }

    /// Builds a LangChain4j [`ChatModel`] from the given configuration.
    ///
    /// The returned model is configured with the endpoint, API key, model name,
    /// temperature, max tokens, and timeout from the supplied LlmConfig.
    ///
    /// @param config the HMCL LLM configuration; must not be {@code null}
    /// @return a new LangChain4j chat model instance
    public ChatModel buildChatModel(LlmConfig config) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .httpClientBuilder(proxyAwareHttpClientBuilder())
                .baseUrl(extractBaseUrl(config.getEndpoint()))
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout());
        applyOpenAiReasoningAndOutputCap(builder, config);

        if (config.getTopP() != LlmConfig.DEFAULT_TOP_P) {
            builder.topP(config.getTopP());
        }
        if (config.getPresencePenalty() != LlmConfig.DEFAULT_PRESENCE_PENALTY) {
            builder.presencePenalty(config.getPresencePenalty());
        }
        if (config.getFrequencyPenalty() != LlmConfig.DEFAULT_FREQUENCY_PENALTY) {
            builder.frequencyPenalty(config.getFrequencyPenalty());
        }

        Long seed = config.getSeed();
        if (seed != null) {
            builder.seed(seed.intValue());
        }

        builder.logRequests(logRequests);
        builder.logResponses(logResponses);

        return builder.build();
    }

    /// Builds a LangChain4j [`StreamingChatModel`] from the given
    /// configuration.
    ///
    /// The returned streaming model is configured with the endpoint, API key,
    /// model name, temperature, and timeout from the supplied LlmConfig.
    ///
    /// @param config the HMCL LLM configuration; must not be {@code null}
    /// @return a new LangChain4j streaming chat model instance
    public StreamingChatModel buildStreamingChatModel(LlmConfig config) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
                OpenAiStreamingChatModel.builder()
                        .httpClientBuilder(proxyAwareHttpClientBuilder())
                        .baseUrl(extractBaseUrl(config.getEndpoint()))
                        .apiKey(config.getApiKey())
                        .modelName(config.getModel())
                        .temperature(config.getTemperature())
                        .timeout(config.getTimeout());
        applyOpenAiReasoningAndOutputCap(builder, config);

        if (config.getTopP() != LlmConfig.DEFAULT_TOP_P) {
            builder.topP(config.getTopP());
        }
        if (config.getPresencePenalty() != LlmConfig.DEFAULT_PRESENCE_PENALTY) {
            builder.presencePenalty(config.getPresencePenalty());
        }
        if (config.getFrequencyPenalty() != LlmConfig.DEFAULT_FREQUENCY_PENALTY) {
            builder.frequencyPenalty(config.getFrequencyPenalty());
        }

        Long seed = config.getSeed();
        if (seed != null) {
            builder.seed(seed.intValue());
        }

        builder.logRequests(logRequests);
        builder.logResponses(logResponses);

        // Capture provider "reasoning"/thinking content (e.g. DeepSeek-R1's reasoning_content) so it
        // can be surfaced in a collapsible card. Harmless for models that don't emit it (stays null,
        // onPartialThinking simply never fires).
        builder.returnThinking(true);

        return builder.build();
    }

    /// Builds a LangChain4j Anthropic ChatModel from the given configuration.
    public ChatModel buildAnthropicChatModel(LlmConfig config) {
        int thinkingBudget = clampAnthropicThinkingBudget(
                mapAnthropicThinkingBudget(config.getReasoningEffort()), config.getMaxOutputTokens());
        var builder = dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
                .httpClientBuilder(proxyAwareHttpClientBuilder())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                // Extended thinking requires temperature = 1 (the Anthropic API rejects any other
                // value when thinking is enabled); otherwise honour the configured temperature.
                .temperature(thinkingBudget > 0 ? 1.0 : config.getTemperature())
                .maxTokens(config.getMaxOutputTokens())
                .timeout(config.getTimeout());
        if (thinkingBudget > 0) {
            builder.thinkingType("enabled").thinkingBudgetTokens(thinkingBudget);
        }
        String base = extractAnthropicBaseUrl(config.getEndpoint());
        if (base != null && !base.isBlank()) {
            builder.baseUrl(base);
        }
        return builder.build();
    }

    /// Builds a LangChain4j Anthropic StreamingChatModel from the given configuration.
    public StreamingChatModel buildAnthropicStreamingChatModel(LlmConfig config) {
        int thinkingBudget = clampAnthropicThinkingBudget(
                mapAnthropicThinkingBudget(config.getReasoningEffort()), config.getMaxOutputTokens());
        var builder = dev.langchain4j.model.anthropic.AnthropicStreamingChatModel.builder()
                .httpClientBuilder(proxyAwareHttpClientBuilder())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(thinkingBudget > 0 ? 1.0 : config.getTemperature())
                .maxTokens(config.getMaxOutputTokens())
                .timeout(config.getTimeout());
        if (thinkingBudget > 0) {
            builder.thinkingType("enabled").thinkingBudgetTokens(thinkingBudget);
        }
        String base = extractAnthropicBaseUrl(config.getEndpoint());
        if (base != null && !base.isBlank()) {
            builder.baseUrl(base);
        }
        builder.returnThinking(true);
        return builder.build();
    }

    // ---- Reasoning-effort → provider request-parameter mapping (spec §3.2②) ---------------------

    /// Applies the reasoning effort and the output-token cap to an OpenAI(-compatible) chat builder.
    /// When a reasoning effort is present the model is a reasoning model, so the cap is sent as
    /// {@code max_completion_tokens} (reasoning models reject {@code max_tokens}) and
    /// {@code reasoning_effort} is set; otherwise the cap is the ordinary {@code max_tokens}.
    private static void applyOpenAiReasoningAndOutputCap(
            OpenAiChatModel.OpenAiChatModelBuilder builder, LlmConfig config) {
        String effort = mapOpenAiReasoningEffort(config.getReasoningEffort());
        if (effort != null) {
            builder.reasoningEffort(effort).maxCompletionTokens(config.getMaxOutputTokens());
        } else {
            builder.maxTokens(config.getMaxOutputTokens());
        }
    }

    /// Streaming-builder overload of {@link #applyOpenAiReasoningAndOutputCap(OpenAiChatModel.OpenAiChatModelBuilder, LlmConfig)}
    /// — the two OpenAI builders share no common supertype exposing these setters, so the identical
    /// logic is duplicated across a typed overload rather than reflected.
    private static void applyOpenAiReasoningAndOutputCap(
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder, LlmConfig config) {
        String effort = mapOpenAiReasoningEffort(config.getReasoningEffort());
        if (effort != null) {
            builder.reasoningEffort(effort).maxCompletionTokens(config.getMaxOutputTokens());
        } else {
            builder.maxTokens(config.getMaxOutputTokens());
        }
    }

    /// Maps HMCL's reasoning-effort level to a value the OpenAI {@code reasoning_effort} parameter
    /// accepts, or {@code null} to omit it. HMCL's two extra levels collapse to the API ceiling
    /// {@code "high"}; a blank value or {@code "none"} omit the parameter entirely so non-reasoning
    /// models and the connection test (which sends {@code "none"}) are unaffected.
    @Nullable
    static String mapOpenAiReasoningEffort(@Nullable String effort) {
        if (effort == null) {
            return null;
        }
        return switch (effort.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high", "xhigh", "max" -> "high";
            default -> null; // "", "none", or anything unrecognised → omit
        };
    }

    /// Maps HMCL's reasoning-effort level to an Anthropic extended-thinking token budget
    /// ({@code 0} = thinking disabled). Anthropic has no effort enum — it takes a token budget — so
    /// each level is assigned a representative budget, clamped later against the output cap by
    /// {@link #clampAnthropicThinkingBudget(int, int)}.
    static int mapAnthropicThinkingBudget(@Nullable String effort) {
        if (effort == null) {
            return 0;
        }
        return switch (effort.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> 2048;
            case "medium" -> 8192;
            case "high" -> 16384;
            case "xhigh" -> 24576;
            case "max" -> 32768;
            default -> 0; // "", "none", or anything unrecognised → thinking off
        };
    }

    /// Clamps an Anthropic thinking budget so it stays valid: the API requires the budget to be
    /// below {@code max_tokens}, and a minimum of 1024. Reserves 1024 tokens of head-room for the
    /// visible answer; if the output cap is too small to fit both thinking and an answer, thinking is
    /// disabled ({@code 0}) rather than sending an invalid request.
    static int clampAnthropicThinkingBudget(int budget, int maxOutputTokens) {
        if (budget <= 0) {
            return 0;
        }
        int cap = maxOutputTokens - 1024;
        if (cap < 1024) {
            return 0;
        }
        return Math.min(budget, cap);
    }

    /// Maps a configured Anthropic endpoint to the base URL the Anthropic client expects.
    ///
    /// The Anthropic client appends `messages` to its base URL (default `https://api.anthropic.com/v1/`),
    /// while HMCL stores the normalized full endpoint (e.g. `.../v1/messages`). Strip a trailing
    /// `/messages` so a user-configured proxy/relay/regional endpoint is actually honoured instead of
    /// being silently ignored. Returns `null` for a blank endpoint (keep the client default).
    static String extractAnthropicBaseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String e = endpoint.trim();
        if (e.endsWith("/messages")) {
            return e.substring(0, e.length() - "messages".length());
        }
        return e;
    }

    /// Extracts the base URL from a full chat-completions endpoint.
    ///
    /// If the endpoint ends with `/chat/completions`, the suffix is stripped.
    /// Otherwise the endpoint is returned as-is, assuming it is already a
    /// base URL.
    ///
    /// @param endpoint the full endpoint URL from LlmConfig
    /// @return the base URL suitable for LangChain4j
    static String extractBaseUrl(String endpoint) {
        if (endpoint.endsWith(CHAT_PATH)) {
            return endpoint.substring(0, endpoint.length() - CHAT_PATH.length());
        }
        return endpoint;
    }
}
