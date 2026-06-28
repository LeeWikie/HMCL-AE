package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Lists crash reports in an instance's crash-reports directory.
@NotNullByDefault
public final class CrashReportListTool implements ToolSpec {
    @Nullable
    private Path gameDir;

    public void setGameDir(@Nullable Path gameDir) {
        this.gameDir = gameDir != null ? gameDir.toAbsolutePath().normalize() : null;
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
        return "list_crash_reports";
    }

    @Override
    public String getDescription() {
        return "Lists recent crash report files for the tracked Minecraft instance. "
                + "Returns file names, sizes, and modification times.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (gameDir == null) {
            return ToolResult.failure("No game directory configured.");
        }

        Path crashDir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(crashDir)) {
            return ToolResult.success("No crash-reports directory was found at " + crashDir + ".");
        }

        try (Stream<Path> entries = Files.list(crashDir)) {
            String listing = entries
                    .filter(p -> Files.isRegularFile(p))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                    Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) { return 0; }
                    })
                    .limit(20)
                    .map(p -> {
                        try {
                            long size = Files.size(p);
                            return p.getFileName() + " (" + (size / 1024) + " KiB)";
                        } catch (IOException e) {
                            return p.getFileName().toString();
                        }
                    })
                    .collect(Collectors.joining("\n"));
            return ToolResult.success(listing.isEmpty() ? "(no crash reports found)" : listing);
        } catch (IOException e) {
            return ToolResult.failure("Failed to list crash reports: " + e.getMessage());
        }
    }
}
