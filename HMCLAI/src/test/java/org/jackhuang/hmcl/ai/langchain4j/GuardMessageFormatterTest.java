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

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link GuardMessageFormatter} — the identity channel every runtime-guard
/// message rides (borrow-list A3).
public final class GuardMessageFormatterTest {

    @Test
    public void wrapProducesTheTaggedEnvelope() {
        String wrapped = GuardMessageFormatter.wrap("loop_warning", "stop repeating yourself");
        assertTrue(wrapped.startsWith("<runtime-guard type=\"loop_warning\">"),
                "the opening tag must carry the type attribute: " + wrapped);
        assertTrue(wrapped.endsWith("</runtime-guard>"), "the tag must be closed: " + wrapped);
        assertTrue(wrapped.contains("stop repeating yourself"), "the guard text must be inside the tag");
    }

    @Test
    public void guardMessageIsAUserRoleMessageWithTheWrappedText() {
        // role=user is deliberate — see GuardMessageFormatter's class doc for the evaluation of
        // mid-history system-role insertion (Anthropic hoisting / OpenAI-compatible rejection).
        UserMessage msg = GuardMessageFormatter.guardMessage("verify_on_stop", "verify before finishing");
        assertEquals(GuardMessageFormatter.wrap("verify_on_stop", "verify before finishing"),
                msg.singleText(), "the message content must be exactly the wrapped text");
    }

    @Test
    public void tagConstantMatchesTheWrappedOutput() {
        // AiPromptBuilder teaches the tag by name via this constant — the wrap output must use it.
        assertTrue(GuardMessageFormatter.wrap("t", "x").contains("<" + GuardMessageFormatter.TAG + " "));
    }
}
