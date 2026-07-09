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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /// A job whose work reports incremental progress (via the job-id it reads back from
    /// {@link ToolProgress#currentJobId()}, exactly as {@code AiJobManager} binds it around the
    /// job's synchronous work) must surface strictly increasing percentages through
    /// {@link AiJobManager#describeRunning}, in step with each update — and, once finished, must
    /// leave no stale progress snapshot behind.
    @Test
    void progressReportingJobSurfacesIncreasingPercentagesThroughDescribeRunning() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();

        CountDownLatch reachedStep1 = new CountDownLatch(1);
        CountDownLatch resumeStep1 = new CountDownLatch(1);
        CountDownLatch reachedStep2 = new CountDownLatch(1);
        CountDownLatch resumeStep2 = new CountDownLatch(1);
        CountDownLatch reachedStep3 = new CountDownLatch(1);
        CountDownLatch resumeStep3 = new CountDownLatch(1);

        String id = mgr.submit("s-progress", "download_tool", "downloading", () -> {
            String jobId = ToolProgress.currentJobId();
            ToolProgress.publish(jobId, "download_tool", 0.25, "Downloading part 1");
            reachedStep1.countDown();
            resumeStep1.await();

            ToolProgress.publish(jobId, "download_tool", 0.60, "Downloading part 2");
            reachedStep2.countDown();
            resumeStep2.await();

            ToolProgress.publish(jobId, "download_tool", 0.95, "Downloading part 3");
            reachedStep3.countDown();
            resumeStep3.await();

            return ToolResult.success("done");
        });

        assertTrue(reachedStep1.await(5, TimeUnit.SECONDS), "step 1 should have been reported");
        AiJobManager.Job job = mgr.get(id);
        assertNotNull(job);
        String status1 = AiJobManager.describeRunning(job);
        assertTrue(status1.startsWith(AiJobManager.STILL_RUNNING_MARKER), status1);
        assertTrue(status1.contains("25%"), status1);
        assertTrue(status1.contains("Downloading part 1"), status1);
        resumeStep1.countDown();

        assertTrue(reachedStep2.await(5, TimeUnit.SECONDS), "step 2 should have been reported");
        String status2 = AiJobManager.describeRunning(job);
        assertTrue(status2.contains("60%"), status2);
        resumeStep2.countDown();

        assertTrue(reachedStep3.await(5, TimeUnit.SECONDS), "step 3 should have been reported");
        String status3 = AiJobManager.describeRunning(job);
        assertTrue(status3.contains("95%"), status3);
        resumeStep3.countDown();

        int percent1 = extractPercent(status1);
        int percent2 = extractPercent(status2);
        int percent3 = extractPercent(status3);
        assertTrue(percent1 < percent2 && percent2 < percent3,
                "percentages should strictly increase across polls: " + percent1 + ", " + percent2 + ", " + percent3);

        AiJobManager.Job finished = awaitDone(mgr, id, 5);
        assertEquals(AiJobManager.Status.SUCCEEDED, finished.getStatus());

        // A finished job must never leave a stale progress snapshot behind.
        assertNull(ToolProgress.latestForJob(id));
    }

    /// A job whose work never reports progress must fall back to EXACTLY the historical
    /// "Still running (Ns elapsed)." text — no percentage clause, unchanged from before this
    /// feature existed.
    @Test
    void nonReportingJobFallsBackToElapsedTimeTextOnly() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        String id = mgr.submit("s-no-progress", "quiet_tool", "quiet", () -> {
            started.countDown();
            resume.await();
            return ToolResult.success("done");
        });

        assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started running");
        AiJobManager.Job job = mgr.get(id);
        assertNotNull(job);

        String status = AiJobManager.describeRunning(job);
        assertTrue(status.startsWith(AiJobManager.STILL_RUNNING_MARKER + " ("), status);
        assertTrue(status.endsWith("s elapsed)."), status);
        assertFalse(status.contains("%"), status);
        assertFalse(status.contains(" - "), status);

        resume.countDown();
        awaitDone(mgr, id, 5);
    }

    private static int extractPercent(String describeRunningText) {
        Matcher m = Pattern.compile("(\\d+)%").matcher(describeRunningText);
        assertTrue(m.find(), "expected a percentage in: " + describeRunningText);
        return Integer.parseInt(m.group(1));
    }
}
