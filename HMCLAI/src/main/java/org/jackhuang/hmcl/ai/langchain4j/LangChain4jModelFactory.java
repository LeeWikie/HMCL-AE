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

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;

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

    /// Builds a LangChain4j [`ChatModel`] from the given configuration.
    ///
    /// The returned model is configured with the endpoint, API key, model name,
    /// temperature, max tokens, and timeout from the supplied LlmConfig.
    ///
    /// @param config the HMCL LLM configuration; must not be {@code null}
    /// @return a new LangChain4j chat model instance
    public ChatModel buildChatModel(LlmConfig config) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(extractBaseUrl(config.getEndpoint()))
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(config.getTimeout());

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
                        .baseUrl(extractBaseUrl(config.getEndpoint()))
                        .apiKey(config.getApiKey())
                        .modelName(config.getModel())
                        .temperature(config.getTemperature())
                        .maxTokens(config.getMaxTokens())
                        .timeout(config.getTimeout());

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

    /// Builds a LangChain4j Anthropic ChatModel from the given configuration.
    public ChatModel buildAnthropicChatModel(LlmConfig config) {
        return dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(config.getTimeout())
                .build();
    }

    /// Builds a LangChain4j Anthropic StreamingChatModel from the given configuration.
    public StreamingChatModel buildAnthropicStreamingChatModel(LlmConfig config) {
        return dev.langchain4j.model.anthropic.AnthropicStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(config.getTimeout())
                .build();
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
