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

/// End-to-end (model-free) checks of the adaptive skill-matching prompt path (product-level
/// rework of the 0.3.0 "hit → inject the whole playbook body" behaviour, per the user's
/// 2026-07-10 live-test feedback): matchSkills() honours triggers + the settings toggle,
/// a match injects a short <runtime-guard type="skill_hint"> nudge (NOT the body — the model
/// reads it via load_skill), and the stable prefix carries the full skill tree.
///
/// NOTE: SkillRegistry.refresh() also loads all BUILT-IN skills (from bundled JSON, in memory —
/// see SkillLoader#loadBuiltinSkills), so these tests use a custom skill name and an artificial
/// trigger phrase that no built-in skill carries — otherwise the real built-ins would shadow the
/// fixtures.
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
        registry.refresh(); // also loads all built-in skills (from bundled JSON, in memory); the fixture's unique name survives
        return registry;
    }

    @Test
    public void matchedSkillInjectsANudgeNotTheFullBody(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER,
                "# Test playbook\nUNIQUE-TEST-STEP-1");
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        // Exact-list equality isn't asserted here: the query may ALSO pick up a Layer-2 (BM25)
        // fuzzy match among the real seeded built-in skills — that's expected now that matching
        // is two-layered, not a regression. What must hold is Layer 1's exact-trigger hit winning
        // a slot, and it winning it FIRST (Layer 1 always precedes Layer 2 fills).
        List<String> matched = pb.matchSkills("请示范一下" + TRIGGER + "怎么跳");
        assertFalse(matched.isEmpty());
        assertEquals(SKILL, matched.get(0), "the exact trigger-phrase hit must win a slot, and win it first");

        // A match injects a short skill_hint nudge into the volatile suffix — never the body.
        String suffix = pb.buildVolatileSuffix(Set.copyOf(matched));
        assertTrue(suffix.contains("<runtime-guard type=\"" + AiPromptBuilder.SKILL_HINT_GUARD_TYPE + "\">"),
                "the nudge must ride the runtime-guard identity channel");
        assertTrue(suffix.contains("- " + SKILL), "the nudge must name the matched skill");
        assertTrue(suffix.contains("test skill " + SKILL), "the nudge must carry the one-line brief");
        assertTrue(suffix.contains("load_skill"), "the nudge must state the default action (load first)");
        assertFalse(suffix.contains("UNIQUE-TEST-STEP-1"),
                "a match must NOT inject the playbook body — the model reads it via load_skill");

        // The stable prefix must carry the skill tree, listing this skill with its brief and
        // teaching the load_skill mechanism + the skill_hint semantics.
        String prefix = pb.buildStablePrefix();
        assertTrue(prefix.contains(SKILL + ": test skill " + SKILL), "the tree must list name + brief");
        assertTrue(prefix.contains("load_skill"), "the tree block must teach load_skill");
        assertTrue(prefix.contains(AiPromptBuilder.SKILL_HINT_GUARD_TYPE),
                "the tree block must teach what a skill_hint notice means");
        assertFalse(prefix.contains("UNIQUE-TEST-STEP-1"), "no playbook body may live in the stable prefix");

        // Without matched skills there is no nudge (the prefix's education line legitimately
        // mentions the tag name, so check the volatile suffix), and still no body anywhere.
        assertFalse(pb.buildVolatileSuffix(Set.of()).contains(AiPromptBuilder.SKILL_HINT_GUARD_TYPE));
        assertFalse(pb.build().contains("UNIQUE-TEST-STEP-1"));
    }

    @Test
    public void toggleOffDisablesMatching(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER, "body");
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        settings.autoSkillInjectionProperty().set(false);
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        assertTrue(pb.matchSkills("表演" + TRIGGER).isEmpty());
        // New toggle semantics: off = no nudge is ever injected, but the skill tree stays in the
        // stable prefix, so the model can still self-serve skills via load_skill.
        assertTrue(pb.buildStablePrefix().contains(SKILL + ": test skill " + SKILL),
                "the skill tree must remain in the prefix even with auto-matching off");
    }

    @Test
    public void disabledSkillIsNeverMatchedOrInjected(@TempDir Path dir) throws Exception {
        SkillRegistry registry = registryWithFixture(dir.resolve("skills"), TRIGGER, "SECRET-BODY");
        registry.disable(SKILL);
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        // The disabled skill itself must never surface via EITHER retrieval layer. Layer 2 (BM25)
        // may still legitimately fill slots with OTHER, unrelated real built-in skills for this
        // query — the per-character CJK unigram recall fix (SkillIndex) means a short, mostly
        // nonsense query like this one can now share individual characters with plenty of real
        // skills' descriptions/triggers, so the match list is no longer expected to be empty. What
        // matters is that the DISABLED skill specifically is excluded.
        assertFalse(pb.matchSkills("表演" + TRIGGER).contains(SKILL), "a disabled skill must never be matched");
        // even a stale hint entry must not surface a disabled skill (nor, a fortiori, its body)
        String prompt = pb.build(Set.of(SKILL));
        assertFalse(prompt.contains("SECRET-BODY"));
        assertFalse(prompt.contains("- " + SKILL), "a disabled skill must appear in neither the nudge nor the tree");
    }

    /// Exercises matchSkills()'s own requires:-expansion (AiPromptBuilder lines ~208-218) — a
    /// scenario-level skill matched by trigger must ALSO pull in the operation-level skill(s) named
    /// in its `requires:` list, mirroring how every shipped built-in scenario skill actually works
    /// (e.g. diagnose-crash requires: java-runtime, mods-install, ...). This is the currently-live,
    /// one-level-deep case — the ONLY nesting depth any shipped skill uses today.
    @Test
    public void requiresExpansionPullsInTheRequiredOperationLevelSkill(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        String childName = "test-injection-required-child";
        writeSkill(skillsDir, childName, "", "# Child playbook\nCHILD-UNIQUE-BODY-MARKER");
        // Scenario fixture whose triggers a real user message will hit, requiring the child above.
        Path scenarioDir = skillsDir.resolve(SKILL);
        Files.createDirectories(scenarioDir);
        Files.writeString(scenarioDir.resolve("SKILL.md"), "---\n"
                + "name: " + SKILL + "\n"
                + "description: test scenario skill\n"
                + "version: 1.0\n"
                + "triggers: " + TRIGGER + "\n"
                + "requires: " + childName + "\n"
                + "---\n\n# Scenario playbook\nSCENARIO-UNIQUE-BODY-MARKER\n");
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh(); // also loads all built-in skills (from bundled JSON, in memory); the fixtures' unique names survive

        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        List<String> matched = pb.matchSkills("请示范一下" + TRIGGER + "怎么跳");
        assertTrue(matched.contains(SKILL), "the trigger-matched scenario skill must be in the hint set");
        assertTrue(matched.contains(childName),
                "the scenario's requires:-listed operation skill must be expanded into the hint set too");

        // Both surface in the nudge as candidates; NEITHER body is inlined (load_skill's own
        // requires:-expansion delivers the child when the model loads the scenario).
        String suffix = pb.buildVolatileSuffix(Set.copyOf(matched));
        assertTrue(suffix.contains("- " + SKILL), "the nudge must name the scenario skill");
        assertTrue(suffix.contains("- " + childName), "the nudge must name the required child skill");
        assertFalse(suffix.contains("SCENARIO-UNIQUE-BODY-MARKER"), "no body may be inlined by matching");
        assertFalse(suffix.contains("CHILD-UNIQUE-BODY-MARKER"), "no body may be inlined by matching");
    }

    // --- Dev mode: literal "[Dev]" tag, deterministic per-turn injection (bypasses SkillMatcher) ---

    /// End-to-end: a message carrying the project's own "[Dev]" testing/debug-collaboration tag
    /// (mirroring real usage, e.g. "[Dev]为什么工具识别为共享？") must reliably surface the real,
    /// built-in dev-mode skill's body in the assembled volatile suffix; a message without the tag
    /// must never pay for it.
    @Test
    public void devTagReliablyInjectsDevModeSkillBody(@TempDir Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh(); // loads the real built-in dev-mode skill (from bundled JSON) alongside everything else
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        String suffixWithTag = pb.buildVolatileSuffix(Set.of(), "[Dev]为什么工具识别为共享？");
        assertTrue(suffixWithTag.contains("Dev mode is ACTIVE this turn"),
                "a message containing the literal \"[Dev]\" tag must inject the dev-mode skill body");
        assertTrue(suffixWithTag.contains("[Dev 诊断]"),
                "the dev-mode skill's own output-format section must be inlined, not just a stub");

        String suffixWithoutTag = pb.buildVolatileSuffix(Set.of(), "为什么工具识别为共享？");
        assertFalse(suffixWithoutTag.contains("Dev mode is ACTIVE this turn"),
                "a message without the tag must never pay for the dev-mode skill body");
        assertFalse(suffixWithoutTag.contains("[Dev 诊断]"));
    }

    /// Regression guard for exactly the reliability gap that ruled out registering "[Dev]" as an
    /// ordinary SkillMatcher trigger: SkillMatcher's ASCII-trigger path requires a non-alphanumeric
    /// boundary on BOTH sides of the match (see SkillMatcher#hits), so a ordinary trigger-based
    /// "[Dev]" would silently fail to fire whenever the tag is glued directly to a letter/digit on
    /// either side — e.g. a developer pasting a stack trace or error code right after the tag, with
    /// no separating space ("[Dev]NullPointerException", "test[Dev]500"). The deterministic
    /// substring check this feature actually uses must have no such failure mode.
    @Test
    public void devTagFiresEvenWithNoWhitespaceBoundary(@TempDir Path dir) throws Exception {
        assertTrue(AiPromptBuilder.isDevModeTriggered("[Dev]NullPointerException at line 42"));
        assertTrue(AiPromptBuilder.isDevModeTriggered("test[Dev]"));
        assertTrue(AiPromptBuilder.isDevModeTriggered("some text [Dev]500 error right after"));
        assertTrue(AiPromptBuilder.isDevModeTriggered("[Dev]为什么工具识别为共享？"));
        assertFalse(AiPromptBuilder.isDevModeTriggered("no tag here"));
        assertFalse(AiPromptBuilder.isDevModeTriggered("mismatched brackets [dev] different case"));
        assertFalse(AiPromptBuilder.isDevModeTriggered(null));

        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        String suffix = pb.buildVolatileSuffix(Set.of(), "test[Dev]NullPointerException");
        assertTrue(suffix.contains("Dev mode is ACTIVE this turn"),
                "the tag must fire even glued directly to alphanumerics on both sides");
    }

    /// Dev mode must stay out of the ordinary retrieval pool: matchSkills() feeds the per-turn
    /// skill_hint nudge — if dev-mode ever surfaced there, the model would be invited to
    /// load_skill it like a normal playbook, instead of it firing only via its own deterministic
    /// "[Dev]"-tag injection path.
    @Test
    public void devModeSkillNeverEntersTheStickyRetrievalPath(@TempDir Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        assertFalse(pb.matchSkills("[Dev]为什么工具识别为共享？").contains("dev-mode"),
                "dev-mode must never be matched via the normal trigger/BM25 retrieval pool");
    }

    // --- Skill tree in the stable prefix (replaces the old scenario-only flat index) ---

    /// The stable prefix's skill index must be the full, domain-grouped tree over the REAL
    /// built-in catalog: scenario-level playbooks at the root, operation-level ones grouped under
    /// their domain directory (previously invisible in the prompt entirely), and dev-mode — which
    /// has its own dedicated injection path — absent from it.
    @Test
    public void stablePrefixCarriesTheDomainGroupedSkillTree(@TempDir Path dir) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh(); // loads the real built-in skills (both levels) from bundled JSON
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = new AiPromptBuilder(settings, new ToolRegistry(), registry, new AiSearchConfig());

        String prefix = pb.buildStablePrefix();
        assertTrue(prefix.contains("Scenario playbooks"), "tree must have a scenario section");
        assertTrue(prefix.contains("- diagnose-crash:"), "a real scenario skill must be listed with a brief");
        assertTrue(prefix.contains("- instance/"), "operation skills must be grouped by their domain");
        assertTrue(prefix.contains("  - mods-install:"),
                "a real operation-level skill must now be listed in the tree (it never was before)");
        assertFalse(prefix.contains("- dev-mode"),
                "dev-mode must not be offered in the tree — it is never a load_skill target");
    }

    /// oneLineBrief() is what keeps the always-present tree cheap: it must cut a shipped-style
    /// "What it does. Use when …" description down to its first sentence (Chinese or English)
    /// and survive null/blank input.
    @Test
    public void oneLineBriefTakesTheFirstSentenceOnly() {
        assertEquals("How to install mods correctly.",
                AiPromptBuilder.oneLineBrief("How to install mods correctly. Use when the user wants mods."));
        assertEquals("诊断游戏崩溃的完整流程。",
                AiPromptBuilder.oneLineBrief("诊断游戏崩溃的完整流程。当用户说闪退、进不去时使用。"));
        assertEquals("No trailing sentence separator at all",
                AiPromptBuilder.oneLineBrief("No trailing sentence separator at all"));
        assertEquals("", AiPromptBuilder.oneLineBrief(null));
    }
}
