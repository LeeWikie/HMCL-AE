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
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.GameBuilder;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.download.RemoteVersion;
import org.jackhuang.hmcl.download.VersionList;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// An AI-accessible tool that installs a Minecraft version together with an optional
/// mod loader fully automatically, reusing HMCL's existing install pipeline.
///
/// This tool does **not** reimplement any install logic. It drives the very same
/// pipeline the graphical install wizard uses:
///
/// - {@link Profiles#getSelectedProfile()} → {@link Profile#getDependency()} to obtain the
///   {@link DefaultDependencyManager} bound to the current game directory and download provider.
/// - {@link DefaultDependencyManager#getVersionList(String)} to resolve / validate game and
///   loader versions ("latest" resolution, newest-compatible loader picking).
/// - {@link DefaultDependencyManager#gameBuilder()} + {@link GameBuilder} to assemble the
///   install {@link Task}, exactly like {@code DownloadPage} / modpack install tasks do.
///
/// HMCL tasks are asynchronous, so the resulting {@link Task} (and the version-list refresh
/// tasks) are run on a daemon worker via {@link TaskExecutor} and joined with a generous
/// timeout. Success / failure is reported through {@link ToolResult} so the model can react.
///
/// Supported loaders: {@code vanilla}, {@code fabric}, {@code forge}, {@code neoforge},
/// {@code quilt}, {@code optifine}. All of them are driven through the uniform
/// {@code getVersionList} / {@code gameBuilder().version(...)} API.
public final class InstallLoaderTool implements ToolSpec {

    /// Timeout (seconds) for refreshing / loading a remote version list.
    private static final int VERSION_LIST_TIMEOUT_SECONDS = 120;

    /// Timeout (seconds) for a full game + loader installation.
    private static final int INSTALL_TIMEOUT_SECONDS = 600;

    @Override
    public String getName() {
        return "install_loader";
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
                   "gameVersion": {"type": "string", "description": "Minecraft version such as 1.21.1, or latest / latest-release for the newest release."},
                   "loader": {"type": "string", "enum": ["vanilla","fabric","forge","neoforge","quilt","optifine"], "description": "Mod loader to install; default vanilla."},
                   "loaderVersion": {"type": "string", "description": "Optional specific loader version; default newest compatible."},
                   "name": {"type": "string", "description": "Optional instance name; default derived from version."}
                 },
                 "required": ["gameVersion"]
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Install a Minecraft version with an optional mod loader fully automatically, "
                + "reusing HMCL's install pipeline. Parameters: "
                + "gameVersion (required, e.g. \"1.21.1\"; also accepts \"latest\" or \"latest-release\" "
                + "to resolve the newest release), "
                + "loader (optional, one of vanilla/fabric/forge/neoforge/quilt/optifine; default vanilla), "
                + "loaderVersion (optional; default = newest version compatible with the game version), "
                + "name (optional instance name; default derived from game version and loader). "
                + "Creates a new instance in the currently selected profile and returns its name on success.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Defensive outer guard: HMCL's install pipeline can throw unchecked exceptions
        // (e.g. UnsupportedOperationException from version lists that don't support a
        // given refresh mode). Never let a bare exception escape to the model.
        try {
            return doExecute(parameters);
        } catch (Throwable t) {
            return ToolResult.failure("install_loader failed unexpectedly: " + describe(t));
        }
    }

    private ToolResult doExecute(Map<String, Object> parameters) {
        String gameVersionParam = string(parameters.get("gameVersion"));
        String loaderParam = string(parameters.get("loader"));
        String loaderVersionParam = string(parameters.get("loaderVersion"));
        String nameParam = string(parameters.get("name"));

        if (gameVersionParam == null) {
            return ToolResult.failure("Missing required parameter: gameVersion (e.g. \"1.21.1\" or \"latest\").");
        }

        // Resolve loader id (the patch id used both by getVersionList and GameBuilder.version).
        String loader = loaderParam == null ? "vanilla" : loaderParam.trim().toLowerCase(Locale.ROOT);
        String loaderId; // null for vanilla
        switch (loader) {
            case "":
            case "vanilla":
            case "minecraft":
                loader = "vanilla";
                loaderId = null;
                break;
            case "fabric":
                loaderId = LibraryAnalyzer.LibraryType.FABRIC.getPatchId();
                break;
            case "forge":
                loaderId = LibraryAnalyzer.LibraryType.FORGE.getPatchId();
                break;
            case "neoforge":
            case "neo-forge":
                loader = "neoforge";
                loaderId = LibraryAnalyzer.LibraryType.NEO_FORGE.getPatchId();
                break;
            case "quilt":
                loaderId = LibraryAnalyzer.LibraryType.QUILT.getPatchId();
                break;
            case "optifine":
                loaderId = LibraryAnalyzer.LibraryType.OPTIFINE.getPatchId();
                break;
            default:
                return ToolResult.failure("Unknown loader '" + loaderParam + "'. "
                        + "Use one of: vanilla, fabric, forge, neoforge, quilt, optifine.");
        }

        // Obtain the current profile + dependency manager (the install pipeline entry point).
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (RuntimeException e) {
            return ToolResult.failure("No game profile is selected, cannot install.");
        }
        DefaultDependencyManager dependencyManager = profile.getDependency();

        // ---- Resolve the game version (validate / resolve "latest"). ----
        VersionList<?> gameList;
        try {
            gameList = dependencyManager.getVersionList("game");
        } catch (RuntimeException e) {
            return ToolResult.failure("Failed to access the Minecraft version list: " + describe(e));
        }

        String gameVersion;
        String requested = gameVersionParam.trim();
        String requestedLower = requested.toLowerCase(Locale.ROOT);
        boolean wantLatest = requestedLower.equals("latest")
                || requestedLower.equals("latest-release") || requestedLower.equals("latest_release");

        if (wantLatest) {
            // Resolving "latest" needs the full list. Not every source supports a full
            // refresh (some throw UnsupportedOperationException), so fall back gracefully.
            String refreshError = await(gameList.refreshAsync(), VERSION_LIST_TIMEOUT_SECONDS,
                    "Loading the Minecraft version list");
            if (refreshError != null) {
                return ToolResult.failure("Could not load the full version list to resolve 'latest' ("
                        + refreshError + "). Please specify an explicit version such as 1.21.1.");
            }
            String latest = newestRelease(gameList.getVersions(""));
            if (latest == null) {
                return ToolResult.failure("Could not determine the latest Minecraft release; "
                        + "please specify an explicit version such as 1.21.1.");
            }
            gameVersion = latest;
        } else {
            // Per-version load — the exact entry point HMCL's own download UI uses
            // (loadAsync → refreshAsync(gameVersion)), which works for multi-source lists
            // that reject a full refreshAsync().
            String loadError = await(gameList.loadAsync(requested), VERSION_LIST_TIMEOUT_SECONDS,
                    "Loading Minecraft " + requested);
            if (loadError != null) {
                return ToolResult.failure(loadError + ". Check your network, or the version may not exist.");
            }
            if (gameList.getVersion(requested, requested).isEmpty()) {
                return ToolResult.failure("Unknown Minecraft version '" + requested + "'. "
                        + "It does not exist in the remote version list.");
            }
            gameVersion = requested;
        }

        // ---- Resolve the loader version (validate / pick newest compatible). ----
        String loaderVersion = null;
        if (loaderId != null) {
            VersionList<?> loaderList;
            try {
                loaderList = dependencyManager.getVersionList(loaderId);
            } catch (RuntimeException e) {
                return ToolResult.failure("Failed to access the " + loader + " version list: " + describe(e));
            }

            String loaderRefreshError = await(loaderList.loadAsync(gameVersion), VERSION_LIST_TIMEOUT_SECONDS,
                    "Loading the " + loader + " version list");
            if (loaderRefreshError != null) {
                return ToolResult.failure(loaderRefreshError + ". Check your network connection.");
            }

            if (loaderVersionParam != null && !loaderVersionParam.trim().isEmpty()) {
                String requestedLoader = loaderVersionParam.trim();
                Optional<? extends RemoteVersion> match = loaderList.getVersion(gameVersion, requestedLoader);
                if (match.isEmpty()) {
                    return ToolResult.failure(loader + " version '" + requestedLoader
                            + "' is not available for Minecraft " + gameVersion
                            + " (incompatible or unknown loader version).");
                }
                loaderVersion = match.get().getSelfVersion();
            } else {
                Collection<? extends RemoteVersion> candidates = loaderList.getVersions(gameVersion);
                if (candidates.isEmpty()) {
                    return ToolResult.failure("No " + loader + " version is compatible with Minecraft "
                            + gameVersion + ".");
                }
                // Version lists are sorted newest-first, so the first entry is the newest.
                loaderVersion = candidates.iterator().next().getSelfVersion();
            }
        }

        // ---- Derive a unique instance name. ----
        String name;
        if (nameParam != null && !nameParam.trim().isEmpty()) {
            name = nameParam.trim();
        } else {
            name = loaderId == null ? gameVersion : gameVersion + "-" + loader;
        }
        name = uniqueName(profile, name);

        // ---- Assemble the install task exactly like the wizard does. ----
        GameBuilder builder = dependencyManager.gameBuilder()
                .name(name)
                .gameVersion(gameVersion);
        if (loaderId != null) {
            builder.version(loaderId, loaderVersion);
        }

        Task<?> installTask;
        try {
            installTask = builder.buildAsync();
        } catch (RuntimeException e) {
            return ToolResult.failure("Failed to build the install task: " + describe(e));
        }

        String installError = await(installTask, INSTALL_TIMEOUT_SECONDS, "Installation");
        if (installError != null) {
            return ToolResult.failure(installError);
        }

        // Make the new instance visible to the rest of the launcher.
        try {
            profile.getRepository().refreshVersions();
        } catch (RuntimeException ignored) {
            // Non-fatal: the instance is on disk; the list will refresh on next access.
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Successfully installed instance \"").append(name).append("\".\n");
        sb.append("Minecraft: ").append(gameVersion).append('\n');
        if (loaderId == null) {
            sb.append("Loader: vanilla (no mod loader)");
        } else {
            sb.append("Loader: ").append(loader).append(' ').append(loaderVersion);
        }
        return ToolResult.success(sb.toString());
    }

    /// Returns the newest RELEASE version id from a game version collection, or {@code null}.
    @Nullable
    private static String newestRelease(Collection<? extends RemoteVersion> versions) {
        RemoteVersion best = null;
        for (RemoteVersion version : versions) {
            if (version.getVersionType() != RemoteVersion.Type.RELEASE) {
                continue;
            }
            if (best == null || isNewer(version.getReleaseDate(), best.getReleaseDate())) {
                best = version;
            }
        }
        return best == null ? null : best.getSelfVersion();
    }

    /// Compares two (possibly null) release instants; a non-null date is always newer than null.
    private static boolean isNewer(@Nullable Instant candidate, @Nullable Instant current) {
        if (candidate == null) return false;
        if (current == null) return true;
        return candidate.isAfter(current);
    }

    /// Derives an instance name that does not collide with an existing version on disk.
    private static String uniqueName(Profile profile, String base) {
        if (!profile.getRepository().hasVersion(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!profile.getRepository().hasVersion(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }

    /// Runs an HMCL {@link Task} on a daemon worker and blocks until it finishes or the timeout
    /// elapses. Returns {@code null} on success, or a human-readable error message otherwise.
    @Nullable
    private static String await(Task<?> task, int timeoutSeconds, String what) {
        TaskExecutor executor = task.executor(false);
        boolean[] success = {false};
        Thread worker = new Thread(() -> success[0] = executor.test(), "AI-install-loader");
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(timeoutSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(executor);
            return what + " was interrupted";
        }

        if (worker.isAlive()) {
            cancelQuietly(executor);
            return what + " timed out after " + timeoutSeconds + " seconds";
        }

        if (!success[0]) {
            return what + " failed: " + describe(executor.getException());
        }
        return null;
    }

    private static void cancelQuietly(TaskExecutor executor) {
        try {
            executor.cancel();
        } catch (RuntimeException ignored) {
            // Executor may not have started yet; nothing to cancel.
        }
    }

    /// Builds a concise description of a throwable for an error message.
    private static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return t.getClass().getSimpleName();
    }

    /// Converts a parameter value to a trimmed string, or {@code null} if absent / blank.
    @Nullable
    private static String string(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
