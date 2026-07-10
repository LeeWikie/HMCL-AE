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

import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertFailureEnvelope;
import static org.junit.jupiter.api.Assertions.*;

/// The wave-1 file-tool contract on WriteFileTool: read precondition for overwriting an
/// existing file (F1, new files unrestricted), staleness detection (F2), post-write
/// validation for .json/.properties (F5), and allowed-roots data in errors (F6).
public final class WriteFileToolContractTest {

    // ---- F1/F2: read precondition for overwrite ----

    @Test
    void creatingANewFileNeedsNoPriorRead(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "fresh.txt", "content", "hello"));

        assertTrue(result.isSuccess(), "creating a new file must stay unrestricted: " + result.getError());
    }

    @Test
    void overwritingANeverReadFileIsRefused(@TempDir Path root) throws IOException {
        Path file = root.resolve("existing.txt");
        Files.writeString(file, "precious");
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "existing.txt", "content", "clobber"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("has not been read yet — read it first before overwriting"),
                result.getError());
        assertEquals("precious", Files.readString(file), "the refused overwrite must not touch the file");
    }

    @Test
    void overwritingAfterAReadSucceedsAndSelfRecords(@TempDir Path root) throws IOException {
        Path file = root.resolve("existing.txt");
        Files.writeString(file, "old content");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        WriteFileTool tool = new WriteFileTool(root, ledger);

        ToolResult first = tool.execute(Map.of("path", "existing.txt", "content", "new content"));
        assertTrue(first.isSuccess(), first.getError());
        assertEquals("new content", Files.readString(file));

        // The write self-records, so an immediate second overwrite needs no re-read.
        ToolResult second = tool.execute(Map.of("path", "existing.txt", "content", "newer content"));
        assertTrue(second.isSuccess(), second.getError());
        assertEquals("newer content", Files.readString(file));
    }

    @Test
    void overwritingAStaleFileIsRefused(@TempDir Path root) throws IOException {
        Path file = root.resolve("existing.txt");
        Files.writeString(file, "v1");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        Files.writeString(file, "v2 - changed externally");
        WriteFileTool tool = new WriteFileTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "existing.txt", "content", "v3"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("The file has been modified since it was last read"),
                result.getError());
        assertEquals("v2 - changed externally", Files.readString(file));
    }

    @Test
    void appendingToAnExistingUnreadFileIsStillAllowed(@TempDir Path root) throws IOException {
        Path file = root.resolve("log.txt");
        Files.writeString(file, "line1\n");
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "log.txt", "content", "line2\n", "append", true));

        assertTrue(result.isSuccess(), "append does not discard content, so it stays exempt: " + result.getError());
        assertEquals("line1\nline2\n", Files.readString(file));
    }

    // ---- F5: post-write validation ----

    @Test
    void writingBrokenJsonSucceedsWithAWarning(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "config.json", "content", "{\"a\": 1,, \"b\"}"));

        assertTrue(result.isSuccess(), "the file IS on disk, so the call must not fail: " + result.getError());
        assertTrue(result.getOutput().contains("WARNING"), result.getOutput());
        assertTrue(result.getOutput().contains("no longer valid JSON"), result.getOutput());
    }

    @Test
    void writingValidJsonHasNoWarning(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "config.json", "content", "{\"a\": 1}"));

        assertTrue(result.isSuccess(), result.getError());
        assertFalse(result.getOutput().contains("WARNING"), result.getOutput());
    }

    @Test
    void writingMalformedPropertiesSucceedsWithAWarning(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of(
                "path", "server.properties",
                "content", "max-players=10\nthis line is broken\nmotd=Hello\n"));

        assertTrue(result.isSuccess(), result.getError());
        assertTrue(result.getOutput().contains("WARNING"), result.getOutput());
        assertTrue(result.getOutput().contains("line 2"), result.getOutput());
    }

    @Test
    void writingValidPropertiesHasNoWarning(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of(
                "path", "server.properties",
                "content", "# comment\nmax-players=10\nmotd=Hello\n\n"));

        assertTrue(result.isSuccess(), result.getError());
        assertFalse(result.getOutput().contains("WARNING"), result.getOutput());
    }

    // ---- F6: allowed-roots data ----

    @Test
    void outsideRootsErrorCarriesTheAllowedRoots(@TempDir Path root, @TempDir Path outside) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of(
                "path", outside.resolve("evil.txt").toString(), "content", "x"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("allowed roots are " + root), result.getError());
    }

    @Test
    void descriptionAdvertisesAllowedRootsAndOverwritePrecondition(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool(root, new ReadLedger());

        String description = tool.getDescription();
        assertTrue(description.contains("Allowed roots: " + root + "."), description);
        assertTrue(description.contains("requires having read it"), description);
    }
}
