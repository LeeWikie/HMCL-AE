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

import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.addon.resourcepack.ResourcePackFile;
import org.jackhuang.hmcl.addon.resourcepack.ResourcePackManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/// Installs a piece of content the user ALREADY HAS as a local file/folder into the matching
/// subfolder of a Minecraft instance — a mod into `mods`, a resource pack into `resourcepacks`,
/// or a shader pack into `shaderpacks`. This is the local-file counterpart to the download-based
/// `mods_install` / `resourcepacks_install` / `shaders_install` actions: it copies from disk and
/// never touches the network.
///
/// Each `kind` reuses the exact native "add a local file" path HMCL's own version pages drive, so
/// the same validation, copy semantics and isolation-aware destination directory apply:
/// - **mod** — [`HMCLGameRepository#getModManager(String)`] + [`ModManager#addMod(Path)`], the same
///   call [`org.jackhuang.hmcl.ui.versions.ModListPage#add()`] makes per selected file (rejects
///   anything that is not a `.jar`/`.litemod` via [`ModManager#isFileNameMod(Path)`], copies into
///   [`HMCLGameRepository#getModsDirectory(String)`], then parses the freshly added file);
/// - **resourcepack** — [`ResourcePackManager#importResourcePack(Path)`], the same call
///   [`org.jackhuang.hmcl.ui.versions.ResourcePackListPage#addFiles(java.util.List)`] makes
///   (rejects anything [`ResourcePackFile#isFileResourcePack(Path)`] does not accept — a `.zip`
///   archive, or a folder containing `pack.mcmeta` — and copies file OR unpacked folder into
///   [`HMCLGameRepository#getResourcePackDirectory(String)`]);
/// - **shader** — this codebase has NO manager class for shader packs (see {@link ToggleShaderTool}
///   / {@link DeleteShaderTool}, which treat the `shaderpacks` folder as plain `.zip` files and
///   unpacked folders), so — exactly like {@link InstallShaderTool}'s download path, which writes
///   straight into `<instance>/shaderpacks` — the file/folder is copied into
///   `getRunDirectory(id)/shaderpacks` via [`FileUtils#copyFile`] / [`FileUtils#copyDirectory`].
///
/// All three destinations go through [`HMCLGameRepository#getRunDirectory(String)`], so version
/// isolation (a per-instance running directory, or a modpack instance's own version root) is
/// honoured automatically — the content lands in the same folder the game will actually read.
///
/// Validation is up front and specific: the `kind` must be one of mod/resourcepack/shader, the
/// source must exist, and its type must match the `kind` (a `.zip` handed to `kind=mod`, or a
/// `.jar` handed to `kind=resourcepack`, is rejected with the accepted types rather than silently
/// copied into the wrong folder). A source that is already the exact destination file is reported
/// as already-installed instead of attempting a self-copy.
///
/// Permission level: it WRITES a new file/folder into the instance's content directory (and, like
/// the native add path, overwrites an entry of the same name) — CONTROLLED_WRITE at the merged
/// `instance` facade, mirroring the sibling `*_install` actions.
@NotNullByDefault
public final class InstallLocalContentTool implements Tool {

    /// The three content kinds this tool understands, each paired with the human label used in
    /// messages. The destination directory is resolved per-kind at install time (isolation-aware)
    /// rather than stored here.
    private enum Kind {
        MOD("mod"),
        RESOURCE_PACK("resource pack"),
        SHADER("shader pack");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    @Override
    public String getName() {
        return "install_local_content";
    }

    @Override
    public String getDescription() {
        return "Installs a piece of content you ALREADY HAVE as a local file into a Minecraft instance by "
                + "copying it into the matching folder. Use this for a file already on disk — NOT for downloading "
                + "from Modrinth/CurseForge (use mods_install / resourcepacks_install / shaders_install for that). "
                + "Parameters: kind (required, one of 'mod', 'resourcepack', 'shader'), "
                + "path (required, the absolute local path of the file — a mod '.jar'/'.litemod', a resource pack "
                + "'.zip' or an unpacked resource-pack folder containing pack.mcmeta, or a shader pack '.zip' or an "
                + "unpacked shader folder), "
                + "instance (optional, the target instance/version id; defaults to the currently selected instance). "
                + "The file's type must match 'kind' (a '.zip' is not a mod, a '.jar' is not a resource pack) or the "
                + "call is rejected. Copies into the instance's mods/resourcepacks/shaderpacks folder respecting "
                + "version isolation, overwriting an entry of the same name. WRITES a new file to disk. "
                + "Returns the installed file path.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // ---- kind ----
        Kind kind = parseKind(InstanceToolSupport.string(parameters, "kind"));
        if (kind == null) {
            String raw = InstanceToolSupport.string(parameters, "kind");
            return ToolFailures.failure(
                    "Missing or unknown 'kind'" + (raw != null ? " '" + raw + "'" : ""),
                    ToolFailures.Retryable.YES,
                    "kind selects which folder the file is copied into and how it is validated",
                    "pass kind=\"mod\", kind=\"resourcepack\" or kind=\"shader\"");
        }

        // ---- path ---- (also honour the generic 'query'/'file' aliases the runtime may route here)
        String pathStr = InstanceToolSupport.string(parameters, "path");
        if (pathStr == null) {
            pathStr = InstanceToolSupport.string(parameters, "file");
        }
        if (pathStr == null) {
            pathStr = InstanceToolSupport.string(parameters, "query");
        }
        if (pathStr == null) {
            return ToolFailures.failure(
                    "Missing required parameter 'path'",
                    ToolFailures.Retryable.YES,
                    "the tool needs the local file to copy in",
                    "pass path=<absolute path to the " + kind.label + " file/folder>");
        }

        Path source;
        try {
            source = Path.of(pathStr).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return ToolResult.failure("Parameter 'path' is not a valid file path: '" + pathStr + "' ("
                    + e.getMessage() + ").");
        }
        if (!Files.exists(source)) {
            return ToolFailures.failure(
                    "The file to install does not exist: " + source,
                    ToolFailures.Retryable.YES,
                    "nothing is at that path, usually a typo or a relative path resolved against the wrong folder",
                    "check the path (use an absolute path) and retry");
        }
        if (source.getFileName() == null) {
            return ToolResult.failure("Parameter 'path' has no file name component: " + source);
        }

        // ---- resolve profile / repository / instance ----
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

        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        return switch (kind) {
            case MOD -> installMod(repository, instance, source);
            case RESOURCE_PACK -> installResourcePack(repository, instance, source);
            case SHADER -> installShader(repository, instance, source);
        };
    }

    /// Copies a `.jar`/`.litemod` into the instance's `mods` folder via the native
    /// {@link ModManager#addMod(Path)} (the exact per-file call {@code ModListPage.add()} makes).
    private ToolResult installMod(HMCLGameRepository repository, String instance, Path source) {
        if (Files.isDirectory(source)) {
            return typeMismatch("mod", source, "a mod must be a '.jar' or '.litemod' file, not a folder");
        }
        if (!ModManager.isFileNameMod(source)) {
            return typeMismatch("mod", source,
                    "a mod must be a '.jar' or '.litemod' file (got '" + extensionOf(source) + "')");
        }

        Path destDir = repository.getModsDirectory(instance);
        Path dest = destDir.resolve(source.getFileName());
        if (isSamePath(source, dest)) {
            return ToolResult.success("That mod is already in the mods folder of instance '" + instance + "':\n"
                    + dest + "\nNothing to copy.");
        }
        boolean replaced = Files.exists(dest);

        try {
            repository.getModManager(instance).addMod(source);
        } catch (IllegalArgumentException e) {
            // Should not happen — isFileNameMod already gate-kept — but keep the manager's own contract intact.
            return typeMismatch("mod", source, e.getMessage());
        } catch (Throwable e) {
            // IOException from the copy, or an unchecked failure resolving a corrupt instance
            // (VersionNotFoundException etc.) — report cleanly rather than leaking a stack trace.
            return copyFailure("mod", instance, source, e);
        }
        return installed(Kind.MOD, instance, dest, replaced);
    }

    /// Imports a resource-pack `.zip` OR an unpacked pack folder into the instance's
    /// `resourcepacks` folder via the native {@link ResourcePackManager#importResourcePack(Path)}
    /// (the exact call {@code ResourcePackListPage.addFiles(...)} makes).
    private ToolResult installResourcePack(HMCLGameRepository repository, String instance, Path source) {
        if (!ResourcePackFile.isFileResourcePack(source)) {
            String reason = Files.isDirectory(source)
                    ? "a resource-pack folder must contain a 'pack.mcmeta' file at its top level"
                    : "a resource pack must be a '.zip' archive (got '" + extensionOf(source) + "')";
            return typeMismatch("resource pack", source, reason);
        }

        Path destDir = repository.getResourcePackDirectory(instance);
        Path dest = destDir.resolve(source.getFileName());
        if (isSamePath(source, dest)) {
            return ToolResult.success("That resource pack is already in the resourcepacks folder of instance '"
                    + instance + "':\n" + dest + "\nNothing to copy.");
        }
        boolean replaced = Files.exists(dest);

        try {
            new ResourcePackManager(repository, instance).importResourcePack(source);
        } catch (IllegalArgumentException e) {
            return typeMismatch("resource pack", source, e.getMessage());
        } catch (Throwable e) {
            // IOException from the copy, or an unchecked failure resolving a corrupt instance.
            return copyFailure("resource pack", instance, source, e);
        }
        return installed(Kind.RESOURCE_PACK, instance, dest, replaced);
    }

    /// Copies a shader-pack `.zip` OR an unpacked shader folder into the instance's `shaderpacks`
    /// folder. There is no shader manager in this codebase (see {@link ToggleShaderTool}), so this
    /// mirrors {@link InstallShaderTool}'s download destination and copies the file/folder directly.
    private ToolResult installShader(HMCLGameRepository repository, String instance, Path source) {
        boolean isFolder = Files.isDirectory(source);
        if (!isFolder && !extensionOf(source).equals("zip")) {
            return typeMismatch("shader pack", source,
                    "a shader pack must be a '.zip' archive or an unpacked folder (got '" + extensionOf(source) + "')");
        }

        Path destDir;
        try {
            destDir = repository.getRunDirectory(instance).resolve("shaderpacks");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the shaderpacks directory for instance '" + instance
                    + "': " + e.getMessage());
        }
        Path dest = destDir.resolve(source.getFileName());
        if (isSamePath(source, dest)) {
            return ToolResult.success("That shader pack is already in the shaderpacks folder of instance '"
                    + instance + "':\n" + dest + "\nNothing to copy.");
        }
        boolean replaced = Files.exists(dest);

        try {
            Files.createDirectories(destDir);
            if (isFolder) {
                FileUtils.copyDirectory(source, dest);
            } else {
                FileUtils.copyFile(source, dest);
            }
        } catch (Throwable e) {
            return copyFailure("shader pack", instance, source, e);
        }
        return installed(Kind.SHADER, instance, dest, replaced);
    }

    // ---- shared helpers ----

    private static ToolResult installed(Kind kind, String instance, Path dest, boolean replaced) {
        return ToolResult.success("Installed local " + kind.label + " into instance '" + instance + "'"
                + (replaced ? " (replaced an existing entry of the same name)" : "") + ":\n" + dest);
    }

    private static ToolResult typeMismatch(String kindLabel, Path source, @Nullable String reason) {
        return ToolFailures.failure(
                "The file '" + source.getFileName() + "' is not a valid " + kindLabel,
                ToolFailures.Retryable.YES,
                reason != null && !reason.isBlank() ? reason : null,
                "point 'path' at a matching file, or set 'kind' to the type that actually matches this file");
    }

    private static ToolResult copyFailure(String kindLabel, String instance, Path source, Throwable e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ToolResult.failure("Failed to install the " + kindLabel + " '" + source.getFileName()
                + "' into instance '" + instance + "': " + detail);
    }

    /// Whether two paths point at the same location on disk — the self-copy guard. Falls back to a
    /// normalized-path comparison when {@link Files#isSameFile} can't run (e.g. the destination does
    /// not exist yet); both inputs are already absolute+normalized at this point.
    private static boolean isSamePath(Path a, Path b) {
        try {
            if (Files.exists(a) && Files.exists(b)) {
                return Files.isSameFile(a, b);
            }
        } catch (IOException ignored) {
            // fall through to path comparison
        }
        return a.equals(b);
    }

    private static String extensionOf(Path path) {
        return FileUtils.getExtension(path).toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static Kind parseKind(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (k) {
            case "mod", "mods" -> Kind.MOD;
            case "resourcepack", "resourcepacks", "respack", "resource" -> Kind.RESOURCE_PACK;
            case "shader", "shaders", "shaderpack", "shaderpacks" -> Kind.SHADER;
            default -> null;
        };
    }
}
