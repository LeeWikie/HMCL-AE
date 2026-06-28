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
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.io.CompressingUtils;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// AI tool that installs a modpack as a NEW game instance.
///
/// This is not a file copy: it reuses HMCL's modpack install pipeline. The modpack
/// archive is downloaded via a [FileDownloadTask], its manifest is read with
/// {@link ModpackHelper#readModpackManifest}, and the actual instance is created by the
/// task returned from {@link ModpackHelper#getInstallTask} (CurseForge, Modrinth,
/// MultiMC, Mcbbs, server and HMCL formats are all handled there).
public final class InstallModpackTool implements Tool {

    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;
    private static final int INSTALL_TIMEOUT_SECONDS = 600;

    @Override
    public String getName() {
        return "install_modpack";
    }

    @Override
    public String getDescription() {
        return "Install a modpack as a NEW game instance (this creates a new instance, it is not a file copy). "
                + "Parameters: id (the modpack id/slug from search_modpacks, required), "
                + "name (optional, the new instance name; defaults to the modpack name), "
                + "game_version (optional, picks the newest pack file for that version), "
                + "version_id (optional, exact version name/number), "
                + "source (optional, \"modrinth\" (default) or \"curseforge\"). "
                + "Returns the created instance name. May take several minutes for large packs.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String id = ContentToolSupport.primaryParam(parameters, "id");
        if (id == null) {
            return ToolResult.failure("Missing required parameter: id (the modpack id/slug from search_modpacks).");
        }

        ContentToolSupport.Source source = parameters.containsKey("source")
                ? ContentToolSupport.parseSource(ContentToolSupport.optional(parameters, "source"))
                : ContentToolSupport.Source.MODRINTH;

        RemoteAddonRepository repository = ContentToolSupport.repositoryFor(source, RemoteAddonRepository.Type.MODPACK);
        if (repository == null) {
            return ToolResult.failure("Source '" + source + "' does not provide modpacks. Try a different source.");
        }
        if (source == ContentToolSupport.Source.CURSEFORGE && !ContentToolSupport.isCurseForgeAvailable()) {
            return ToolResult.failure("CurseForge is not configured (no API key in this build). Use the Modrinth source instead.");
        }

        String gameVersion = ContentToolSupport.optional(parameters, "game_version");
        String versionId = ContentToolSupport.optional(parameters, "version_id");
        String requestedName = ContentToolSupport.optional(parameters, "name");

        Path modpackFile = null;
        try {
            RemoteAddon.Version version = ContentToolSupport.resolveVersion(repository, id, gameVersion, versionId);
            RemoteAddon.File file = version.file();

            DownloadProvider provider = ContentToolSupport.downloadProvider();
            List<URI> urls = provider.injectURLWithCandidates(file.url());

            modpackFile = Files.createTempFile("hmcl-ai-modpack", ".zip");
            FileDownloadTask downloadTask = new FileDownloadTask(urls, modpackFile);
            downloadTask.setName(version.name());
            ContentToolSupport.runTaskBlocking(downloadTask, DOWNLOAD_TIMEOUT_SECONDS, "Modpack download");

            Charset charset = CompressingUtils.findSuitableEncoding(modpackFile);
            Modpack modpack = ModpackHelper.readModpackManifest(modpackFile, charset);

            Profile profile = Profiles.getSelectedProfile();
            String desiredName = requestedName != null ? requestedName : modpack.getName();
            String name = ContentToolSupport.uniqueInstanceName(profile, desiredName == null ? "" : desiredName);

            Task<?> installTask = ModpackHelper.getInstallTask(profile, modpackFile, name, modpack, "")
                    .thenRunAsync(Schedulers.javafx(), () -> Profiles.setSelectedInstance(profile, name));

            ContentToolSupport.runTaskBlocking(installTask, INSTALL_TIMEOUT_SECONDS, "Modpack install");

            return ToolResult.success("Installed modpack \"" + modpack.getName() + "\" as a new instance: " + name);
        } catch (Exception e) {
            return ToolResult.failure("Modpack install failed: " + AbstractContentSearchTool.messageOf(e));
        } finally {
            if (modpackFile != null) {
                try {
                    Files.deleteIfExists(modpackFile);
                } catch (Exception ignored) {
                    // best-effort cleanup of the temporary download
                }
            }
        }
    }
}
