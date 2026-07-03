/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.util;

/// Lightweight logging seam for the AI module. HMCLAI does not depend on HMCLCore's {@code Logger},
/// so the host application (HMCL) installs a {@link Sink} that forwards these lines into the app log;
/// until a sink is set the messages are simply dropped (e.g. in unit tests).
public final class AiLog {

    private AiLog() {
    }

    /// Destination for AI log lines, wired by the host to the application logger.
    @FunctionalInterface
    public interface Sink {
        void log(boolean warn, String message);
    }

    private static volatile Sink sink;

    /// Installs the sink that receives subsequent log lines. Idempotent; the last sink wins.
    public static void setSink(Sink s) {
        sink = s;
    }

    /// Logs an informational line (model request/response, timing, token usage).
    public static void info(String message) {
        Sink s = sink;
        if (s != null) {
            s.log(false, message);
        }
    }

    /// Logs a warning line (transient retry, request failure).
    public static void warn(String message) {
        Sink s = sink;
        if (s != null) {
            s.log(true, message);
        }
    }
}
