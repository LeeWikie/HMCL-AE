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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Wording locks for the third-wave prompt-text changes on {@link AiPromptBuilder}:
/// - T18: hand-editing file-only launcher settings (theme/background) is taught as "may not take
///   effect / gets overwritten, tell the user to restart and verify", not as a plain "just edit the JSON".
/// - T19: the per-turn Runtime context block is explicitly framed as a start-of-turn SNAPSHOT that a
///   live tool return overrides, so the model doesn't treat a stale selected-instance/account as fact.
/// - T23: the "don't rename the .jar suffix by hand — use mods_toggle" warning is now stated ONCE
///   (in Conventions) with the other two occurrences reduced to short references.
public final class AiPromptBuilderWordingWave3Test {

    private static AiPromptBuilder builder(Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        return new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /// T18 — editing theme/background JSON directly must carry the "may not take effect + gets
    /// overwritten + restart to verify" warning, not just "use read/edit/write on the JSON".
    @Test
    public void themeEditWarnsItMayNotTakeEffectAndNeedsRestart(@TempDir Path dir) throws Exception {
        String prefix = builder(dir).buildStablePrefix();

        assertTrue(prefix.contains("theme/background"),
                "the file-only launcher-settings warning must name theme/background specifically");
        assertTrue(prefix.contains("may NOT take effect in the already-running launcher"),
                "the model must be told a hand-edited launcher setting may not take effect immediately");
        assertTrue(prefix.contains("silently overwritten"),
                "the model must be warned the whole-config exit rewrite can overwrite the edit");
        assertTrue(prefix.contains("do NOT claim the change is applied"),
                "the model must not report success for these edits");
        assertTrue(prefix.contains("full HMCL restart") && prefix.contains("confirm it worked"),
                "the next step must be: tell the user to restart HMCL and confirm it took effect");
    }

    /// T19 — the Runtime context block must be labelled a start-of-turn snapshot that a live tool
    /// return overrides.
    @Test
    public void runtimeContextIsLabelledAStartOfTurnSnapshot(@TempDir Path dir) throws Exception {
        String suffix = builder(dir).buildVolatileSuffix(Set.of());

        assertTrue(suffix.contains("Runtime context (a SNAPSHOT taken at the START of this turn"),
                "the runtime context header must declare it is a start-of-turn snapshot");
        assertTrue(suffix.contains("selected instance") && suffix.contains("active account"),
                "the caveat must call out instance/account switches specifically");
        assertTrue(suffix.contains("a tool's live return is the source of truth, not these values"),
                "the model must be told a live tool return wins over the snapshot");
    }

    /// T23 — the mods-suffix / mods_toggle warning is stated once (Conventions) and referenced twice.
    @Test
    public void modsToggleWarningIsDeduplicatedToOneCanonicalStatement(@TempDir Path dir) throws Exception {
        String prefix = builder(dir).buildStablePrefix();

        assertEquals(1, countOccurrences(prefix, "never rename the file yourself"),
                "the canonical 'never rename the .jar suffix' warning must appear exactly ONCE "
                        + "(in Conventions), not be restated in TOOLS_GUIDE and DISCIPLINE too");
        assertEquals(2, countOccurrences(prefix, "see the mods/suffix rule under Conventions"),
                "the two former restatements (shell golden rule + discipline rule 2) must now be "
                        + "short references pointing back to the single canonical statement");
        // The canonical statement itself is still the actionable one (names the tool).
        assertTrue(prefix.contains("Toggle it with instance(action=mods_toggle) — never rename the file yourself"),
                "the one surviving statement must still name the mods_toggle action");
    }
}
