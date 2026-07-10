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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// A controlled-write tool that removes a single resource pack (a `.zip` archive or an unpacked
/// folder) from an instance's `resourcepacks` directory.
///
/// Resolution mirrors {@link DeleteModTool}: the `resourcepacks` folder is located through
/// HMCL's launcher APIs ([`Profiles#getSelectedProfile()`] /
/// [`Profiles#getSelectedInstance()`] / [`HMCLGameRepository#getRunDirectory(String)`]),
/// then every top-level entry recognised as an installed pack by {@link ListResourcePacksTool}
/// (a folder, or a `.zip` file) is matched against the `pack` parameter by a case-insensitive
/// substring. The tool refuses to act unless exactly one entry matches (zero or several is an
/// error), so it can never delete the wrong pack by accident.
///
/// Deletion is recoverable when possible: the entry is routed to the OS recycle
/// bin / trash via {@link FileTrash} when the user's "delete to recycle bin"
/// preference (supplied to the constructor and read live on every call) is enabled
/// and the platform supports it; otherwise it is permanently deleted.
///
/// Like {@link DeleteModTool}, a failed deletion is attributed through the shared
/// [ToggleModTool#fileOperationFailure(String, String, Throwable)] helper (which consults
/// [GameResourceGuard#checkInstanceNotRunning(String)]): when the instance is being played the
/// failure says so ("the file is most likely held open by the running game; nothing was changed")
/// instead of leaking a raw I/O message. A zero-match failure lists the real pack names in the
/// folder (the {@link DeleteModTool} zero-match enumeration paradigm), all through the unified
/// {@link ToolFailures} envelope.
///
/// Permission level: {@link ToolPermission#CONTROLLED_WRITE} at this leaf-tool level — exactly
/// like {@link DeleteModTool}, the merged `instance` facade elevates the `resourcepacks_delete`
/// action to {@link ToolPermission#DANGEROUS_WRITE} in its own {@code getPermission(Map)}.
@NotNullByDefault
public final class DeleteResourcePackTool implements ToolSpec {

    /// Whether to route the deletion to the OS recycle bin (recoverable) instead of
    /// permanently deleting it; read live on each call.
    private final BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether to prefer the OS recycle bin (recoverable) over a
    ///                     permanent delete; typically `aiSettings::isDeleteToRecycleBin`.
    public DeleteResourcePackTool(BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "delete_resourcepack";
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
                   "pack": {"type": "string", "description": "The resource pack file/folder name, or a case-insensitive substring of it that matches exactly one entry in the resourcepacks folder (matches both '.zip' archives and unpacked folders)."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."}
                 },
                 "required": ["pack"]
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Removes a single resource pack (a '.zip' archive or an unpacked folder) from an instance's resourcepacks folder. "
                + "Parameters: pack (required, the pack file/folder name or a case-insensitive substring that matches exactly one entry), "
                + "instance (optional, the instance/version id; defaults to the currently selected instance). "
                + "The entry is moved to the system recycle bin when possible (recoverable), otherwise permanently deleted. "
                + "Fails if the substring matches zero or more than one entry, so it never deletes the wrong pack. "
                + "Returns the removed entry name and whether it was recycled or permanently deleted.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object packObj = parameters.get("pack");
        if (packObj == null || packObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'pack' (the resource pack file/folder name or a substring of it).");
        }
        String packQuery = packObj.toString().trim();

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        InstanceToolSupport.ResolvedInstance resolvedTarget =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (resolvedTarget.failure() != null) {
            return resolvedTarget.failure();
        }
        String instanceId = resolvedTarget.name();

        Path packsDir;
        try {
            packsDir = repository.getRunDirectory(instanceId).resolve("resourcepacks");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve resourcepacks directory: " + e.getMessage());
        }
        if (!Files.isDirectory(packsDir)) {
            return ToolResult.failure("Resourcepacks directory does not exist for instance '" + instanceId + "': " + packsDir);
        }

        // Collect candidate entries — a folder (unpacked pack) or a '.zip' archive, exactly what
        // ListResourcePacksTool reports as an installed pack — and match by case-insensitive
        // substring, exactly like DeleteModTool.
        List<Path> candidates = new ArrayList<>();
        List<Path> installedPacks = new ArrayList<>();
        String queryLower = packQuery.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                boolean isPackKind = Files.isDirectory(entry)
                        || (Files.isRegularFile(entry) && name.toLowerCase(Locale.ROOT).endsWith(".zip"));
                if (!isPackKind) {
                    continue;
                }
                installedPacks.add(entry);
                if (name.toLowerCase(Locale.ROOT).contains(queryLower)) {
                    candidates.add(entry);
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to list resourcepacks directory: " + e.getMessage());
        }

        if (candidates.isEmpty()) {
            // Carry the real installed pack names (the DeleteModTool zero-match enumeration
            // paradigm) so a typo is obvious without a separate packs_list_local round-trip.
            return ToolFailures.failure(
                    "No resource pack matching '" + packQuery + "' was found in " + packsDir,
                    ToolFailures.Retryable.YES,
                    "no installed resource pack name contains this substring, which is usually a typo",
                    "installed packs: " + describeInstalledPacks(installedPacks)
                            + "; use instance(action=\"packs_list_local\") for the full list, or refine the query");
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(packQuery).append("' matches ").append(candidates.size()).append(" entries:\n");
            for (Path c : candidates) {
                sb.append("  - ").append(c.getFileName()).append('\n');
            }
            sb.append("Please refine the 'pack' parameter to match exactly one entry.");
            return ToolResult.failure(sb.toString().trim());
        }

        Path target = candidates.get(0);
        String entryName = target.getFileName().toString();
        boolean wasFolder = Files.isDirectory(target);

        boolean recycled;
        try {
            recycled = FileTrash.delete(target, toRecycleBin.getAsBoolean());
        } catch (Throwable e) {
            // Attribute a failed deletion to a running game (file held open) through the shared
            // DeleteModTool/ToggleModTool helper — the same GameResourceGuard-backed envelope.
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Deleting resource pack '" + entryName + "' from instance '" + instanceId + "' failed", e);
        }

        return ToolResult.success((recycled
                ? "Moved resource pack '" + entryName + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted resource pack '" + entryName + "' from disk.\n")
                + "  instance: " + instanceId + "\n"
                + "  kind    : " + (wasFolder ? "folder" : "zip archive") + "\n"
                + "  path    : " + target);
    }

    /// The maximum number of real pack names carried in a zero-match failure — enough for the
    /// model to spot a typo, bounded so a huge resourcepacks folder can't flood the context.
    /// Mirrors {@link ToggleModTool}'s zero-match file listing.
    private static final int MAX_LISTED_PACKS = 10;

    /// Lists up to [#MAX_LISTED_PACKS] real pack names (folders and `.zip` archives — exactly the
    /// deletable entries this tool considers) for a zero-match failure, appending a "(N more)" tail
    /// when truncated; an empty folder is reported explicitly. Mirrors {@link ToggleModTool}'s
    /// `describeInstalledFiles`.
    private static String describeInstalledPacks(List<Path> packs) {
        if (packs.isEmpty()) {
            return "(none — the resourcepacks folder is empty)";
        }
        int shown = Math.min(packs.size(), MAX_LISTED_PACKS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(packs.get(i).getFileName());
        }
        if (packs.size() > shown) {
            sb.append(", ... (").append(packs.size() - shown).append(" more)");
        }
        return sb.toString();
    }
}
