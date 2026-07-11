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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Read-only tool that lists directory contents or reads a text file inside any of
/// several allowlisted root directories (config dir, HMCL home, current game dir).
///
/// Paths may be given relative to the primary root, or as absolute paths — either way
/// they must resolve inside one of the allowed roots. The system prompt tells the agent
/// which concrete paths to read (e.g. `.minecraft/logs/latest.log`).
@NotNullByDefault
public final class FileReadTool implements ToolSpec {
    private final List<Path> roots = new ArrayList<>();
    private final ReadLedger ledger;

    public FileReadTool(Path primaryRoot) {
        this(primaryRoot, ReadLedger.global());
    }

    /// Ledger-injecting constructor for tests (production wiring uses [`ReadLedger#global`]
    /// so reads recorded here are visible to [`EditTool`] / [`WriteFileTool`]).
    public FileReadTool(Path primaryRoot, ReadLedger ledger) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
        this.ledger = ledger;
    }

    /// Adds another allowed root (no-op if null or already present).
    public void addRoot(@Nullable Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (!roots.contains(normalized)) roots.add(normalized);
    }

    /// Replaces the primary (first) root, keeping any additional roots.
    public void setRoot(Path primaryRoot) {
        roots.set(0, primaryRoot.toAbsolutePath().normalize());
    }

    @Nullable
    private Path instanceRoot;
    @Nullable
    private List<Path> staticRootsSnapshot;

    /// Rebases the single per-instance allowed root (§3.8): removes the previously-set instance
    /// root (unless it coincides with a static root added before instance-rebasing began) and, when
    /// {@code root} is non-null, adds the new one. Passing {@code null} clears it entirely. Unlike
    /// {@link #addRoot(Path)} (accumulate-only), this REPLACES the slot, so a previously-selected
    /// instance's files stop being reachable the moment the user switches instances — the
    /// confinement leak this fixes. The roots present at the first call are snapshotted as "static"
    /// (config dir, HMCL home) and never removed here.
    public void setInstanceRoot(@Nullable Path root) {
        if (staticRootsSnapshot == null) {
            staticRootsSnapshot = List.copyOf(roots);
        }
        Path normalized = root == null ? null : root.toAbsolutePath().normalize();
        if (java.util.Objects.equals(instanceRoot, normalized)) {
            return;
        }
        if (instanceRoot != null && !staticRootsSnapshot.contains(instanceRoot)) {
            roots.remove(instanceRoot);
        }
        instanceRoot = normalized;
        if (normalized != null && !roots.contains(normalized)) {
            roots.add(normalized);
        }
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.FILESYSTEM;
    }

    @Override
    public String getName() {
        return "read";
    }

    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder("Read a text file or list a directory's contents. ");
        sb.append("Pass 'path' (relative to the launcher config dir, or an absolute path). ");
        sb.append("'maxLines' limits output (default 200). ");
        sb.append("'startLine' (1-based) reads a window of maxLines starting there; omit it to get the "
                + "LAST maxLines lines. For a big file/log, read the tail first, then page earlier parts "
                + "with startLine instead of dumping the whole file. Allowed roots: ");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(roots.get(i));
        }
        sb.append('.');
        return sb.toString();
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
                   "path": {
                     "type": "string",
                     "description": "File or directory path, relative to the config root or absolute (must be under an allowed root)."
                   },
                   "maxLines": {
                     "type": "integer",
                     "description": "Maximum number of lines to return (default 200)."
                   },
                   "startLine": {
                     "type": "integer",
                     "description": "1-based line to start reading from; returns maxLines lines from here. Omit to return the LAST maxLines lines of the file."
                   }
                 },
                 "required": ["path"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String child = parameters.getOrDefault("path", ".").toString();
        int maxLines = parse(parameters.get("maxLines"), 200);
        if (maxLines <= 0) maxLines = 200;
        int startLine = parse(parameters.get("startLine"), 0);

        try {
            Path candidate = Path.of(child);
            Path resolved;
            if (candidate.isAbsolute()) {
                resolved = candidate.toAbsolutePath().normalize();
                if (roots.stream().noneMatch(resolved::startsWith)) {
                    return FileToolFailures.outsideRoots(resolved, roots);
                }
                if (!Files.exists(resolved)) {
                    return ToolResult.failure("Path does not exist: " + resolved);
                }
            } else {
                // Try every allowed root in priority order (root[0] first, so anything that
                // already resolves correctly there keeps doing so) and use the first root
                // where the path actually exists. A relative path may only live under a root
                // added later via addRoot() (e.g. the currently selected game/instance dir),
                // so resolving against roots.get(0) alone is not enough.
                List<Path> attempted = new ArrayList<>();
                Path found = null;
                for (Path root : roots) {
                    Path candidateResolved = root.resolve(child).toAbsolutePath().normalize();
                    attempted.add(candidateResolved);
                    if (roots.stream().anyMatch(candidateResolved::startsWith) && Files.exists(candidateResolved)) {
                        found = candidateResolved;
                        break;
                    }
                }
                if (found == null) {
                    // Fall back to today's behavior (resolve against the primary root) for the
                    // allowed-roots check, but report every root that was tried.
                    resolved = attempted.get(0);
                    if (roots.stream().noneMatch(resolved::startsWith)) {
                        return FileToolFailures.outsideRoots(resolved, roots);
                    }
                    StringBuilder sb = new StringBuilder("Path does not exist under any allowed root. Tried: ");
                    for (int i = 0; i < attempted.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(attempted.get(i));
                    }
                    return ToolResult.failure(sb.toString());
                }
                resolved = found;
            }
            // Real-path containment: `resolved` passed the lexical/normalized check above (and is
            // confirmed to exist), but it — or an allowed root itself — could still be a symlink
            // pointing outside every allowed root, letting a symlink planted under an allowed root
            // read back an arbitrary external file/directory's content.
            Path realResolved = resolved.toRealPath();
            if (roots.stream().noneMatch(r -> realResolved.startsWith(realOrSelf(r)))) {
                return FileToolFailures.outsideRoots(realResolved, roots);
            }
            if (Files.isDirectory(resolved)) {
                StringBuilder sb = new StringBuilder();
                try (var s = Files.list(resolved)) {
                    s.limit(200).forEach(p -> sb.append(
                            (Files.isDirectory(p) ? "[DIR]  " : "[FILE] ")
                                    + p.getFileName()).append('\n'));
                }
                return ToolResult.success(sb.toString().trim());
            }
            long size = Files.size(resolved);
            if (size > 10 * 1024 * 1024) {
                return ToolResult.failure("File too large: " + (size >> 20) + " MiB.");
            }
            byte[] raw = Files.readAllBytes(resolved);
            // Strict decode (same failure behavior as the previous Files.readAllLines): a
            // malformed-UTF-8 file must surface as an IO error, not as mojibake.
            String text = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
            // Record the read in the shared ledger — this is what later entitles edit/write
            // to touch the file (read precondition) and detects external changes (staleness).
            ledger.recordRead(realResolved, raw);
            java.util.List<String> lines = text.lines().toList();
            int total = lines.size();
            if (startLine > 0) {
                // Explicit paging window: lines [startLine, startLine + maxLines).
                int from = startLine - 1;
                if (from >= total) {
                    return ToolResult.success("[file has " + total + " lines; startLine " + startLine
                            + " is past the end]");
                }
                int to = Math.min(from + maxLines, total);
                String header = "[showing lines " + (from + 1) + "-" + to + " of " + total + " total]";
                return ToolResult.success(header + "\n" + String.join("\n", lines.subList(from, to)));
            }
            if (total > maxLines) {
                // Default: tail the file, but tell the model earlier lines exist and how to reach them.
                String header = "[file has " + total + " lines; showing the LAST " + maxLines
                        + " (lines " + (total - maxLines + 1) + "-" + total + ")."
                        + " Pass startLine to read earlier parts]";
                return ToolResult.success(header + "\n"
                        + String.join("\n", lines.subList(total - maxLines, total)));
            }
            return ToolResult.success(String.join("\n", lines));
        } catch (IOException e) {
            return FileToolFailures.io("reading the path", e);
        } catch (RuntimeException e) {
            return FileToolFailures.invalid("path", e);
        }
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself (already
    /// absolute/normalized by the caller) when that fails — e.g. an allowed root that doesn't (yet)
    /// exist on disk.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    private static int parse(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
