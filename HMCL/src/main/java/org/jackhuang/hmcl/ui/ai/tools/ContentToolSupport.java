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

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.ToolProgress;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/// Shared helpers for the content (resource pack / shader / modpack / world) AI tools.
///
/// These tools reuse HMCL's existing remote-repository and download/install pipeline:
/// [RemoteAddonRepository] for searching and resolving addons, the configured
/// [DownloadProvider] for URL injection, and HMCL [Task]s for downloading/installing.
/// HMCL tasks are asynchronous, so the helpers here run them on a daemon worker pool
/// and block with a timeout so the synchronous {@code Tool.execute} contract is honored.
final class ContentToolSupport {

    private ContentToolSupport() {
    }

    /// Daemon worker pool used to run blocking network / task operations off the caller thread.
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ai-content-tool-worker");
        thread.setDaemon(true);
        return thread;
    });

    /// Selects the remote source by name (defaults to Modrinth, which needs no API key).
    enum Source {
        MODRINTH,
        CURSEFORGE
    }

    static Source parseSource(@Nullable String value) {
        if (value == null) {
            return Source.MODRINTH;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("curse")) {
            return Source.CURSEFORGE;
        }
        return Source.MODRINTH;
    }

    /// Returns the repository for the given source and content type, or {@code null}
    /// if that source does not expose the content type.
    @Nullable
    static RemoteAddonRepository repositoryFor(Source source, RemoteAddonRepository.Type type) {
        return source == Source.CURSEFORGE
                ? RemoteAddon.Source.CURSEFORGE.getRepoForType(type)
                : RemoteAddon.Source.MODRINTH.getRepoForType(type);
    }

    static DownloadProvider downloadProvider() {
        return DownloadProviders.getDownloadProvider();
    }

    /// Reads a named parameter, falling back to the generic {@code "query"} key when absent.
    /// The runtime currently advertises a single {@code query} parameter, so every tool's
    /// primary input must also be reachable through it.
    @Nullable
    static String primaryParam(Map<String, Object> parameters, String primaryKey) {
        String value = str(parameters.get(primaryKey));
        if (value == null || value.isBlank()) {
            value = str(parameters.get("query"));
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nullable
    static String optional(Map<String, Object> parameters, String key) {
        String value = str(parameters.get(key));
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nullable
    static String str(@Nullable Object value) {
        return value != null ? value.toString() : null;
    }

    /// Runs a blocking callable on the daemon worker and waits up to {@code timeoutSeconds}.
    static <T> T callWithTimeout(Callable<T> callable, int timeoutSeconds, String operation) throws Exception {
        Future<T> future = WORKER.submit(callable);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException(operation + " timed out after " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException(operation + " failed: " + cause, cause);
        }
    }

    /// Runs an HMCL [Task] to completion on the daemon worker, blocking with a timeout.
    /// Cancels the task on timeout and rethrows the underlying failure otherwise.
    static void runTaskBlocking(Task<?> task, int timeoutSeconds, String operation) throws Exception {
        TaskExecutor executor = task.executor();
        executor.addTaskListener(progressListener(operation));
        Future<Boolean> future = WORKER.submit(() -> {
            boolean ok = executor.test();
            if (!ok) {
                Exception exception = executor.getException();
                throw exception != null ? exception : new IOException(operation + " failed");
            }
            return Boolean.TRUE;
        });
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            executor.cancel();
            future.cancel(true);
            throw new IOException(operation + " timed out after " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException(operation + " failed: " + cause, cause);
        }
    }

    /// Builds a [TaskListener] that bridges an HMCL task chain's live progress onto the
    /// decoupled [ToolProgress] bus, so the chat UI can render a real-time progress card.
    ///
    /// It tracks the currently *running* significant subtask and forwards that subtask's
    /// {@code progressProperty} (fraction; negative = indeterminate) together with a phase
    /// label (the subtask name when meaningful, otherwise the operation label). HMCL mutates
    /// the progress property on the JavaFX thread, so the property listener is added/removed
    /// on that same thread to avoid racing with JavaFX's (non-thread-safe) listener lists.
    static TaskListener progressListener(String operation) {
        return new TaskListener() {
            /// The subtask we are currently mirroring (confined to the JavaFX thread).
            private Task<?> tracked;
            private final InvalidationListener onProgress = o -> {
                Task<?> current = tracked;
                if (current != null) {
                    ToolProgress.publish(operation, current.progressProperty().get(), phaseOf(current, operation));
                }
            };

            @Override
            public void onStart() {
                ToolProgress.begin(operation, operation + "…");
            }

            @Override
            public void onRunning(Task<?> task) {
                if (!task.getSignificance().shouldShow()) {
                    return;
                }
                Platform.runLater(() -> {
                    if (tracked != null) {
                        tracked.progressProperty().removeListener(onProgress);
                    }
                    tracked = task;
                    task.progressProperty().addListener(onProgress);
                    ToolProgress.publish(operation, task.progressProperty().get(), phaseOf(task, operation));
                });
            }

            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                Platform.runLater(() -> {
                    if (tracked != null) {
                        tracked.progressProperty().removeListener(onProgress);
                        tracked = null;
                    }
                    ToolProgress.finish(operation, success, success ? operation + " 完成" : operation + " 失败");
                });
            }
        };
    }

    /// A human-readable phase label: the task's own name when it set one, else the operation.
    private static String phaseOf(Task<?> task, String fallback) {
        String name = task.getName();
        if (name == null || name.isBlank() || name.equals(task.getClass().getName())) {
            return fallback;
        }
        return name;
    }

    /// Performs a popularity-sorted search and renders a compact, model-friendly listing.
    static String search(RemoteAddonRepository repository, @Nullable String gameVersion, String filter, int limit) throws Exception {
        String effectiveGameVersion = gameVersion == null ? "" : gameVersion;
        RemoteAddonRepository.SearchResult result = callWithTimeout(
                () -> repository.search(downloadProvider(), effectiveGameVersion, null, 0, Math.max(limit, 10),
                        filter, RemoteAddonRepository.SortType.POPULARITY, RemoteAddonRepository.SortOrder.DESC),
                60, "Search");

        List<RemoteAddon> addons = result.getResults().limit(limit).collect(Collectors.toList());
        if (addons.isEmpty()) {
            return "No results found for \"" + filter + "\"" + (gameVersion != null ? " (game version " + gameVersion + ")" : "") + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(addons.size()).append(" result(s) for \"").append(filter).append("\":\n");
        int index = 1;
        for (RemoteAddon addon : addons) {
            sb.append(index++).append(". ").append(addon.title());
            if (addon.author() != null && !addon.author().isBlank()) {
                sb.append(" by ").append(addon.author());
            }
            sb.append("\n   id: ").append(addon.slug());
            if (addon.description() != null && !addon.description().isBlank()) {
                String description = addon.description().strip();
                if (description.length() > 160) {
                    description = description.substring(0, 157) + "...";
                }
                sb.append("\n   ").append(description);
            }
            if (addon.categories() != null && !addon.categories().isEmpty()) {
                sb.append("\n   categories: ").append(String.join(", ", addon.categories()));
            }
            sb.append('\n');
        }
        sb.append("\nUse the \"id\" value with the matching install tool.");
        return sb.toString();
    }

    /// Resolves the addon and selects the best matching version for installation.
    static RemoteAddon.Version resolveVersion(RemoteAddonRepository repository, String id,
                                              @Nullable String gameVersion, @Nullable String versionId) throws Exception {
        DownloadProvider provider = downloadProvider();
        RemoteAddon addon = callWithTimeout(() -> repository.getModById(provider, id), 60, "Lookup");
        if (addon == null || addon == RemoteAddon.BROKEN) {
            throw new IOException("No addon found with id '" + id + "'.");
        }

        List<RemoteAddon.Version> versions = callWithTimeout(
                () -> addon.data().loadVersions(repository, provider).collect(Collectors.toList()),
                60, "Version lookup");

        if (versions.isEmpty()) {
            throw new IOException("No downloadable files were found for '" + addon.title() + "'.");
        }

        if (versionId != null) {
            Optional<RemoteAddon.Version> match = versions.stream()
                    .filter(v -> versionId.equalsIgnoreCase(v.version()) || versionId.equalsIgnoreCase(v.name()))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            throw new IOException("No version matching '" + versionId + "' for '" + addon.title() + "'.");
        }

        List<RemoteAddon.Version> candidates = versions;
        if (gameVersion != null) {
            List<RemoteAddon.Version> filtered = versions.stream()
                    .filter(v -> v.gameVersions() != null && v.gameVersions().contains(gameVersion))
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                throw new IOException("No version of '" + addon.title() + "' supports game version " + gameVersion + ".");
            }
            candidates = filtered;
        }

        return candidates.stream()
                .max(Comparator.comparing(RemoteAddon.Version::datePublished,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(candidates.get(0));
    }

    /// Resolves {@code <instance>/subdirectory} for the currently selected profile/instance,
    /// creating the directory if necessary. Falls back to the profile base directory when no
    /// instance is selected.
    static Path resolveInstanceSubdirectory(String subdirectory, @Nullable String instanceOverride) throws IOException {
        Profile profile = Profiles.getSelectedProfile();
        HMCLGameRepository repository = profile.getRepository();
        String version = instanceOverride != null ? instanceOverride : Profiles.getSelectedInstance(profile);

        Path runDirectory = (version != null && repository.hasVersion(version))
                ? repository.getRunDirectory(version)
                : repository.getBaseDirectory();

        Path directory = runDirectory.resolve(subdirectory);
        Files.createDirectories(directory);
        return directory;
    }

    /// Picks an instance name that does not collide with an existing version in the profile.
    static String uniqueInstanceName(Profile profile, String desired) {
        HMCLGameRepository repository = profile.getRepository();
        String base = desired.isBlank() ? "Modpack" : desired.trim();
        if (!repository.hasVersion(base)) {
            return base;
        }
        AtomicInteger counter = new AtomicInteger(2);
        String candidate;
        do {
            candidate = base + "-" + counter.getAndIncrement();
        } while (repository.hasVersion(candidate));
        return candidate;
    }

    static boolean isCurseForgeAvailable() {
        return CurseForgeRemoteAddonRepository.isAvailable();
    }
}
