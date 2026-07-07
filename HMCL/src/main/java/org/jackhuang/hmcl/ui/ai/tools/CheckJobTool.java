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

/// A read-only tool that reports the status of a single background job and, once it
/// has finished, the full text of its result.
///
/// Background jobs are how long-running work (downloads / installs / backups) runs
/// without blocking the agent's turn. After starting one, poll it here with its id
/// to learn whether it is still RUNNING, has SUCCEEDED/FAILED, or was CANCELLED, and
/// to read the output it produced.
///
/// Permission level: READ_ONLY. It never modifies any state.
@NotNullByDefault
public final class CheckJobTool implements ToolSpec {

    @Override
    public String getName() {
        return "check_job";
    }

    @Override
    public String getDescription() {
        return "Reports the status of one background job and, if it has finished, the full result text. "
                + "Parameter: jobId (the id returned when the job was started, e.g. \"1\"). Read-only. "
                + "Use list_jobs to discover job ids.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
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
                   "jobId": {"type": "string", "description": "The id of the background job to check, e.g. \\"1\\"."}
                 },
                 "required": ["jobId"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        @Nullable String jobId = jobId(parameters);
        if (jobId == null) {
            return ToolResult.failure("check_job: provide 'jobId' (the id returned when the job was started). "
                    + "Use list_jobs to see job ids.");
        }

        AiJobManager.Job job = AiJobManager.getInstance().get(jobId);
        if (job == null) {
            return ToolResult.failure("No background job with id '" + jobId + "'. "
                    + "It may have never existed or been pruned. Use list_jobs to see current jobs.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Job #").append(job.getId())
                .append("  [").append(job.getStatus()).append("]\n");
        sb.append("Tool: ").append(job.getToolName()).append('\n');
        sb.append("Label: ").append(job.getLabel()).append('\n');

        if (!job.isFinished()) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - job.getStartedAtMillis());
            sb.append("Still running (").append(elapsed / 1000).append("s elapsed). ")
                    .append("Check again later, or cancel_job ").append(job.getId()).append(" to stop it.");
            return ToolResult.success(sb.toString());
        }

        long duration = Math.max(0L, job.getFinishedAtMillis() - job.getStartedAtMillis());
        sb.append("Finished in ").append(duration / 1000).append("s.\n");

        ToolResult result = job.getResult();
        switch (job.getStatus()) {
            case SUCCEEDED:
                sb.append("Result:\n").append(result != null ? result.getOutput() : "(no output)");
                break;
            case FAILED:
                String error = job.getError();
                sb.append("Error: ").append(error != null ? error : "(unknown)");
                if (result != null && result.getOutput() != null && !result.getOutput().isEmpty()) {
                    sb.append("\nOutput:\n").append(result.getOutput());
                }
                break;
            case CANCELLED:
                sb.append("Cancelled before completion.");
                break;
            default:
                // RUNNING is handled above; this is unreachable but kept exhaustive.
                sb.append("Status: ").append(job.getStatus());
                break;
        }

        // The model has now SEEN this job's terminal outcome — suppress the redundant
        // auto-continue prompt that would otherwise re-announce it ("延迟回执" spam).
        job.markAcknowledged();
        return ToolResult.success(sb.toString().trim());
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
