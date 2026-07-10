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

import javafx.application.Platform;
import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.task.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// G6: "取消"必须打穿到底层。`runTaskBlocking` blocks a caller thread on a Future while the REAL
/// work runs on the HMCL scheduler inside a [org.jackhuang.hmcl.task.TaskExecutor] — interrupting
/// the caller (what `AiJobManager.cancel` does to its worker) never touches the scheduler thread,
/// so before this fix the underlying task ran to completion after "cancelled" was reported.
/// These tests prove BOTH new paths stop the underlying task: the InterruptedException catch
/// (executor.cancel() on unwind) and the job-registered cancel-action forwarding.
public final class ContentToolSupportCancelTest {

    @BeforeAll
    static void initFxToolkit() {
        // progressListener's onRunning/onStop use Platform.runLater — the toolkit must exist.
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — needs the FX toolkit");
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Another test initialized it first — fine.
        }
        Platform.setImplicitExit(false);
    }

    /// A task that spins until the EXECUTOR's cancelled flag is set. Its loop swallows plain
    /// interrupts on purpose and it runs on the HMCL scheduler (not the caller / job worker
    /// thread), so the ONLY way it can exit within the test timeout is a real
    /// {@code TaskExecutor.cancel()} — exactly the guarantee under test.
    private static final class CooperativeTask extends Task<Void> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);

        @Override
        public void execute() {
            started.countDown();
            try {
                while (!isCancelled()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        // Keep polling the flag; isCancelled() also observes the interrupt.
                    }
                }
            } finally {
                exited.countDown();
            }
        }
    }

    @Test
    void interruptingTheBlockedCallerCancelsTheUnderlyingExecutor() throws Exception {
        CooperativeTask task = new CooperativeTask();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                ContentToolSupport.runTaskBlocking(task, 60, "cancel-test");
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "cancel-test-caller");
        caller.setDaemon(true);
        caller.start();

        assertTrue(task.started.await(10, TimeUnit.SECONDS), "task should be running");
        caller.interrupt();

        assertTrue(task.exited.await(10, TimeUnit.SECONDS),
                "the InterruptedException catch must executor.cancel() — the underlying task must stop");
        caller.join(10_000);
        assertInstanceOf(InterruptedException.class, thrown.get(),
                "the interrupt must still surface to the caller after cancelling the executor");
    }

    @Test
    void cancellingTheBackgroundJobStopsTheUnderlyingExecutor() throws Exception {
        CooperativeTask task = new CooperativeTask();
        AiJobManager mgr = AiJobManager.getInstance();
        String id = mgr.submit("cts-cancel-" + System.nanoTime(), "install_mod", "cancel test", () -> {
            ContentToolSupport.runTaskBlocking(task, 60, "cancel-test");
            return ToolResult.success("done");
        });

        assertTrue(task.started.await(10, TimeUnit.SECONDS), "task should be running inside the job");
        assertTrue(mgr.cancel(id), "cancel() should transition the running job");

        assertTrue(task.exited.await(10, TimeUnit.SECONDS),
                "job cancel must be forwarded to TaskExecutor.cancel() (G6: 取消打穿到底层)");
        assertEquals(AiJobManager.Status.CANCELLED, mgr.get(id).getStatus());
    }
}
