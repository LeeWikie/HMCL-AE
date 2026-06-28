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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Searches file contents by regular expression under an allowlisted root, returning
/// matching `path:line: text` entries. Optionally limited to a sub-path and a filename
/// glob. Read-only.
@NotNullByDefault
public final class GrepTool implements ToolSpec {
    private static final int MAX_MATCHES = 200;
    private static final int MAX_FILES = 5000;
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    private final List<Path> roots = new ArrayList<>();

    public GrepTool(Path primaryRoot) {
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
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents by regular expression. Pass 'pattern' (Java regex), "
                + "optional 'path' (sub-directory to search) and optional 'glob' (filename "
                + "filter like *.json). Returns matching 'path:line: text' lines.";
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
                   "pattern": {"type": "string", "description": "Java regular expression to search for."},
                   "path": {"type": "string", "description": "Optional sub-directory (relative to config root or absolute) to search under."},
                   "glob": {"type": "string", "description": "Optional filename glob filter, e.g. *.json or *.log."}
                 },
                 "required": ["pattern"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object patternObj = parameters.get("pattern");
        if (patternObj == null || patternObj.toString().isEmpty()) {
            return ToolResult.failure("No 'pattern' provided.");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternObj.toString());
        } catch (RuntimeException e) {
            return ToolResult.failure("Invalid regex: " + e.getMessage());
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

        Object globObj = parameters.get("glob");
        String glob = globObj == null ? null : globObj.toString();
        java.nio.file.PathMatcher matcher = glob == null || glob.isBlank()
                ? null : base.getFileSystem().getPathMatcher("glob:" + glob);

        List<String> results = new ArrayList<>();
        int[] filesScanned = {0};
        try (Stream<Path> walk = Files.walk(base)) {
            var it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && results.size() < MAX_MATCHES && filesScanned[0] < MAX_FILES) {
                Path file = it.next();
                if (matcher != null && file.getFileName() != null && !matcher.matches(file.getFileName())) {
                    continue;
                }
                try {
                    if (Files.size(file) > MAX_FILE_SIZE) continue;
                } catch (IOException e) {
                    continue;
                }
                filesScanned[0]++;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue; // binary / unreadable
                }
                for (int i = 0; i < lines.size() && results.size() < MAX_MATCHES; i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        String rel = base.relativize(file).toString();
                        String text = lines.get(i).strip();
                        if (text.length() > 200) text = text.substring(0, 200) + "…";
                        results.add(rel + ":" + (i + 1) + ": " + text);
                    }
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("IO error: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("(no matches)");
        }
        return ToolResult.success(String.join("\n", results));
    }
}
