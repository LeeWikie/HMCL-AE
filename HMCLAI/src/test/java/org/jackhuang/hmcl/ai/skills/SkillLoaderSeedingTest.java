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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// {@link SkillLoader#seedSkills} used to unconditionally overwrite every built-in SKILL.md on
/// every {@link SkillRegistry#refresh()} call (i.e. every time the AI chat/settings page opens),
/// silently discarding any hand-edit a user made to one of these files. It now records a per-file
/// content hash in a sidecar manifest and skips overwriting a target whose on-disk hash has drifted
/// from what was last seeded — these tests drive {@link SkillLoader#seedSkills} directly with
/// synthetic content (rather than the real classpath resources {@link SkillLoader#seedBuiltinSkills}
/// reads) so both branches are exercised precisely.
public final class SkillLoaderSeedingTest {

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    public void firstSeedWritesTheFile(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v1-content".getBytes(StandardCharsets.UTF_8)));

        Path target = skillsDir.resolve("demo").resolve("SKILL.md");
        assertTrue(Files.exists(target));
        assertEquals("v1-content", read(target));
    }

    /// An unmodified seeded file (on-disk hash still matches what was last seeded) must be
    /// refreshed normally when the bundled content changes — i.e. the fix does not turn seeding
    /// into a one-shot no-op.
    @Test
    public void unmodifiedSeededFileIsRefreshedWhenBundledContentChanges(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v1-content".getBytes(StandardCharsets.UTF_8)));
        // Simulate an app update shipping a new version of the built-in skill; the user never
        // touched the file in between.
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v2-content".getBytes(StandardCharsets.UTF_8)));

        Path target = skillsDir.resolve("demo").resolve("SKILL.md");
        assertEquals("v2-content", read(target), "an untouched seeded file must track the new bundled version");
    }

    /// A user hand-edit to a previously-seeded file must be PRESERVED across a later refresh(),
    /// instead of being silently clobbered by the next bundled version.
    @Test
    public void userHandEditIsPreservedAcrossRefresh(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v1-content".getBytes(StandardCharsets.UTF_8)));

        Path target = skillsDir.resolve("demo").resolve("SKILL.md");
        Files.writeString(target, "user's hand-edited content", StandardCharsets.UTF_8);

        // A later refresh ships a new bundled version, but the user's edit must win.
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v2-content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("user's hand-edited content", read(target),
                "a hand-edited file must not be overwritten by the next seed");
    }

    /// Multiple refresh() cycles after a hand-edit must keep preserving it (not just the very next
    /// call) — the manifest itself must not get updated for a skipped target.
    @Test
    public void userHandEditStaysPreservedAcrossMultipleRefreshes(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v1-content".getBytes(StandardCharsets.UTF_8)));
        Path target = skillsDir.resolve("demo").resolve("SKILL.md");
        Files.writeString(target, "user's hand-edited content", StandardCharsets.UTF_8);

        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v2-content".getBytes(StandardCharsets.UTF_8)));
        SkillLoader.seedSkills(skillsDir, Map.of("demo", "v3-content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("user's hand-edited content", read(target));
    }

    /// Other built-in skills unrelated to the hand-edited one must keep updating normally — the
    /// skip is per-file, not a blanket freeze of the whole seeding pass.
    @Test
    public void unrelatedSkillsStillUpdateWhenOneIsHandEdited(@TempDir Path dir) throws Exception {
        Path skillsDir = dir.resolve("skills");
        SkillLoader.seedSkills(skillsDir, Map.of(
                "demo-a", "a-v1".getBytes(StandardCharsets.UTF_8),
                "demo-b", "b-v1".getBytes(StandardCharsets.UTF_8)));

        Path targetA = skillsDir.resolve("demo-a").resolve("SKILL.md");
        Path targetB = skillsDir.resolve("demo-b").resolve("SKILL.md");
        Files.writeString(targetA, "hand-edited a", StandardCharsets.UTF_8);

        SkillLoader.seedSkills(skillsDir, Map.of(
                "demo-a", "a-v2".getBytes(StandardCharsets.UTF_8),
                "demo-b", "b-v2".getBytes(StandardCharsets.UTF_8)));

        assertEquals("hand-edited a", read(targetA), "the edited skill must be preserved");
        assertEquals("b-v2", read(targetB), "the untouched sibling skill must still update");
    }
}
