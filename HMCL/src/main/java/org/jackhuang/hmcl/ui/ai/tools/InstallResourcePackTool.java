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

import org.jackhuang.hmcl.addon.RemoteAddonRepository;

/// AI tool that downloads a resource pack into {@code <instance>/resourcepacks}.
public final class InstallResourcePackTool extends AbstractFileInstallTool {

    public InstallResourcePackTool() {
        super(RemoteAddonRepository.Type.RESOURCE_PACK, ContentToolSupport.Source.MODRINTH, "resourcepacks");
    }

    @Override
    public String getName() {
        return "install_resourcepack";
    }

    @Override
    public String getDescription() {
        return "Download a resource pack into the selected instance's resourcepacks folder. "
                + "Parameters: id (the addon id/slug from search_resourcepacks, required), "
                + "game_version (optional, picks the newest file for that version), "
                + "version_id (optional, exact version name/number), "
                + "source (optional, \"modrinth\" (default) or \"curseforge\"), "
                + "instance (optional, target instance id; defaults to the selected instance). "
                + "Returns the installed file path.";
    }
}
