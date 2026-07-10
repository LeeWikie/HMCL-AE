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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// A dangerous-write tool that force-stops a Minecraft instance HMCL itself launched, via
/// {@link LauncherHelper#stopInstance(String)} — the same `ManagedProcess.stop()` the native
/// "terminate game" affordance uses, now keyed by instance id.
///
/// Can only see/stop a game process HMCL launched and is still tracking; it has no visibility
/// into a copy of the game started outside HMCL.
///
/// Permission level: DANGEROUS_WRITE. It force-kills a running process.
@NotNullByDefault
public final class StopInstanceTool implements Tool {

    @Override
    public String getName() {
        return "stop_instance";
    }

    @Override
    public String getDescription() {
        return "Force-stops a Minecraft instance's game process that HMCL itself launched. "
                + "Parameters: instance (string, optional: defaults to the currently selected instance). "
                + "Only works if HMCL is still tracking a live process for it (see game(action=\"list\")'s "
                + "'(running)' marker) — cannot affect a copy of the game started outside HMCL. "
                + "This is a hard kill, not a graceful in-game quit — unsaved world progress may be lost.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }

        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        String instance = extractString(parameters, "instance", null);
        if (instance == null) {
            instance = Profiles.getSelectedInstance();
        }
        if (instance == null || instance.isBlank()) {
            return ToolResult.failure("No instance specified and no instance is currently selected.");
        }
        if (!repository.hasVersion(instance)) {
            // Reuse the shared "instance does not exist" envelope carrying the real instance names
            // (the candidate list the model is looking for), so this matches the delete/rename tools
            // instead of a bare dead-end message.
            return InstanceToolSupport.instanceNotFoundFailure(repository, instance);
        }

        boolean stopped;
        try {
            stopped = LauncherHelper.stopInstance(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to stop '" + instance + "': " + e.getMessage());
        }

        return stopped
                ? ToolResult.success("Stopped instance '" + instance + "'.")
                : ToolResult.success("Instance '" + instance + "' has no live process HMCL is tracking "
                        + "— it may have already exited, or it wasn't launched through HMCL.");
    }

    @Nullable
    private static String extractString(Map<String, Object> params, String key, @Nullable String fallback) {
        Object val = params.get(key);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return fallback;
    }
}
