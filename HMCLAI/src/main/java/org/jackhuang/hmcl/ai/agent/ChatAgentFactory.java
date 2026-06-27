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
package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.langchain4j.LangChain4jChatAdapter;
import org.jackhuang.hmcl.ai.langchain4j.LangChain4jModelFactory;
import org.jackhuang.hmcl.ai.langchain4j.LangChain4jToolAdapter;
import org.jackhuang.hmcl.ai.llm.LlmClient;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Factory for building {@link ChatAgent} instances and performing lightweight
/// connection tests without engaging the full conversation pipeline.
///
/// ## Responsibilities
///
/// - **Build**: creates a {@link ChatAgent} from the current {@link AiSettings},
///   a supplied {@link AiSession}, and a {@link ToolRegistry}.
/// - **Test connection**: sends a minimal one-word prompt to the configured
///   endpoint using a dedicated lightweight call — no session history is required
///   and no tool invocation loop is engaged.
///
/// ## Connection test contract
///
/// The {@link #testConnection} method sends a user message `"Reply with exactly
/// one word: Hello"`, requests at most 8 output tokens at temperature 0, and
/// times out after 10 seconds. This validates the endpoint, API key, model, and
/// basic response path without creating or mutating any session.
///
/// Failure mapping follows the same convention as {@link LlmException}:
/// 401 → authentication error, 429 → rate limit, 0+timeout → network error,
/// other → generic API error.
///
/// @see ChatAgent
/// @see AiSettings
@NotNullByDefault
public final class ChatAgentFactory {

    /// The prompt used for the lightweight connection test.
    static final String TEST_PROMPT = "Reply with exactly one word: Hello";

    /// Maximum output tokens for the connection test.
    static final int TEST_MAX_TOKENS = 8;

    /// Timeout for the connection test.
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    /// Provider IDs that can be handled by the LangChain4j OpenAI-compatible
    /// adapter. Providers NOT in this set fall back to the legacy
    /// [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient] path so that no
    /// configuration is silently broken.
    ///
    /// This is a deliberate first-pass list. Providers whose endpoint
    /// follows the OpenAI `/v1/chat/completions` convention can be added
    /// here as they are verified against LangChain4j.
    private static final Set<String> LANGCHAIN4J_PROVIDERS = Set.of(
            "openai", "azure", "deepseek", "ollama", "groq", "zhipu",
            "moonshot", "qwen", "stepfun"
    );

    /// Shared factory for building LangChain4j models. Logging disabled
    /// by default.
    private static final LangChain4jModelFactory MODEL_FACTORY =
            new LangChain4jModelFactory();

    /// Builds a {@link ChatAgent} from settings, a session, and a tool registry.
    ///
    /// The returned agent is immediately ready to handle user messages.
    ///
    /// ## Provider routing
    ///
    /// If the configured provider is in {@link #LANGCHAIN4J_PROVIDERS}, the
    /// agent is backed by a LangChain4j-powered adapter. Otherwise the legacy
    /// [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient] path is used.
    ///
    /// @param settings the AI configuration; must not be `null`
    /// @param session  the conversation session to bind; must not be `null`
    /// @param tools    the tool registry; must not be `null`
    /// @return a new {@link ChatAgent} instance
    public static ChatAgent build(AiSettings settings, AiSession session, ToolRegistry tools) {
        LlmConfig config = buildConfig(settings);
        AiChatClient client = resolveClient(config, tools);
        return new ChatAgent(client, session, settings);
    }

    /// Tests the connection to the configured LLM endpoint with a lightweight
    /// one-word prompt.
    ///
    /// This method sends a single non-streaming request with minimal parameters
    /// and does not interact with any session or tool registry. It returns a
    /// {@link CompletableFuture} that completes with an empty string on success
    /// or throws an {@link LlmException} on failure.
    ///
    /// The future may complete exceptionally with:
    /// - {@link LlmException} for API errors (401, 429, 5xx, etc.)
    /// - {@link TimeoutException} if the request exceeds {@code TEST_TIMEOUT}
    ///
    /// @param settings the AI settings to test connectivity with; must not be `null`
    /// @return a future that completes successfully or with an error
    public static CompletableFuture<String> testConnection(AiSettings settings) {
        // Build a minimal config just for the test — temperature 0, tiny output,
        // short timeout, no streaming.
        LlmConfig testConfig = new LlmConfig(
                settings.getEndpoint(),
                settings.getApiKey(),
                settings.getModel().isEmpty() ? LlmConfig.DEFAULT_MODEL : settings.getModel(),
                settings.getProvider(),
                TEST_MAX_TOKENS,
                0.0, // temperature 0 for deterministic output
                TEST_TIMEOUT,
                LlmConfig.DEFAULT_CONTEXT_WINDOW,
                TEST_MAX_TOKENS,
                LlmConfig.DEFAULT_TOP_P,
                LlmConfig.DEFAULT_PRESENCE_PENALTY,
                LlmConfig.DEFAULT_FREQUENCY_PENALTY,
                null,
                null,
                false, // no streaming for test
                Collections.emptyList()
        );

        AiChatClient client = resolveClient(testConfig, null);

        return client.sendMessage(Collections.singletonList(
                new LlmMessage("user", TEST_PROMPT)
        ));
    }

    /// Synchronous variant of {@link #testConnection} that blocks for at most
    /// {@code timeoutSeconds}.
    ///
    /// @param settings       the AI settings to test connectivity with
    /// @param timeoutSeconds the maximum seconds to wait
    /// @return a result message on success, or throws on failure
    /// @throws LlmException     if the API returns an error
    /// @throws TimeoutException if the request times out
    /// @throws InterruptedException if the thread is interrupted
    public static String testConnectionSync(AiSettings settings, long timeoutSeconds)
            throws LlmException, InterruptedException, TimeoutException {
        try {
            return testConnection(settings).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof LlmException) {
                    throw (LlmException) cause;
                }
                if (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                } else {
                    break;
                }
            }
            throw new LlmException("Connection test failed", 0, e);
        }
    }

    /// Builds an [`LlmConfig`] from the given settings.
    ///
    /// @param settings the AI settings; must not be {@code null}
    /// @return a new immutable configuration
    private static LlmConfig buildConfig(AiSettings settings) {
        return new LlmConfig(
                settings.getEndpoint(),
                settings.getApiKey(),
                settings.getModel(),
                settings.getProvider(),
                settings.getMaxTokens(),
                settings.getTemperature(),
                LlmConfig.DEFAULT_TIMEOUT,
                settings.getContextWindow(),
                settings.getMaxOutputTokens(),
                settings.getTopP(),
                settings.getPresencePenalty(),
                settings.getFrequencyPenalty(),
                settings.getSeed(),
                settings.getReasoningEffort().isEmpty() ? null : settings.getReasoningEffort(),
                settings.isStream(),
                settings.getStopSequences()
        );
    }

    /// Resolves the appropriate [`AiChatClient`] for the given configuration.
    ///
    /// If the provider is in {@link #LANGCHAIN4J_PROVIDERS}, a
    /// [`LangChain4jChatAdapter`] is built using the LangChain4j model factory.
    /// The optional tool registry is wired into the adapter for native tool-calling
    /// support.
    ///
    /// Providers not in the supported set fall back to the legacy
    /// [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient].
    ///
    /// @param config the LLM configuration; must not be {@code null}
    /// @param tools  the tool registry for tool support, or {@code null}
    ///               to disable tools
    /// @return an AiChatClient ready for use
    private static AiChatClient resolveClient(LlmConfig config, @Nullable ToolRegistry tools) {
        String provider = config.getProvider();
        AiProtocolFamily family = provider != null ? AiProtocolFamily.fromId(provider) : null;

        if (family != null) {
            if (family.isOpenaiCompatible()) {
                LangChain4jToolAdapter toolAdapter = tools != null
                        ? new LangChain4jToolAdapter(tools)
                        : null;
                return new LangChain4jChatAdapter(
                        MODEL_FACTORY.buildChatModel(config),
                        MODEL_FACTORY.buildStreamingChatModel(config),
                        toolAdapter
                );
            }
            return new UnsupportedAiChatClient(
                    "Protocol family '" + provider + "' is not yet supported for runtime chat"
            );
        }

        if (provider != null && LANGCHAIN4J_PROVIDERS.contains(provider)) {
            LangChain4jToolAdapter toolAdapter = tools != null
                    ? new LangChain4jToolAdapter(tools)
                    : null;
            return new LangChain4jChatAdapter(
                    MODEL_FACTORY.buildChatModel(config),
                    MODEL_FACTORY.buildStreamingChatModel(config),
                    toolAdapter
            );
        }
        return new LlmClient(config);
    }

    /// Small adapter used to fail honestly when a protocol family is configured
    /// in settings but the runtime chat layer does not yet support it.
    @NotNullByDefault
    private static final class UnsupportedAiChatClient implements AiChatClient {
        private final LlmException error;

        private UnsupportedAiChatClient(String message) {
            this.error = new LlmException(message, 0);
        }

        @Override
        public CompletableFuture<@Nullable String> sendMessage(java.util.List<LlmMessage> messages) {
            return CompletableFuture.failedFuture(error);
        }

        @Override
        public void sendMessageStreaming(java.util.List<LlmMessage> messages, org.jackhuang.hmcl.ai.llm.LlmStreamCallback callback) {
            callback.onError(error);
        }
    }
}
