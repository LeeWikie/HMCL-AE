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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Optional capability a {@link ToolSpec} implements when a single call of it EDITS (or removes) one
/// pre-existing file whose whole content can be cheaply snapshotted before the change. It lets the
/// {@link org.jackhuang.hmcl.ai.langchain4j.LangChain4jToolAdapter} turn the ⚠️ASK confirmation the
/// policy stamps on such an edit into an automatic ALLOW *once the file has been backed up* — the
/// product decision (2026-07-11) that editing a small file is cheap-and-reversible enough not to
/// interrupt the user, provided a restore point is captured first.
///
/// The adapter ONLY consults this for a call the policy already classified as the CONTROLLED_WRITE
/// edit/remove ASK tier (see {@link EditOrRemoveActions}) — never for a DANGEROUS_WRITE / CRITICAL
/// operation, which stays gated no matter what this returns. Returning {@code null} (no resolvable
/// single-file target) simply leaves the original ASK in place, so implementing this can only ever
/// RELAX a confirmation, never add or remove a gate elsewhere.
@NotNullByDefault
public interface BackupTargetResolver {

    /// The single pre-existing file this specific call will modify in place — already validated by
    /// the implementing tool to exist, to be a regular file, and to lie within {@code allowedRoots}
    /// — together with the roots it is confined to (so {@link FileBackup} can re-assert containment
    /// as defence-in-depth). Returns {@code null} when the call has no single small-file target that
    /// can be safely snapshotted, in which case the adapter keeps the original ASK.
    ///
    /// @param params the call's already-parsed parameter map (same map passed to {@link Tool#execute})
    @Nullable
    Target resolveBackupTarget(Map<String, Object> params);

    /// A resolved backup target: the existing file to snapshot plus the allowlisted roots it must
    /// stay within. {@code allowedRoots} may be empty, meaning "the caller has no root list to
    /// enforce" — {@link FileBackup} then skips the redundant containment re-check (the resolver is
    /// still responsible for only returning a legitimate target).
    record Target(Path file, List<Path> allowedRoots) {
    }
}
