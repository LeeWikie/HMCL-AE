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
import org.jackhuang.hmcl.download.BMCLAPIDownloadProvider;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.MojangDownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /// Lazily-built single-source providers used as alternates when the configured
    /// provider's candidates fail. They are stateless for our purposes (we only call
    /// {@link DownloadProvider#injectURLWithCandidates}, a pure URL rewrite), so a
    /// single cached instance is safe to share.
    private static volatile DownloadProvider officialProvider;
    private static volatile DownloadProvider mirrorProvider;

    /// The official (Mojang) source only — forces direct download URLs.
    private static DownloadProvider officialProvider() {
        DownloadProvider p = officialProvider;
        if (p == null) {
            synchronized (ContentToolSupport.class) {
                p = officialProvider;
                if (p == null) {
                    p = officialProvider = new MojangDownloadProvider();
                }
            }
        }
        return p;
    }

    /// The BMCLAPI mirror source only — forces mirror download URLs (helpful in mainland China).
    private static DownloadProvider mirrorProvider() {
        DownloadProvider p = mirrorProvider;
        if (p == null) {
            synchronized (ContentToolSupport.class) {
                p = mirrorProvider;
                if (p == null) {
                    String root = System.getProperty("hmcl.bmclapi.override", "https://bmclapi2.bangbang93.com");
                    p = mirrorProvider = new BMCLAPIDownloadProvider(root);
                }
            }
        }
        return p;
    }

    /// Ordered download providers to try when a download fails. The first entry is the
    /// user-configured provider (an {@code AutoDownloadProvider} that already merges all
    /// candidate mirrors); the remaining entries force a single alternate source so a
    /// retry genuinely *switches* mirror ordering when one source is down.
    static List<DownloadProvider> downloadProviderCandidates() {
        List<DownloadProvider> providers = new ArrayList<>(3);
        providers.add(downloadProvider());
        providers.add(officialProvider());
        providers.add(mirrorProvider());
        return providers;
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

    /// Builds and runs a download [Task], retrying a couple of times with exponential
    /// backoff and switching the download source on failure.
    ///
    /// The {@code factory} is handed each candidate [DownloadProvider] in turn so it can
    /// rebuild the task with that source's candidate URLs (e.g.
    /// {@code provider.injectURLWithCandidates(url)}). The attempt plan is:
    /// configured provider → configured provider (retry) → each alternate source once.
    /// On every failure we back off; on success we return immediately. If all attempts
    /// fail, the last underlying exception is rethrown so callers can classify it (e.g.
    /// via [#isNetworkError]).
    static void runDownloadWithFallback(DownloadTaskFactory factory, int timeoutSeconds, String operation) throws Exception {
        List<DownloadProvider> providers = downloadProviderCandidates();

        // Attempt plan: primary, primary (retry), then each distinct alternate once.
        List<DownloadProvider> plan = new ArrayList<>(providers.size() + 1);
        plan.add(providers.get(0));
        plan.add(providers.get(0));
        for (int i = 1; i < providers.size(); i++) {
            plan.add(providers.get(i));
        }

        Exception last = null;
        for (int attempt = 0; attempt < plan.size(); attempt++) {
            try {
                Task<?> task = factory.create(plan.get(attempt));
                runTaskBlocking(task, timeoutSeconds, operation);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                last = e;
                // A non-network failure (404 / checksum / disk full) won't be fixed by retrying the
                // SAME source — skip the duplicate same-provider attempt and move to the next distinct
                // mirror. Network errors keep the same-source retry (they are usually transient).
                if (!isNetworkError(e)) {
                    while (attempt + 1 < plan.size() && plan.get(attempt + 1).equals(plan.get(attempt))) {
                        attempt++;
                    }
                }
                if (attempt < plan.size() - 1) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw last != null ? last : new IOException(operation + " failed");
    }

    /// Builds a download [Task] for a specific [DownloadProvider]. Implementations should
    /// resolve candidate URLs from the given provider (so a retry can switch source).
    @FunctionalInterface
    interface DownloadTaskFactory {
        Task<?> create(DownloadProvider provider) throws Exception;
    }

    /// Sleeps with exponential backoff (1s, 2s, 4s, capped at 8s) between retries.
    static void sleepBackoff(int attempt) throws InterruptedException {
        long millis = Math.min(8000L, 1000L * (1L << Math.min(attempt, 3)));
        Thread.sleep(millis);
    }

    /// Detects whether a failure is a low-level network/connectivity problem (DNS failure,
    /// timeout, refused/unreachable connection, TLS handshake) rather than a logical error.
    /// Walks the whole cause chain because HMCL wraps these inside {@code DownloadException}.
    static boolean isNetworkError(@Nullable Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard < 32; c = c.getCause(), guard++) {
            if (c instanceof UnknownHostException
                    || c instanceof SocketTimeoutException
                    || c instanceof ConnectException
                    || c instanceof NoRouteToHostException
                    || c instanceof PortUnreachableException
                    || c instanceof SSLException) {
                return true;
            }
            String message = c.getMessage();
            if (message != null && message.contains("timed out after")) {
                // Our own timeout wrappers from callWithTimeout / runTaskBlocking.
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /// A clear, actionable Chinese message for a network/connectivity failure, suitable
    /// for returning directly as a {@code ToolResult.failure} payload.
    static String networkErrorAdvice(@Nullable Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("网络连接失败，下载未能完成。可尝试：\n");
        sb.append("1. 切换下载源：国内网络建议使用 BMCLAPI 镜像，海外网络建议使用官方源（可在 HMCL 设置中调整下载源）；\n");
        sb.append("2. 检查网络与代理：确认能正常联网，若使用了代理/VPN 请确认其工作正常；\n");
        sb.append("3. 稍后重试：可能是临时的网络波动或下载服务器繁忙。");
        String reason = shortNetworkReason(t);
        if (reason != null) {
            sb.append("\n（错误详情：").append(reason).append("）");
        }
        return sb.toString();
    }

    /// A short, human-readable cause for a network failure (in Chinese), or {@code null}.
    @Nullable
    private static String shortNetworkReason(@Nullable Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard < 32; c = c.getCause(), guard++) {
            if (c instanceof UnknownHostException) {
                return "无法解析主机地址（DNS 解析失败）：" + c.getMessage();
            }
            if (c instanceof SocketTimeoutException) {
                String m = c.getMessage();
                return m != null && !m.isBlank() ? m : "连接超时";
            }
            if (c instanceof ConnectException || c instanceof NoRouteToHostException
                    || c instanceof PortUnreachableException) {
                return "无法建立连接：" + c.getMessage();
            }
            if (c instanceof SSLException) {
                return "安全连接（TLS/SSL）失败：" + c.getMessage();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        if (t == null) {
            return null;
        }
        String message = t.getMessage();
        return message != null && !message.isBlank() ? message : t.getClass().getSimpleName();
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
        return resolveVersion(repository, id, gameVersion, versionId, null);
    }

    /// As above, plus an optional mod-loader filter applied on the auto-pick path. A multi-loader
    /// project publishes Fabric+Forge+NeoForge files for the same MC version, so without this the
    /// newest file may be the wrong loader and crash the instance.
    static RemoteAddon.Version resolveVersion(RemoteAddonRepository repository, String id,
                                              @Nullable String gameVersion, @Nullable String versionId,
                                              @Nullable org.jackhuang.hmcl.addon.mod.ModLoaderType loader) throws Exception {
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

        if (loader != null) {
            List<RemoteAddon.Version> byLoader = candidates.stream()
                    .filter(v -> v.loaders() != null && v.loaders().contains(loader))
                    .collect(Collectors.toList());
            if (byLoader.isEmpty()) {
                throw new IOException("No version of '" + addon.title() + "' supports loader " + loader
                        + (gameVersion != null ? " for game version " + gameVersion : "") + ".");
            }
            candidates = byLoader;
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
        // Bounded probe so a pathological repository state can't spin forever; fall back to a
        // timestamp suffix (effectively unique) if we somehow exhaust the numbered candidates.
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!repository.hasVersion(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }

    static boolean isCurseForgeAvailable() {
        return CurseForgeRemoteAddonRepository.isAvailable();
    }
}
