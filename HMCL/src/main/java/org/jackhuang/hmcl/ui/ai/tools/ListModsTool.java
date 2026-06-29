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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Read-only tool: lists the mod files of an instance, marking enabled vs disabled.
///
/// A `.jar` is enabled; a `.jar.disabled` is disabled. Reuses HMCL's repository to
/// resolve the (isolation-aware) mods directory. The AI should use this instead of
/// `ls`/`dir` over the mods folder.
@NotNullByDefault
public final class ListModsTool implements Tool {

    @Override
    public String getName() {
        return "list_mods";
    }

    @Override
    public String getDescription() {
        return "Lists the mods of an instance, marking each enabled or disabled. "
                + "Parameter: instance (optional — defaults to the selected instance). Read-only. "
                + "Use this instead of ls/dir over the mods folder.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = String.valueOf(parameters.getOrDefault("instance", "")).trim();
        Path modsDir;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            String target = instance.isEmpty() ? Profiles.getSelectedInstance() : instance;
            if (target == null || target.isEmpty()) {
                return ToolResult.failure("No instance selected. Use list_instances, or pass instance.");
            }
            if (!instance.isEmpty() && !repo.hasVersion(instance)) {
                return ToolResult.failure("No such instance '" + instance + "'. Use list_instances.");
            }
            modsDir = repo.getRunDirectory(target).resolve("mods");
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve mods directory: " + t.getMessage());
        }

        if (!Files.isDirectory(modsDir)) {
            return ToolResult.success("No mods folder yet (" + modsDir + "). The instance has no mods installed.");
        }

        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path p : stream.sorted().toList()) {
                if (Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".jar")) enabled.add(name);
                else if (name.endsWith(".jar.disabled")) disabled.add(name.substring(0, name.length() - ".disabled".length()));
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to read mods directory: " + e.getMessage());
        }

        if (enabled.isEmpty() && disabled.isEmpty()) {
            return ToolResult.success("No mods in " + modsDir + ".");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Mods in ").append(modsDir).append(":\n");
        sb.append("Enabled (").append(enabled.size()).append("):\n");
        for (String m : enabled) sb.append("  ✓ ").append(m).append('\n');
        if (!disabled.isEmpty()) {
            sb.append("Disabled (").append(disabled.size()).append("):\n");
            for (String m : disabled) sb.append("  ✗ ").append(m).append(" (disabled)\n");
        }
        return ToolResult.success(sb.toString().trim());
    }
}
