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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link LangChain4jChatAdapter} covering message
/// conversion and error wrapping.
public final class LangChain4jChatAdapterTest {

    /// Verifies that an LlmException is created with a network error
    /// status code for generic exceptions.
    @Test
    public void testWrapErrorGenericException() {
        RuntimeException cause = new RuntimeException("Something went wrong");
        LlmException result = LangChain4jChatAdapter.wrapError(cause);

        assertNotNull(result);
        assertEquals(0, result.getStatusCode());
        assertTrue(result.getMessage().contains("Something went wrong"));
    }

    /// Verifies that an existing LlmException is returned unchanged.
    @Test
    public void testWrapErrorAlreadyLlmException() {
        LlmException original = new LlmException("Auth error", 401);
        LlmException result = LangChain4jChatAdapter.wrapError(original);

        assertSame(original, result);
    }

    /// Verifies that converting a system-role message produces a
    /// SystemMessage.
    @Test
    public void testConvertMessagesSystemRole() {
        List<LlmMessage> messages = Collections.singletonList(
                new LlmMessage("system", "You are a helpful assistant.")
        );

        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(messages);

        assertEquals(1, result.size());
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertEquals("You are a helpful assistant.",
                ((SystemMessage) result.get(0)).text());
    }

    /// Verifies that converting a user-role message produces a
    /// UserMessage.
    @Test
    public void testConvertMessagesUserRole() {
        List<LlmMessage> messages = Collections.singletonList(
                new LlmMessage("user", "Hello!")
        );

        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(messages);

        assertEquals(1, result.size());
        assertInstanceOf(UserMessage.class, result.get(0));
        assertEquals("Hello!",
                ((UserMessage) result.get(0)).singleText());
    }

    /// Verifies that converting an assistant-role message produces an
    /// AiMessage.
    @Test
    public void testConvertMessagesAssistantRole() {
        List<LlmMessage> messages = Collections.singletonList(
                new LlmMessage("assistant", "Hi, how can I help?")
        );

        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(messages);

        assertEquals(1, result.size());
        assertInstanceOf(AiMessage.class, result.get(0));
        assertEquals("Hi, how can I help?",
                ((AiMessage) result.get(0)).text());
    }

    /// Verifies that an unknown role defaults to UserMessage.
    @Test
    public void testConvertMessagesUnknownRole() {
        List<LlmMessage> messages = Collections.singletonList(
                new LlmMessage("tool", "Tool result text")
        );

        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(messages);

        assertEquals(1, result.size());
        assertInstanceOf(UserMessage.class, result.get(0));
    }

    /// Verifies that multiple messages of different roles convert correctly.
    @Test
    public void testConvertMessagesMultiple() {
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "System prompt"),
                new LlmMessage("user", "User question"),
                new LlmMessage("assistant", "Assistant answer")
        );

        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(messages);

        assertEquals(3, result.size());
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertInstanceOf(UserMessage.class, result.get(1));
        assertInstanceOf(AiMessage.class, result.get(2));
    }

    /// Verifies that an empty message list converts to an empty list.
    @Test
    public void testConvertMessagesEmpty() {
        List<ChatMessage> result = LangChain4jChatAdapter.convertMessages(
                Collections.emptyList());

        assertTrue(result.isEmpty());
    }

    /// Verifies that a model-based adapter can be built from LlmConfig
    /// using the factory.
    @Test
    public void testAdapterConstruction() {
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
        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(
                factory.buildChatModel(config),
                factory.buildStreamingChatModel(config)
        );

        assertNotNull(adapter);
    }
}
