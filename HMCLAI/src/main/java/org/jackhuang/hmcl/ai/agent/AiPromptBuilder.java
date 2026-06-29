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
                    + "file paths or contents you can discover. "
                    + "But for DECISIONS or PREFERENCES that are genuinely the user's to make (which Minecraft "
                    + "version, which mod loader, which optional mods, or confirming a destructive action like "
                    + "deleting an instance), you MUST call the `ask` tool to ask with concrete options — never "
                    + "silently guess, and NEVER reply with a list of manual steps for the user to do by hand "
                    + "when you have tools that can do them. Gather the decisions with `ask`, then act. "
                    + "Be concise; reply in Chinese when the user does. "
                    + "Cite source URLs when you use web search/fetch.";

    private static final String TOOLS_GUIDE = String.join("\n",
            "Tools and when to use them:",
            "*** GOLDEN RULE: ALWAYS prefer a dedicated tool over shell. Shell is a LAST RESORT for things no tool covers. ***",
            "NEVER use shell for any of these — there is a proper tool, and shell will be wrong/unsafe/fail:",
            "  • Accounts & login: use microsoft_login (opens the native sign-in dialog), add_offline_account, list_accounts, select_account. NEVER attempt Microsoft/OAuth login, edit account files, or run auth commands via shell.",
            "  • Java: use list_java. Don't run `java -version` / search the disk via shell.",
            "  • Game install: use install_loader / install_mod / install_modpack / install_resourcepack / install_shader. Don't download or unzip via shell.",
            "  • Listing: use list_instances / list_game_versions / search_mods. Don't `ls`/`dir` or scrape websites for these.",
            "  • Launch: use launch_instance. Manage instances with edit_instance (rename etc.) and delete_instance.",
            "File / generic tools:",
            "- read: read a file, or list a directory's entries.",
            "- glob: find files by name pattern (e.g. logs/*.log). grep: search file contents by regex.",
            "- write: create a new file or completely overwrite one (auto-creates parent dirs).",
            "- edit: make a surgical change to an existing file (old_string must match exactly).",
            "- shell: LAST RESORT only — run a host command for something genuinely not covered above (e.g. renaming a mod file to toggle it).",
            "Minecraft / HMCL tools:",
            "- list_instances: the installed instances of the selected profile. list_game_versions: the REAL, live Minecraft versions — call this before asking which version; never rely on training memory.",
            "- search_mods: search Modrinth/CurseForge. install_mod(name/id, instance): install into an instance. install_loader(gameVersion, loader[, loaderVersion, name]): create a modded instance. install_modpack / install_resourcepack / install_shader: install content. search_worlds: list worlds of an instance.",
            "- launch_instance: start the game. edit_instance: rename / native instance settings. delete_instance: delete an instance (destructive, confirm-gated).",
            "Accounts / Java:",
            "- list_accounts, add_offline_account(username), select_account(username), microsoft_login (native OAuth dialog). list_java: discovered Java runtimes.",
            "Memory (persists across conversations, file-based):",
            "- remember(title, content[, tags]): store a durable fact (user preferences, decisions, recurring setups). recall(query[, tag, limit]): retrieve them. Use recall at the start when a task may depend on remembered preferences; use remember when the user states a lasting preference.",
            "Web / dialog:",
            "- web_search: search the web for current info. PREFER this for any 'search/look up/find online' request.",
            "- web_fetch: fetch a SPECIFIC, already-known URL. Do NOT use web_fetch to 'search' — web_search first, then web_fetch a result's URL.",
            "- ask: ask the user structured questions to resolve ambiguity or confirm a destructive action — instead of guessing or listing manual steps. RULES: every question MUST be type 'single' or 'multi' and MUST include at least one concrete option; NEVER ask a free-text-only question. A '自定义/custom' choice is appended automatically — do NOT add it yourself. Example: vague 'install a version then Sodium + addons' -> list_instances, search_mods, then ask {version? single [1.21.1,1.20.1]; loader? single [Fabric,Forge,NeoForge,Quilt]; which addons? multi}, then install_loader + install_mod with the answers.",
            "Do not print whole files via shell just to show them — read and summarize in plain text.");

    private static final String CONVENTIONS = String.join("\n",
            "Minecraft / HMCL conventions:",
            "- A mod in <gameDir>/mods is enabled/disabled by its file suffix: 'name.jar' = enabled, "
                    + "'name.jar.disabled' = disabled. Toggle by renaming the file (use shell).",
            "- Latest log: <logsDir>/latest.log. Crash reports: <crashReportsDir>/. Game options: <gameDir>/options.txt.",
            "- Mods: <gameDir>/mods. In-game config: <gameDir>/config. Launcher config: the read/write tool's config root.");

    private static final String PLAYBOOKS = String.join("\n",
            "Operating playbooks (follow end-to-end with tools; never hand the user manual steps you can do yourself):",
            "[Fresh install + mods] e.g. \"装个最新版+Sodium全家桶\": 1) list_instances. 2) list_game_versions to get REAL versions (never guess from memory). 3) search_mods for the requested mod(s) to get real options/compatibility. 4) ask the user with REAL options: which version (from list_game_versions), which loader (Fabric for the Sodium family), which addons (multi, from search_mods); pick sensible defaults and ask only what matters. 5) install_loader(gameVersion, loader) to create the instance; tell the user it may take a while. 6) install_mod for each chosen mod, passing instance = the just-installed instance name so files land in the right place (see Version isolation). 7) verify (read/glob the mods dir) and tell the user it's ready (offer launch_instance).",
            "[Add mods to an existing instance]: 1) list_instances; ask which instance if ambiguous. 2) search_mods; ask which to install (multi, real options) if unspecified. 3) install_mod with instance = that instance. 4) verify + report.",
            "[Diagnose a crash / \"游戏崩溃了\"]: 1) read logs/latest.log and the newest file in crash-reports/ (glob then read). 2) match_known_errors on that text. 3) explain the cause in plain language + the concrete fix; if you can perform the fix (toggle a mod, change memory, install a mod) do it (confirm anything destructive).",
            "[Log in / switch account / \"登录/换账号\"]: 1) list_accounts to see what exists and which is active. 2) For a Microsoft (genuine/online) account: call microsoft_login — it opens HMCL's native sign-in dialog; tell the user to finish signing in there, then list_accounts to confirm. 3) For an offline account: add_offline_account(username). 4) To switch the active one: select_account(username). NEVER try to perform login via shell or by editing files.",
            "Version isolation: the runtime context states the selected instance and whether isolation is ON. ON => that instance's mods/saves/config live under versions/<name>/; OFF => they are SHARED in the base .minecraft across all versions. Before installing mods, know which it is and pass the target instance to install_mod. If isolation is OFF, warn that version-specific mods will be shared across versions.");

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
            Tool gctTool = toolRegistry.get("resolve_game_context");
            if (gctTool instanceof GameContextTool gct) {
                String inst = gct.getInstanceName();
                ctx.add("- Selected instance: " + (inst != null ? inst : "(base directory)")
                        + " | Version isolation: " + (gct.isIsolated()
                            ? "ON — mods/saves/config live under versions/" + (inst != null ? inst : "<name>") + "/"
                            : "OFF — shared in the base .minecraft across all versions"));
            }
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
            case SAFE -> "Safe – read-only, controlled-write and network tools run automatically; "
                    + "ONLY dangerous operations (e.g. destructive shell commands, deleting an instance) "
                    + "require the user's confirmation.";
            case ASK -> "Ask – tools run automatically; only dangerous operations require confirmation.";
            case YOLO -> "YOLO – all tools are allowed without confirmation. "
                    + "Use responsibly and always explain dangerous actions before performing them.";
        };
    }
}
