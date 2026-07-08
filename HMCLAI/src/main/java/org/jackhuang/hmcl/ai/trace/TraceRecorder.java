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
package org.jackhuang.hmcl.ai.trace;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.ai.util.AiLog;
import org.jackhuang.hmcl.ai.util.Redactor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Append-only, per-session JSONL trace of the full agent interaction — the complete-truth
/// record that the summarised `[AI]` log and the mutable `ai-sessions.json` cannot provide.
///
/// Each call to {@link #record} appends one JSON line to `<traceDir>/<sessionId>.jsonl`
/// after passing the whole line through {@link Redactor} (so secrets never reach a trace that
/// may be uploaded for diagnosis). Deliberately a pure sink: it takes an already-built
/// {@link JsonObject}, so the langchain4j→JSON conversion (and system-prompt hash de-dup) live
/// in the caller and this class stays trivially unit-testable and free of langchain4j deps.
///
/// Fail-safe: any I/O error is swallowed (logged once) — tracing must never break a real turn.
/// The host installs the trace directory and enabled flag via {@link #configure}; until then
/// (e.g. in unit tests without configuration) every call is a no-op.
public final class TraceRecorder {

    private TraceRecorder() {
    }

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();

    private static volatile @Nullable Path traceDir;
    private static volatile boolean enabled;
    private static volatile boolean warnedOnce;

    /// Installs the trace output directory and enabled flag. Called by the host (HMCL) at AI init.
    /// A null directory or {@code enabled=false} turns recording into a no-op.
    public static void configure(@Nullable Path dir, boolean on) {
        traceDir = dir;
        enabled = on;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled && traceDir != null;
    }

    /// Appends one trace event for {@code sessionId}. The caller supplies the event body
    /// (type-specific fields); this method stamps {@code ts} (epoch millis) if absent, redacts,
    /// and appends. No-op when disabled, unconfigured, or {@code sessionId} is blank.
    public static void record(@Nullable String sessionId, JsonObject event) {
        Path dir = traceDir;
        if (!enabled || dir == null || sessionId == null || sessionId.isBlank() || event == null) {
            return;
        }
        if (!event.has("ts")) {
            event.addProperty("ts", System.currentTimeMillis());
        }
        String line = GSON.toJson(event);
        String redacted = Redactor.redact(line);
        Path file = dir.resolve(sanitize(sessionId) + ".jsonl");
        synchronized (LOCK) {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, redacted + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                if (!warnedOnce) {
                    warnedOnce = true;
                    AiLog.warn("[AI] trace 写入失败（后续同类错误不再提示）：" + e.getMessage());
                }
            }
        }
    }

    /// Resolves the trace file for a session (for the upload path to package it). May not exist.
    public static @Nullable Path traceFile(@Nullable String sessionId) {
        Path dir = traceDir;
        if (dir == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return dir.resolve(sanitize(sessionId) + ".jsonl");
    }

    /// Keeps a session id safe as a file name (ids are UUIDs, but be defensive).
    static String sanitize(String sessionId) {
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
