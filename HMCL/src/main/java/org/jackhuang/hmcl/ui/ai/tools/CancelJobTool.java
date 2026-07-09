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
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// A tool that cancels a running background job, interrupting its worker thread.
///
/// Background jobs are how long-running work (downloads / installs / backups) runs
/// without blocking the agent's turn; this stops one the user no longer wants.
/// Cancelling an unknown or already-finished job is reported but harmless.
///
/// Permission level: CONTROLLED_WRITE. It only stops launcher-internal background
/// work; it is low-risk.
@NotNullByDefault
public final class CancelJobTool implements ToolSpec {

    @Override
    public String getName() {
        return "cancel_job";
    }

    @Override
    public String getDescription() {
        return "Cancels a running background job, interrupting its worker. "
                + "Parameter: jobId (the id returned when the job was started, e.g. \"1\"). "
                + "Cancelling an unknown or already-finished job is reported but harmless. "
                + "Use job(action=\"list\") to discover job ids.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "jobId": {"type": "string", "description": "The id of the background job to cancel, e.g. \\"1\\"."}
                 },
                 "required": ["jobId"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        @Nullable String jobId = jobId(parameters);
        if (jobId == null) {
            return ToolResult.failure("cancel_job: provide 'jobId' (the id returned when the job was started). "
                    + "Use job(action=\"list\") to see job ids.");
        }

        AiJobManager manager = AiJobManager.getInstance();
        AiJobManager.Job job = manager.get(jobId);
        if (job == null) {
            return ToolResult.failure("No background job with id '" + jobId + "'. "
                    + "Use job(action=\"list\") to see current jobs.");
        }

        if (job.isFinished()) {
            return ToolResult.success("Job #" + jobId + " already finished ("
                    + job.getStatus() + "); nothing to cancel.");
        }

        boolean cancelled = manager.cancel(jobId);
        if (cancelled) {
            return ToolResult.success("Cancelled job #" + jobId + " (" + job.getToolName() + " — "
                    + job.getLabel() + ").");
        }
        // The job finished on its own between the check above and the cancel attempt.
        return ToolResult.success("Job #" + jobId + " finished before it could be cancelled ("
                + job.getStatus() + ").");
    }

    /// Resolves the job id from {@code jobId} (falling back to {@code id} / {@code query}),
    /// tolerating a numeric value that Gson decoded as e.g. {@code 1.0}.
    private static @Nullable String jobId(Map<String, Object> parameters) {
        String raw = InstanceToolSupport.string(parameters, "jobId");
        if (raw == null) {
            raw = InstanceToolSupport.string(parameters, "id");
        }
        if (raw == null) {
            raw = InstanceToolSupport.string(parameters, "query");
        }
        if (raw == null) {
            return null;
        }
        if (raw.endsWith(".0")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        return raw.isEmpty() ? null : raw;
    }
}
