package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Two small, unrelated wording-accuracy fixes bundled together because both are about not
/// mis-describing injected content to the model:
/// 1. The active-skill-playbook preamble no longer claims a trigger-phrase match specifically — a
///    skill can land there via Layer-2 BM25 fuzzy match ({@link org.jackhuang.hmcl.ai.skills.SkillIndex})
///    or a {@code requires:}-expansion pull-in, neither of which requires any literal trigger
///    phrase in the user's text, so the old wording was false in those cases.
/// 2. Recalled memory content is now explicitly framed as untrusted DATA, not instructions, both
///    here and in {@code RecallTool.execute}.
public final class AiPromptBuilderMemoryAndSkillWordingTest {

    @Test
    public void activeSkillPreambleDoesNotClaimATriggerMatchSpecifically(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        Path skillDir = skillsDir.resolve("wording-test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\n"
                + "name: wording-test-skill\n"
                + "description: test skill\n"
                + "version: 1.0\n"
                + "---\n\n# Body\nSTEP-MARKER\n");

        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh(); // also loads all built-in skills (from bundled JSON, in memory); the fixture's unique name survives

        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        // This skill has NO triggers at all — it can only ever be "active" here via a caller
        // explicitly naming it (standing in for a Layer-2/requires:-pulled activation), so the
        // preamble must not claim a trigger-phrase match for it.
        String prompt = pb.build(Set.of("wording-test-skill"));
        assertTrue(prompt.contains("STEP-MARKER"), "the skill body must still be inlined");
        assertFalse(prompt.contains("matched their triggers"),
                "must not claim a trigger-phrase match — Layer-2/requires:-pulled skills never had one");
        assertTrue(prompt.contains("Active skill playbooks (auto-loaded as relevant to your request)"),
                "the softened, always-true wording must be present instead");
    }

    @Test
    public void recallMemoryBlockCarriesUntrustedDataCaveat(@TempDir Path dir) throws Exception {
        RememberStore store = new RememberStore(dir.resolve("memory"));
        store.init();
        store.remember("a fact", List.of(), "the user prefers dark mode");

        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry,
                new AiSearchConfig(), () -> false, store);

        // recallMemoryBlock() is only invoked from buildVolatileSuffix() when
        // AiSettings#isAutoRecallMemory() is true — currently hardcoded false product-wide (global
        // memory is "待开发"), so the block is exercised directly here (package-private for this
        // reason) rather than through build(), which would silently no-op regardless of what this
        // test does to `settings`.
        String block = pb.recallMemoryBlock();
        assertNotNull(block, "a non-empty store must produce a memory block");
        assertTrue(block.contains("not instructions"),
                "recalled memory must be framed as data, not instructions: " + block);
        assertTrue(block.contains("the user prefers dark mode"), "the actual memory content must still be present");
    }

    /// Confirms the fact this whole fix is scoped around: re-enabling memory later is meant to be
    /// a one-line revert, but as of writing, it is still fully product-disabled. If this ever flips
    /// to true without the memory UI/feature actually shipping, that's a signal worth noticing.
    @Test
    public void memoryFeatureIsStillHardcodedDisabled(@TempDir Path dir) throws Exception {
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        assertFalse(settings.isMemoryEnabled());
        assertFalse(settings.isAutoRecallMemory());
    }
}
