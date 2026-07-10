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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// G8: the shutdown hook's snapshot of still-RUNNING jobs (ai-jobs-interrupted.json) — written by
/// {@code writeInterruptedSnapshot} (the hook body, tested directly so the JVM survives) and read
/// back exactly once by {@code consumeInterruptedSnapshot} on next startup. Without this the
/// manager is purely in-memory on daemon threads and a window close vaporises every in-flight
/// background task without a trace.
public final class AiJobManagerShutdownSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void runningJobsAreWrittenAndConsumedExactlyOnce() throws Exception {
        AiJobManager mgr = AiJobManager.getInstance();
        Path file = tempDir.resolve("ai-jobs-interrupted.json");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String sessionId = "snapshot-" + System.nanoTime();
        String id = mgr.submit(sessionId, "mods_install", "批量安装 3 个模组", () -> {
            started.countDown();
            release.await();
            return ToolResult.success("done");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "job should be running");
        try {
            mgr.writeInterruptedSnapshot(file);

            assertTrue(Files.isRegularFile(file), "a RUNNING job must produce a snapshot file");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(json.contains("mods_install"), json);
            assertTrue(json.contains("批量安装 3 个模组"), json);
            assertTrue(json.contains(sessionId), json);

            List<AiJobManager.InterruptedJobRecord> records = AiJobManager.consumeInterruptedSnapshot(file);
            AiJobManager.InterruptedJobRecord ours = records.stream()
                    .filter(r -> id.equals(r.id)).findFirst().orElse(null);
            assertNotNull(ours, "the running job must be in the consumed snapshot");
            assertEquals("mods_install", ours.toolName);
            assertEquals("批量安装 3 个模组", ours.label);
            assertEquals(sessionId, ours.sessionId);
            assertTrue(ours.startedAtMillis > 0);

            assertFalse(Files.exists(file), "consume must delete the snapshot (report exactly once)");
            assertTrue(AiJobManager.consumeInterruptedSnapshot(file).isEmpty(),
                    "a second consume finds nothing");
        } finally {
            release.countDown();
            mgr.cancel(id);
        }
    }

    @Test
    void snapshotWithNothingRunningDeletesAStaleFile() throws Exception {
        AiJobManager mgr = AiJobManager.getInstance();
        // Clear out any RUNNING leftovers from sibling tests so "nothing running" really holds.
        for (AiJobManager.Job job : mgr.list()) {
            if (job.getStatus() == AiJobManager.Status.RUNNING) {
                mgr.cancel(job.getId());
            }
        }
        Path file = tempDir.resolve("stale.json");
        Files.writeString(file, "[{\"id\":\"999\",\"toolName\":\"old\"}]", StandardCharsets.UTF_8);

        mgr.writeInterruptedSnapshot(file);
        assertFalse(Files.exists(file),
                "with nothing running the stale snapshot must be deleted, never re-prompted");
    }

    @Test
    void corruptSnapshotIsSwallowedAndDeleted() throws Exception {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "{not json[", StandardCharsets.UTF_8);
        assertTrue(AiJobManager.consumeInterruptedSnapshot(file).isEmpty(),
                "a corrupt snapshot yields an empty list, not an exception");
        assertFalse(Files.exists(file), "the corrupt file is deleted so it can't re-prompt forever");
    }

    @Test
    void missingSnapshotYieldsEmptyList() {
        assertTrue(AiJobManager.consumeInterruptedSnapshot(tempDir.resolve("absent.json")).isEmpty());
    }
}
