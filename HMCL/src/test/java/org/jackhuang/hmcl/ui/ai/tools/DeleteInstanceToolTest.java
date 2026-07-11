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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.event.Event;
import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.event.RemoveVersionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Covers [DeleteInstanceTool]'s parameter-resolution/validation branches, the confirm-gate,
/// the [GameResourceGuard] occupancy guards (live-process probe + external-launch session.lock
/// fallback), and the unified [org.jackhuang.hmcl.game.DefaultGameRepository#removeVersionFromDisk(String, boolean)]
/// removal path (RemoveVersionEvent firing / veto), using a real [ProfileFixture]-backed
/// instance on disk rather than mocks.
public final class DeleteInstanceToolTest {

    @AfterEach
    void restoreInstanceRunningProbe() {
        GameResourceGuard.setInstanceRunningProbeForTesting(null);
    }

    @Test
    void missingInstanceParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("confirm", true));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("instance"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void nonexistentInstanceFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "confirm", true));

            assertFalse(result.isSuccess());
            // T4: the missing-instance failure is now the shared resolveInstance envelope carrying
            // the real instance names, instead of a bare "No such instance".
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "the failure should list the real instance names (candidate list): " + result.getError());
        }
    }

    @Test
    void notConfirmedFailsAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("KeepMe");
            Path versionDir = fx.repository().getVersionRoot("KeepMe");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "KeepMe"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Not confirmed"), "unexpected message: " + result.getError());
            assertTrue(Files.isDirectory(versionDir), "instance directory must be untouched when not confirmed");
            assertTrue(fx.repository().hasVersion("KeepMe"));
        }
    }

    @Test
    void confirmedDeletePermanentlyRemovesInstanceFromDiskAndRepository() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToDelete");
            Path versionDir = fx.repository().getVersionRoot("ToDelete");
            assertTrue(Files.isDirectory(versionDir));
            // toRecycleBin=false forces the permanent/native delete path regardless of platform
            // trash support, so this test is deterministic everywhere.
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "ToDelete", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertFalse(Files.exists(versionDir), "version directory should be gone from disk");
            assertFalse(fx.repository().hasVersion("ToDelete"), "repository should have forgotten the version");
        }
    }

    @Test
    void confirmAcceptsStringTrueCaseInsensitively() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToDelete2");
            Path versionDir = fx.repository().getVersionRoot("ToDelete2");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "ToDelete2", "confirm", "TRUE"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertFalse(Files.exists(versionDir));
        }
    }

    @Test
    void confirmedDeleteToRecycleBinMovesInstanceWhenTrashSupported() throws Exception {
        assumeTrue(FileTrash.trashSupported(), "no recycle-bin support on this platform/environment");
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToTrash");
            Path versionDir = fx.repository().getVersionRoot("ToTrash");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> true);

            ToolResult result = tool.execute(Map.of("instance", "ToTrash", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertNotNull(result.getOutput());
            assertFalse(Files.exists(versionDir), "version directory should be gone from its original location");
            assertFalse(fx.repository().hasVersion("ToTrash"));
        }
    }

    // -------------------------------------------------------------------------------
    // G2: occupancy guards — running instance / externally-launched game (session.lock)
    // -------------------------------------------------------------------------------

    @Test
    void runningInstanceIsRefusedAndNothingDeleted() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("RunningPack");
            Path versionDir = fx.repository().getVersionRoot("RunningPack");
            GameResourceGuard.setInstanceRunningProbeForTesting("RunningPack"::equals);
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "RunningPack", "confirm", true));

            assertFalse(result.isSuccess(), "deleting a running instance must be refused");
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("game(action=\"stop\", instance=\"RunningPack\")"),
                    "rejection should direct the model to stop_instance first: " + result.getError());
            assertTrue(Files.isDirectory(versionDir), "version directory must be untouched");
            assertTrue(fx.repository().hasVersion("RunningPack"));
        }
    }

    @Test
    void lockedWorldUnderVersionRootIsRefusedAndNothingDeleted() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ExternalRun");
            Path versionDir = fx.repository().getVersionRoot("ExternalRun");
            Path worldDir = versionDir.resolve("saves").resolve("LockedWorld");
            Files.createDirectories(worldDir);
            Path sessionLock = worldDir.resolve("session.lock");
            Files.write(sessionLock, new byte[]{1});
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            // Simulate a game launched OUTSIDE HMCL: the process table sees nothing, but the
            // session holds the world's session.lock (a same-JVM FileLock trips the guard's
            // OverlappingFileLockException branch, same as the native World#isLocked).
            try (FileChannel holder = FileChannel.open(sessionLock, StandardOpenOption.WRITE);
                 FileLock ignored = holder.lock()) {
                ToolResult result = tool.execute(Map.of("instance", "ExternalRun", "confirm", true));

                assertFalse(result.isSuccess(), "deleting an instance with a locked world must be refused");
                assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                        "not a well-formed envelope: " + result.getError());
                assertTrue(result.getError().contains("LockedWorld"),
                        "rejection should name the locked world: " + result.getError());
            }
            assertTrue(Files.isDirectory(versionDir), "version directory must be untouched");
            assertTrue(fx.repository().hasVersion("ExternalRun"));
        }
    }

    @Test
    void unheldSessionLockFileDoesNotBlockDeletion() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("IdleWorlds");
            Path versionDir = fx.repository().getVersionRoot("IdleWorlds");
            Path worldDir = versionDir.resolve("saves").resolve("IdleWorld");
            Files.createDirectories(worldDir);
            // A session.lock left behind by a past session, held by nobody: must not block.
            Files.write(worldDir.resolve("session.lock"), new byte[]{1});
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "IdleWorlds", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertFalse(Files.exists(versionDir));
            assertFalse(fx.repository().hasVersion("IdleWorlds"));
        }
    }

    // -------------------------------------------------------------------------------
    // G12: unified removal path — RemoveVersionEvent always fires, and may veto
    // -------------------------------------------------------------------------------

    @Test
    void removeVersionEventFiresOnRecycleBinPreferredPath() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("TrashHooked");
            Path versionDir = fx.repository().getVersionRoot("TrashHooked");
            List<String> removedVersions = new CopyOnWriteArrayList<>();
            // registerWeak + a strong local reference scoped to this test: the listener dies with
            // the test, so it cannot leak DENY/observation behavior into other tests (the event
            // bus has no unregister API).
            Consumer<RemoveVersionEvent> listener = event -> removedVersions.add(event.getVersion());
            EventBus.EVENT_BUS.channel(RemoveVersionEvent.class).registerWeak(listener);
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> true);

            ToolResult result = tool.execute(Map.of("instance", "TrashHooked", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(removedVersions.contains("TrashHooked"),
                    "RemoveVersionEvent must fire on the recycle-bin-preferred path too (it was bypassed before)");
            assertFalse(Files.exists(versionDir), "version directory should be gone from its original location");
            assertFalse(fx.repository().hasVersion("TrashHooked"));
        }
    }

    @Test
    void vetoedRemovalFailsAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("VetoedPack");
            Path versionDir = fx.repository().getVersionRoot("VetoedPack");
            Consumer<RemoveVersionEvent> veto = event -> {
                if ("VetoedPack".equals(event.getVersion())) {
                    event.setResult(Event.Result.DENY);
                }
            };
            EventBus.EVENT_BUS.channel(RemoveVersionEvent.class).registerWeak(veto);
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> true);

            ToolResult result = tool.execute(Map.of("instance", "VetoedPack", "confirm", true));

            assertFalse(result.isSuccess(), "a vetoed removal must be reported as a failure");
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(Files.isDirectory(versionDir), "version directory must be untouched after a veto");
            assertTrue(fx.repository().hasVersion("VetoedPack"));
        }
    }

    @Test
    void singleArgRemoveVersionFromDiskStillRemoves() throws Exception {
        // Backward-compat lock for the DefaultGameRepository signature split: the historical
        // single-argument overload must keep working (it delegates with moveToTrash=true).
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("LegacySignature");
            Path versionDir = fx.repository().getVersionRoot("LegacySignature");

            assertTrue(fx.repository().removeVersionFromDisk("LegacySignature"));

            assertFalse(Files.exists(versionDir));
        }
    }

    @Test
    void toolMetadataIsSensible() {
        DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);
        assertEquals("delete_instance", tool.getName());
        assertTrue(tool.getDescription().toLowerCase(Locale.ROOT).contains("delete"));
    }
}
