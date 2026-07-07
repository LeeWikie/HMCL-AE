package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Parsed manifest of a single SKILL.md file.
@NotNullByDefault
public final class SkillManifest {

    @Nullable
    private final String name;
    @Nullable
    private final String description;
    @Nullable
    private final String version;
    /// Absolute path of the SKILL.md file, so the agent can read the full body on demand.
    @Nullable
    private final String path;
    /// Trigger phrases from the `triggers:` frontmatter line, used by {@link SkillMatcher}
    /// to auto-load this skill's body when a user message matches. Empty = never auto-matched
    /// (the skill still appears in the prompt index for model-initiated reads).
    private final List<String> triggers;
    private final Map<String, Object> permissions;
    private final List<String> errors;

    public SkillManifest() {
        this.name = null;
        this.description = null;
        this.version = null;
        this.path = null;
        this.triggers = new ArrayList<>();
        this.permissions = new LinkedHashMap<>();
        this.errors = new ArrayList<>();
    }

    public SkillManifest(String name, String description, String version, String path,
                         Map<String, Object> permissions, List<String> errors) {
        this(name, description, version, path, List.of(), permissions, errors);
    }

    public SkillManifest(String name, String description, String version, String path,
                         List<String> triggers, Map<String, Object> permissions, List<String> errors) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.path = path;
        this.triggers = List.copyOf(triggers);
        this.permissions = Map.copyOf(permissions);
        this.errors = List.copyOf(errors);
    }

    @Nullable public String getName() { return name; }
    @Nullable public String getDescription() { return description; }
    @Nullable public String getVersion() { return version; }
    @Nullable public String getPath() { return path; }
    public List<String> getTriggers() { return triggers; }
    public Map<String, Object> getPermissions() { return permissions; }
    public List<String> getErrors() { return errors; }
    public boolean isValid() { return name != null && errors.isEmpty(); }
}
