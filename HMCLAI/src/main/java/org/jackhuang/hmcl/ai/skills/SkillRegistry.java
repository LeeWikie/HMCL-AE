package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/// Registry of loaded skills.
///
/// The enabled/disabled state is persisted to a `.disabled` file inside the skills directory
/// (one skill name per line) and re-synced from disk whenever that file changes. This matters
/// because the settings page and the chat page each hold their OWN registry instance: without
/// the shared file, toggling a skill in settings changed a private in-memory set the prompt
/// builder never saw, and the toggle silently reset on restart.
@NotNullByDefault
public final class SkillRegistry {

    private static final String DISABLED_FILE = ".disabled";

    private final List<SkillManifest> skills = new ArrayList<>();
    private final Set<String> disabled = new HashSet<>();
    private Path skillsDir;
    /// Last seen modification time of the `.disabled` file (0 = absent, -1 = never read).
    private long disabledSeenAt = -1;

    public synchronized void setSkillsDir(Path dir) {
        this.skillsDir = dir;
    }

    public synchronized Path getSkillsDir() {
        return skillsDir;
    }

    public synchronized void refresh() {
        skills.clear();
        if (skillsDir != null) {
            SkillLoader.seedBuiltinSkills(skillsDir);
        }
        skills.addAll(SkillLoader.scanDirectory(skillsDir));
        syncDisabledFromDisk();
    }

    public synchronized List<SkillManifest> list() {
        return List.copyOf(skills);
    }

    public synchronized List<SkillManifest> enabled() {
        syncDisabledFromDisk();
        return skills.stream()
                .filter(s -> s.isValid() && !disabled.contains(s.getName()))
                .toList();
    }

    public synchronized void disable(String name) {
        syncDisabledFromDisk();
        disabled.add(name);
        saveDisabledToDisk();
    }

    public synchronized void enable(String name) {
        syncDisabledFromDisk();
        disabled.remove(name);
        saveDisabledToDisk();
    }

    public synchronized boolean isDisabled(String name) {
        syncDisabledFromDisk();
        return disabled.contains(name);
    }

    /// Re-reads the persisted disabled set when the on-disk file changed (another registry
    /// instance may have written it). Best-effort: an unreadable file leaves the current state.
    private void syncDisabledFromDisk() {
        if (skillsDir == null) {
            return;
        }
        Path f = skillsDir.resolve(DISABLED_FILE);
        try {
            long mtime = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            if (mtime == disabledSeenAt) {
                return;
            }
            disabled.clear();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    String name = line.trim();
                    if (!name.isEmpty()) {
                        disabled.add(name);
                    }
                }
            }
            disabledSeenAt = mtime;
        } catch (IOException ignored) {
        }
    }

    private void saveDisabledToDisk() {
        if (skillsDir == null) {
            return;
        }
        Path f = skillsDir.resolve(DISABLED_FILE);
        try {
            Files.createDirectories(skillsDir);
            List<String> names = new ArrayList<>(disabled);
            Collections.sort(names);
            Files.write(f, names);
            disabledSeenAt = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException ignored) {
        }
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
