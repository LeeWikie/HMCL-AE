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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// FileReadTool's side of the read-before-edit contract: successful file reads register in
/// the shared [`ReadLedger`]; directory listings and failed reads do not.
public final class FileReadToolLedgerTest {

    @Test
    void successfulFileReadRecordsInTheLedger(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello\nworld\n");
        ReadLedger ledger = new ReadLedger();
        FileReadTool tool = new FileReadTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "a.txt"));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(ReadLedger.Status.OK, ledger.check(file, Files.readAllBytes(file)),
                "a successful read must entitle a later edit");
    }

    @Test
    void pagedReadStillRecordsTheWholeFile(@TempDir Path root) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            big.append("line ").append(i).append('\n');
        }
        Path file = root.resolve("big.txt");
        Files.writeString(file, big.toString());
        ReadLedger ledger = new ReadLedger();
        FileReadTool tool = new FileReadTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "big.txt", "maxLines", 10, "startLine", 100));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(ReadLedger.Status.OK, ledger.check(file, Files.readAllBytes(file)),
                "the tool reads the full file even when it only SHOWS a window, so the ledger covers it all");
    }

    @Test
    void directoryListingDoesNotRecord(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        ReadLedger ledger = new ReadLedger();
        FileReadTool tool = new FileReadTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "."));

        assertTrue(result.isSuccess(), result.getError());
        assertNull(ledger.get(root.resolve("a.txt")), "listing a directory is not reading its files");
    }

    @Test
    void failedReadDoesNotRecord(@TempDir Path root) {
        ReadLedger ledger = new ReadLedger();
        FileReadTool tool = new FileReadTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "missing.txt"));

        assertFalse(result.isSuccess());
        assertNull(ledger.get(root.resolve("missing.txt")));
    }

    @Test
    void readOutputIsUnchangedByTheLedgerPlumbing(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "first\nsecond\nthird");
        FileReadTool tool = new FileReadTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "a.txt"));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals("first\nsecond\nthird", result.getOutput());
    }
}
