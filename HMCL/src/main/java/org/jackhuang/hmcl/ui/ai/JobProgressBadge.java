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
package org.jackhuang.hmcl.ui.ai;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;
import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.ToolProgress;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A live-updating inline badge for the {@code {{job_progress:<id>[,<id>...]}}} chat-message
/// syntax (see {@link MarkdownMessageView}), rendered as a small highlighted pill instead of
/// literal text.
///
/// Two modes, selected purely by how many job ids are given after the colon:
///  - **Single id** ("percentage mode"): shows the job's own live percentage (+ phase message)
///    reported through {@link ToolProgress#latestForJob}, falling back to a neutral "运行中"
///    while the job exists but hasn't reported a determinate fraction yet. Settles into "完成"
///    once {@link AiJobManager.Job#isFinished()} — checked against the manager's own job
///    registry, not {@code ToolProgress}, since a finished job's progress snapshot is cleared
///    immediately (see {@code ToolProgress.clearJob}) and would otherwise be indistinguishable
///    from a job that never reported one.
///  - **2+ ids** ("completed-count mode"): shows {@code "<done>/<N> 已完成"}, where a job counts
///    as done once it reaches ANY terminal {@link AiJobManager.Status} — even one that never
///    reported a percentage at all (e.g. a fast {@code mods_install} job).
///
/// A bad/hallucinated id that doesn't exist in {@link AiJobManager} at all renders a graceful
/// {@link #NOT_FOUND_TEXT} fallback rather than crashing or leaving the raw {@code {{...}}}
/// token visible. In multi-job mode, unknown ids are simply dropped from both the numerator and
/// the denominator (chosen over failing the whole badge — a typo'd id shouldn't hide the live
/// progress of the ids that ARE valid); only when *every* listed id is unknown does the whole
/// badge fall back to {@link #NOT_FOUND_TEXT}.
///
/// Polls on a ~500ms {@link Timeline} only while attached to a live {@link javafx.scene.Scene}
/// (see the {@code sceneProperty()} listener below) and stops permanently once the tracked state
/// reaches its terminal form, so a long chat history never accumulates one running Timeline per
/// historical message.
final class JobProgressBadge extends Label {

    static final String NOT_FOUND_TEXT = i18n("ai.jobs.badge.not_found");
    private static final String RUNNING_TEXT = i18n("ai.jobs.badge.running");
    private static final String SINGLE_DONE_TEXT = i18n("ai.jobs.badge.done");
    private static final Duration POLL_INTERVAL = Duration.millis(500);

    private final Timeline timeline = new Timeline();

    /// Non-null for single-job (percentage) mode; null for multi-job (count) mode.
    private final @Nullable String singleJobId;
    /// Non-null (2+ entries) for multi-job mode; null for single-job mode.
    private final @Nullable List<String> jobIds;

    private boolean settled = false;

    private JobProgressBadge(@Nullable String singleJobId, @Nullable List<String> jobIds) {
        this.singleJobId = singleJobId;
        this.jobIds = jobIds;
        // "ai-approval-badge" supplies the shared small-pill base look (background, padding,
        // radius); "ai-job-progress-badge" layers the badge-specific font size on top.
        getStyleClass().addAll("ai-approval-badge", "ai-job-progress-badge");
        timeline.getKeyFrames().add(new KeyFrame(POLL_INTERVAL, e -> refresh()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        // Only poll while this badge is actually visible on screen — a chat can accumulate many
        // historical messages, and a Timeline per off-screen badge would leak indefinitely.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                refresh();
                if (!settled) timeline.play();
            } else {
                timeline.stop();
            }
        });
        refresh();
    }

    /// Parses the captured group of a {@code {{job_progress:...}}} match (everything after the
    /// colon) and builds the appropriate badge node. Never throws — an empty/unparsable id list
    /// renders the "not found" fallback rather than propagating an exception up into markdown
    /// rendering.
    static JobProgressBadge create(String rawIds) {
        List<String> ids = new ArrayList<>();
        for (String part : rawIds.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        if (ids.size() <= 1) {
            return new JobProgressBadge(ids.isEmpty() ? "" : ids.get(0), null);
        }
        return new JobProgressBadge(null, ids);
    }

    private void refresh() {
        if (settled) return;
        if (singleJobId != null) {
            refreshSingle();
        } else {
            refreshMulti();
        }
    }

    private void refreshSingle() {
        AiJobManager.Job job = singleJobId.isEmpty() ? null : AiJobManager.getInstance().get(singleJobId);
        if (job == null) {
            settle(NOT_FOUND_TEXT);
            return;
        }
        if (job.isFinished()) {
            settle(SINGLE_DONE_TEXT);
            return;
        }
        ToolProgress.Event progress = ToolProgress.latestForJob(singleJobId);
        if (progress != null && !progress.indeterminate()) {
            int percent = (int) Math.round(Math.max(0.0, Math.min(1.0, progress.fraction())) * 100);
            String message = progress.message();
            setText(message != null && !message.isBlank()
                    ? percent + "% - " + message
                    : percent + "%");
        } else {
            setText(RUNNING_TEXT);
        }
    }

    private void refreshMulti() {
        int total = 0;
        int done = 0;
        for (String id : jobIds) {
            AiJobManager.Job job = AiJobManager.getInstance().get(id);
            if (job == null) continue; // unknown id: not counted toward numerator or denominator
            total++;
            if (job.isFinished()) done++;
        }
        if (total == 0) {
            settle(NOT_FOUND_TEXT);
            return;
        }
        setText(i18n("ai.jobs.badge.completed", done, total));
        if (done == total) {
            markSettled();
        }
    }

    /// Renders the final text and stops polling permanently — used both for a genuine terminal
    /// state and for the "not found" fallback (which can never change, so it's equally settled).
    private void settle(String text) {
        setText(text);
        markSettled();
    }

    /// Marks this badge as permanently settled: stops the poll timeline for good and applies the
    /// dimmed "-done" style so old chat history reads as visually finished at a glance.
    private void markSettled() {
        settled = true;
        timeline.stop();
        if (!getStyleClass().contains("ai-job-progress-badge-done")) {
            getStyleClass().add("ai-job-progress-badge-done");
        }
    }

    // ---- test-only accessors ----

    boolean isMultiMode() {
        return jobIds != null;
    }

    boolean isSettled() {
        return settled;
    }
}
