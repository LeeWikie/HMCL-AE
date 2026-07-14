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
import java.util.function.IntSupplier;

/// Domain tool for RUNTIME instance state — deliberately small and tight (3 actions), unlike the
/// local-config-and-content `instance` tool. `list` intentionally overlaps with `instance(action=list)`
/// (both show installed instances; this one also marks which are currently running) — the user
/// explicitly asked for that duplication rather than making `game.list` a stripped-down subset.
@NotNullByDefault
public final class GameTool implements ToolSpec {

    private final ListInstancesTool list = new ListInstancesTool();
    private final LaunchInstanceTool launch;
    private final StopInstanceTool stop = new StopInstanceTool();

    /// @param worldBackupMaxMb per-world cap on the total snapshot size in MB, applied to the
    ///                          automatic pre-launch safety backup of a freshly-imported world
    ///                          (see {@link LaunchInstanceTool} / {@code WorldBackupManager}).
    ///                          Typically `aiSettings::getWorldBackupMaxMb`.
    public GameTool(IntSupplier worldBackupMaxMb) {
        this.launch = new LaunchInstanceTool(worldBackupMaxMb);
    }

    @Override
    public String getName() {
        return "game";
    }

    @Override
    public String getDescription() {
        return "Runtime state of Minecraft instances — separate from the 'instance' tool's local "
                + "config/content management. Parameter 'action' (required): "
                + "list — installed instances with release type + paths + whether HMCL currently has "
                + "each one running (a '(running)' marker; HMCL can only see processes it launched itself); "
                + "launch(instance) — start an instance's game process (DESTRUCTIVE in the sense that it "
                + "starts a real process; an account/download prompt may appear; returns immediately without "
                + "waiting for the game to finish loading); pass testMode=true to launch in TEST mode (launcher "
                + "stays open + log window shown for this launch only, saved settings unchanged). CAUTION: a "
                + "loader/game-version/mod mismatch is NOT "
                + "reported back to this chat — it only shows up as an in-game crash or an immediate exit, "
                + "which you cannot see directly; if this instance was just created or had mods "
                + "installed/updated this conversation, consider instance(action=\"mods_check_updates\") or "
                + "checking latest.log after a failed launch before assuming success, and do not claim the "
                + "game launched successfully beyond 'the process started'; "
                + "stop(instance) — force-kill an instance's game process that HMCL itself launched and is "
                + "still tracking (hard kill, not a graceful quit — unsaved world progress may be lost; "
                + "no effect on a copy of the game started outside HMCL). 'instance' defaults to the "
                + "currently selected instance for both launch and stop.";
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
                   "action": {"type": "string", "enum": ["list", "launch", "stop"], "description": "Which operation to perform."},
                   "instance": {"type": "string", "description": "Target instance id for launch/stop; defaults to the currently selected instance."},
                   "testMode": {"type": "boolean", "description": "For launch only: true starts the instance in TEST mode — keeps the launcher window open and shows the game log window for THIS launch only, without changing the instance's saved launcher-visibility/show-logs settings (so an early crash/immediate exit is visible). Defaults to false."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "list" -> ToolPermission.READ_ONLY;
            case "launch", "stop" -> ToolPermission.DANGEROUS_WRITE;
            default -> ToolPermission.CONTROLLED_WRITE;
        };
    }

    /// This tool's worst case is DANGEROUS_WRITE (launch/stop) — reported to the settings/catalog
    /// UI instead of the no-arg {@link #getPermission()} default.
    @Override
    public ToolPermission getMaxPermission() {
        return ToolPermission.DANGEROUS_WRITE;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "list" -> list.execute(parameters);
            case "launch" -> launch.execute(parameters);
            case "stop" -> stop.execute(parameters);
            default -> ToolResult.failure("Unknown action '" + action + "'. Valid actions: list, launch, stop.");
        };
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
