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
        // Dedupe by name — two manifests declaring the same name (e.g. a user copies an existing
        // skill folder to customize it and forgets to rename it) would otherwise both survive
        // here, and SkillMatcher/SkillIndex/activeSkillBlock treat them as independent hits,
        // injecting the same skill name's playbook body TWICE into one prompt. Keep the first one
        // seen — built-ins first (loaded below), then disk-scanned ones, so a same-named user copy
        // never shadows the real built-in.
        Set<String> seenNames = new HashSet<>();
        // Built-in skills: loaded straight from bundled classpath JSON, in memory — never written
        // under skillsDir (see SkillLoader#loadBuiltinSkills's doc comment for why the old
        // seedBuiltinSkills()-into-skillsDir step, and its hash-based hand-edit protection, are no
        // longer part of this path).
        for (SkillManifest m : SkillLoader.loadBuiltinSkills()) {
            String key = m.getName() != null ? m.getName().toLowerCase(Locale.ROOT) : null;
            if (key != null && !seenNames.add(key)) {
                continue;
            }
            skills.add(m);
        }
        if (skillsDir != null) {
            for (SkillManifest m : SkillLoader.scanDirectory(skillsDir)) {
                String key = m.getName() != null ? m.getName().toLowerCase(Locale.ROOT) : null;
                if (key != null && !seenNames.add(key)) {
                    continue;
                }
                skills.add(m);
            }
        }
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

    /// Static index injected into every system prompt: SCENARIO-level skills only (one directory
    /// component under the skills dir). Operation-level skills (two components — the split-out
    /// playbooks a scenario orchestrates via `requires:`) are deliberately excluded here: at the
    /// ~90-skill scale, listing every one of them in a prompt block present on EVERY turn would
    /// far outweigh the point of splitting scenarios apart in the first place. They're still fully
    /// reachable — via a scenario's `requires:` expansion (see AiPromptBuilder#matchSkills) or a
    /// direct `load_skill` call once the model knows the name from an active playbook.
    public String summarizeEnabled() {
        List<SkillManifest> active = enabled().stream()
                .filter(s -> SkillLoader.isScenarioLevel(skillsDir, s))
                .toList();
        if (active.isEmpty()) return "(no enabled skills)";
        StringBuilder sb = new StringBuilder();
        for (SkillManifest s : active) {
            sb.append("- ").append(s.getName());
            if (s.getDescription() != null) {
                sb.append(": ").append(s.getDescription());
            }
            // Progressive disclosure: the agent calls load_skill(name) for the full playbook —
            // NOT the generic read tool, so tool-call UI/trace shows a real skill invocation.
            sb.append(" (call load_skill(name=\"").append(s.getName()).append("\") for the full playbook)");
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /// On-demand full skill index for {@code load_skill}'s no-args discovery call — deliberately
    /// separate from {@link #summarizeEnabled()}, which is injected into EVERY system prompt and is
    /// scoped to scenario-level skills only for that reason (see its doc comment). This method is
    /// only ever invoked in response to an explicit tool call, so listing every enabled skill of
    /// BOTH levels here (currently ~30 total) is cheap and gives the model the one thing
    /// {@code summarizeEnabled()} deliberately omits: operation-level skill names it hasn't already
    /// learned via a `requires:` expansion or a BM25 match.
    public String listAllForDiscovery() {
        List<SkillManifest> active = enabled();
        if (active.isEmpty()) return "(no enabled skills)";
        StringBuilder sb = new StringBuilder();
        for (SkillManifest s : active) {
            String level = SkillLoader.isScenarioLevel(skillsDir, s) ? "scenario" : "operation";
            sb.append("- ").append(s.getName()).append(" [").append(level).append(']');
            if (s.getDescription() != null) {
                sb.append(": ").append(s.getDescription());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
