package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// Resolves game-directory paths for a Minecraft version or instance.
/// An optional version-specific directory can be set via {@link #setGameDir(Path)}.
@NotNullByDefault
public final class GameContextTool implements ToolSpec {
    @Nullable
    private Path gameDir;

    public void setGameDir(@Nullable Path gameDir) {
        this.gameDir = gameDir != null ? gameDir.toAbsolutePath().normalize() : null;
    }

    @Nullable
    public Path getGameDir() {
        return gameDir;
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
        return "resolve_game_context";
    }

    @Override
    public String getDescription() {
        return "Resolves directory paths for a Minecraft instance: runDirectory, "
                + "modsDir, logsDir, crashReportsDir, configDir. "
                + "Uses the currently selected game directory.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            return ToolResult.failure("No game directory configured or directory does not exist.");
        }

        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("gameDirectory", gameDir.toString());
        paths.put("modsDir", gameDir.resolve("mods").toString());
        paths.put("logsDir", gameDir.resolve("logs").toString());
        paths.put("crashReportsDir", gameDir.resolve("crash-reports").toString());
        paths.put("configDir", gameDir.resolve("config").toString());
        paths.put("resourcePacksDir", gameDir.resolve("resourcepacks").toString());
        paths.put("savesDir", gameDir.resolve("saves").toString());
        paths.put("shaderPacksDir", gameDir.resolve("shaderpacks").toString());

        StringBuilder sb = new StringBuilder();
        for (var entry : paths.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
            if (Files.isDirectory(Path.of(entry.getValue()))) {
                sb.append(" (exists)");
            }
            sb.append('\n');
        }
        return ToolResult.success(sb.toString().trim());
    }
}
