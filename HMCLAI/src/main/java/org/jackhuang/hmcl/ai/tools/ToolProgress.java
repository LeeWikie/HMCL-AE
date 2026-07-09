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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/// A lightweight, decoupled progress bus for long-running AI tools (downloads / installs).
///
/// Tools (or the helpers they call) publish progress through the static {@link #publish},
/// {@link #begin} and {@link #finish} methods without knowing anything about the UI. The
/// UI registers a single {@link Listener} via {@link #setListener} and renders a live
/// progress card. This keeps the {@code Tool} interface unchanged: tools stay synchronous
/// and simply emit progress as a side effect while they block on the underlying task.
///
/// Threading: publishing is allowed from any thread; the listener is responsible for
/// marshalling onto its own UI thread (e.g. {@code Platform.runLater}). Exceptions thrown
/// by the listener are swallowed so a misbehaving UI can never break a tool.
///
/// Job-id dimension: a tool that is currently running as an {@link AiJobManager} background
/// job can additionally tag its updates with that job's id (via the {@code jobId}-taking
/// overloads below), so {@code check_job} can surface a live percentage for THAT specific job
/// even while other jobs are running concurrently. {@link AiJobManager} binds the id of the
/// job it is about to run into a thread-local for the duration of the job's synchronous work
/// (see {@link #runInJobScope}); callers whose actual {@code publish}/{@code begin}/{@code
/// finish} calls may happen later on a different thread (e.g. after an FX {@code
/// Platform.runLater} hop, where a thread-local read would no longer see it) should read
/// {@link #currentJobId()} up front, on the job's own call stack, and close over the result.
/// Updates that never carry a job id (the common case, e.g. this bus's original callers)
/// behave exactly as before: they are only ever delivered to the single {@link Listener}.
public final class ToolProgress {

    private ToolProgress() {
    }

    /// A single progress update for a tool.
    ///
    /// @param jobId    the id of the {@link AiJobManager} job this update belongs to, or
    ///                 {@code null} when the update isn't associated with any background job
    ///                 (e.g. a foreground/synchronous tool call).
    /// @param toolName an opaque label for the operation in progress (used only for display
    ///                 / debugging; the UI routes updates to the currently running tool card).
    /// @param fraction completion in {@code [0, 1]}, or a negative value for an indeterminate
    ///                 (unknown duration) state.
    /// @param message  a short human-readable phase description (e.g. "Downloading …").
    /// @param done     {@code true} for the terminal update of an operation.
    /// @param success  meaningful only when {@code done} is {@code true}.
    public record Event(@Nullable String jobId, String toolName, double fraction, String message, boolean done,
                         boolean success) {
        public boolean indeterminate() {
            return fraction < 0 || Double.isNaN(fraction);
        }
    }

    /// Receives progress events. Implementations must not assume any particular thread.
    @FunctionalInterface
    public interface Listener {
        void onProgress(Event event);
    }

    private static volatile @Nullable Listener listener;

    /// Thread-local id of the {@link AiJobManager} job currently executing on this thread's
    /// call stack, bound by {@link #runInJobScope}. {@code null} outside of any job's work.
    private static final ThreadLocal<@Nullable String> CURRENT_JOB_ID = new ThreadLocal<>();

    /// The most recent non-terminal progress update reported for each job id that has
    /// reported at least one, so {@code check_job} can look one up by job id on demand
    /// without itself being a {@link Listener}. Entries are removed once the update that
    /// created them was terminal ({@link Event#done()}), or via {@link #clearJob}.
    private static final ConcurrentHashMap<String, Event> latestByJob = new ConcurrentHashMap<>();

    /// Registers (or clears, when {@code null}) the single progress listener. The most
    /// recent registration wins; there is intentionally only one consumer (the chat view).
    public static void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    /// Returns the id of the {@link AiJobManager} job currently running on this thread's call
    /// stack (as bound by {@link #runInJobScope}), or {@code null} when this thread isn't
    /// (synchronously) inside a job's work right now. Call sites whose actual publish will
    /// happen later, possibly on a different thread, should read this up front and pass the
    /// result explicitly to the {@code jobId}-taking overloads below.
    public static @Nullable String currentJobId() {
        return CURRENT_JOB_ID.get();
    }

    /// Runs {@code work}, binding {@code jobId} as {@link #currentJobId()} for the duration of
    /// the call on this thread (restoring whatever was bound before on return, including
    /// {@code null}). Used by {@link AiJobManager} to associate a job's synchronous work with
    /// its id without changing the {@code Tool} interface.
    static <T> T runInJobScope(String jobId, Callable<T> work) throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        String previous = CURRENT_JOB_ID.get();
        CURRENT_JOB_ID.set(jobId);
        try {
            return work.call();
        } finally {
            if (previous != null) {
                CURRENT_JOB_ID.set(previous);
            } else {
                CURRENT_JOB_ID.remove();
            }
        }
    }

    /// Publishes a progress fraction tagged with {@code jobId} (may be {@code null}). Use a
    /// negative {@code fraction} for indeterminate.
    public static void publish(@Nullable String jobId, String toolName, double fraction, String message) {
        emit(new Event(jobId, toolName, fraction, message, false, false));
    }

    /// Publishes a progress fraction. Use a negative {@code fraction} for indeterminate.
    public static void publish(String toolName, double fraction, String message) {
        publish(null, toolName, fraction, message);
    }

    /// Publishes the start of an operation, tagged with {@code jobId} (may be {@code null}),
    /// as an indeterminate update.
    public static void begin(@Nullable String jobId, String toolName, String message) {
        emit(new Event(jobId, toolName, -1.0, message, false, false));
    }

    /// Publishes the start of an operation as an indeterminate update.
    public static void begin(String toolName, String message) {
        begin(null, toolName, message);
    }

    /// Publishes the terminal update of an operation, tagged with {@code jobId} (may be
    /// {@code null}).
    public static void finish(@Nullable String jobId, String toolName, boolean success, String message) {
        emit(new Event(jobId, toolName, success ? 1.0 : -1.0, message, true, success));
    }

    /// Publishes the terminal update of an operation.
    public static void finish(String toolName, boolean success, String message) {
        finish(null, toolName, success, message);
    }

    /// Returns the most recent non-terminal progress update reported for {@code jobId}, or
    /// {@code null} when that job has never reported one (or has already finished, or
    /// {@code jobId} is {@code null}). Used by {@code check_job} to surface a live percentage.
    public static @Nullable Event latestForJob(@Nullable String jobId) {
        return jobId != null ? latestByJob.get(jobId) : null;
    }

    /// Clears any retained progress snapshot for {@code jobId}. Called by {@link AiJobManager}
    /// once a job reaches a terminal state, so a finished job never leaves a stale percentage
    /// behind even if the tool that ran it never itself called {@link #finish}.
    static void clearJob(@Nullable String jobId) {
        if (jobId != null) {
            latestByJob.remove(jobId);
        }
    }

    private static void emit(Event event) {
        String jobId = event.jobId();
        if (jobId != null) {
            if (event.done()) {
                latestByJob.remove(jobId);
            } else {
                latestByJob.put(jobId, event);
            }
        }
        Listener current = listener;
        if (current != null) {
            try {
                current.onProgress(event);
            } catch (Throwable ignored) {
                // A broken UI listener must never break a tool's execution.
            }
        }
    }
}
