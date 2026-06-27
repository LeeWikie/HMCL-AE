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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// A tool that enables or disables Minecraft mods in a mods directory.
///
/// Accepts `action` ("enable", "disable", or "list") and `modsDir` (path to the mods folder).
/// The tool manipulates file extensions: `.jar.disabled` → `.jar` (enable) and
/// `.jar` → `.jar.disabled` (disable). Also supports individual mod targeting via
/// the optional `modName` parameter (file name without path).
///
/// All modifications are non-destructive; original files are preserved.
/// This tool does NOT perform its own backups — pair with {@link FileBackupTool} for
/// rollback capability before bulk operations.
@NotNullByDefault
public final class ModToggleTool implements Tool {

    /// Extension suffix used for disabled mods.
    private static final String DISABLED_SUFFIX = ".disabled";

    @Override
    public String getName() {
        return "mod_toggle";
    }

    @Override
    public String getDescription() {
        return "Enable, disable, or list Minecraft mods in a mods directory. "
                + "Parameters: action (\"enable\", \"disable\", or \"list\"), "
                + "modsDir (absolute path to mods folder), "
                + "modName (optional, specific mod file name to target). "
                + "When disabling, renames .jar to .jar.disabled. "
                + "When enabling, renames .jar.disabled back to .jar. "
                + "Returns the count and list of affected mods.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = toString(parameters.get("action"));
        String modsDirStr = toString(parameters.get("modsDir"));
        String modName = toString(parameters.get("modName"));

        if (action == null || modsDirStr == null) {
            return ToolResult.failure("Missing required parameter: action and modsDir are required");
        }

        Path modsDir = Path.of(modsDirStr).toAbsolutePath().normalize();

        if (!Files.isDirectory(modsDir)) {
            return ToolResult.failure("Mods directory does not exist or is not a directory: " + modsDir);
        }

        return switch (action) {
            case "disable" -> disableMods(modsDir, modName);
            case "enable" -> enableMods(modsDir, modName);
            case "list" -> listMods(modsDir);
            default -> ToolResult.failure("Unknown action '" + action + "'. Use 'enable', 'disable', or 'list'.");
        };
    }

    /// Disables mods by renaming `.jar` → `.jar.disabled`.
    private static ToolResult disableMods(Path modsDir, @Nullable String targetMod) {
        return processMods(modsDir, targetMod, ".jar", ".jar" + DISABLED_SUFFIX, "disabled");
    }

    /// Enables mods by renaming `.jar.disabled` → `.jar`.
    private static ToolResult enableMods(Path modsDir, @Nullable String targetMod) {
        return processMods(modsDir, targetMod, ".jar" + DISABLED_SUFFIX, ".jar", "enabled");
    }

    /// Lists all mods with their current state.
    private static ToolResult listMods(Path modsDir) {
        try {
            List<String> enabled = new ArrayList<>();
            List<String> disabled = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir,
                    p -> p.getFileName().toString().endsWith(".jar")
                            || p.getFileName().toString().endsWith(".jar" + DISABLED_SUFFIX))) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(DISABLED_SUFFIX)) {
                        disabled.add(name);
                    } else {
                        enabled.add(name);
                    }
                }
            }

            StringBuilder sb = new StringBuilder("Mods in ").append(modsDir).append(":\n");
            sb.append("Enabled (").append(enabled.size()).append("):\n");
            for (String mod : enabled) sb.append("  + ").append(mod).append("\n");
            sb.append("Disabled (").append(disabled.size()).append("):\n");
            for (String mod : disabled) sb.append("  - ").append(mod).append("\n");

            return ToolResult.success(sb.toString());
        } catch (IOException e) {
            return ToolResult.failure("Failed to list mods: " + e.getMessage());
        }
    }

    /// Processes mods by renaming files matching a source extension.
    private static ToolResult processMods(Path modsDir, @Nullable String targetMod,
                                          String fromSuffix, String toSuffix, String actionName) {
        try {
            List<String> affected = new ArrayList<>();
            int skipped = 0;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir,
                    p -> p.getFileName().toString().endsWith(fromSuffix))) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    if (targetMod != null && !name.equals(targetMod + fromSuffix)
                            && !name.equals(targetMod)) {
                        continue; // not the targeted mod
                    }

                    String newName;
                    if (name.endsWith(fromSuffix)) {
                        newName = name.substring(0, name.length() - fromSuffix.length()) + toSuffix;
                    } else {
                        skipped++;
                        continue;
                    }

                    Path target = file.resolveSibling(newName);
                    Files.move(file, target);
                    affected.add(newName);
                }
            }

            if (affected.isEmpty() && targetMod != null) {
                return ToolResult.failure("No mod matching '" + targetMod + "' found with extension " + fromSuffix);
            }

            return ToolResult.success(actionName.substring(0, 1).toUpperCase()
                    + actionName.substring(1) + " " + affected.size() + " mod(s)"
                    + (skipped > 0 ? " (" + skipped + " skipped)" : "")
                    + ":\n" + String.join("\n", affected.stream().map(n -> "  " + n).toList()));
        } catch (IOException e) {
            return ToolResult.failure("Failed to " + actionName + " mods: " + e.getMessage());
        }
    }

    /// Converts an object to its string representation, or `null` if it is `null`.
    @org.jetbrains.annotations.Nullable
    private static String toString(@org.jetbrains.annotations.Nullable Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
