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
package org.jackhuang.hmcl.util.platform;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

final class ManagedProcessTest {

    @Test
    void isStoppedIntentionally_defaultsToFalse() {
        ManagedProcess process = new ManagedProcess(new FakeProcess(), List.of("java"));

        assertFalse(process.isStoppedIntentionally());
    }

    @Test
    void stop_marksProcessAsStoppedIntentionally_andStillDestroysIt() {
        FakeProcess fake = new FakeProcess();
        ManagedProcess process = new ManagedProcess(fake, List.of("java"));

        process.stop();

        assertTrue(process.isStoppedIntentionally(),
                "stop() must mark the process as intentionally stopped so ExitWaiter can tell " +
                        "a deliberate stop apart from a real crash");
        assertTrue(fake.wasDestroyCalled(), "stop() must still destroy the underlying process");
    }

    /** Minimal controllable {@link Process} double; behavior details are irrelevant to this test. */
    private static final class FakeProcess extends Process {
        private final CompletableFuture<Integer> exitCodeFuture = new CompletableFuture<>();
        private volatile boolean destroyCalled = false;

        boolean wasDestroyCalled() {
            return destroyCalled;
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
            destroyCalled = true;
            exitCodeFuture.complete(1);
        }
    }
}
