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

import org.jackhuang.hmcl.ai.LlmConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link LangChain4jModelFactory} covering the endpoint
/// to base URL extraction and model building without real network calls.
public final class LangChain4jModelFactoryTest {

    /// Verifies that the chat completions path suffix is correctly stripped.
    @Test
    public void testExtractBaseUrlStripsChatPath() {
        assertEquals("https://api.openai.com/v1",
                LangChain4jModelFactory.extractBaseUrl("https://api.openai.com/v1/chat/completions"));
    }

    /// Verifies that URLs without the suffix are returned unchanged.
    @Test
    public void testExtractBaseUrlNoSuffix() {
        assertEquals("https://api.example.com/v1",
                LangChain4jModelFactory.extractBaseUrl("https://api.example.com/v1"));
    }

    /// Verifies that a custom non-standard endpoint path is returned as-is.
    @Test
    public void testExtractBaseUrlCustomPath() {
        assertEquals("https://custom.provider.io/api/generate",
                LangChain4jModelFactory.extractBaseUrl("https://custom.provider.io/api/generate"));
    }

    /// Verifies that a chat model can be built from a minimal LlmConfig
    /// without throwing.
    @Test
    public void testBuildChatModelFromConfig() {
        LlmConfig config = new LlmConfig(
                "https://api.openai.com/v1/chat/completions",
                "sk-test-key",
                "gpt-4o-mini",
                "openai",
                4096,
                0.7,
                Duration.ofSeconds(30)
        );

        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        assertNotNull(factory.buildChatModel(config));
    }

    /// Verifies that a streaming chat model can be built from a minimal
    /// LlmConfig without throwing.
    @Test
    public void testBuildStreamingChatModelFromConfig() {
        LlmConfig config = new LlmConfig(
                "https://api.openai.com/v1/chat/completions",
                "sk-test-key",
                "gpt-4o-mini",
                "openai",
                4096,
                0.7,
                Duration.ofSeconds(30)
        );

        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        assertNotNull(factory.buildStreamingChatModel(config));
    }

    /// Verifies that models can be built with logging enabled.
    @Test
    public void testBuildWithLogging() {
        LlmConfig config = new LlmConfig(
                "https://api.openai.com/v1/chat/completions",
                "sk-test-key",
                "gpt-4o-mini",
                "openai",
                4096,
                0.7,
                Duration.ofSeconds(30)
        );

        LangChain4jModelFactory factory = new LangChain4jModelFactory(true, true);
        assertNotNull(factory.buildChatModel(config));
        assertNotNull(factory.buildStreamingChatModel(config));
    }
}
