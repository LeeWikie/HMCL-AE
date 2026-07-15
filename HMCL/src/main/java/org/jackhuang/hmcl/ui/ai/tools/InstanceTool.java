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
import org.jackhuang.hmcl.ai.tools.BackupTargetResolver;
import org.jackhuang.hmcl.ai.tools.FileBackup;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
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
    private final SetInstanceJavaTool setJava = new SetInstanceJavaTool();
    private final SetInstanceWindowTool setWindow = new SetInstanceWindowTool();
    private final SetInstanceLaunchBehaviorTool setLaunchBehavior = new SetInstanceLaunchBehaviorTool();
    private final SetInstanceGraphicsTool setGraphics = new SetInstanceGraphicsTool();
    private final SetInstanceLaunchArgsTool setLaunchArgs = new SetInstanceLaunchArgsTool();
    private final ListGameSettingsPresetsTool listPresets = new ListGameSettingsPresetsTool();
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
    private final RollbackModTool modsRollback = new RollbackModTool();

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
    private final WorldExportTool worldExport = new WorldExportTool();
    /// Owns the {@code saves/<world>/datapacks/} write domain (toggle + remove); needs the recycle-bin
    /// preference because its remove action deletes, so it's constructed in the ctor like the sibling
    /// delete tools.
    private final ManageDatapackTool worldsDatapacksManage;

    private final ListResourcePacksTool packsListLocal = new ListResourcePacksTool();
    private final InstallResourcePackTool resourcepacksInstall = new InstallResourcePackTool();
    private final CheckResourcePackUpdatesTool resourcepacksCheckUpdates = new CheckResourcePackUpdatesTool();
    private final DeleteResourcePackTool resourcepacksDelete;
    private final InstallShaderTool shadersInstall = new InstallShaderTool();
    private final ToggleShaderTool shadersToggle = new ToggleShaderTool();
    private final DeleteShaderTool shadersDelete;
    private final InstallModpackTool modpacksInstall = new InstallModpackTool();
    private final ExportModpackTool modpacksExport = new ExportModpackTool();

    // Maintenance / advanced tooling.
    private final GenerateLaunchScriptTool generateScript = new GenerateLaunchScriptTool();
    private final InstallLocalContentTool installLocal = new InstallLocalContentTool();
    private final SetInstanceIconTool setIcon = new SetInstanceIconTool();
    private final InstanceMaintenanceTool maintenance = new InstanceMaintenanceTool();
    private final ManageJavaTool manageJava = new ManageJavaTool();
    private final SchematicTool schematic; // needs the recycle-bin preference (schematics_delete)

    private final BooleanSupplier nbtToolsEnabled;

    /// @param toRecycleBin        whether destructive file operations should prefer the system
    ///                            recycle bin (delete / mods_delete / mods_update /
    ///                            worlds_delete / resourcepacks_delete / shaders_delete /
    ///                            datapacks_remove) — see {@code AiSettings#isDeleteToRecycleBin()}
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
        this.worldsDatapacksManage = new ManageDatapackTool(toRecycleBin);
        this.schematic = new SchematicTool(toRecycleBin);
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
                + "- Config: set_memory (maxMemoryMB fixed -Xmx, or auto=true for automatic allocation), "
                + "set_jvm_args (jvmArgs — custom JVM/GC flags; NEVER "
                + "hand-edit instance-game-settings.json for this, always use this action), "
                + "set_isolation (enable: true=own version folder, "
                + "false=follow global default), "
                + "set_window (windowType windowed/fullscreen/maximized, plus width/height for windowed mode), "
                + "set_launch_behavior (launcherVisibility, processPriority, allowAutoAgent, disableAutoGameOptions, "
                + "showLogs, enableDebugLogOutput, notCheckGame — what the launcher does around a launch, not in-game video), "
                + "set_graphics (graphicsBackend default/opengl/vulkan, openGLRenderer, vulkanRenderer — call with no "
                + "params first to see the renderers this machine supports), "
                + "set_launch_args (gameArguments, environmentVariables, noJVMOptions, noOptimizingJVMOptions, notCheckJVM "
                + "— per-instance overrides), "
                + "list_presets (no params — read-only; lists the global game-settings presets), "
                + "get_options, set_option (key, value), "
                + "clean_logs (keep), open_folder.\n"
                + "- Java: list_java, set_java (mode auto/version/detected/custom, with version or path), "
                + "download_java (gameVersion or javaVersion).\n"
                + "- Mods (already-known/installed content only — search via the 'search' tool first): "
                + "mods_list, mods_install (id or ids, source, loader, gameVersion, version — 'search' does NOT "
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
                + "mods_check_updates, mods_update (mod — or 'mods' array / all=true to update several at once), "
                + "mods_delete (mod, DANGEROUS), "
                + "mods_rollback (mod; version — rolls a mod back to an archived previous version, or omit "
                + "'version' to list the versions it can be rolled back to).\n"
                + "- Worlds: worlds_list, worlds_info (world; NBT tools must be enabled), "
                + "worlds_import (zip), worlds_delete (world, DANGEROUS), "
                + "worlds_backup_create (world), worlds_backup_list (world), "
                + "worlds_backup_restore (world, backupId, DANGEROUS — auto-backs-up current world first), "
                + "worlds_export (world; target — zips a world into a .zip archive, never overwriting), "
                + "worlds_duplicate (world, newName — copies a world into a new save folder), "
                + "worlds_datapacks_list (world), worlds_datapacks_install (world, source), "
                + "datapacks_toggle (world, datapack, enable — enables/disables a single datapack), "
                + "datapacks_remove (world, datapack, DANGEROUS — deletes a single datapack).\n"
                + "- Content already installed locally: packs_list_local (resource packs), "
                + "resourcepacks_install (id), resourcepacks_toggle (pack, enable — flips options.txt "
                + "enablement, no file renaming), resourcepacks_check_updates (apply, limit — lists resource "
                + "packs with a newer version; apply=true downloads & replaces them), "
                + "resourcepacks_delete (pack, DANGEROUS), "
                + "shaders_install (id), shaders_toggle (shader, enable — renames the shaderpacks entry "
                + "with a '.disabled' suffix, mirroring mods_toggle since shader packs have no options.txt "
                + "enablement state), shaders_delete (shader, DANGEROUS), "
                + "modpacks_install (id), modpacks_export.\n"
                + "- Maintenance & advanced: instance_maintenance (scope clean_junk/redownload_assets/"
                + "clear_resources/clear_libraries — mirrors the native Version Management menu; omit scope to only "
                + "report reclaimable sizes; the clear_* scopes are DANGEROUS and need confirm=true), "
                + "java_manage (operation refresh/add/uninstall — manage HMCL's Java runtime registry; add takes a "
                + "'path', uninstall is DANGEROUS and picks a managed runtime by javaVersion and/or path), "
                + "install_local_content (kind mod/resourcepack/shader, path — install a file you ALREADY have on "
                + "disk; use search + mods_install/etc. to DOWNLOAD instead), "
                + "set_instance_icon (iconType a built-in icon or 'auto', or imagePath a custom image; omit both to "
                + "report), "
                + "generate_launch_script (target output path — writes the exact command HMCL would use to start the "
                + "game as a runnable .bat/.sh/.command; requires a selected account), "
                + "schematics_list (READ-ONLY), schematics_import (path), schematics_delete (name, DANGEROUS), "
                + "schematics_reveal (name — open a Litematica '.litematic' in the file manager).";
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
                   "newName": {"type": "string", "description": "rename: the new instance name. worlds_duplicate: the folder name for the copied world (required for that action; must be a valid folder name that does not already exist)."},
                   "confirm": {"type": "boolean", "description": "delete/mods_delete/worlds_delete/worlds_backup_restore: confirmation flag some leaf tools require."},
                   "gameVersion": {"type": "string", "description": "create/download_java: Minecraft version, or 'latest'. mods_install: the Minecraft version you actually want to target when auto-picking a version — strongly recommended; 'search' does NOT verify this per result, so pass the version the user wants, not just whatever you queried search with. Omitting it risks installing a build for the wrong game version."},
                   "loader": {"type": "string", "description": "create: vanilla/fabric/forge/neoforge/quilt/optifine (loader to install). mods_install: fabric/forge/neoforge/quilt — the loader you actually want to target; strongly recommended. 'search' does NOT verify per result which loader a mod supports (loader there is only a query hint, not a filter), so pass the loader the user wants; omitting it risks installing a jar built for the wrong loader."},
                   "loaderVersion": {"type": "string", "description": "create: specific loader version; default = newest compatible."},
                   "version": {"type": "string", "description": "mods_install: an exact version name/number to lock onto (e.g. from the matching 'search' result); when set, this exact version is installed instead of auto-picking the newest match."},
                   "name": {"type": "string", "description": "create: optional new instance name. schematics_delete/schematics_reveal: the on-disk schematic file/folder name (case-insensitive substring, or a 'sub/dir/file' relative path) matching exactly one entry, as shown by schematics_list."},
                   "maxMemoryMB": {"type": "integer", "description": "set_memory: fixed -Xmx in MiB; setting it turns automatic allocation OFF so the exact value takes effect. Omit to only report, or use 'auto' to switch modes. Mutually exclusive with auto=true."},
                   "auto": {"type": "boolean", "description": "set_memory: true switches the instance back to AUTOMATIC memory allocation (HMCL sizes -Xmx from available physical RAM at launch; any configured maximum acts only as a lower bound); false forces fixed allocation while keeping the current maximum. Mutually exclusive with a maxMemoryMB value."},
                   "jvmArgs": {"type": "string", "description": "set_jvm_args: custom JVM/GC arguments string (e.g. '-XX:+UseG1GC'); omit to only report the current value, pass an empty string to clear it. Do not put -Xmx/-Xms here, use maxMemoryMB via set_memory instead."},
                   "mode": {"type": "string", "description": "set_java: Java runtime selection mode — 'auto', 'version' (pin installed Java by MAJOR version via 'version'), 'detected' (reference a detected Java via its executable 'path'), or 'custom' (explicit Java executable via 'path'). Omit to only REPORT current Java selection."},
                   "path": {"type": "string", "description": "set_java: absolute path to a Java executable (mode=custom/detected). install_local_content: absolute path of the local file to install. schematics_import: absolute path of the source '.litematic'. java_manage: for add, a java/java.exe or JDK/JRE home path; for uninstall, a substring of the managed runtime's install path."},
                   "windowType": {"type": "string", "description": "set_window: initial game window mode — 'windowed', 'fullscreen', or 'maximized'. Omit (with width/height) to only report."},
                   "width": {"type": "integer", "description": "set_window: window width in px (1..16384); only in windowed mode. Paired with height."},
                   "height": {"type": "integer", "description": "set_window: window height in px (1..16384); only in windowed mode. Paired with width."},
                   "launcherVisibility": {"type": "string", "description": "set_launch_behavior: what the HMCL window does after the game starts — close/hide/keep/hide_and_reopen. Omit to leave unchanged."},
                   "processPriority": {"type": "string", "description": "set_launch_behavior: game process OS priority — low/below_normal/normal/above_normal/high. Omit to leave unchanged."},
                   "allowAutoAgent": {"type": "boolean", "description": "set_launch_behavior: allow HMCL to attach Java agents. Omit to leave unchanged."},
                   "disableAutoGameOptions": {"type": "boolean", "description": "set_launch_behavior: do NOT auto-generate game options/options.txt. Omit to leave unchanged."},
                   "showLogs": {"type": "boolean", "description": "set_launch_behavior: open the log window after launch. Omit to leave unchanged."},
                   "enableDebugLogOutput": {"type": "boolean", "description": "set_launch_behavior: verbose debug logs. Omit to leave unchanged."},
                   "notCheckGame": {"type": "boolean", "description": "set_launch_behavior: skip game-completeness check before launch. Omit to leave unchanged."},
                   "graphicsBackend": {"type": "string", "description": "set_graphics: graphics API — 'default', 'opengl', or 'vulkan'. Pass 'inherit' to drop the per-instance override. Omit all three graphics params to only REPORT current settings + this machine's supported renderers."},
                   "openGLRenderer": {"type": "string", "description": "set_graphics: OpenGL renderer driver name (takes effect when graphicsBackend=opengl). Must be one the report lists (e.g. DEFAULT/LLVMPIPE/ZINK/D3D12); invalid is rejected with the valid list. 'inherit' clears the override."},
                   "vulkanRenderer": {"type": "string", "description": "set_graphics: Vulkan renderer driver name (takes effect when graphicsBackend=vulkan). Must be one the report lists (e.g. DEFAULT/DOZEN/NVIDIA_VULKAN/MOLTENVK); invalid is rejected with the valid list. 'inherit' clears the override."},
                   "gameArguments": {"type": "string", "description": "set_launch_args: extra Minecraft command-line arguments (e.g. '--width 1280'); empty string clears. Omit to leave unchanged. Per-instance override. NOT JVM flags (use set_jvm_args)."},
                   "environmentVariables": {"type": "string", "description": "set_launch_args: environment variables for the game process in KEY=VALUE (newline/semicolon-separated) form; empty string clears. Omit to leave unchanged. Per-instance override."},
                   "noJVMOptions": {"type": "boolean", "description": "set_launch_args: true = do NOT add HMCL's default JVM arguments. Omit to leave unchanged. Per-instance override."},
                   "noOptimizingJVMOptions": {"type": "boolean", "description": "set_launch_args: true = do NOT add HMCL's optimizing JVM arguments. Omit to leave unchanged. Per-instance override."},
                   "notCheckJVM": {"type": "boolean", "description": "set_launch_args: true = skip HMCL's JVM validity check before launch. Omit to leave unchanged. Per-instance override."},
                   "enable": {"type": "boolean", "description": "set_isolation: true to isolate this instance, false to follow the global default; omit to only report the current state. mods_toggle/resourcepacks_toggle/shaders_toggle: true forces enable, false forces disable; omit to toggle the current state."},
                   "key": {"type": "string", "description": "get_options/set_option: options.txt key."},
                   "value": {"type": "string", "description": "set_option: the new value."},
                   "keep": {"type": "integer", "description": "clean_logs: how many recent log files to keep."},
                   "javaVersion": {"type": "integer", "description": "download_java: an integer Java major version (8/16/17/21/25), alternative to gameVersion."},
                   "id": {"type": "string", "description": "mods_install/resourcepacks_install/shaders_install/modpacks_install: the project id/slug from the matching search_* action of the 'search' tool."},
                   "ids": {"type": "array", "items": {"type": "string"}, "description": "mods_install (optional, batch): several project ids/slugs to install in one call, each reusing the same loader/gameVersion/version/source and reported separately. Dependencies are NOT auto-installed. Use 'id' for a single mod."},
                   "source": {"type": "string", "description": "mods_install/worlds_datapacks_install: content source (e.g. modrinth/curseforge), or a local file path."},
                   "mod": {"type": "string", "description": "mods_toggle/mods_info/mods_update/mods_delete: the mod file name or a distinguishing substring."},
                   "mods": {"type": "array", "items": {"type": "string"}, "description": "mods_update (optional, batch): several mod substrings to update in one call; each must match exactly one installed mod. Use 'mod' for a single mod, or 'all' to update every mod."},
                   "all": {"type": "boolean", "description": "mods_update (optional, batch): when true, update every installed mod that has a newer compatible version. Can be slow with many mods; consider mods_check_updates first."},
                   "world": {"type": "string", "description": "worlds_*: the save folder name under 'saves/'."},
                   "zip": {"type": "string", "description": "worlds_import: absolute local path of the .zip world archive."},
                   "backupId": {"type": "string", "description": "worlds_backup_restore: the snapshot id from worlds_backup_list."},
                   "pack": {"type": "string", "description": "resourcepacks_toggle/resourcepacks_delete: the resource pack file/folder name or a distinguishing substring (matches both '.zip' archives and unpacked folders)."},
                   "shader": {"type": "string", "description": "shaders_toggle/shaders_delete: the shader pack file/folder name or a distinguishing substring (matches both '.zip' archives and unpacked folders)."},
                   "apply": {"type": "boolean", "description": "resourcepacks_check_updates: false (default) only lists resource packs with a newer version; true downloads & replaces them."},
                   "limit": {"type": "number", "description": "resourcepacks_check_updates: max resource packs to check (default 25, cap 60)."},
                   "target": {"type": "string", "description": "worlds_export: output .zip path (absolute, or relative to the instance game dir; a bare dir or name without '.zip' is auto-completed). Optional; defaults to '<world>.zip'. Never overwrites an existing file. generate_launch_script: the output script path; absolute or relative to the instance game dir, extension auto-completed (.bat/.sh/.command). Optional; defaults to '<instance>.<ext>'."},
                   "datapack": {"type": "string", "description": "datapacks_toggle/datapacks_remove: the datapack name under saves/<world>/datapacks/ (case-insensitive substring) matching exactly one entry."},
                   "scope": {"type": "string", "description": "instance_maintenance: which cleanup to run — clean_junk (delete logs/ + crash-reports/), redownload_assets (force-refresh the asset index), clear_resources (DANGEROUS, delete shared assets; needs confirm=true), clear_libraries (DANGEROUS, delete shared libraries; needs confirm=true). Omit to only REPORT reclaimable sizes."},
                   "operation": {"type": "string", "description": "java_manage: which Java-registry action — refresh (re-scan for installed Java), add (register a Java you have, with 'path'), uninstall (DANGEROUS, delete an HMCL-downloaded runtime, pick via 'javaVersion' and/or 'path'). Omit to only list the known runtimes."},
                   "kind": {"type": "string", "description": "install_local_content: the type of the local file being installed — 'mod', 'resourcepack', or 'shader'. The file at 'path' must match this kind."},
                   "iconType": {"type": "string", "description": "set_instance_icon: one of HMCL's built-in icon names, or 'auto' to reset to automatic detection. Mutually exclusive with imagePath. Omit both to only report the current icon."},
                   "imagePath": {"type": "string", "description": "set_instance_icon: absolute path to a custom image file (copied into the instance folder). Mutually exclusive with iconType."}
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
            // datapacks_remove likewise deletes a datapack file/folder (recycle-bin-preferring), so it
            // shares the same DANGEROUS_WRITE gate as its sibling delete actions.
            // schematics_delete removes a schematic file/folder (recycle-bin-preferring), the same
            // destructive-delete pattern as the sibling delete actions.
            case "delete", "mods_delete", "mods_update", "worlds_delete", "worlds_backup_restore",
                    "resourcepacks_delete", "shaders_delete", "datapacks_remove",
                    "schematics_delete" -> ToolPermission.DANGEROUS_WRITE;
            // resourcepacks_check_updates is READ_ONLY when only checking (apply unset/false) but
            // CONTROLLED_WRITE when apply=true downloads and replaces files — delegate to the leaf so
            // the apply-true write path can never be under-gated by a blanket entry here.
            case "resourcepacks_check_updates" -> resourcepacksCheckUpdates.getPermission(parameters);
            // instance_maintenance and java_manage carry a whole spread of risk levels behind one
            // action verb (a read-only report through to a shared-directory wipe / on-disk uninstall),
            // so their permission is computed from the sub-operation, never a blanket entry.
            case "instance_maintenance", "maintenance" -> maintenancePermission(parameters);
            case "java_manage", "manage_java" -> javaManagePermission(parameters);
            case "list", "details", "get_options", "list_java", "list_presets",
                    "mods_list", "mods_info", "mods_check_updates",
                    "worlds_list", "worlds_info", "worlds_backup_list", "worlds_datapacks_list",
                    "packs_list_local", "schematics_list" -> ToolPermission.READ_ONLY;
            // open_folder creates the directory on disk (Files.createDirectories) and launches an
            // external file-manager process — side-effecting, not safely repeatable/parallelizable.
            default -> ToolPermission.CONTROLLED_WRITE;
        };
    }

    /// instance_maintenance permission by 'scope': the two clear_* scopes wipe shared base-directory
    /// content (DANGEROUS); the no-scope call only reports reclaimable sizes (READ_ONLY); clean_junk /
    /// redownload_assets write but are recoverable (CONTROLLED_WRITE default).
    private static ToolPermission maintenancePermission(Map<String, Object> parameters) {
        String scope = strParam(parameters, "scope");
        if (scope.isEmpty()) {
            scope = strParam(parameters, "query");
        }
        return switch (scope) {
            case "" -> ToolPermission.READ_ONLY;
            case "clear_resources", "clear_libraries" -> ToolPermission.DANGEROUS_WRITE;
            default -> ToolPermission.CONTROLLED_WRITE;
        };
    }

    /// java_manage permission by 'operation': uninstall deletes a managed runtime from disk
    /// (DANGEROUS); the no-op report / refresh only read/re-scan (READ_ONLY); add registers a runtime
    /// (CONTROLLED_WRITE default).
    private static ToolPermission javaManagePermission(Map<String, Object> parameters) {
        String op = strParam(parameters, "operation");
        if (op.isEmpty()) {
            op = strParam(parameters, "mode");
        }
        return switch (op) {
            case "", "list", "report", "refresh", "rescan", "scan" -> ToolPermission.READ_ONLY;
            case "uninstall", "remove", "delete" -> ToolPermission.DANGEROUS_WRITE;
            default -> ToolPermission.CONTROLLED_WRITE;
        };
    }

    private static String strParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
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
            case "set_java" -> setJava.execute(parameters);
            case "set_window" -> setWindow.execute(parameters);
            case "set_launch_behavior" -> setLaunchBehavior.execute(parameters);
            case "set_graphics" -> setGraphics.execute(parameters);
            case "set_launch_args" -> setLaunchArgs.execute(parameters);
            case "list_presets" -> listPresets.execute(parameters);
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
            case "mods_rollback" -> modsRollback.execute(parameters);

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
            case "worlds_export", "world_export" -> worldExport.execute(parameters);
            case "worlds_duplicate", "world_duplicate" -> worldExport.execute(parameters);
            case "datapacks_toggle" -> worldsDatapacksManage.execute(parameters);
            case "datapacks_remove" -> worldsDatapacksManage.execute(parameters);

            case "packs_list_local" -> packsListLocal.execute(parameters);
            case "resourcepacks_install" -> resourcepacksInstall.execute(parameters);
            case "resourcepacks_toggle" -> toggleResourcePack(parameters);
            case "resourcepacks_check_updates" -> resourcepacksCheckUpdates.execute(parameters);
            case "resourcepacks_delete" -> resourcepacksDelete.execute(parameters);
            case "shaders_install" -> shadersInstall.execute(parameters);
            case "shaders_toggle" -> shadersToggle.execute(parameters);
            case "shaders_delete" -> shadersDelete.execute(parameters);
            case "modpacks_install" -> modpacksInstall.execute(parameters);
            case "modpacks_export" -> modpacksExport.execute(parameters);

            case "generate_launch_script", "launch_script" -> generateScript.execute(parameters);
            case "install_local_content", "install_local" -> installLocal.execute(parameters);
            case "set_instance_icon", "set_icon" -> setIcon.execute(parameters);
            case "instance_maintenance", "maintenance" -> maintenance.execute(parameters);
            case "java_manage", "manage_java" -> manageJava.execute(parameters);
            case "schematics_list", "schematics_import", "schematics_delete", "schematics_reveal"
                    -> schematic.execute(parameters);

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

        // Mandatory backup-before-edit (hard precondition): enabling/disabling a resource pack
        // rewrites the resourcePacks/incompatibleResourcePacks lines of the instance's existing
        // options.txt in place, inside HMCL's native ResourcePackManager (so there is no Files.write
        // here to wrap — the snapshot must be taken BEFORE the manager call). Only when options.txt
        // already exists — the manager creating a fresh one has nothing to back up. Fail-closed: no
        // restore point, no toggle.
        Path optionsFile;
        try {
            optionsFile = repository.getRunDirectory(instanceId).resolve("options.txt");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve options.txt for instance '" + instanceId + "': " + e.getMessage());
        }
        if (Files.isRegularFile(optionsFile)) {
            FileBackup.Result snapshot = FileBackup.requireSnapshot(
                    new BackupTargetResolver.Target(optionsFile, List.of()));
            if (!snapshot.success()) {
                return ToolResult.failure("Refusing to toggle the resource pack without backing up options.txt first: " + snapshot.reason());
            }
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
