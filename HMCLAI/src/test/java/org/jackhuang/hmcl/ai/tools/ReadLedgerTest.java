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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// The read-before-edit ledger underpinning the file-tool contract (F1 read precondition +
/// F2 staleness detection).
public final class ReadLedgerTest {

    @Test
    void unreadPathReportsNotRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content");
        ReadLedger ledger = new ReadLedger();

        assertEquals(ReadLedger.Status.NOT_READ, ledger.check(file, Files.readAllBytes(file)));
    }

    @Test
    void recordedReadWithUnchangedBytesReportsOk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));

        assertEquals(ReadLedger.Status.OK, ledger.check(file, Files.readAllBytes(file)));
        assertNotNull(ledger.get(file));
    }

    @Test
    void externalModificationReportsStale(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));

        Files.writeString(file, "changed by someone else");

        assertEquals(ReadLedger.Status.STALE, ledger.check(file, Files.readAllBytes(file)));
    }

    @Test
    void stringOverloadHashesUtf8Bytes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "héllo 世界");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, "héllo 世界");

        assertEquals(ReadLedger.Status.OK, ledger.check(file, Files.readAllBytes(file)));
    }

    @Test
    void differentPathSpellingsOfTheSameFileShareOneEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sub").resolve("a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));

        Path alias = dir.resolve("sub").resolve("..").resolve("sub").resolve("a.txt");
        assertEquals(ReadLedger.Status.OK, ledger.check(alias, Files.readAllBytes(file)));
    }

    @Test
    void clearForgetsEverything(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "x");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        ledger.clear();

        assertEquals(ReadLedger.Status.NOT_READ, ledger.check(file, Files.readAllBytes(file)));
        assertNull(ledger.get(file));
    }

    @Test
    void hashIsStableAndContentSensitive() {
        byte[] a = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] b = "abd".getBytes(StandardCharsets.UTF_8);
        assertEquals(ReadLedger.hash(a), ReadLedger.hash(a));
        assertNotEquals(ReadLedger.hash(a), ReadLedger.hash(b));
        assertEquals(64, ReadLedger.hash(a).length(), "SHA-256 hex is 64 chars");
    }
}
