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

import org.jackhuang.hmcl.addon.mod.LocalMod;
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A controlled-write tool that rolls a single installed mod back to one of its archived
/// previous versions — the `*.jar.old` backups HMCL keeps whenever a mod is updated (by the
/// native "check for updates" flow or by {@link UpdateModTool}). It is the AI-facing equivalent
/// of the native mod list's restore button (see {@code ModListPageSkin}'s {@code restoreButton}
/// + {@code ModListPage#rollback}).
///
/// The whole operation goes through HMCL's native mod state machine — no faked file juggling:
/// - [`HMCLGameRepository#getModManager(String)`] + [`ModManager#getLocalFiles()`] to parse and
///   locate the currently installed (active) mods,
/// - the current mod is resolved by a case-insensitive substring over its file name, display name
///   or mod id — the same matcher {@link ToggleModTool}/{@link DeleteModTool} share
///   ([`ToggleModTool#resolveTrackedMod`]); it refuses to act unless exactly one mod matches,
/// - the rollback targets are the archived versions registered on that mod's [`LocalMod#getOldFiles`],
/// - [`ModManager#rollback(LocalModFile, LocalModFile)`] performs the swap: it archives the current
///   jar as `.old` and restores the chosen previous version as the active one — exactly the call
///   the native restore menu makes.
///
/// The rollback is non-destructive and reversible: no file is deleted, the version rolled back
/// FROM is kept on disk as a fresh `.old` archive (so a later call can roll forward/back again).
///
/// ### Read vs. write
/// - `mod` only (no `version`): READ-ONLY. Lists the archived versions this mod can be rolled
///   back to.
/// - `mod` + `version`: performs the rollback to the single archived version matched by `version`
///   (its version string or a distinguishing part of its file name).
///
/// The mod file may be held open by a running game while this executes; when a rename fails the
/// failure is attributed via [GameResourceGuard#checkInstanceNotRunning(String)]
/// ([`ToggleModTool#fileOperationFailure`]) instead of pretending success.
///
/// Permission level: modifies the file system (renames the mod files it swaps). It never deletes
/// anything.
@NotNullByDefault
public final class RollbackModTool implements Tool {

    @Override
    public String getName() {
        return "rollback_mod";
    }

    @Override
    public String getDescription() {
        return "Rolls a single installed mod back to one of its archived previous versions (the '.old' "
                + "backups HMCL keeps whenever a mod is updated), through HMCL's native mod manager — the same "
                + "action as the mod list's restore button. "
                + "Parameters: mod (required, the mod file name — or a case-insensitive substring of the file name, "
                + "display name or mod id — that matches exactly one installed mod), "
                + "version (optional, the historical version to roll back to: its version string or a distinguishing "
                + "part of its file name, matching exactly one archived version), "
                + "instance (optional, the instance/version id; defaults to the currently selected instance). "
                + "If 'version' is omitted it only LISTS the archived versions this mod can be rolled back to "
                + "(read-only). When 'version' is given it archives the current jar with the '.old' suffix and "
                + "restores the chosen previous version as the active one — no file is deleted and the swap is "
                + "reversible. Fails clearly if the mod is missing or ambiguous, has no archived versions, or the "
                + "version is missing or ambiguous.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object modObj = parameters.get("mod");
        if (modObj == null || modObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'mod' (the mod file name or a substring of it).");
        }
        String modQuery = modObj.toString().trim();

        @Nullable String versionQuery = InstanceToolSupport.string(parameters, "version");

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

        // NOTE: generic aliases are NOT accepted for instance resolution — 'mod' and 'version' are
        // distinct parameters and the instance resolver must not steal either of them.
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
                    "the instance has no mods folder yet, so there is nothing to roll back",
                    "Use list_mods to see the installed mods, or check the instance id");
        }

        // Resolve the current (active) mod through the native ModManager/LocalModFile state machine,
        // exactly like ToggleModTool/DeleteModTool (shared helper) — zero or several matches is a
        // failure, so the wrong mod can never be rolled back by accident.
        ModManager modManager = repository.getModManager(instanceId);
        ToggleModTool.ResolvedMod resolved = ToggleModTool.resolveTrackedMod(modManager, modsDir, modQuery);
        if (resolved.failure() != null) {
            return resolved.failure();
        }
        LocalModFile current = resolved.mod();
        String label = safe(current.getName()).isBlank() ? current.getFileName() : current.getName();

        // The rollback targets are the archived (.old) versions registered on this mod, exactly the
        // set the native restore menu reads (modInfo.getMod().getOldFiles()).
        LocalMod mod = current.getMod();
        List<LocalModFile> oldFiles = new ArrayList<>(mod.getOldFiles());
        if (oldFiles.isEmpty()) {
            return ToolFailures.failure(
                    "Mod '" + label + "' (" + current.getFile().getFileName() + ") in instance '" + instanceId
                            + "' has no archived previous versions to roll back to",
                    ToolFailures.Retryable.NO,
                    "a rollback target is only created when the mod is updated (the superseded jar is kept as '.old')",
                    "There is nothing to roll back; update the mod first (mods_update) if you want a version you can "
                            + "later roll back from, or install a specific build with mods_install");
        }
        oldFiles.sort(null); // LocalModFile is Comparable (by file name) — deterministic ordering.

        // Read-only mode: no 'version' given → list the archived versions the caller can pick from.
        if (versionQuery == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Mod '").append(label).append("' (").append(current.getFile().getFileName())
                    .append(") in instance '").append(instanceId).append("' has ").append(oldFiles.size())
                    .append(" archived previous version(s) you can roll back to:\n");
            for (LocalModFile old : oldFiles) {
                sb.append("  - ").append(describeOld(old)).append('\n');
            }
            sb.append("To roll back, call this action again with version=<one of the above> "
                    + "(the version string, or a distinguishing part of the file name).");
            return ToolResult.success(sb.toString());
        }

        // Write mode: match 'version' to exactly one archived version.
        String needle = versionQuery.toLowerCase(Locale.ROOT);
        List<LocalModFile> matches = new ArrayList<>();
        for (LocalModFile old : oldFiles) {
            if (safe(old.getVersion()).toLowerCase(Locale.ROOT).contains(needle)
                    || old.getFile().getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)
                    || safe(old.getFileName()).toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(old);
            }
        }
        if (matches.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("No archived version of mod '").append(label).append("' matches \"").append(versionQuery)
                    .append("\". Available version(s): ");
            appendVersions(sb, oldFiles);
            return ToolFailures.failure(
                    sb.toString(),
                    ToolFailures.Retryable.YES,
                    "no archived version string or file name contains this substring",
                    "Call this action with only 'mod' to list the exact archived versions, then retry with one of them");
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(matches.size()).append(" archived versions of mod '").append(label).append("' match \"")
                    .append(versionQuery).append("\":\n");
            for (LocalModFile old : matches) {
                sb.append("  - ").append(describeOld(old)).append('\n');
            }
            return ToolFailures.failure(
                    sb.toString().trim(),
                    ToolFailures.Retryable.YES,
                    "the version substring is not specific enough to pick a single archived version",
                    "Refine 'version' to match exactly one — use a distinguishing part of its file name");
        }
        LocalModFile target = matches.get(0);

        // ModManager#rollback silently no-ops when the target file name equals the current one
        // (it refuses to overwrite an identically-named jar) — detect it up front and report it,
        // rather than returning a "success" that changed nothing.
        if (current.getFileName().equals(target.getFileName())) {
            return ToolFailures.failure(
                    "The archived version '" + target.getFile().getFileName() + "' has the same file name as the "
                            + "current '" + current.getFile().getFileName() + "', so rolling back to it would change nothing",
                    ToolFailures.Retryable.NO,
                    "HMCL cannot roll back to a version whose file name matches the current one",
                    "Pick a different archived version (call this action with only 'mod' to list them), or reinstall "
                            + "the desired build with mods_install");
        }

        String currentBefore = current.getFile().getFileName().toString();
        String targetBefore = target.getFile().getFileName().toString();
        String targetVersion = safe(target.getVersion());

        // Perform the swap through the native state machine (archive current as .old, restore the
        // chosen version). This does file renames; the tool runs off the JavaFX thread, matching the
        // sibling mod write tools (ToggleModTool#setActive / UpdateModTool#archiveOldJar) — the mod
        // objects come from a freshly created ModManager and are not bound to any open mod page.
        try {
            modManager.rollback(current, target);
        } catch (IOException e) {
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Rolling back mod '" + label + "' to '" + targetBefore + "' in instance '" + instanceId + "'", e);
        } catch (Throwable e) {
            // IllegalState/IllegalArgument from ModManager#rollback's own precondition checks; our
            // resolution already satisfies them, so this should not happen — report truthfully if it does.
            return ToolResult.failure("Failed to roll back mod '" + label + "': " + messageOf(e));
        }

        // Verify the restored file actually landed on disk before reporting success (ModManager only
        // moves a file that exists; a vanished archive would otherwise slip through as a silent success).
        Path restored = target.getFile();
        if (!Files.exists(restored)) {
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Rolled back mod '" + label + "' but the restored file '" + restored.getFileName()
                            + "' is missing afterwards", null);
        }

        String targetAfter = restored.getFileName().toString();
        String currentAfter = current.getFile().getFileName().toString();
        boolean nowEnabled = target.isActive();

        StringBuilder sb = new StringBuilder();
        sb.append("Rolled back mod '").append(label).append("' in instance '").append(instanceId).append("':\n");
        sb.append("  restored: ").append(targetBefore).append("  ->  ").append(targetAfter);
        if (!targetVersion.isBlank()) {
            sb.append("  (version ").append(targetVersion).append(')');
        }
        sb.append("  — now the ").append(nowEnabled ? "active" : "current (disabled)").append(" version\n");
        sb.append("  archived: ").append(currentBefore).append("  ->  ").append(currentAfter)
                .append("  — the version you rolled back from; kept on disk for another rollback\n");
        sb.append("The change takes effect the next time the instance is launched.");
        return ToolResult.success(sb.toString());
    }

    /// One-line description of an archived version for the read-only listing and the ambiguous-match
    /// failures: its version string when known, always its on-disk file name.
    private static String describeOld(LocalModFile old) {
        String version = safe(old.getVersion());
        String fileName = old.getFile().getFileName().toString();
        return version.isBlank()
                ? "file " + fileName
                : "version " + version + "  (file: " + fileName + ")";
    }

    /// Comma-joined version strings (falling back to file name when a version is unknown) of the
    /// archived versions — the candidate list carried by the "no such version" failure.
    private static void appendVersions(StringBuilder sb, List<LocalModFile> oldFiles) {
        for (int i = 0; i < oldFiles.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            LocalModFile old = oldFiles.get(i);
            String version = safe(old.getVersion());
            sb.append(version.isBlank() ? old.getFile().getFileName().toString() : version);
        }
    }

    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
