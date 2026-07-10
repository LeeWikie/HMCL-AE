/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.event.JVMLaunchFailedEvent;
import org.jackhuang.hmcl.event.ProcessExitedAbnormallyEvent;
import org.jackhuang.hmcl.event.ProcessStoppedEvent;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Regression tests for the "active stop mis-classified as crash" bug.
///
/// Before the fix, {@link ManagedProcess#stop()} only called {@link Process#destroy()}, which
/// almost always makes the underlying process exit with a non-zero code (and sometimes with
/// crash-report-shaped log lines, e.g. LWJGL/Minecraft printing "Crash report saved to ..." while
/// dying from the kill signal). {@link ExitWaiter} could not tell that apart from a real crash, so
/// it classified the exit as {@link ProcessListener.ExitType#APPLICATION_ERROR}. Every deliberate
/// stop -- the native "Stop" button, the AI `stop_instance` tool, cancelling an in-progress launch --
/// could therefore be misreported as a game crash, which in turn (in
/// {@code LauncherHelper.onExit()}) popped a crash window, marked the instance as launched
/// abnormally, and re-showed/refocused the main stage.
///
/// The fix: {@link ManagedProcess#stop()} now marks the process via
/// {@link ManagedProcess#isStoppedIntentionally()}, and {@link ExitWaiter} checks that flag first
/// and reports {@link ProcessListener.ExitType#INTERRUPTED} unconditionally when it is set, skipping
/// the crash heuristics (and the crash-only events) entirely.
final class ExitWaiterTest {

    private static final AtomicInteger abnormalEvents = new AtomicInteger();
    private static final AtomicInteger jvmErrorEvents = new AtomicInteger();

    @BeforeAll
    static void registerEventCounters() {
        // EventBus/EventManager has no unregister API, so these counters are registered once for
        // the whole test class and read via before/after deltas in each test method.
        EventBus.EVENT_BUS.channel(ProcessExitedAbnormallyEvent.class).register(e -> abnormalEvents.incrementAndGet());
        EventBus.EVENT_BUS.channel(JVMLaunchFailedEvent.class).register(e -> jvmErrorEvents.incrementAndGet());
        // Registered so a broken build doesn't silently swallow this event either; not asserted on
        // directly since ProcessStoppedEvent already fires for every non-intentional exit type.
        EventBus.EVENT_BUS.channel(ProcessStoppedEvent.class).register(e -> {
        });
    }

    private static ManagedProcess newManagedProcess(FakeProcess fake) {
        return new ManagedProcess(fake, List.of("java", "-jar", "test.jar"));
    }

    private static Result run(ManagedProcess process) {
        List<Result> results = new ArrayList<>();
        new ExitWaiter(process, List.of(), (code, type) -> results.add(new Result(code, type))).run();
        assertEquals(1, results.size(), "watcher should be invoked exactly once");
        return results.get(0);
    }

    @Test
    void intentionalStop_withCrashLikeExitCodeAndLogs_isClassifiedInterrupted() {
        FakeProcess fake = new FakeProcess();
        fake.setDestroyExitCode(1);
        ManagedProcess process = newManagedProcess(fake);
        // Log lines that would normally trip the "Crash report saved to" heuristic below.
        // (Needs the "[HH:MM:SS] [thread/LEVEL]:" shape so Log4jLevel.guessLevel recognizes it as
        // an error line in the first place -- see Log4jLevel.MINECRAFT_LOGGER.)
        process.addLine("[12:00:00] [Server thread/FATAL]: Crash report saved to ./crash-reports/crash.txt");

        int abnormalBefore = abnormalEvents.get();

        process.stop(); // <- the fix under test: must mark the process as intentionally stopped

        Result result = run(process);

        assertEquals(ProcessListener.ExitType.INTERRUPTED, result.exitType,
                "an intentional stop must be classified as INTERRUPTED, not APPLICATION_ERROR");
        assertEquals(1, result.exitCode);
        assertEquals(abnormalBefore, abnormalEvents.get(),
                "an intentional stop must not fire ProcessExitedAbnormallyEvent");
    }

    @Test
    void intentionalStop_withHighExitCode_isStillClassifiedInterrupted() {
        // Exit code 137 (128 + SIGKILL) would otherwise be eligible for ExitType.SIGKILL; an
        // intentional stop must pre-empt that classification too.
        FakeProcess fake = new FakeProcess();
        fake.setDestroyExitCode(137);
        ManagedProcess process = newManagedProcess(fake);

        process.stop();

        Result result = run(process);

        assertEquals(ProcessListener.ExitType.INTERRUPTED, result.exitType);
        assertEquals(137, result.exitCode);
    }

    @Test
    void spontaneousCrash_withCrashReportLog_isStillClassifiedApplicationError() {
        // Regression guard: real crashes (nobody called ManagedProcess#stop()) must keep working
        // exactly as before the fix.
        FakeProcess fake = new FakeProcess();
        ManagedProcess process = newManagedProcess(fake);
        process.addLine("[12:00:00] [Server thread/FATAL]: Crash report saved to ./crash-reports/crash.txt");

        int abnormalBefore = abnormalEvents.get();

        fake.exitSpontaneously(1);

        Result result = run(process);

        assertEquals(ProcessListener.ExitType.APPLICATION_ERROR, result.exitType);
        assertEquals(1, result.exitCode);
        assertEquals(abnormalBefore + 1, abnormalEvents.get(),
                "a real crash must still fire ProcessExitedAbnormallyEvent");
    }

    @Test
    void spontaneousJvmFailure_isStillClassifiedJvmError() {
        // Regression guard for the other crash-classification branch.
        FakeProcess fake = new FakeProcess();
        ManagedProcess process = newManagedProcess(fake);
        process.addLine("[12:00:00] [main/ERROR]: Error occurred during initialization of VM");

        int jvmErrorsBefore = jvmErrorEvents.get();

        fake.exitSpontaneously(1);

        Result result = run(process);

        assertEquals(ProcessListener.ExitType.JVM_ERROR, result.exitType);
        assertEquals(jvmErrorsBefore + 1, jvmErrorEvents.get());
    }

    @Test
    void spontaneousCleanExit_isClassifiedNormal() {
        FakeProcess fake = new FakeProcess();
        ManagedProcess process = newManagedProcess(fake);

        fake.exitSpontaneously(0);

        Result result = run(process);

        assertEquals(ProcessListener.ExitType.NORMAL, result.exitType);
        assertEquals(0, result.exitCode);
    }

    @Test
    void threadInterrupted_isStillClassifiedInterrupted() throws Exception {
        // Pre-existing behavior (unrelated to this fix, kept as a regression guard): if the
        // ExitWaiter thread itself is interrupted while blocked in Process#waitFor() -- which is
        // what used to happen *sometimes*, racily, when ManagedProcess#stop() interrupted this
        // same thread via destroyRelatedThreads() -- it must still report INTERRUPTED.
        FakeProcess fake = new FakeProcess();
        ManagedProcess process = newManagedProcess(fake);

        List<Result> results = new ArrayList<>();
        Thread runner = new Thread(new ExitWaiter(process, List.of(), (code, type) -> results.add(new Result(code, type))));
        runner.start();

        assertTrue(fake.awaitEnteredWaitFor(5, TimeUnit.SECONDS), "waiter never reached Process#waitFor()");
        runner.interrupt();
        runner.join(5000);

        assertFalse(runner.isAlive(), "waiter thread should have terminated");
        assertEquals(1, results.size());
        assertEquals(ProcessListener.ExitType.INTERRUPTED, results.get(0).exitType);
        assertEquals(1, results.get(0).exitCode);
    }

    private static final class Result {
        final int exitCode;
        final ProcessListener.ExitType exitType;

        Result(int exitCode, ProcessListener.ExitType exitType) {
            this.exitCode = exitCode;
            this.exitType = exitType;
        }
    }

    /** Minimal controllable {@link Process} double, standing in for the real Minecraft process. */
    private static final class FakeProcess extends Process {
        private final CompletableFuture<Integer> exitCodeFuture = new CompletableFuture<>();
        private final CountDownLatch enteredWaitFor = new CountDownLatch(1);
        private volatile int destroyExitCode = 1;

        void setDestroyExitCode(int code) {
            this.destroyExitCode = code;
        }

        /** Simulates the process terminating on its own (crash, OOM, normal exit) -- nobody asked it to stop. */
        void exitSpontaneously(int code) {
            exitCodeFuture.complete(code);
        }

        boolean awaitEnteredWaitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return enteredWaitFor.await(timeout, unit);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            enteredWaitFor.countDown();
            try {
                return exitCodeFuture.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int exitValue() {
            if (!exitCodeFuture.isDone()) {
                throw new IllegalThreadStateException("process has not exited");
            }
            try {
                return exitCodeFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void destroy() {
            // Simulates ManagedProcess#stop() -> Process#destroy() eventually terminating the process.
            exitCodeFuture.complete(destroyExitCode);
        }
    }
}
