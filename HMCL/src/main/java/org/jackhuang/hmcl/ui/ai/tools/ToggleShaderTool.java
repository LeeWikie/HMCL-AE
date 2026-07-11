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

import org.jackhuang.hmcl.addon.LocalAddonManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A tool that enables or disables a single shader pack of a Minecraft instance by appending or
/// removing the `.disabled` suffix on its `shaderpacks` entry (a `.zip` archive or an unpacked
/// folder).
///
/// Unlike mods/resource packs, this codebase has no native manager class for shader packs (see
/// {@link DeleteShaderTool} / {@link ListResourcePacksTool}, which already treat the
/// `shaderpacks` folder as plain files/folders instead of going through a `LocalAddonManager`
/// state machine) — there is no vanilla `options.txt` concept of an "active" shader pack the way
/// there is for resource packs, and shader loaders (OptiFine/Iris) keep their own selection state
/// outside anything this launcher edits. So this tool mirrors {@link ToggleModTool}'s rename
/// convention directly (enabled = `xxx.zip`/`xxx` folder, disabled = `xxx.zip.disabled`/
/// `xxx.disabled`) instead of delegating to a manager class that doesn't exist for this content
/// type — this is the same on-disk convention the model would otherwise have to reproduce by hand
/// via raw shell renames.
///
/// Resolution mirrors {@link DeleteShaderTool}: the `shaderpacks` folder is located through
/// HMCL's launcher APIs ([`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] /
/// [`HMCLGameRepository#getRunDirectory(String)`]), then every top-level entry (a folder, or a
/// `.zip` file, matched with or without the `.disabled` suffix already applied) is matched against
/// the `shader` parameter by a case-insensitive substring over its base name. The tool refuses to
/// act unless exactly one entry matches (zero or several is an error), so it can never toggle the
/// wrong shader pack by accident.
///
/// When a rename does not take effect (typically because the file is held open by a running
/// game), the failure is attributed via the shared
/// [ToggleModTool#fileOperationFailure(String, String, Throwable)] helper (which consults
/// [GameResourceGuard#checkInstanceNotRunning(String)]) instead of leaking a raw I/O message.
///
/// Permission level: MODIFIES the file system (renames a file/folder on disk) — CONTROLLED_WRITE
/// at this leaf-tool level, exactly like {@link ToggleModTool}; the merged `instance` facade keeps
/// the `shaders_toggle` action at CONTROLLED_WRITE (reversible), unlike the DANGEROUS_WRITE-gated
/// `shaders_delete`.
@NotNullByDefault
public final class ToggleShaderTool implements Tool {

    private static final String DISABLED_SUFFIX = LocalAddonManager.DISABLED_EXTENSION;

    @Override
    public String getName() {
        return "toggle_shader";
    }

    @Override
    public String getDescription() {
        return "Enables or disables a single shader pack of a Minecraft instance by renaming its entry in the "
                + "shaderpacks folder (enabled = 'xxx.zip'/'xxx', disabled = 'xxx.zip.disabled'/'xxx.disabled'). "
                + "Parameters: 'shader' (required, the shader pack file/folder name — or a case-insensitive "
                + "substring of its base name — that matches exactly one entry in the shaderpacks folder), "
                + "'instance' (optional, the instance/version id; defaults to the currently selected instance), "
                + "'enable' (optional boolean; if provided forces enable=true or disable=false, otherwise the "
                + "current state is toggled). "
                + "WARNING: this modifies the file system by renaming a file or folder on disk. "
                + "Returns the old name, the new name and the resulting state. "
                + "Fails if the substring matches zero or more than one entry.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object shaderObj = parameters.get("shader");
        if (shaderObj == null || shaderObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'shader' (the shader pack file/folder name or a substring of it).");
        }
        String shaderQuery = shaderObj.toString().trim();

        // Resolve the optional 'enable' parameter (may be a Boolean or a String like "true"/"false").
        Boolean forceEnable = null;
        Object enableObj = parameters.get("enable");
        if (enableObj != null) {
            if (enableObj instanceof Boolean b) {
                forceEnable = b;
            } else {
                String s = enableObj.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true")) {
                    forceEnable = Boolean.TRUE;
                } else if (s.equals("false")) {
                    forceEnable = Boolean.FALSE;
                } else if (!s.isEmpty()) {
                    return ToolResult.failure("Parameter 'enable' must be a boolean (true/false), got: " + enableObj);
                }
            }
        }

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

        InstanceToolSupport.ResolvedInstance resolvedTarget =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (resolvedTarget.failure() != null) {
            return resolvedTarget.failure();
        }
        String instanceId = resolvedTarget.name();

        Path shadersDir;
        try {
            shadersDir = repository.getRunDirectory(instanceId).resolve("shaderpacks");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve shaderpacks directory: " + e.getMessage());
        }
        if (!Files.isDirectory(shadersDir)) {
            return ToolFailures.failure(
                    "Shaderpacks directory does not exist for instance '" + instanceId + "': " + shadersDir,
                    ToolFailures.Retryable.NO,
                    "the instance has no shaderpacks folder yet, so there is nothing to toggle",
                    "Install a shader pack first, or check the instance id with the instance list");
        }

        // Collect candidate entries — a folder or a '.zip' archive, matched with or without an
        // already-applied '.disabled' suffix stripped first — by case-insensitive substring over
        // the base name, exactly like ToggleModTool matches mods.
        List<Entry> candidates = new ArrayList<>();
        List<Path> installedPacks = new ArrayList<>();
        String needle = shaderQuery.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shadersDir)) {
            for (Path path : stream) {
                String rawName = path.getFileName().toString();
                boolean disabled = rawName.endsWith(DISABLED_SUFFIX);
                String baseName = disabled ? StringUtils.removeSuffix(rawName, DISABLED_SUFFIX) : rawName;
                boolean isPackKind = Files.isDirectory(path)
                        || (Files.isRegularFile(path) && baseName.toLowerCase(Locale.ROOT).endsWith(".zip"));
                if (!isPackKind) {
                    continue;
                }
                installedPacks.add(path);
                if (baseName.toLowerCase(Locale.ROOT).contains(needle)) {
                    candidates.add(new Entry(path, rawName, baseName, disabled));
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to list shaderpacks directory: " + e.getMessage());
        }

        if (candidates.isEmpty()) {
            return ToolFailures.failure(
                    "No shader pack matching '" + shaderQuery + "' was found in " + shadersDir,
                    ToolFailures.Retryable.YES,
                    "no installed shader pack name contains this substring, which is usually a typo",
                    "installed shaders: " + describeInstalledPacks(installedPacks)
                            + "; use instance(action=\"packs_list_local\") for the full list, or refine the query");
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(shaderQuery).append("' matches ").append(candidates.size()).append(" entries:\n");
            for (Entry c : candidates) {
                sb.append("  - ").append(c.rawName).append('\n');
            }
            sb.append("Please refine the 'shader' parameter to match exactly one entry.");
            return ToolResult.failure(sb.toString().trim());
        }

        Entry match = candidates.get(0);
        boolean currentlyEnabled = !match.disabled;
        boolean targetEnabled = forceEnable != null ? forceEnable : !currentlyEnabled;

        if (targetEnabled == currentlyEnabled) {
            return ToolResult.success("Shader pack '" + match.rawName + "' is already "
                    + (currentlyEnabled ? "enabled" : "disabled") + "; no change made.\n"
                    + "Path: " + match.path);
        }

        String newName = targetEnabled ? match.baseName : match.baseName + DISABLED_SUFFIX;
        Path target = match.path.resolveSibling(newName);
        if (!target.equals(match.path) && Files.exists(target)) {
            return ToolFailures.failure(
                    "Cannot rename '" + match.rawName + "' to '" + newName + "': the target already exists "
                            + "in the shaderpacks folder, so both an enabled and a disabled copy of this shader "
                            + "pack are present",
                    ToolFailures.Retryable.NO,
                    "renaming would silently overwrite one of the duplicate entries",
                    "Delete one of the two duplicate entries first (shaders_delete with the exact name), then retry");
        }

        try {
            Files.move(match.path, target);
        } catch (IOException e) {
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Renaming shader pack '" + match.rawName + "' to '" + newName + "' in instance '" + instanceId + "' failed",
                    e);
        }
        if (!Files.exists(target)) {
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Renaming shader pack '" + match.rawName + "' to '" + newName + "' in instance '" + instanceId + "' failed",
                    null);
        }

        return ToolResult.success("Shader pack " + (targetEnabled ? "enabled" : "disabled") + " in instance '" + instanceId + "'.\n"
                + "  " + match.rawName + "  ->  " + newName + "\n"
                + "  state: " + (targetEnabled ? "enabled" : "disabled") + "\n"
                + "  path : " + target);
    }

    /// One matched shaderpacks-folder entry: its current path, raw (on-disk) name, base name with
    /// any `.disabled` suffix already stripped, and whether that suffix was present.
    private record Entry(Path path, String rawName, String baseName, boolean disabled) {
    }

    /// The maximum number of real pack names carried in a zero-match failure — enough for the
    /// model to spot a typo, bounded so a huge shaderpacks folder can't flood the context. Mirrors
    /// {@link DeleteShaderTool}'s zero-match pack listing.
    private static final int MAX_LISTED_PACKS = 10;

    /// Lists up to [#MAX_LISTED_PACKS] real pack names (folders and `.zip` archives, `.disabled`
    /// suffix included as on disk) for a zero-match failure, appending a "(N more)" tail when
    /// truncated; an empty folder is reported explicitly. Mirrors {@link DeleteShaderTool}'s
    /// `describeInstalledPacks`.
    private static String describeInstalledPacks(List<Path> packs) {
        if (packs.isEmpty()) {
            return "(none — the shaderpacks folder is empty)";
        }
        int shown = Math.min(packs.size(), MAX_LISTED_PACKS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(packs.get(i).getFileName());
        }
        if (packs.size() > shown) {
            sb.append(", ... (").append(packs.size() - shown).append(" more)");
        }
        return sb.toString();
    }
}
