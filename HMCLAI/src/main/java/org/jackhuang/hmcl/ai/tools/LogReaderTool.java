/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Reads Minecraft and HMCL log files for AI analysis.
///
/// Supports reading logs from two sources:
/// - **minecraft**: the current game's `latest.log` file under `{gameDir}/logs/`
/// - **hmcl**: the most recent timestamped log file in the HMCL log directory
///
/// To avoid overwhelming the LLM context, output is truncated according to
/// [`DEFAULT_MAX_LINES`] and [`DEFAULT_MAX_SIZE_BYTES`]. Both limits can be
/// overridden via the optional `lines` parameter in [`execute`].
///
/// Constructor parameters specify the directories to search; no absolute
/// paths are hardcoded.
///
/// @see Tool
/// @see ToolResult
@NotNullByDefault
public final class LogReaderTool implements Tool {

    /// Default maximum number of lines to return.
    static final int DEFAULT_MAX_LINES = 500;

    /// Default maximum output size in bytes (32 KB).
    static final int DEFAULT_MAX_SIZE_BYTES = 32 * 1024;

    /// Pattern matching HMCL timestamped log files such as `2026-06-26T12-30-00.log`.
    private static final Pattern HMCL_LOG_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}(?:\\.\\d+)?\\.log");

    /// The Minecraft game directory (parent of `logs/latest.log`).
    private final Path gameDir;

    /// The HMCL log directory containing timestamped log files.
    private final Path hmclLogDir;

    /// Creates a new tool that reads logs from the given directories.
    ///
    /// @param gameDir   the Minecraft game directory, e.g. `~/.minecraft`
    /// @param hmclLogDir the directory containing HMCL's own log files,
    ///                   typically `~/.hmcl`
    public LogReaderTool(Path gameDir, Path hmclLogDir) {
        this.gameDir = gameDir;
        this.hmclLogDir = hmclLogDir;
    }

    /// Returns the unique name `"read_minecraft_log"`.
    @Override
    public String getName() {
        return "read_minecraft_log";
    }

    /// Returns a human-readable description of what the tool reads.
    @Override
    public String getDescription() {
        return "Reads Minecraft and HMCL log files for crash analysis. "
                + "Returns the last N lines (default 500) from minecraft logs "
                + "(logs/latest.log) and/or the most recent HMCL launcher log. "
                + "Parameters: lines (int, optional), source (string: 'minecraft', 'hmcl', or 'both', default 'both').";
    }

    /// Executes the log-reading operation.
    ///
    /// Supported parameters:
    /// - `lines` ({@code Integer}) — max lines per log source; overrides the 500 default
    /// - `source` ({@code String}) — `"minecraft"`, `"hmcl"`, or `"both"` (default)
    ///
    /// @param parameters a map of named parameters; never `null`
    /// @return a successful result containing the selected log content,
    ///         or a failure result if the requested log files are missing
    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        int maxLines = extractInt(parameters, "lines", DEFAULT_MAX_LINES);
        String source = extractString(parameters, "source", "both");
        // Clamp lines to a reasonable range (1..10000) to prevent abuse.
        maxLines = Math.max(1, Math.min(maxLines, 10000));

        StringBuilder output = new StringBuilder();
        boolean anySuccess = false;

        if (shouldReadSource(source, "minecraft")) {
            Path mcLog = gameDir.resolve("logs").resolve("latest.log");
            String content = readLogTail(mcLog, maxLines);
            if (content != null) {
                output.append("=== Minecraft log (latest.log) ===\n");
                output.append(content);
                output.append('\n');
                anySuccess = true;
            } else {
                output.append("# Minecraft log not found at ").append(mcLog).append('\n');
            }
        }

        if (shouldReadSource(source, "hmcl")) {
            Path hmclLog = findMostRecentHmclLog();
            if (hmclLog != null) {
                String content = readLogTail(hmclLog, maxLines);
                if (content != null) {
                    output.append("=== HMCL log (").append(hmclLog.getFileName()).append(") ===\n");
                    output.append(content);
                    output.append('\n');
                    anySuccess = true;
                }
            } else {
                output.append("# HMCL log not found in ").append(hmclLogDir).append('\n');
            }
        }

        if (!anySuccess && output.isEmpty()) {
            return ToolResult.failure("No log files found for source '" + source + "'.");
        }

        return ToolResult.success(output.toString().trim());
    }

    /// Reads the tail of the specified log file, applying line and size limits.
    ///
    /// @param logFile  the path to read
    /// @param maxLines the maximum number of lines from the end of the file
    /// @return the truncated content, or `null` if the file is missing or unreadable
    @Nullable
    private String readLogTail(Path logFile, int maxLines) {
        if (!Files.isRegularFile(logFile)) {
            return null;
        }

        try {
            List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int start = Math.max(0, allLines.size() - maxLines);
            List<String> tailLines = allLines.subList(start, allLines.size());

            StringBuilder builder = new StringBuilder();
            for (String line : tailLines) {
                builder.append(line).append('\n');
            }

            // Enforce the byte-size limit by trimming from the front.
            String result = builder.toString();
            if (result.getBytes(StandardCharsets.UTF_8).length > DEFAULT_MAX_SIZE_BYTES) {
                result = truncateToSize(result, DEFAULT_MAX_SIZE_BYTES);
            }

            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    /// Finds the most recent HMCL timestamped log file in the configured directory.
    ///
    /// Files are matched against [`HMCL_LOG_PATTERN`] and sorted by name
    /// (which corresponds to chronological order due to the timestamp format).
    ///
    /// @return the most recent log file, or `null` if none exist
    @Nullable
    private Path findMostRecentHmclLog() {
        if (!Files.isDirectory(hmclLogDir)) {
            return null;
        }

        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hmclLogDir)) {
            for (Path entry : stream) {
                if (HMCL_LOG_PATTERN.matcher(entry.getFileName().toString()).matches()) {
                    matches.add(entry);
                }
            }
        } catch (IOException ignored) {
            return null;
        }

        if (matches.isEmpty()) {
            return null;
        }

        // Sort descending by file name — the timestamp format ensures
        // lexicographic order equals chronological order.
        matches.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return matches.get(0);
    }

    /// Truncates a string so its UTF-8 encoded form fits within `maxBytes`.
    ///
    /// Lines are dropped from the front. A truncation marker is prepended
    /// to indicate content was omitted.
    ///
    /// @param text    the full log text
    /// @param maxBytes the byte limit
    /// @return truncated text with a leading marker
    private String truncateToSize(String text, int maxBytes) {
        final String marker = "... (truncated due to size limit)\n";
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);

        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }

        // Drop lines from the front until we fit.
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder(marker);
        int currentSize = markerBytes.length;

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
            int lineLen = lineBytes.length + 1; // +1 for newline
            if (currentSize + lineLen > maxBytes) {
                break;
            }
            builder.insert(marker.length(), line + "\n");
            currentSize += lineLen;
        }

        return builder.toString().trim();
    }

    /// Extracts an integer value from the parameters map, falling back to a default.
    ///
    /// @param params   the parameter map; must not be `null`
    /// @param key      the parameter name
    /// @param fallback the default value when the key is absent or unparseable
    /// @return the extracted or default value
    private static int extractInt(Map<String, Object> params, String key, int fallback) {
        Object val = params.get(key);
        if (val instanceof Integer i) {
            return i;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /// Extracts a string value from the parameters map, falling back to a default.
    ///
    /// @param params   the parameter map; must not be `null`
    /// @param key      the parameter name
    /// @param fallback the default value when the key is absent
    /// @return the extracted or default value
    private static String extractString(Map<String, Object> params, String key, String fallback) {
        Object val = params.get(key);
        if (val instanceof String s) {
            return s.toLowerCase();
        }
        return fallback;
    }

    /// Returns `true` when the given source parameter should trigger reading
    /// from the specified log type.
    ///
    /// @param param the value of the `source` parameter
    /// @param target the log type to check (`"minecraft"` or `"hmcl"`)
    private static boolean shouldReadSource(String param, String target) {
        return param.equals(target) || param.equals("both");
    }
}
