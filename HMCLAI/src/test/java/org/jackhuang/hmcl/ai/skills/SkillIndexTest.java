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

/// {@link SkillIndex} (the BM25 Layer-2 fuzzy matcher) had zero test coverage before this — every
/// boundary here (empty index, single-doc index, tokenizer behavior, tied-score ordering) had no
/// regression protection at all.
public final class SkillIndexTest {

    private static SkillManifest skill(String name, String description, String... triggers) {
        return new SkillManifest(name, description, "1.0", "/x/" + name + "/SKILL.md",
                List.of(triggers), java.util.Map.of(), List.of());
    }

    @Test
    public void emptyIndexReturnsEmptyForAnyQuery() {
        SkillIndex index = new SkillIndex(List.of());
        assertTrue(index.search("崩溃 crash", 5).isEmpty());
    }

    @Test
    public void blankOrZeroLimitQueryReturnsEmpty() {
        SkillIndex index = new SkillIndex(List.of(skill("a", "diagnose a crash")));
        assertTrue(index.search("", 5).isEmpty());
        assertTrue(index.search("   ", 5).isEmpty());
        assertTrue(index.search("crash", 0).isEmpty());
    }

    @Test
    public void singleDocumentIndexReturnsItForAMatchingQueryWithoutDivideByZero() {
        SkillIndex index = new SkillIndex(List.of(skill("diagnose-crash", "diagnose a game crash")));
        List<SkillManifest> hits = index.search("crash", 5);
        assertEquals(1, hits.size());
        assertEquals("diagnose-crash", hits.get(0).getName());
    }

    @Test
    public void queryWithNoOverlapReturnsEmpty() {
        SkillIndex index = new SkillIndex(List.of(skill("diagnose-crash", "diagnose a game crash")));
        assertTrue(index.search("completely unrelated topic", 5).isEmpty());
    }

    @Test
    public void resultsAreCappedAtLimit() {
        SkillIndex index = new SkillIndex(List.of(
                skill("a", "mod install guide"),
                skill("b", "mod update guide"),
                skill("c", "mod remove guide")));
        assertEquals(2, index.search("mod guide", 2).size());
    }

    @Test
    public void semanticallyCloserDocumentRanksAboveIncidentalOverlap() {
        // "install" and "mod" both appear in the query; skill A shares BOTH words repeatedly
        // (higher term frequency), skill B shares only one incidental token once.
        SkillIndex index = new SkillIndex(List.of(
                skill("install-and-mod", "install a mod, mod install guide, installing mods"),
                skill("unrelated", "mod of the month newsletter archive")));
        List<SkillManifest> hits = index.search("install mod", 2);
        assertFalse(hits.isEmpty());
        assertEquals("install-and-mod", hits.get(0).getName());
    }

    /// Two skills whose indexed text produces a genuine BM25 SCORE TIE must still order
    /// deterministically (by name), not by whatever order the input list happened to iterate in
    /// (that order comes from Files.walk via SkillLoader.scanDirectory and is not guaranteed).
    @Test
    public void tiedScoresOrderDeterministicallyByNameRegardlessOfInputOrder() {
        SkillManifest zebra = skill("zebra-skill", "unique-shared-term");
        SkillManifest alpha = skill("alpha-skill", "unique-shared-term");

        List<SkillManifest> order1 = new SkillIndex(List.of(zebra, alpha)).search("unique-shared-term", 5);
        List<SkillManifest> order2 = new SkillIndex(List.of(alpha, zebra)).search("unique-shared-term", 5);

        assertEquals(2, order1.size());
        assertEquals(order1.stream().map(SkillManifest::getName).toList(),
                order2.stream().map(SkillManifest::getName).toList(),
                "tie-break must not depend on input list order");
        assertEquals("alpha-skill", order1.get(0).getName(), "ties break alphabetically by name");
    }

    @Test
    public void tokenizeHandlesMixedAsciiAndCjkText() {
        List<String> tokens = SkillIndex.tokenize("MC 崩溃了");
        assertTrue(tokens.contains("mc"), "ASCII run lower-cased: " + tokens);
        // CJK run "崩溃了" (3 chars) becomes overlapping bigrams: 崩溃, 溃了
        assertTrue(tokens.contains("崩溃"), "expected CJK bigram: " + tokens);
        assertTrue(tokens.contains("溃了"), "expected CJK bigram: " + tokens);
    }

    @Test
    public void tokenizeSingleCjkCharacterIsItsOwnToken() {
        List<String> tokens = SkillIndex.tokenize("卡");
        assertEquals(List.of("卡"), tokens);
    }

    /// The bugfix under test: a multi-character CJK run must ALSO emit every one of its
    /// characters as its own unigram, in addition to the pre-existing overlapping bigrams — not
    /// only when the whole run happens to be exactly 1 character. Without this, a single-character
    /// query (e.g. "崩") could basically never match a real document, since document text almost
    /// always has that character embedded inside a longer run, never isolated.
    @Test
    public void tokenizeMultiCharacterCjkRunAlsoEmitsPerCharacterUnigrams() {
        List<String> tokens = SkillIndex.tokenize("崩溃了");
        // Pre-existing bigram behavior must survive unchanged.
        assertTrue(tokens.contains("崩溃"), "bigram must still be present: " + tokens);
        assertTrue(tokens.contains("溃了"), "bigram must still be present: " + tokens);
        // New: per-character unigrams from the SAME run.
        assertTrue(tokens.contains("崩"), "unigram must now be present: " + tokens);
        assertTrue(tokens.contains("溃"), "unigram must now be present: " + tokens);
        assertTrue(tokens.contains("了"), "unigram must now be present: " + tokens);
    }

    /// Regression guard for the fix above: adding per-character unigrams must not change WHICH
    /// document wins a multi-character CJK query, or its relative ranking — i.e. the pre-existing
    /// bigram-driven matches/rankings are unaffected by the change.
    @Test
    public void multiCharacterCjkQueryRankingUnaffectedByUnigramFix() {
        SkillIndex index = new SkillIndex(List.of(
                skill("diagnose-crash", "诊断游戏崩溃问题，处理闪退", "崩溃", "闪退"),
                skill("unrelated", "存档管理与整理备份")));
        List<SkillManifest> hits = index.search("崩溃", 5);
        assertFalse(hits.isEmpty());
        assertEquals("diagnose-crash", hits.get(0).getName(),
                "the skill whose text actually shares the query's bigram must still rank first");
    }

    /// The actual bug report: a single-character query like "闪" or "崩" returned nothing against
    /// the REAL bundled skill catalog before this fix, because those characters only ever appear
    /// embedded in longer CJK runs there (e.g. diagnose-crash's description contains "闪退" and
    /// "游戏崩溃了" as multi-character runs, never "闪"/"崩" alone) — so only overlapping bigrams were
    /// ever indexed for them, never the bare single-character unigram a short query tokenizes to.
    ///
    /// Built-in skills are now loaded straight from bundled classpath JSON via
    /// {@link SkillLoader#loadBuiltinSkills()} (no more seed-to-disk-then-scan step — see that
    /// method's doc comment), so this test exercises that production API directly instead of the
    /// old {@code seedBuiltinSkills}/{@code scanDirectory} pair.
    @Test
    public void singleCharacterCjkQueryMatchesRealBundledCatalog() {
        List<SkillManifest> skills = SkillLoader.loadBuiltinSkills();
        assertFalse(skills.isEmpty(), "the bundled skill catalog must have loaded something to search");

        SkillIndex index = new SkillIndex(skills);
        assertFalse(index.search("闪", 5).isEmpty(),
                "single-character query '闪' must now match a real skill (e.g. diagnose-crash's '闪退'/'游戏崩溃了')");
        assertFalse(index.search("崩", 5).isEmpty(),
                "single-character query '崩' must now match a real skill (e.g. diagnose-crash's '崩溃'/'游戏崩溃了')");
    }

    /// NFKC-fold: a fullwidth Latin run (common from CJK input methods, e.g. "ＭＯＤ") must
    /// tokenize identically to its halfwidth ASCII equivalent, so a query in one form matches
    /// indexed text in the other.
    @Test
    public void tokenizeFoldsFullwidthLatinToHalfwidth() {
        List<String> tokens = SkillIndex.tokenize("ＭＯＤ"); // fullwidth "MOD"
        assertEquals(List.of("mod"), tokens);
    }

    @Test
    public void fullwidthQueryMatchesHalfwidthIndexedText() {
        SkillIndex index = new SkillIndex(List.of(skill("install-and-mod", "mod install guide")));
        List<SkillManifest> hits = index.search("ＭＯＤ", 5); // fullwidth "MOD"
        assertEquals(1, hits.size(), "a fullwidth-typed query must still match halfwidth-indexed text");
    }
}
