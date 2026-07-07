package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        String haystack = userInput.toLowerCase(Locale.ROOT);
        List<SkillManifest> matched = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (SkillManifest skill : skills) {
            int score = 0;
            for (String trigger : skill.getTriggers()) {
                String needle = trigger.toLowerCase(Locale.ROOT);
                if (!needle.isEmpty() && hits(haystack, needle)) {
                    score += needle.length();
                }
            }
            if (score > 0) {
                // insertion sort by descending score keeps this allocation-light for tiny lists
                int at = 0;
                while (at < scores.size() && scores.get(at) >= score) at++;
                scores.add(at, score);
                matched.add(at, skill);
            }
        }
        return matched.size() > limit ? List.copyOf(matched.subList(0, limit)) : List.copyOf(matched);
    }

    private static boolean hits(String haystack, String needle) {
        if (isAscii(needle)) {
            // word-boundary match so short English triggers don't fire inside other words
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(needle) + "(?![a-z0-9])")
                    .matcher(haystack).find();
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
