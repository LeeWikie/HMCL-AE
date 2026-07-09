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

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Renders the {@code {{job_progress:...}}} inline live-badge markup (see
/// {@link JobProgressBadge}) against REAL {@link AiJobManager} jobs and asserts the structural,
/// non-timing-dependent contract:
///  - a single job id renders a percentage-mode badge with the job's live {@code ToolProgress}
///    fraction as its initial text;
///  - 2+ comma-separated job ids render a completed-count-mode badge ("<done>/<N> 已完成");
///  - an id that doesn't exist in {@link AiJobManager} at all renders the graceful
///    "[任务未找到]" fallback instead of crashing the render or leaving the raw {@code {{...}}}
///    token as literal text.
///
/// The live ~500ms poll timeline itself (a badge picking up a job's progress *after* the node
/// was already built) is not exercised here — that's a timing-based behaviour better suited to
/// manual/headed verification than a fast deterministic unit test; see the class doc of
/// {@link JobProgressBadge} for the polling contract this intentionally does not re-assert.
public final class JobProgressBadgeFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    /// Submits a job whose work blocks on a latch (released in each test's cleanup / left running
    /// for the duration of the assertions), so the job stays RUNNING with a stable, real id for
    /// the whole test — a completed job would race the poll/pruning logic non-deterministically.
    private static AiJobManager.Job submitBlockingJob(CountDownLatch releaseLatch) {
        String id = AiJobManager.getInstance().submit(null, "test-tool", "test job", () -> {
            releaseLatch.await();
            return org.jackhuang.hmcl.ai.tools.ToolResult.success("done");
        });
        return AiJobManager.getInstance().get(id);
    }

    @Test
    public void singleJobIdRendersPercentageBadge() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AiJobManager.Job job = submitBlockingJob(release);
        try {
            org.jackhuang.hmcl.ai.tools.ToolProgress.publish(job.getId(), "test-tool", 0.42, "正在下载");
            // job.getStatus() is RUNNING from the moment submit() returns (the Job's status
            // field starts as RUNNING before the pooled worker even picks up the work), so no
            // extra synchronization is needed before asserting against it below.

            MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
            FxToolkit.setupSceneRoot(() -> {
                MarkdownMessageView view = MarkdownMessageView.create(
                        "已安排后台下载mod，已安装 {{job_progress:" + job.getId() + "}}，还有什么别的要求吗？", 710);
                assertNotNull(view, "text containing the job_progress marker must be recognised as markdown");
                viewHolder[0] = view;
                StackPane root = new StackPane(view);
                root.setPrefSize(600, 400);
                return root;
            });
            FxToolkit.showStage();
            WaitForAsyncUtils.waitForFxEvents();

            JobProgressBadge badge = findBadge(viewHolder[0]);
            assertNotNull(badge, "a JobProgressBadge node must be spliced in place of the marker");
            assertTrue(badge.getStyleClass().contains("ai-job-progress-badge"),
                    "badge must carry the shared badge style class");
            assertTrue(!badge.isMultiMode(), "a single id must select percentage mode, not count mode");
            assertEquals("42% - 正在下载", badge.getText(),
                    "initial text must reflect the job's live ToolProgress percentage + phase message");
            assertTrue(!badge.isSettled(), "a running job's badge must not be settled yet");
        } finally {
            release.countDown();
            AiJobManager.getInstance().cancel(job.getId());
        }
    }

    @Test
    public void multipleJobIdsRenderCompletedCountBadge() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AiJobManager.Job running1 = submitBlockingJob(release);
        AiJobManager.Job running2 = submitBlockingJob(release);
        // A job that has already reached a terminal state without ever reporting a percentage —
        // the exact "fast mods_install job" case the spec calls out for multi-job mode.
        String finishedId = AiJobManager.getInstance().submit(null, "test-tool", "instant job",
                () -> org.jackhuang.hmcl.ai.tools.ToolResult.success("done"));
        AiJobManager.Job finishedJob = AiJobManager.getInstance().get(finishedId);
        try {
            // Wait for the instant job to actually reach a terminal state (its work is
            // synchronous but still runs on a pooled worker thread).
            long deadline = System.currentTimeMillis() + 5000;
            while (!finishedJob.isFinished() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(finishedJob.isFinished(), "the instant job must have finished by now");

            String marker = "{{job_progress:" + running1.getId() + "," + running2.getId() + "," + finishedId + "}}";
            MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
            FxToolkit.setupSceneRoot(() -> {
                MarkdownMessageView view = MarkdownMessageView.create("批量安装中 " + marker + "，请稍候", 710);
                assertNotNull(view);
                viewHolder[0] = view;
                StackPane root = new StackPane(view);
                root.setPrefSize(600, 400);
                return root;
            });
            FxToolkit.showStage();
            WaitForAsyncUtils.waitForFxEvents();

            JobProgressBadge badge = findBadge(viewHolder[0]);
            assertNotNull(badge, "a JobProgressBadge node must be spliced in place of the marker");
            assertTrue(badge.isMultiMode(), "2+ ids must select completed-count mode");
            assertEquals("1/3 已完成", badge.getText(),
                    "exactly one of the three listed jobs (the instant one) has reached a terminal "
                            + "state, counted via AiJobManager status — not ToolProgress percentage");
            assertTrue(!badge.isSettled(), "not all listed jobs are terminal yet, so the badge must "
                    + "not be permanently settled");
        } finally {
            release.countDown();
            AiJobManager.getInstance().cancel(running1.getId());
            AiJobManager.getInstance().cancel(running2.getId());
        }
    }

    @Test
    public void unknownJobIdRendersNotFoundFallback() throws Exception {
        MarkdownMessageView[] viewHolder = new MarkdownMessageView[1];
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create(
                    "进度：{{job_progress:this-job-id-does-not-exist-999999}}", 710);
            assertNotNull(view);
            viewHolder[0] = view;
            StackPane root = new StackPane(view);
            root.setPrefSize(600, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        JobProgressBadge badge = findBadge(viewHolder[0]);
        assertNotNull(badge, "a bad job id must still render a badge node (not raw {{...}} text)");
        assertEquals(JobProgressBadge.NOT_FOUND_TEXT, badge.getText(),
                "an id absent from AiJobManager must fail gracefully with the fallback text");
        assertTrue(badge.isSettled(), "the not-found fallback can never change, so it must be "
                + "immediately (and permanently) settled");
    }

    /// Walks the rendered view's TextFlow(s) looking for the spliced-in badge node.
    private static JobProgressBadge findBadge(MarkdownMessageView view) {
        List<Node> queue = new ArrayList<>(view.getChildren());
        while (!queue.isEmpty()) {
            Node node = queue.remove(0);
            if (node instanceof JobProgressBadge badge) {
                return badge;
            }
            if (node instanceof TextFlow flow) {
                queue.addAll(flow.getChildren());
            } else if (node instanceof javafx.scene.Parent parent) {
                queue.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return null;
    }
}
