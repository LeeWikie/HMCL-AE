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

/// AI tool that searches for Minecraft shader packs on the remote repositories.
public final class SearchShadersTool extends AbstractContentSearchTool {

    public SearchShadersTool() {
        super(RemoteAddonRepository.Type.SHADER_PACK, ContentToolSupport.Source.MODRINTH);
    }

    @Override
    public String getName() {
        return "search_shaders";
    }

    @Override
    public String getDescription() {
        return "Search for Minecraft shader packs. "
                + "Parameters: query (search keywords, required), "
                + "game_version (optional, e.g. \"1.20.1\"), "
                + "source (optional, \"modrinth\" (default) or \"curseforge\"). "
                + "Returns a numbered list of matches; each item has an \"id\" to pass to install_shader.";
    }
}
