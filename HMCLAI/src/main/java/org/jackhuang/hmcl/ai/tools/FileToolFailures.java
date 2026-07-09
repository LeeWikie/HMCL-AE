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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/// Shared failure texts for the filesystem tool family ([`FileReadTool`], [`EditTool`],
/// [`WriteFileTool`], [`GlobTool`], [`GrepTool`]) plus [`WebFetchTool`]'s IO paths:
///
///   - the path-outside-allowed-roots rejection now carries the actual allowed roots
///     (previously 11 call sites said "outside the allowed roots" without ever telling the
///     model what IS allowed — only 2 of 5 tool descriptions listed them);
///   - raw `"IO error: {msg}"` / `"Invalid X: {msg}"` passthroughs are classified by
///     exception subclass into the unified [`ToolFailures`] envelope with a `Retryable`
///     verdict (`NoSuchFileException`/`AccessDeniedException` → terminal, other IO →
///     transient, malformed input → fix-and-retry).
@NotNullByDefault
final class FileToolFailures {

    private FileToolFailures() {
    }

    /// The path-outside-roots failure, spec rewrite #1: names the offending path, states it
    /// is retryable with a corrected path, and enumerates every allowed root.
    static ToolResult outsideRoots(Path attempted, List<Path> roots) {
        return ToolFailures.failure(
                "Path '" + attempted + "' is outside the allowed roots",
                ToolFailures.Retryable.YES,
                "pass a path under one of the allowed roots",
                "allowed roots are " + joinRoots(roots) + "; relative paths resolve against the first root");
    }

    /// `"Allowed roots: a; b."` — the sentence every file tool's `getDescription()` appends so
    /// the model learns the legal range BEFORE hitting the wall.
    static String allowedRootsSentence(List<Path> roots) {
        return " Allowed roots: " + joinRoots(roots) + ".";
    }

    private static String joinRoots(List<Path> roots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(roots.get(i));
        }
        return sb.toString();
    }

    /// Classifies an [`IOException`] raised while `doing` (e.g. `"reading the file"`) into the
    /// unified envelope. `NoSuchFileException` and `AccessDeniedException` are terminal —
    /// retrying the same call cannot succeed — while everything else is treated as possibly
    /// transient (on this codebase's main platform, Windows, that is typically a file locked
    /// by the running game or another process).
    static ToolResult io(String doing, IOException e) {
        if (e instanceof NoSuchFileException || e instanceof FileNotFoundException) {
            String missing = e instanceof NoSuchFileException nsf ? nsf.getFile() : e.getMessage();
            return ToolFailures.failure(
                    "IO error while " + doing + ": no such file or directory" + (missing == null ? "" : ": " + missing),
                    ToolFailures.Retryable.NO,
                    "the path does not exist, so the same call cannot succeed",
                    "switch to an existing path — list the parent directory with read, or find candidates with glob, then retry");
        }
        if (e instanceof AccessDeniedException) {
            return ToolFailures.failure(
                    "IO error while " + doing + ": access denied: " + e.getMessage(),
                    ToolFailures.Retryable.NO,
                    "the launcher process lacks permission for this path",
                    "pick a different location under the allowed roots, or ask the user to fix the file's permissions");
        }
        return ToolFailures.failure(
                "IO error while " + doing + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                ToolFailures.Retryable.LATER,
                "possibly transient — the file may be locked by the running game or another process",
                "retry once after a short wait; if it fails again, stop and report the error to the user");
    }

    /// Classifies a [`RuntimeException`] from malformed input (`InvalidPathException` and
    /// friends) — always fixable by correcting the value, never by retrying unchanged.
    static ToolResult invalid(String what, RuntimeException e) {
        return ToolFailures.failure(
                "Invalid " + what + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                ToolFailures.Retryable.YES,
                "the value is malformed, not the operation",
                "fix the " + what + " format and retry; do not retry unchanged");
    }
}
