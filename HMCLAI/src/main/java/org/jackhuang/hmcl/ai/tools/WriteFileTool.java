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
            // Real-path containment for an EXISTING target: mirrors EditTool's symlink-escape fix.
            // A symlink can be planted under an allowed root (e.g. via the shell tool's `mklink` /
            // `ln -s`, which today's DangerousCommands heuristics do not flag) pointing OUTSIDE every
            // allowed root. Since this tool overwrites by default, writing through such a symlink
            // would otherwise let the lexical/normalized containment check above pass while the
            // bytes actually land in an arbitrary external file. A target that does not exist yet has
            // nothing to resolve — that case is unaffected and still lets new files be created.
            Path real = Files.exists(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ? resolved.toRealPath() : null;
            if (real != null && roots.stream().noneMatch(r -> real.startsWith(realOrSelf(r)))) {
                return FileToolFailures.outsideRoots(real, roots);
            }
            // Read precondition for OVERWRITING an existing regular file: without a prior read
            // the model is destroying content it has never seen. Creating a new file is
            // unrestricted, and append is exempt too (it does not discard existing content).
            if (real != null && !append && Files.isRegularFile(resolved)) {
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
}
