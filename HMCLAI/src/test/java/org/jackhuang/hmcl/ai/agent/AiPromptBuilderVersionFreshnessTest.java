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
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Locks in the two-layer fix for the model mislabelling real, released Minecraft versions as
/// "snapshots"/"unusual" just because they postdate its training cutoff (a tester actually hit
/// this — a genuine stable version reported as "probably a snapshot"):
/// 1. A STATIC version/knowledge-freshness guardrail must live in the cacheable stable prefix.
/// 2. The current date must be injected into the VOLATILE runtime context — never the stable
///    prefix (a date that changes daily would defeat the whole prefix-hash cache).
public final class AiPromptBuilderVersionFreshnessTest {

    private static AiPromptBuilder builder(Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        return new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());
    }

    /// The freshness guardrail must be in the STABLE prefix (static, so cacheable) and carry its
    /// key claims: memory is stale on versions, and don't call unknown versions "snapshots".
    @Test
    public void freshnessGuardrailLivesInStablePrefix(@TempDir Path dir) throws Exception {
        String prefix = builder(dir).buildStablePrefix();

        assertTrue(prefix.contains("training"),
                "the guardrail must state the model's training data has a cutoff");
        assertTrue(prefix.contains("snapshot"),
                "the guardrail must warn against calling unrecognised versions 'snapshots'");
        assertTrue(prefix.contains("the launcher's data is AUTHORITATIVE"),
                "the guardrail must say the launcher's data — not the model's memory — is authoritative");
        assertTrue(prefix.contains("not become a snapshot just because it shipped after your training cutoff"),
                "the guardrail must spell out the exact failure mode: a real release is not a snapshot "
                        + "just because it postdates the cutoff");
    }

    /// The stable prefix must NOT contain the current date — a date changes daily and would
    /// invalidate the prefix-hash cache for the ENTIRE prompt every day if it leaked in here.
    @Test
    public void stablePrefixDoesNotContainCurrentDate(@TempDir Path dir) throws Exception {
        String prefix = builder(dir).buildStablePrefix();

        assertFalse(prefix.contains(LocalDate.now().toString()),
                "the current date must NOT appear in the cacheable stable prefix — it belongs in the "
                        + "volatile runtime context, otherwise the prefix-hash cache breaks every day");
        assertFalse(prefix.contains("Current date"),
                "even the 'Current date' label must not be in the stable prefix");
    }

    /// The current date must be injected into the volatile runtime context (via buildVolatileSuffix).
    @Test
    public void currentDateIsInjectedIntoVolatileSuffix(@TempDir Path dir) throws Exception {
        String suffix = builder(dir).buildVolatileSuffix(Set.of());

        assertTrue(suffix.contains("Current date: " + LocalDate.now()),
                "the volatile runtime context must carry today's ISO date so the model knows 'now'");
    }
}
