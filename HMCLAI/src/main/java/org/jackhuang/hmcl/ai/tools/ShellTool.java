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
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Runs a shell command on the host and returns its combined stdout/stderr.
///
/// The shell and OS are detected at construction time and baked into the tool
/// description so the model knows which syntax to emit (PowerShell on Windows,
/// the user's `$SHELL` — bash/fish/zsh/… — on Unix). This is a dangerous-write
/// tool and is gated by the approval system.
@NotNullByDefault
public final class ShellTool implements ToolSpec {

    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 16_000;

    private final boolean windows;
    private final String osLabel;
    private final String shellName;
    private final List<String> commandPrefix;

    public ShellTool() {
        String osName = System.getProperty("os.name", "Unknown");
        String osVersion = System.getProperty("os.version", "");
        this.osLabel = osVersion.isBlank() ? osName : (osName + " (" + osVersion + ")");
        this.windows = osName.toLowerCase().contains("win");

        if (windows) {
            this.shellName = "PowerShell";
            this.commandPrefix = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command");
        } else {
            String envShell = System.getenv("SHELL");
            String shellPath = (envShell != null && !envShell.isBlank()) ? envShell : "/bin/sh";
            int slash = shellPath.lastIndexOf('/');
            this.shellName = slash >= 0 ? shellPath.substring(slash + 1) : shellPath;
            this.commandPrefix = List.of(shellPath, "-c");
        }
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.DANGEROUS_WRITE;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.LOCAL;
    }

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return buildDescription(osLabel, shellName, windows, TIMEOUT_SECONDS);
    }

    /// Builds the model-visible tool description. Package-private and static so both the Windows
    /// (PowerShell 5.1) and Unix branches can be unit-tested regardless of the host OS the test
    /// runs on — the constructor bakes the OS in, so the instance can only ever exercise one branch.
    static String buildDescription(String osLabel, String shellName, boolean windows, int timeoutSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Run a shell command on the user's computer and return its combined stdout/stderr.")
                .append(" Operating system: ").append(osLabel).append(". Shell: ").append(shellName).append('.')
                // Golden rule: shell is a last resort, dedicated tools come first (mirrors the
                // TOOLS_GUIDE rule so the model sees it at the call site, not only once at session start).
                .append(" *** LAST RESORT ONLY: prefer a dedicated tool (read/glob/grep/instance/search/…)")
                .append(" over this. Do NOT use shell to run find/grep/cat/dir/type for something a dedicated")
                .append(" tool already covers, and do NOT use it to toggle a mod's .jar/.jar.disabled suffix —")
                .append(" use instance(action=mods_toggle) instead. ***");

        if (windows) {
            sb.append(" Emit commands in the syntax of this shell (PowerShell on Windows 10/11).")
                    .append(" WINDOWS/POWERSHELL 5.1 PITFALLS — this shell is PowerShell 5.1, not pwsh 7:")
                    .append(" - '&&' and '||' do NOT exist here (parser error); chain unconditionally with ';',")
                    .append(" or 'A; if ($?) { B }' for conditional chaining.")
                    .append(" - 'New-Item -Force' on an EXISTING file TRUNCATES it — never use -Force to 'touch'")
                    .append(" a file that might already have content; check Test-Path first.")
                    .append(" - Redirecting a native exe's stderr with '2>&1' wraps each line as a")
                    .append(" NativeCommandError and makes $? false even on exit code 0 — avoid it; the tool")
                    .append(" already captures stderr for you.")
                    .append(" - No ternary/null-coalescing operators; use if/else.");
        } else {
            sb.append(" Emit commands in the syntax of this shell (").append(shellName)
                    .append(" on Linux/macOS).");
        }

        sb.append(" Always pass the literal command in plain text — never obfuscate it with base64,")
                .append(" PowerShell -EncodedCommand/-enc, or similar encodings, so the user can read and")
                .append(" confirm exactly what will run (encoded payloads are decoded and re-checked by the")
                .append(" safety filter, and ones that cannot be decoded are blocked).")
                .append(" Pass the full command line as 'command'. Commands run with a ")
                .append(timeoutSeconds).append("s timeout. Avoid interactive or long-running commands.")
                .append(" ALWAYS also pass 'description': one short plain-language sentence (in the user's")
                .append(" language) saying what this command does and why — it is shown to the user in the")
                .append(" confirmation dialog, who likely cannot read the raw command.");
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
                   "command": {
                     "type": "string",
                     "description": "The full shell command line to run, in the host shell's syntax."
                   },
                   "description": {
                     "type": "string",
                     "description": "One short plain-language sentence (in the user's language) explaining what this command does and why. Shown to the user in the confirmation dialog."
                   }
                 },
                 "required": ["command", "description"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object raw = parameters.get("command");
        if (raw == null) raw = parameters.get("query");
        if (raw == null) raw = parameters.get("input");
        String command = raw == null ? "" : raw.toString().trim();
        if (command.isEmpty()) {
            return emptyCommandFailure();
        }

        try {
            java.util.List<String> argv = new java.util.ArrayList<>(commandPrefix);
            argv.add(command);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            Charset charset = windows ? Charset.defaultCharset() : java.nio.charset.StandardCharsets.UTF_8;
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), charset);
                } catch (IOException e) {
                    return "";
                }
            });

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return timeoutFailure(TIMEOUT_SECONDS);
            }

            String output;
            try {
                output = outputFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                output = "";
            }
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS) + "\n…(output truncated)";
            }

            int exit = process.exitValue();
            String body = output.isBlank() ? "(no output)" : output;
            return ToolResult.success("exit code: " + exit + "\n" + body);
        } catch (IOException e) {
            return startFailure(shellName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interruptedFailure();
        }
    }

    // Failure texts follow the unified ToolFailures envelope (<what+data>. Retryable: … . Next: …).
    // Package-private and static so each failure contract is unit-testable without spawning a
    // process or waiting out the real timeout — same testability convention as buildDescription.

    /// Empty/missing `command` parameter: a pure input fix, so retryable `yes`.
    static ToolResult emptyCommandFailure() {
        return ToolFailures.failure(
                "No command was provided (the 'command' parameter was empty)",
                ToolFailures.Retryable.YES,
                "the shell has nothing to run until a command line is supplied",
                "pass the full command line as 'command' (with a short 'description') and call again");
    }

    /// Command overran the fixed timeout and was killed. `later`: the budget is fixed, so retry
    /// only after making the command faster/non-interactive, not by re-running it verbatim.
    static ToolResult timeoutFailure(int timeoutSeconds) {
        return ToolFailures.failure(
                "Command exceeded the " + timeoutSeconds + "s time limit and was forcibly terminated",
                ToolFailures.Retryable.LATER,
                "the " + timeoutSeconds + "s budget is fixed — only a faster or non-interactive command can finish within it",
                "re-run a shorter, non-interactive command, or split the work into steps that each finish under " + timeoutSeconds + "s");
    }

    /// The shell process could not be launched. `no`: a different command cannot fix a missing or
    /// misconfigured shell, so the Next step is a non-retry way out (check install / ask the user).
    static ToolResult startFailure(String shellName, @Nullable String detail) {
        String reasonDetail = (detail == null || detail.isBlank()) ? "the shell executable could not be launched" : detail;
        return ToolFailures.failure(
                "Failed to start the " + shellName + " process: " + reasonDetail,
                ToolFailures.Retryable.NO,
                "the shell could not be launched, which a different command cannot fix",
                "check that " + shellName + " is installed and on PATH, or ask the user to verify their shell configuration");
    }

    /// The wait was interrupted before the command finished. `later`: no command error occurred,
    /// so retry once the launcher is idle (checking state first if the command had side effects).
    static ToolResult interruptedFailure() {
        return ToolFailures.failure(
                "Interrupted while waiting for the command to finish (it may still be running or left incomplete)",
                ToolFailures.Retryable.LATER,
                "the wait was cut short by an interruption, not by a command error",
                "re-run the command once the launcher is idle, checking the current state first if the command had side effects");
    }
}
