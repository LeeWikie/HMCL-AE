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

/// Targeted in-place edit: replaces an exact substring in a text file, inside one of the
/// allowlisted roots. Unlike {@link WriteFileTool}, it changes only the matched text.
/// By default the match must be unique; pass `replaceAll=true` to replace every occurrence.
@NotNullByDefault
public final class EditTool implements ToolSpec {
    private final List<Path> roots = new ArrayList<>();

    public EditTool(Path primaryRoot) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
    }

    public void addRoot(@Nullable Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (!roots.contains(normalized)) roots.add(normalized);
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.FILESYSTEM;
    }

    @Override
    public String getName() {
        return "edit";
    }

    @Override
    public String getDescription() {
        return "Replace an exact text snippet in a file in place. Pass 'path', 'old_string' "
                + "(must match exactly), and 'new_string'. The match must be unique unless "
                + "'replace_all' is true. Prefer this over write for small changes.";
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
                   "path": {"type": "string", "description": "File path, relative to the config root or absolute (under an allowed root)."},
                   "old_string": {"type": "string", "description": "Exact text to replace."},
                   "new_string": {"type": "string", "description": "Replacement text."},
                   "replace_all": {"type": "boolean", "description": "Replace every occurrence (default false)."}
                 },
                 "required": ["path", "old_string", "new_string"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object pathObj = parameters.get("path");
        if (pathObj == null || pathObj.toString().isBlank()) {
            return ToolResult.failure("No 'path' provided.");
        }
        String oldString = String.valueOf(parameters.getOrDefault("old_string", ""));
        String newString = String.valueOf(parameters.getOrDefault("new_string", ""));
        boolean replaceAll = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("replace_all", false)));
        if (oldString.isEmpty()) {
            return ToolResult.failure("'old_string' must not be empty.");
        }

        try {
            Path candidate = Path.of(pathObj.toString());
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith)) {
                return ToolResult.failure("Path is outside the allowed roots: " + resolved);
            }
            if (!Files.isRegularFile(resolved)) {
                return ToolResult.failure("File does not exist: " + resolved);
            }
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return ToolResult.failure("'old_string' not found in " + resolved);
            }
            if (count > 1 && !replaceAll) {
                return ToolResult.failure("'old_string' is not unique (" + count + " matches); set replace_all or add more context.");
            }
            String updated = replaceAll
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString));
            Files.writeString(resolved, updated, StandardCharsets.UTF_8);
            return ToolResult.success("Edited " + resolved + " (" + (replaceAll ? count : 1) + " replacement(s)).");
        } catch (IOException e) {
            return ToolResult.failure("IO error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure("Edit failed: " + e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
