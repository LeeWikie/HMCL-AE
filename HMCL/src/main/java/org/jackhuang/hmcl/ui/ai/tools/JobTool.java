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

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Map;

/// Domain tool for background-job bookkeeping (installs/downloads/backups started with
/// `background=true` run as a job — see {@code AiJobManager}). `sleep` is deliberately NOT part of
/// this domain: it is a generic wait primitive, not a job-object operation.
///
/// This wraps the SAME `AiJobManager` used by the runtime loop-guard/auto-continue machinery
/// (`Job.acknowledged` / `markAcknowledged()`, `autoContinueDepth`/`AUTO_CONTINUE_LIMIT`) —
/// action dispatch here changes nothing about that; it only changes which tool name the model
/// calls.
@NotNullByDefault
public final class JobTool implements ToolSpec {

    private final ListJobsTool list = new ListJobsTool();
    private final CheckJobTool check = new CheckJobTool();
    private final CancelJobTool cancel = new CancelJobTool();

    @Override
    public String getName() {
        return "job";
    }

    @Override
    public String getDescription() {
        return "Background job bookkeeping for long-running tool calls (installs/downloads/backups) started "
                + "without blocking. Parameter 'action' (required): "
                + "list — running + most-recent-finished jobs with id/status/label, no parameters, READ-ONLY; "
                + "NOTE: listing a FINISHED job here marks it as acknowledged, exactly as check(jobId) does — "
                + "once list shows a job as SUCCEEDED/FAILED/CANCELLED, its automatic completion notification "
                + "will NOT fire again, so if you need that job's full result text call check(jobId) for it NOW "
                + "rather than assuming you'll be told later. "
                + "check(jobId) — one job's status and, once finished, its full result text, READ-ONLY; "
                + "cancel(jobId) — interrupt a running job's worker. "
                + "A finished job's result is delivered back to you AUTOMATICALLY as a new turn once you have "
                + "nothing else to do THIS turn (as long as you haven't already seen it via list/check) — do "
                + "not sleep+check in a loop waiting for it.";
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
                   "action": {"type": "string", "enum": ["list", "check", "cancel"], "description": "Which job operation to perform."},
                   "jobId": {"type": "string", "description": "check/cancel: the id returned when the job was started, e.g. \\"1\\"."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return "cancel".equals(action) ? ToolPermission.CONTROLLED_WRITE : ToolPermission.READ_ONLY;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "list" -> list.execute(parameters);
            case "check" -> check.execute(parameters);
            case "cancel" -> cancel.execute(parameters);
            default -> ToolResult.failure("Unknown action '" + action + "'. Valid actions: list, check, cancel.");
        };
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
