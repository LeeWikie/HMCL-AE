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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A tiny local BM25 index over skills' `name` + `description` + `triggers` (NOT the body —
/// bodies are only read on demand, either via {@link SkillMatcher}'s exact-phrase hit or a
/// `load_skill` call). This is Layer 2 of skill retrieval: {@link SkillMatcher} (Layer 1, exact
/// trigger-phrase hits) runs first and always wins a slot; this index fills any REMAINING slots
/// up to the match limit with a fuzzier ranked search, so a user message that doesn't contain an
/// exact trigger phrase can still surface the right skill.
///
/// No external search library is used (none was in the classpath) — this hand-rolls the classic
/// Robertson/Sparck-Jones BM25 formula over a bag-of-tokens model. Tokenization: ASCII runs become
/// lower-cased word tokens; CJK runs become overlapping CHARACTER BIGRAMS plus EVERY character of
/// the run as its own unigram — Chinese/Japanese text has no whitespace to split on, bigrams give
/// partial-match recall without a real segmenter dependency, and the per-character unigrams let a
/// single-character query (e.g. "闪") match a document where that character is embedded in a
/// longer run instead of only ever matching when it happens to form an isolated 1-character run.
///
/// Rebuilt on every {@link SkillRegistry#refresh()} — at the ~100-skill scale this is microseconds,
/// so no incremental-update complexity is needed.
@NotNullByDefault
public final class SkillIndex {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /// Relative-score floor: a Layer-2 hit is kept only if its BM25 score is at least this fraction
    /// of the TOP hit's score for the same query. Without it, BM25 returns a long tail of documents
    /// that share just one weak, common term with the query — for a short generic message that tail
    /// filled every open match slot (the "skill flooding" the user reported). Anchoring the cutoff
    /// to the top score (rather than an absolute threshold) keeps it scale-free across queries of
    /// different lengths/rarities: only skills genuinely close to the best match survive.
    private static final double RELATIVE_SCORE_FLOOR = 0.3;

    private final List<SkillManifest> skills;
    private final List<Map<String, Integer>> termFrequencies;
    private final Map<String, Integer> documentFrequency;
    private final double[] docLength;
    private final double averageDocLength;

    public SkillIndex(List<SkillManifest> skills) {
        this.skills = List.copyOf(skills);
        int n = this.skills.size();
        this.termFrequencies = new ArrayList<>(n);
        this.documentFrequency = new HashMap<>();
        this.docLength = new double[n];

        long totalLength = 0;
        for (int i = 0; i < n; i++) {
            List<String> tokens = tokenize(documentText(this.skills.get(i)));
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            termFrequencies.add(tf);
            docLength[i] = tokens.size();
            totalLength += tokens.size();
            for (String term : tf.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        this.averageDocLength = n == 0 ? 0 : (double) totalLength / n;
    }

    /// Text a skill is indexed on: name + description + triggers, joined — deliberately NOT the
    /// body (bodies can be thousands of characters; indexing them would blur relevance toward
    /// whichever skill happens to repeat the query's words most, rather than the skill whose
    /// stated PURPOSE matches).
    private static String documentText(SkillManifest manifest) {
        StringBuilder sb = new StringBuilder();
        if (manifest.getName() != null) sb.append(manifest.getName()).append(' ');
        if (manifest.getDescription() != null) sb.append(manifest.getDescription()).append(' ');
        for (String trigger : manifest.getTriggers()) {
            sb.append(trigger).append(' ');
        }
        return sb.toString();
    }

    /// Ranks {@link #skills} against {@code query} by BM25 score and returns the top
    /// {@code limit} with a positive score (empty query or empty index returns no results).
    public List<SkillManifest> search(String query, int limit) {
        if (query == null || query.isBlank() || skills.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> queryTerms = tokenize(query, true);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int n = skills.size();
        double[] scores = new double[n];
        for (String term : queryTerms) {
            Integer df = documentFrequency.get(term);
            if (df == null || df == 0) {
                continue;
            }
            // Standard BM25 IDF (with the +1 inside the log to keep it non-negative for
            // common terms, per the Lucene/Okapi variant).
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                Integer tf = termFrequencies.get(i).get(term);
                if (tf == null) {
                    continue;
                }
                double denom = tf + K1 * (1 - B + B * (docLength[i] / Math.max(averageDocLength, 1e-9)));
                scores[i] += idf * (tf * (K1 + 1)) / denom;
            }
        }

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0) {
                order.add(i);
            }
        }
        // Descending score, then a deterministic secondary key (skill name) for exact ties —
        // without it, ties resolve by `skills`' original iteration order, which comes from
        // Files.walk (via SkillLoader.scanDirectory) and is NOT contractually ordered, so which
        // tied skill fills the last open match slot could silently change across refresh()/restarts.
        order.sort((a, b) -> {
            int cmp = Double.compare(scores[b], scores[a]);
            if (cmp != 0) {
                return cmp;
            }
            return nameOf(skills.get(a)).compareTo(nameOf(skills.get(b)));
        });

        // Relative-score floor: `order` is sorted descending, so the first entry is the top score.
        // Keep only hits scoring at least RELATIVE_SCORE_FLOOR of it; once one falls below, every
        // later one does too, so we can stop. This drops the weak, one-common-term tail that used
        // to flood the match slots on short generic queries.
        double floor = order.isEmpty() ? 0 : scores[order.get(0)] * RELATIVE_SCORE_FLOOR;
        List<SkillManifest> results = new ArrayList<>(Math.min(limit, order.size()));
        for (int i = 0; i < order.size() && results.size() < limit; i++) {
            if (scores[order.get(i)] < floor) {
                break;
            }
            results.add(skills.get(order.get(i)));
        }
        return results;
    }

    /// Null-safe skill name accessor for the tie-break comparator above.
    private static String nameOf(SkillManifest m) {
        return m.getName() != null ? m.getName() : "";
    }

    /// Document-side tokenizer (see {@link #tokenize(String, boolean)} with {@code forQuery=false}):
    /// lower-cased ASCII word tokens plus, for each CJK run, both overlapping character bigrams AND
    /// every individual character as its own unigram. Package-private so the tokenizer tests can
    /// assert the indexed (document) token set directly.
    static List<String> tokenize(String text) {
        return tokenize(text, false);
    }

    /// Splits text into lower-cased ASCII word tokens plus CJK n-grams. Punctuation/whitespace are
    /// token separators throughout. NFKC-normalizes first so fullwidth Latin letters/digits (common
    /// from CJK input methods, e.g. "ＭＯＤ") fold to their halfwidth equivalents and tokenize
    /// identically to ASCII "mod" — without this they fell into the CJK/isWordChar gap and could
    /// never match a halfwidth query or trigger phrase.
    ///
    /// CJK unigram emission is ASYMMETRIC between documents and queries, and that asymmetry is the
    /// point:
    /// - DOCUMENTS ({@code forQuery=false}) emit every character of a run as a unigram AND all
    ///   overlapping bigrams — so a genuine single-character query can still find a character that
    ///   only ever appears embedded inside a longer run.
    /// - QUERIES ({@code forQuery=true}) emit a unigram ONLY for a run that is itself a single
    ///   character; a multi-character query run emits bigrams ONLY. This means a normal
    ///   multi-character message must share at least a 2-character sequence with a skill to match
    ///   it — it can no longer light up nearly every skill via one incidentally-shared common
    ///   character, which is what let short generic messages flood the Layer-2 match slots.
    static List<String> tokenize(String text, boolean forQuery) {
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        List<String> tokens = new ArrayList<>();
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (isWordChar(c) && !isCjk(c)) {
                int start = i;
                while (i < length && isWordChar(text.charAt(i)) && !isCjk(text.charAt(i))) {
                    i++;
                }
                tokens.add(text.substring(start, i).toLowerCase(Locale.ROOT));
            } else if (isCjk(c)) {
                int start = i;
                while (i < length && isCjk(text.charAt(i))) {
                    i++;
                }
                String run = text.substring(start, i);
                // Unigrams: always for a document; for a query, only a single-character run (see the
                // method doc for why this asymmetry stops short generic queries over-matching).
                if (!forQuery || run.length() == 1) {
                    for (int j = 0; j < run.length(); j++) {
                        tokens.add(run.substring(j, j + 1));
                    }
                }
                for (int j = 0; j + 1 < run.length(); j++) {
                    tokens.add(run.substring(j, j + 2));
                }
            } else {
                i++;
            }
        }
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /// True for characters in the common CJK Unicode blocks (Han ideographs, Hiragana, Katakana,
    /// Hangul) — good enough for this project's Chinese-first user base without a real segmenter.
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
