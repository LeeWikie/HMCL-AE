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
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A tool that enables or disables a mod of a Minecraft instance THROUGH the native
/// [ModManager]/[LocalModFile] state machine (the same objects behind HMCL's mod page),
/// instead of renaming files behind its back.
///
/// In HMCL (as in most launchers) a mod is considered enabled when its file name
/// ends with `.jar`, and disabled when the `.disabled` suffix is appended
/// (`xxx.jar.disabled`). This tool resolves the [LocalModFile] via
/// [`HMCLGameRepository#getModManager(String)`] + [`ModManager#getLocalFiles()`] and flips its
/// state with [`LocalModFile#setActive(boolean)`], which delegates to
/// [`ModManager#enableMod`]/[`ModManager#disableMod`] — so the on-disk naming conventions
/// (including the `.old` rollback archive convention) always stay consistent with the native UI.
///
/// It reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`],
/// - [`HMCLGameRepository#getRunDirectory(String)`] to locate the `mods` folder,
/// - [`HMCLGameRepository#getModManager(String)`] to parse the installed mods.
///
/// When a rename does not take effect (typically because the file is held open by a running
/// game), the failure is attributed via [GameResourceGuard#checkInstanceNotRunning(String)]
/// and reported in the unified failure envelope instead of pretending success.
///
/// Permission level: MODIFIES the file system (renames a file on disk).
@NotNullByDefault
public final class ToggleModTool implements Tool {

    private static final String DISABLED_SUFFIX = LocalAddonManager.DISABLED_EXTENSION;
    private static final String OLD_SUFFIX = LocalAddonManager.OLD_EXTENSION;

    @Override
    public String getName() {
        return "toggle_mod";
    }

    @Override
    public String getDescription() {
        return "Enables or disables a single mod of a Minecraft instance through HMCL's native mod manager "
                + "(enabled = 'xxx.jar', disabled = 'xxx.jar.disabled'). "
                + "Parameters: 'mod' (required, the mod file name — or a case-insensitive substring of the file name, "
                + "display name or mod id — that matches exactly one installed mod), "
                + "'instance' (optional, the instance/version id; defaults to the currently selected instance), "
                + "'enable' (optional boolean; if provided forces enable=true or disable=false, otherwise the current state is toggled). "
                + "WARNING: this modifies the file system by renaming a file on disk. "
                + "Returns the old name, the new name and the resulting state. "
                + "Fails if the substring matches zero or more than one mod.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object modObj = parameters.get("mod");
        if (modObj == null || modObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'mod' (the mod file name or a substring of it).");
        }
        String modQuery = modObj.toString().trim();

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

        // Resolve the instance.
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

        Path modsDir;
        try {
            modsDir = repository.getRunDirectory(instanceId).resolve("mods");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve mods directory: " + e.getMessage());
        }
        if (!Files.isDirectory(modsDir)) {
            return ToolFailures.failure(
                    "Mods directory does not exist for instance '" + instanceId + "': " + modsDir,
                    ToolFailures.Retryable.NO,
                    "the instance has no mods folder yet, so there is nothing to toggle",
                    "Install a mod first, or check the instance id with the instance list");
        }

        // Resolve the mod through the native ModManager/LocalModFile state machine.
        ResolvedMod resolved = resolveTrackedMod(repository.getModManager(instanceId), modsDir, modQuery);
        if (resolved.failure() != null) {
            return resolved.failure();
        }
        LocalModFile mod = resolved.mod();

        Path source = mod.getFile();
        String oldName = source.getFileName().toString();
        boolean currentlyEnabled = mod.isActive();

        boolean targetEnabled = forceEnable != null ? forceEnable : !currentlyEnabled;

        if (targetEnabled == currentlyEnabled) {
            return ToolResult.success("Mod '" + oldName + "' is already "
                    + (currentlyEnabled ? "enabled" : "disabled") + "; no change made.\n"
                    + "Path: " + source);
        }

        String newName = targetEnabled
                ? StringUtils.removeSuffix(oldName, DISABLED_SUFFIX)
                : oldName + DISABLED_SUFFIX;

        // The native ModManager renames with REPLACE_EXISTING, which would silently clobber a
        // stale duplicate — refuse explicitly instead, like the pre-ModManager implementation did.
        Path target = source.resolveSibling(newName);
        if (!target.equals(source) && Files.exists(target)) {
            return ToolFailures.failure(
                    "Cannot rename '" + oldName + "' to '" + newName + "': the target file already exists "
                            + "in the mods folder, so both an enabled and a disabled copy of this mod are present",
                    ToolFailures.Retryable.NO,
                    "renaming would silently overwrite one of the duplicate files",
                    "Delete one of the two duplicate files first (delete_mod with the exact file name), then retry");
        }

        // Flip the state through the native state machine. LocalModFile#setActive delegates to
        // ModManager#enableMod/#disableMod but swallows IOExceptions (it only logs them), so
        // verify the rename actually happened before reporting success.
        mod.setActive(targetEnabled);
        Path resultFile = mod.getFile();
        if (!resultFile.getFileName().toString().equals(newName) || !Files.exists(resultFile)) {
            return fileOperationFailure(instanceId,
                    "Renaming mod '" + oldName + "' to '" + newName + "' in instance '" + instanceId + "' failed",
                    null);
        }

        return ToolResult.success("Mod " + (targetEnabled ? "enabled" : "disabled") + " in instance '" + instanceId + "'.\n"
                + "  " + oldName + "  ->  " + newName + "\n"
                + "  state: " + (targetEnabled ? "enabled" : "disabled") + "\n"
                + "  path : " + resultFile);
    }

    // ---------------------------------------------------------------------
    // Shared helpers for the mod tool trio (ToggleModTool / DeleteModTool / UpdateModTool).
    // They live here because the three tools form one file-ownership group; extracting a
    // separate support class would spread the mods-domain logic over yet another file.
    // ---------------------------------------------------------------------

    /// Result of [#resolveTrackedMod]: exactly one of [#mod] / [#failure] is non-null.
    record ResolvedMod(@Nullable LocalModFile mod, @Nullable ToolResult failure) {
        static ResolvedMod of(LocalModFile mod) {
            return new ResolvedMod(mod, null);
        }

        static ResolvedMod fail(ToolResult failure) {
            return new ResolvedMod(null, failure);
        }
    }

    /// Resolves exactly one installed mod through the native [ModManager] by a case-insensitive
    /// substring over the on-disk file name, the display name and the mod id. Zero or several
    /// matches is a failure. When nothing is tracked but a file on disk does match the query, the
    /// failure explains why the file is not manageable (stale enabled+disabled duplicate pair,
    /// `.old` rollback archive, or a non-mod file) instead of a bare "not found".
    static ResolvedMod resolveTrackedMod(ModManager modManager, Path modsDir, String modQuery) {
        List<LocalModFile> all;
        try {
            all = modManager.getLocalFiles();
        } catch (Throwable e) {
            return ResolvedMod.fail(ToolFailures.failure(
                    "Failed to parse the mods of instance '" + modManager.getInstanceId() + "': " + e.getMessage(),
                    ToolFailures.Retryable.LATER,
                    "reading the mods folder failed, which is usually a transient I/O problem",
                    "Retry once; if it keeps failing, check the mods folder on disk: " + modsDir));
        }

        String needle = modQuery.toLowerCase(Locale.ROOT);
        List<LocalModFile> matches = new ArrayList<>();
        for (LocalModFile mod : all) {
            if (mod.getFile().getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getName()).toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getId()).toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(mod);
            }
        }

        if (matches.isEmpty()) {
            return ResolvedMod.fail(noMatchFailure(modsDir, modQuery, needle));
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(modQuery).append("' matches ").append(matches.size()).append(" mod files:\n");
            for (LocalModFile mod : matches) {
                sb.append("  - ").append(mod.getFile().getFileName()).append('\n');
            }
            return ResolvedMod.fail(ToolFailures.failure(
                    sb.toString().trim(),
                    ToolFailures.Retryable.YES,
                    "a more specific query resolves to exactly one file",
                    "Refine the 'mod' parameter to match exactly one of the listed files"));
        }
        return ResolvedMod.of(matches.get(0));
    }

    /// The maximum number of real file names carried in a zero-match failure — enough for the
    /// model to spot a typo, bounded so a huge mods folder can't flood the context.
    private static final int MAX_LISTED_FILES = 10;

    /// Builds the zero-match failure. Lists the mods directory ONCE and reuses that single
    /// listing for both jobs, so the failure carries the actual candidates just like the
    /// multi-match branch already does (B10/#19): (a) when a file on disk DOES match the query
    /// but the [ModManager] does not track it as a manageable mod, explain why (stale
    /// enabled+disabled duplicate pair, `.old` rollback archive, or a non-mod file); (b) otherwise
    /// a plain "not found" that lists the first [#MAX_LISTED_FILES] real file names in the folder
    /// so a typo is obvious without a separate list_mods round-trip.
    private static ToolResult noMatchFailure(Path modsDir, String modQuery, String needle) {
        List<Path> allFiles = new ArrayList<>();
        boolean listed = true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    allFiles.add(entry);
                }
            }
        } catch (Throwable e) {
            listed = false; // directory unreadable — fall back to a not-found without candidates
        }

        if (listed) {
            ToolResult untracked = explainUntrackedMatch(modsDir, allFiles, needle);
            if (untracked != null) {
                return untracked;
            }
        }

        return ToolFailures.failure(
                "No mod file matching '" + modQuery + "' was found in " + modsDir,
                ToolFailures.Retryable.YES,
                "no installed mod file name contains this substring, which is usually a typo",
                listed
                        ? "installed mods: " + describeInstalledFiles(allFiles)
                            + "; use list_mods for the full list, or refine the query"
                        : "Use list_mods to see the installed mods, then retry with an exact file name");
    }

    /// Lists up to [#MAX_LISTED_FILES] real file names in the mods folder for a zero-match
    /// failure, appending a "(N more)" tail when truncated; an empty folder is reported explicitly.
    private static String describeInstalledFiles(List<Path> allFiles) {
        if (allFiles.isEmpty()) {
            return "(none — the mods folder is empty)";
        }
        int shown = Math.min(allFiles.size(), MAX_LISTED_FILES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(allFiles.get(i).getFileName());
        }
        if (allFiles.size() > shown) {
            sb.append(", ... (").append(allFiles.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    /// When the [ModManager] tracked nothing for a query but a file on disk DOES match, explains
    /// why that file is not manageable (stale enabled+disabled duplicate pair, `.old` rollback
    /// archive, or a non-mod file). Reuses the single directory listing collected by
    /// [#noMatchFailure] (no second stream). Returns `null` when no on-disk file matches the query.
    @Nullable
    private static ToolResult explainUntrackedMatch(Path modsDir, List<Path> allFiles, String needle) {
        List<Path> onDisk = new ArrayList<>();
        for (Path file : allFiles) {
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                onDisk.add(file);
            }
        }

        for (Path file : onDisk) {
            String name = file.getFileName().toString();
            if (name.endsWith(OLD_SUFFIX)) {
                return ToolFailures.failure(
                        "The query only matches '" + name + "', which is an archived previous version "
                                + "('" + OLD_SUFFIX + "' files are kept for rollback and are not active mods)",
                        ToolFailures.Retryable.NO,
                        "rollback archives are managed by HMCL's native mod page, not by this tool",
                        "Ask the user to use the native mod list's rollback if they want this version back, "
                                + "or target the current (non-.old) mod file instead");
            }
            Path counterpart = name.endsWith(DISABLED_SUFFIX)
                    ? file.resolveSibling(StringUtils.removeSuffix(name, DISABLED_SUFFIX))
                    : file.resolveSibling(name + DISABLED_SUFFIX);
            if (Files.exists(counterpart)) {
                return ToolFailures.failure(
                        "Cannot operate on '" + name + "': its counterpart '" + counterpart.getFileName()
                                + "' already exists in the same mods folder, so both an enabled and a disabled "
                                + "copy of this mod are present and its state is ambiguous",
                        ToolFailures.Retryable.NO,
                        "acting on either duplicate would silently overwrite or shadow the other",
                        "Delete one of the two duplicate files first (delete_mod with the exact file name), then retry");
            }
        }
        if (!onDisk.isEmpty()) {
            String name = onDisk.get(0).getFileName().toString();
            return ToolFailures.failure(
                    "'" + name + "' exists in " + modsDir + " but is not recognized as a manageable mod file",
                    ToolFailures.Retryable.NO,
                    "only .jar/.litemod mod files (optionally with the .disabled suffix) are managed by the mod tools",
                    "Inspect the file with the file tools, or ask the user what it is");
        }
        return null;
    }

    /// Builds the failure for a mod file operation (rename/delete) that did not take effect,
    /// attributing it to a running game when [GameResourceGuard] detects one (G10): the classic
    /// cause of a locked mod file is the instance being played right now. Model-visible, English,
    /// unified envelope; both branches state that nothing was changed and how to proceed.
    static ToolResult fileOperationFailure(String instanceId, String what, @Nullable Throwable cause) {
        String detail = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? " (" + cause.getMessage().trim() + ")"
                : "";
        if (GameResourceGuard.checkInstanceNotRunning(instanceId) != null) {
            return ToolResult.failure(GameResourceGuard.rejectionText(
                    GameResourceGuard.Kind.INSTANCE_RUNNING, instanceId,
                    what + detail + " — the file is most likely held open by the running game"));
        }
        return ToolFailures.failure(
                what + detail + ". Nothing was changed",
                ToolFailures.Retryable.LATER,
                "the file may be held open by a game launched outside HMCL or by another program",
                "Ask the user to quit the game or close the program using the file, then retry this call");
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
