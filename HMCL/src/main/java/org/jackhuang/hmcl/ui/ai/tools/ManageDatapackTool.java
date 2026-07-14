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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolParams;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// A tool that ENABLES/DISABLES or REMOVES a single datapack of a single-player world — the write
/// side of the native {@link org.jackhuang.hmcl.ui.versions.DataPackListPage} (its per-row enable
/// switch and its delete button). Reading the installed datapacks is covered by
/// {@link ListDatapacksTool} (`worlds_datapacks_list`); this tool focuses on the two write
/// operations that page exposes and reports the resulting state for each.
///
/// It is dispatched by the `instance` facade under TWO actions:
/// - `datapacks_toggle` → [#toggle] (enable/disable), and
/// - `datapacks_remove` → [#remove] (delete),
///
/// so one class owns the whole `saves/<world>/datapacks/` write domain, exactly as
/// {@link ToggleShaderTool}/{@link DeleteShaderTool} pair up for shaders. [#execute] additionally
/// routes on the `action` parameter, so wiring either a single `X.execute(parameters)` case or two
/// explicit `X.toggle`/`X.remove` cases both work.
///
/// ### Faithful reuse of the native on-disk convention
///
/// The enable/disable and delete file operations mirror the native `DataPack` addon class
/// (`org.jackhuang.hmcl.addon.datapack.DataPack`) exactly (see its
/// `Pack#calculateNewStatusFilePath` / `deletePack`) rather than inventing a parallel scheme.
/// Datapacks use the bare `"disabled"` extension
/// ({@code DataPack.DISABLED_EXT}), and — unlike mods/shaders — the disabled state is encoded
/// differently for the two kinds of pack:
/// - a **zip/file** datapack is disabled by suffixing the file itself: `foo.zip` ⇄ `foo.zip.disabled`;
/// - a **folder** datapack is disabled by renaming the `pack.mcmeta` INSIDE it —
///   `pack.mcmeta` ⇄ `pack.mcmeta.disabled` — while the folder name stays put.
///
/// `DataPack`'s observable-list updates are `Platform.runLater`-deferred, but the actual
/// filesystem work ({@code Files.move} / delete) is synchronous, so — exactly like
/// {@link ToggleShaderTool}/{@link DeleteShaderTool}, which also touch a content type with no
/// long-lived manager object — this tool performs the same moves/deletes directly off the JavaFX
/// thread. Any datapacks page that happens to be open refreshes its list the next time it is
/// shown, just as the shader tools already accept.
///
/// Deletion prefers the OS recycle bin (recoverable) via {@link FileTrash} when the user's
/// "delete to recycle bin" preference is on — a deliberate, user-friendlier superset of the native
/// page's permanent delete, consistent with the other AE delete tools
/// ({@link DeleteShaderTool}/{@code DeleteModTool}). A file held open by a running game is
/// attributed through the shared {@link ToggleModTool#fileOperationFailure(String, String, Throwable)}
/// helper instead of leaking a raw I/O message.
///
/// Resolution mirrors {@link ListDatapacksTool}/{@link InstallDatapackTool}: the world's
/// `saves/<world>/datapacks/` folder is located through HMCL's launcher APIs
/// ([`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] /
/// [`HMCLGameRepository#getRunDirectory(String)`]) with the same path-confinement guard, then the
/// `datapack` parameter is matched by a case-insensitive substring over each entry's on-disk name
/// (or its `.disabled`-stripped base name). The tool refuses to act unless exactly one entry
/// matches, so it can never toggle/delete the wrong datapack by accident.
///
/// Permission level (at the leaf): CONTROLLED_WRITE. The merged `instance` facade elevates the
/// `datapacks_remove` action to DANGEROUS_WRITE (destructive delete), while `datapacks_toggle`
/// stays CONTROLLED_WRITE (a reversible rename).
@NotNullByDefault
public final class ManageDatapackTool implements Tool {

    /// The datapack "disabled" extension, mirroring {@code DataPack.DISABLED_EXT} (a bare
    /// `"disabled"`, i.e. the on-disk suffix is `".disabled"`). Intentionally NOT
    /// {@code LocalAddonManager.DISABLED_EXTENSION} — mods/shaders suffix the whole file/folder
    /// name, datapacks suffix either the zip file or the inner `pack.mcmeta`, so the two constants
    /// must not be conflated.
    private static final String DISABLED_EXT = "disabled";
    private static final String DISABLED_DOT_EXT = "." + DISABLED_EXT;
    private static final String MCMETA = "pack.mcmeta";

    /// The maximum number of real datapack names carried in a zero-match failure — enough for the
    /// model to spot a typo, bounded so a huge datapacks folder can't flood the context. Mirrors
    /// {@link DeleteShaderTool}'s zero-match listing.
    private static final int MAX_LISTED = 10;

    /// Whether destructive removal should prefer the OS recycle bin (recoverable) over a permanent
    /// delete; read live on each call (typically `aiSettings::isDeleteToRecycleBin`).
    private final BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether the `datapacks_remove` path should prefer the OS recycle bin
    ///                     (recoverable) over a permanent delete — the same {@link BooleanSupplier}
    ///                     the sibling delete tools receive from the `instance` facade.
    public ManageDatapackTool(BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "manage_datapack";
    }

    @Override
    public String getDescription() {
        return "Enables/disables or removes a single datapack of a single-player world — the write side of "
                + "HMCL's datapack page. Parameters: world (required, the save folder name under 'saves/'), "
                + "datapack (required, the datapack name — or a case-insensitive substring — that matches exactly "
                + "one entry under saves/<world>/datapacks/), instance (optional, the instance id; defaults to the "
                + "currently selected instance). "
                + "Enable/disable (action=\"datapacks_toggle\"): pass 'enabled' (true/false) to force a state, or "
                + "omit it to flip the current one; a zip datapack is toggled by renaming it to/from "
                + "'xxx.zip.disabled', a folder datapack by renaming its inner pack.mcmeta to/from "
                + "'pack.mcmeta.disabled' (the folder is never moved). "
                + "Remove (action=\"datapacks_remove\"): deletes the datapack, preferring the system recycle bin "
                + "when possible (recoverable), otherwise a permanent delete. "
                + "Fails if the substring matches zero or more than one entry, so it never touches the wrong "
                + "datapack. To LIST a world's datapacks first, use instance(action=\"worlds_datapacks_list\").";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Route on the facade's action verb (or an explicit operation/mode fallback for standalone
        // use). The facade may instead call toggle()/remove() directly; both paths funnel into run().
        String op = firstNonBlank(
                str(parameters.get("action")),
                str(parameters.get("operation")),
                str(parameters.get("mode"))).toLowerCase(Locale.ROOT);
        switch (op) {
            case "datapacks_remove":
            case "remove":
            case "delete":
                return remove(parameters);
            case "datapacks_toggle":
            case "toggle":
            case "enable":
            case "disable":
                return toggle(parameters);
            default:
                return ToolResult.failure("Specify which datapack write operation to perform: "
                        + "action=\"datapacks_toggle\" (enable/disable) or action=\"datapacks_remove\" (delete).");
        }
    }

    /// Enables/disables one datapack (action `datapacks_toggle`). See class doc.
    public ToolResult toggle(Map<String, Object> parameters) {
        return run(parameters, false);
    }

    /// Removes one datapack (action `datapacks_remove`). See class doc.
    public ToolResult remove(Map<String, Object> parameters) {
        return run(parameters, true);
    }

    // ------------------------------------------------------------------------------------------

    private ToolResult run(Map<String, Object> parameters, boolean remove) {
        // Two distinct required params (world + datapack): read both with strict() so neither the
        // generic dump-key nor the sole-value fallback can steal a value meant for the other one
        // (the InstallDatapackTool convention for its world + source pair).
        String world = ToolParams.strict(parameters, "world", "save", "folder", "saveName");
        if (world.isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }
        String datapackQuery = ToolParams.strict(parameters, "datapack", "pack", "datapackName", "name", "file");
        if (datapackQuery.isEmpty()) {
            return ToolResult.failure("Parameter 'datapack' (the datapack name or a substring of it) is required.");
        }

        // Toggle-only: parse the optional 'enabled' (accepting 'enable' too, matching the facade's
        // shared toggle property). A Boolean or the strings "true"/"false" are accepted; absent
        // means "flip the current state".
        @Nullable Boolean forceEnabled = null;
        if (!remove) {
            Object enabledObj = parameters.get("enabled");
            if (enabledObj == null) {
                enabledObj = parameters.get("enable");
            }
            if (enabledObj != null) {
                if (enabledObj instanceof Boolean b) {
                    forceEnabled = b;
                } else {
                    String s = enabledObj.toString().trim().toLowerCase(Locale.ROOT);
                    if (s.equals("true")) {
                        forceEnabled = Boolean.TRUE;
                    } else if (s.equals("false")) {
                        forceEnabled = Boolean.FALSE;
                    } else if (!s.isEmpty()) {
                        return ToolResult.failure("Parameter 'enabled' must be a boolean (true/false), got: " + enabledObj);
                    }
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

        // 'world'/'datapack' are the primary params, so the instance resolver must NOT accept the
        // generic aliases (they'd steal a value meant for those) — allowGenericAliases=false, exactly
        // like the sibling world tools.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        Path savesDir;
        Path worldDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves").normalize();
            worldDir = savesDir.resolve(world).normalize();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        // Path confinement: a garbled name like "../.." or an absolute path must never escape the
        // saves directory and let this tool rename/delete files elsewhere (mirrors ListDatapacksTool
        // / InstallDatapackTool / DeleteWorldTool).
        if (!worldDir.startsWith(savesDir) || worldDir.equals(savesDir)) {
            return ToolResult.failure("Refused to operate on world '" + world + "': it resolves outside the saves "
                    + "directory. Pass a single world folder name only.");
        }

        if (!Files.isDirectory(worldDir)) {
            return WorldToolSupport.worldNotFoundFailure(savesDir, world);
        }

        Path datapacksDir = worldDir.resolve("datapacks");
        if (!Files.isDirectory(datapacksDir)) {
            return ToolFailures.failure(
                    "World '" + world + "' of instance '" + instance + "' has no datapacks folder yet, so there is no "
                            + "datapack to " + (remove ? "remove" : "toggle"),
                    ToolFailures.Retryable.NO,
                    "the world has no datapacks/ directory",
                    "Add a datapack first with instance(action=\"worlds_datapacks_install\"), or double-check the "
                            + "world name with instance(action=\"worlds_datapacks_list\")");
        }

        // Enumerate every immediate child (folder or regular file) — exactly what
        // worlds_datapacks_list shows the model — and match by case-insensitive substring over the
        // raw on-disk name AND the '.disabled'-stripped base name.
        List<Entry> all = new ArrayList<>();
        List<Entry> matches = new ArrayList<>();
        String needle = datapackQuery.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(datapacksDir)) {
            for (Path path : stream) {
                boolean directory = Files.isDirectory(path);
                if (!directory && !Files.isRegularFile(path)) {
                    continue;
                }
                String rawName = path.getFileName().toString();
                String baseName = (!directory && DISABLED_EXT.equals(FileUtils.getExtension(rawName)))
                        ? StringUtils.removeSuffix(rawName, DISABLED_DOT_EXT)
                        : rawName;
                Entry entry = new Entry(path, directory, rawName, baseName);
                all.add(entry);
                if (rawName.toLowerCase(Locale.ROOT).contains(needle)
                        || baseName.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(entry);
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to list the datapacks folder of world '" + world + "': " + e.getMessage());
        }

        if (matches.isEmpty()) {
            return ToolFailures.failure(
                    "No datapack matching '" + datapackQuery + "' was found in " + datapacksDir,
                    ToolFailures.Retryable.YES,
                    "no datapack name contains this substring, which is usually a typo",
                    "datapacks: " + describe(all) + "; use instance(action=\"worlds_datapacks_list\") for the full "
                            + "list, or refine the 'datapack' query");
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(datapackQuery).append("' matches ").append(matches.size())
                    .append(" datapacks:\n");
            for (Entry e : matches) {
                sb.append("  - ").append(e.rawName).append('\n');
            }
            sb.append("Please refine the 'datapack' parameter to match exactly one entry.");
            return ToolResult.failure(sb.toString().trim());
        }

        Entry match = matches.get(0);
        return remove ? doRemove(instance, match) : doToggle(instance, world, match, forceEnabled);
    }

    /// Deletes the matched datapack, preferring the recycle bin (recoverable) when enabled — the
    /// `DataPack#deletePack` filesystem effect (folder → recursive, file → single delete), routed
    /// through {@link FileTrash} like the sibling AE delete tools.
    private ToolResult doRemove(String instance, Entry match) {
        boolean recycled;
        try {
            recycled = FileTrash.delete(match.path, toRecycleBin.getAsBoolean());
        } catch (Throwable e) {
            // A locked datapack is almost always the world being played right now — attribute it
            // through the shared GameResourceGuard-backed helper instead of a raw I/O message.
            return ToggleModTool.fileOperationFailure(instance,
                    "Deleting datapack '" + match.rawName + "' from instance '" + instance + "' failed", e);
        }
        return ToolResult.success((recycled
                ? "Moved datapack '" + match.rawName + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted datapack '" + match.rawName + "' from disk.\n")
                + "  instance: " + instance + "\n"
                + "  kind    : " + (match.directory ? "folder" : "zip/file") + "\n"
                + "  path    : " + match.path);
    }

    /// Flips the matched datapack's enabled state, reproducing {@code DataPack.Pack}'s status-file
    /// rename convention exactly (zip → the file itself, folder → its inner `pack.mcmeta`).
    private ToolResult doToggle(String instance, String world, Entry match, @Nullable Boolean forceEnabled) {
        // Resolve the status file whose name carries the enabled/disabled state, and the enabled /
        // disabled target names for it, per the two datapack kinds.
        Path statusFile;
        Path enabledTarget;
        Path disabledTarget;
        boolean currentlyEnabled;

        if (match.directory) {
            Path activeMeta = match.path.resolve(MCMETA);
            Path disabledMeta = match.path.resolve(MCMETA + DISABLED_DOT_EXT); // pack.mcmeta.disabled
            boolean hasActive = Files.exists(activeMeta);
            boolean hasDisabled = Files.exists(disabledMeta);
            if (!hasActive && !hasDisabled) {
                return ToolFailures.failure(
                        "'" + match.rawName + "' is a folder under datapacks/ but not a valid datapack (it has no "
                                + "pack.mcmeta), so it has no enabled/disabled state to toggle",
                        ToolFailures.Retryable.NO,
                        "only a datapack folder containing pack.mcmeta (or pack.mcmeta.disabled) can be toggled",
                        "Use instance(action=\"datapacks_remove\") to delete this folder, or inspect its contents");
            }
            // Native prefers the active pack.mcmeta when both somehow exist; the resulting
            // enable-when-already-enabled / collision cases are handled below.
            currentlyEnabled = hasActive;
            statusFile = hasActive ? activeMeta : disabledMeta;
            enabledTarget = activeMeta;
            disabledTarget = disabledMeta;
        } else {
            boolean disabled = DISABLED_EXT.equals(FileUtils.getExtension(match.rawName));
            currentlyEnabled = !disabled;
            statusFile = match.path;
            Path parent = match.path.getParent(); // = the datapacks directory
            // enable: strip the trailing '.disabled' (foo.zip.disabled -> foo.zip);
            // disable: append '.disabled'       (foo.zip           -> foo.zip.disabled).
            enabledTarget = disabled ? parent.resolve(FileUtils.getNameWithoutExtension(match.rawName)) : match.path;
            disabledTarget = disabled ? match.path : parent.resolve(match.rawName + DISABLED_DOT_EXT);
        }

        boolean targetEnabled = forceEnabled != null ? forceEnabled : !currentlyEnabled;
        if (targetEnabled == currentlyEnabled) {
            return ToolResult.success("Datapack '" + match.rawName + "' is already "
                    + (currentlyEnabled ? "enabled" : "disabled") + "; no change made.\n"
                    + "  path: " + match.path);
        }

        Path from = statusFile;
        Path to = targetEnabled ? enabledTarget : disabledTarget;

        // Refuse to clobber a counterpart: if both an enabled and a disabled variant already exist,
        // renaming would silently overwrite one of them (mirrors ToggleShaderTool/ToggleModTool).
        if (!to.equals(from) && Files.exists(to)) {
            return ToolFailures.failure(
                    "Cannot " + (targetEnabled ? "enable" : "disable") + " datapack '" + match.rawName + "': the "
                            + "target '" + to.getFileName() + "' already exists, so both an enabled and a disabled "
                            + "copy are present",
                    ToolFailures.Retryable.NO,
                    "renaming would silently overwrite the counterpart",
                    "Remove one of the two duplicates first with instance(action=\"datapacks_remove\"), then retry");
        }

        try {
            Files.move(from, to);
        } catch (IOException e) {
            return ToggleModTool.fileOperationFailure(instance,
                    "Toggling datapack '" + match.rawName + "' in world '" + world + "' of instance '" + instance
                            + "' failed", e);
        }
        if (!Files.exists(to)) {
            return ToggleModTool.fileOperationFailure(instance,
                    "Toggling datapack '" + match.rawName + "' in world '" + world + "' of instance '" + instance
                            + "' failed", null);
        }

        return ToolResult.success("Datapack " + (targetEnabled ? "enabled" : "disabled") + " in world '" + world
                + "' of instance '" + instance + "'.\n"
                + "  datapack: " + match.rawName + "\n"
                + "  " + from.getFileName() + "  ->  " + to.getFileName()
                + (match.directory ? "  (inside the folder)" : "") + "\n"
                + "  state   : " + (targetEnabled ? "enabled" : "disabled"));
    }

    /// One immediate child of the datapacks directory: its path, whether it is a folder, its raw
    /// on-disk name, and the base name with any trailing `.disabled` stripped (for matching/display).
    private record Entry(Path path, boolean directory, String rawName, String baseName) {
    }

    /// Lists up to [#MAX_LISTED] real datapack names (raw on-disk, as worlds_datapacks_list shows
    /// them) for a zero-match failure, appending a "(N more)" tail when truncated; an empty folder
    /// is reported explicitly. Mirrors {@link DeleteShaderTool}'s zero-match listing.
    private static String describe(List<Entry> entries) {
        if (entries.isEmpty()) {
            return "(none — the datapacks folder is empty)";
        }
        int shown = Math.min(entries.size(), MAX_LISTED);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entries.get(i).rawName);
        }
        if (entries.size() > shown) {
            sb.append(", ... (").append(entries.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    private static String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
