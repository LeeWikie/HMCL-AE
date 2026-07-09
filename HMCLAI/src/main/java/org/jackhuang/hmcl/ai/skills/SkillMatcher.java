package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/// Deterministic trigger-phrase matching of a user message against skill manifests.
///
/// This is the weak-model path of the skill system: instead of relying on the model to
/// notice the skill index in the prompt, decide to `read` the SKILL.md and then follow it
/// (a chain small models routinely fail), the launcher itself matches the user's message
/// against each skill's `triggers:` phrases and auto-injects the matched playbook body
/// into the request. Matching is plain substring work — no model call, no heuristics that
/// can hallucinate.
///
/// Rules:
/// - CJK / non-ASCII triggers match by case-insensitive containment ("闪退" in "游戏闪退了").
/// - Pure-ASCII triggers additionally require word boundaries, so "mod" does not fire
///   inside "model" but does inside "install a mod please".
/// - A skill's score is the summed length of its matched triggers (longer phrase = more
///   specific = stronger signal); results are ordered by score, capped by {@code limit}.
@NotNullByDefault
public final class SkillMatcher {

    private SkillMatcher() {
    }

    /// Returns the skills whose trigger phrases occur in {@code userInput}, most specific
    /// first, at most {@code limit} entries. Skills without triggers never match.
    public static List<SkillManifest> match(String userInput, List<SkillManifest> skills, int limit) {
        if (userInput.isBlank() || skills.isEmpty() || limit <= 0) {
            return List.of();
        }
        // NFKC-normalize before lower-casing: folds fullwidth Latin letters/digits (common from CJK
        // input methods, e.g. "ＭＯＤ") down to their halfwidth equivalents so a halfwidth trigger
        // ("mod") matches fullwidth-typed user text and vice versa, without touching genuine CJK.
        String haystack = java.text.Normalizer.normalize(userInput, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<SkillManifest> matched = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (SkillManifest skill : skills) {
            int score = 0;
            for (String trigger : skill.getTriggers()) {
                String needle = java.text.Normalizer.normalize(trigger, java.text.Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT);
                if (!needle.isEmpty() && hits(haystack, needle)) {
                    score += needle.length();
                }
            }
            if (score > 0) {
                // Insertion sort by descending score, ties broken by skill name for determinism —
                // NOT by which order `skills` happened to iterate in (that order comes from
                // Files.walk via SkillLoader.scanDirectory and is not contractually stable, so a
                // score tie could otherwise silently pick a different skill across refresh()/restarts).
                int at = 0;
                while (at < scores.size() && (scores.get(at) > score
                        || (scores.get(at) == score && nameOf(matched.get(at)).compareTo(nameOf(skill)) < 0))) {
                    at++;
                }
                scores.add(at, score);
                matched.add(at, skill);
            }
        }
        return matched.size() > limit ? List.copyOf(matched.subList(0, limit)) : List.copyOf(matched);
    }

    /// Null-safe skill name accessor for the tie-break comparison above.
    private static String nameOf(SkillManifest m) {
        return m.getName() != null ? m.getName() : "";
    }

    /// Compiled word-boundary patterns for ASCII triggers, cached across calls — {@link #match}
    /// runs on every user turn, and the set of trigger phrases across all enabled skills is
    /// effectively static, so recompiling the same regex per call is avoidable work.
    private static final Map<String, Pattern> ASCII_TRIGGER_PATTERNS = new ConcurrentHashMap<>();

    private static boolean hits(String haystack, String needle) {
        if (isAscii(needle)) {
            // word-boundary match so short English triggers don't fire inside other words
            Pattern pattern = ASCII_TRIGGER_PATTERNS.computeIfAbsent(needle,
                    n -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(n) + "(?![a-z0-9])"));
            return pattern.matcher(haystack).find();
        }
        return haystack.contains(needle);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }
}
