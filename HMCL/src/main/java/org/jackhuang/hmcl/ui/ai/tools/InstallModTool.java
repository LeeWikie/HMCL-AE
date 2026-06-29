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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A controlled-write tool that downloads a mod from Modrinth (or CurseForge)
/// into the selected instance's `mods` directory.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`ModrinthRemoteAddonRepository#MODS`] /
///   [`CurseForgeRemoteAddonRepository#MODS`] to resolve the mod and its versions,
/// - [`RemoteAddon.IMod#loadVersions`] to list downloadable versions,
/// - [`FileDownloadTask`] (with the active [`DownloadProvider`]) to download
///   the selected file, blocking on the HMCL [`Task`] until completion.
///
/// The target `mods` directory is derived from the game directory supplied to
/// the constructor (typically the selected instance's run directory). It can be
/// overridden per-call with the `modsDir` parameter.
///
/// Permission level: CONTROLLED_WRITE. It writes a single new file into the
/// mods folder and never deletes or overwrites existing mods.
@NotNullByDefault
public final class InstallModTool implements Tool {

    /// Maximum time to wait for a download to finish, in seconds.
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    /// The game/run directory of the target instance. Its `mods` subfolder is
    /// the default download destination.
    private final Path gameDirectory;

    /// Creates a tool that installs mods into `{gameDirectory}/mods`.
    ///
    /// @param gameDirectory the run directory of the target instance, e.g. the
    ///                      result of `repository.getRunDirectory(versionId)`
    public InstallModTool(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    @Override
    public String getName() {
        return "install_mod";
    }

    @Override
    public String getDescription() {
        return "Downloads a mod into the selected instance's mods folder. "
                + "Parameters: id (string, required: the Modrinth/CurseForge project id or slug from search_mods), "
                + "source (string, optional: \"modrinth\" (default) or \"curseforge\"), "
                + "loader (string, optional: fabric/forge/neoforge/quilt - filters to a matching version), "
                + "gameVersion (string, optional Minecraft version like \"1.20.1\" - filters to a matching version), "
                + "version (string, optional: a specific version name/number; otherwise the newest matching version is used), "
                + "modsDir (string, optional: absolute path override for the destination mods folder). "
                + "Returns the installed file name and path. This writes a new file to disk.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Fall back to "query" since the tool schema currently advertises a single param.
        String id = extractString(parameters, "id", extractString(parameters, "query", null));
        if (id == null || id.isBlank()) {
            return ToolResult.failure("Missing required parameter: id (the mod slug/project id from search_mods)");
        }

        String source = extractString(parameters, "source", "modrinth");
        String loaderStr = extractString(parameters, "loader", null);
        String gameVersion = extractString(parameters, "gameVersion", null);
        String versionName = extractString(parameters, "version", null);
        String modsDirOverride = extractString(parameters, "modsDir", null);
        String instance = extractString(parameters, "instance", null);

        RemoteAddonRepository repository;
        if ("curseforge".equalsIgnoreCase(source)) {
            if (!CurseForgeRemoteAddonRepository.isAvailable()) {
                return ToolResult.failure("CurseForge is not available (no API key configured). Use source=\"modrinth\".");
            }
            repository = CurseForgeRemoteAddonRepository.MODS;
        } else {
            repository = ModrinthRemoteAddonRepository.MODS;
        }

        ModLoaderType loader = parseLoader(loaderStr);
        if (loaderStr != null && loader == null) {
            return ToolResult.failure("Unknown loader '" + loaderStr + "'. Use fabric, forge, neoforge or quilt.");
        }

        DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();

        // Resolve the addon and pick the best matching version through the shared,
        // timeout-guarded helper (it wraps getModById + loadVersions each in a 60s
        // timeout and applies the standard game-version / version-id selection).
        RemoteAddon.Version selected;
        try {
            selected = ContentToolSupport.resolveVersion(repository, id, gameVersion, versionName);
        } catch (Exception e) {
            return ToolResult.failure("Failed to resolve mod '" + id + "': " + AbstractContentSearchTool.messageOf(e));
        }

        RemoteAddon.File file = selected.file();
        if (file == null || file.url() == null || file.url().isBlank()) {
            return ToolResult.failure("Selected version '" + selected.name() + "' has no downloadable file.");
        }

        // Determine the destination folder. Priority: explicit modsDir override >
        // a named target instance (isolation-aware via getRunDirectory) > the selected instance.
        Path modsDir;
        if (modsDirOverride != null) {
            modsDir = Path.of(modsDirOverride).toAbsolutePath().normalize();
        } else if (instance != null && !instance.isBlank()) {
            try {
                var repo = org.jackhuang.hmcl.setting.Profiles.getSelectedProfile().getRepository();
                if (!repo.hasVersion(instance)) {
                    return ToolResult.failure("No such instance '" + instance
                            + "'. Use list_instances to see installed instances.");
                }
                modsDir = repo.getRunDirectory(instance).resolve("mods");
            } catch (Throwable t) {
                return ToolResult.failure("Could not resolve instance '" + instance + "': " + t);
            }
        } else {
            modsDir = gameDirectory.resolve("mods");
        }

        Path dest = modsDir.resolve(file.filename());
        if (Files.exists(dest)) {
            return ToolResult.success("Mod file already present, skipping download: " + dest);
        }

        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            return ToolResult.failure("Failed to create mods directory " + modsDir + ": " + e.getMessage());
        }

        // Build the download task and run it through the shared helper, which cancels
        // the task executor on timeout so a timed-out download cannot orphan-run.
        List<URI> uris = downloadProvider.injectURLWithCandidates(file.url());
        FileDownloadTask task = new FileDownloadTask(uris, dest, file.getIntegrityCheck());
        task.setName(selected.name());

        try {
            ContentToolSupport.runTaskBlocking(task, DOWNLOAD_TIMEOUT_SECONDS, "Download");
        } catch (Exception e) {
            return ToolResult.failure("Download failed for '" + id + "' version '"
                    + selected.name() + "': " + AbstractContentSearchTool.messageOf(e));
        }

        return ToolResult.success("Installed mod '" + id + "' version '" + selected.name() + "'"
                + (selected.version() != null ? " (" + selected.version() + ")" : "")
                + " into:\n  " + dest);
    }

    @Nullable
    private static ModLoaderType parseLoader(@Nullable String loader) {
        if (loader == null) {
            return null;
        }
        return switch (loader.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
            case "fabric" -> ModLoaderType.FABRIC;
            case "forge" -> ModLoaderType.FORGE;
            case "neoforge" -> ModLoaderType.NEO_FORGE;
            case "quilt" -> ModLoaderType.QUILT;
            case "cleanroom" -> ModLoaderType.CLEANROOM;
            case "liteloader" -> ModLoaderType.LITE_LOADER;
            case "legacyfabric" -> ModLoaderType.LEGACY_FABRIC;
            default -> null;
        };
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
