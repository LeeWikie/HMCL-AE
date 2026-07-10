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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the `triggers:` frontmatter parsing (mixed-language separators, quotes) and the
/// frontmatter-stripping body reader that auto-injection uses.
public class SkillLoaderTriggersTest {

    @Test
    public void parsesMixedSeparatorsAndQuotes() {
        List<String> t = SkillLoader.parseTriggers("崩溃, crash，闪退、\"won't start\", '进不去'");
        assertEquals(List.of("崩溃", "crash", "闪退", "won't start", "进不去"), t);
    }

    @Test
    public void emptyValueYieldsNoTriggers() {
        assertTrue(SkillLoader.parseTriggers("").isEmpty());
        assertTrue(SkillLoader.parseTriggers(" ,， 、 ").isEmpty());
    }

    @Test
    public void manifestCarriesTriggersFromFrontmatter(@TempDir Path dir) throws Exception {
        Path skillDir = dir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: test skill
                version: 1.0
                triggers: 崩溃, crash
                ---

                # Body

                Step one.
                """);
        List<SkillManifest> found = SkillLoader.scanDirectory(dir);
        assertEquals(1, found.size());
        SkillManifest m = found.get(0);
        assertTrue(m.isValid());
        assertEquals(List.of("崩溃", "crash"), m.getTriggers());
    }

    @Test
    public void manifestWithoutTriggersLineIsStillValid(@TempDir Path dir) throws Exception {
        Path skillDir = dir.resolve("plain");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: plain
                description: no triggers here
                version: 1.0
                ---
                Body text.
                """);
        SkillManifest m = SkillLoader.scanDirectory(dir).get(0);
        assertTrue(m.isValid());
        assertTrue(m.getTriggers().isEmpty());
    }

    @Test
    public void readBodyStripsFrontmatterAndCaps(@TempDir Path dir) throws Exception {
        Path skillDir = dir.resolve("body");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: body
                version: 1.0
                ---

                # Playbook

                Do the thing.
                """);
        SkillManifest m = SkillLoader.scanDirectory(dir).get(0);

        String body = SkillLoader.readBody(m, 10_000);
        assertTrue(body.startsWith("# Playbook"), "frontmatter must be stripped, got: " + body);
        assertTrue(body.contains("Do the thing."));

        String capped = SkillLoader.readBody(m, 5);
        assertTrue(capped.contains("[truncated"));
    }

    @Test
    public void readBodyOfMissingFileIsEmpty() {
        SkillManifest ghost = new SkillManifest("g", "d", "1", "/definitely/not/here/SKILL.md",
                List.of(), java.util.Map.of(), List.of());
        assertEquals("", SkillLoader.readBody(ghost, 1000));
    }
}
