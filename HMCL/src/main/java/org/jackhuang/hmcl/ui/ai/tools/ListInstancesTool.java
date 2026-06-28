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
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// A read-only tool that lists the installed Minecraft instances (versions) of
/// the currently selected HMCL profile.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] for the active profile,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getDisplayVersions()`]
///   for the user-visible instance list,
/// - [`HMCLGameRepository#getRunDirectory(String)`] /
///   [`HMCLGameRepository#getModsDirectory(String)`] for per-instance paths.
///
/// Permission level: READ_ONLY. It never modifies any launcher state.
@NotNullByDefault
public final class ListInstancesTool implements Tool {

    @Override
    public String getName() {
        return "list_instances";
    }

    @Override
    public String getDescription() {
        return "Lists the installed Minecraft instances (versions) of the currently selected HMCL profile. "
                + "Takes no parameters. Returns each instance's id, release type, run directory and mods directory, "
                + "and marks the currently selected instance. Read-only.";
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
        // The repository is lazily loaded; make sure the version list is populated.
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        String selectedInstance = Profiles.getSelectedInstance();

        List<Version> versions = repository.getDisplayVersions().toList();
        if (versions.isEmpty()) {
            return ToolResult.success("No Minecraft instances are installed in the selected profile.\n"
                    + "Game directory: " + repository.getBaseDirectory());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(Profiles.getProfileDisplayName(profile)).append('\n');
        sb.append("Game directory: ").append(repository.getBaseDirectory()).append('\n');
        sb.append("Installed instances (").append(versions.size()).append("):\n");

        for (Version version : versions) {
            String id = version.getId();
            boolean selected = id.equals(selectedInstance);
            sb.append(selected ? "  * " : "  - ").append(id);
            if (version.getType() != null) {
                sb.append(" [").append(version.getType().name().toLowerCase()).append(']');
            }
            if (selected) {
                sb.append(" (selected)");
            }
            sb.append('\n');

            Path runDirectory = repository.getRunDirectory(id);
            sb.append("      runDir : ").append(runDirectory).append('\n');
            sb.append("      modsDir: ").append(repository.getModsDirectory(id)).append('\n');
        }

        return ToolResult.success(sb.toString().trim());
    }
}
