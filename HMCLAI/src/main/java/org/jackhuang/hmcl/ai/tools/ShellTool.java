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
        return "Run a shell command on the user's computer and return its combined stdout/stderr."
                + " Operating system: " + osLabel + ". Shell: " + shellName
                + ". Emit commands in the syntax of this shell (e.g. PowerShell on Windows 10/11,"
                + " or bash/fish/zsh on Linux/macOS)."
                + " Pass the full command line as 'command'. Commands run with a "
                + TIMEOUT_SECONDS + "s timeout. Avoid interactive or long-running commands."
                + " ALWAYS also pass 'description': one short plain-language sentence (in the user's"
                + " language) saying what this command does and why — it is shown to the user in the"
                + " confirmation dialog, who likely cannot read the raw command.";
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
            return ToolResult.failure("No command provided (pass the command line as 'query').");
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
                return ToolResult.failure("Command timed out after " + TIMEOUT_SECONDS + "s and was terminated.");
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
            return ToolResult.failure("Failed to start process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while waiting for the command.");
        }
    }
}
