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
package org.jackhuang.hmcl.ai.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the deterministic trigger-matching rules the auto-skill-injection path relies on:
/// CJK containment, ASCII word boundaries, specificity ordering and the match limit.
public class SkillMatcherTest {

    private static SkillManifest skill(String name, String... triggers) {
        return new SkillManifest(name, "desc", "1.0", "/x/" + name + "/SKILL.md",
                List.of(triggers), java.util.Map.of(), List.of());
    }

    @Test
    public void cjkTriggerMatchesBySubstring() {
        SkillManifest crash = skill("diagnose-crash", "崩溃", "闪退");
        List<SkillManifest> hits = SkillMatcher.match("我的游戏又闪退了怎么办", List.of(crash), 2);
        assertEquals(1, hits.size());
        assertEquals("diagnose-crash", hits.get(0).getName());
    }

    @Test
    public void asciiTriggerRequiresWordBoundary() {
        SkillManifest mods = skill("install-and-mod", "mod");
        // "model" must NOT fire the "mod" trigger…
        assertTrue(SkillMatcher.match("please switch the model", List.of(mods), 2).isEmpty());
        // …but a real word hit must.
        assertEquals(1, SkillMatcher.match("install a mod please", List.of(mods), 2).size());
        // and matching is case-insensitive.
        assertEquals(1, SkillMatcher.match("Install A MOD", List.of(mods), 2).size());
    }

    @Test
    public void moreSpecificMatchWinsOrdering() {
        SkillManifest broad = skill("optimize-performance", "卡");
        SkillManifest specific = skill("java-and-memory", "内存溢出", "爆内存");
        List<SkillManifest> hits = SkillMatcher.match("游戏卡住了还内存溢出", List.of(broad, specific), 2);
        assertEquals(2, hits.size());
        // summed matched-trigger length: 内存溢出(4) > 卡(1)
        assertEquals("java-and-memory", hits.get(0).getName());
    }

    @Test
    public void limitCapsResults() {
        SkillManifest a = skill("a", "存档");
        SkillManifest b = skill("b", "备份");
        SkillManifest c = skill("c", "恢复");
        List<SkillManifest> hits = SkillMatcher.match("把存档备份然后恢复", List.of(a, b, c), 2);
        assertEquals(2, hits.size());
    }

    @Test
    public void skillWithoutTriggersNeverMatches() {
        SkillManifest none = skill("no-triggers");
        assertTrue(SkillMatcher.match("崩溃 crash mod 备份", List.of(none), 2).isEmpty());
    }

    @Test
    public void blankInputMatchesNothing() {
        SkillManifest crash = skill("diagnose-crash", "崩溃");
        assertTrue(SkillMatcher.match("   ", List.of(crash), 2).isEmpty());
    }

    /// NFKC-fold: a fullwidth Latin trigger (or user message) must match its halfwidth
    /// counterpart — Character.toLowerCase alone keeps a fullwidth run fullwidth (isAscii() sees
    /// codepoint > 127 and treats it as non-ASCII, taking the plain-containment path with no
    /// word-boundary check), so without NFKC normalization "ＭＯＤ" and "mod" never matched.
    @Test
    public void fullwidthTriggerMatchesHalfwidthMessage() {
        SkillManifest mods = skill("install-and-mod", "ＭＯＤ"); // fullwidth trigger
        assertEquals(1, SkillMatcher.match("install a mod please", List.of(mods), 2).size());
    }

    @Test
    public void halfwidthTriggerMatchesFullwidthMessage() {
        SkillManifest mods = skill("install-and-mod", "mod");
        assertEquals(1, SkillMatcher.match("请ＭＯＤ帮我装一下", List.of(mods), 2).size());
    }

    /// Two skills that produce a genuine tied score must order deterministically by name, not by
    /// whichever order the input `skills` list happened to iterate in (that order comes from
    /// Files.walk via SkillLoader.scanDirectory and is not guaranteed stable across refreshes).
    @Test
    public void tiedScoresOrderDeterministicallyByNameRegardlessOfInputOrder() {
        SkillManifest zebra = skill("zebra-skill", "同分触发词");
        SkillManifest alpha = skill("alpha-skill", "同分触发词");

        List<SkillManifest> order1 = SkillMatcher.match("测试一下同分触发词", List.of(zebra, alpha), 5);
        List<SkillManifest> order2 = SkillMatcher.match("测试一下同分触发词", List.of(alpha, zebra), 5);

        assertEquals(order1.stream().map(SkillManifest::getName).toList(),
                order2.stream().map(SkillManifest::getName).toList(),
                "tie-break must not depend on input list order");
        assertEquals("alpha-skill", order1.get(0).getName(), "ties break alphabetically by name");
    }
}
