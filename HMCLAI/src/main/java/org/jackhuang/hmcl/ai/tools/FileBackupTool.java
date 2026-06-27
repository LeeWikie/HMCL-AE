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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/// A tool that performs atomic file backup and restore with SHA-256 checksums.
///
/// Accepts `action` ("backup" or "restore") and `path` parameters. On backup,
/// the file is copied to `{path}.ae-backup` and its SHA-256 hash is recorded.
/// On restore, the backup is copied back and the hash is verified.
///
/// All operations are idempotent: backup overwrites any existing backup file;
/// restore is a no-op if no backup exists.
@NotNullByDefault
public final class FileBackupTool implements Tool {

    /// Suffix appended to the original path for backup files.
    private static final String BACKUP_SUFFIX = ".ae-backup";

    /// Suffix for the checksum companion file.
    private static final String CHECKSUM_SUFFIX = ".ae-backup.sha256";

    @Override
    public String getName() {
        return "file_backup";
    }

    @Override
    public String getDescription() {
        return "Backup or restore a file atomically. Parameters: action (\"backup\" or \"restore\"), path (absolute file path). On backup, creates a .ae-backup copy with SHA-256 checksum. On restore, copies the backup back. Returns operation result with path and checksum.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = toString(parameters.get("action"));
        String pathStr = toString(parameters.get("path"));

        if (action == null || pathStr == null) {
            return ToolResult.failure("Missing required parameter: action and path are required");
        }

        Path source = Path.of(pathStr).toAbsolutePath().normalize();

        return switch (action) {
            case "backup" -> doBackup(source);
            case "restore" -> doRestore(source);
            default -> ToolResult.failure("Unknown action '" + action + "'. Use 'backup' or 'restore'.");
        };
    }

    /// Creates a backup of the given file.
    private static ToolResult doBackup(Path source) {
        try {
            if (!Files.exists(source)) {
                return ToolResult.failure("File does not exist: " + source);
            }

            Path backup = source.resolveSibling(source.getFileName() + BACKUP_SUFFIX);
            Path checksumFile = source.resolveSibling(source.getFileName() + CHECKSUM_SUFFIX);

            String hash = sha256(source);
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(checksumFile, hash);

            return ToolResult.success("Backed up " + source.getFileName()
                    + " → " + backup.getFileName()
                    + " (SHA-256: " + hash + ")");
        } catch (IOException e) {
            return ToolResult.failure("Backup failed: " + e.getMessage());
        }
    }

    /// Restores a file from its backup.
    private static ToolResult doRestore(Path source) {
        try {
            Path backup = source.resolveSibling(source.getFileName() + BACKUP_SUFFIX);
            Path checksumFile = source.resolveSibling(source.getFileName() + CHECKSUM_SUFFIX);

            if (!Files.exists(backup)) {
                return ToolResult.failure("No backup found for: " + source.getFileName());
            }

            String storedHash = Files.exists(checksumFile)
                    ? Files.readString(checksumFile).trim()
                    : null;
            String currentHash = sha256(backup);

            if (storedHash != null && !storedHash.isEmpty() && !storedHash.equals(currentHash)) {
                return ToolResult.failure("Backup checksum mismatch for "
                        + backup.getFileName() + " — backup may be corrupted");
            }

            Files.copy(backup, source, StandardCopyOption.REPLACE_EXISTING);

            return ToolResult.success("Restored " + source.getFileName()
                    + " from " + backup.getFileName()
                    + " (SHA-256: " + currentHash + ")");
        } catch (IOException e) {
            return ToolResult.failure("Restore failed: " + e.getMessage());
        }
    }

    /// Computes the SHA-256 hash of a file.
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /// Converts an object to its string representation, or `null` if it is `null`.
    @org.jetbrains.annotations.Nullable
    private static String toString(@org.jetbrains.annotations.Nullable Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
