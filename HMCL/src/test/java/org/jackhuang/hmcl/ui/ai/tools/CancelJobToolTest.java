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

import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [CancelJobTool] against the real [AiJobManager] singleton (a plain in-process manager
/// with no UI/network dependency, so no fake is needed): the missing-jobId hard failure, an unknown
/// job id, a job that already finished before the cancel arrived, and cancelling a genuinely
/// in-flight job. Every job id used here comes from a real {@link AiJobManager#submit} call (ids
/// are a shared, monotonically-increasing sequence across the whole JVM), so tests never guess or
/// collide on an id.
public final class CancelJobToolTest {

    private final CancelJobTool tool = new CancelJobTool();
    private final AiJobManager manager = AiJobManager.getInstance();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("cancel_job", tool.getName());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission());
        assertTrue(tool.supportsStructuredSchema());
        assertTrue(tool.getInputSchemaJson().contains("\"jobId\""));
    }

    @Test
    void missingJobIdFails() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("jobId"), "unexpected message: " + result.getError());
    }

    @Test
    void unknownJobIdFails() {
        // Job ids are a monotonically increasing integer sequence starting at 1; this string can
        // never collide with a real id.
        ToolResult result = tool.execute(Map.of("jobId", "not-a-real-job-id"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No background job"), "unexpected message: " + result.getError());
    }

    @Test
    void alreadyFinishedJobReportsNothingToCancel() throws Exception {
        String jobId = manager.submit(null, "test_tool", "quick job", () -> ToolResult.success("done"));
        awaitFinished(jobId);

        ToolResult result = tool.execute(Map.of("jobId", jobId));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("already finished"), "unexpected message: " + result.getOutput());
        assertTrue(result.getOutput().contains("nothing to cancel"), "unexpected message: " + result.getOutput());
    }

    @Test
    void jobIdWithTrailingPointZeroIsTolerated() throws Exception {
        // Gson decodes a bare numeric jobId as a Double (e.g. 3 -> "3.0"); the tool must still
        // resolve the same job.
        String jobId = manager.submit(null, "test_tool", "quick job", () -> ToolResult.success("done"));
        awaitFinished(jobId);

        ToolResult result = tool.execute(Map.of("jobId", jobId + ".0"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("Job #" + jobId), "unexpected message: " + result.getOutput());
    }

    @Test
    void cancelsARunningJobAndInterruptsItsWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        String jobId = manager.submit(null, "test_tool", "long job", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return ToolResult.success("should not reach here");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "job never started running");

        ToolResult result = tool.execute(Map.of("jobId", jobId));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertTrue(result.getOutput().contains("Cancelled job #" + jobId), "unexpected message: " + result.getOutput());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the worker thread must have been interrupted");
        assertEquals(AiJobManager.Status.CANCELLED, manager.get(jobId).getStatus());
    }

    private void awaitFinished(String jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!manager.get(jobId).isFinished() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
