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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [InstanceMaintenanceTool]'s deterministic contract and guard branches:
/// the metadata/description contract, the "no profile selected" guard (fires before any
/// `scope`/`confirm` parsing, so it needs no [ProfileFixture]), the unknown-`scope` rejection,
/// the `confirm=true` gate on both `clear_*` scopes (asserting the target directory survives
/// untouched when rejected — the "reject-and-don't-delete" contract called out in the tool's own
/// Javadoc), the no-`scope` report branch, and the real `clean_junk` delete path (the one branch
/// that is both destructive AND fully deterministic — plain filesystem I/O, no network).
///
/// NOT exercised here, and left to the manual release checklist:
/// - `redownload_assets` — needs a real network round-trip through
///   [ContentToolSupport#runDownloadWithFallback] / [org.jackhuang.hmcl.download.game.GameAssetDownloadTask];
/// - the CONFIRMED `clear_resources` / `clear_libraries` delete paths — deterministic in
///   principle, but exercising them meaningfully would mean asserting against this tool's own
///   `FileUtils.deleteDirectoryQuietly` calls with no independent check, and the multi-instance
///   "refused while ANY instance in the profile is running" fan-out
///   ([InstanceMaintenanceTool#checkNoInstanceRunning]) needs [GameResourceGuard]'s live-process
///   probe, which this suite does not stub;
/// - grouping/sizing across multiple real instances sharing one base directory.
public final class InstanceMaintenanceToolTest {

    private final InstanceMaintenanceTool tool = new InstanceMaintenanceTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("instance_maintenance", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("clean_junk"), "must document the clean_junk scope: " + description);
        assertTrue(description.contains("redownload_assets"), "must document the redownload_assets scope: " + description);
        assertTrue(description.contains("clear_resources"), "must document the clear_resources scope: " + description);
        assertTrue(description.contains("clear_libraries"), "must document the clear_libraries scope: " + description);
        assertTrue(description.contains("confirm=true"),
                "must document that the destructive scopes require confirm=true: " + description);
    }

    @Test
    void unknownScopeIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "scope", "not_a_real_scope"));

            assertFalse(result.isSuccess(), "an unknown scope must be rejected, got: " + result.getOutput());
            assertTrue(result.getError().contains("Unknown maintenance scope"),
                    "the failure must name the bad scope: " + result.getError());
            assertTrue(result.getError().contains("clean_junk") && result.getError().contains("clear_libraries"),
                    "the failure must list the valid scopes: " + result.getError());
        }
    }

    @Test
    void clearResourcesWithoutConfirmIsRejectedAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path base = fx.repository().getBaseDirectory();
            Path assetsDir = base.resolve("assets");
            Files.createDirectories(assetsDir);
            Files.writeString(assetsDir.resolve("indexes.json"), "asset-index-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "scope", "clear_resources"));

            assertFalse(result.isSuccess(), "clear_resources without confirm=true must be rejected");
            assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("confirm"),
                    "the failure must mention the confirm=true requirement: " + result.getError());
            assertTrue(Files.exists(assetsDir.resolve("indexes.json")),
                    "an unconfirmed call must not delete anything");
        }
    }

    @Test
    void clearLibrariesWithoutConfirmIsRejectedAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path librariesDir = fx.repository().getBaseDirectory().resolve("libraries");
            Files.createDirectories(librariesDir);
            Files.writeString(librariesDir.resolve("some-lib.jar"), "library-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "scope", "clear_libraries"));

            assertFalse(result.isSuccess(), "clear_libraries without confirm=true must be rejected");
            assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("confirm"),
                    "the failure must mention the confirm=true requirement: " + result.getError());
            assertTrue(Files.exists(librariesDir.resolve("some-lib.jar")),
                    "an unconfirmed call must not delete anything");
        }
    }

    @Test
    void noScopeReportsAllFourScopesReadOnly() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst"));

            assertTrue(result.isSuccess(), "a call with no scope must report, not fail: " + result.getError());
            String output = result.getOutput();
            assertTrue(output.contains("clean_junk"), "report must list clean_junk: " + output);
            assertTrue(output.contains("redownload_assets"), "report must list redownload_assets: " + output);
            assertTrue(output.contains("clear_resources"), "report must list clear_resources: " + output);
            assertTrue(output.contains("clear_libraries"), "report must list clear_libraries: " + output);
        }
    }

    @Test
    void cleanJunkDeletesLogsAndCrashReportsButLeavesModsAlone() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path base = fx.repository().getBaseDirectory();
            Files.createDirectories(base.resolve("logs"));
            Files.writeString(base.resolve("logs").resolve("latest.log"), "log-bytes");
            Files.createDirectories(base.resolve("crash-reports"));
            Files.writeString(base.resolve("crash-reports").resolve("crash-1.txt"), "crash-bytes");
            Files.createDirectories(base.resolve("mods"));
            Files.writeString(base.resolve("mods").resolve("Sodium.jar"), "mod-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "scope", "clean_junk"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Cleaned junk files"), "unexpected message: " + result.getOutput());
            assertFalse(Files.exists(base.resolve("logs")), "logs/ must be deleted");
            assertFalse(Files.exists(base.resolve("crash-reports")), "crash-reports/ must be deleted");
            assertTrue(Files.exists(base.resolve("mods").resolve("Sodium.jar")),
                    "clean_junk must not touch mods/ or any other content");
        }
    }
}
