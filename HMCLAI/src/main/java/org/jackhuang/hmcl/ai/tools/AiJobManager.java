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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/// A UI-free, thread-safe manager for non-blocking background tasks ("jobs").
///
/// Long-running tools (downloads, installs, world backups …) normally block the
/// agent's turn until they finish. {@link AiJobManager} lets a tool instead hand
/// its work to a background daemon-thread pool and return immediately with a short
/// {@code jobId}; the agent can keep going and later poll the job's status / result
/// (via the job tool's {@code list} / {@code check} actions) or cancel it
/// ({@code job(action="cancel")}).
///
/// This mirrors the static, decoupled style of {@link ToolProgress}: it knows
/// nothing about the UI. The UI (or any other consumer) observes job completion by
/// registering a {@link Consumer} of finished {@link Job}s via
/// {@link #addCompletionListener}. Completion listeners are always fired off the
/// JavaFX application thread (on a dedicated notifier thread) and serialized; a
/// listener that throws can never break the manager or another listener.
///
/// Threading: every public method is safe to call from any thread. Submitted work
/// runs on a small bounded pool of daemon threads, so a forgotten job never keeps
/// the JVM alive.
public final class AiJobManager {

    /// Exact marker substring the job tool's {@code check} action emits when a job hasn't finished yet
    /// (e.g. "Still running (12s elapsed)."). Shared here — rather than duplicated as a string
    /// literal — so the langchain4j adapter's loop guards can recognise "technically successful
    /// but zero new information" poll results without depending on the HMCL-module tool class
    /// (the module dependency direction is HMCL → HMCLAI, not the reverse), and both sides stay
    /// in sync if the wording ever changes.
    public static final String STILL_RUNNING_MARKER = "Still running";

    /// The single shared instance. There is intentionally one manager per process.
    private static final AiJobManager INSTANCE = new AiJobManager();

    /// Returns the shared {@link AiJobManager}.
    public static AiJobManager getInstance() {
        return INSTANCE;
    }

    /// Builds the RUNNING-state status text the job tool's {@code check} action shows: the
    /// elapsed time, plus — when the tool driving {@code job} has reported one via the
    /// job-id-keyed {@link ToolProgress} bus — a live completion percentage and phase message.
    /// A tool that never reports progress (the common case) gets exactly the historical
    /// {@code "Still running (Ns elapsed)."} text, unchanged; this is purely additive.
    ///
    /// @param job a job whose {@link Job#getStatus()} is {@link Status#RUNNING}
    /// @return the status text, always starting with {@link #STILL_RUNNING_MARKER}
    public static String describeRunning(Job job) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - job.getStartedAtMillis());
        StringBuilder sb = new StringBuilder(STILL_RUNNING_MARKER)
                .append(" (").append(elapsed / 1000).append("s elapsed");

        ToolProgress.Event progress = ToolProgress.latestForJob(job.getId());
        if (progress != null && !progress.indeterminate()) {
            int percent = (int) Math.round(Math.max(0.0, Math.min(1.0, progress.fraction())) * 100);
            sb.append(", ").append(percent).append('%');
            String message = progress.message();
            if (message != null && !message.isBlank()) {
                sb.append(" - ").append(message);
            }
        }
        return sb.append(").").toString();
    }

    /// The lifecycle state of a {@link Job}.
    public enum Status {
        /// The work is queued or executing; not yet finished.
        RUNNING,
        /// The work returned a successful {@link ToolResult}.
        SUCCEEDED,
        /// The work threw, returned a failure {@link ToolResult}, or could not be started.
        FAILED,
        /// The job was cancelled before it finished.
        CANCELLED
    }

    /// A single background task tracked by the manager.
    ///
    /// All mutable state is published through {@code volatile} fields and mutated
    /// only under a lock on the {@code Job} instance, so readers on other threads
    /// observe a consistent terminal snapshot once {@link #getStatus()} is no longer
    /// {@link Status#RUNNING}.
    public static final class Job {

        private final String id;
        /// Monotonic submission order, used only for "most-recent-first" sorting.
        private final int seq;
        private final String toolName;
        private final String label;
        private final @Nullable String sessionId;
        /// The work to run; cleared from the public surface (never exposed).
        private final Callable<ToolResult> work;
        private final long startedAtMillis;

        /// Cancellation-forwarding hooks (G6): actions that must run when THIS job is cancelled so
        /// the cancel really reaches the underlying machinery (e.g. an HMCL-side
        /// {@code TaskExecutor.cancel()}) — interrupting the worker thread alone is NOT enough when
        /// the tool handed its real work to another executor, which used to keep downloading to
        /// disk after cancel() had already reported "已取消". Registered while the work runs via
        /// {@link AiJobManager#registerCancelAction}; each action runs at most once.
        private final CopyOnWriteArrayList<Runnable> cancelActions = new CopyOnWriteArrayList<>();

        private volatile @Nullable Future<?> future;
        private volatile Status status = Status.RUNNING;
        private volatile @Nullable ToolResult result;
        private volatile @Nullable String error;
        private volatile long finishedAtMillis;
        /// Whether the MODEL has already seen this job's terminal outcome via the job tool's
        /// check/list actions in its own tool loop. An acknowledged job must not additionally fire
        /// the auto-continue prompt — in a real session a 15-install batch produced a dozen junk
        /// "延迟回执" turns because the model had already confirmed everything via job(action="list").
        private volatile boolean acknowledged;

        private Job(String id, int seq, String toolName, String label,
                    @Nullable String sessionId, Callable<ToolResult> work) {
            this.id = id;
            this.seq = seq;
            this.toolName = toolName;
            this.label = label;
            this.sessionId = sessionId;
            this.work = work;
            this.startedAtMillis = System.currentTimeMillis();
        }

        /// The short, stable job identifier (e.g. {@code "1"}, {@code "2"}, …).
        public String getId() {
            return id;
        }

        /// The name of the tool that submitted this job.
        public String getToolName() {
            return toolName;
        }

        /// A short human-readable label describing the work.
        public String getLabel() {
            return label;
        }

        /// The chat session that owns this job, or {@code null} if it is unscoped.
        public @Nullable String getSessionId() {
            return sessionId;
        }

        /// Whether the model already saw this job's terminal outcome (see field docs).
        public boolean isAcknowledged() {
            return acknowledged;
        }

        /// Marks this job's terminal outcome as seen by the model. Called by the job tool's
        /// check/list actions when they include a SUCCEEDED/FAILED/CANCELLED status in a tool result.
        public void markAcknowledged() {
            this.acknowledged = true;
        }

        /// The current lifecycle state.
        public Status getStatus() {
            return status;
        }

        /// The work's {@link ToolResult}, or {@code null} until the job finishes.
        public @Nullable ToolResult getResult() {
            return result;
        }

        /// A short error description when {@link #getStatus()} is
        /// {@link Status#FAILED} / {@link Status#CANCELLED}; {@code null} otherwise.
        public @Nullable String getError() {
            return error;
        }

        /// Wall-clock time the job was submitted ({@link System#currentTimeMillis()}).
        public long getStartedAtMillis() {
            return startedAtMillis;
        }

        /// Wall-clock time the job finished, or {@code 0} while still running.
        public long getFinishedAtMillis() {
            return finishedAtMillis;
        }

        /// Whether the job has reached a terminal state (not {@link Status#RUNNING}).
        public boolean isFinished() {
            return status != Status.RUNNING;
        }
    }

    /// The maximum number of finished jobs retained; older finished jobs are pruned.
    /// RUNNING jobs are never pruned.
    private static final int MAX_RETAINED_JOBS = 100;

    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Job>> listeners = new CopyOnWriteArrayList<>();
    /// Fired (off the FX thread) whenever the job list changes — a job is submitted OR reaches a
    /// terminal state — so a UI can keep a live "running tasks" view in sync. UI must marshal to FX.
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /// Bounded pool that runs the submitted work. Daemon threads only.
    private final ExecutorService workers =
            Executors.newFixedThreadPool(4, daemonThreadFactory("ai-job-worker-"));

    /// Single-threaded notifier so completion listeners always run off both the
    /// worker threads and the caller (which may be the FX thread when cancelling),
    /// and are delivered one at a time.
    private final ExecutorService notifier =
            Executors.newSingleThreadExecutor(daemonThreadFactory("ai-job-notifier-"));

    private AiJobManager() {
    }

    /// Submits {@code work} to run on a background daemon thread and returns
    /// immediately with a short job id.
    ///
    /// The returned id is a small incrementing integer rendered as a string
    /// ({@code "1"}, {@code "2"}, …). When the work finishes — by returning a
    /// {@link ToolResult}, by throwing (any {@link Throwable}), or by being
    /// cancelled — the job's status and result are stored and completion listeners
    /// are fired. An exception from {@code work} never escapes the worker.
    ///
    /// @param sessionId the owning chat session, or {@code null} if unscoped
    /// @param toolName  the name of the submitting tool (for display); not null
    /// @param label     a short human-readable label; falls back to {@code toolName} when null/blank
    /// @param work      the work to run; not null
    /// @return the new job id
    public String submit(@Nullable String sessionId, String toolName, String label, Callable<ToolResult> work) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(work, "work");
        String effectiveLabel = (label == null || label.isBlank()) ? toolName : label;

        int seq = idGenerator.getAndIncrement();
        String id = Integer.toString(seq);
        Job job = new Job(id, seq, toolName, effectiveLabel, sessionId, work);
        jobs.put(id, job);
        notifier.execute(this::fireChange);

        try {
            Future<?> future = workers.submit(() -> runJob(job));
            job.future = future;
            // If cancel() raced in before the future was assigned, honour it now by interrupting.
            if (job.getStatus() == Status.CANCELLED) {
                future.cancel(true);
            }
        } catch (RejectedExecutionException e) {
            // The pool is unavailable (e.g. JVM shutting down); fail the job immediately.
            complete(job, Status.FAILED,
                    ToolResult.failure("Could not start background task: " + describe(e)), describe(e));
        }
        return id;
    }

    /// Runs a job's work, translating its outcome into a terminal state. Never throws.
    private void runJob(Job job) {
        try {
            // Bind this job's id as ToolProgress.currentJobId() for the duration of the
            // synchronous work, so a tool that reports progress (directly, or via a helper that
            // reads currentJobId() up front to close over it before an async thread hop) gets
            // its updates attributed to THIS job — see ToolProgress's class doc.
            ToolResult result = ToolProgress.runInJobScope(job.id, job.work);
            if (result == null) {
                complete(job, Status.FAILED,
                        ToolResult.failure("Background task returned no result."), "Tool returned no result.");
            } else if (result.isSuccess()) {
                complete(job, Status.SUCCEEDED, result, null);
            } else {
                complete(job, Status.FAILED, result, result.getError());
            }
        } catch (Throwable t) {
            // Covers InterruptedException from cancellation as well as any tool failure.
            String msg = describe(t);
            complete(job, Status.FAILED, ToolResult.failure("Background task failed: " + msg), msg);
        }
    }

    /// Atomically transitions a job from {@link Status#RUNNING} to {@code status}.
    /// Returns {@code true} only for the call that performed the transition; further
    /// calls (e.g. a worker finishing after a cancel won the race) are no-ops.
    private boolean complete(Job job, Status status, @Nullable ToolResult result, @Nullable String error) {
        synchronized (job) {
            if (job.status != Status.RUNNING) {
                return false;
            }
            job.result = result;
            job.error = error;
            job.finishedAtMillis = System.currentTimeMillis();
            // Publish status last so a reader that sees a terminal status also sees result/error.
            job.status = status;
        }
        // A finished job must never leave a stale in-progress percentage behind for
        // describeRunning()/check_job to find, even if the tool never itself called
        // ToolProgress.finish for its job id.
        ToolProgress.clearJob(job.id);
        notifier.execute(() -> fireCompletion(job));
        pruneFinished();
        notifier.execute(this::fireChange); // serialize change notifications off the worker/FX thread
        return true;
    }

    /// Invokes every completion listener, swallowing any exception they throw.
    private void fireCompletion(Job job) {
        for (Consumer<Job> listener : listeners) {
            try {
                listener.accept(job);
            } catch (Throwable ignored) {
                // One broken listener must never break the manager or other listeners.
            }
        }
    }

    /// Returns the job with the given id, or {@code null} if unknown (or pruned).
    public @Nullable Job get(String jobId) {
        if (jobId == null) {
            return null;
        }
        return jobs.get(jobId);
    }

    /// Returns a snapshot of all known jobs, most-recently-submitted first.
    public List<Job> list() {
        List<Job> all = new ArrayList<>(jobs.values());
        all.sort(Comparator.comparingInt((Job j) -> j.seq).reversed());
        return all;
    }

    /// Returns a snapshot of the jobs owned by {@code sessionId}, most-recent first.
    public List<Job> listBySession(String sessionId) {
        List<Job> result = new ArrayList<>();
        for (Job job : list()) {
            if (Objects.equals(job.sessionId, sessionId)) {
                result.add(job);
            }
        }
        return result;
    }

    /// Attempts to cancel a running job, interrupting its worker thread.
    ///
    /// Returns {@code true} only when this call transitioned a running job to
    /// {@link Status#CANCELLED}. Cancelling an unknown or already-finished job
    /// returns {@code false}; the operation is idempotent and safe to repeat.
    public boolean cancel(String jobId) {
        Job job = get(jobId);
        if (job == null) {
            return false;
        }
        // Transition status FIRST, then read the future and interrupt. Combined with submit()
        // publishing job.future BEFORE re-checking the status, this guarantees at least one side
        // always interrupts the worker — closing the TOCTOU window where cancel() reported success
        // yet the work ran to completion (holding a worker thread).
        boolean transitioned = complete(job, Status.CANCELLED, ToolResult.failure("Job cancelled."), "Job cancelled.");
        Future<?> future = job.future;
        if (future != null) {
            // Interrupt the worker; the resulting exception is absorbed by runJob.
            future.cancel(true);
        }
        // G6: forward the cancel to whatever machinery the tool registered (e.g. the HMCL
        // TaskExecutor actually doing the download) — interrupting the worker alone leaves that
        // executor running to completion, contradicting the "cancelled" status on disk.
        if (job.getStatus() == Status.CANCELLED) {
            runCancelActions(job);
        }
        return transitioned;
    }

    /// Registers an action to run when the job with {@code jobId} is cancelled, so the cancel
    /// reaches the tool's real underlying work (G6 — e.g. {@code TaskExecutor.cancel()} for a
    /// download). If the job was ALREADY cancelled when this is called (cancel raced in during
    /// setup), the action runs immediately on the calling thread. Unknown job ids are a no-op.
    /// Each registered action runs at most once; exceptions it throws are swallowed.
    public void registerCancelAction(String jobId, Runnable action) {
        if (action == null) {
            return;
        }
        Job job = get(jobId);
        if (job == null) {
            return;
        }
        job.cancelActions.addIfAbsent(action);
        // Close the register-vs-cancel race: cancel() drains AFTER transitioning the status, so if
        // the status already reads CANCELLED here, our just-added action might have missed that
        // drain — run the drain again ourselves (idempotent: actions are removed before running).
        if (job.getStatus() == Status.CANCELLED) {
            runCancelActions(job);
        }
    }

    /// Removes a previously registered cancel action (typically in the tool's {@code finally}
    /// once the underlying work has completed and no longer needs cancel forwarding).
    public void unregisterCancelAction(String jobId, Runnable action) {
        Job job = get(jobId);
        if (job != null && action != null) {
            job.cancelActions.remove(action);
        }
    }

    /// Runs and removes every pending cancel action of {@code job}, swallowing their exceptions.
    /// Remove-before-run keeps this idempotent across the cancel()/registerCancelAction race.
    private static void runCancelActions(Job job) {
        for (Runnable action : job.cancelActions) {
            if (job.cancelActions.remove(action)) {
                try {
                    action.run();
                } catch (Throwable ignored) {
                    // A broken cancel hook must never break the manager.
                }
            }
        }
    }

    /// Registers a listener fired (off the FX thread) when a job reaches a terminal state.
    public void addCompletionListener(Consumer<Job> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    /// Removes a previously registered completion listener.
    public void removeCompletionListener(Consumer<Job> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /// Registers a listener fired (off the FX thread) whenever the job list changes (submit or
    /// terminal). The listener must marshal any UI work to the FX thread itself.
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.addIfAbsent(listener);
        }
    }

    /// Removes a previously registered change listener.
    public void removeChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.remove(listener);
        }
    }

    /// Invokes every change listener, swallowing any exception they throw.
    private void fireChange() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // One broken listener must never break the manager or other listeners.
            }
        }
    }

    /// Returns the number of jobs currently {@link Status#RUNNING}.
    public int activeCount() {
        int count = 0;
        for (Job job : jobs.values()) {
            if (job.status == Status.RUNNING) {
                count++;
            }
        }
        return count;
    }

    /// Prunes the oldest finished jobs so the map stays bounded. RUNNING jobs are
    /// always kept, so the live set can briefly exceed {@link #MAX_RETAINED_JOBS}.
    private void pruneFinished() {
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        List<Job> finished = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (job.status != Status.RUNNING) {
                finished.add(job);
            }
        }
        int removable = jobs.size() - MAX_RETAINED_JOBS;
        if (removable <= 0) {
            return;
        }
        // Oldest finished first.
        finished.sort(Comparator.comparingInt(j -> j.seq));
        for (Job job : finished) {
            if (removable <= 0) {
                break;
            }
            jobs.remove(job.id);
            removable--;
        }
    }

    // ---- Shutdown snapshot (G8) -------------------------------------------------------------

    /// A plain serializable summary of a job that was still RUNNING when the JVM shut down,
    /// written to {@code ai-jobs-interrupted.json} by the shutdown hook (see
    /// {@link #enableShutdownSnapshot}) and read back on next startup via
    /// {@link #consumeInterruptedSnapshot} so the UI can tell the user which background tasks
    /// were lost instead of letting them silently evaporate with the daemon worker threads.
    public static final class InterruptedJobRecord {
        public @Nullable String id;
        public @Nullable String toolName;
        public @Nullable String label;
        public @Nullable String sessionId;
        public long startedAtMillis;
    }

    private static final Gson SNAPSHOT_GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Where the shutdown hook writes the interrupted-jobs snapshot; set by
    /// {@link #enableShutdownSnapshot}, {@code null} until then (hook writes nothing).
    private volatile @Nullable Path interruptedJobsFile;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();

    /// Enables the shutdown snapshot (G8, mirroring {@code FileSaver}'s shutdown-hook pattern):
    /// registers a JVM shutdown hook (once) that synchronously writes a summary of every job
    /// still RUNNING to {@code file} — the manager is purely in-memory on daemon threads, so a
    /// window close otherwise vaporises all knowledge of in-flight background work. When nothing
    /// is running at shutdown the file is deleted instead, so a stale snapshot can never prompt
    /// the user twice.
    public void enableShutdownSnapshot(Path file) {
        this.interruptedJobsFile = Objects.requireNonNull(file, "file");
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Path target = interruptedJobsFile;
                if (target != null) {
                    writeInterruptedSnapshot(target);
                }
            }, "ai-jobs-interrupted-snapshot"));
        }
    }

    /// Writes the RUNNING-jobs summary to {@code file} (deleting it when none are running).
    /// Package-private so the shutdown hook's body is testable without killing the JVM.
    /// Never throws — this runs on the shutdown path.
    void writeInterruptedSnapshot(Path file) {
        try {
            List<InterruptedJobRecord> running = new ArrayList<>();
            for (Job job : list()) {
                if (job.getStatus() == Status.RUNNING) {
                    InterruptedJobRecord record = new InterruptedJobRecord();
                    record.id = job.getId();
                    record.toolName = job.getToolName();
                    record.label = job.getLabel();
                    record.sessionId = job.getSessionId();
                    record.startedAtMillis = job.getStartedAtMillis();
                    running.add(record);
                }
            }
            if (running.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, SNAPSHOT_GSON.toJson(running), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // Best-effort only; the shutdown path must never throw.
        }
    }

    /// Reads and DELETES the interrupted-jobs snapshot left by a previous run's shutdown hook.
    /// Returns an empty list when there is no snapshot or it is unreadable (an unreadable file is
    /// still deleted so it can't re-prompt forever). The consume-on-read contract guarantees each
    /// interruption is reported to the user exactly once.
    public static List<InterruptedJobRecord> consumeInterruptedSnapshot(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Files.deleteIfExists(file);
            List<InterruptedJobRecord> records = SNAPSHOT_GSON.fromJson(json,
                    new TypeToken<List<InterruptedJobRecord>>() { }.getType());
            if (records == null) {
                return List.of();
            }
            records.removeIf(r -> r == null || (r.label == null && r.toolName == null));
            return records;
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(file);
            } catch (Throwable ignored) {
            }
            return List.of();
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
