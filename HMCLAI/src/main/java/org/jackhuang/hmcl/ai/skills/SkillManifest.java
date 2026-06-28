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
    private final Map<String, Object> permissions;
    private final List<String> errors;

    public SkillManifest() {
        this.name = null;
        this.description = null;
        this.version = null;
        this.path = null;
        this.permissions = new LinkedHashMap<>();
        this.errors = new ArrayList<>();
    }

    public SkillManifest(String name, String description, String version, String path,
                         Map<String, Object> permissions, List<String> errors) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.path = path;
        this.permissions = Map.copyOf(permissions);
        this.errors = List.copyOf(errors);
    }

    @Nullable public String getName() { return name; }
    @Nullable public String getDescription() { return description; }
    @Nullable public String getVersion() { return version; }
    @Nullable public String getPath() { return path; }
    public Map<String, Object> getPermissions() { return permissions; }
    public List<String> getErrors() { return errors; }
    public boolean isValid() { return name != null && errors.isEmpty(); }
}
