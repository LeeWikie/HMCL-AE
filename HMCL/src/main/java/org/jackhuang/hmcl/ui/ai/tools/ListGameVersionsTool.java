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
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.RemoteVersion;
import org.jackhuang.hmcl.download.VersionList;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
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
/// The tool reuses the exact pipeline the launcher's install/version picker uses:
/// {@link Profiles#getSelectedProfile()} → {@link Profile#getDependency()} →
/// {@link DefaultDependencyManager#getVersionList(String)} with id {@code "game"},
/// then {@link VersionList#refreshAsync()} to fetch the full game manifest and
/// {@link VersionList#getVersions(String)} (with the empty game-version key) to read them all.
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
    private static final long TIMEOUT_SECONDS = 120L;

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

        // Resolve the dependency manager exactly like the install/version UI does.
        DefaultDependencyManager dependencyManager;
        try {
            Profile profile = Profiles.getSelectedProfile();
            dependencyManager = profile.getDependency();
        } catch (Exception e) {
            return ToolResult.failure("No game profile is available to query versions: " + describe(e));
        }

        VersionList<?> versionList;
        try {
            versionList = dependencyManager.getVersionList("game");
        } catch (Exception e) {
            return ToolResult.failure("Failed to obtain the game version list: " + describe(e));
        }

        // Load the full game manifest on a daemon worker, bounded by a timeout.
        // For the GAME list the no-arg refreshAsync() loads ALL versions and
        // getVersions("") (empty game-version key) returns every one of them.
        List<RemoteVersion> versions;
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-list-game-versions");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<List<RemoteVersion>> future = worker.submit(() -> {
                versionList.refreshAsync().run();
                return new ArrayList<RemoteVersion>(versionList.getVersions(""));
            });
            versions = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ToolResult.failure("Timed out after " + TIMEOUT_SECONDS
                    + "s while loading the Minecraft version list. Check your network connection and try again.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.failure("Failed to load the Minecraft version list (network error?): " + describe(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while loading the Minecraft version list.");
        } catch (Exception e) {
            return ToolResult.failure("Failed to load the Minecraft version list: " + describe(e));
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

    /// Produces a short, non-null description of a throwable for error messages.
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message != null && !message.isBlank()) ? message : t.getClass().getSimpleName();
    }
}
