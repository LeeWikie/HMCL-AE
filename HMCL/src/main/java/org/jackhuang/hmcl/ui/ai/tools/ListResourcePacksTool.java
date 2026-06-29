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
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// A read-only tool that lists the resource packs and shader packs an instance has
/// installed locally (the contents of its `resourcepacks/` and `shaderpacks/`
/// folders). Each entry is either a `.zip` archive or an unpacked folder.
///
/// This complements the existing `search_resourcepack` / `search_shader` (online
/// discovery) and `install_resourcepack` / `install_shader` (download) tools:
/// there was previously no way for the AI to see what packs are already present.
///
/// Instance paths are resolved via HMCL's
/// [`HMCLGameRepository#getRunDirectory(String)`] (isolation-aware).
///
/// Permission level: READ_ONLY. It never modifies any pack.
@NotNullByDefault
public final class ListResourcePacksTool implements Tool {

    @Override
    public String getName() {
        return "list_resourcepacks";
    }

    @Override
    public String getDescription() {
        return "Lists the resource packs and shader packs installed locally in an instance (the contents of its "
                + "'resourcepacks/' and 'shaderpacks/' folders; each is a .zip or an unpacked folder). "
                + "Parameter: instance (optional; defaults to the currently selected instance). Read-only. "
                + "Use this instead of ls/dir; to find/download new packs use search_resourcepack / search_shader.";
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

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        Path runDir;
        try {
            runDir = repository.getRunDirectory(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Local packs of instance '").append(instance).append("':\n");

        try {
            appendSection(sb, "Resource packs", runDir.resolve("resourcepacks"));
            appendSection(sb, "Shader packs", runDir.resolve("shaderpacks"));
        } catch (IOException e) {
            return ToolResult.failure("Failed to read packs: " + e.getMessage());
        }

        return ToolResult.success(sb.toString().trim());
    }

    private static void appendSection(StringBuilder sb, String title, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            sb.append('\n').append(title).append(": (no '").append(dir.getFileName()).append("' folder)\n");
            return;
        }

        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.sorted().toList()) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    entries.add(name + "  (folder)");
                } else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    entries.add(name);
                }
            }
        }

        sb.append('\n').append(title).append(" (").append(entries.size()).append("):\n");
        if (entries.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String e : entries) {
                sb.append("  - ").append(e).append('\n');
            }
        }
    }
}
