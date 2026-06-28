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

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link ChatAgentFactory#build}.
///
/// NOTE: the previous connection-test cases (testTestConnection*) were removed when the
/// transport layer migrated from the legacy `LlmClient` to the LangChain4j adapter. They
/// asserted `LlmClient`-specific behaviour (a `custom-mock` provider, HTTP status codes
/// surfaced on `LlmException`, and `TimeoutException`) that no longer exists under the new
/// provider-profile routing, so they could not be made to pass without testing removed code.
public final class ChatAgentFactoryTest {

    /// Verifies that {@link ChatAgentFactory#build} creates a ChatAgent bound to the
    /// supplied session and exposes the settings it was built with.
    @Test
    public void testBuildCreatesFunctionalAgent() throws IOException {
        AiSession session = new AiSession();
        ToolRegistry tools = new ToolRegistry();

        Path dir = Files.createTempDirectory("hmcl-ai-factory-build-");
        try {
            AiSettings settings = new AiSettings(dir);
            AiPromptBuilder promptBuilder = new AiPromptBuilder(settings, tools,
                    new SkillRegistry(), new AiSearchConfig());
            ChatAgent agent = ChatAgentFactory.build(settings, session, tools, promptBuilder);

            assertNotNull(agent);
            assertSame(session, agent.getSession());
            assertNotNull(agent.getSettings(), "ChatAgent should expose its settings");
            assertSame(settings, agent.getSettings(), "ChatAgent.getSettings() should return the same instance");
        } finally {
            cleanup(dir);
        }
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) {
        }
    }
}
