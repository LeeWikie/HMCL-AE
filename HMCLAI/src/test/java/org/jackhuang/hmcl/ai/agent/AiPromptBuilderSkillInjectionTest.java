package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSettings;
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

/// End-to-end (model-free) checks of the auto-skill-injection prompt path:
/// matchSkills() honours triggers + the settings toggle, and build(activeSkills)
/// actually inlines the matched playbook body into the system prompt.
///
/// NOTE: SkillRegistry.refresh() seeds all BUILT-IN skills into the directory, so these
/// tests use a custom skill name and an artificial trigger phrase that no built-in skill
/// carries — otherwise the seeded real skills would shadow the fixtures.
public final class AiPromptBuilderSkillInjectionTest {

    /// Artificial trigger no real skill uses, so only the fixture can match.
    private static final String TRIGGER = "波尔卡测试舞步";
    private static final String SKILL = "test-injection-skill";

    private static void writeSkill(Path skillsDir, String name, String triggers, String body) throws Exception {
        Path d = skillsDir.resolve(name);
        Files.createDirectories(d);
        Files.writeString(d.resolve("SKILL.md"), "---\n"
                + "name: " + name + "\n"
                + "description: test skill " + name + "\n"
                + "version: 1.0\n"
                + (triggers.isEmpty() ? "" : "triggers: " + triggers + "\n")
                + "---\n\n" + body + "\n");
    }

    private static SkillRegistry registryWithFixture(Path skillsDir, String triggers, String body) throws Exception {
        writeSkill(skillsDir, SKILL, triggers, body);
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh(); // also seeds built-ins; the fixture's unique name survives
        return registry;
    }

    @Test
    public void matchedSkillBodyIsInlinedIntoThePrompt(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER,
                "# Test playbook\nUNIQUE-TEST-STEP-1");
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        List<String> matched = pb.matchSkills("请示范一下" + TRIGGER + "怎么跳");
        assertEquals(List.of(SKILL), matched);

        String prompt = pb.build(Set.copyOf(matched));
        assertTrue(prompt.contains("Active skill playbooks"), "injection header missing");
        assertTrue(prompt.contains("UNIQUE-TEST-STEP-1"), "playbook body must be inlined");

        // Without active skills the body must NOT be in the prompt (only the index line is).
        String plain = pb.build();
        assertFalse(plain.contains("UNIQUE-TEST-STEP-1"));
    }

    @Test
    public void toggleOffDisablesMatching(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER, "body");
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        settings.autoSkillInjectionProperty().set(false);
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        assertTrue(pb.matchSkills("表演" + TRIGGER).isEmpty());
    }

    @Test
    public void disabledSkillIsNeverMatchedOrInjected(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER, "SECRET-BODY");
        registry.disable(SKILL);
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        assertTrue(pb.matchSkills("表演" + TRIGGER).isEmpty());
        // even a stale active-set entry must not leak a disabled skill's body
        assertFalse(pb.build(Set.of(SKILL)).contains("SECRET-BODY"));
    }
}
