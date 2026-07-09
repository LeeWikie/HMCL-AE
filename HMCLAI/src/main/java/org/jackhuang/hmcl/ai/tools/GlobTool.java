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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Finds files by glob pattern under an allowlisted root (e.g. `**/*.json`,
/// `logs/*.log`). Returns matching paths. Read-only.
@NotNullByDefault
public final class GlobTool implements ToolSpec {
    private static final int MAX_RESULTS = 300;

    private final List<Path> roots = new ArrayList<>();

    public GlobTool(Path primaryRoot) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
    }

    public void addRoot(@Nullable Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (!roots.contains(normalized)) roots.add(normalized);
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
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Find files by glob pattern. Pass 'pattern' (e.g. **/*.json or logs/*.log) "
                + "and optional 'path' (base directory). Returns matching file paths.";
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
                   "pattern": {"type": "string", "description": "Glob pattern, e.g. **/*.json or logs/*.log."},
                   "path": {"type": "string", "description": "Optional base directory (relative to config root or absolute)."}
                 },
                 "required": ["pattern"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object patternObj = parameters.get("pattern");
        if (patternObj == null || patternObj.toString().isBlank()) {
            return ToolResult.failure("No 'pattern' provided.");
        }

        Path base;
        Object pathObj = parameters.get("path");
        if (pathObj != null && !pathObj.toString().isBlank()) {
            Path candidate = Path.of(pathObj.toString());
            base = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(base::startsWith)) {
                return ToolResult.failure("Path is outside the allowed roots: " + base);
            }
        } else {
            base = roots.get(0);
        }
        if (!Files.isDirectory(base)) {
            return ToolResult.failure("Not a directory: " + base);
        }
        // Real-path containment: `base` passed the lexical/normalized check above, but it (or an
        // allowed root itself) could still be a symlink pointing outside every allowed root.
        List<Path> realRoots = realRootsOf(roots);
        Path realBase = realOrSelf(base);
        if (realRoots.stream().noneMatch(realBase::startsWith)) {
            return ToolResult.failure("Path is outside the allowed roots: " + realBase);
        }

        java.nio.file.PathMatcher matcher;
        try {
            matcher = base.getFileSystem().getPathMatcher("glob:" + patternObj);
        } catch (RuntimeException e) {
            return ToolResult.failure("Invalid glob: " + e.getMessage());
        }

        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            var it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && results.size() < MAX_RESULTS) {
                Path file = it.next();
                // Files::isRegularFile follows symlinks, so a symlink planted under an allowed root
                // but pointing OUTSIDE it would otherwise be reported as a matching path (a read-
                // exfiltration primitive when chained with the `read` tool on the returned relative
                // path, which resolves back through the same symlink). Re-verify each candidate's
                // REAL path stays within an allowed root before including it.
                Path realFile = realOrSelf(file);
                if (realRoots.stream().noneMatch(realFile::startsWith)) {
                    continue;
                }
                Path rel = base.relativize(file);
                if (matcher.matches(rel)) {
                    results.add(rel.toString());
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("IO error: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("(no files matched)");
        }
        return ToolResult.success(String.join("\n", results));
    }

    private static List<Path> realRootsOf(List<Path> roots) {
        List<Path> real = new ArrayList<>(roots.size());
        for (Path r : roots) {
            real.add(realOrSelf(r));
        }
        return real;
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself (already
    /// absolute/normalized by the caller) when that fails — e.g. a broken symlink, or a race where
    /// the file vanished between being listed and checked. The fallback keeps behavior unchanged
    /// for anything that isn't a live symlink escape.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }
}
