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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// G6: cancel() must punch through to the tool's real underlying machinery. Interrupting the job's
/// worker thread alone leaves an HMCL TaskExecutor running to completion ("已取消" while the disk
/// keeps writing) — so tools register cancel actions with their job, and {@code cancel()} forwards
/// to them. These tests lock in the forwarding contract, including its registration/cancel races.
public final class AiJobManagerCancelForwardingTest {

    /// A work body that survives a plain interrupt (models a tool whose real work lives on another
    /// executor): the ONLY way it "stops" is via the registered cancel action.
    private static String submitInterruptSwallowingJob(AiJobManager mgr, CountDownLatch started) {
        return mgr.submit("cancel-fwd-" + System.nanoTime(), "download_tool", "long download", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                // Deliberately swallowed — mirrors work delegated to a non-interruptible executor.
            }
            return ToolResult.success("late");
        });
    }

    @Test
    void cancelForwardsToRegisteredCancelAction() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch actionRan = new CountDownLatch(1);
        String id = submitInterruptSwallowingJob(mgr, started);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started");

        mgr.registerCancelAction(id, actionRan::countDown);
        assertTrue(mgr.cancel(id), "cancel() should transition the running job");
        assertTrue(actionRan.await(5, TimeUnit.SECONDS),
                "cancel() must forward to the registered cancel action (G6: 取消要打穿到底层)");
        assertEquals(AiJobManager.Status.CANCELLED, mgr.get(id).getStatus());
    }

    @Test
    void registeringAfterCancelRunsTheActionImmediately() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        String id = submitInterruptSwallowingJob(mgr, started);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started");
        assertTrue(mgr.cancel(id));

        // The tool registers its executor a beat AFTER cancel() already won the race — the action
        // must still run (on the registering thread), otherwise the underlying work leaks.
        AtomicInteger runs = new AtomicInteger();
        mgr.registerCancelAction(id, runs::incrementAndGet);
        assertEquals(1, runs.get(), "register-after-cancel must run the action immediately");
    }

    @Test
    void actionsNeverRunForAJobThatFinishedNormally() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        java.util.function.Consumer<AiJobManager.Job> listener = job -> done.countDown();
        mgr.addCompletionListener(listener);
        try {
            String id = mgr.submit("cancel-fwd-ok", "demo_tool", "quick", () -> ToolResult.success("ok"));
            mgr.registerCancelAction(id, runs::incrementAndGet);
            assertTrue(done.await(5, TimeUnit.SECONDS), "job should finish");
            // Cancelling a finished job is a no-op and must NOT fire the action either.
            assertFalse(mgr.cancel(id));
            assertEquals(0, runs.get(), "cancel actions must only ever run on a real cancellation");
        } finally {
            mgr.removeCompletionListener(listener);
        }
    }

    @Test
    void eachActionRunsAtMostOnceAcrossRepeatedCancels() throws InterruptedException {
        AiJobManager mgr = AiJobManager.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        String id = submitInterruptSwallowingJob(mgr, started);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started");

        AtomicInteger runs = new AtomicInteger();
        mgr.registerCancelAction(id, runs::incrementAndGet);
        assertTrue(mgr.cancel(id));
        assertFalse(mgr.cancel(id), "second cancel is an idempotent no-op");
        mgr.cancel(id);
        assertEquals(1, runs.get(), "a registered action runs at most once");
    }
}
