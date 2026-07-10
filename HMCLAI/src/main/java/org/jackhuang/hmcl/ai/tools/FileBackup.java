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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/// Lightweight, single-file snapshot helper used to make a small-file edit/remove cheaply
/// reversible so the adapter can auto-approve it (see {@link BackupTargetResolver}). It copies the
/// target's current bytes to a sibling `<name>.bak` BEFORE the edit runs, giving a one-step "undo
/// the last change" restore point.
///
/// Deliberately narrow (this is NOT the versioned world-backup engine):
///   - **size-capped** — refuses anything larger than the caller's threshold (a snapshot is only
///     "instant/cheap" for a genuinely small file); the caller keeps the ⚠️ASK instead;
///   - **idempotent** — if the sibling `.bak` already holds bytes identical to the target, it is a
///     no-op that reports success (re-backing-up the same content never fans out copies);
///   - **root-confined** — re-asserts, via the real (symlink-resolved) path, that the target lies
///     within the caller's allowlisted roots, so a resolver bug can never make it copy an arbitrary
///     external file;
///   - **fail-closed** — ANY I/O problem (unwritable directory, a directory occupying the `.bak`
///     path, an undecodable/oversize target) returns a failure so the adapter falls back to ASK
///     rather than silently proceeding without a restore point.
@NotNullByDefault
public final class FileBackup {

    /// Default "small file" ceiling: 1 MiB. Files at or below this are snapshotted; larger ones keep
    /// their confirmation. A default (the product-agreed value, 2026-07-11); the adapter passes it
    /// explicitly so a future user-tunable setting can override it without touching this class.
    public static final long DEFAULT_MAX_BYTES = 1_048_576L;

    private FileBackup() {
    }

    /// Outcome of a backup attempt. {@code success} with a non-null {@code backupPath} means a
    /// restore point exists (freshly written OR already-present-and-identical); a failure carries a
    /// short {@code reason} and leaves {@code backupPath} null. {@code tooLarge} is a distinct
    /// failure flavour so the caller can tell "file exceeded the threshold" (keep ASK, expected)
    /// apart from a genuine I/O error.
    public record Result(boolean success, @Nullable Path backupPath, boolean tooLarge, @Nullable String reason) {
        static Result ok(Path backupPath) {
            return new Result(true, backupPath, false, null);
        }

        static Result tooLarge(long size, long maxBytes) {
            return new Result(false, null, true,
                    "file is " + size + " bytes, over the " + maxBytes + "-byte snapshot limit");
        }

        static Result failed(String reason) {
            return new Result(false, null, false, reason);
        }
    }

    /// Snapshots {@code target.file()} to a sibling `<name>.bak`, enforcing the size cap and (when
    /// {@code target.allowedRoots()} is non-empty) real-path containment within those roots. Never
    /// throws — every failure mode is reported through {@link Result}.
    ///
    /// @param target   the existing file to snapshot plus its confining roots (see
    ///                 {@link BackupTargetResolver.Target})
    /// @param maxBytes the inclusive size ceiling; a larger file yields {@link Result#tooLarge()}
    public static Result backup(BackupTargetResolver.Target target, long maxBytes) {
        Path file = target.file();
        try {
            if (!Files.isRegularFile(file)) {
                return Result.failed("target is not a regular file: " + file);
            }
            // Real-path containment: resolve symlinks so a link planted inside a root but pointing
            // OUTSIDE it can't trick us into copying (and thereby exposing) an arbitrary file.
            Path real = file.toRealPath();
            if (!target.allowedRoots().isEmpty()
                    && target.allowedRoots().stream().noneMatch(r -> real.startsWith(realOrSelf(r)))) {
                return Result.failed("target is outside the allowed roots: " + real);
            }
            long size = Files.size(real);
            if (size > maxBytes) {
                return Result.tooLarge(size, maxBytes);
            }

            Path backup = real.resolveSibling(real.getFileName() + ".bak");
            // Idempotent short-circuit: an identical snapshot already exists — nothing to do. Also
            // avoids a needless rewrite when the same file is edited twice with no change between.
            if (Files.isRegularFile(backup) && Arrays.equals(Files.readAllBytes(backup), Files.readAllBytes(real))) {
                return Result.ok(backup);
            }
            // REPLACE_EXISTING so the snapshot always reflects the immediately-prior state (undo the
            // LAST edit). If `backup` is a non-empty directory this throws (fail-closed → ASK).
            Files.copy(real, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return Result.ok(backup);
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage();
            return Result.failed(message != null && !message.isBlank() ? message : e.getClass().getSimpleName());
        }
    }

    /// Resolves symlinks via {@link Path#toRealPath()}, falling back to {@code p} itself when that
    /// fails (e.g. the root doesn't exist on disk) — mirrors {@code EditTool.realOrSelf}. Used only
    /// against already-normalized allowed roots, so the fallback just means "root not present",
    /// never a symlink-escape risk.
    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }
}
