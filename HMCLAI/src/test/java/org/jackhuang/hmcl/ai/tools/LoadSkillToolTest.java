/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ai.tools;

import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers the two additive `load_skill` fixes: batch loading via `names` (fix for a trace where a
/// model needing several skills fell back to `glob()` + repeated `read()` instead of ever calling
/// `load_skill`), and the no-args discovery/list mode. The pre-existing single-name path is also
/// re-verified end to end to prove neither addition changed it.
///
/// NOTE: {@link SkillRegistry#refresh()} also loads all BUILT-IN skills (from bundled JSON, in
/// memory — see {@code SkillLoader#loadBuiltinSkills}), so fixture skill names below are
/// deliberately unique and prefixed to avoid colliding with (or being confused for) a real shipped
/// skill — mirroring the same precaution `AiPromptBuilderSkillInjectionTest` takes. This test
/// intentionally does NOT assert anything about built-in skills' content.
public final class LoadSkillToolTest {

    private static void writeSkill(Path skillsDir, String relativeDir, String name, String requires, String body) throws IOException {
        Path d = skillsDir.resolve(relativeDir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("SKILL.md"), "---\n"
                + "name: " + name + "\n"
                + "description: fixture skill " + name + "\n"
                + "version: 1.0\n"
                + (requires.isEmpty() ? "" : "requires: " + requires + "\n")
                + "---\n\n" + body + "\n");
    }

    private static SkillRegistry newRegistry(Path skillsDir) {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh(); // also loads all built-in skills (from bundled JSON, in memory); fixtures' unique names survive alongside them
        return registry;
    }

    // ---- (existing) single-name behaviour, unchanged ----------------------------------------

    @Test
    void singleNameLoadsBodyUnchanged(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-solo", "test-load-skill-solo", "", "SOLO-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of("name", "test-load-skill-solo"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("### test-load-skill-solo"));
        assertTrue(result.getOutput().contains("SOLO-BODY-MARKER"));
    }

    @Test
    void singleNameStillExpandsRequires(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-scenario", "test-load-skill-scenario",
                "test-load-skill-child", "SCENARIO-BODY-MARKER");
        writeSkill(skillsDir, "ops/test-load-skill-child", "test-load-skill-child", "", "CHILD-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of("name", "test-load-skill-scenario"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("SCENARIO-BODY-MARKER"), "scenario body missing");
        assertTrue(result.getOutput().contains("CHILD-BODY-MARKER"), "requires:-expanded child body missing");
    }

    @Test
    void requiresExpansionWorksAgainstTheRealSeededBuiltinCatalog(@TempDir Path dir) {
        // Regression guard: fixture-only coverage above uses matching name/requires strings by
        // construction and would never catch a real shipped scenario skill's `requires:` using a
        // stale "<domain>/<slug>" path that no longer equals the referenced skill's plain `name:`
        // field. Exercise the actual built-in `diagnose-crash` skill, whose `requires:` names
        // several operation-level playbooks it orchestrates.
        LoadSkillTool tool = new LoadSkillTool(newRegistry(dir.resolve("skills")));

        ToolResult result = tool.execute(Map.of("name", "diagnose-crash"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("### diagnose-crash"), "scenario body missing");
        assertTrue(result.getOutput().contains("### mods-toggle-and-remove"), "requires:-expanded child missing");
        assertTrue(result.getOutput().contains("### java-runtime"), "requires:-expanded child missing");
        assertTrue(result.getOutput().contains("### mods-install"), "requires:-expanded child missing");
        assertTrue(result.getOutput().contains("### launch-and-verify"), "requires:-expanded child missing");
    }

    @Test
    void unknownSingleNameFailsWithExistingMessageStyle(@TempDir Path dir) {
        LoadSkillTool tool = new LoadSkillTool(newRegistry(dir.resolve("skills")));

        ToolResult result = tool.execute(Map.of("name", "test-load-skill-does-not-exist"));

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("No skill named"), "unexpected message: " + result.getError());
    }

    @Test
    void disabledSingleNameFailsWithExistingMessageStyle(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-disabled", "test-load-skill-disabled", "", "BODY");
        SkillRegistry registry = newRegistry(skillsDir);
        registry.disable("test-load-skill-disabled");
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(Map.of("name", "test-load-skill-disabled"));

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("disabled"), "unexpected message: " + result.getError());
    }

    @Test
    void blankNameFailsLikeMissingParameter(@TempDir Path dir) {
        LoadSkillTool tool = new LoadSkillTool(newRegistry(dir.resolve("skills")));

        ToolResult result = tool.execute(Map.of("name", ""));

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("Missing required parameter"), "unexpected message: " + result.getError());
    }

    // ---- (i) batch loading via `names` --------------------------------------------------------

    @Test
    void namesArrayBatchReturnsBothBodiesConcatenated(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-alpha", "test-load-skill-alpha", "", "ALPHA-BODY-MARKER");
        writeSkill(skillsDir, "test-load-skill-beta", "test-load-skill-beta", "", "BETA-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of("names", List.of("test-load-skill-alpha", "test-load-skill-beta")));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("ALPHA-BODY-MARKER"), "alpha body missing: " + out);
        assertTrue(out.contains("BETA-BODY-MARKER"), "beta body missing: " + out);
        assertTrue(out.indexOf("ALPHA-BODY-MARKER") < out.indexOf("BETA-BODY-MARKER"),
                "alpha should come before beta, in call order");
    }

    @Test
    void nameAndNamesAreUnionedWithDuplicatesSkipped(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-alpha", "test-load-skill-alpha", "", "ALPHA-BODY-MARKER");
        writeSkill(skillsDir, "test-load-skill-beta", "test-load-skill-beta", "", "BETA-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of(
                "name", "test-load-skill-alpha",
                "names", List.of("test-load-skill-alpha", "test-load-skill-beta")));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        String out = result.getOutput();
        int firstAlpha = out.indexOf("ALPHA-BODY-MARKER");
        int lastAlpha = out.lastIndexOf("ALPHA-BODY-MARKER");
        assertEquals(firstAlpha, lastAlpha, "the exact-duplicate 'test-load-skill-alpha' request must appear only once: " + out);
        assertTrue(out.contains("BETA-BODY-MARKER"));
    }

    @Test
    void batchWithOneUnknownNameStillReturnsTheKnownSkills(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-alpha", "test-load-skill-alpha", "", "ALPHA-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of(
                "names", List.of("test-load-skill-alpha", "test-load-skill-nonexistent")));

        assertTrue(result.isSuccess(), "a typo in one of several requested names shouldn't fail the whole batch");
        String out = result.getOutput();
        assertTrue(out.contains("ALPHA-BODY-MARKER"), "the resolvable skill must still come through: " + out);
        assertTrue(out.contains("No skill named 'test-load-skill-nonexistent'"), "unresolved name should be noted inline: " + out);
    }

    @Test
    void bodyMaxCharsIsPerSkillNotSharedAcrossBatch(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        // Each body is under the 4000-char per-skill cap on its own, but the two COMBINED
        // (~7900 chars, plus headers) are well over it — if a regression made the cap a shared
        // batch budget instead of a per-skill one, the second skill's body would come back
        // truncated (or missing) even though its own body alone would never trip the cap.
        String bodyA = "A".repeat(3900);
        String bodyB = "B".repeat(3900);
        writeSkill(skillsDir, "test-load-skill-long-a", "test-load-skill-long-a", "", bodyA);
        writeSkill(skillsDir, "test-load-skill-long-b", "test-load-skill-long-b", "", bodyB);
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of(
                "names", List.of("test-load-skill-long-a", "test-load-skill-long-b")));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        String out = result.getOutput();
        assertFalse(out.contains("truncated"), "no skill should be truncated by a shared batch budget");
        assertTrue(out.contains(bodyA), "expected skill a's full, untruncated body to be present");
        assertTrue(out.contains(bodyB), "expected skill b's full, untruncated body to be present — "
                + "would fail if BODY_MAX_CHARS were a shared budget across the batch instead of per-skill");
    }

    @Test
    void batchDedupesASkillRequiredByMultipleRequestedNames(@TempDir Path dir) throws Exception {
        // Mirrors the real shipped catalog: several scenario skills `requires:` the same
        // operation-level skill (e.g. diagnose-crash and fix-download-and-network both
        // `requires: java-runtime`) — a naive per-name expansion would duplicate its body once
        // per requesting scenario instead of once per batch call.
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-shared-child", "test-load-skill-shared-child", "",
                "SHARED-CHILD-BODY-MARKER");
        writeSkill(skillsDir, "test-load-skill-scenario-a", "test-load-skill-scenario-a",
                "test-load-skill-shared-child", "SCENARIO-A-BODY-MARKER");
        writeSkill(skillsDir, "test-load-skill-scenario-b", "test-load-skill-scenario-b",
                "test-load-skill-shared-child", "SCENARIO-B-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of(
                "names", List.of("test-load-skill-scenario-a", "test-load-skill-scenario-b")));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("SCENARIO-A-BODY-MARKER"));
        assertTrue(out.contains("SCENARIO-B-BODY-MARKER"));
        int first = out.indexOf("SHARED-CHILD-BODY-MARKER");
        int last = out.lastIndexOf("SHARED-CHILD-BODY-MARKER");
        assertTrue(first >= 0, "shared required skill body should appear at least once: " + out);
        assertEquals(first, last, "a skill required by BOTH requested scenarios must appear only once: " + out);
    }

    @Test
    void batchWhereEveryNameFailsReturnsFailureNotSuccess(@TempDir Path dir) {
        LoadSkillTool tool = new LoadSkillTool(newRegistry(dir.resolve("skills")));

        ToolResult result = tool.execute(Map.of(
                "names", List.of("test-load-skill-bogus-1", "test-load-skill-bogus-2")));

        assertFalse(result.isSuccess(), "a batch where NOTHING resolved must signal failure, not success");
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("No skill named 'test-load-skill-bogus-1'"));
        assertTrue(result.getError().contains("No skill named 'test-load-skill-bogus-2'"));
    }

    // ---- (ii) no-args discovery/list mode -----------------------------------------------------

    @Test
    void noParamsListsEnabledSkillsAcrossBothLevels(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        writeSkill(skillsDir, "test-load-skill-alpha", "test-load-skill-alpha", "", "ALPHA-BODY-MARKER");
        writeSkill(skillsDir, "ops/test-load-skill-beta", "test-load-skill-beta", "", "BETA-BODY-MARKER");
        LoadSkillTool tool = new LoadSkillTool(newRegistry(skillsDir));

        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("test-load-skill-alpha"), "scenario-level fixture should be listed: " + out);
        assertTrue(out.contains("test-load-skill-beta"), "operation-level fixture should be listed too: " + out);
        // Discovery mode must not have pulled in the (large) playbook bodies themselves.
        assertFalse(out.contains("ALPHA-BODY-MARKER"));
        assertFalse(out.contains("BETA-BODY-MARKER"));
    }

    @Test
    void emptyNamesArrayFailsLikeMissingParameter(@TempDir Path dir) {
        LoadSkillTool tool = new LoadSkillTool(newRegistry(dir.resolve("skills")));

        ToolResult result = tool.execute(Map.of("names", List.of()));

        assertFalse(result.isSuccess(), "an explicitly-empty names array is not the same as omitting both params");
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("Missing required parameter"), "unexpected message: " + result.getError());
    }
}
