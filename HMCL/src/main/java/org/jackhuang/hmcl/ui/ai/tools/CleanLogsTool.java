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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// A tool that cleans up old log files in a Minecraft instance's `logs/` directory,
/// always keeping the live `latest.log` plus the newest N archived logs.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedInstance()`] for the default instance id,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getRunDirectory(String)`]
///   for the isolation-aware per-instance run directory.
///
/// It deletes only regular files directly inside `logs/` (the rolled archives such as
/// `2024-01-01-1.log.gz`), never recursing into subdirectories and never touching
/// `latest.log` or `debug.log`. The deletion count is reported.
///
/// Permission level: it DELETES old log files (recoverable noise only — never saves,
/// worlds or configs).
@NotNullByDefault
public final class CleanLogsTool implements Tool {

    private static final int DEFAULT_KEEP = 5;

    @Override
    public String getName() {
        return "clean_logs";
    }

    @Override
    public String getDescription() {
        return "Cleans up old log files in a Minecraft instance's 'logs/' folder, always keeping the live "
                + "'latest.log' and 'debug.log' plus the newest N archived logs. "
                + "Parameters: instance (optional, defaults to the currently selected instance), "
                + "keep (optional integer, how many archived logs to keep; default " + DEFAULT_KEEP + "). "
                + "WRITES: deletes the older rolled log archives (e.g. dated .log.gz files); it never recurses into "
                + "subfolders and never deletes saves, configs or 'latest.log'. Reports how many files were removed.";
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

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        int keep = DEFAULT_KEEP;
        Object keepObj = parameters.get("keep");
        if (keepObj == null) {
            keepObj = parameters.get("query");
        }
        if (keepObj != null && !String.valueOf(keepObj).trim().isEmpty()) {
            try {
                keep = Integer.parseInt(String.valueOf(keepObj).trim());
            } catch (NumberFormatException e) {
                return ToolResult.failure("Parameter 'keep' must be an integer, got: " + keepObj);
            }
            if (keep < 0) {
                return ToolResult.failure("Parameter 'keep' must not be negative.");
            }
        }

        Path logsDirectory;
        try {
            logsDirectory = repository.getRunDirectory(instance).resolve("logs");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isDirectory(logsDirectory)) {
            return ToolResult.success("Instance '" + instance + "' has no 'logs' directory; nothing to clean.");
        }

        List<Path> archives = new ArrayList<>();
        try (Stream<Path> stream = Files.list(logsDirectory)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (name.equalsIgnoreCase("latest.log") || name.equalsIgnoreCase("debug.log")) {
                    continue; // never touch the live logs
                }
                archives.add(path);
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to list the logs directory: " + e.getMessage());
        }

        if (archives.size() <= keep) {
            return ToolResult.success("Instance '" + instance + "' has " + archives.size()
                    + " archived log file(s) in " + logsDirectory + ", which is within the keep limit of "
                    + keep + ". Nothing was deleted.");
        }

        // Newest first, so the first `keep` are retained and the rest deleted.
        archives.sort(Comparator.comparing(CleanLogsTool::lastModified).reversed());

        int deleted = 0;
        long bytesFreed = 0;
        List<String> failures = new ArrayList<>();
        for (int i = keep; i < archives.size(); i++) {
            Path path = archives.get(i);
            long size = 0;
            try {
                size = Files.size(path);
            } catch (IOException ignored) {
                // size is informational only
            }
            try {
                Files.delete(path);
                deleted++;
                bytesFreed += size;
            } catch (IOException e) {
                failures.add(path.getFileName() + " (" + e.getMessage() + ")");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Cleaned logs of instance '").append(instance).append("'.\n");
        sb.append("Directory: ").append(logsDirectory).append('\n');
        sb.append("Archived logs found: ").append(archives.size())
                .append(" (kept newest ").append(keep).append(", plus latest.log/debug.log).\n");
        sb.append("Deleted: ").append(deleted).append(" file(s)");
        if (bytesFreed > 0) {
            sb.append(", freeing ").append(String.format("%.2f", bytesFreed / 1024.0 / 1024.0)).append(" MiB");
        }
        sb.append('.');
        if (!failures.isEmpty()) {
            sb.append("\nFailed to delete ").append(failures.size()).append(" file(s): ")
                    .append(String.join(", ", failures));
        }
        return ToolResult.success(sb.toString());
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
