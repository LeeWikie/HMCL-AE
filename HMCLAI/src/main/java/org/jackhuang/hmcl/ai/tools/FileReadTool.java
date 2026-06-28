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

    public FileReadTool(Path primaryRoot) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
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
        sb.append("'maxLines' limits output (default 200). Allowed roots: ");
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
                     "description": "Maximum number of trailing lines to return (default 200)."
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

        try {
            Path candidate = Path.of(child);
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(child))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith)) {
                return ToolResult.failure("Path is outside the allowed roots: " + resolved);
            }
            if (!Files.exists(resolved)) {
                return ToolResult.failure("Path does not exist: " + resolved);
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
            java.util.List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            if (lines.size() > maxLines) {
                lines = lines.subList(lines.size() - maxLines, lines.size());
            }
            return ToolResult.success(String.join("\n", lines));
        } catch (IOException e) {
            return ToolResult.failure("IO error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure("Invalid path: " + e.getMessage());
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
