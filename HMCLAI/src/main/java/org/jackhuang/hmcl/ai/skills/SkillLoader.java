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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Scans a directory tree for user-authored `SKILL.md` files and parses their YAML frontmatter
/// into {@link SkillManifest} instances ({@link #scanDirectory}), and separately loads the app's
/// bundled built-in skills straight from classpath JSON resources, entirely in memory
/// ({@link #loadBuiltinSkills}) — see that method's doc comment for why built-ins moved off
/// Markdown-on-disk.
@NotNullByDefault
public final class SkillLoader {

    private static final Gson GSON = new Gson();

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*$", Pattern.MULTILINE);

    /// Max directory depth under {@code skillsDir} a SKILL.md may live at, relative to
    /// {@code skillsDir} itself: `skillsDir/<name>/SKILL.md` (scenario-level, 1 directory
    /// component) or `skillsDir/<domain>/<action-slug>/SKILL.md` (operation-level, 2 directory
    /// components). Passed to {@link Files#walk(Path, int, java.nio.file.FileVisitOption...)},
    /// whose {@code maxDepth} counts hops from {@code skillsDir} itself (0), so 3 hops reaches
    /// `skillsDir/a/b/SKILL.md`.
    private static final int MAX_SCAN_DEPTH = 3;

    public static List<SkillManifest> scanDirectory(Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) return List.of();

        List<SkillManifest> manifests = new ArrayList<>();
        try (var files = Files.walk(skillsDir, MAX_SCAN_DEPTH)) {
            files.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                 .forEach(p -> manifests.add(parseSkillMd(p)));
        } catch (IOException ignored) {
        }
        return manifests;
    }

    /// True for a scenario-level skill (`skillsDir/<name>/SKILL.md`, one directory component
    /// below {@code skillsDir}) as opposed to an operation-level one
    /// (`skillsDir/<domain>/<action-slug>/SKILL.md`, two components). Used to keep the static
    /// skill index in the system prompt down to just the scenario layer.
    public static boolean isScenarioLevel(Path skillsDir, SkillManifest manifest) {
        if (manifest.isBuiltin()) {
            // Built-in manifests carry their level precomputed at load time (see
            // loadBuiltinSkills) from their classpath resource's own directory depth — they're
            // never materialized under skillsDir, so relativizing against it below doesn't apply.
            return manifest.isScenarioLevel();
        }
        String path = manifest.getPath();
        if (path == null) return false;
        Path parent = Path.of(path).getParent();
        if (parent == null) return false;
        Path relative;
        try {
            relative = skillsDir.relativize(parent);
        } catch (IllegalArgumentException e) {
            return false; // not actually under skillsDir (shouldn't happen; fail closed)
        }
        return relative.getNameCount() == 1;
    }

    static SkillManifest parseSkillMd(Path file) {
        List<String> errors = new ArrayList<>();
        String name = null;
        String description = null;
        String version = null;
        List<String> triggers = List.of();
        List<String> requires = List.of();
        Map<String, Object> permissions = Map.of();

        try {
            // safety guard: refuse files larger than 128 KiB
            if (Files.size(file) > 128L * 1024) {
                errors.add("SKILL.md is too large: " + file);
                return new SkillManifest(null, null, null, file.toString(), Map.of(), errors);
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);
            // extract YAML frontmatter if present
            int firstDash = content.indexOf("---");
            if (firstDash == 0 && !content.equals("---")) {
                // There is a potential frontmatter block...
                // We strip the leading ---, find the second ---, and parse key: value lines.
                String remainder = content.substring(3).trim();
                int secondDash = remainder.indexOf("---");
                String frontBlock;
                if (secondDash >= 0) {
                    frontBlock = remainder.substring(0, secondDash).trim();
                } else {
                    frontBlock = remainder;
                }

                for (String line : frontBlock.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    // Simple key: value parsing, no YAML library needed
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (value.startsWith("\"") || value.startsWith("'")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    switch (key) {
                        case "name" -> name = value;
                        case "description" -> description = value;
                        case "version" -> version = value;
                        case "triggers" -> triggers = parseTriggers(value);
                        case "requires" -> requires = parseTriggers(value); // same comma-list syntax
                        case "permissions" -> { /* nested; skip for now */ }
                    }
                }
            }

            if (name == null || name.isEmpty()) {
                errors.add("SKILL.md missing 'name' in frontmatter: " + file);
            }

        } catch (IOException e) {
            errors.add("Failed to read " + file + ": " + e.getMessage());
        }

        return new SkillManifest(name, description, version, file.toString(), triggers, requires, permissions, errors);
    }

    /// Splits a `triggers:` frontmatter value into individual phrases. Accepts ASCII commas,
    /// full-width commas and enumeration commas as separators so authors can write naturally
    /// in either language: `triggers: 游戏崩溃, crash, 闪退、进不去`.
    static List<String> parseTriggers(String value) {
        if (value.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,，、]")) {
            String t = part.trim();
            // strip optional per-phrase quotes: triggers: "crash", "闪退"
            if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"")
                    || t.startsWith("'") && t.endsWith("'"))) {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /// Reads a skill's body (everything after the frontmatter block for a Markdown skill, or the
    /// eagerly-loaded {@code body} field for a JSON-sourced built-in one), capped at
    /// {@code maxChars}. Returns an empty string when the content is unavailable — callers treat
    /// that as "nothing to inject" rather than an error.
    public static String readBody(SkillManifest manifest, int maxChars) {
        if (manifest.isBuiltin()) {
            // No on-disk SKILL.md to lazily re-read here — the body was already parsed once from
            // the classpath JSON resource's "body" field in loadBuiltinSkills.
            String content = manifest.getBody() == null ? "" : manifest.getBody().strip();
            if (content.length() > maxChars) {
                content = content.substring(0, maxChars) + "\n…[truncated]";
            }
            return content;
        }
        if (manifest.getPath() == null) return "";
        try {
            Path file = Path.of(manifest.getPath());
            if (Files.size(file) > 128L * 1024) return "";
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.startsWith("---")) {
                int end = content.indexOf("---", 3);
                if (end >= 0) {
                    content = content.substring(end + 3);
                }
            }
            content = content.strip();
            if (content.length() > maxChars) {
                content = content.substring(0, maxChars) + "\n…[truncated — read the SKILL.md file for the rest]";
            }
            return content;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /// Names of built-in skills bundled with the app under classpath
    /// `/assets/skills/&lt;name&gt;/SKILL.json`, loaded directly from there (in memory, no
    /// filesystem I/O) by {@link #loadBuiltinSkills} on every {@link SkillRegistry#refresh()}.
    /// A name containing a `/` is operation-level (`<domain>/<action-slug>`); one without is
    /// scenario-level — see {@link #loadBuiltinSkills}.
    private static final List<String> BUILTIN_SKILLS = List.of(
            // Scenario-level (depth 1): thin orchestration indexes with a requires: line.
            "config-hmcl-ae", "config-hmcl",
            "diagnose-crash", "optimize-performance", "manage-accounts", "install-and-mod",
            "multiplayer-and-servers", "manage-worlds-and-backups", "edit-save-data",
            "resourcepacks-and-shaders", "fix-download-and-network", "java-and-memory",
            "use-modpacks",
            // dev-mode is scenario-level by directory depth too, but is NOT retrieval-matched like
            // the others above: it has no triggers: line and is explicitly excluded from
            // AiPromptBuilder#matchSkills's candidate pool. It is injected by its own dedicated,
            // deterministic per-turn check (AiPromptBuilder#isDevModeTriggered) instead — see that
            // skill's SKILL.md and AiPromptBuilder#devModeBlock for why.
            "dev-mode",
            // Operation-level (depth 2, "<domain>/<action-slug>"): the tool-usage playbooks the
            // scenario skills above orchestrate via requires:, and directly BM25/load_skill
            // reachable on their own.
            "instance/create-instance", "instance/set-isolation",
            "instance/mods-install", "instance/mods-toggle-and-remove",
            "instance/set-memory", "instance/java-runtime", "instance/game-options",
            "instance/worlds-backup", "instance/worlds-import",
            "instance/modpacks-install", "instance/modpacks-export",
            "instance/resourcepacks-and-shaders",
            "nbt/edit-values", "nbt/transfer-player-data",
            "account/login-and-switch", "account/set-skin",
            "game/launch-and-verify",
            "search/choose-source-and-verify");

    /// Loads every skill in {@link #BUILTIN_SKILLS} straight from its bundled classpath JSON
    /// resource (`/assets/skills/<name>/SKILL.json`), entirely in memory. Unlike the old
    /// Markdown/{@link #seedBuiltinSkills} path, nothing is written to disk: built-in skills are
    /// never materialized under the user's skillsDir at all, which is exactly what makes them fully
    /// invisible when a user browses that folder — on top of the settings-page list filter
    /// (`AISettingsPage#buildSkillsTab` filters on {@link SkillManifest#isBuiltin()}). Called by
    /// {@link SkillRegistry#refresh()} and merged with {@link #scanDirectory}'s user/custom results.
    ///
    /// A malformed or missing bundled resource is skipped rather than surfaced as a manifest error
    /// (shouldn't happen for shipped content; fail soft exactly like {@link #parseSkillMd} does for
    /// a bad file it can't even read).
    public static List<SkillManifest> loadBuiltinSkills() {
        List<SkillManifest> manifests = new ArrayList<>();
        for (String skill : BUILTIN_SKILLS) {
            String resource = "/assets/skills/" + skill + "/SKILL.json";
            try (var in = SkillLoader.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                manifests.add(parseSkillJson(json, resource, !skill.contains("/")));
            } catch (IOException ignored) {
            }
        }
        return manifests;
    }

    /// Gson target for a built-in skill's JSON resource — deliberately a plain field-holding DTO,
    /// not {@link SkillManifest} itself, so the wire format (nullable fields, absent-vs-empty
    /// arrays) stays decoupled from the manifest's own invariants (e.g. `triggers`/`requires`
    /// always non-null, defaulted below).
    private static final class SkillJson {
        @Nullable String name;
        @Nullable String description;
        @Nullable String version;
        @Nullable List<String> triggers;
        @Nullable List<String> requires;
        @Nullable Map<String, Object> permissions;
        @Nullable String body;
    }

    /// Parses one built-in skill's JSON resource into a {@link SkillManifest} — the JSON-format
    /// counterpart of {@link #parseSkillMd}. Retrieval (matching/ranking in {@link SkillMatcher}
    /// and {@link SkillIndex}) reads only the resulting manifest's name/description/triggers
    /// fields, exactly as it does for a Markdown-sourced one, so switching this storage format did
    /// NOT need to touch either of those classes.
    static SkillManifest parseSkillJson(String json, String resourcePath, boolean scenarioLevel) {
        List<String> errors = new ArrayList<>();
        try {
            SkillJson parsed = GSON.fromJson(json, SkillJson.class);
            if (parsed == null) {
                errors.add("Empty/invalid SKILL.json: " + resourcePath);
                return new SkillManifest(null, null, null, resourcePath, List.of(), List.of(), Map.of(), errors,
                        true, scenarioLevel, null);
            }
            if (parsed.name == null || parsed.name.isEmpty()) {
                errors.add("SKILL.json missing 'name': " + resourcePath);
            }
            return new SkillManifest(parsed.name, parsed.description, parsed.version, resourcePath,
                    parsed.triggers == null ? List.of() : parsed.triggers,
                    parsed.requires == null ? List.of() : parsed.requires,
                    parsed.permissions == null ? Map.of() : parsed.permissions,
                    errors, true, scenarioLevel, parsed.body);
        } catch (JsonSyntaxException e) {
            errors.add("Failed to parse " + resourcePath + ": " + e.getMessage());
            return new SkillManifest(null, null, null, resourcePath, List.of(), List.of(), Map.of(), errors,
                    true, scenarioLevel, null);
        }
    }

    /// Name of the sidecar manifest file (directly under {@code skillsDir}) recording, for every
    /// built-in skill we've ever seeded, the SHA-256 hash of the content we last wrote for it. Lets
    /// {@link #seedSkills} tell "unmodified since we seeded it" (safe to refresh) apart from
    /// "user hand-edited it" (leave alone) without needing a full backup copy.
    private static final String SEEDED_MANIFEST_FILE = ".seeded-manifest";

    /// Copies bundled built-in skills into the skills directory as `SKILL.md` files, refreshing the
    /// managed copy so it tracks the app version. User-created skills are left untouched, as always.
    ///
    /// This used to unconditionally overwrite every target on every call (i.e. every
    /// {@link SkillRegistry#refresh()}, which runs every time the AI chat/settings page opens),
    /// silently discarding any hand-edit a user made to one of these files. A target whose on-disk
    /// content no longer matches the hash recorded the last time we seeded it — meaning the user
    /// changed it — was then made to skip instead of clobber; see {@link #seedSkills}.
    ///
    /// **No longer called for built-in skills.** Since the migration to JSON (see
    /// {@link #loadBuiltinSkills}), built-ins are loaded directly from the classpath in memory and
    /// are never materialized under skillsDir in the first place — so even if this method were still
    /// invoked, {@code resources} below would always come back empty (`/assets/skills/*/SKILL.md`
    /// no longer exists as a bundled resource), and the hash-check protection in {@link #seedSkills}
    /// would never have anything to compare. {@link SkillRegistry#refresh()} no longer calls this
    /// method at all. It (and {@link #seedSkills}) are kept as-is — still directly exercised by
    /// {@code SkillLoaderSeedingTest} — as the generic "seed managed Markdown files, protecting hand
    /// edits" mechanism, in case a future non-built-in seeding use case needs it again.
    public static void seedBuiltinSkills(Path skillsDir) {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (String skill : BUILTIN_SKILLS) {
            String resource = "/assets/skills/" + skill + "/SKILL.md";
            try (var in = SkillLoader.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                resources.put(skill, in.readAllBytes());
            } catch (IOException ignored) {
            }
        }
        seedSkills(skillsDir, resources);
    }

    /// Core seeding logic, factored out from {@link #seedBuiltinSkills} so tests can drive it with
    /// synthetic content instead of real classpath resources: for each {@code skill path -> content
    /// bytes} pair, writes {@code skillsDir/<skill>/SKILL.md} UNLESS that target already exists and
    /// its current content's hash differs from the hash recorded in the sidecar manifest the last
    /// time we seeded it (i.e. the user hand-edited it since) — in that case, it is left untouched.
    /// The manifest is updated with every skill actually written, then persisted back to disk.
    static void seedSkills(Path skillsDir, Map<String, byte[]> resources) {
        Map<String, String> manifest = readSeededManifest(skillsDir);
        Map<String, String> updatedManifest = new LinkedHashMap<>(manifest);
        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            String skill = resource.getKey();
            byte[] bytes = resource.getValue();
            try {
                Path target = skillsDir.resolve(skill).resolve("SKILL.md");
                if (Files.exists(target)) {
                    String lastSeededHash = manifest.get(skill);
                    if (lastSeededHash != null && !sha256Hex(Files.readAllBytes(target)).equals(lastSeededHash)) {
                        // User hand-edited this file since we last seeded it — leave it alone.
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                updatedManifest.put(skill, sha256Hex(bytes));
            } catch (IOException ignored) {
            }
        }
        writeSeededManifest(skillsDir, updatedManifest);
    }

    /// Reads the sidecar seeded-hash manifest (`skill path=hex sha256` lines), or an empty map if
    /// absent/unreadable — the latter is treated as "never seeded before", so every currently
    /// existing target is seeded once more (matching the pre-fix behavior for that one transition).
    private static Map<String, String> readSeededManifest(Path skillsDir) {
        Path f = skillsDir.resolve(SEEDED_MANIFEST_FILE);
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void writeSeededManifest(Path skillsDir, Map<String, String> manifest) {
        Path f = skillsDir.resolve(SEEDED_MANIFEST_FILE);
        try {
            Files.createDirectories(skillsDir);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every conforming JVM per the Java spec.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
