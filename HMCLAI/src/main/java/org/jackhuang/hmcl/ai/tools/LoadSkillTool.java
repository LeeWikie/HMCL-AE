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
package org.jackhuang.hmcl.ai.tools;

import org.jackhuang.hmcl.ai.skills.SkillLoader;
import org.jackhuang.hmcl.ai.skills.SkillManifest;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Explicitly loads one or more skills' playbook bodies by name — the dedicated replacement for
/// reading a SKILL.md file through the generic `read` tool. Before this tool existed, a model
/// pulling in a skill that wasn't auto-matched had no way to do so except `read(path)`, which
/// showed up in the UI/trace as an anonymous file read rather than a skill invocation — this tool
/// call is now visible AS a skill invocation everywhere a tool call is (UI tool-call card,
/// background job labels, trace events), for free, just by being its own named tool.
///
/// Loading a SCENARIO-level skill also loads every operation-level skill in its `requires:` list
/// in the same call — the same deterministic expansion {@code AiPromptBuilder#matchSkills} applies
/// to auto-matched skills, so an explicit load doesn't need a second round-trip per sub-skill.
///
/// Three modes, one tool:
/// - `name` (single skill, as before).
/// - `names` (array): batch-loads several skills' bodies in ONE call instead of one `load_skill`
///   round-trip per skill — this is the fix for a trace where a model needing 3 skills called
///   `glob()` once then `read()` three times rather than ever calling this tool. `name` and `names`
///   can be combined; the result is their union, `name` first, in call order, with exact-duplicate
///   skill names skipped.
/// - neither present: lists every enabled skill (name + description + level) instead of loading
///   one. Mostly redundant now that the system prompt's skill tree (see
///   {@code AiPromptBuilder#skillTreeBlock}) already lists BOTH levels with one-line briefs, but
///   kept as the on-demand full-description fallback (the tree's briefs are first-sentence only).
///
/// Permission level: READ_ONLY. It only reads SKILL.md files already shipped with/authored for
/// the app; it changes no state (use the settings page to enable/disable a skill).
@NotNullByDefault
public final class LoadSkillTool implements ToolSpec {

    /// Generous relative to the ~1000-char auto-inject cap — an EXPLICIT load is a deliberate
    /// choice to spend context on one playbook, not a background auto-match. Applied PER SKILL,
    /// not as a shared budget across a `names` batch — loading 3 skills in one call still gets
    /// each one up to this many chars, exactly as if they'd been loaded one at a time.
    private static final int BODY_MAX_CHARS = 4000;

    private final SkillRegistry skillRegistry;

    public LoadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "load_skill";
    }

    @Override
    public String getDescription() {
        return "Loads one or more skills' full playbooks by name (scenario-level or operation-level — see the "
                + "skill tree in this prompt for available names). Playbook text is NEVER preloaded: this tool is "
                + "how you read a skill before following it, and the dedicated replacement for reading SKILL files "
                + "with the read tool. Loading a scenario-level skill also loads every operation-level skill its "
                + "playbook requires, in the same call. Parameters: name (a single exact skill name) and/or names "
                + "(an array of exact skill names to batch-load in ONE call instead of one call per skill — use "
                + "this whenever you already know you need several skills). Omit both name and names to list every "
                + "enabled skill with its full description (the tree shows only a one-line brief). Read-only.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "name": {
                     "type": "string",
                     "description": "Exact skill name, as shown in the skill index. Omit together with 'names' to list every enabled skill instead."
                   },
                   "names": {
                     "type": "array",
                     "items": {"type": "string"},
                     "description": "Multiple exact skill names to load in one call — each one's body (plus its own requires: expansion) is concatenated in the result, in order. Can be combined with 'name'."
                   }
                 },
                 "required": []
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        boolean nameKeyGiven = parameters.get("name") != null || parameters.get("query") != null;
        boolean namesKeyGiven = parameters.get("names") != null;

        if (!nameKeyGiven && !namesKeyGiven) {
            // Neither parameter present at all: this is the deliberate discovery/list call (ii),
            // not a malformed load request — see the class doc for why this is the only way a
            // model can learn an operation-level skill's name outside a requires: expansion or a
            // BM25 match.
            return ToolResult.success(skillRegistry.listAllForDiscovery());
        }

        List<String> requested = collectRequestedNames(parameters);
        if (requested.isEmpty()) {
            // A name/names key WAS given (so this isn't the no-args discovery call above) but it
            // resolved to nothing usable (e.g. name="" or names=[]) — mirror the pre-batch
            // validation-failure message rather than silently falling back to list mode.
            return ToolResult.failure("Missing required parameter: name (or names)");
        }

        List<SkillManifest> enabled = skillRegistry.enabled();

        if (requested.size() == 1) {
            // Exactly the original single-name path (whether the request came from `name` or a
            // singleton `names` array): unresolved -> hard failure, exactly as before.
            String name = requested.get(0);
            SkillManifest manifest = findByName(enabled, name);
            if (manifest == null) {
                return ToolResult.failure(notFoundMessage(name));
            }
            StringBuilder sb = new StringBuilder();
            appendSkillWithRequires(sb, manifest, enabled, new java.util.HashSet<>());
            return ToolResult.success(sb.toString().strip());
        }

        // Batch path: resolve what we can, and note (rather than abort on) any name that doesn't
        // resolve — a typo in one of several requested names shouldn't cost the model the skills
        // it DID name correctly. `injected` is shared across the WHOLE batch (not just within one
        // name's own requires: expansion) so a skill pulled in as a requires:-dependency of one
        // requested name is never re-appended verbatim if another requested name also names it
        // directly or requires it too — real shipped skills overlap heavily on shared operation-level
        // dependencies (e.g. several scenario skills all `requires: mods-install`).
        StringBuilder sb = new StringBuilder();
        int resolvedCount = 0;
        java.util.Set<String> injected = new java.util.HashSet<>();
        for (String name : requested) {
            SkillManifest manifest = findByName(enabled, name);
            if (manifest == null) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("### ").append(name).append('\n').append(notFoundMessage(name));
                continue;
            }
            resolvedCount++;
            appendSkillWithRequires(sb, manifest, enabled, injected);
        }
        if (resolvedCount == 0) {
            // Every requested name failed to resolve — signal overall failure (not success) so a
            // caller/model branching on the result's success flag gets an accurate signal, exactly
            // like the single-name path above does for the identical situation. The per-name notes
            // already collected in `sb` are kept as the failure detail.
            return ToolResult.failure(sb.toString().strip());
        }
        return ToolResult.success(sb.toString().strip());
    }

    private String notFoundMessage(String name) {
        boolean disabled = skillRegistry.isDisabled(name);
        return disabled
                ? "Skill '" + name + "' is disabled in AI 设置 and cannot be loaded."
                : "No skill named '" + name + "'. Check the skill index in this prompt for valid names.";
    }

    /// Appends one skill's body plus its own `requires:`-expansion, exactly like the original
    /// single-name path — reused by both the single-name and batch branches of {@link #execute}.
    /// `injected` (lower-cased skill names already appended to `sb`, shared across an entire batch
    /// call) is consulted before appending each body, so a skill required by more than one of the
    /// requested top-level names — or a top-level name also reachable via another entry's
    /// `requires:` — is never duplicated verbatim in the result.
    private static void appendSkillWithRequires(StringBuilder sb, SkillManifest manifest, List<SkillManifest> enabled,
                                                 java.util.Set<String> injected) {
        appendSkillBodyIfNew(sb, manifest, injected);
        for (String required : manifest.getRequires()) {
            SkillManifest requiredManifest = findByName(enabled, required);
            if (requiredManifest != null) {
                appendSkillBodyIfNew(sb, requiredManifest, injected);
            }
        }
    }

    /// Appends {@code manifest}'s body to {@code sb} (with a blank-line separator when {@code sb}
    /// already has content) UNLESS its name is already in {@code injected}, in which case this is a
    /// no-op — the caller already has that skill's body from earlier in the same batch.
    private static void appendSkillBodyIfNew(StringBuilder sb, SkillManifest manifest, java.util.Set<String> injected) {
        String key = manifest.getName().toLowerCase(java.util.Locale.ROOT);
        if (!injected.add(key)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        appendSkillBody(sb, manifest);
    }

    private static void appendSkillBody(StringBuilder sb, SkillManifest manifest) {
        String body = SkillLoader.readBody(manifest, BODY_MAX_CHARS);
        sb.append("### ").append(manifest.getName()).append('\n');
        sb.append(body.isEmpty() ? "(empty playbook body)" : body);
    }

    @Nullable
    private static SkillManifest findByName(List<SkillManifest> skills, String name) {
        for (SkillManifest m : skills) {
            if (name.equalsIgnoreCase(m.getName())) {
                return m;
            }
        }
        return null;
    }

    /// Builds the ordered, de-duplicated list of skill names to load: `name` first (if present and
    /// non-blank), then each non-blank entry of `names` in call order, skipping any name that's an
    /// exact (case-insensitive) duplicate of one already collected.
    private static List<String> collectRequestedNames(Map<String, Object> parameters) {
        LinkedHashMap<String, String> byLowerCase = new LinkedHashMap<>();

        String name = extractName(parameters);
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            byLowerCase.putIfAbsent(trimmed.toLowerCase(java.util.Locale.ROOT), trimmed);
        }

        for (String n : extractNames(parameters)) {
            if (n != null && !n.isBlank()) {
                String trimmed = n.trim();
                byLowerCase.putIfAbsent(trimmed.toLowerCase(java.util.Locale.ROOT), trimmed);
            }
        }

        return new ArrayList<>(byLowerCase.values());
    }

    @Nullable
    private static String extractName(Map<String, Object> parameters) {
        Object value = parameters.get("name");
        if (value == null) {
            value = parameters.get("query"); // generic fallback used by the tool adapter
        }
        return value != null ? value.toString() : null;
    }

    /// Reads the `names` parameter as a list of strings. Accepts the normal JSON-array shape
    /// (deserialized as a {@link List}) as well as a lone string (either a single name, or a
    /// comma-separated list of names) as a defensive fallback for a model that didn't quite follow
    /// the advertised array schema.
    private static List<String> extractNames(Map<String, Object> parameters) {
        Object value = parameters.get("names");
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
            return out;
        }
        String s = value.toString();
        if (s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            out.add(part.trim());
        }
        return out;
    }
}
