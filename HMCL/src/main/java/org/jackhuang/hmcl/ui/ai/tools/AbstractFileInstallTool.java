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
import org.jackhuang.hmcl.task.FileDownloadTask;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Base class for the single-file content-install AI tools (resource packs, shaders).
///
/// Reuses HMCL's download pipeline exactly like {@code ui.download.DownloadPage.download}:
/// resolve the addon and its best version, inject download candidates via the configured
/// [DownloadProvider], then run a [FileDownloadTask] into {@code <instance>/<subdirectory>}.
abstract class AbstractFileInstallTool implements Tool {

    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    private final RemoteAddonRepository.Type type;
    private final ContentToolSupport.Source defaultSource;
    private final String subdirectory;

    AbstractFileInstallTool(RemoteAddonRepository.Type type, ContentToolSupport.Source defaultSource, String subdirectory) {
        this.type = type;
        this.defaultSource = defaultSource;
        this.subdirectory = subdirectory;
    }

    @Override
    public final ToolResult execute(Map<String, Object> parameters) {
        String id = ContentToolSupport.primaryParam(parameters, "id");
        if (id == null) {
            return ToolResult.failure("Missing required parameter: id (the addon id/slug from the search tool).");
        }

        ContentToolSupport.Source source = parameters.containsKey("source")
                ? ContentToolSupport.parseSource(ContentToolSupport.optional(parameters, "source"))
                : defaultSource;

        RemoteAddonRepository repository = ContentToolSupport.repositoryFor(source, type);
        if (repository == null) {
            return ToolResult.failure("Source '" + source + "' does not provide this content type. Try a different source.");
        }
        if (source == ContentToolSupport.Source.CURSEFORGE && !ContentToolSupport.isCurseForgeAvailable()) {
            return ToolResult.failure("CurseForge is not configured (no API key in this build). Use the Modrinth source instead.");
        }

        String gameVersion = ContentToolSupport.optional(parameters, "game_version");
        String versionId = ContentToolSupport.optional(parameters, "version_id");
        String instance = ContentToolSupport.optional(parameters, "instance");

        try {
            RemoteAddon.Version version = ContentToolSupport.resolveVersion(repository, id, gameVersion, versionId);
            RemoteAddon.File file = version.file();

            DownloadProvider provider = ContentToolSupport.downloadProvider();
            List<URI> urls = provider.injectURLWithCandidates(file.url());

            Path directory = ContentToolSupport.resolveInstanceSubdirectory(subdirectory, instance);
            Path destination = directory.resolve(file.filename());

            FileDownloadTask task = new FileDownloadTask(urls, destination, file.getIntegrityCheck());
            task.setName(version.name());

            ContentToolSupport.runTaskBlocking(task, DOWNLOAD_TIMEOUT_SECONDS, "Download");

            return ToolResult.success("Installed \"" + file.filename() + "\" into " + subdirectory + ":\n" + destination);
        } catch (Exception e) {
            return ToolResult.failure("Install failed: " + AbstractContentSearchTool.messageOf(e));
        }
    }
}
