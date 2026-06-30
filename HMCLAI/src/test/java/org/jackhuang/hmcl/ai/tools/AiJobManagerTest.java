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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the async background-job engine's lifecycle: success, failure, cancellation, lookup,
/// and that completion listeners fire. Uses a latch + a job-id-filtered listener to await the
/// off-thread terminal state deterministically.
public final class AiJobManagerTest {

    /// Awaits the terminal state of {@code jobId} via a completion listener (removed afterwards).
    private static AiJobManager.Job awaitDone(AiJobManager mgr, String jobId, long timeoutSeconds)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Consumer<AiJobManager.Job> listener = job -> {
            if (jobId.equals(job.getId())) {
                latch.countDown();
            }
        };
        mgr.addCompletionListener(listener);
        try {
            // Guard against a completion that fired before the listener was registered.
            AiJobManager.Job already = mgr.get(jobId);
            if (already != null && already.isFinished()) {
                return already;
            }
            assertTrue(latch.await(timeoutSeconds, TimeUnit.SECONDS), "job did not finish within timeout");
            return mgr.get(jobId);
        } finally {
            mgr.removeCompletionListener(listener);
        }
    }

    @Test
    void successfulJobSucceedsWithResult() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        String id = mgr.submit("s1", "demo_tool", "demo", () -> ToolResult.success("ok-output"));
        AiJobManager.Job job = awaitDone(mgr, id, 5);
        assertNotNull(job);
        assertEquals(AiJobManager.Status.SUCCEEDED, job.getStatus());
        assertNotNull(job.getResult());
        assertTrue(job.getResult().isSuccess());
        assertEquals("ok-output", job.getResult().getOutput());
        assertTrue(job.isFinished());
    }

    @Test
    void throwingJobFails() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        String id = mgr.submit(null, "demo_tool", "boom", () -> {
            throw new IllegalStateException("kaboom");
        });
        AiJobManager.Job job = awaitDone(mgr, id, 5);
        assertNotNull(job);
        assertEquals(AiJobManager.Status.FAILED, job.getStatus());
        assertNotNull(job.getError());
    }

    @Test
    void cancellingARunningJobMarksItCancelled() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        String id = mgr.submit("s2", "slow_tool", "slow", () -> {
            started.countDown();
            Thread.sleep(30_000); // interrupted by cancel
            return ToolResult.success("never");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started running");
        boolean cancelled = mgr.cancel(id);
        assertTrue(cancelled, "cancel() should transition the running job");
        AiJobManager.Job job = mgr.get(id);
        assertNotNull(job);
        assertEquals(AiJobManager.Status.CANCELLED, job.getStatus());
    }

    @Test
    void listAndSessionLookupSeeSubmittedJob() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        String session = "sess-" + System.nanoTime();
        String id = mgr.submit(session, "demo_tool", "demo", () -> ToolResult.success("x"));
        awaitDone(mgr, id, 5);
        assertNotNull(mgr.get(id));
        assertTrue(mgr.list().stream().anyMatch(j -> j.getId().equals(id)));
        assertTrue(mgr.listBySession(session).stream().anyMatch(j -> j.getId().equals(id)));
    }

    @Test
    void nullWorkIsRejected() {
        AiJobManager mgr = AiJobManager.getInstance();
        assertThrows(NullPointerException.class, () -> mgr.submit("s", "t", "l", null));
    }
}
