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

/// Writes a UTF-8 text file inside one of several allowlisted root directories,
/// creating parent directories as needed. Overwrites by default; pass `append=true`
/// to append. Controlled-write tool gated by the approval system.
@NotNullByDefault
public final class WriteFileTool implements ToolSpec {
    private final List<Path> roots = new ArrayList<>();

    public WriteFileTool(Path primaryRoot) {
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
        return "write";
    }

    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder("Write a UTF-8 text file (creating parent directories). ");
        sb.append("Pass 'path' (relative to the config root or absolute) and 'content'. ");
        sb.append("Set 'append' to true to append instead of overwrite. Allowed roots: ");
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
                     "description": "Target file path, relative to the config root or absolute (must be under an allowed root)."
                   },
                   "content": {
                     "type": "string",
                     "description": "The full text content to write."
                   },
                   "append": {
                     "type": "boolean",
                     "description": "Append to the file instead of overwriting (default false)."
                   }
                 },
                 "required": ["path", "content"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object pathObj = parameters.get("path");
        if (pathObj == null || pathObj.toString().isBlank()) {
            return ToolResult.failure("No 'path' provided.");
        }
        String content = parameters.getOrDefault("content", "").toString();
        boolean append = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("append", false)));

        try {
            Path candidate = Path.of(pathObj.toString());
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith)) {
                return ToolResult.failure("Path is outside the allowed roots: " + resolved);
            }
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (append) {
                Files.write(resolved, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.write(resolved, bytes);
            }
            return ToolResult.success((append ? "Appended to " : "Wrote ") + resolved + " (" + bytes.length + " bytes).");
        } catch (IOException e) {
            return ToolResult.failure("IO error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure("Invalid path: " + e.getMessage());
        }
    }
}
