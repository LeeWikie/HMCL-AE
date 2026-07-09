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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Read-before-edit ledger: records the content hash of every file the agent has read via
/// [`FileReadTool`], so that [`EditTool`] / [`WriteFileTool`] can enforce the two file-tool
/// contract rules borrowed from Claude Code:
///
///   1. **Read precondition** — a file that was never read must not be edited (or overwritten):
///      the model would only be guessing at `old_string` / the current content.
///   2. **Staleness detection** — if the file changed on disk after the recorded read (external
///      editor, the game itself, another tool), the edit must be refused until it is re-read.
///
/// Keys are real paths (symlinks resolved) so that the same physical file reached through
/// different spellings maps to one entry. Values are SHA-256 hashes of the raw file **bytes**
/// (not decoded text), so binary-level changes such as a BOM or line-ending rewrite are
/// detected too.
///
/// Lifecycle: one process-wide [`#global`] instance shared by the file tools' default
/// constructors. Entries are only ever replaced by newer reads (including the tools' own
/// successful writes, which self-record so consecutive edits don't force a redundant re-read).
/// The ledger is deliberately NOT turn-scoped: an old read whose hash still matches the bytes
/// on disk is exactly as trustworthy as a fresh one — the hash comparison subsumes recency.
///
/// Thread safety: backed by a [`ConcurrentHashMap`]; safe for arbitrary tool worker threads.
@NotNullByDefault
public final class ReadLedger {

    /// Result of checking a path against the ledger before an edit/overwrite.
    public enum Status {
        /// The file was read and its bytes are unchanged — the edit may proceed.
        OK,
        /// The file was never read (through this ledger) — refuse and ask for a read first.
        NOT_READ,
        /// The file was read, but its bytes changed since — refuse and ask for a re-read.
        STALE
    }

    /// A ledger entry: the content hash recorded at read time plus the read timestamp
    /// (the timestamp is informational; staleness is decided purely by hash comparison).
    public record Entry(String contentHash, long readAtMillis) {
    }

    private static final ReadLedger GLOBAL = new ReadLedger();

    /// The process-wide ledger shared by the file tools' default constructors, so a read done
    /// by the registered `read` tool is visible to the registered `edit`/`write` tools.
    public static ReadLedger global() {
        return GLOBAL;
    }

    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

    /// Records that `path` was read with the given raw content bytes.
    public void recordRead(Path path, byte[] content) {
        entries.put(normalize(path), new Entry(hash(content), System.currentTimeMillis()));
    }

    /// Convenience overload hashing the UTF-8 bytes of `content` (for callers/tests that hold
    /// the decoded text).
    public void recordRead(Path path, String content) {
        recordRead(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /// Checks whether `path`, whose CURRENT raw bytes are `currentContent`, may be edited:
    /// [`Status#NOT_READ`] when no read was recorded, [`Status#STALE`] when the recorded hash
    /// no longer matches, [`Status#OK`] otherwise.
    public Status check(Path path, byte[] currentContent) {
        Entry entry = entries.get(normalize(path));
        if (entry == null) {
            return Status.NOT_READ;
        }
        return entry.contentHash().equals(hash(currentContent)) ? Status.OK : Status.STALE;
    }

    /// Returns the recorded entry for `path`, or `null` when it was never read. Exposed for
    /// tests and diagnostics.
    @Nullable
    public Entry get(Path path) {
        return entries.get(normalize(path));
    }

    /// Removes every entry (test isolation helper).
    public void clear() {
        entries.clear();
    }

    /// Canonicalizes a key: real path when resolvable (so symlink aliases collide), otherwise
    /// absolute+normalized. Tools pass already-real paths; this is a safety net for tests and
    /// future call sites.
    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /// SHA-256 of the raw bytes, hex-encoded. Package-private for tests.
    static String hash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec for every conforming JRE.
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }
}
