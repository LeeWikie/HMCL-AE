/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javafx.application.Platform;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.agent.AiPromptBuilder;
import org.jackhuang.hmcl.ai.agent.ChatAgent;
import org.jackhuang.hmcl.ai.agent.ChatAgentFactory;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.EditTool;
import org.jackhuang.hmcl.ai.tools.FileReadTool;
import org.jackhuang.hmcl.ai.tools.GameContextTool;
import org.jackhuang.hmcl.ai.tools.GlobTool;
import org.jackhuang.hmcl.ai.tools.GrepTool;
import org.jackhuang.hmcl.ai.tools.ShellTool;
import org.jackhuang.hmcl.ai.tools.SleepTool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.WebFetchTool;
import org.jackhuang.hmcl.ai.tools.WriteFileTool;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.AuthlibInjectorServers;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.ProxyManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.ai.tools.AskTool;
import org.jackhuang.hmcl.ui.ai.tools.TodoWriteTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Headless, argument-driven, verbose CLI for exercising the HMCL AI agent end-to-end
/// without any UI. It keeps the entire HMCL backend alive (Profiles / game repository /
/// JavaManager / Accounts / downloads) so launcher tools such as `list_instances` work
/// exactly as they do in the GUI — then streams every token, tool call, tool result,
/// token-usage and the final reply to stdout, and exits with a status code reflecting
/// success/failure.
///
/// ## Usage
///
/// ```
/// ./gradlew :HMCL:runAiCli --args="--prompt '列出我的实例'"
/// ```
///
/// ## Arguments
///
/// - `--prompt <text>`      (required) the user message to send to the agent.
/// - `--config <path>`      path to the model config JSON (default: `.ai-cli-test.json`
///                          in the working directory). The file is read but never printed,
///                          and the API key is never echoed.
/// - `--fallback`           use the `fallback` provider in the config instead of the primary.
/// - `--auto-answer <idx>`  0-based option index the `ask` tool auto-selects so automation
///                          never blocks (default 0).
/// - `--verbose`            print full (untruncated) tool results.
/// - `--no`                 deny every dangerous-operation confirmation (default: approve / yolo).
/// - `--help`               print this help and exit.
///
/// ## Model config format (gitignored, e.g. `.ai-cli-test.json`)
///
/// ```
/// {
///   "provider": "openai-completions",
///   "endpoint": "https://.../v1/chat/completions",
///   "model": "mercury-2",
///   "apiKey": "...",
///   "fallback": { "provider": ..., "endpoint": ..., "model": ..., "apiKey": ... }
/// }
/// ```
public final class AiCli {

    private static final Gson GSON = new Gson();

    /// Driver-supplied `ask` answers (from --answer), consumed in order across the turn.
    private final java.util.ArrayDeque<String> answerQueue = new java.util.ArrayDeque<>();
    /// Set when an `ask` ran out of supplied answers — the turn was paused, not guessed.
    private final AtomicBoolean askDeferred = new AtomicBoolean(false);

    private AiCli() {
    }

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            printHelp();
            System.exit(2);
            return;
        }

        if (parsed.help) {
            printHelp();
            System.exit(0);
            return;
        }

        if (parsed.prompt == null || parsed.prompt.isBlank()) {
            System.err.println("Error: --prompt <text> is required.");
            printHelp();
            System.exit(2);
            return;
        }

        int code;
        try {
            code = new AiCli().run(parsed);
        } catch (Throwable t) {
            System.err.println("[FATAL] " + t);
            t.printStackTrace();
            code = 1;
        }
        // Tools may have spun up FX / scheduler threads; force exit with our status code.
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    private int run(Args args) throws Exception {
        long startNanos = System.nanoTime();
        answerQueue.clear();
        answerQueue.addAll(args.answers);

        // ---- 1. Load the (gitignored) model config. Never print the API key. ----
        ModelConfig model = loadModelConfig(args);
        log("CONFIG", "provider=" + model.provider + " endpoint=" + model.endpoint
                + " model=" + model.model + " apiKey=<" + model.apiKey.length() + " chars>"
                + (args.fallback ? " (fallback)" : " (primary)"));

        // ---- 2. Headless HMCL backend init (no JavaFX Application.launch). ----
        // Start the FX toolkit (no Stage) so account tools' Platform.runLater works and
        // HMCL's runInFX-based init can dispatch onto the FX thread.
        startFxToolkit();
        initHmclBackend();

        // ---- 3. Build AiSettings from the config, in an isolated temp config dir so the
        //         user's ~/.hmcl/ai-settings.json is never touched. ----
        Path aiConfigDir = Files.createTempDirectory("hmcl-aicli-");
        aiConfigDir.toFile().deleteOnExit();
        AiSettings settings = new AiSettings(aiConfigDir);
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDisplayName("ai-cli-test");
        profile.setProtocolFamily(resolveProtocolFamily(model.provider));
        profile.setEndpoint(model.endpoint);
        profile.setApiKey(model.apiKey);
        profile.putModel(new AiModelEntry(model.model));
        profile.setDefaultModelId(model.model);
        settings.putProfile(profile);
        settings.setSelectedProfileId(profile.getId());
        // Stream so we get token-by-token output; YOLO so nothing blocks waiting for a human.
        settings.streamProperty().set(true);
        settings.approvalModeProperty().set(args.deny ? "safe" : "yolo");
        settings.toolCallDisplayEnabledProperty().set(true);

        // ---- 4. Session + tools + prompt + agent (mirrors AIMainPage wiring). ----
        AiSessionStore sessionStore = new AiSessionStore(aiConfigDir);
        AiSession session = sessionStore.createSession();
        // Multi-turn: load prior turns from the session.json so this invocation continues
        // the same conversation (the agent appends the new prompt + its reply).
        if (args.session != null) {
            try {
                int loaded = loadSessionJson(Path.of(args.session), session);
                if (loaded > 0) {
                    log("SESSION", "loaded " + loaded + " prior message(s) from " + args.session);
                }
            } catch (Exception e) {
                log("SESSION", "load failed (" + args.session + "): " + e);
            }
        }

        AiSearchConfig searchConfig = new AiSearchConfig();
        SkillRegistry skillRegistry = new SkillRegistry();
        skillRegistry.setSkillsDir(SettingsManager.localConfigDirectory().resolve("ai-skills"));
        try {
            skillRegistry.refresh();
        } catch (Exception ignored) {
        }

        ToolRegistry toolRegistry = new ToolRegistry();
        registerTools(toolRegistry, settings, searchConfig, sessionStore, args);

        AiPromptBuilder promptBuilder = new AiPromptBuilder(settings, toolRegistry,
                skillRegistry, searchConfig, () -> false);

        // confirmHandler: in the CLI we run unattended. Default = approve (yolo); --no = deny.
        ChatAgent agent = ChatAgentFactory.build(settings, session, toolRegistry, promptBuilder,
                (toolName, summary) -> {
                    log("CONFIRM", toolName + " -> " + (args.deny ? "DENIED (--no)" : "APPROVED")
                            + " | " + oneLine(summary));
                    return !args.deny;
                });

        log("PROMPT", args.prompt);
        System.out.println("---- agent stream begin ----");

        // ---- 5. Run the tool loop, streaming everything to stdout. ----
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LlmException> errorRef = new AtomicReference<>();
        AtomicBoolean sawText = new AtomicBoolean(false);

        agent.sendStreaming(args.prompt, new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                sawText.set(true);
                System.out.print(token);
                System.out.flush();
            }

            @Override
            public void onUsage(LlmUsage usage) {
                System.out.println();
                log("USAGE", "prompt=" + usage.getPromptTokens()
                        + " completion=" + usage.getCompletionTokens()
                        + " total=" + usage.getTotalTokens());
            }

            @Override
            public void onToolActivity(String toolName, String arguments) {
                System.out.println();
                log("TOOL→", toolName + " " + (args.verbose ? arguments : oneLine(arguments)));
            }

            @Override
            public void onToolResult(String toolName, boolean success, String resultSummary) {
                String summary = args.verbose ? resultSummary : oneLine(resultSummary);
                log(success ? "TOOL✓" : "TOOL✗", toolName + " | " + summary);
            }

            @Override
            public void onComplete(String fullResponse) {
                System.out.println();
                System.out.println("---- agent stream end ----");
                if (fullResponse != null && !fullResponse.isBlank()) {
                    log("FINAL", "\n" + fullResponse.strip());
                } else {
                    log("FINAL", "(empty final text)");
                }
                done.countDown();
            }

            @Override
            public void onError(LlmException error) {
                errorRef.set(error);
                System.out.println();
                log("ERROR", "status=" + error.getStatusCode() + " | " + error.getMessage());
                done.countDown();
            }
        }).whenComplete((v, ex) -> {
            // NOTE: sendStreaming's future completes as soon as the async streaming is KICKED
            // OFF (sendMessageStreaming is non-blocking), NOT when the response finishes — so
            // normal completion is signalled only by onComplete/onError above. Here we only
            // handle the case where kicking it off threw synchronously.
            if (ex != null) {
                Throwable cause = ex;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                if (errorRef.get() == null) {
                    errorRef.set(cause instanceof LlmException le
                            ? le
                            : new LlmException(cause.getMessage() != null ? cause.getMessage()
                                    : cause.toString(), 0, cause));
                }
                log("ERROR", "(future) " + cause);
                done.countDown();
            }
        });

        // Anti-hang: hard wall-clock cap. On expiry we fall through and main() System.exit()s,
        // killing any stuck tool/model/FX thread — a test run must NEVER block forever.
        boolean finished = done.await(args.timeoutSeconds, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // Persist the session for multi-turn even on timeout/error (so the next turn has context).
        if (args.session != null) {
            try {
                saveSessionJson(Path.of(args.session), session);
                log("SESSION", "saved " + session.getMessages().size() + " message(s) -> " + args.session);
            } catch (Exception e) {
                log("SESSION", "save failed (" + args.session + "): " + e);
            }
        }

        if (!finished) {
            log("TIMEOUT", "agent did not finish within " + args.timeoutSeconds + "s — force-exiting (anti-hang)");
            log("ELAPSED", elapsedMs + " ms");
            log("RESULT", "TIMEOUT");
            return 124; // conventional timeout exit code
        }

        log("ELAPSED", elapsedMs + " ms");
        if (errorRef.get() != null) {
            log("RESULT", "FAILED");
            return 1;
        }
        if (askDeferred.get()) {
            log("ASK-DEFERRED", "the agent asked something with no --answer supplied; "
                    + "re-run with an added --answer (multi-turn via --session) to continue.");
            log("RESULT", "OK (ask deferred — supply --answer and re-run)");
            return 0;
        }
        log("RESULT", "OK" + (sawText.get() ? "" : " (no streamed text)"));
        return 0;
    }

    // ---- Tool registration (mirrors AIMainPage.registerTools, headless variants). ----

    private void registerTools(ToolRegistry toolRegistry, AiSettings settings,
                               AiSearchConfig searchConfig, AiSessionStore sessionStore, Args args) {
        Path configRoot = SettingsManager.localConfigDirectory();
        FileReadTool fileReadTool = new FileReadTool(configRoot);
        WriteFileTool fileWriteTool = new WriteFileTool(configRoot);
        EditTool editTool = new EditTool(configRoot);
        GrepTool grepTool = new GrepTool(configRoot);
        GlobTool globTool = new GlobTool(configRoot);
        fileReadTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        fileWriteTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        editTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        grepTool.addRoot(Metadata.HMCL_LOCAL_HOME);
        globTool.addRoot(Metadata.HMCL_LOCAL_HOME);

        toolRegistry.register(fileReadTool);
        toolRegistry.register(fileWriteTool);
        toolRegistry.register(editTool);
        toolRegistry.register(grepTool);
        toolRegistry.register(globTool);
        if (settings.isShellToolEnabled()) {
            toolRegistry.register(new ShellTool());
        }
        if (settings.isWebAccessEnabled()) {
            toolRegistry.register(new WebFetchTool());
            toolRegistry.register(new org.jackhuang.hmcl.ai.search.WebSearchTool(searchConfig));
        }

        GameContextTool gameContextTool = new GameContextTool();
        toolRegistry.register(gameContextTool);

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListInstancesTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListGameVersionsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchModsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.LaunchInstanceTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallLoaderTool());
        // CLI ask handler: answer from the --answer queue (in order); if exhausted, DEFER
        // (tell the model to stop and state what it needs) instead of guessing — never blocks.
        toolRegistry.register(new AskTool(this::cliAsk));
        // CLI todo handler: just print the checklist whenever the agent updates it.
        toolRegistry.register(new TodoWriteTool(this::cliTodo));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.KnownErrorMatcherTool());
        toolRegistry.register(new SleepTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchResourcePacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallResourcePackTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchShadersTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallShaderTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchModpacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallModpackTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SearchWorldsTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.EditInstanceTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.DeleteInstanceTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListAccountsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.AddOfflineAccountTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SelectAccountTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.MicrosoftLoginTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListJavaTool());

        org.jackhuang.hmcl.ai.remember.RememberStore rememberStore =
                new org.jackhuang.hmcl.ai.remember.RememberStore(
                        SettingsManager.localConfigDirectory().resolve("ai-memory"));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RememberTool(rememberStore));
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.RecallTool(rememberStore));

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListModsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ToggleModTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.BackupWorldTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadGameOptionsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SetGameOptionTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.OpenGameFolderTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.SystemInfoTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListScreenshotsTool());

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ReadClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.CopyToClipboardTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.PromptLibraryTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ExportConversationTool(sessionStore));

        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListWorldsTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListServersTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.ListResourcePacksTool());
        toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstanceDetailsTool());

        // Wire the currently-selected Minecraft run directory into the filesystem +
        // install_mod tools, exactly like AIMainPage.refreshGameContext().
        Path runDir = resolveCurrentGameDir();
        gameContextTool.setGameDir(runDir);
        try {
            Profile p = Profiles.getSelectedProfile();
            String sel = Profiles.getSelectedInstance(p);
            boolean isolated = runDir != null
                    && !runDir.equals(p.getRepository().getBaseDirectory());
            gameContextTool.setInstanceInfo(sel, isolated);
        } catch (Throwable ignored) {
            gameContextTool.setInstanceInfo(null, false);
        }
        if (runDir != null) {
            fileReadTool.addRoot(runDir);
            fileWriteTool.addRoot(runDir);
            editTool.addRoot(runDir);
            grepTool.addRoot(runDir);
            globTool.addRoot(runDir);
            toolRegistry.register(new org.jackhuang.hmcl.ui.ai.tools.InstallModTool(runDir));
        }

        log("TOOLS", toolRegistry.listAll().size() + " registered"
                + (runDir != null ? " (gameDir=" + runDir + ")" : " (no instance selected)"));
    }

    private static Path resolveCurrentGameDir() {
        try {
            Profile profile = Profiles.getSelectedProfile();
            var repository = profile.getRepository();
            String version = Profiles.getSelectedInstance(profile);
            if (version != null && repository.hasVersion(version)) {
                return repository.getRunDirectory(version);
            }
            return repository.getBaseDirectory();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- CLI tool handlers ----

    /// Answers each `ask` question from the --answer queue (consumed in order). A queued
    /// value that is a valid 0-based option index selects that option; otherwise it is used
    /// verbatim as a free-text/custom answer. When the queue is empty the question is NOT
    /// guessed — it is DEFERRED: the model is told to stop and state in plain words what it
    /// needs, so the driver can re-run with another --answer (multi-turn via --session).
    /// Prints everything so the run is auditable, and never blocks.
    private CompletableFuture<List<String>> cliAsk(List<AskTool.Question> questions) {
        List<String> answers = new ArrayList<>();
        System.out.println();
        log("ASK", questions.size() + " question(s); " + answerQueue.size() + " --answer value(s) remaining");
        for (int i = 0; i < questions.size(); i++) {
            AskTool.Question q = questions.get(i);
            System.out.println("  Q" + (i + 1) + " [" + q.type() + "] " + q.question());
            List<String> opts = q.options();
            for (int j = 0; j < opts.size(); j++) {
                System.out.println("      " + j + ") " + opts.get(j));
            }
            String supplied = answerQueue.poll();
            String chosen;
            if (supplied != null) {
                Integer idx = tryParseIndex(supplied);
                if (idx != null && idx >= 0 && idx < opts.size()) {
                    chosen = opts.get(idx);
                    System.out.println("      -> [" + idx + "] " + chosen + "  (from --answer)");
                } else {
                    chosen = supplied; // free text / custom answer
                    System.out.println("      -> \"" + chosen + "\"  (from --answer, custom)");
                }
            } else {
                askDeferred.set(true);
                chosen = "（自动化测试未对该问题提供答案。请不要猜测、不要继续执行；用一句话明确告诉用户你需要他回答什么，然后停止本轮回复，等待用户下一轮提供答案。）";
                System.out.println("      -> DEFERRED (no --answer left; model asked to stop and wait)");
            }
            answers.add(chosen);
        }
        return CompletableFuture.completedFuture(answers);
    }

    private static Integer tryParseIndex(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void cliTodo(List<TodoWriteTool.TodoItem> todos) {
        System.out.println();
        log("TODO", todos.size() + " item(s)");
        for (TodoWriteTool.TodoItem item : todos) {
            String mark = switch (item.status() == null ? "" : item.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            System.out.println("      " + mark + " " + item.content());
        }
    }

    // ---- Headless bootstrap ----

    private void startFxToolkit() {
        try {
            CountDownLatch fxLatch = new CountDownLatch(1);
            Platform.startup(fxLatch::countDown);
            fxLatch.await(30, TimeUnit.SECONDS);
            Platform.setImplicitExit(false);
            log("INIT", "JavaFX toolkit started (no Stage)");
        } catch (IllegalStateException alreadyRunning) {
            log("INIT", "JavaFX toolkit already running");
        } catch (Throwable t) {
            log("INIT", "JavaFX toolkit start failed (continuing): " + t);
        }
    }

    /// Initializes the HMCL backend the AI tools depend on. Mirrors the relevant subset of
    /// {@code Launcher.initializeSettingsRuntime()} so Profiles / repository / JavaManager /
    /// Accounts / downloads are all live. Runs on the FX thread (like the real Launcher) so
    /// any runInFX-based init dispatches correctly.
    private void initHmclBackend() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SettingsManager.init();
                safeInit("CacheRepository", AiCli::initCacheRepository);
                safeInit("DownloadProviders", DownloadProviders::init);
                safeInit("ProxyManager", ProxyManager::init);
                safeInit("Accounts", Accounts::init);
                safeInit("Profiles", Profiles::init);
                safeInit("AuthlibInjectorServers", AuthlibInjectorServers::init);
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("HMCL backend init timed out");
        }
        if (err.get() != null) {
            throw new IllegalStateException("HMCL backend init failed", err.get());
        }
        log("INIT", "HMCL backend ready (config=" + SettingsManager.localConfigDirectory() + ")");
    }

    /// Headless equivalent of Launcher's cache-repo wiring (Launcher.java:166). Without this,
    /// CacheRepository.getInstance().index stays null and any download / version-list refresh
    /// NPEs with "this.index is null" — which is exactly what list_game_versions hit in the CLI.
    private static void initCacheRepository() {
        org.jackhuang.hmcl.util.CacheRepository.setInstance(org.jackhuang.hmcl.game.HMCLCacheRepository.REPOSITORY);
        String commonDir = org.jackhuang.hmcl.setting.LauncherSettings.getDefaultCommonDirectory();
        org.jackhuang.hmcl.game.HMCLCacheRepository.REPOSITORY.changeDirectory(java.nio.file.Paths.get(commonDir));
    }

    private void safeInit(String name, Runnable init) {
        try {
            init.run();
        } catch (Throwable t) {
            log("INIT", name + ".init() failed (continuing): " + t);
        }
    }

    // ---- Config loading ----

    private ModelConfig loadModelConfig(Args args) throws Exception {
        Path path = args.config != null ? Path.of(args.config) : Path.of(".ai-cli-test.json");
        if (!Files.exists(path)) {
            throw new IllegalStateException("Model config not found: " + path.toAbsolutePath()
                    + " (pass --config <path>)");
        }
        String json = Files.readString(path);
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        JsonObject node = root;
        if (args.fallback) {
            if (!root.has("fallback") || !root.get("fallback").isJsonObject()) {
                throw new IllegalStateException("--fallback requested but config has no 'fallback' object");
            }
            node = root.getAsJsonObject("fallback");
        }
        ModelConfig mc = new ModelConfig();
        mc.provider = str(node, "provider", "openai-completions");
        mc.endpoint = str(node, "endpoint", null);
        mc.model = str(node, "model", null);
        mc.apiKey = str(node, "apiKey", "");
        if (mc.endpoint == null || mc.endpoint.isBlank()) {
            throw new IllegalStateException("config: 'endpoint' is required");
        }
        if (mc.model == null || mc.model.isBlank()) {
            throw new IllegalStateException("config: 'model' is required");
        }
        return mc;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    /// Maps a config provider id to a valid AiProtocolFamily id, defaulting to OpenAI
    /// completions when unknown (the langchain4j OpenAI-compatible path).
    private static String resolveProtocolFamily(String provider) {
        AiProtocolFamily fam = AiProtocolFamily.fromId(provider);
        return fam != null ? fam.getId() : AiProtocolFamily.OPENAI_COMPLETIONS.getId();
    }

    // ---- Logging helpers ----

    private static void log(String tag, String message) {
        System.out.println("[" + tag + "] " + message);
        System.out.flush();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    private static void printHelp() {
        System.out.println(String.join("\n",
                "HMCL AI CLI — headless agent runner",
                "",
                "Usage: ./gradlew :HMCL:runAiCli --args=\"--prompt '<text>' [options]\"",
                "",
                "Options:",
                "  --prompt <text>      (required) user message to send to the agent",
                "  --config <path>      model config JSON (default: ./.ai-cli-test.json)",
                "  --fallback           use the 'fallback' provider in the config",
                "  --answer <text>      answer for an `ask` question (repeatable, in order; index or free text);",
                "                       when exhausted the ask is DEFERRED (not guessed) — re-run with more --answer",
                "  --verbose            print full (untruncated) tool results",
                "  --no                 deny dangerous-operation confirmations (default: approve)",
                "  --timeout <sec>      hard wall-clock cap for the turn (default 120; force-exits on hang)",
                "  --session <path>     session.json for multi-turn (loaded before, saved after the turn)",
                "  --help               show this help"));
    }

    // ---- Session.json (multi-turn) ----

    /// Loads prior {role,content} turns from a session.json into the session. Returns the count.
    private static int loadSessionJson(Path path, AiSession session) throws java.io.IOException {
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        String json = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        com.google.gson.JsonArray arr = new Gson().fromJson(json, com.google.gson.JsonArray.class);
        if (arr == null) {
            return 0;
        }
        int n = 0;
        for (com.google.gson.JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String role = o.has("role") ? o.get("role").getAsString() : "user";
            String content = o.has("content") ? o.get("content").getAsString() : "";
            session.addMessage(new LlmMessage(role, content));
            n++;
        }
        return n;
    }

    /// Writes the session's {role,content} turns to a session.json (pretty-printed).
    private static void saveSessionJson(Path path, AiSession session) throws java.io.IOException {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (LlmMessage m : session.getMessages()) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.getRole());
            o.addProperty("content", m.getContent());
            arr.add(o);
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- Value holders ----

    private static final class ModelConfig {
        String provider;
        String endpoint;
        String model;
        String apiKey = "";
    }

    private static final class Args {
        String prompt;
        String config;
        boolean fallback;
        /// Driver-supplied answers for `ask`, consumed in order (one per question). Each is
        /// either an option index (e.g. "0") or free text (custom answer). When exhausted,
        /// the ask is DEFERRED (model is told to stop and state what it needs) rather than
        /// auto-guessed — resolve it by re-running with more --answer (multi-turn via --session).
        final List<String> answers = new ArrayList<>();
        boolean verbose;
        boolean deny;
        boolean help;
        /// Hard wall-clock cap for the whole agent turn (seconds). Anti-hang: on expiry the
        /// CLI prints [TIMEOUT] and force-exits non-zero so a test run NEVER blocks forever.
        int timeoutSeconds = 120;
        /// Optional session.json for multi-turn: loaded before the turn, saved after, so a
        /// follow-up invocation with the same path continues the same conversation.
        String session;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "--prompt" -> a.prompt = requireValue(argv, ++i, "--prompt");
                    case "--config" -> a.config = requireValue(argv, ++i, "--config");
                    case "--session" -> a.session = requireValue(argv, ++i, "--session");
                    case "--timeout" -> {
                        String v = requireValue(argv, ++i, "--timeout");
                        try {
                            a.timeoutSeconds = Integer.parseInt(v.trim());
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--timeout expects an integer (seconds), got: " + v);
                        }
                        if (a.timeoutSeconds <= 0) {
                            throw new IllegalArgumentException("--timeout must be > 0");
                        }
                    }
                    case "--answer" -> a.answers.add(requireValue(argv, ++i, "--answer"));
                    case "--fallback" -> a.fallback = true;
                    case "--verbose" -> a.verbose = true;
                    case "--no" -> a.deny = true;
                    case "--help", "-h" -> a.help = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return a;
        }

        private static String requireValue(String[] argv, int i, String flag) {
            if (i >= argv.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return argv[i];
        }
    }
}
