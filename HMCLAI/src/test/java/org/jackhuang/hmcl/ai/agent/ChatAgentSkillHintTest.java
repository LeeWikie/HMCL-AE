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
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// Agent-level guarantees of the adaptive skill-matching rework (the product-level change to the
/// 0.3.0 "hit → inject the whole playbook body, sticky for the session" behaviour, per the user's
/// 2026-07-10 live-test feedback):
/// 1. A trigger hit puts a short {@code <runtime-guard type="skill_hint">} nudge — and no playbook
///    body — on the WIRE copy of that turn's user message.
/// 2. The nudge is strictly per-turn: the next turn without a hit carries no nudge (the old sticky
///    {@code activeSkills} set is gone — content the model chose to load via {@code load_skill}
///    lives on in the conversation itself instead).
/// 3. The nudge never leaks into the PERSISTED user message (wire-only, like the whole
///    {@code <turn-context>} block it rides in).
public final class ChatAgentSkillHintTest {

    /// Artificial trigger no real skill carries, so only the fixture can Layer-1-match.
    private static final String TRIGGER = "十六进制铁砧咒语";
    private static final String SKILL = "chatagent-hint-test-skill";

    /// Fake client that records the exact message list each request was built with.
    private static final class CapturingClient implements AiChatClient {
        volatile List<LlmMessage> lastRequest;

        @Override
        public CompletableFuture<String> sendMessage(List<LlmMessage> messages) {
            this.lastRequest = messages;
            return CompletableFuture.completedFuture("好的");
        }

        @Override
        public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    public void skillHintIsPerTurnWireOnlyAndBodyFree(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        Path skillDir = skillsDir.resolve(SKILL);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\n"
                + "name: " + SKILL + "\n"
                + "description: chat agent hint test skill\n"
                + "version: 1.0\n"
                + "triggers: " + TRIGGER + "\n"
                + "---\n\nHINT-TEST-BODY-MARKER\n");
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh();

        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder promptBuilder = new AiPromptBuilder(settings, new ToolRegistry(), registry,
                new AiSearchConfig());
        CapturingClient client = new CapturingClient();
        AiSession session = new AiSession();
        ChatAgent agent = new ChatAgent(client, session, settings, promptBuilder);
        try {
            // Turn 1: trigger hit — the wire copy of THIS turn's user message carries the nudge.
            agent.send("帮我看看" + TRIGGER + "怎么用").join();
            String wireUser1 = client.lastRequest.get(client.lastRequest.size() - 1).getContent();
            assertTrue(wireUser1.contains("<runtime-guard type=\"skill_hint\">"),
                    "a trigger hit must inject the skill_hint nudge into the turn's wire message");
            assertTrue(wireUser1.contains("- " + SKILL), "the nudge must name the matched skill");
            assertFalse(wireUser1.contains("HINT-TEST-BODY-MARKER"),
                    "a trigger hit must NOT inject the playbook body any more");
            assertFalse(session.getMessages().get(0).getContent().contains("skill_hint"),
                    "the persisted user message must stay clean — the nudge is wire-only");

            // Turn 2: no trigger hit — no nudge. The old sticky behaviour would have re-attached
            // the turn-1 match here; the nudge must not survive its own turn.
            agent.send("zzqqxxplaceholder").join();
            String wireUser2 = client.lastRequest.get(client.lastRequest.size() - 1).getContent();
            assertFalse(wireUser2.contains("skill_hint"),
                    "the nudge must be per-turn — a later, unrelated message must not re-carry it");
        } finally {
            agent.shutdown();
        }
    }
}
