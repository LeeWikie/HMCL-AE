package org.jackhuang.hmcl.ai.skills;

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.agent.AiPromptBuilder;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.tools.LoadSkillTool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Covers the built-in-skills storage migration from `SKILL.md` (Markdown, seeded to disk) to
/// `SKILL.json` (JSON, loaded straight from the classpath in memory — see
/// {@link SkillLoader#loadBuiltinSkills}) and the accompanying settings-page visibility change
/// (built-ins never appear in the user-facing skill list any more).
///
/// Spot-checks two real shipped skills end to end — one scenario-level (`dev-mode`, whose
/// deterministic per-turn injection bypasses the normal trigger/BM25 retrieval pool entirely) and
/// one operation-level (`mods-install`, reached via ordinary trigger-match retrieval and
/// `load_skill`) — to prove the whole pipeline (parse → match/rank → inject) still works
/// end-to-end against the new JSON data source, not just that {@code loadBuiltinSkills()} parses
/// without throwing.
public final class BuiltinSkillJsonMigrationTest {

    /// {@link SkillLoader#loadBuiltinSkills} must produce well-formed, built-in-flagged manifests
    /// straight from the bundled `SKILL.json` resources — the basic shape every other assertion in
    /// this class builds on.
    @Test
    public void devModeAndModsInstallLoadFromJsonWithExpectedShape() {
        List<SkillManifest> builtins = SkillLoader.loadBuiltinSkills();
        assertFalse(builtins.isEmpty(), "the built-in catalog must not be empty");

        SkillManifest devMode = findByName(builtins, "dev-mode");
        SkillManifest modsInstall = findByName(builtins, "mods-install");
        assertNotNull(devMode, "dev-mode must be present in the JSON-loaded built-in catalog");
        assertNotNull(modsInstall, "mods-install must be present in the JSON-loaded built-in catalog");

        for (SkillManifest m : List.of(devMode, modsInstall)) {
            assertTrue(m.isBuiltin(), m.getName() + " must be flagged builtin");
            assertTrue(m.isValid(), m.getName() + " must parse without errors: " + m.getErrors());
            assertNotNull(m.getBody(), m.getName() + "'s body must be eagerly loaded from its JSON's \"body\" field");
            assertFalse(m.getBody().isBlank(), m.getName() + "'s body must not be blank");
        }

        // mods-install is operation-level (bundled at "instance/mods-install" — two classpath
        // directory components); dev-mode is scenario-level ("dev-mode" — one component). Both
        // must be derived correctly from the JSON resource's own path, with no skillsDir involved.
        assertFalse(SkillLoader.isScenarioLevel(Path.of("/does/not/matter"), modsInstall),
                "mods-install is operation-level");
        assertTrue(SkillLoader.isScenarioLevel(Path.of("/does/not/matter"), devMode),
                "dev-mode is scenario-level");
    }

    /// mods-install must still be reachable through ordinary Layer-1 trigger-phrase retrieval
    /// ({@link SkillMatcher}) using a trigger phrase that lives in its JSON's `triggers` array —
    /// this is the "retrieval logic doesn't care about storage format" guarantee the migration
    /// promised, exercised against the real bundled JSON content instead of a synthetic fixture.
    @Test
    public void modsInstallIsTriggerMatchedFromTheJsonSource() {
        List<SkillManifest> builtins = SkillLoader.loadBuiltinSkills();
        List<SkillManifest> hits = SkillMatcher.match("我想装模组", builtins, 5);
        assertTrue(hits.stream().anyMatch(m -> "mods-install".equals(m.getName())),
                "'装模组' must trigger-match mods-install via its JSON-sourced triggers list");
    }

    /// End-to-end via the real tool surface: {@link LoadSkillTool} loading mods-install by name off
    /// a registry that merges the JSON-sourced built-ins must return its actual playbook body
    /// (parsed from the JSON's `body` field), not an empty/placeholder string.
    @Test
    public void modsInstallBodyIsInjectedByLoadSkillToolFromTheJsonSource(@TempDir Path dir) {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(java.util.Map.of("name", "mods-install"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("### mods-install"));
        // A distinctive line from the real mods-install SKILL.json's "body" field.
        assertTrue(result.getOutput().contains("Don't guess mod IDs/versions from memory — resolve via `search`."),
                "expected the real JSON-sourced playbook body, got: " + result.getOutput());
    }

    /// End-to-end via {@link AiPromptBuilder}: the dev-mode skill's deterministic "[Dev]"-tag
    /// injection path (which bypasses SkillMatcher/SkillIndex entirely — see
    /// {@code AiPromptBuilder#isDevModeTriggered}) must still pull in dev-mode's real body text
    /// from the JSON source, proving the migration didn't just move the file but kept the actual
    /// content byte-equivalent to what the old Markdown parse produced.
    @Test
    public void devModeBodyIsInjectedByPromptBuilderFromTheJsonSource(@TempDir Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        String suffix = pb.buildVolatileSuffix(Set.of(), "[Dev]为什么工具识别为共享？");

        assertTrue(suffix.contains("Dev mode is ACTIVE this turn"),
                "the \"[Dev]\" tag must still inject the dev-mode skill body from its new JSON source");
        // A distinctive line from the real dev-mode SKILL.json's "body" field.
        assertTrue(suffix.contains("Being honest and sparse beats being verbose and padded, every time."),
                "expected the real JSON-sourced dev-mode playbook body, got: " + suffix);
    }

    /// Mirrors the exact filter `AISettingsPage#buildSkillsTab` applies to the skills list — after
    /// the migration, built-in skills must be completely absent from it (not merely
    /// disabled/collapsed), while a genuine user-authored skill in skillsDir still shows up.
    @Test
    public void builtinSkillsAreFullyExcludedFromTheSettingsPageListFilter(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        Path userSkillDir = skillsDir.resolve("my-custom-skill");
        java.nio.file.Files.createDirectories(userSkillDir);
        java.nio.file.Files.writeString(userSkillDir.resolve("SKILL.md"), "---\n"
                + "name: my-custom-skill\n"
                + "description: a user-authored skill\n"
                + "version: 1.0\n"
                + "---\n\nBody.\n");

        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh();

        List<SkillManifest> visibleInSettings = registry.list().stream()
                .filter(s -> !s.isBuiltin())
                .toList();

        assertEquals(1, visibleInSettings.size(),
                "only the user-authored skill should be visible in the settings-page list: " + visibleInSettings);
        assertEquals("my-custom-skill", visibleInSettings.get(0).getName());
        assertTrue(registry.list().size() > visibleInSettings.size(),
                "the full registry must still contain the (now-hidden) built-in skills for retrieval/injection");
    }

    private static SkillManifest findByName(List<SkillManifest> skills, String name) {
        for (SkillManifest m : skills) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }
}
