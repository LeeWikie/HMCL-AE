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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// A curated prompt / preset library, the launcher equivalent of the "assistants" or
/// "prompt library" panels in desktop LLM clients (e.g. Cherry Studio). Users (or the
/// project) drop reusable instruction templates as `.md`/`.txt` files into
/// `{localConfigDir}/ai-prompts/`; the agent can then list them and pull one in to follow
/// a known workflow — for example a saved "diagnose my crash" or "set up a modpack"
/// procedure.
///
/// With no parameter the tool lists the available presets (name + first heading/line as a
/// summary). With a `name` it returns that preset's full text so the model can act on it.
/// Reading is confined to the dedicated prompts directory, so this never doubles as a
/// general file reader.
///
/// Permission level: READ_ONLY. It only lists and reads files inside the prompts folder.
@NotNullByDefault
public final class PromptLibraryTool implements ToolSpec {

    private static final int MAX_LIST = 100;
    private static final int MAX_CHARS = 40_000;

    @Override
    public String getName() {
        return "prompt_library";
    }

    @Override
    public String getDescription() {
        return "Browses the saved prompt/preset library (reusable instruction templates kept in the "
                + "ai-prompts folder). Parameter: 'name' (optional). Without it, lists the available presets "
                + "with a one-line summary. With it, returns the full text of that preset so you can follow it. "
                + "Read-only.";
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
    public ToolResult execute(Map<String, Object> parameters) {
        Path dir = SettingsManager.localConfigDirectory().resolve("ai-prompts");
        if (!Files.isDirectory(dir)) {
            return ToolResult.success("No prompt library exists yet.\n"
                    + "Create the folder and add .md or .txt preset files here:\n" + dir.toAbsolutePath());
        }

        String name = InstanceToolSupport.string(parameters, "name");
        if (name == null) {
            name = InstanceToolSupport.string(parameters, "query");
        }

        if (name == null) {
            return list(dir);
        }
        return read(dir, name);
    }

    private ToolResult list(Path dir) {
        List<Path> presets = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(PromptLibraryTool::isPreset)
                    .sorted()
                    .forEach(presets::add);
        } catch (IOException e) {
            return ToolResult.failure("Failed to list the prompt library: " + e.getMessage());
        }

        if (presets.isEmpty()) {
            return ToolResult.success("The prompt library is empty.\nAdd .md or .txt preset files to: "
                    + dir.toAbsolutePath());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Prompt library (").append(presets.size()).append("):\n");
        int shown = Math.min(presets.size(), MAX_LIST);
        for (int i = 0; i < shown; i++) {
            Path preset = presets.get(i);
            sb.append("  - ").append(baseName(preset));
            String summary = summarize(preset);
            if (summary != null) {
                sb.append("  — ").append(summary);
            }
            sb.append('\n');
        }
        sb.append("\nCall prompt_library with name=\"<name>\" to load a preset's full text.");
        return ToolResult.success(sb.toString().trim());
    }

    private ToolResult read(Path dir, String name) {
        Path resolved = resolve(dir, name);
        if (resolved == null) {
            return ToolResult.failure("No preset named '" + name + "' was found. "
                    + "Call prompt_library with no parameter to see the available presets.");
        }
        String content;
        try {
            content = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure("Failed to read preset '" + name + "': " + e.getMessage());
        }
        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS);
        }
        return ToolResult.success("Preset: " + baseName(resolved) + (truncated ? " (truncated)" : "") + "\n\n"
                + content.strip());
    }

    /// Resolves a preset by its base name (case-insensitive), with or without extension,
    /// guarding against path traversal by matching only direct children of the prompts dir.
    private static Path resolve(Path dir, String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        // Strip a known extension from the request so "crash" and "crash.md" both work.
        if (wanted.endsWith(".md") || wanted.endsWith(".txt")) {
            wanted = wanted.substring(0, wanted.lastIndexOf('.'));
        }
        try (Stream<Path> stream = Files.list(dir)) {
            String target = wanted;
            return stream.filter(Files::isRegularFile)
                    .filter(PromptLibraryTool::isPreset)
                    .filter(p -> baseName(p).toLowerCase(Locale.ROOT).equals(target))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isPreset(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".txt");
    }

    private static String baseName(Path path) {
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /// Returns the first non-blank, non-heading-marker line of the preset (trimmed) as a
    /// short summary, or {@code null} if none can be read.
    private static String summarize(Path path) {
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                while (line.startsWith("#")) {
                    line = line.substring(1).strip();
                }
                if (!line.isEmpty()) {
                    return line.length() > 80 ? line.substring(0, 80) + "…" : line;
                }
            }
        } catch (IOException ignored) {
            // Summary is best-effort.
        }
        return null;
    }
}
