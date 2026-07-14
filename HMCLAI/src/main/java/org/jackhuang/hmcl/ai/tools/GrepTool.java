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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Searches file contents by regular expression under an allowlisted root, returning
/// matching `path:line: text` entries. Optionally limited to a sub-path and a filename
/// glob. Read-only.
@NotNullByDefault
public final class GrepTool implements ToolSpec {
    private static final int MAX_MATCHES = 200;
    private static final int MAX_FILES = 5000;
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    /// Allowlisted roots. Mutated from the FX thread ({@link #addRoot}/{@link #setInstanceRoot})
    /// while the worker thread iterates it in {@link #execute}, so it must be concurrency-safe or the
    /// turn dies on a {@link java.util.ConcurrentModificationException} — the same FX-vs-worker race
    /// the {@link ToolRegistry} lock fixes. {@link CopyOnWriteArrayList} suffices because all mutation
    /// happens on the single FX thread (no writer-writer race), while readers get a stable snapshot
    /// and never CME.
    private final List<Path> roots = new CopyOnWriteArrayList<>();

    public GrepTool(Path primaryRoot) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
    }

    public void addRoot(@Nullable Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (!roots.contains(normalized)) roots.add(normalized);
    }

    @Nullable
    private Path instanceRoot;
    @Nullable
    private List<Path> staticRootsSnapshot;

    /// Rebases the single per-instance allowed root (§3.8): replaces the previously-set instance
    /// root rather than accumulating, and {@code null} clears it — so a previously-selected
    /// instance's files stop being reachable the moment the user switches instances. Static roots
    /// present at the first call (config dir, HMCL home) are never removed here. See
    /// {@code FileReadTool#setInstanceRoot} for the full rationale.
    public void setInstanceRoot(@Nullable Path root) {
        if (staticRootsSnapshot == null) {
            staticRootsSnapshot = List.copyOf(roots);
        }
        Path normalized = root == null ? null : root.toAbsolutePath().normalize();
        if (java.util.Objects.equals(instanceRoot, normalized)) {
            return;
        }
        if (instanceRoot != null && !staticRootsSnapshot.contains(instanceRoot)) {
            roots.remove(instanceRoot);
        }
        instanceRoot = normalized;
        if (normalized != null && !roots.contains(normalized)) {
            roots.add(normalized);
        }
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
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents by regular expression. Pass 'pattern' (Java regex), "
                + "optional 'path' (sub-directory or single file to search) and optional 'glob' "
                + "(filename filter like *.json, ignored when 'path' names a single file). "
                + "Optional 'contextBefore'/'contextAfter' include surrounding lines like grep -B/-A. "
                + "Returns matching 'path:line: text' lines (context lines use 'path-line- text')."
                + FileToolFailures.allowedRootsSentence(roots);
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
                   "pattern": {"type": "string", "description": "Java regular expression to search for."},
                   "path": {"type": "string", "description": "Optional sub-directory or single file (relative to config root or absolute) to search under."},
                   "glob": {"type": "string", "description": "Optional filename glob filter, e.g. *.json or *.log."},
                   "contextBefore": {"type": "integer", "minimum": 0, "description": "Optional number of lines of context to include before each match, like grep -B. Default 0. Context lines are printed as 'path-line- text'."},
                   "contextAfter": {"type": "integer", "minimum": 0, "description": "Optional number of lines of context to include after each match, like grep -A. Default 0. Context lines are printed as 'path-line- text'."}
                 },
                 "required": ["pattern"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object patternObj = parameters.get("pattern");
        if (patternObj == null || patternObj.toString().isEmpty()) {
            return ToolResult.failure("No 'pattern' provided.");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternObj.toString());
        } catch (RuntimeException e) {
            // Spec rewrite #2: carry the dialect hint and forbid an unchanged retry.
            return ToolFailures.failure(
                    "Invalid regex: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    ToolFailures.Retryable.YES,
                    "this is Java regex, not glob/PCRE",
                    "escape literal '(' ')' '.' etc. and retry; do not retry unchanged");
        }

        int contextBefore = optionalNonNegativeInt(parameters, "contextBefore");
        int contextAfter = optionalNonNegativeInt(parameters, "contextAfter");

        Path base;
        Object pathObj = parameters.get("path");
        if (pathObj != null && !pathObj.toString().isBlank()) {
            Path candidate = Path.of(pathObj.toString());
            base = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(base::startsWith)) {
                return FileToolFailures.outsideRoots(base, roots);
            }
        } else {
            base = roots.get(0);
        }
        if (!Files.exists(base)) {
            return ToolResult.failure("Path does not exist: " + base);
        }
        // Real-path containment: `base` passed the lexical/normalized check above, but it (or an
        // allowed root itself) could still be a symlink pointing outside every allowed root.
        List<Path> realRoots = realRootsOf(roots);
        Path realBase = realOrSelf(base);
        if (realRoots.stream().noneMatch(realBase::startsWith)) {
            return FileToolFailures.outsideRoots(realBase, roots);
        }

        if (Files.isRegularFile(base)) {
            List<String> results = new ArrayList<>();
            Path fileName = base.getFileName();
            String rel = fileName != null ? fileName.toString() : base.toString();
            searchFile(base, rel, pattern, results, contextBefore, contextAfter);
            if (results.isEmpty()) {
                return ToolResult.success("(no matches)");
            }
            return ToolResult.success(String.join("\n", results));
        }

        if (!Files.isDirectory(base)) {
            return ToolResult.failure("Not a directory: " + base);
        }

        Object globObj = parameters.get("glob");
        String glob = globObj == null ? null : globObj.toString();
        java.nio.file.PathMatcher matcher = glob == null || glob.isBlank()
                ? null : base.getFileSystem().getPathMatcher("glob:" + glob);

        List<String> results = new ArrayList<>();
        int[] filesScanned = {0};
        try (Stream<Path> walk = Files.walk(base)) {
            var it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext() && results.size() < MAX_MATCHES && filesScanned[0] < MAX_FILES) {
                Path file = it.next();
                // Files::isRegularFile follows symlinks, so a symlink planted under an allowed
                // root but pointing OUTSIDE it would otherwise be searched (and its matching lines
                // leaked into the result) despite living outside every allowed root.
                Path realFile = realOrSelf(file);
                if (realRoots.stream().noneMatch(realFile::startsWith)) {
                    continue;
                }
                if (matcher != null && file.getFileName() != null && !matcher.matches(file.getFileName())) {
                    continue;
                }
                String rel = base.relativize(file).toString();
                if (searchFile(file, rel, pattern, results, contextBefore, contextAfter)) {
                    filesScanned[0]++;
                }
            }
        } catch (IOException e) {
            return FileToolFailures.io("walking the directory tree", e);
        }

        if (results.isEmpty()) {
            return ToolResult.success("(no matches)");
        }
        return ToolResult.success(String.join("\n", results));
    }

    private static List<Path> realRootsOf(List<Path> roots) {
        List<Path> real = new ArrayList<>(roots.size());
        for (Path r : roots) {
            real.add(realOrSelf(r));
        }
        return real;
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself (already
    /// absolute/normalized by the caller) when that fails — e.g. a broken symlink, or a race where
    /// the file vanished between being listed and checked.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    /// Scans a single file for [pattern] matches, appending formatted `rel:line: text`
    /// entries to [results] (capped at [MAX_MATCHES], each line truncated to 200 chars).
    /// Shared by both the single-file and directory-walk search paths.
    ///
    /// When [contextBefore]/[contextAfter] are positive, the lines surrounding each match are
    /// also appended (like grep -B/-A) as `rel-line- text`. Overlapping or adjacent windows are
    /// de-duplicated so no source line is emitted twice; a context line that itself matches
    /// [pattern] is still marked as a match (printed with `:` rather than `-`).
    ///
    /// @return `true` if the file was within [MAX_FILE_SIZE] and thus actually scanned
    ///         (even if it turned out unreadable/binary); `false` if skipped due to size.
    private static boolean searchFile(Path file, String rel, Pattern pattern, List<String> results,
                                      int contextBefore, int contextAfter) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE) return false;
        } catch (IOException e) {
            return false;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return true; // binary / unreadable, but still counts as scanned
        }
        if (contextBefore <= 0 && contextAfter <= 0) {
            // Fast path: no context requested — behaviour identical to before this feature existed.
            for (int i = 0; i < lines.size() && results.size() < MAX_MATCHES; i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    results.add(rel + ":" + (i + 1) + ": " + truncate(lines.get(i)));
                }
            }
            return true;
        }
        // Context path: emit each match together with its surrounding window. `lastEmitted` is the
        // highest line index already appended for this file, so overlapping/adjacent windows never
        // reprint a line (and the earlier window's copy wins, preserving output order).
        int lastEmitted = -1;
        for (int i = 0; i < lines.size() && results.size() < MAX_MATCHES; i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                int from = Math.max(0, i - contextBefore);
                int to = Math.min(lines.size() - 1, i + contextAfter);
                if (from <= lastEmitted) {
                    from = lastEmitted + 1; // skip lines already emitted by an earlier window
                }
                for (int j = from; j <= to && results.size() < MAX_MATCHES; j++) {
                    boolean isMatch = j == i || pattern.matcher(lines.get(j)).find();
                    String sep = isMatch ? ":" : "-";
                    results.add(rel + sep + (j + 1) + sep + " " + truncate(lines.get(j)));
                    lastEmitted = j;
                }
            }
        }
        return true;
    }

    /// Strips a source line and caps it at 200 characters (appending an ellipsis when truncated),
    /// matching the length handling applied to grep result lines since before context support.
    private static String truncate(String line) {
        String text = line.strip();
        if (text.length() > 200) text = text.substring(0, 200) + "…";
        return text;
    }

    /// Reads an optional non-negative integer parameter, tolerating the numeric ({@link Number})
    /// and string encodings the model may send. Missing, negative, or unparseable values fall back
    /// to {@code 0} so the context feature stays off by default and never fails a search.
    private static int optionalNonNegativeInt(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            int n = ((Number) value).intValue();
            return Math.max(n, 0);
        }
        try {
            int n = Integer.parseInt(value.toString().trim());
            return Math.max(n, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
