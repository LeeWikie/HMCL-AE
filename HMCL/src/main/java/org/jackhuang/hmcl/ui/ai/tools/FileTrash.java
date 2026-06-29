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

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/// Deletes a file or directory, preferring the OS recycle bin / trash so a mistaken deletion
/// can be recovered by the user. Falls back to a permanent recursive delete when the platform
/// has no trash support or the move-to-trash call fails.
///
/// {@link Desktop#moveToTrash(java.io.File)} (Java 9+) handles whole directories and is
/// cross-platform (Windows Recycle Bin, macOS Trash, Linux Trash where the desktop supports it).
@NotNullByDefault
public final class FileTrash {

    private FileTrash() {
    }

    /// Returns whether the platform can move files to the recycle bin / trash.
    public static boolean trashSupported() {
        try {
            return Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (Throwable t) {
            return false;
        }
    }

    /// Removes {@code target}. When {@code preferTrash} is true and the platform supports it, the
    /// target is moved to the recycle bin / trash (recoverable); otherwise it is permanently
    /// deleted. Returns {@code true} if it ended up in the trash, {@code false} if it was
    /// permanently deleted.
    ///
    /// @throws IOException if the target could not be removed by either method
    public static boolean delete(Path target, boolean preferTrash) throws IOException {
        if (preferTrash && trashSupported()) {
            try {
                if (Desktop.getDesktop().moveToTrash(target.toFile())) {
                    return true;
                }
            } catch (Throwable t) {
                // Fall through to a permanent delete below.
            }
        }
        deleteRecursively(target);
        return false;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
