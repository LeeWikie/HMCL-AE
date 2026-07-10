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

    @Nullable
    private String instanceName;
    private boolean isolated;

    /// Records the selected instance's name and whether version isolation is on.
    /// Isolation ON => mods/saves/config live under versions/&lt;name&gt;/; OFF => this instance
    /// follows the global/parent preset's own directory setting instead (commonly the shared
    /// base .minecraft, but whatever that global default currently is).
    public void setInstanceInfo(@Nullable String instanceName, boolean isolated) {
        this.instanceName = instanceName;
        this.isolated = isolated;
    }

    @Nullable
    public String getInstanceName() {
        return instanceName;
    }

    public boolean isIsolated() {
        return isolated;
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
        return "Resolves directory paths for a Minecraft instance: gameDirectory, modsDir, logsDir, "
                + "crashReportsDir, configDir, resourcePacksDir, savesDir, shaderPacksDir, plus the "
                + "selected instance name and its version-isolation state. "
                + "Uses the currently selected game directory. NOTE: these values are a snapshot taken "
                + "at the START of the turn; if the user switches the selected instance mid-turn, prefer "
                + "the live result from list_instances over this tool's cached paths.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            return ToolFailures.failure(
                    "No game directory is configured, or the configured one no longer exists"
                            + (gameDir != null ? " (" + gameDir + ")" : ""),
                    ToolFailures.Retryable.NO,
                    "there is no selected instance / game directory in this context",
                    "ask the user to select an instance in the launcher, or call list_instances to see "
                            + "what is available and pick one first");
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
        sb.append("Note: this is a snapshot captured at the start of this turn. If the selected instance "
                + "or its game directory changed since (e.g. the user switched instances mid-turn, or "
                + "list_instances shows something different), trust that live result over these cached "
                + "paths.\n\n");
        if (instanceName != null) {
            sb.append("selectedInstance: ").append(instanceName).append('\n');
        }
        sb.append("versionIsolation: ").append(isolated
                ? "ON (mods/saves/config under versions/" + (instanceName != null ? instanceName : "<name>") + "/)"
                : "OFF (follows the global/parent preset's own directory setting instead of its own "
                + "versions/<name>/ folder — commonly the shared base .minecraft, but check the actual "
                + "current global default)").append('\n');
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
