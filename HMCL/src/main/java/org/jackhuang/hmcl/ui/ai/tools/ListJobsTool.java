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

import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;

/// A read-only tool that lists the background jobs tracked by {@link AiJobManager}:
/// the ones still running plus the most recent finished ones, with their id, the
/// tool that started them, their status, and a short label.
///
/// Background jobs are how long-running work (downloads / installs / backups) is
/// started without blocking the agent's turn; this tool lets the agent see what is
/// in flight. Use `check_job` to read a single job's full result and `cancel_job`
/// to stop one.
///
/// Permission level: READ_ONLY. It never modifies any state.
@NotNullByDefault
public final class ListJobsTool implements ToolSpec {

    /// How many of the most-recent jobs to show; older finished jobs are omitted.
    private static final int MAX_SHOWN = 30;

    @Override
    public String getName() {
        return "list_jobs";
    }

    @Override
    public String getDescription() {
        return "Lists background jobs (long-running tasks started without blocking): the running ones plus the "
                + "most recent finished ones, each with its id, the tool that started it, its status "
                + "(RUNNING/SUCCEEDED/FAILED/CANCELLED) and a short label. Takes no parameters. Read-only. "
                + "Use check_job <id> to read a job's full result, or cancel_job <id> to stop one.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        AiJobManager manager = AiJobManager.getInstance();
        List<AiJobManager.Job> jobs = manager.list();
        if (jobs.isEmpty()) {
            return ToolResult.success("No background jobs have been started.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Background jobs (")
                .append(manager.activeCount()).append(" running, ")
                .append(jobs.size()).append(" total):\n");

        int shown = 0;
        for (AiJobManager.Job job : jobs) {
            if (shown >= MAX_SHOWN) {
                sb.append("  … and ").append(jobs.size() - shown).append(" older job(s) not shown.\n");
                break;
            }
            sb.append("  - #").append(job.getId())
                    .append("  [").append(job.getStatus()).append(']')
                    .append("  ").append(job.getToolName())
                    .append("  — ").append(job.getLabel())
                    .append('\n');
            shown++;
        }

        return ToolResult.success(sb.toString().trim());
    }
}
