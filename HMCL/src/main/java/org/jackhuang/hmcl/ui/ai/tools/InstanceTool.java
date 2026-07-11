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

import org.jackhuang.hmcl.addon.resourcepack.ResourcePackFile;
import org.jackhuang.hmcl.addon.resourcepack.ResourcePackManager;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/// Domain tool for LOCAL instance configuration and content management — one registered tool
/// name (`instance`) dispatching to ~34 actions via an `action` parameter, delegating each to the
/// existing single-purpose tool it replaces (zero business-logic duplication; every leaf tool's
/// validation/wording is reused verbatim).
///
/// Deliberately does NOT include anything that queries an external content source (search_mods
/// and friends live in the separate `search` domain tool) or runtime process state (`list`
/// overlaps intentionally with the `game` domain tool's own `list`, but `launch`/`stop` do not
/// live here — see the `game` domain tool). "instance only handles local operations."
///
/// Migration note: the older standalone `backup_world` tool (superseded by the
/// `worlds_backup_create`/`worlds_backup_list`/`worlds_backup_restore` versioned-snapshot engine)
/// is intentionally NOT carried over — the system prompt already told the model to prefer the
/// versioned engine, so this consolidation just retires the redundant path instead of giving it a
/// new action name.
@NotNullByDefault
public final class InstanceTool implements ToolSpec {

    // ---- Leaf tools this facade delegates to (constructed once, reused across calls) ----
    private final ListInstancesTool list = new ListInstancesTool();
    private final InstanceDetailsTool details = new InstanceDetailsTool();
    private final EditInstanceTool rename = new EditInstanceTool();
    private final DeleteInstanceTool delete;
    private final InstallLoaderTool create = new InstallLoaderTool();
    private final SetInstanceMemoryTool setMemory = new SetInstanceMemoryTool();
    private final SetInstanceJvmArgsTool setJvmArgs = new SetInstanceJvmArgsTool();
    private final SetInstanceIsolationTool setIsolation = new SetInstanceIsolationTool();
    private final ReadGameOptionsTool getOptions = new ReadGameOptionsTool();
    private final SetGameOptionTool setOption = new SetGameOptionTool();
    private final ListJavaTool listJava = new ListJavaTool();
    private final DownloadJavaTool downloadJava = new DownloadJavaTool();
    private final OpenGameFolderTool openFolder = new OpenGameFolderTool();
    private final CleanLogsTool cleanLogs = new CleanLogsTool();

    private final ListModsTool modsList = new ListModsTool();
    /// Needs the current instance's run directory, which isn't known at construction time and
    /// changes as the user switches instances — see {@link #refreshRunDir(Path)}, called from
    /// {@code AIMainPage.refreshGameContext()} exactly like the old per-instance re-`register()`.
    private volatile InstallModTool modsInstall;
    private final ToggleModTool modsToggle = new ToggleModTool();
    private final GetModInfoTool modsInfo = new GetModInfoTool();
    private final CheckModUpdatesTool modsCheckUpdates = new CheckModUpdatesTool();
    private final UpdateModTool modsUpdate;
    private final DeleteModTool modsDelete;

    private final ListWorldsTool worldsList = new ListWorldsTool();
    /// Reads world NBT internally, so — like the original registration — it's only usable when
    /// NBT tools are enabled; see {@link #nbtToolsEnabled}.
    private final ReadWorldInfoTool worldsInfo = new ReadWorldInfoTool();
    private final ImportWorldTool worldsImport = new ImportWorldTool();
    private final DeleteWorldTool worldsDelete;
    private final CreateWorldBackupTool worldsBackupCreate;
    private final ListWorldBackupsTool worldsBackupList = new ListWorldBackupsTool();
    private final RestoreWorldBackupTool worldsBackupRestore;
    private final ListDatapacksTool worldsDatapacksList = new ListDatapacksTool();
    private final InstallDatapackTool worldsDatapacksInstall = new InstallDatapackTool();

    private final ListResourcePacksTool packsListLocal = new ListResourcePacksTool();
    private final InstallResourcePackTool resourcepacksInstall = new InstallResourcePackTool();
    private final DeleteResourcePackTool resourcepacksDelete;
    private final InstallShaderTool shadersInstall = new InstallShaderTool();
    private final ToggleShaderTool shadersToggle = new ToggleShaderTool();
    private final DeleteShaderTool shadersDelete;
    private final InstallModpackTool modpacksInstall = new InstallModpackTool();
    private final ExportModpackTool modpacksExport = new ExportModpackTool();

    private final BooleanSupplier nbtToolsEnabled;

    /// @param toRecycleBin        whether destructive file operations should prefer the system
    ///                            recycle bin (delete / mods_delete / mods_update /
    ///                            worlds_delete / resourcepacks_delete / shaders_delete) — see
    ///                            {@code AiSettings#isDeleteToRecycleBin()}
    /// @param worldBackupMaxMb    per-world cap on the total snapshot size in MB
    ///                            (worlds_backup_create / worlds_backup_restore's post-restore prune)
    /// @param nbtToolsEnabled     gates the {@code worlds_info} action exactly as the standalone
    ///                            {@code ReadWorldInfoTool} registration used to (it reads NBT
    ///                            internally); the REST of this domain tool is unaffected by this
    ///                            setting — only this one action is gated per-call rather than the
    ///                            whole tool, since it's the only action here that touches NBT
    public InstanceTool(BooleanSupplier toRecycleBin, IntSupplier worldBackupMaxMb,
                        BooleanSupplier nbtToolsEnabled) {
        this.delete = new DeleteInstanceTool(toRecycleBin);
        this.modsUpdate = new UpdateModTool(toRecycleBin);
        this.modsDelete = new DeleteModTool(toRecycleBin);
        this.worldsDelete = new DeleteWorldTool(toRecycleBin);
        this.worldsBackupCreate = new CreateWorldBackupTool(worldBackupMaxMb);
        this.worldsBackupRestore = new RestoreWorldBackupTool(worldBackupMaxMb);
        this.resourcepacksDelete = new DeleteResourcePackTool(toRecycleBin);
        this.shadersDelete = new DeleteShaderTool(toRecycleBin);
        this.nbtToolsEnabled = nbtToolsEnabled;
    }

    /// Re-targets the mod-install leaf tool at the newly-selected instance's run directory.
    /// Called from {@code AIMainPage.refreshGameContext()} on every instance switch — mirrors
    /// what used to be a full re-`register()` of a standalone `InstallModTool`.
    public void refreshRunDir(Path runDir) {
        this.modsInstall = new InstallModTool(runDir);
    }

    @Override
    public String getName() {
        return "instance";
    }

    @Override
    public String getDescription() {
        return "Local Minecraft instance configuration and content management for the selected profile. "
                + "Does NOT search external content sources (use the 'search' tool for that) and does NOT "
                + "launch/stop the game (use the 'game' tool for that). Parameter 'action' selects the "
                + "operation (required); most actions accept an optional 'instance' id defaulting to the "
                + "currently selected instance. Actions:\n"
                + "- Lifecycle: list, details, rename (newName), delete (DANGEROUS, confirm-gated), "
                + "create (gameVersion, loader, loaderVersion, name — installs a loader/creates an instance).\n"
                + "- Config: set_memory (maxMemoryMB), set_jvm_args (jvmArgs — custom JVM/GC flags; NEVER "
                + "hand-edit instance-game-settings.json for this, always use this action), "
                + "set_isolation (enable: true=own version folder, "
                + "false=follow global default), get_options, set_option (key, value), "
                + "clean_logs (keep), open_folder.\n"
                + "- Java: list_java, download_java (gameVersion or javaVersion).\n"
                + "- Mods (already-known/installed content only — search via the 'search' tool first): "
                + "mods_list, mods_install (id, source, loader, gameVersion, version — 'search' does NOT "
                + "verify per-result which loader/version a mod actually supports (its loader field is a "
                + "hint, not a filter), so PASS the loader/gameVersion the USER actually wants, not just "
                + "whatever you queried search with; omitting them silently falls back to that mod's single "
                + "most-recently-published file across EVERY loader and MC version, which is how a Fabric "
                + "build ends up on a Forge/NeoForge instance or the wrong game version gets installed — "
                + "pass version too when you need one exact build locked. If mods_install then reports 'no "
                + "version supports loader X', that mod likely ships no build for X at all — the error lists "
                + "the loaders/versions it DOES support, so pick one of those or search for an alternative "
                + "mod rather than retrying the same call), "
                + "mods_toggle (mod, enable), mods_info (mod), "
                + "mods_check_updates, mods_update (mod), mods_delete (mod, DANGEROUS).\n"
                + "- Worlds: worlds_list, worlds_info (world; NBT tools must be enabled), "
                + "worlds_import (zip), worlds_delete (world, DANGEROUS), "
                + "worlds_backup_create (world), worlds_backup_list (world), "
                + "worlds_backup_restore (world, backupId, DANGEROUS — auto-backs-up current world first), "
                + "worlds_datapacks_list (world), worlds_datapacks_install (world, source).\n"
                + "- Content already installed locally: packs_list_local (resource packs), "
                + "resourcepacks_install (id), resourcepacks_toggle (pack, enable — flips options.txt "
                + "enablement, no file renaming), resourcepacks_delete (pack, DANGEROUS), "
                + "shaders_install (id), shaders_toggle (shader, enable — renames the shaderpacks entry "
                + "with a '.disabled' suffix, mirroring mods_toggle since shader packs have no options.txt "
                + "enablement state), shaders_delete (shader, DANGEROUS), "
                + "modpacks_install (id), modpacks_export.";
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        // Flat, loosely-typed union of every action's parameters (matches the existing
        // InstallLoaderTool-style schema convention used across this codebase — no oneOf
        // branching, since LangChain4jToolAdapter's schema parser only understands a flat
        // properties map, and every leaf tool already tolerates extra/absent keys gracefully).
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "action": {"type": "string", "description": "Which instance operation to perform (see tool description for the full list)."},
                   "instance": {"type": "string", "description": "Target instance id; most actions default to the currently selected instance."},
                   "newName": {"type": "string", "description": "rename: the new instance name."},
                   "confirm": {"type": "boolean", "description": "delete/mods_delete/worlds_delete/worlds_backup_restore: confirmation flag some leaf tools require."},
                   "gameVersion": {"type": "string", "description": "create/download_java: Minecraft version, or 'latest'. mods_install: the Minecraft version you actually want to target when auto-picking a version — strongly recommended; 'search' does NOT verify this per result, so pass the version the user wants, not just whatever you queried search with. Omitting it risks installing a build for the wrong game version."},
                   "loader": {"type": "string", "description": "create: vanilla/fabric/forge/neoforge/quilt/optifine (loader to install). mods_install: fabric/forge/neoforge/quilt — the loader you actually want to target; strongly recommended. 'search' does NOT verify per result which loader a mod supports (loader there is only a query hint, not a filter), so pass the loader the user wants; omitting it risks installing a jar built for the wrong loader."},
                   "loaderVersion": {"type": "string", "description": "create: specific loader version; default = newest compatible."},
                   "version": {"type": "string", "description": "mods_install: an exact version name/number to lock onto (e.g. from the matching 'search' result); when set, this exact version is installed instead of auto-picking the newest match."},
                   "name": {"type": "string", "description": "create: optional new instance name."},
                   "maxMemoryMB": {"type": "integer", "description": "set_memory: -Xmx in MiB; omit to only report the current value."},
                   "jvmArgs": {"type": "string", "description": "set_jvm_args: custom JVM/GC arguments string (e.g. '-XX:+UseG1GC'); omit to only report the current value, pass an empty string to clear it. Do not put -Xmx/-Xms here, use maxMemoryMB via set_memory instead."},
                   "enable": {"type": "boolean", "description": "set_isolation: true to isolate this instance, false to follow the global default; omit to only report the current state. mods_toggle/resourcepacks_toggle/shaders_toggle: true forces enable, false forces disable; omit to toggle the current state."},
                   "key": {"type": "string", "description": "get_options/set_option: options.txt key."},
                   "value": {"type": "string", "description": "set_option: the new value."},
                   "keep": {"type": "integer", "description": "clean_logs: how many recent log files to keep."},
                   "javaVersion": {"type": "integer", "description": "download_java: an integer Java major version (8/16/17/21/25), alternative to gameVersion."},
                   "id": {"type": "string", "description": "mods_install/resourcepacks_install/shaders_install/modpacks_install: the project id/slug from the matching search_* action of the 'search' tool."},
                   "source": {"type": "string", "description": "mods_install/worlds_datapacks_install: content source (e.g. modrinth/curseforge), or a local file path."},
                   "mod": {"type": "string", "description": "mods_toggle/mods_info/mods_update/mods_delete: the mod file name or a distinguishing substring."},
                   "world": {"type": "string", "description": "worlds_*: the save folder name under 'saves/'."},
                   "zip": {"type": "string", "description": "worlds_import: absolute local path of the .zip world archive."},
                   "backupId": {"type": "string", "description": "worlds_backup_restore: the snapshot id from worlds_backup_list."},
                   "pack": {"type": "string", "description": "resourcepacks_toggle/resourcepacks_delete: the resource pack file/folder name or a distinguishing substring (matches both '.zip' archives and unpacked folders)."},
                   "shader": {"type": "string", "description": "shaders_toggle/shaders_delete: the shader pack file/folder name or a distinguishing substring (matches both '.zip' archives and unpacked folders)."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            // mods_update performs the exact same destructive delete of the old mod jar as
            // mods_delete (both go through FileTrash.delete()) — it must carry the same
            // DANGEROUS_WRITE gate, not silently fall through to the CONTROLLED_WRITE default
            // (which runs with zero confirmation by default).
            // resourcepacks_delete/shaders_delete follow the exact same FileTrash.delete()-based
            // destructive-delete pattern as mods_delete, so they get the same gate.
            case "delete", "mods_delete", "mods_update", "worlds_delete", "worlds_backup_restore",
                    "resourcepacks_delete", "shaders_delete" -> ToolPermission.DANGEROUS_WRITE;
            case "list", "details", "get_options", "list_java",
                    "mods_list", "mods_info", "mods_check_updates",
                    "worlds_list", "worlds_info", "worlds_backup_list", "worlds_datapacks_list",
                    "packs_list_local" -> ToolPermission.READ_ONLY;
            // open_folder creates the directory on disk (Files.createDirectories) and launches an
            // external file-manager process — side-effecting, not safely repeatable/parallelizable.
            default -> ToolPermission.CONTROLLED_WRITE;
        };
    }

    /// This tool's worst case is DANGEROUS_WRITE (delete/mods_delete/mods_update/worlds_delete/
    /// worlds_backup_restore/resourcepacks_delete/shaders_delete) — reported to the
    /// settings/catalog UI instead of the conservative CONTROLLED_WRITE default the no-arg
    /// {@link #getPermission()} falls back to.
    @Override
    public ToolPermission getMaxPermission() {
        return ToolPermission.DANGEROUS_WRITE;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "list" -> list.execute(parameters);
            case "details" -> details.execute(parameters);
            case "rename" -> rename.execute(parameters);
            case "delete" -> delete.execute(parameters);
            case "create" -> create.execute(parameters);
            case "set_memory" -> setMemory.execute(parameters);
            case "set_jvm_args" -> setJvmArgs.execute(parameters);
            case "set_isolation" -> setIsolation.execute(parameters);
            case "get_options" -> getOptions.execute(parameters);
            case "set_option" -> setOption.execute(parameters);
            case "list_java" -> listJava.execute(parameters);
            case "download_java" -> downloadJava.execute(parameters);
            case "open_folder" -> openFolder.execute(parameters);
            case "clean_logs" -> cleanLogs.execute(parameters);

            case "mods_list" -> modsList.execute(parameters);
            case "mods_install" -> {
                InstallModTool tool = modsInstall;
                yield tool != null ? tool.execute(parameters)
                        : ToolResult.failure("No instance run directory resolved yet — select an instance first.");
            }
            case "mods_toggle" -> modsToggle.execute(parameters);
            case "mods_info" -> modsInfo.execute(parameters);
            case "mods_check_updates" -> modsCheckUpdates.execute(parameters);
            case "mods_update" -> modsUpdate.execute(parameters);
            case "mods_delete" -> modsDelete.execute(parameters);

            case "worlds_list" -> worldsList.execute(parameters);
            case "worlds_info" -> nbtToolsEnabled.getAsBoolean() ? worldsInfo.execute(parameters)
                    : ToolResult.failure("worlds_info requires NBT tools to be enabled in AI 设置 > 高级与开发者.");
            case "worlds_import" -> worldsImport.execute(parameters);
            case "worlds_delete" -> worldsDelete.execute(parameters);
            case "worlds_backup_create" -> worldsBackupCreate.execute(parameters);
            case "worlds_backup_list" -> worldsBackupList.execute(parameters);
            case "worlds_backup_restore" -> worldsBackupRestore.execute(parameters);
            case "worlds_datapacks_list" -> worldsDatapacksList.execute(parameters);
            case "worlds_datapacks_install" -> worldsDatapacksInstall.execute(parameters);

            case "packs_list_local" -> packsListLocal.execute(parameters);
            case "resourcepacks_install" -> resourcepacksInstall.execute(parameters);
            case "resourcepacks_toggle" -> toggleResourcePack(parameters);
            case "resourcepacks_delete" -> resourcepacksDelete.execute(parameters);
            case "shaders_install" -> shadersInstall.execute(parameters);
            case "shaders_toggle" -> shadersToggle.execute(parameters);
            case "shaders_delete" -> shadersDelete.execute(parameters);
            case "modpacks_install" -> modpacksInstall.execute(parameters);
            case "modpacks_export" -> modpacksExport.execute(parameters);

            default -> ToolResult.failure("Unknown action '" + action + "'. See the tool description for the "
                    + "full list of valid actions.");
        };
    }

    /// Resolves and flips a single resource pack's enabled state through the native
    /// [ResourcePackManager] (the same `options.txt` `resourcePacks`/`incompatibleResourcePacks`
    /// state machine backing HMCL's resource pack page) — unlike the sibling `shaders_toggle`
    /// action, resource packs have no `.disabled` file-renaming convention, enablement lives
    /// entirely in `options.txt`, so this stays a thin inline wrapper around the manager instead
    /// of a separate leaf tool that would just re-expose the same three manager calls.
    /// Parameters ('pack', 'enable', 'instance') mirror `mods_toggle`/`shaders_toggle`.
    private ToolResult toggleResourcePack(Map<String, Object> parameters) {
        Object packObj = parameters.get("pack");
        if (packObj == null || packObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'pack' (the resource pack file/folder name or a substring of it).");
        }
        String packQuery = packObj.toString().trim();

        Boolean forceEnable = null;
        Object enableObj = parameters.get("enable");
        if (enableObj != null) {
            if (enableObj instanceof Boolean b) {
                forceEnable = b;
            } else {
                String s = enableObj.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true")) {
                    forceEnable = Boolean.TRUE;
                } else if (s.equals("false")) {
                    forceEnable = Boolean.FALSE;
                } else if (!s.isEmpty()) {
                    return ToolResult.failure("Parameter 'enable' must be a boolean (true/false), got: " + enableObj);
                }
            }
        }

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

        ResourcePackManager manager = new ResourcePackManager(repository, instanceId);
        List<ResourcePackFile> all;
        try {
            all = manager.getLocalFiles();
        } catch (IOException e) {
            return ToolResult.failure("Failed to read resource packs of instance '" + instanceId + "': " + e.getMessage());
        }

        String needle = packQuery.toLowerCase(Locale.ROOT);
        List<ResourcePackFile> candidates = new ArrayList<>();
        for (ResourcePackFile pack : all) {
            if (pack.getFileNameWithExtension().toLowerCase(Locale.ROOT).contains(needle)
                    || pack.getFileName().toLowerCase(Locale.ROOT).contains(needle)) {
                candidates.add(pack);
            }
        }

        if (candidates.isEmpty()) {
            StringBuilder names = new StringBuilder();
            if (all.isEmpty()) {
                names.append("(none — the resourcepacks folder is empty)");
            } else {
                int shown = Math.min(all.size(), 10);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) names.append(", ");
                    names.append(all.get(i).getFileNameWithExtension());
                }
                if (all.size() > shown) names.append(", ... (").append(all.size() - shown).append(" more)");
            }
            return ToolFailures.failure(
                    "No resource pack matching '" + packQuery + "' was found for instance '" + instanceId + "'",
                    ToolFailures.Retryable.YES,
                    "no installed resource pack name contains this substring, which is usually a typo",
                    "installed packs: " + names + "; use packs_list_local for the full list, or refine the query");
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(packQuery).append("' matches ").append(candidates.size()).append(" packs:\n");
            for (ResourcePackFile p : candidates) {
                sb.append("  - ").append(p.getFileNameWithExtension()).append('\n');
            }
            sb.append("Please refine the 'pack' parameter to match exactly one entry.");
            return ToolResult.failure(sb.toString().trim());
        }

        ResourcePackFile pack = candidates.get(0);
        boolean currentlyEnabled = manager.isEnabled(pack);
        boolean targetEnabled = forceEnable != null ? forceEnable : !currentlyEnabled;

        if (targetEnabled == currentlyEnabled) {
            return ToolResult.success("Resource pack '" + pack.getFileNameWithExtension() + "' is already "
                    + (currentlyEnabled ? "enabled" : "disabled") + "; no change made.");
        }

        boolean modified = targetEnabled
                ? manager.enableResourcePacks(List.of(pack))
                : manager.disableResourcePacks(List.of(pack));
        if (!modified) {
            return ToolResult.failure("Failed to " + (targetEnabled ? "enable" : "disable")
                    + " resource pack '" + pack.getFileNameWithExtension() + "': options.txt was not modified.");
        }

        return ToolResult.success("Resource pack " + (targetEnabled ? "enabled" : "disabled") + " in instance '" + instanceId + "'.\n"
                + "  pack : " + pack.getFileNameWithExtension() + "\n"
                + "  state: " + (targetEnabled ? "enabled" : "disabled"));
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
