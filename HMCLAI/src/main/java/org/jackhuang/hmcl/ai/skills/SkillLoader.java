package org.jackhuang.hmcl.ai.skills;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Scans a directory tree for SKILL.md files and parses their YAML
/// frontmatter into {@link SkillManifest} instances.
@NotNullByDefault
public final class SkillLoader {

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*$", Pattern.MULTILINE);

    public static List<SkillManifest> scanDirectory(Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) return List.of();

        List<SkillManifest> manifests = new ArrayList<>();
        try (var files = Files.walk(skillsDir, 2)) {
            files.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                 .forEach(p -> manifests.add(parseSkillMd(p)));
        } catch (IOException ignored) {
        }
        return manifests;
    }

    static SkillManifest parseSkillMd(Path file) {
        List<String> errors = new ArrayList<>();
        String name = null;
        String description = null;
        String version = null;
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

        return new SkillManifest(name, description, version, file.toString(), permissions, errors);
    }

    /// Names of skills bundled with the app under classpath `/assets/skills/&lt;name&gt;/SKILL.md`.
    /// They are extracted into the user skills directory so they appear as real, readable,
    /// listable skills and stay up to date with the app.
    private static final List<String> BUILTIN_SKILLS = List.of("config-hmcl-ae", "config-hmcl");

    /// Copies bundled built-in skills into the skills directory, overwriting the managed
    /// copy so it tracks the app version. User-created skills are left untouched.
    public static void seedBuiltinSkills(Path skillsDir) {
        for (String skill : BUILTIN_SKILLS) {
            String resource = "/assets/skills/" + skill + "/SKILL.md";
            try (var in = SkillLoader.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                byte[] bytes = in.readAllBytes();
                Path target = skillsDir.resolve(skill).resolve("SKILL.md");
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException ignored) {
            }
        }
    }
}
