package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.*;

/// In-memory registry of loaded skills.
@NotNullByDefault
public final class SkillRegistry {

    private final List<SkillManifest> skills = new ArrayList<>();
    private final Set<String> disabled = new HashSet<>();
    private Path skillsDir;

    public void setSkillsDir(Path dir) {
        this.skillsDir = dir;
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    public void refresh() {
        skills.clear();
        if (skillsDir != null) {
            SkillLoader.seedBuiltinSkills(skillsDir);
        }
        skills.addAll(SkillLoader.scanDirectory(skillsDir));
    }

    public List<SkillManifest> list() {
        return Collections.unmodifiableList(skills);
    }

    public List<SkillManifest> enabled() {
        return skills.stream()
                .filter(s -> s.isValid() && !disabled.contains(s.getName()))
                .toList();
    }

    public void disable(String name) {
        disabled.add(name);
    }

    public void enable(String name) {
        disabled.remove(name);
    }

    public boolean isDisabled(String name) {
        return disabled.contains(name);
    }

    public String summarizeEnabled() {
        List<SkillManifest> active = enabled();
        if (active.isEmpty()) return "(no enabled skills)";
        StringBuilder sb = new StringBuilder();
        for (SkillManifest s : active) {
            sb.append("- ").append(s.getName());
            if (s.getDescription() != null) {
                sb.append(": ").append(s.getDescription());
            }
            if (s.getPath() != null) {
                // Progressive disclosure: the agent reads the full SKILL.md on demand.
                sb.append(" (read this file for full instructions: ").append(s.getPath()).append(")");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
