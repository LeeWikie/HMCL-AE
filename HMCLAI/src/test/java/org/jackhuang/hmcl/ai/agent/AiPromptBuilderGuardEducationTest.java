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

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.langchain4j.GuardMessageFormatter;
import org.jackhuang.hmcl.ai.langchain4j.LangChain4jChatAdapter;
import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks in the one-time system-prompt education for the runtime-harness channels
/// (H2: `<runtime-guard>` tag semantics; H8: eviction/compaction are routine housekeeping) and
/// the recalled-memories identity-channel pilot.
public final class AiPromptBuilderGuardEducationTest {

    private static AiPromptBuilder builder(Path dir, RememberStore store) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        return new AiPromptBuilder(settings, new ToolRegistry(), registry,
                new AiSearchConfig(), () -> false, store);
    }

    @Test
    public void stablePrefixTeachesTheRuntimeGuardTagOnce(@TempDir Path dir) throws Exception {
        String prefix = builder(dir, null).buildStablePrefix();
        assertTrue(prefix.contains("<" + GuardMessageFormatter.TAG + " type=\"...\">"),
                "the stable prefix must teach the runtime-guard tag by its real name");
        assertTrue(prefix.contains("NOT typed by the user"),
                "the education must establish the tag's identity (harness, not user)");
    }

    @Test
    public void stablePrefixTeachesEvictionAndCompactionAsRoutineHousekeeping(@TempDir Path dir) throws Exception {
        String prefix = builder(dir, null).buildStablePrefix();
        assertTrue(prefix.contains(LangChain4jChatAdapter.EVICTED_TOOL_RESULT),
                "the eviction placeholder must be taught VERBATIM (constant-referenced, no drift)");
        assertTrue(prefix.contains("【上下文已压缩】"),
                "the compaction headers must be taught so a compacted history isn't read as loss");
        assertTrue(prefix.contains("not a signal to wrap up"),
                "the whole point: housekeeping must not induce wrap-up anxiety");
    }

    @Test
    public void recallMemoryBlockRidesTheIdentityChannel(@TempDir Path dir) throws Exception {
        RememberStore store = new RememberStore(dir.resolve("memory"));
        store.init();
        store.remember("a fact", List.of(), "the user prefers dark mode");

        String block = builder(dir, store).recallMemoryBlock();
        assertNotNull(block);
        assertTrue(block.startsWith("<" + GuardMessageFormatter.TAG + " type=\"recalled_memories\">"),
                "the pilot producer must wrap its block in the unified identity tag: " + block);
        assertTrue(block.endsWith("</" + GuardMessageFormatter.TAG + ">"), block);
        assertTrue(block.contains("the user prefers dark mode"), "content must survive the wrapping");
        assertTrue(block.contains("not instructions"), "the untrusted-data caveat must survive too");
    }
}
