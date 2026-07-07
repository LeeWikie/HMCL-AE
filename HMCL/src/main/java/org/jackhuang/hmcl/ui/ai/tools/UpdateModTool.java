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

import org.jackhuang.hmcl.addon.LocalAddonFile.AddonUpdate;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.ai.tools.ToolParams;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A controlled-write tool that updates a single installed mod to its newest
/// compatible version and removes the old jar, so two copies of the same mod can
/// never coexist (the classic "duplicate mods" launch crash).
///
/// This reuses HMCL's native pipeline end to end — no faked matching:
/// - [`HMCLGameRepository#getModManager(String)`] + [`ModManager#getLocalFiles()`]
///   to locate and parse the installed mods,
/// - [`LocalModFile#checkUpdates`] (the same call behind HMCL's "check for updates"
///   button) to hash the local jar, match it to a remote project and find a strictly
///   newer version for the same game version and mod loader,
/// - [`FileDownloadTask`] (with the active [`DownloadProvider`]) to download the new
///   file into the same folder as the old one,
/// - [`FileTrash`] to remove the superseded old jar (recycle bin when possible).
///
/// The mod is resolved by a case-insensitive substring over its file name, display
/// name or mod id (like {@link GetModInfoTool}); the tool refuses to act unless
/// exactly one mod matches.
///
/// Ordering guarantees no duplicate is left behind: the new file is downloaded and
/// verified first, then the old jar is removed. The game is not running during a
/// tool call, so the brief on-disk overlap is harmless; what matters is that only
/// one version remains when the tool returns.
///
/// Permission level: {@link ToolPermission#CONTROLLED_WRITE}. It downloads one file
/// and removes the single old jar it replaces.
@NotNullByDefault
public final class UpdateModTool implements ToolSpec {

    /// Per-source network timeout for the update check, in seconds.
    private static final int CHECK_TIMEOUT_SECONDS = 30;

    /// Maximum time to wait for the download to finish, in seconds.
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    /// Whether to route the removal of the old jar to the OS recycle bin (recoverable)
    /// instead of permanently deleting it; read live on each call.
    private final java.util.function.BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether to prefer the OS recycle bin (recoverable) over a
    ///                     permanent delete for the superseded old jar; typically
    ///                     `aiSettings::isDeleteToRecycleBin`.
    public UpdateModTool(java.util.function.BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "update_mod";
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
                   "mod": {"type": "string", "description": "A case-insensitive substring of the mod's file name, display name or mod id that matches exactly one installed mod (e.g. \\"sodium\\")."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."},
                   "source": {"type": "string", "enum": ["modrinth", "curseforge"], "description": "Optional: restrict the update lookup to a single source. By default both Modrinth and CurseForge are checked and the newest match wins."}
                 },
                 "required": ["mod"]
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Updates a single installed mod to its newest compatible version and removes the old jar, so two "
                + "versions of the same mod never coexist (which would crash the game on launch). "
                + "Parameters: mod (required, a case-insensitive substring of the mod's file name, display name or mod id "
                + "that matches exactly one installed mod), "
                + "instance (optional, the instance/version id; defaults to the selected instance), "
                + "source (optional, \"modrinth\" or \"curseforge\"; by default both are checked and the newest wins). "
                + "It hashes the local jar to match it to a remote project (same as the 'check for updates' button), "
                + "downloads the newer file, then recycles/deletes the old jar. "
                + "Use check_mod_updates first to see what can be updated. Network calls are time-bounded.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String query = ToolParams.string(parameters, "mod", "name", "id", "modId", "query");
        if (query.isEmpty()) {
            return ToolResult.failure("Missing required parameter: mod (a substring of the mod file/display name or id).");
        }
        @Nullable RemoteAddon.Source onlySource = parseSource(String.valueOf(parameters.getOrDefault("source", "")).trim());

        ModManager modManager;
        String target;
        String gameVersion;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            InstanceToolSupport.ResolvedInstance resolved =
                    InstanceToolSupport.resolveInstance(repo, parameters, false);
            if (resolved.failure() != null) {
                return resolved.failure();
            }
            target = resolved.name();
            modManager = repo.getModManager(target);
            gameVersion = repo.getGameVersion(target).orElse(null);
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve the instance's mods: " + t.getMessage());
        }

        if (gameVersion == null || gameVersion.isBlank()) {
            return ToolResult.failure("Could not determine the Minecraft version of instance '" + target
                    + "', which is required to match a compatible update.");
        }

        List<LocalModFile> all;
        try {
            all = new ArrayList<>(modManager.getLocalFiles());
        } catch (Throwable t) {
            return ToolResult.failure("Failed to parse mods in instance '" + target + "': " + t.getMessage());
        }
        if (all.isEmpty()) {
            return ToolResult.success("Instance '" + target + "' has no mods installed.");
        }

        // Resolve exactly one mod by substring over file name / display name / id.
        String needle = query.toLowerCase(Locale.ROOT);
        List<LocalModFile> matches = new ArrayList<>();
        for (LocalModFile mod : all) {
            if (safe(mod.getFileName()).toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getName()).toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getId()).toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(mod);
            }
        }
        if (matches.isEmpty()) {
            return ToolResult.failure("No installed mod matches \"" + query + "\" in instance '" + target
                    + "'. Use list_mods to see the available mods.");
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(matches.size()).append(" mods match \"").append(query)
                    .append("\" in instance '").append(target).append("'. Refine 'mod' to one of:\n");
            for (LocalModFile mod : matches) {
                sb.append("  - ").append(mod.getFileName());
                String name = safe(mod.getName());
                if (!name.isBlank() && !name.equalsIgnoreCase(mod.getFileName())) {
                    sb.append("  (").append(name).append(')');
                }
                sb.append('\n');
            }
            return ToolResult.failure(sb.toString().trim());
        }

        LocalModFile mod = matches.get(0);

        // Find the newest available update across the requested source(s).
        DownloadProvider provider = DownloadProviders.getDownloadProvider();
        RemoteAddon.Source[] sources = onlySource != null
                ? new RemoteAddon.Source[]{onlySource}
                : RemoteAddon.Source.values();

        AddonUpdate best = null;
        int failed = 0;
        for (RemoteAddon.Source source : sources) {
            AddonUpdate update;
            try {
                update = ContentToolSupport.callWithTimeout(
                        () -> mod.checkUpdates(provider, gameVersion, source),
                        CHECK_TIMEOUT_SECONDS, "Update check");
            } catch (Exception e) {
                failed++;
                continue;
            }
            if (update == null) {
                continue;
            }
            if (best == null || best.targetVersion().datePublished()
                    .isBefore(update.targetVersion().datePublished())) {
                best = update;
            }
        }

        if (best == null) {
            String suffix = failed > 0
                    ? " (" + failed + " remote lookup(s) failed or timed out)"
                    : "";
            return ToolResult.success("Mod '" + mod.getFileName() + "' is already up to date, or no newer compatible "
                    + "version could be matched on " + (onlySource != null ? sourceName(onlySource) : "Modrinth/CurseForge")
                    + " for Minecraft " + gameVersion + suffix + ". Nothing was changed.");
        }

        RemoteAddon.Version newVersion = best.targetVersion();
        RemoteAddon.File remoteFile = newVersion.file();
        if (remoteFile == null || remoteFile.url() == null || remoteFile.url().isBlank()
                || remoteFile.filename() == null || remoteFile.filename().isBlank()) {
            return ToolResult.failure("The newer version '" + describe(newVersion)
                    + "' of '" + mod.getFileName() + "' has no downloadable file.");
        }

        Path oldFile = mod.getFile();
        Path destDir = oldFile.getParent();
        if (destDir == null) {
            return ToolResult.failure("Could not resolve the folder of '" + oldFile + "'.");
        }
        Path dest = destDir.resolve(remoteFile.filename());
        boolean sameTarget = dest.equals(oldFile);

        // Download the new file (unless it is already present). Downloading first guarantees we
        // never remove the old jar before the replacement is safely on disk.
        // When the new version has the SAME filename, dest == the old jar, so it always "exists" —
        // but that is the file we must overwrite, not skip. Only treat a DIFFERENT filename that is
        // already on disk as already-present.
        boolean alreadyPresent = !sameTarget && Files.exists(dest);
        if (!alreadyPresent) {
            // Download through the shared helper, which retries with backoff and switches the
            // download source (configured provider → official → BMCLAPI mirror) on failure, and
            // cancels the task on timeout. The factory rebuilds the task for each candidate
            // provider so a retry genuinely switches mirror.
            try {
                ContentToolSupport.runDownloadWithFallback(p -> {
                    FileDownloadTask download = new FileDownloadTask(
                            p.injectURLWithCandidates(remoteFile.url()), dest, remoteFile.getIntegrityCheck());
                    download.setName(newVersion.name());
                    return download;
                }, DOWNLOAD_TIMEOUT_SECONDS, "Download");
            } catch (Exception e) {
                if (ContentToolSupport.isNetworkError(e)) {
                    return ToolResult.failure(ContentToolSupport.networkErrorAdvice(e));
                }
                return ToolResult.failure("Download failed for the newer version '" + describe(newVersion)
                        + "' of '" + mod.getFileName() + "': " + AbstractContentSearchTool.messageOf(e));
            }
        }

        // Remove the superseded old jar so the two versions never coexist. When the new file has
        // the exact same name it was overwritten in place, so there is nothing to remove.
        String removalNote;
        if (sameTarget) {
            removalNote = "  old jar : replaced in place (" + oldFile.getFileName() + ")";
        } else {
            boolean recycled;
            try {
                recycled = FileTrash.delete(oldFile, toRecycleBin.getAsBoolean());
            } catch (Throwable e) {
                return ToolResult.failure("Downloaded the new version to:\n  " + dest
                        + "\nbut FAILED to remove the old jar '" + oldFile.getFileName() + "': " + e.getMessage()
                        + "\nTwo versions of the mod are now present — delete the old jar manually to avoid a "
                        + "duplicate-mod crash.");
            }
            removalNote = recycled
                    ? "  old jar : '" + oldFile.getFileName() + "' moved to the recycle bin (recoverable)"
                    : "  old jar : '" + oldFile.getFileName() + "' permanently deleted";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Updated mod in instance '").append(target).append("' (Minecraft ").append(gameVersion).append("):\n");
        sb.append("  mod     : ").append(safe(mod.getName()).isBlank() ? mod.getFileName() : mod.getName()).append('\n');
        sb.append("  from    : ").append(describe(best.currentVersion())).append('\n');
        sb.append("  to      : ").append(describe(newVersion)).append(" (").append(sourceName(newVersion)).append(")\n");
        sb.append("  new file: ").append(remoteFile.filename());
        if (alreadyPresent && !sameTarget) {
            sb.append(" (already on disk)");
        }
        sb.append('\n');
        sb.append(removalNote).append('\n');
        sb.append("  path    : ").append(dest);
        if (failed > 0) {
            sb.append("\n(Note: ").append(failed).append(" remote lookup(s) failed or timed out and were skipped.)");
        }
        return ToolResult.success(sb.toString());
    }

    /// Parses an optional source filter; returns {@code null} for "both / unspecified".
    @Nullable
    private static RemoteAddon.Source parseSource(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("curse")) {
            return RemoteAddon.Source.CURSEFORGE;
        }
        if (v.startsWith("modr")) {
            return RemoteAddon.Source.MODRINTH;
        }
        return null;
    }

    private static String sourceName(RemoteAddon.Source source) {
        return source == RemoteAddon.Source.CURSEFORGE ? "CurseForge" : "Modrinth";
    }

    @Nullable
    private static String sourceNameOrNull(@Nullable RemoteAddon.Version version) {
        if (version == null || version.self() == null) {
            return null;
        }
        return sourceName(version.self().getType());
    }

    private static String sourceName(RemoteAddon.Version version) {
        String name = sourceNameOrNull(version);
        return name != null ? name : "remote";
    }

    private static String describe(@Nullable RemoteAddon.Version version) {
        if (version == null) {
            return "(unknown)";
        }
        String v = version.version();
        if (v == null || v.isBlank()) {
            v = version.name();
        }
        String name = version.name();
        if (name != null && !name.isBlank() && !name.equals(v)) {
            return v + " (" + name + ")";
        }
        return v == null ? "(unnamed)" : v;
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
