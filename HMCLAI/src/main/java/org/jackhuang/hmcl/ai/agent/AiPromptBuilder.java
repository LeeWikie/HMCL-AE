package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.tools.GameContextTool;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Assembles a deliberately small system prompt (Pi-style): a short persona, concise
/// tool-selection guidance, the Minecraft/HMCL conventions the agent needs, and the
/// concrete runtime paths — then lets the model drive the tool loop itself.
@NotNullByDefault
public final class AiPromptBuilder {

    private static final String PERSONA =
            "You are an AI assistant embedded in HMCL (Hello Minecraft! Launcher). "
                    + "You help users by reading files, running commands, editing configs and writing files "
                    + "with the tools below. Work autonomously: when a task needs information (instance list, "
                    + "logs, crash reports, config), use the tools to get it yourself — do not ask the user for "
                    + "file paths or contents you can discover. Be concise; reply in Chinese when the user does. "
                    + "Cite source URLs when you use web search/fetch.";

    private static final String TOOLS_GUIDE = String.join("\n",
            "Tools and when to use them:",
            "- read: read a file, or list a directory's entries.",
            "- glob: find files by name pattern (e.g. logs/*.log). grep: search file contents by regex.",
            "- write: create a new file or completely overwrite one (auto-creates parent dirs).",
            "- edit: make a surgical change to an existing file (old_string must match exactly).",
            "- shell: run a command in the host shell. Use for anything else, including renames/moves.",
            "- web_search: search the web for current information (news, docs, how-tos). PREFER this for any 'search/look up/find online' request.",
            "- web_fetch: fetch a SPECIFIC, already-known URL (e.g. a search result, a README, an install/MCP/skill manifest). Do NOT use web_fetch to 'search' — use web_search first, then web_fetch a result's URL.",
            "Do not print whole files via shell just to show them — read and summarize in plain text.");

    private static final String CONVENTIONS = String.join("\n",
            "Minecraft / HMCL conventions:",
            "- A mod in <gameDir>/mods is enabled/disabled by its file suffix: 'name.jar' = enabled, "
                    + "'name.jar.disabled' = disabled. Toggle by renaming the file (use shell).",
            "- Latest log: <logsDir>/latest.log. Crash reports: <crashReportsDir>/. Game options: <gameDir>/options.txt.",
            "- Mods: <gameDir>/mods. In-game config: <gameDir>/config. Launcher config: the read/write tool's config root.");

    private final AiSettings settings;
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final AiSearchConfig searchConfig;

    public AiPromptBuilder(AiSettings settings, ToolRegistry toolRegistry,
                           SkillRegistry skillRegistry, AiSearchConfig searchConfig) {
        this.settings = settings;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.searchConfig = searchConfig;
    }

    public String build() {
        List<String> blocks = new ArrayList<>();
        blocks.add(PERSONA);
        blocks.add("");
        blocks.add(TOOLS_GUIDE);
        blocks.add("");
        blocks.add(CONVENTIONS);
        blocks.add("");
        blocks.add(buildRuntimeContext());

        if (searchConfig.isEnabled()) {
            blocks.add("");
            blocks.add("Web search is enabled — use it for current information and cite source URLs.");
        }

        String skillSummary = skillRegistry.summarizeEnabled();
        if (!skillSummary.startsWith("(no")) {
            blocks.add("");
            blocks.add("Enabled skills (domain workflows you can follow):\n" + skillSummary);
        }

        AiExecutionPolicy policy = new AiExecutionPolicy(
                settings.getApprovalModeEnum(),
                settings.isDangerousActionConfirmationEnabled());
        blocks.add("");
        blocks.add("Tool execution policy: " + describePolicy(policy));

        return String.join("\n", blocks);
    }

    /// Injects basic machine info and the concrete runtime paths so the agent knows the
    /// environment and exactly where to look.
    private String buildRuntimeContext() {
        List<String> ctx = new ArrayList<>();
        ctx.add("Runtime context:");
        ctx.add("- Operating system: " + System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", "") + ")");
        ctx.add("- Shell: " + detectShell());
        ctx.add("- CPU logical processors: " + Runtime.getRuntime().availableProcessors());

        String totalMem = totalPhysicalMemory();
        if (totalMem != null) {
            ctx.add("- Physical memory: " + totalMem);
        }
        ctx.add("- JVM max heap: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MiB");

        Path gameDirForDisk = resolveGameDir();
        String disk = diskSpace(gameDirForDisk);
        if (disk != null) {
            ctx.add("- Disk (" + (gameDirForDisk != null ? "game drive" : "home drive") + "): " + disk);
        }
        ctx.add("- For details not listed (GPU model, CPU frequency/cores), run shell: "
                + "Windows `wmic cpu get name,maxclockspeed,numberofcores` / `wmic path win32_videocontroller get name`; "
                + "Linux `lscpu` / `lspci | grep -i vga`.");

        Path gameDir = resolveGameDir();
        if (gameDir != null) {
            ctx.add("- Game directory: " + gameDir);
            ctx.add("- Logs: " + gameDir.resolve("logs"));
            ctx.add("- Crash reports: " + gameDir.resolve("crash-reports"));
            ctx.add("- Mods: " + gameDir.resolve("mods"));
            ctx.add("- In-game config: " + gameDir.resolve("config"));
        } else {
            ctx.add("- Game directory: (no instance selected — ask the user to pick one, "
                    + "or use shell to locate a .minecraft directory)");
        }
        return String.join("\n", ctx);
    }

    @Nullable
    private Path resolveGameDir() {
        Tool tool = toolRegistry.get("resolve_game_context");
        return tool instanceof GameContextTool game ? game.getGameDir() : null;
    }

    private static String detectShell() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "PowerShell";
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            return "/bin/sh";
        }
        int slash = shell.lastIndexOf('/');
        return slash >= 0 ? shell.substring(slash + 1) : shell;
    }

    @Nullable
    private static String totalPhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                return (bytes / (1024L * 1024L * 1024L)) + " GiB";
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return null;
    }

    @Nullable
    private static String diskSpace(@Nullable Path gameDir) {
        try {
            Path target = gameDir != null ? gameDir : Path.of(System.getProperty("user.home", "."));
            java.io.File file = target.toFile();
            long total = file.getTotalSpace();
            if (total <= 0) return null;
            long free = file.getUsableSpace();
            long gb = 1024L * 1024L * 1024L;
            return (free / gb) + " GiB free / " + (total / gb) + " GiB total";
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String describePolicy(AiExecutionPolicy policy) {
        return switch (policy.getMode()) {
            case SAFE -> "Safe – read-only tools are allowed automatically; "
                    + "controlled-write and network tools require user confirmation; "
                    + "dangerous writes are blocked.";
            case ASK -> "Ask – most tools are allowed, but dangerous writes require confirmation.";
            case YOLO -> "YOLO – all tools are allowed without confirmation. "
                    + "Use responsibly and always explain dangerous actions before performing them.";
        };
    }
}
