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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// A controlled-write tool that downloads a mod from Modrinth (or CurseForge)
/// into the selected instance's `mods` directory.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`ModrinthRemoteAddonRepository#MODS`] /
///   [`CurseForgeRemoteAddonRepository#MODS`] to resolve the mod and its versions,
/// - [`RemoteAddon.IMod#loadVersions`] to list downloadable versions,
/// - [`FileDownloadTask`] (with the active [`DownloadProvider`]) to download
///   the selected file, blocking on the HMCL [`Task`] until completion.
///
/// The target `mods` directory is derived from the game directory supplied to
/// the constructor (typically the selected instance's run directory). It can be
/// overridden per-call with the `modsDir` parameter.
///
/// Permission level: CONTROLLED_WRITE. It writes a single new file into the
/// mods folder and never deletes or overwrites existing mods.
@NotNullByDefault
public final class InstallModTool implements Tool {

    /// Maximum time to wait for a download to finish, in seconds.
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    /// The game/run directory of the target instance. Its `mods` subfolder is
    /// the default download destination.
    private final Path gameDirectory;

    /// Creates a tool that installs mods into `{gameDirectory}/mods`.
    ///
    /// @param gameDirectory the run directory of the target instance, e.g. the
    ///                      result of `repository.getRunDirectory(versionId)`
    public InstallModTool(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    @Override
    public String getName() {
        return "install_mod";
    }

    @Override
    public String getDescription() {
        return "Downloads a mod into the selected instance's mods folder. "
                + "Parameters: id (string: the Modrinth/CurseForge project id or slug from "
                + "search(action=\"mods\") — required unless 'ids' is given), "
                + "ids (string array, optional: several project ids/slugs to install in one call; each "
                + "reuses the same source/loader/gameVersion/version options and is reported separately. "
                + "Dependencies are NOT auto-installed), "
                + "source (string, optional: \"modrinth\" (default) or \"curseforge\"), "
                + "loader (string, optional: fabric/forge/neoforge/quilt - filters to a matching version), "
                + "gameVersion (string, optional Minecraft version like \"1.20.1\" - filters to a matching version), "
                + "version (string, optional: a specific version name/number; otherwise the newest matching version is used), "
                + "modsDir (string, optional: absolute path override for the destination mods folder). "
                + "Returns the installed file name and path, plus (best-effort) a note of any REQUIRED "
                + "dependencies still missing, OPTIONAL dependencies you may also want, and INCOMPATIBLE "
                + "mods already installed — check that note before assuming the instance will run. This "
                + "does NOT auto-install dependencies; call install_mod again for each one reported missing. "
                + "This writes a new file to disk.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Batch path: an optional `ids` array installs several already-known project ids in one
        // call, reusing the exact single-id logic per id and aggregating the per-id receipts. It
        // does NOT auto-install dependencies — each per-id result still reports what's missing.
        List<String> ids = extractIds(parameters);
        if (ids != null) {
            return executeBatch(ids, parameters);
        }

        // Fall back to "query" since the tool schema currently advertises a single param.
        String id = extractString(parameters, "id", extractString(parameters, "query", null));
        if (id == null || id.isBlank()) {
            return ToolResult.failure("Missing required parameter: id (the mod slug/project id from "
                    + "search(action=\"mods\"))");
        }
        return installSingle(id, parameters);
    }

    /// Installs a batch of already-known project ids by delegating to {@link #installSingle} for
    /// each — every id therefore gets the exact same live-target resolution, dependency reporting
    /// and download the single-id path performs — then aggregates the per-id receipts.
    ///
    /// Deliberately does NOT auto-install dependencies: recursive dependency installation needs
    /// dedup + cycle detection + a depth cap and is left as a separate feature. The per-id
    /// dependency notes still tell the caller exactly what to install next.
    private ToolResult executeBatch(List<String> ids, Map<String, Object> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("Batch mod install: ").append(ids.size()).append(" project id(s) requested.\n");
        int ok = 0;
        int failed = 0;
        for (String id : ids) {
            ToolResult result = installSingle(id, parameters);
            boolean success = result.isSuccess();
            if (success) {
                ok++;
            } else {
                failed++;
            }
            sb.append('\n').append(success ? "[OK] " : "[FAILED] ").append(id).append('\n');
            String body = success ? result.getOutput() : result.getError();
            sb.append(indentLines(body)).append('\n');
        }
        sb.append("\nSummary: ").append(ok).append(" installed / ").append(failed)
                .append(" failed of ").append(ids.size())
                .append(". Dependencies were NOT auto-installed — review each result's dependency "
                        + "note and install any missing REQUIRED dependency with another call.");
        String text = sb.toString().trim();
        // Mirror the single-id contract: a batch where at least one id installed is a success
        // (per-id failures are still listed); only a wholesale failure surfaces as a failure.
        return ok > 0 ? ToolResult.success(text) : ToolResult.failure(text);
    }

    /// Installs a single project id. This is the original single-id body verbatim; both the
    /// single-call path and each iteration of {@link #executeBatch} run through it unchanged.
    private ToolResult installSingle(String id, Map<String, Object> parameters) {
        String source = extractString(parameters, "source", "modrinth");
        String loaderStr = extractString(parameters, "loader", null);
        String gameVersion = extractString(parameters, "gameVersion", null);
        String versionName = extractString(parameters, "version", null);
        String modsDirOverride = extractString(parameters, "modsDir", null);

        RemoteAddonRepository repository;
        if ("curseforge".equalsIgnoreCase(source)) {
            if (!CurseForgeRemoteAddonRepository.isAvailable()) {
                return ToolResult.failure("CurseForge is not available (no API key configured). Use source=\"modrinth\".");
            }
            repository = CurseForgeRemoteAddonRepository.MODS;
        } else {
            repository = ModrinthRemoteAddonRepository.MODS;
        }

        ModLoaderType loader = parseLoader(loaderStr);
        if (loaderStr != null && loader == null) {
            return ToolResult.failure("Unknown loader '" + loaderStr + "'. Use fabric, forge, neoforge or quilt.");
        }

        // Determine the destination folder BEFORE any network call, so a bad/ambiguous target fails
        // fast instead of after a wasted mod-resolution round trip. Priority: explicit modsDir
        // override > the target instance resolved LIVE at call time.
        //
        // T20/ST-1: the default target must NOT come from the turn-start cached `gameDirectory` — if
        // the user switched the selected instance mid-conversation, that cached path is stale and the
        // mod would be installed into the wrong (old) instance with no error. Resolve the target fresh
        // via the shared resolveInstance range: an explicit 'instance' wins, else the CURRENTLY
        // selected instance (a named-but-missing one fails with the unified envelope listing the real
        // names — T4). `gameDirectory` is only a last resort when the profile/repository system is
        // unavailable, so a stale cache can never silently win.
        Path modsDir;
        // Captured alongside modsDir when the target instance is resolved normally, so the
        // dependency check below can tell which mods are already installed. Stays null on the
        // modsDir override / fallback paths, where the dependency check simply reports every
        // dependency (safe over-reporting, never a false "you already have it").
        ModManager modManager = null;
        if (modsDirOverride != null) {
            modsDir = Path.of(modsDirOverride).toAbsolutePath().normalize();
        } else {
            try {
                var repo = org.jackhuang.hmcl.setting.Profiles.getSelectedProfile().getRepository();
                InstanceToolSupport.ResolvedInstance resolved =
                        InstanceToolSupport.resolveInstance(repo, parameters, false);
                if (resolved.failure() != null) {
                    return resolved.failure();
                }
                modsDir = repo.getRunDirectory(resolved.name()).resolve("mods");
                modManager = repo.getModManager(resolved.name());
            } catch (Throwable t) {
                // Profile/repository unavailable (an atypical embedding): fall back to the run
                // directory captured when this tool was last retargeted rather than failing outright.
                modsDir = gameDirectory.resolve("mods");
            }
        }

        // Resolve the addon and pick the best matching version through the shared,
        // timeout-guarded helper (it wraps getModById + loadVersions each in a 60s
        // timeout and applies the standard game-version / version-id selection).
        RemoteAddon.Version selected;
        try {
            selected = ContentToolSupport.resolveVersion(repository, id, gameVersion, versionName, loader);
        } catch (Exception e) {
            return ToolResult.failure("Failed to resolve mod '" + id + "': " + AbstractContentSearchTool.messageOf(e));
        }

        RemoteAddon.File file = selected.file();
        if (file == null || file.url() == null || file.url().isBlank()) {
            return ToolResult.failure("Selected version '" + selected.name() + "' has no downloadable file.");
        }

        // Walkthrough P2: the model repeatedly installed a mod, then only found out it was missing
        // a hard dependency after the instance crashed (3x in a row). Surface REQUIRED/OPTIONAL/
        // INCOMPATIBLE dependency info up front instead of silently dropping it, so the caller can
        // react before launching. This is a report only — it does NOT recursively install missing
        // dependencies (that needs dedup + cycle detection + a depth cap, left for a follow-up).
        String dependencyNotice = describeDependencies(selected, modManager);

        Path dest = modsDir.resolve(file.filename());
        // Only skip when a NON-EMPTY file is already there — a 0-byte / truncated leftover from a
        // failed earlier download must be re-fetched, not mistaken for a good install.
        try {
            if (Files.exists(dest) && Files.size(dest) > 0) {
                return ToolResult.success(withNotice(
                        "Mod file already present, skipping download: " + dest, dependencyNotice));
            }
        } catch (java.io.IOException ignored) {
            // size unreadable — fall through and re-download to be safe
        }

        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            return ToolResult.failure("Failed to create mods directory " + modsDir + ": " + e.getMessage());
        }

        // Build the download task and run it through the shared helper, which retries with
        // backoff, switches download source on failure, and cancels the task executor on
        // timeout so a timed-out download cannot orphan-run.
        try {
            ContentToolSupport.runDownloadWithFallback(provider -> {
                FileDownloadTask task = new FileDownloadTask(
                        provider.injectURLWithCandidates(file.url()), dest, file.getIntegrityCheck());
                task.setName(selected.name());
                return task;
            }, DOWNLOAD_TIMEOUT_SECONDS, "Download");
        } catch (Exception e) {
            if (ContentToolSupport.isNetworkError(e)) {
                return ToolResult.failure(ContentToolSupport.networkErrorAdvice(e));
            }
            return ToolResult.failure("Download failed for '" + id + "' version '"
                    + selected.name() + "': " + AbstractContentSearchTool.messageOf(e));
        }

        return ToolResult.success(withNotice("Installed mod '" + id + "' version '" + selected.name() + "'"
                + (selected.version() != null ? " (" + selected.version() + ")" : "")
                + " into:\n  " + dest, dependencyNotice));
    }

    /// Best-effort dependency report for `version`, checked against the mods already installed in
    /// `modManager` (may be `null` if the target instance couldn't be resolved, in which case every
    /// dependency is reported since there's nothing to compare against).
    ///
    /// The "already installed" match compares a dependency's repository project id/slug against the
    /// technical mod ids ([`LocalModFile#getId`]) of the locally installed mods. These are different
    /// namespaces in general (a project slug isn't always the mod's technical id), but they commonly
    /// coincide (e.g. `fabric-api`, `cloth-config`) and this is only used to avoid re-nagging about a
    /// dependency that's plainly already there — a false negative here just means it gets listed
    /// even though it happens to already be installed, never the reverse.
    ///
    /// Returns `null` when there's nothing worth reporting.
    @Nullable
    private static String describeDependencies(RemoteAddon.Version version, @Nullable ModManager modManager) {
        List<RemoteAddon.Dependency> dependencies = version.dependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return null;
        }

        Set<String> installedIds = Set.of();
        if (modManager != null) {
            try {
                installedIds = modManager.getLocalFiles().stream()
                        .map(f -> f.getId().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
            } catch (Throwable ignored) {
                // Best-effort only: fall back to reporting every dependency as not (yet) installed.
            }
        }

        LinkedHashSet<String> missingRequired = new LinkedHashSet<>();
        LinkedHashSet<String> missingOptional = new LinkedHashSet<>();
        LinkedHashSet<String> incompatiblePresent = new LinkedHashSet<>();
        for (RemoteAddon.Dependency dep : dependencies) {
            String depId = dep.getId();
            if (depId == null || depId.isBlank()) {
                continue;
            }
            boolean alreadyInstalled = installedIds.contains(depId.toLowerCase(Locale.ROOT));
            switch (dep.getType()) {
                case REQUIRED -> {
                    if (!alreadyInstalled) {
                        missingRequired.add(depId);
                    }
                }
                case OPTIONAL -> {
                    if (!alreadyInstalled) {
                        missingOptional.add(depId);
                    }
                }
                case INCOMPATIBLE -> {
                    if (alreadyInstalled) {
                        incompatiblePresent.add(depId);
                    }
                }
                default -> {
                    // TOOL/INCLUDE/EMBEDDED/BROKEN: not actionable for the caller here, skip.
                }
            }
        }

        if (missingRequired.isEmpty() && missingOptional.isEmpty() && incompatiblePresent.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (!missingRequired.isEmpty()) {
            sb.append("Still needs installing (required dependencies): ")
                    .append(String.join(", ", missingRequired)).append('\n');
        }
        if (!missingOptional.isEmpty()) {
            sb.append("Optional dependencies (not required): ")
                    .append(String.join(", ", missingOptional)).append('\n');
        }
        if (!incompatiblePresent.isEmpty()) {
            sb.append("Warning: incompatible with already-installed mod(s): ")
                    .append(String.join(", ", incompatiblePresent)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String withNotice(String message, @Nullable String notice) {
        if (notice == null || notice.isBlank()) {
            return message;
        }
        return message + "\n" + notice;
    }

    @Nullable
    private static ModLoaderType parseLoader(@Nullable String loader) {
        if (loader == null) {
            return null;
        }
        return switch (loader.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
            case "fabric" -> ModLoaderType.FABRIC;
            case "forge" -> ModLoaderType.FORGE;
            case "neoforge" -> ModLoaderType.NEO_FORGE;
            case "quilt" -> ModLoaderType.QUILT;
            case "cleanroom" -> ModLoaderType.CLEANROOM;
            case "liteloader" -> ModLoaderType.LITE_LOADER;
            case "legacyfabric" -> ModLoaderType.LEGACY_FABRIC;
            default -> null;
        };
    }

    @Nullable
    private static String extractString(Map<String, Object> params, String key, @Nullable String fallback) {
        Object val = params.get(key);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return fallback;
    }

    /// Extracts the optional `ids` batch parameter as an ordered, de-duplicated list of non-blank
    /// project ids. Accepts a JSON array (parsed as a `List`), a raw `Object[]`, or — leniently — a
    /// single string with several ids separated by commas/whitespace. Returns `null` when the
    /// parameter is absent or yields nothing usable, so the caller falls back to the single-id path.
    @Nullable
    private static List<String> extractIds(Map<String, Object> params) {
        Object raw = params.get("ids");
        if (raw == null) {
            return null;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                addId(out, o);
            }
        } else if (raw instanceof Object[] arr) {
            for (Object o : arr) {
                addId(out, o);
            }
        } else if (raw instanceof String s) {
            for (String part : s.split("[,\\s]+")) {
                addId(out, part);
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private static void addId(Set<String> out, @Nullable Object value) {
        if (value != null) {
            String s = value.toString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }

    /// Indents every line of a per-id receipt by two spaces so it reads as a nested block under its
    /// `[OK]`/`[FAILED]` header in the aggregated batch output.
    private static String indentLines(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(lines[i]);
        }
        return sb.toString();
    }
}
