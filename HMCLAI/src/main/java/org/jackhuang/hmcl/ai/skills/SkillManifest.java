package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Parsed manifest of a single skill, sourced either from a user-authored `SKILL.md` file on disk
/// (see {@link SkillLoader#scanDirectory}) or a bundled built-in `SKILL.json` classpath resource
/// (see {@link SkillLoader#loadBuiltinSkills}).
@NotNullByDefault
public final class SkillManifest {

    @Nullable
    private final String name;
    @Nullable
    private final String description;
    @Nullable
    private final String version;
    /// Absolute path of the `SKILL.md` file (user/custom skills) or the built-in's classpath
    /// resource path (informational only for built-ins — see {@link #body}), so the agent can
    /// read the full body on demand.
    @Nullable
    private final String path;
    /// Trigger phrases from the `triggers:` frontmatter line, used by {@link SkillMatcher}
    /// to auto-load this skill's body when a user message matches. Empty = never auto-matched
    /// (the skill still appears in the prompt index for model-initiated reads).
    private final List<String> triggers;
    /// Operation-level skill names this (scenario-level) skill's playbook orchestrates, from
    /// the `requires:` frontmatter line — same phrase-list syntax as `triggers:`. Deterministic:
    /// when this skill is active, every name listed here is loaded alongside it, independent of
    /// (and not counted against) the retrieval match limit. Empty for operation-level skills.
    private final List<String> requires;
    private final Map<String, Object> permissions;
    private final List<String> errors;
    /// True for a skill bundled with the app (loaded via {@link SkillLoader#loadBuiltinSkills},
    /// stored as JSON, never materialized under the user's skillsDir) as opposed to a user-authored
    /// one (scanned from a `SKILL.md` file on disk). Drives the settings-page skill list filter —
    /// built-ins never appear there, see {@code AISettingsPage#buildSkillsTab}.
    private final boolean builtin;
    /// Precomputed scenario-vs-operation level for a {@link #builtin} manifest, derived at load
    /// time from its classpath resource's own directory depth (mirrors the on-disk
    /// one-vs-two-directory-components convention {@link SkillLoader#isScenarioLevel} uses for
    /// disk-scanned skills). Meaningless/unused for non-builtin manifests — those are scanned from
    /// disk instead, where {@link SkillLoader#isScenarioLevel} derives the level itself; see that
    /// method.
    private final boolean scenarioLevel;
    /// Eagerly-loaded playbook body for a {@link #builtin} (JSON-sourced) manifest — parsed once
    /// from the classpath resource's `body` field at load time, since there is no on-disk file to
    /// lazily re-read later the way {@link SkillLoader#readBody} does for Markdown skills. Null for
    /// non-builtin manifests, which stay lazy (read from {@link #path} on demand).
    @Nullable
    private final String body;

    public SkillManifest() {
        this.name = null;
        this.description = null;
        this.version = null;
        this.path = null;
        this.triggers = new ArrayList<>();
        this.requires = new ArrayList<>();
        this.permissions = new LinkedHashMap<>();
        this.errors = new ArrayList<>();
        this.builtin = false;
        this.scenarioLevel = false;
        this.body = null;
    }

    public SkillManifest(String name, String description, String version, String path,
                         Map<String, Object> permissions, List<String> errors) {
        this(name, description, version, path, List.of(), List.of(), permissions, errors);
    }

    public SkillManifest(String name, String description, String version, String path,
                         List<String> triggers, Map<String, Object> permissions, List<String> errors) {
        this(name, description, version, path, triggers, List.of(), permissions, errors);
    }

    public SkillManifest(String name, String description, String version, String path,
                         List<String> triggers, List<String> requires,
                         Map<String, Object> permissions, List<String> errors) {
        this(name, description, version, path, triggers, requires, permissions, errors, false, false, null);
    }

    /// Full constructor, used by {@link SkillLoader#loadBuiltinSkills} for JSON-sourced built-in
    /// manifests. Every other constructor delegates here with {@code builtin=false},
    /// {@code scenarioLevel=false}, {@code body=null} (the Markdown/user-skill defaults).
    public SkillManifest(String name, String description, String version, String path,
                         List<String> triggers, List<String> requires,
                         Map<String, Object> permissions, List<String> errors,
                         boolean builtin, boolean scenarioLevel, @Nullable String body) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.path = path;
        this.triggers = List.copyOf(triggers);
        this.requires = List.copyOf(requires);
        this.permissions = Map.copyOf(permissions);
        this.errors = List.copyOf(errors);
        this.builtin = builtin;
        this.scenarioLevel = scenarioLevel;
        this.body = body;
    }

    @Nullable public String getName() { return name; }
    @Nullable public String getDescription() { return description; }
    @Nullable public String getVersion() { return version; }
    @Nullable public String getPath() { return path; }
    public List<String> getTriggers() { return triggers; }
    public List<String> getRequires() { return requires; }
    public Map<String, Object> getPermissions() { return permissions; }
    public List<String> getErrors() { return errors; }
    public boolean isValid() { return name != null && errors.isEmpty(); }
    public boolean isBuiltin() { return builtin; }
    public boolean isScenarioLevel() { return scenarioLevel; }
    @Nullable public String getBody() { return body; }
}
