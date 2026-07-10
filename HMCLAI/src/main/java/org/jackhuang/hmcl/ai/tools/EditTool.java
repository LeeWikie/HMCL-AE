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

/// Targeted in-place edit: replaces an exact substring in a text file, inside one of the
/// allowlisted roots. Unlike {@link WriteFileTool}, it changes only the matched text.
/// By default the match must be unique; pass `replaceAll=true` to replace every occurrence.
///
/// File-tool contract (this wave):
///   - **read precondition / staleness** — the file must have been read (via the `read` tool)
///     and be unchanged on disk since, enforced through the shared [`ReadLedger`];
///   - **no-op interception** — `old_string == new_string` is rejected instead of returning a
///     fake success;
///   - **fallback matching** — when the exact substring is absent, a line-trimmed →
///     whole-trimmed → whitespace-normalized [`EditReplacers`] chain absorbs CRLF/indentation
///     drift, with an anti-over-match circuit breaker;
///   - **post-write validation** — `.json`/`.properties` content is re-validated after the
///     write and a WARNING is appended to the success receipt when it came out broken.
@NotNullByDefault
public final class EditTool implements ToolSpec, BackupTargetResolver {
    private final List<Path> roots = new ArrayList<>();
    private final ReadLedger ledger;

    public EditTool(Path primaryRoot) {
        this(primaryRoot, ReadLedger.global());
    }

    /// Ledger-injecting constructor for tests (production wiring uses [`ReadLedger#global`]
    /// so reads recorded by [`FileReadTool`] are visible here).
    public EditTool(Path primaryRoot, ReadLedger ledger) {
        roots.add(primaryRoot.toAbsolutePath().normalize());
        this.ledger = ledger;
    }

    public void addRoot(@Nullable Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (!roots.contains(normalized)) roots.add(normalized);
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.FILESYSTEM;
    }

    @Override
    public String getName() {
        return "edit";
    }

    @Override
    public String getDescription() {
        return "Replace an exact text snippet in a file in place. Pass 'path', 'old_string' "
                + "(must match exactly), and 'new_string'. The match must be unique unless "
                + "'replace_all' is true. Prefer this over write for small changes. "
                + "The file must have been read with the read tool first (and re-read after "
                + "any external change), or the edit is refused."
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
                   "path": {"type": "string", "description": "File path, relative to the config root or absolute (under an allowed root)."},
                   "old_string": {"type": "string", "description": "Exact text to replace."},
                   "new_string": {"type": "string", "description": "Replacement text."},
                   "replace_all": {"type": "boolean", "description": "Replace every occurrence (default false)."}
                 },
                 "required": ["path", "old_string", "new_string"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object pathObj = parameters.get("path");
        if (pathObj == null || pathObj.toString().isBlank()) {
            return ToolResult.failure("No 'path' provided.");
        }
        String oldString = String.valueOf(parameters.getOrDefault("old_string", ""));
        String newString = String.valueOf(parameters.getOrDefault("new_string", ""));
        boolean replaceAll = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("replace_all", false)));
        if (oldString.isEmpty()) {
            return ToolResult.failure("'old_string' must not be empty.");
        }
        // No-op interception (spec rewrite #3): replacing text with itself would previously
        // return "Edited ... (1 replacement(s))" while the file bytes stayed identical — a
        // silent fake success, worse than an error.
        if (oldString.equals(newString)) {
            return ToolFailures.failure(
                    "'old_string' and 'new_string' are identical — nothing to change",
                    ToolFailures.Retryable.NO,
                    "this call is a no-op by definition",
                    "if you intended a real edit, fix new_string; if you only wanted to verify the text exists, use read instead");
        }

        try {
            Path candidate = Path.of(pathObj.toString());
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith)) {
                return FileToolFailures.outsideRoots(resolved, roots);
            }
            if (!Files.isRegularFile(resolved)) {
                return ToolResult.failure("File does not exist: " + resolved);
            }
            // Real-path containment: the file must already exist (checked above), so it's safe to
            // resolve symlinks here — a symlink planted under an allowed root but pointing OUTSIDE
            // it would otherwise let an edit through the lexical/normalized containment check above
            // and overwrite an arbitrary external file's content.
            Path real = resolved.toRealPath();
            if (roots.stream().noneMatch(r -> real.startsWith(realOrSelf(r)))) {
                return FileToolFailures.outsideRoots(real, roots);
            }

            byte[] raw = Files.readAllBytes(resolved);
            // Read precondition + staleness detection against the shared ledger. Checked on the
            // raw bytes BEFORE decoding, so even undecodable external changes are caught.
            ReadLedger.Status ledgerStatus = ledger.check(real, raw);
            if (ledgerStatus == ReadLedger.Status.NOT_READ) {
                return ToolFailures.failure(
                        "File has not been read yet — read it first before editing (" + real + ")",
                        ToolFailures.Retryable.LATER,
                        "old_string must be copied from the file's actual content, not guessed",
                        "call read on this path, then retry the edit with old_string taken from what read returned");
            }
            if (ledgerStatus == ReadLedger.Status.STALE) {
                return ToolFailures.failure(
                        "The file has been modified since it was last read — re-read it before editing (" + real + ")",
                        ToolFailures.Retryable.LATER,
                        "the on-disk content no longer matches what old_string was based on",
                        "call read on this path again, then retry the edit with old_string taken from the fresh content");
            }

            // Strict decode (same failure behavior as the previous Files.readString): a
            // malformed-UTF-8 file must error out, NOT be decoded leniently and re-encoded
            // with replacement characters, which would corrupt it on write-back.
            String content = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString();
            EditReplacers.Result match = EditReplacers.locate(content, oldString);
            if (match.disproportionate()) {
                EditReplacers.Span span = match.spans().get(0);
                return ToolFailures.failure(
                        "Refusing the replacement: the closest match (via the " + match.strategy()
                                + " fallback) spans " + span.length() + " characters, far more than old_string's "
                                + oldString.length(),
                        ToolFailures.Retryable.YES,
                        "a fuzzy match that much larger than old_string almost certainly grabbed unintended text",
                        "re-read the file and provide the full exact old_string for the intended replacement");
            }
            List<EditReplacers.Span> spans = match.spans();
            if (spans.isEmpty()) {
                return ToolFailures.failure(
                        "'old_string' not found in " + resolved,
                        ToolFailures.Retryable.YES,
                        "it must match the file's current content (near-miss fallbacks ignoring whitespace also found nothing)",
                        "re-read the file and copy old_string exactly from what read returns, including punctuation");
            }
            if (spans.size() > 1 && !replaceAll) {
                return ToolFailures.failure(
                        "'old_string' is not unique (" + spans.size() + " matches)",
                        ToolFailures.Retryable.YES,
                        "an ambiguous match must not silently pick one occurrence",
                        "set replace_all to change every occurrence, or add surrounding context to old_string to make it unique");
            }

            List<EditReplacers.Span> toReplace = replaceAll ? spans : List.of(spans.get(0));
            StringBuilder updated = new StringBuilder(content);
            for (int i = toReplace.size() - 1; i >= 0; i--) {
                EditReplacers.Span span = toReplace.get(i);
                updated.replace(span.start(), span.end(), newString);
            }
            String result = updated.toString();
            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
            Files.write(resolved, resultBytes);
            // Self-record the write so a follow-up edit doesn't demand a redundant re-read.
            ledger.recordRead(real, resultBytes);

            StringBuilder receipt = new StringBuilder("Edited ").append(resolved)
                    .append(" (").append(toReplace.size()).append(" replacement(s)");
            if (!match.isExact()) {
                receipt.append(", matched via the ").append(match.strategy())
                        .append(" fallback — surrounding whitespace was taken from the file, not from old_string");
            }
            receipt.append(").");
            String warning = PostWriteValidator.validate(resolved, result);
            if (warning != null) {
                receipt.append('\n').append(warning);
            }
            return ToolResult.success(receipt.toString());
        } catch (IOException e) {
            return FileToolFailures.io("editing the file", e);
        } catch (RuntimeException e) {
            return FileToolFailures.invalid("edit request", e);
        }
    }

    /// Backup-target resolution for the adapter's small-file ASK→ALLOW downgrade (see
    /// {@link BackupTargetResolver}): an `edit` call ALWAYS edits one pre-existing file, so its
    /// snapshot target is exactly that file. Resolves the `path` argument the same way
    /// {@link #execute} does (relative → first root; symlink-resolved real-path containment), and
    /// returns it ONLY when it is an existing regular file inside an allowed root. Any missing arg,
    /// containment failure, or non-file yields {@code null} (best-effort — the adapter then simply
    /// keeps the confirmation), and this never throws.
    @Override
    @Nullable
    public Target resolveBackupTarget(Map<String, Object> parameters) {
        Object pathObj = parameters.get("path");
        if (pathObj == null || pathObj.toString().isBlank()) {
            return null;
        }
        try {
            Path candidate = Path.of(pathObj.toString());
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith) || !Files.isRegularFile(resolved)) {
                return null;
            }
            Path real = resolved.toRealPath();
            if (roots.stream().noneMatch(r -> real.startsWith(realOrSelf(r)))) {
                return null;
            }
            return new Target(real, List.copyOf(roots));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself when that
    /// fails (e.g. {@code p} doesn't exist) — used only against already-normalized allowed roots,
    /// so the fallback just means "root not present on disk", not a symlink-escape risk.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }
}
