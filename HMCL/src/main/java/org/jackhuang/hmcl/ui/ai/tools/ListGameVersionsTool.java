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
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.RemoteVersion;
import org.jackhuang.hmcl.download.VersionList;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// A read-only AI tool that returns the REAL, live list of Minecraft game versions
/// from HMCL's own version list, instead of relying on the model's (stale) training data.
///
/// The tool reuses the exact pipeline the launcher's install/version picker uses
/// (see {@code MainPage#launchNoGame()}): {@link DownloadProviders#getDownloadProvider()} →
/// {@link DownloadProvider#getVersionListById(String)} with id {@code "game"}, then
/// {@link VersionList#refreshAsync(String)} (with the empty game-version key) to fetch the
/// full game manifest and {@link VersionList#getVersions(String)} to read them all. Going
/// through the active download provider means the configured mirror (e.g. BMCLAPI),
/// source fallback and proxy settings are all reused.
///
/// The refresh task is run through a {@link TaskExecutor} (not {@link Task#run()}): the
/// active provider returns a multi-source version list whose mirror-fallback logic only
/// runs under a real executor, and whose no-arg {@code refreshAsync()} is unsupported.
///
/// The result is summarized for an LLM: the latest release, the latest snapshot,
/// and the most recent versions (newest first), capped to keep the payload small.
///
/// ## Parameters
///
/// - `count` (optional, default 20, clamped to 1..50): how many recent versions to list.
/// - `includeSnapshots` (optional, default false): when false, only releases are listed;
///   when true, snapshots (and other non-release types) are included in the recent list.
///
/// This tool performs no mutations and is safe to call at any time. Network access is
/// required; failures (no profile, network error, timeout) are reported via
/// {@link ToolResult#failure(String)}.
@NotNullByDefault
public final class ListGameVersionsTool implements Tool {

    /// Default number of recent versions to list when `count` is not supplied.
    private static final int DEFAULT_COUNT = 20;

    /// Maximum number of recent versions the tool will ever list.
    private static final int MAX_COUNT = 50;

    /// Maximum time to wait for the remote version list to load before giving up.
    private static final long TIMEOUT_SECONDS = 30L;

    /// Formats release timestamps as a plain UTC date (yyyy-MM-dd).
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    @Override
    public String getName() {
        return "list_game_versions";
    }

    @Override
    public String getDescription() {
        return "List the REAL, live Minecraft (Java Edition) versions available for installation, "
                + "fetched from HMCL's official version manifest. "
                + "Use this to offer the user accurate version choices and to validate a version "
                + "instead of guessing from memory (your training data may be outdated). "
                + "Read-only; performs no installation. "
                + "Parameters: count (optional integer, default 20, max 50 — how many recent versions to show), "
                + "includeSnapshots (optional boolean, default false — when false only stable releases are listed). "
                + "Returns the latest release, the latest snapshot, and the most recent versions newest-first.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        int count = clampCount(parseInt(parameters.get("count"), DEFAULT_COUNT));
        boolean includeSnapshots = parseBoolean(parameters.get("includeSnapshots"), false);

        // Resolve the version list through HMCL's active download source, exactly like the
        // launcher's "install new version" picker (MainPage#launchNoGame). This reuses the
        // configured mirror (e.g. BMCLAPI), the source fallback and the proxy settings.
        VersionList<?> versionList;
        try {
            DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();
            versionList = downloadProvider.getVersionListById("game");
        } catch (Exception e) {
            return ToolResult.failure("Failed to obtain the game version list: " + describe(e));
        }

        // Load the full game manifest on a daemon worker, bounded by a timeout.
        // Use refreshAsync("") (empty game-version key) — the no-arg refreshAsync() is
        // unsupported by the active multi-source version list and would always throw.
        // Drive it through a TaskExecutor (not Task#run()) so the built-in mirror/source
        // fallback actually kicks in when the first source fails.
        Task<?> refreshTask = versionList.refreshAsync("");
        TaskExecutor executor = refreshTask.executor();

        List<RemoteVersion> versions;
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-list-game-versions");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Boolean> future = worker.submit(executor::test);
            boolean succeeded;
            try {
                succeeded = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                executor.cancel();
                return ToolResult.failure("Timed out after " + TIMEOUT_SECONDS
                        + "s while loading the Minecraft version list. "
                        + "Check your network connection (or download-source/proxy settings) and try again.");
            }
            if (!succeeded) {
                return ToolResult.failure(classifyFailure(executor.getException()));
            }
            versions = new ArrayList<>(versionList.getVersions(""));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.failure(classifyFailure(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while loading the Minecraft version list.");
        } catch (Exception e) {
            return ToolResult.failure(classifyFailure(e));
        } finally {
            worker.shutdownNow();
        }

        if (versions.isEmpty()) {
            return ToolResult.failure("The Minecraft version list came back empty. Try again later.");
        }

        return ToolResult.success(formatVersions(versions, count, includeSnapshots));
    }

    /// Builds the model-friendly summary text from the full version list.
    private static String formatVersions(List<RemoteVersion> all, int count, boolean includeSnapshots) {
        // Newest first. RemoteVersion#compareTo already orders newer-before-older,
        // but we sort by release date (falling back to natural order) for robustness.
        List<RemoteVersion> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparing((RemoteVersion v) -> v.getReleaseDate() == null ? Instant.MIN : v.getReleaseDate())
                .reversed()
                .thenComparing(v -> v));

        RemoteVersion latestRelease = null;
        RemoteVersion latestSnapshot = null;
        int releaseCount = 0;
        int snapshotCount = 0;
        for (RemoteVersion v : sorted) {
            RemoteVersion.Type type = v.getVersionType();
            if (type == RemoteVersion.Type.RELEASE) {
                releaseCount++;
                if (latestRelease == null) latestRelease = v;
            } else if (type == RemoteVersion.Type.SNAPSHOT
                    || type == RemoteVersion.Type.PENDING
                    || type == RemoteVersion.Type.UNOBFUSCATED) {
                snapshotCount++;
                if (latestSnapshot == null) latestSnapshot = v;
            }
        }

        // The pool of versions we list in detail.
        List<RemoteVersion> pool = new ArrayList<>();
        for (RemoteVersion v : sorted) {
            if (includeSnapshots || v.getVersionType() == RemoteVersion.Type.RELEASE) {
                pool.add(v);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Minecraft versions (live from HMCL's version manifest):\n");
        sb.append("Latest release: ").append(latestRelease != null ? describeVersion(latestRelease) : "(none)").append('\n');
        sb.append("Latest snapshot: ").append(latestSnapshot != null ? describeVersion(latestSnapshot) : "(none)").append('\n');
        sb.append('\n');

        String heading = includeSnapshots
                ? "Recent versions (releases + snapshots, newest first):"
                : "Recent releases (newest first):";
        sb.append(heading).append('\n');

        int shown = Math.min(count, pool.size());
        for (int i = 0; i < shown; i++) {
            sb.append("  - ").append(describeVersion(pool.get(i))).append('\n');
        }

        int remaining = pool.size() - shown;
        if (remaining > 0) {
            sb.append("  (").append(remaining).append(" more older ")
                    .append(includeSnapshots ? "versions" : "releases")
                    .append(" exist)\n");
        }

        sb.append('\n');
        sb.append("Totals: ").append(releaseCount).append(" releases, ")
                .append(snapshotCount).append(" snapshots, ")
                .append(all.size()).append(" versions overall.");
        return sb.toString();
    }

    /// Renders a single version as "id (type, yyyy-MM-dd)".
    private static String describeVersion(RemoteVersion v) {
        StringBuilder sb = new StringBuilder(v.getSelfVersion());
        sb.append(" (").append(typeLabel(v.getVersionType()));
        Instant date = v.getReleaseDate();
        if (date != null) {
            sb.append(", ").append(DATE_FORMAT.format(date));
        }
        sb.append(')');
        return sb.toString();
    }

    /// Maps a {@link RemoteVersion.Type} to a short human-readable label.
    private static String typeLabel(RemoteVersion.Type type) {
        return switch (type) {
            case RELEASE -> "release";
            case SNAPSHOT -> "snapshot";
            case OLD -> "old";
            case PENDING -> "pending";
            case UNOBFUSCATED -> "unobfuscated";
            case UNCATEGORIZED -> "other";
        };
    }

    /// Clamps the requested count into the supported range.
    private static int clampCount(int count) {
        if (count < 1) return 1;
        return Math.min(count, MAX_COUNT);
    }

    /// Parses an integer parameter that may arrive as a Number or a String.
    private static int parseInt(@Nullable Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    /// Parses a boolean parameter that may arrive as a Boolean or a String.
    private static boolean parseBoolean(@Nullable Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString().trim());
        }
        return fallback;
    }

    /// Turns a load failure into a clear, model-readable message, distinguishing a
    /// connection timeout, a "no network" failure and a parse failure from one another.
    private static String classifyFailure(@Nullable Throwable cause) {
        if (cause == null) {
            return "Failed to load the Minecraft version list (unknown error). Try again later.";
        }
        // Walk the whole cause chain — the real reason is usually a wrapped cause.
        for (Throwable t = cause; t != null; t = t.getCause()) {
            String className = t.getClass().getName();
            if (t instanceof java.net.SocketTimeoutException || className.endsWith("SocketTimeoutException")) {
                return "Timed out talking to the download source while loading the Minecraft version list. "
                        + "Check your network connection (or download-source/proxy settings) and try again.";
            }
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof javax.net.ssl.SSLException) {
                return "No network access to the download source while loading the Minecraft version list: "
                        + describe(t) + ". Check your network connection (or download-source/proxy settings) and try again.";
            }
            if (className.contains("Json") || className.contains("Syntax")
                    || className.contains("Parse") || className.contains("Malformed")) {
                return "Failed to parse the Minecraft version list returned by the download source: "
                        + describe(t) + ". The source may be temporarily broken; try again later.";
            }
        }
        return "Failed to load the Minecraft version list (network error?): " + describe(cause);
    }

    /// Produces a short, non-null description of a throwable for error messages.
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message != null && !message.isBlank()) ? message : t.getClass().getSimpleName();
    }
}
