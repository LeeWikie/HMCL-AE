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

/// Writes a UTF-8 text file inside one of several allowlisted root directories,
/// creating parent directories as needed. Overwrites by default; pass `append=true`
/// to append. Controlled-write tool gated by the approval system.
@NotNullByDefault
public final class WriteFileTool implements ToolSpec {
    private final List<Path> roots = new ArrayList<>();
    private final ReadLedger ledger;

    public WriteFileTool(Path primaryRoot) {
        this(primaryRoot, ReadLedger.global());
    }

    /// Ledger-injecting constructor for tests (production wiring uses [`ReadLedger#global`]
    /// so reads recorded by [`FileReadTool`] are visible here).
    public WriteFileTool(Path primaryRoot, ReadLedger ledger) {
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
        return "write";
    }

    @Override
    public String getDescription() {
        StringBuilder sb = new StringBuilder("Write a UTF-8 text file (creating parent directories). ");
        sb.append("Pass 'path' (relative to the config root or absolute) and 'content'. ");
        sb.append("Set 'append' to true to append instead of overwrite. ");
        sb.append("Overwriting an EXISTING file requires having read it with the read tool first ");
        sb.append("(creating a new file does not).");
        sb.append(FileToolFailures.allowedRootsSentence(roots));
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
                   "path": {
                     "type": "string",
                     "description": "Target file path, relative to the config root or absolute (must be under an allowed root)."
                   },
                   "content": {
                     "type": "string",
                     "description": "The full text content to write."
                   },
                   "append": {
                     "type": "boolean",
                     "description": "Append to the file instead of overwriting (default false)."
                   }
                 },
                 "required": ["path", "content"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object pathObj = parameters.get("path");
        if (pathObj == null || pathObj.toString().isBlank()) {
            return ToolResult.failure("No 'path' provided.");
        }
        String content = parameters.getOrDefault("content", "").toString();
        boolean append = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("append", false)));

        try {
            Path candidate = Path.of(pathObj.toString());
            Path resolved = (candidate.isAbsolute() ? candidate : roots.get(0).resolve(pathObj.toString()))
                    .toAbsolutePath().normalize();
            if (roots.stream().noneMatch(resolved::startsWith)) {
                return FileToolFailures.outsideRoots(resolved, roots);
            }
            // Real-path containment: mirrors EditTool's symlink-escape fix, extended to also cover
            // a target that does NOT exist yet. `Path#toRealPath()` requires the path to exist, so
            // the previous code skipped this whole check whenever the leaf was absent — which is
            // exactly the "create a new file" case, i.e. the common one. That let a directory
            // symlink/junction planted under an allowed root (e.g. via `mklink /J`, buildable with
            // plain user rights on Windows and unflagged by today's DangerousCommands heuristics)
            // redirect a "new file" write to anywhere on disk: the lexical/normalized containment
            // check above happens BEFORE any symlink is followed, so it can't see the redirect.
            // realPathForWrite() walks up to the nearest EXISTING ancestor (there is always at
            // least one — the filesystem root) and resolves that ancestor's real path, which
            // correctly follows any symlink/junction sitting above the not-yet-created leaf,
            // regardless of how many nonexistent path segments sit beneath it.
            Path real = realPathForWrite(resolved);
            if (roots.stream().noneMatch(r -> real.startsWith(realOrSelf(r)))) {
                return FileToolFailures.outsideRoots(real, roots);
            }
            // Read precondition for OVERWRITING an existing regular file: without a prior read
            // the model is destroying content it has never seen. Creating a new file is
            // unrestricted, and append is exempt too (it does not discard existing content).
            if (!append && Files.isRegularFile(resolved)) {
                ReadLedger.Status ledgerStatus = ledger.check(real, Files.readAllBytes(resolved));
                if (ledgerStatus == ReadLedger.Status.NOT_READ) {
                    return ToolFailures.failure(
                            "File already exists and has not been read yet — read it first before overwriting (" + real + ")",
                            ToolFailures.Retryable.LATER,
                            "overwriting content you have never seen risks silent data loss",
                            "call read on this path to confirm what you are replacing, then retry the write (or use append:true to add instead of replace)");
                }
                if (ledgerStatus == ReadLedger.Status.STALE) {
                    return ToolFailures.failure(
                            "The file has been modified since it was last read — re-read it before overwriting (" + real + ")",
                            ToolFailures.Retryable.LATER,
                            "the on-disk content changed after your last read",
                            "call read on this path again to see the current content, then retry the write");
                }
            }
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (append) {
                Files.write(resolved, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.write(resolved, bytes);
            }
            // Self-record the written bytes so a follow-up edit/overwrite doesn't demand a
            // redundant re-read. For append the ledger must reflect the WHOLE resulting file.
            byte[] wholeFile = append ? Files.readAllBytes(resolved) : bytes;
            ledger.recordRead(resolved.toRealPath(), wholeFile);
            StringBuilder receipt = new StringBuilder(append ? "Appended to " : "Wrote ")
                    .append(resolved).append(" (").append(bytes.length).append(" bytes).");
            String warning;
            try {
                warning = PostWriteValidator.validate(resolved, new String(wholeFile, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                warning = null; // validation is best-effort; the write itself already succeeded
            }
            if (warning != null) {
                receipt.append('\n').append(warning);
            }
            return ToolResult.success(receipt.toString());
        } catch (IOException e) {
            return FileToolFailures.io("writing the file", e);
        } catch (RuntimeException e) {
            return FileToolFailures.invalid("path", e);
        }
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself when that
    /// fails (e.g. {@code p} doesn't exist) — used only against already-normalized allowed roots,
    /// so the fallback just means "root not present on disk", not a symlink-escape risk. Mirrors
    /// {@link EditTool#realOrSelf}.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    /// Real-path resolution that also works for a target that does not exist yet (the "create a
    /// new file" case). {@link Path#toRealPath()} throws unless the path exists, so it cannot be
    /// called on `resolved` directly when the leaf is absent — and silently skipping the check in
    /// that case (as this method's predecessor did) is unsafe: any ancestor directory between the
    /// allowed root and the new leaf could be a symlink/directory-junction pointing outside every
    /// allowed root, and a lexical/normalized `startsWith` check can't detect that because it never
    /// follows the link.
    ///
    /// The fix walks upward from `path` component by component until it finds an ancestor that
    /// DOES exist (there is always at least one — the filesystem root itself), resolves that
    /// ancestor's real path — which correctly follows any symlink/junction sitting anywhere along
    /// the way — and then re-appends the (still nonexistent) trailing segments that were peeled off
    /// on the way up. If `path` itself already exists this degenerates to a plain
    /// {@code path.toRealPath()}, i.e. identical to the pre-existing-target behavior.
    private static Path realPathForWrite(Path path) throws IOException {
        Path existing = path;
        Path remainder = null;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Path parent = existing.getParent();
            if (parent == null) {
                // Nothing along the path exists, not even a filesystem root — nothing to resolve.
                // Falls back to the lexical path; the caller's normalized-containment check
                // (already performed) is what governs this (environment-misconfiguration) case.
                return path;
            }
            Path segment = existing.getFileName();
            remainder = remainder == null ? segment : segment.resolve(remainder);
            existing = parent;
        }
        Path real = existing.toRealPath();
        return remainder == null ? real : real.resolve(remainder);
    }
}
