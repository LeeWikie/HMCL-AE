/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ToggleModTool]'s substring-match resolution (none / exactly one / ambiguous), the
/// enable/disable toggle logic (default-toggle, forced enable/disable, boolean-as-string, already
/// in the target state), and the missing-mods-directory branch — using a real
/// [ProfileFixture]-backed instance on disk (this tool's only external dependencies are
/// [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository], both
/// driven by real file-system state here, exactly like [DeleteInstanceToolTest]).
public final class ToggleModToolTest {

    private final ToggleModTool tool = new ToggleModTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("toggle_mod", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("mod"));
    }

    @Test
    void missingModParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("mod"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void missingModsDirectoryFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("NoMods");
            ToolResult result = tool.execute(Map.of("instance", "NoMods", "mod", "foo"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Mods directory does not exist"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void noMatchFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI.jar"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "NoSuchMod"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No mod file matching"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void ambiguousMatchFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI-forge.jar"), "a");
            Files.writeString(modsDir.resolve("JEI-fabric.jar"), "b");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "jei"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ambiguous"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("JEI-forge.jar"));
            assertTrue(result.getError().contains("JEI-fabric.jar"));
        }
    }

    @Test
    void togglesEnabledModToDisabledByDefault() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path jar = modsDir.resolve("Sodium.jar");
            Files.writeString(jar, "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("disabled"), "unexpected message: " + result.getOutput());
            assertFalse(Files.exists(jar), "the original .jar must be gone after disabling");
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar.disabled")));
        }
    }

    @Test
    void togglesDisabledModToEnabledByDefault() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("Sodium.jar.disabled"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("enabled"), "unexpected message: " + result.getOutput());
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar")));
            assertFalse(Files.exists(modsDir.resolve("Sodium.jar.disabled")));
        }
    }

    @Test
    void forcingAlreadyCurrentStateMakesNoChange() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path jar = modsDir.resolve("Sodium.jar");
            Files.writeString(jar, "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium", "enable", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("already enabled"), "unexpected message: " + result.getOutput());
            assertTrue(Files.exists(jar), "no rename should have happened");
        }
    }

    @Test
    void forceDisableAcceptsStringBoolean() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("Sodium.jar"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium", "enable", "FALSE"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar.disabled")));
        }
    }

    @Test
    void invalidEnableValueFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("Sodium.jar"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium", "enable", "maybe"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("must be a boolean"), "unexpected message: " + result.getError());
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar")), "no rename should have happened on invalid input");
        }
    }

    @Test
    void targetAlreadyExistsFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            // "Sodium.jar" already exists (blocks the enable target); query by the FULL disabled
            // file name so it uniquely matches "Sodium.jar.disabled" and not "Sodium.jar" (a
            // substring query like "Sodium" would match BOTH and hit the ambiguous branch instead,
            // since "Sodium.jar.disabled" always contains "Sodium.jar" as a prefix).
            Files.writeString(modsDir.resolve("Sodium.jar"), "already-enabled");
            Files.writeString(modsDir.resolve("Sodium.jar.disabled"), "stale-leftover");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium.jar.disabled"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
            assertEquals("already-enabled", Files.readString(modsDir.resolve("Sodium.jar")));
            assertEquals("stale-leftover", Files.readString(modsDir.resolve("Sodium.jar.disabled")));
        }
    }
}
