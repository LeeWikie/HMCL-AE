package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/// Locks in the whole point of splitting {@link AiPromptBuilder#buildStablePrefix()} out from
/// {@link AiPromptBuilder#buildVolatileSuffix}: the stable half must be BYTE-IDENTICAL across
/// requests regardless of what changes turn-to-turn (plan mode, active skills, language, custom
/// instructions, …) — that identity is what lets a provider's prefix-hash cache actually hit. A
/// regression here silently defeats the whole point of the split without any functional test
/// noticing, since {@link AiPromptBuilder#build} still produces a working (just no-longer-cacheable)
/// prompt either way.
public final class AiPromptBuilderCacheStabilityTest {

    private static AiPromptBuilder newBuilder(Path dir, AiSettings settings, AiSearchConfig search,
                                                AtomicBoolean planMode) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(dir.resolve("skills"));
        registry.refresh();
        return new AiPromptBuilder(settings, new ToolRegistry(), registry, search, planMode::get, null);
    }

    @Test
    public void stablePrefixIsByteIdenticalAcrossVolatileStateChanges(@TempDir Path dir) throws Exception {
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiSearchConfig search = new AiSearchConfig();
        AtomicBoolean planMode = new AtomicBoolean(false);
        AiPromptBuilder pb = newBuilder(dir, settings, search, planMode);

        String prefixBefore = pb.buildStablePrefix();

        // Flip everything buildVolatileSuffix is documented to depend on.
        planMode.set(true);
        search.setEnabled(true);
        settings.responseLanguageProperty().set("zh");
        settings.customInstructionsProperty().set("自定义指令测试");

        String prefixAfter = pb.buildStablePrefix();

        assertEquals(prefixBefore, prefixAfter,
                "buildStablePrefix() must not depend on plan mode / web search / language / any "
                        + "other per-turn setting — those belong in buildVolatileSuffix()");

        // Sanity: the volatile suffix DID pick up at least one of those changes, proving the split
        // actually routes volatile content to the right half rather than dropping it entirely.
        String suffix = pb.buildVolatileSuffix(Set.of());
        assertTrue(suffix.contains("PLAN MODE IS ACTIVE"), "plan mode toggle should surface in the volatile suffix");
    }

    @Test
    public void stablePrefixHasNoRuntimeOrSkillPlaybookContent(@TempDir Path dir) throws Exception {
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = newBuilder(dir, settings, new AiSearchConfig(), new AtomicBoolean(false));

        String prefix = pb.buildStablePrefix();
        assertFalse(prefix.contains("Tool execution policy:"), "execution policy is volatile (settings-driven)");
        // The stable prefix's skill-index intro text legitimately MENTIONS "Active skill
        // playbooks" as a forward reference to where the (volatile) block would appear — the
        // actual playbook injection wrapper text is what must NOT be here.
        assertFalse(prefix.contains("FOLLOW THESE STEPS for the matching part"),
                "active skill playbook bodies are per-session, not stable");
    }

    /// Regression guard against unbounded growth of the cached prefix (e.g. something volatile
    /// accidentally getting merged back into it). Not a tight bound — just catches "someone
    /// inlined a whole file into the stable prefix by mistake".
    @Test
    public void stablePrefixStaysUnderSoftSizeCap(@TempDir Path dir) throws Exception {
        AiSettings settings = new AiSettings(dir.resolve("settings"));
        AiPromptBuilder pb = newBuilder(dir, settings, new AiSearchConfig(), new AtomicBoolean(false));

        int length = pb.buildStablePrefix().length();
        assertTrue(length < 30_000,
                "buildStablePrefix() grew to " + length + " chars — over the 30,000 soft cap; "
                        + "if this is intentional, raise the cap deliberately rather than ignoring the failure");
    }
}
