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
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.game.GameAssetDownloadTask;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// Instance maintenance / cleanup for the selected profile — the AI-facing counterpart of HMCL's
/// native 版本管理 (Version → Management) popup menu ({@code VersionPage} "清理垃圾文件 / 重新下载资源索引
/// / 清除资源文件 / 清除依赖库"). A {@code scope} parameter selects one of four native management
/// actions, each delegating to the exact underlying repository/download call the native menu uses
/// so there is no second, fragile cleanup implementation:
///
/// - `clean_junk` → {@link HMCLGameRepository#clean(String)} — the same call behind
///   {@code Versions.cleanVersion}: deletes the whole {@code logs/} and {@code crash-reports/}
///   directories from BOTH the shared base directory and the instance's run directory (this is the
///   native "清理垃圾文件" behaviour, and it removes ALL logs including {@code latest.log}; the gentler
///   {@code clean_logs} action keeps the newest archives). Low risk — diagnostic files only.
/// - `redownload_assets` → a {@link GameAssetDownloadTask} with {@code DOWNLOAD_INDEX_FORCIBLY},
///   the same task behind {@code Versions.updateGameAssets}: force-refreshes the asset index and
///   re-fetches any missing/corrupt asset objects. Non-destructive, uses the network. Runs through
///   {@link ContentToolSupport#runDownloadWithFallback} so it honours the synchronous tool
///   contract, retries with backoff and switches download mirror on failure (same helper as
///   {@link DownloadJavaTool}).
/// - `clear_resources` → deletes the shared {@code assets} directory plus this instance's legacy
///   {@code resources} directory, mirroring {@code VersionPage.clearAssets} (same
///   {@link FileUtils#deleteDirectoryQuietly} calls). DANGEROUS: forces HMCL to re-download all
///   game assets on next launch and affects EVERY instance sharing the base directory.
/// - `clear_libraries` → deletes the shared {@code libraries} directory, mirroring
///   {@code VersionPage.clearLibraries}. DANGEROUS: forces HMCL to re-download libraries on next
///   launch/install and affects EVERY instance.
///
/// Safety, on top of the confirm gate:
/// - the two `clear_*` scopes wipe shared base-directory content every running game depends on, so
///   they are refused while ANY instance HMCL launched is still running
///   ({@link GameResourceGuard#checkInstanceNotRunning(String)} across the profile); `clean_junk`
///   is refused while the TARGET instance is running (a live game holds {@code latest.log} open, so
///   the delete would otherwise fail half-way);
/// - the two `clear_*` scopes additionally require an explicit {@code confirm=true} (as
///   {@link DeleteInstanceTool} does) — without it nothing is deleted and the tool reports what
///   would be removed, with sizes, and how to confirm.
///
/// Called with no {@code scope}, it only REPORTS the reclaimable sizes and what each scope does.
///
/// Permission level: worst case DELETES shared game data ({@code clear_resources} /
/// {@code clear_libraries}); `redownload_assets` WRITES + uses the network; `clean_junk` deletes
/// diagnostic files; the no-scope report is READ-ONLY.
@NotNullByDefault
public final class InstanceMaintenanceTool implements Tool {

    /// After a `clear_resources` the very next asset re-download pulls the full asset set (can be
    /// hundreds of MB), so allow a generous window; a background job can still cancel it.
    private static final int ASSETS_TIMEOUT_SECONDS = 1200;

    @Override
    public String getName() {
        return "instance_maintenance";
    }

    @Override
    public String getDescription() {
        return "Instance maintenance and cleanup for the selected profile, mirroring HMCL's native Version "
                + "Management menu. Parameter 'scope' selects the operation; 'instance' (optional) defaults to the "
                + "currently selected instance. Scopes:\n"
                + "- clean_junk: delete the 'logs/' and 'crash-reports/' directories (ALL log and crash-report files, "
                + "including latest.log) from BOTH the shared base .minecraft directory and this instance's run "
                + "directory. Diagnostic files only — no saves/mods/configs are touched. For a gentler clean that keeps "
                + "the most recent logs, use the clean_logs action instead. Refused while the instance is running.\n"
                + "- redownload_assets: force re-download of the game asset index and any missing/corrupt asset objects "
                + "for this instance (uses the network). Non-destructive.\n"
                + "- clear_resources: DANGEROUS — permanently delete the shared 'assets' directory and this instance's "
                + "legacy 'resources' directory; HMCL re-downloads game assets on next launch. Affects EVERY instance "
                + "sharing this base directory. Requires confirm=true and is refused while any game is running.\n"
                + "- clear_libraries: DANGEROUS — permanently delete the shared 'libraries' directory; HMCL re-downloads "
                + "libraries on next launch/install. Affects EVERY instance. Requires confirm=true and is refused while "
                + "any game is running.\n"
                + "Call with no 'scope' to only REPORT the reclaimable sizes and what each scope does. "
                + "Reuses HMCL's native cleanup paths.";
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
        if (!repository.isLoaded()) {
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }

        // 'instance' is the only instance key we accept: 'scope'/'confirm' are the other parameters and
        // must not be stolen by the resolver's generic 'query' fallback (scope reads 'query' itself below).
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        Object scopeObj = parameters.get("scope");
        if (scopeObj == null || String.valueOf(scopeObj).trim().isEmpty()) {
            scopeObj = parameters.get("query");
        }
        String scope = scopeObj == null ? "" : scopeObj.toString().trim().toLowerCase(Locale.ROOT);

        if (scope.isEmpty()) {
            return report(repository, instance);
        }

        return switch (scope) {
            case "clean_junk" -> cleanJunk(repository, instance);
            case "redownload_assets" -> redownloadAssets(profile, repository, instance);
            case "clear_resources" -> clearResources(repository, instance, parameters);
            case "clear_libraries" -> clearLibraries(repository, instance, parameters);
            default -> ToolFailures.failure(
                    "Unknown maintenance scope '" + scope + "'",
                    ToolFailures.Retryable.YES,
                    "the scope name is not one of the four supported operations",
                    "use one of: clean_junk, redownload_assets, clear_resources, clear_libraries "
                            + "(or omit 'scope' to see a report of what each does and how much space it would free)");
        };
    }

    // ------------------------------------------------------------------
    // scope: report (no scope given) — read-only
    // ------------------------------------------------------------------

    private ToolResult report(HMCLGameRepository repository, String instance) {
        Path base = repository.getBaseDirectory();
        Path runDir = repository.getRunDirectory(instance);

        // When isolation is OFF (the default), runDir == base, so the base and run-dir log/crash-report
        // directories are the SAME directory — sum over distinct paths so the estimate isn't ~2x too big.
        long junk = sumDistinctDirs(runDir.resolve("logs"), runDir.resolve("crash-reports"),
                base.resolve("logs"), base.resolve("crash-reports"));
        long resources = sumDistinctDirs(base.resolve("assets"), runDir.resolve("resources"));
        long libraries = dirSize(base.resolve("libraries"));

        StringBuilder sb = new StringBuilder();
        sb.append("Maintenance options for instance '").append(instance).append("':\n");
        sb.append("- clean_junk: ~").append(human(junk)).append(" — deletes logs/ and crash-reports/ (ALL log and "
                + "crash-report files, incl. latest.log) from the base directory and this instance's run directory. "
                + "Diagnostic files only; no saves/mods/configs. (clean_logs is the gentler option that keeps recent "
                + "logs.)\n");
        sb.append("- redownload_assets: force-refreshes the asset index and re-fetches missing/corrupt asset objects "
                + "(network, non-destructive).\n");
        sb.append("- clear_resources: ~").append(human(resources)).append(" — DANGEROUS: permanently deletes the shared "
                + "'assets' directory and this instance's 'resources' directory; assets are re-downloaded on next "
                + "launch. Affects every instance sharing this base directory. Needs confirm=true.\n");
        sb.append("- clear_libraries: ~").append(human(libraries)).append(" — DANGEROUS: permanently deletes the shared "
                + "'libraries' directory; libraries are re-downloaded on next launch/install. Affects every instance. "
                + "Needs confirm=true.\n");
        sb.append("Sizes are approximate (current on-disk usage). Re-invoke with scope=<one of the above> to act; the "
                + "clear_* scopes also need confirm=true and are refused while a game is running.");
        return ToolResult.success(sb.toString());
    }

    // ------------------------------------------------------------------
    // scope: clean_junk
    // ------------------------------------------------------------------

    private ToolResult cleanJunk(HMCLGameRepository repository, String instance) {
        // A live game holds latest.log open, so the whole-directory delete would fail half-way — refuse
        // up front with the shared occupancy envelope (points the model at game(action="stop")).
        String rejection = GameResourceGuard.checkInstanceNotRunning(instance);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        Path base = repository.getBaseDirectory();
        Path runDir = repository.getRunDirectory(instance);
        // See report(): dedup base/run-dir paths so a non-isolated instance isn't counted twice.
        long freed = sumDistinctDirs(runDir.resolve("logs"), runDir.resolve("crash-reports"),
                base.resolve("logs"), base.resolve("crash-reports"));

        try {
            // Native path (Versions.cleanVersion → repository.clean): deletes logs/ + crash-reports/ from
            // both the base directory and the instance run directory.
            repository.clean(instance);
        } catch (IOException e) {
            return ToolFailures.failure(
                    "Failed to fully clean junk files of instance '" + instance + "': " + e.getMessage()
                            + " — some files may be held open (e.g. a game launched outside HMCL)",
                    ToolFailures.Retryable.LATER,
                    "an open log/crash-report file cannot be deleted while a process holds it",
                    "Ask the user to close any game using this instance, then retry scope=\"clean_junk\"");
        }

        return ToolResult.success("Cleaned junk files of instance '" + instance + "'.\n"
                + "Deleted the logs/ and crash-reports/ directories (all log and crash-report files, including "
                + "latest.log) from both the shared base directory and this instance's run directory.\n"
                + "Freed approximately " + human(freed) + ".\n"
                + "Note: use the clean_logs action instead if you want to keep the most recent logs.");
    }

    // ------------------------------------------------------------------
    // scope: redownload_assets
    // ------------------------------------------------------------------

    private ToolResult redownloadAssets(Profile profile, HMCLGameRepository repository, String instance) {
        final Version version;
        try {
            version = repository.getVersion(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Could not read the version metadata of instance '" + instance + "': "
                    + e.getMessage());
        }

        try {
            // Same task as Versions.updateGameAssets (GameAssetDownloadTask + DOWNLOAD_INDEX_FORCIBLY),
            // but driven through the shared download helper so it blocks for the synchronous tool contract,
            // retries with backoff and switches mirror on failure. getDependency(provider) rebuilds the
            // dependency manager for each candidate source so a retry genuinely switches download source.
            ContentToolSupport.runDownloadWithFallback(
                    provider -> new GameAssetDownloadTask(profile.getDependency(provider), version,
                            GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY, true),
                    ASSETS_TIMEOUT_SECONDS, "Asset re-download for '" + instance + "'");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("The asset re-download for '" + instance + "' was cancelled.");
        } catch (Throwable e) {
            if (ContentToolSupport.isNetworkError(e)) {
                return ToolResult.failure(ContentToolSupport.networkErrorAdvice(e));
            }
            return ToolResult.failure("Failed to re-download the game assets for '" + instance + "': " + e.getMessage());
        }

        return ToolResult.success("Re-downloaded the game asset index for instance '" + instance + "' and re-fetched "
                + "any missing or corrupt asset objects. This is the same operation as the native \"重新下载资源索引 / "
                + "Update Game Assets\" management action; nothing was deleted.");
    }

    // ------------------------------------------------------------------
    // scope: clear_resources
    // ------------------------------------------------------------------

    private ToolResult clearResources(HMCLGameRepository repository, String instance, Map<String, Object> parameters) {
        Path assetsDir = repository.getBaseDirectory().resolve("assets");
        Path resourcesDir = repository.getRunDirectory(instance).resolve("resources");
        long size = dirSize(assetsDir) + dirSize(resourcesDir);

        if (!InstanceToolSupport.bool(parameters, "confirm")) {
            return ToolFailures.failure(
                    "Not confirmed: this will PERMANENTLY delete the shared assets directory (" + assetsDir + ") and "
                            + "this instance's resources directory (" + resourcesDir + "), about " + human(size)
                            + ", forcing HMCL to re-download game assets on next launch and affecting every instance "
                            + "sharing this base directory",
                    ToolFailures.Retryable.YES,
                    "this destructive, base-directory-wide delete needs explicit confirmation",
                    "re-invoke with scope=\"clear_resources\", confirm=true to proceed (use redownload_assets afterwards "
                            + "if you want to fetch the assets back immediately)");
        }

        String rejection = checkNoInstanceRunning(repository, instance);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        // Native path (VersionPage.clearAssets): FileUtils.deleteDirectoryQuietly on both directories.
        boolean assetsOk = FileUtils.deleteDirectoryQuietly(assetsDir);
        boolean resourcesOk = FileUtils.deleteDirectoryQuietly(resourcesDir);
        if (!assetsOk || !resourcesOk) {
            return ToolFailures.failure(
                    "Partially cleared resources of the base directory — some files could not be deleted (likely held "
                            + "open by a running process)",
                    ToolFailures.Retryable.LATER,
                    "a file in use cannot be deleted until the process holding it exits",
                    "Ask the user to close any running game, then retry scope=\"clear_resources\", confirm=true");
        }

        return ToolResult.success("Cleared game resources: permanently deleted the shared 'assets' directory and this "
                + "instance's 'resources' directory (freed about " + human(size) + "). HMCL will re-download the assets "
                + "on the next launch; run scope=\"redownload_assets\" now to fetch them back immediately. This affects "
                + "every instance sharing this base directory.");
    }

    // ------------------------------------------------------------------
    // scope: clear_libraries
    // ------------------------------------------------------------------

    private ToolResult clearLibraries(HMCLGameRepository repository, String instance, Map<String, Object> parameters) {
        Path librariesDir = repository.getBaseDirectory().resolve("libraries");
        long size = dirSize(librariesDir);

        if (!InstanceToolSupport.bool(parameters, "confirm")) {
            return ToolFailures.failure(
                    "Not confirmed: this will PERMANENTLY delete the shared libraries directory (" + librariesDir + "), "
                            + "about " + human(size) + ", forcing HMCL to re-download libraries on the next launch or "
                            + "install and affecting every instance in this base directory",
                    ToolFailures.Retryable.YES,
                    "this destructive, base-directory-wide delete needs explicit confirmation",
                    "re-invoke with scope=\"clear_libraries\", confirm=true to proceed");
        }

        String rejection = checkNoInstanceRunning(repository, instance);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        // Native path (VersionPage.clearLibraries): FileUtils.deleteDirectoryQuietly on the libraries dir.
        boolean ok = FileUtils.deleteDirectoryQuietly(librariesDir);
        if (!ok) {
            return ToolFailures.failure(
                    "Partially cleared the libraries directory — some files could not be deleted (likely held open by "
                            + "a running process)",
                    ToolFailures.Retryable.LATER,
                    "a library jar in use cannot be deleted until the process holding it exits",
                    "Ask the user to close any running game, then retry scope=\"clear_libraries\", confirm=true");
        }

        return ToolResult.success("Cleared dependency libraries: permanently deleted the shared 'libraries' directory "
                + "(freed about " + human(size) + "). HMCL will re-download the required libraries automatically the "
                + "next time an instance is launched or a loader is installed. This affects every instance in this base "
                + "directory.");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /// Returns the occupancy rejection of the first instance HMCL is still tracking as running, or
    /// {@code null} when none is. The two {@code clear_*} scopes delete base-directory content shared
    /// by every instance, so a running game anywhere in the profile is a hazard — not just the resolved
    /// one. If the version list cannot be enumerated the check degrades to the resolved instance alone
    /// (the confirm gate already captured explicit intent, so a best-effort probe must not hard-fail).
    @Nullable
    private static String checkNoInstanceRunning(HMCLGameRepository repository, String fallbackInstance) {
        try (Stream<Version> versions = repository.getDisplayVersions()) {
            for (String id : (Iterable<String>) versions.map(Version::getId)::iterator) {
                String rejection = GameResourceGuard.checkInstanceNotRunning(id);
                if (rejection != null) {
                    return rejection;
                }
            }
            return null;
        } catch (Throwable e) {
            return GameResourceGuard.checkInstanceNotRunning(fallbackInstance);
        }
    }

    /// Sums {@link #dirSize} over the given directories, counting each distinct on-disk path once.
    /// Non-isolated instances have {@code runDir == base}, so {@code base/logs} and {@code runDir/logs}
    /// resolve to the same directory; without dedup the reported size (and freed estimate) would be
    /// roughly doubled. Paths are keyed by their absolute-normalized form; a path that cannot be
    /// normalized falls back to itself as the key.
    private static long sumDistinctDirs(Path... dirs) {
        java.util.Set<Path> seen = new java.util.LinkedHashSet<>();
        long total = 0L;
        for (Path dir : dirs) {
            if (dir == null) {
                continue;
            }
            Path key;
            try {
                key = dir.toAbsolutePath().normalize();
            } catch (Throwable e) {
                key = dir;
            }
            if (seen.add(key)) {
                total += dirSize(dir);
            }
        }
        return total;
    }

    /// Best-effort recursive size of a directory in bytes; {@code 0} for a missing/non-directory path.
    /// Per-file and walk errors are swallowed so a size estimate never fails the whole operation.
    private static long dirSize(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        long[] total = {0L};
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    total[0] += Files.size(path);
                } catch (IOException ignored) {
                    // a single unreadable file must not abort the estimate
                }
            });
        } catch (IOException | SecurityException ignored) {
            // unreadable tree — report whatever was summed so far
        }
        return total[0];
    }

    /// Formats a byte count as a human-readable size (B / KiB / MiB / GiB / TiB).
    private static String human(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }
}
