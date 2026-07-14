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

import javafx.geometry.Point3D;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolParams;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.schematic.LitematicFile;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A tool that owns the whole `schematics/` directory of a single instance — the AI-facing side of
/// the native {@link org.jackhuang.hmcl.ui.versions.SchematicsPage} (its refresh/list, add-files,
/// per-row delete and "reveal in file manager" buttons). One class dispatches FOUR actions off the
/// `instance` facade, mirroring how {@link ManageDatapackTool} owns the datapacks write domain:
///   - `schematics_list`   → [#list]   (READ-ONLY): recursively lists the `.litematic` files (and
///                           sub-folders) HMCL keeps under the instance's `schematics/` directory,
///                           parsing each file's Litematica metadata (display name, author, size,
///                           dimensions, block/region counts) exactly like the native page's cells;
///   - `schematics_import` → [#import_] : copies a local `.litematic` file INTO that directory,
///                           the same `Files.copy(source, schematics/<fileName>)` the native page's
///                           drag-and-drop / "Add" button performs, but validated and non-clobbering;
///   - `schematics_delete` → [#delete] : removes one matched `.litematic` file (or sub-folder),
///                           preferring the OS recycle bin (recoverable) via {@link FileTrash};
///   - `schematics_reveal` → [#reveal] : opens the matched entry (or the `schematics/` folder itself)
///                           in the system file manager via {@link FXUtils}.
///
/// ### Faithful reuse of the native on-disk logic
///
/// The `schematics/` directory is located through the SAME isolation-aware repository call the
/// native page uses — [`HMCLGameRepository#getSchematicsDirectory(String)`] (which resolves
/// `getRunDirectory(id).resolve("schematics")`), so an isolated instance's own folder is honoured.
/// Listing reproduces {@code SchematicsPage#loadAll} (recurse sub-directories, treat every
/// `*.litematic` regular file as a schematic, tolerate a per-directory I/O error by skipping it),
/// and metadata is read through {@link LitematicFile#load(Path)} — the exact loader the page's cells
/// use. Importing reproduces {@code SchematicsPage#addFiles} (`Files.createDirectories` then
/// `Files.copy`) with the same `*.litematic`-regular-file precondition the page's drag filter
/// enforces. Reveal reuses {@link FXUtils#showFileInExplorer(Path)} for a file and
/// {@link FXUtils#openFolder(Path)} for a folder — precisely the two calls the page's row/back-item
/// `onReveal()` overloads make. Both open commands spawn their own background thread internally, so
/// no JavaFX-thread hop is required here.
///
/// ### Deviations from the native page, on purpose (validation + clear errors)
///
/// The page silently swallows a name collision (`FileAlreadyExistsException`) and permanently
/// deletes; this tool instead REFUSES to overwrite an existing schematic (a clear, retryable error
/// telling the model to rename), routes deletes to the recycle bin when the user's preference is on
/// (recoverable, consistent with the other AE delete tools), and rejects an import whose bytes do
/// not parse as a Litematica schematic — so a mistyped path can never dump junk into `schematics/`.
///
/// Permission level (assigned by the `instance` facade, not here — this leaf implements the
/// permission-less {@link Tool} like {@link ManageDatapackTool}): `schematics_list` is READ_ONLY,
/// `schematics_import`/`schematics_reveal` are CONTROLLED_WRITE, `schematics_delete` is
/// DANGEROUS_WRITE (destructive removal).
@NotNullByDefault
public final class SchematicTool implements Tool {

    /// The on-disk extension every Litematica schematic uses (lower-cased for comparison).
    private static final String LITEMATIC_EXT = ".litematic";

    /// Cap on the number of schematics whose metadata is parsed and printed by `schematics_list`,
    /// bounding both the NBT-parsing work and the output size for a huge collection. The total count
    /// is always reported even when the detailed listing is truncated.
    private static final int MAX_LISTED = 60;

    /// Cap on how many real entry names a zero-match / ambiguous failure enumerates — enough for the
    /// model to spot a typo, bounded so a huge folder cannot flood the context. Mirrors
    /// {@link DeleteShaderTool}/{@link ManageDatapackTool}.
    private static final int MAX_NAMES = 12;

    /// Whether the `schematics_delete` path should prefer the OS recycle bin (recoverable) over a
    /// permanent delete; read live on each call (typically `aiSettings::isDeleteToRecycleBin`), the
    /// same {@link BooleanSupplier} the sibling delete tools receive from the `instance` facade.
    private final BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether `schematics_delete` should prefer the OS recycle bin (recoverable)
    ///                     over a permanent delete — supply `aiSettings::isDeleteToRecycleBin`, as the
    ///                     `instance` facade already does for {@link DeleteShaderTool} and friends.
    public SchematicTool(BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "manage_schematic";
    }

    @Override
    public String getDescription() {
        return "Manages the Litematica '.litematic' schematics stored in an instance's schematics/ folder — the "
                + "AI side of HMCL's schematics page. Common parameter: instance (optional, the instance id; "
                + "defaults to the currently selected instance). Select the operation with 'action':\n"
                + "- schematics_list (READ-ONLY): recursively lists the schematic files (and sub-folders) with each "
                + "file's name, author, on-disk size, dimensions and block/region counts. Takes no other parameters.\n"
                + "- schematics_import: copies a local '.litematic' file into the schematics/ folder. Parameter: "
                + "path (required, the absolute local path of the source '.litematic' file). Refuses to overwrite an "
                + "existing schematic of the same file name (rename the source first), and rejects a file that is not "
                + "a readable Litematica schematic.\n"
                + "- schematics_delete (DANGEROUS): removes one schematic file (or a sub-folder) from the schematics/ "
                + "folder. Parameter: name (required, the on-disk file/folder name — or a case-insensitive substring, "
                + "or a 'sub/dir/file' relative path — that matches exactly one entry, as shown by schematics_list). "
                + "The entry is moved to the system recycle bin when possible (recoverable), otherwise permanently "
                + "deleted. Fails if the name matches zero or more than one entry, so it never deletes the wrong one.\n"
                + "- schematics_reveal: opens a schematic (or the schematics/ folder) in the system file manager. "
                + "Parameter: name (optional; when given, the matched file is highlighted or the matched sub-folder is "
                + "opened; when omitted, the schematics/ folder itself is opened).\n"
                + "For schematics_delete/schematics_reveal pass the on-disk file NAME (from schematics_list), not the "
                + "in-game display name.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Route on the facade's action verb (or an explicit operation/mode fallback for standalone
        // use), exactly like ManageDatapackTool. Short aliases are accepted so a call that dropped the
        // 'schematics_' prefix still routes.
        String op = firstNonBlank(
                str(parameters.get("action")),
                str(parameters.get("operation")),
                str(parameters.get("mode"))).toLowerCase(Locale.ROOT);
        switch (op) {
            case "schematics_list":
            case "list":
                return list(parameters);
            case "schematics_import":
            case "import":
            case "add":
                return import_(parameters);
            case "schematics_delete":
            case "delete":
            case "remove":
                return delete(parameters);
            case "schematics_reveal":
            case "reveal":
            case "open":
                return reveal(parameters);
            default:
                return ToolResult.failure("Specify which schematics operation to perform: "
                        + "action=\"schematics_list\" (list), action=\"schematics_import\" (add a local .litematic), "
                        + "action=\"schematics_delete\" (remove one), or action=\"schematics_reveal\" (open in the "
                        + "file manager).");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // schematics_list (read-only)
    // ---------------------------------------------------------------------------------------------

    /// Recursively lists the schematics of the resolved instance, reproducing
    /// {@code SchematicsPage#loadAll} and its per-cell metadata read. See class doc.
    public ToolResult list(Map<String, Object> parameters) {
        Resolved resolved = resolve(parameters);
        if (resolved.failure != null) {
            return resolved.failure;
        }
        Path schematicsDir = resolved.schematicsDir;

        if (!Files.isDirectory(schematicsDir)) {
            return ToolResult.success("Instance '" + resolved.instance + "' has no schematics yet — the schematics "
                    + "folder does not exist:\n  " + schematicsDir + "\n"
                    + "Add one with instance(action=\"schematics_import\", path=\"...\").");
        }

        Collected collected = collect(schematicsDir);
        if (collected.files.isEmpty() && collected.dirs.isEmpty()) {
            return ToolResult.success("The schematics folder of instance '" + resolved.instance + "' is empty:\n  "
                    + schematicsDir + "\n"
                    + "Add one with instance(action=\"schematics_import\", path=\"...\").");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Schematics of instance '").append(resolved.instance).append("' (")
                .append(collected.files.size()).append(collected.files.size() == 1 ? " file" : " files");
        if (!collected.dirs.isEmpty()) {
            sb.append(", ").append(collected.dirs.size())
                    .append(collected.dirs.size() == 1 ? " sub-folder" : " sub-folders");
        }
        sb.append("):\n  folder: ").append(schematicsDir).append('\n');

        int shown = Math.min(collected.files.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            Entry entry = collected.files.get(i);
            sb.append("  - ").append(entry.relative);
            long size = -1;
            try {
                size = Files.size(entry.path);
            } catch (IOException ignored) {
                // size is best-effort; a file that vanished mid-listing simply shows no size.
            }
            if (size >= 0) {
                sb.append("  (").append(humanSize(size)).append(')');
            }
            sb.append('\n');

            LitematicFile meta = null;
            try {
                meta = LitematicFile.load(entry.path);
            } catch (Throwable e) {
                sb.append("      (could not read schematic metadata: ").append(shortMessage(e)).append(")\n");
            }
            if (meta != null) {
                appendMetadata(sb, entry.fileName, meta);
            }
        }
        if (collected.files.size() > shown) {
            sb.append("  ... and ").append(collected.files.size() - shown).append(" more file(s) not shown.\n");
        }

        if (!collected.dirs.isEmpty()) {
            sb.append("Sub-folders:\n");
            int shownDirs = Math.min(collected.dirs.size(), MAX_NAMES);
            for (int i = 0; i < shownDirs; i++) {
                sb.append("  - ").append(collected.dirs.get(i).relative).append("/\n");
            }
            if (collected.dirs.size() > shownDirs) {
                sb.append("  ... and ").append(collected.dirs.size() - shownDirs).append(" more folder(s).\n");
            }
        }

        return ToolResult.success(sb.toString().stripTrailing());
    }

    /// Appends the human-readable metadata lines for one schematic — the same fields the native
    /// info dialog shows, printed only when present.
    private static void appendMetadata(StringBuilder sb, String fileName, LitematicFile meta) {
        String displayName = meta.getName();
        if (displayName != null && !displayName.isBlank()
                && !"Unnamed".equals(displayName)
                && !stripExtension(fileName).equalsIgnoreCase(displayName.trim())) {
            sb.append("      name: ").append(displayName.trim()).append('\n');
        }
        String author = meta.getAuthor();
        if (author != null && !author.isBlank()) {
            sb.append("      author: ").append(author.trim()).append('\n');
        }
        Point3D size = meta.getEnclosingSize();
        if (size != null) {
            sb.append("      size: ").append((int) size.getX()).append(" x ")
                    .append((int) size.getY()).append(" x ").append((int) size.getZ()).append('\n');
        }
        List<String> stats = new ArrayList<>();
        if (meta.getTotalBlocks() > 0) {
            stats.add(meta.getTotalBlocks() + " blocks");
        }
        if (meta.getRegionCount() > 0) {
            stats.add(meta.getRegionCount() + (meta.getRegionCount() == 1 ? " region" : " regions"));
        }
        if (meta.getMinecraftDataVersion() > 0) {
            stats.add("MC data version " + meta.getMinecraftDataVersion());
        }
        if (!stats.isEmpty()) {
            sb.append("      ").append(String.join(", ", stats)).append('\n');
        }
    }

    // ---------------------------------------------------------------------------------------------
    // schematics_import (controlled write)
    // ---------------------------------------------------------------------------------------------

    /// Copies a local `.litematic` file into the instance's `schematics/` folder, reproducing
    /// {@code SchematicsPage#addFiles} with added validation (see class doc). Named `import_`
    /// because `import` is a reserved word.
    public ToolResult import_(Map<String, Object> parameters) {
        // 'path' is the primary param; reserve the routing keys ('action'/'operation'/'mode') plus
        // 'instance'/'name' so the sole-value fallback can never grab e.g. the action verb (a call
        // that carries ONLY 'action' must fail with "path required", not try to import a file named
        // "schematics_import") — the same hazard ManageDatapackTool sidesteps by using strict().
        String pathText = ToolParams.primary(parameters, "path",
                new String[]{"instance", "name", "action", "operation", "mode"},
                "source", "file", "from", "archive", "schematic");
        if (pathText.isEmpty()) {
            return ToolResult.failure("Parameter 'path' (the absolute local path of the source '.litematic' file) is "
                    + "required for schematics_import.");
        }

        Path source;
        try {
            source = Paths.get(pathText);
        } catch (Throwable e) {
            return ToolResult.failure("Invalid 'path' value '" + pathText + "': " + shortMessage(e));
        }
        if (!Files.exists(source)) {
            return ToolFailures.failure(
                    "The source file was not found: " + source,
                    ToolFailures.Retryable.YES,
                    "the path does not exist on this machine",
                    "Check the path and retry with an absolute path to an existing '.litematic' file");
        }
        if (!Files.isRegularFile(source)) {
            return ToolResult.failure("The source path is not a regular file (a folder cannot be imported as a "
                    + "schematic): " + source);
        }
        String sourceName = source.getFileName().toString();
        if (!sourceName.toLowerCase(Locale.ROOT).endsWith(LITEMATIC_EXT)) {
            return ToolFailures.failure(
                    "The source file is not a '.litematic' schematic: " + sourceName,
                    ToolFailures.Retryable.YES,
                    "only Litematica '.litematic' files can be imported into schematics/",
                    "Pass the path of a '.litematic' file");
        }

        // Validate the bytes actually parse as a Litematica schematic before copying, so a mistyped
        // path can never dump an unreadable file into schematics/. (The native page skips this and
        // only filters on the extension.)
        LitematicFile meta;
        try {
            meta = LitematicFile.load(source);
        } catch (Throwable e) {
            return ToolFailures.failure(
                    "The file '" + sourceName + "' could not be read as a Litematica schematic (" + shortMessage(e) + ")",
                    ToolFailures.Retryable.YES,
                    "the bytes are not a valid '.litematic' file (wrong or corrupt file)",
                    "Verify this is a real Litematica schematic, or pass a different '.litematic' file");
        }

        Resolved resolved = resolve(parameters);
        if (resolved.failure != null) {
            return resolved.failure;
        }
        Path schematicsDir = resolved.schematicsDir;
        Path target = schematicsDir.resolve(sourceName).normalize();
        // Defensive confinement: sourceName is a bare file name (getFileName()), so this always holds;
        // assert it anyway so a future change can never let a copy escape the schematics folder.
        if (!target.startsWith(schematicsDir.normalize()) || target.equals(schematicsDir.normalize())) {
            return ToolResult.failure("Refusing to import: the resolved target escapes the schematics folder.");
        }
        if (Files.exists(target)) {
            return ToolFailures.failure(
                    "A schematic named '" + sourceName + "' already exists in the schematics folder of instance '"
                            + resolved.instance + "'",
                    ToolFailures.Retryable.YES,
                    "importing would otherwise overwrite the existing schematic",
                    "Rename the source file, or delete the existing one first with "
                            + "instance(action=\"schematics_delete\", name=\"" + sourceName + "\")");
        }

        try {
            Files.createDirectories(schematicsDir);
            Files.copy(source, target);
        } catch (FileAlreadyExistsException e) {
            // Lost a race against a concurrent import of the same name.
            return ToolResult.failure("A schematic named '" + sourceName + "' already exists (it appeared while "
                    + "importing): " + target);
        } catch (IOException e) {
            return ToolResult.failure("Failed to import schematic '" + sourceName + "' into instance '"
                    + resolved.instance + "': " + shortMessage(e));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Imported schematic '").append(sourceName).append("' into instance '")
                .append(resolved.instance).append("'.\n");
        sb.append("  path: ").append(target).append('\n');
        appendMetadata(sb, sourceName, meta);
        return ToolResult.success(sb.toString().stripTrailing());
    }

    // ---------------------------------------------------------------------------------------------
    // schematics_delete (controlled write at the leaf; DANGEROUS at the facade)
    // ---------------------------------------------------------------------------------------------

    /// Removes one matched schematic file (or sub-folder), preferring the recycle bin when enabled.
    /// See class doc.
    public ToolResult delete(Map<String, Object> parameters) {
        // Reserve the routing keys so the sole-value fallback never mistakes the action verb for the
        // target name (see the note in import_).
        String query = ToolParams.primary(parameters, "name",
                new String[]{"instance", "action", "operation", "mode"}, "schematic", "file", "target");
        if (query.isEmpty()) {
            return ToolResult.failure("Parameter 'name' (the schematic file/folder name, or a substring of it) is "
                    + "required for schematics_delete.");
        }

        Resolved resolved = resolve(parameters);
        if (resolved.failure != null) {
            return resolved.failure;
        }
        Path schematicsDir = resolved.schematicsDir;
        if (!Files.isDirectory(schematicsDir)) {
            return ToolFailures.failure(
                    "Instance '" + resolved.instance + "' has no schematics folder, so there is nothing to delete",
                    ToolFailures.Retryable.NO,
                    "the schematics/ directory does not exist",
                    "Nothing to do; import a schematic first with instance(action=\"schematics_import\") if intended");
        }

        Collected collected = collect(schematicsDir);
        MatchOutcome outcome = matchOne(collected, query, resolved.instance);
        if (outcome.failure != null) {
            return outcome.failure;
        }
        Entry match = outcome.match;

        // Count what's inside a folder BEFORE deleting it — once FileTrash.delete has moved it to the
        // recycle bin (or removed it), match.path no longer exists and the count would always read 0.
        long inside = match.directory ? countLitematicsUnder(match.path) : -1;

        boolean recycled;
        try {
            recycled = FileTrash.delete(match.path, toRecycleBin.getAsBoolean());
        } catch (Throwable e) {
            // A locked schematic is almost always the instance being played right now — attribute it
            // through the shared GameResourceGuard-backed helper instead of a raw I/O message.
            return ToggleModTool.fileOperationFailure(resolved.instance,
                    "Deleting schematic '" + match.relative + "' from instance '" + resolved.instance + "' failed", e);
        }

        String kind;
        if (match.directory) {
            kind = "folder (" + inside + (inside == 1 ? " schematic" : " schematics") + " inside)";
        } else {
            kind = "schematic file";
        }
        return ToolResult.success((recycled
                ? "Moved schematic '" + match.relative + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted schematic '" + match.relative + "' from disk.\n")
                + "  instance: " + resolved.instance + "\n"
                + "  kind    : " + kind + "\n"
                + "  path    : " + match.path);
    }

    // ---------------------------------------------------------------------------------------------
    // schematics_reveal (side-effecting)
    // ---------------------------------------------------------------------------------------------

    /// Opens the matched schematic (or the `schematics/` folder itself when no name is given) in the
    /// system file manager. See class doc.
    public ToolResult reveal(Map<String, Object> parameters) {
        // 'name' is OPTIONAL here (absent = open the folder itself), so the sole-value fallback
        // grabbing the action verb would be especially harmful — reserve the routing keys so a bare
        // schematics_reveal call correctly resolves to "no name" and opens the folder.
        String query = ToolParams.primary(parameters, "name",
                new String[]{"instance", "action", "operation", "mode"}, "schematic", "file", "target");

        Resolved resolved = resolve(parameters);
        if (resolved.failure != null) {
            return resolved.failure;
        }
        Path schematicsDir = resolved.schematicsDir;

        // No name: open the schematics/ folder itself (FXUtils.openFolder creates it if missing,
        // exactly like OpenGameFolderTool / the native page's folder opening).
        if (query.isEmpty()) {
            try {
                FXUtils.openFolder(schematicsDir);
            } catch (Throwable e) {
                return ToolResult.failure("Failed to open the schematics folder " + schematicsDir + ": "
                        + shortMessage(e));
            }
            return ToolResult.success("Opened the schematics folder of instance '" + resolved.instance
                    + "' in the system file manager:\n  " + schematicsDir);
        }

        if (!Files.isDirectory(schematicsDir)) {
            return ToolFailures.failure(
                    "Instance '" + resolved.instance + "' has no schematics folder, so there is nothing to reveal",
                    ToolFailures.Retryable.NO,
                    "the schematics/ directory does not exist",
                    "Omit 'name' to open (and create) the schematics folder, or import a schematic first");
        }

        Collected collected = collect(schematicsDir);
        MatchOutcome outcome = matchOne(collected, query, resolved.instance);
        if (outcome.failure != null) {
            return outcome.failure;
        }
        Entry match = outcome.match;

        try {
            if (match.directory) {
                FXUtils.openFolder(match.path);
            } else {
                FXUtils.showFileInExplorer(match.path);
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to reveal '" + match.relative + "' in the file manager: "
                    + shortMessage(e));
        }
        return ToolResult.success("Revealed " + (match.directory ? "folder" : "schematic") + " '" + match.relative
                + "' of instance '" + resolved.instance + "' in the system file manager:\n  " + match.path);
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /// Resolves the selected profile, the target instance, and its (isolation-aware) `schematics/`
    /// directory in one place. On any failure {@link Resolved#failure} carries the ready
    /// {@link ToolResult} and the other fields are unset.
    private Resolved resolve(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return Resolved.fail(ToolResult.failure("No profile is currently selected: " + shortMessage(e)));
        }
        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return Resolved.fail(ToolResult.failure("Failed to load installed instances: " + shortMessage(e)));
            }
        }

        // 'name'/'path' are primary params of the write actions, so the instance resolver must NOT
        // accept the generic aliases (they would steal a value meant for those) — allowGenericAliases
        // = false, exactly like the sibling world/shader tools.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return Resolved.fail(target.failure());
        }
        String instance = target.name();

        Path schematicsDir;
        try {
            schematicsDir = repository.getSchematicsDirectory(instance).normalize();
        } catch (Throwable e) {
            return Resolved.fail(ToolResult.failure("Failed to resolve the schematics folder of '" + instance + "': "
                    + shortMessage(e)));
        }
        return Resolved.ok(instance, schematicsDir);
    }

    /// Recursively collects the schematic files and sub-folders under {@code root}, reproducing
    /// {@code SchematicsPage#loadAll}: recurse directories, treat every regular `*.litematic` file as
    /// a schematic, and tolerate a per-directory I/O error by skipping that directory. Results are
    /// sorted by relative path (case-insensitive) for a stable listing.
    private Collected collect(Path root) {
        List<Entry> files = new ArrayList<>();
        List<Entry> dirs = new ArrayList<>();
        collectInto(root, root, files, dirs);
        Comparator<Entry> byRelative = Comparator.comparing((Entry e) -> e.relative.toLowerCase(Locale.ROOT));
        files.sort(byRelative);
        dirs.sort(byRelative);
        return new Collected(files, dirs);
    }

    private void collectInto(Path dir, Path root, List<Entry> files, List<Entry> dirs) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    dirs.add(entryOf(path, root, true));
                    collectInto(path, root, files, dirs);
                } else if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(LITEMATIC_EXT)) {
                    files.add(entryOf(path, root, false));
                }
            }
        } catch (IOException e) {
            // Mirror SchematicsPage#loadAll, which logs and skips a directory it cannot read rather
            // than aborting the whole listing.
            LOG.warning("Failed to list schematics in " + dir, e);
        }
    }

    private static Entry entryOf(Path path, Path root, boolean directory) {
        String relative;
        try {
            relative = root.relativize(path).toString().replace('\\', '/');
        } catch (Throwable e) {
            relative = path.getFileName().toString();
        }
        return new Entry(path, directory, relative, path.getFileName().toString());
    }

    /// Matches the {@code query} against the collected entries (files first, then folders), returning
    /// the single match or a ready failure for the zero-match / ambiguous cases. Matching is a
    /// case-insensitive substring over each entry's file name, its `.litematic`-stripped base name,
    /// and its relative path — with an "exact name wins" refinement so `foo` deletes `foo.litematic`
    /// even when `foobar.litematic` also exists.
    private MatchOutcome matchOne(Collected collected, String query, String instance) {
        List<Entry> all = new ArrayList<>(collected.files);
        all.addAll(collected.dirs);

        String needle = query.toLowerCase(Locale.ROOT);
        List<Entry> matches = new ArrayList<>();
        List<Entry> exact = new ArrayList<>();
        for (Entry entry : all) {
            String fileLower = entry.fileName.toLowerCase(Locale.ROOT);
            String baseLower = stripExtension(entry.fileName).toLowerCase(Locale.ROOT);
            String relLower = entry.relative.toLowerCase(Locale.ROOT);
            if (fileLower.contains(needle) || baseLower.contains(needle) || relLower.contains(needle)) {
                matches.add(entry);
                if (fileLower.equals(needle) || baseLower.equals(needle) || relLower.equals(needle)) {
                    exact.add(entry);
                }
            }
        }

        // Exact name/path wins when it uniquely disambiguates a broader substring hit.
        if (exact.size() == 1) {
            return MatchOutcome.of(exact.get(0));
        }
        if (matches.isEmpty()) {
            return MatchOutcome.fail(ToolFailures.failure(
                    "No schematic matching '" + query + "' was found in the schematics folder of instance '"
                            + instance + "'",
                    ToolFailures.Retryable.YES,
                    "no schematic file/folder name contains this substring, which is usually a typo",
                    "schematics: " + describe(all) + "; use instance(action=\"schematics_list\") for the full list, "
                            + "or refine the 'name' query"));
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(query).append("' matches ").append(matches.size()).append(" entries:\n");
            int shown = Math.min(matches.size(), MAX_NAMES);
            for (int i = 0; i < shown; i++) {
                Entry e = matches.get(i);
                sb.append("  - ").append(e.relative).append(e.directory ? "/ (folder)" : "").append('\n');
            }
            if (matches.size() > shown) {
                sb.append("  ... and ").append(matches.size() - shown).append(" more.\n");
            }
            sb.append("Please refine 'name' to match exactly one entry (the full file name disambiguates).");
            return MatchOutcome.fail(ToolResult.failure(sb.toString().stripTrailing()));
        }
        return MatchOutcome.of(matches.get(0));
    }

    /// Counts the `.litematic` files anywhere under {@code dir}, for the delete report of a folder.
    private long countLitematicsUnder(Path dir) {
        List<Entry> files = new ArrayList<>();
        List<Entry> dirs = new ArrayList<>();
        collectInto(dir, dir, files, dirs);
        return files.size();
    }

    /// Enumerates up to {@link #MAX_NAMES} real entry names (files then folders) for a zero-match
    /// failure, appending a "(N more)" tail when truncated; an empty folder is reported explicitly.
    private static String describe(List<Entry> entries) {
        if (entries.isEmpty()) {
            return "(none — the schematics folder is empty)";
        }
        int shown = Math.min(entries.size(), MAX_NAMES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Entry e = entries.get(i);
            sb.append(e.relative).append(e.directory ? "/" : "");
        }
        if (entries.size() > shown) {
            sb.append(", ... (").append(entries.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    private static String stripExtension(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(LITEMATIC_EXT)
                ? fileName.substring(0, fileName.length() - LITEMATIC_EXT.length())
                : fileName;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
    }

    private static String shortMessage(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message.trim() : e.getClass().getSimpleName();
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

    /// One schematic file or sub-folder: its path, whether it is a folder, its relative path from the
    /// schematics root (forward-slashed, for display/matching), and its bare on-disk file name.
    private record Entry(Path path, boolean directory, String relative, String fileName) {
    }

    /// The recursively-collected schematics: `.litematic` files and sub-folders, each sorted by
    /// relative path.
    private record Collected(List<Entry> files, List<Entry> dirs) {
    }

    /// Outcome of {@link #matchOne}: exactly one of {@code match} / {@code failure} is set.
    private record MatchOutcome(@Nullable Entry match, @Nullable ToolResult failure) {
        static MatchOutcome of(Entry match) {
            return new MatchOutcome(match, null);
        }

        static MatchOutcome fail(ToolResult failure) {
            return new MatchOutcome(null, failure);
        }
    }

    /// Outcome of {@link #resolve}: either the instance + its schematics directory, or a failure.
    private record Resolved(@Nullable String instance, @Nullable Path schematicsDir, @Nullable ToolResult failure) {
        static Resolved ok(String instance, Path schematicsDir) {
            return new Resolved(instance, schematicsDir, null);
        }

        static Resolved fail(ToolResult failure) {
            return new Resolved(null, null, failure);
        }
    }
}
