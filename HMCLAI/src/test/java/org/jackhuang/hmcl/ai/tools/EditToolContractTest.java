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

/// The wave-1 file-tool contract on EditTool: read precondition (F1), staleness detection
/// (F2), no-op interception (F3), fallback matching + circuit breaker (F4), post-write
/// validation (F5) and the allowed-roots data in errors/description (F6).
public final class EditToolContractTest {

    private static EditTool toolWithRead(Path root, Path file, ReadLedger ledger) throws IOException {
        ledger.recordRead(file, Files.readAllBytes(file));
        return new EditTool(root, ledger);
    }

    // ---- F3: no-op interception ----

    @Test
    void identicalOldAndNewStringIsRejectedAndFileUntouched(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "world"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("'old_string' and 'new_string' are identical — nothing to change"),
                result.getError());
        assertTrue(result.getError().contains("Retryable: no"), result.getError());
        assertEquals("hello world", Files.readString(file), "a no-op call must not touch the file");
    }

    // ---- F1: read precondition ----

    @Test
    void editingANeverReadFileIsRefused(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        EditTool tool = new EditTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("File has not been read yet — read it first before editing"),
                result.getError());
        assertEquals("hello world", Files.readString(file));
    }

    @Test
    void readThroughFileReadToolEntitlesTheEdit(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        ReadLedger ledger = new ReadLedger();
        FileReadTool readTool = new FileReadTool(root, ledger);
        EditTool editTool = new EditTool(root, ledger);

        assertTrue(readTool.execute(Map.of("path", "a.txt")).isSuccess());
        ToolResult result = editTool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("hello there", Files.readString(file));
    }

    @Test
    void consecutiveEditsDoNotRequireARereadBetweenThem(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "one two three");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        assertTrue(tool.execute(Map.of("path", "a.txt", "old_string", "one", "new_string", "1")).isSuccess());
        ToolResult second = tool.execute(Map.of("path", "a.txt", "old_string", "two", "new_string", "2"));

        assertTrue(second.isSuccess(), "the tool's own write must self-record: " + second.getError());
        assertEquals("1 2 three", Files.readString(file));
    }

    // ---- F2: staleness detection ----

    @Test
    void externallyModifiedFileIsRefusedUntilReread(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        Files.writeString(file, "hello world, changed externally");

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("The file has been modified since it was last read"), result.getError());
        assertTrue(result.getError().contains("re-read it before editing"), result.getError());
        assertEquals("hello world, changed externally", Files.readString(file));
    }

    // ---- F4: fallback matching chain + circuit breaker ----

    @Test
    void crlfFileMatchesLfOldString(@TempDir Path root) throws IOException {
        Path file = root.resolve("options.txt");
        Files.writeString(file, "fov:70\r\nrenderDistance:8\r\nfullscreen:false\r\n");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of(
                "path", "options.txt",
                "old_string", "fov:70\nrenderDistance:8",
                "new_string", "fov:90\r\nrenderDistance:16"));

        assertTrue(result.isSuccess(), "CRLF-vs-LF drift must be absorbed: " + result.getError());
        assertTrue(result.getOutput().contains("line-trimmed"), "receipt should name the fallback: " + result.getOutput());
        assertEquals("fov:90\r\nrenderDistance:16\r\nfullscreen:false\r\n", Files.readString(file));
    }

    @Test
    void indentationDriftIsAbsorbedByTheFallback(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "    foo();\n    bar();\n");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of(
                "path", "a.txt", "old_string", "foo();\nbar();", "new_string", "    baz();"));

        assertTrue(result.isSuccess(), "indentation drift must be absorbed: " + result.getError());
        assertEquals("    baz();\n", Files.readString(file));
    }

    @Test
    void disproportionateFuzzyMatchIsRefusedAndFileUntouched(@TempDir Path root) throws IOException {
        String content = "a" + " ".repeat(600) + "b\n";
        Path file = root.resolve("a.txt");
        Files.writeString(file, content);
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "a b", "new_string", "c"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Refusing the replacement"), result.getError());
        assertTrue(result.getError().contains("re-read the file and provide the full exact old_string"),
                result.getError());
        assertEquals(content, Files.readString(file));
    }

    @Test
    void zeroMatchAndMultiMatchKeepDistinctEnvelopes(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "dup dup");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult zero = tool.execute(Map.of("path", "a.txt", "old_string", "missing", "new_string", "x"));
        assertFailureEnvelope(zero);
        assertTrue(zero.getError().contains("'old_string' not found"), zero.getError());

        ToolResult multi = tool.execute(Map.of("path", "a.txt", "old_string", "dup", "new_string", "x"));
        assertFailureEnvelope(multi);
        assertTrue(multi.getError().contains("'old_string' is not unique (2 matches)"), multi.getError());
    }

    @Test
    void replaceAllStillReplacesEveryOccurrence(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "dup dup dup");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of(
                "path", "a.txt", "old_string", "dup", "new_string", "x", "replace_all", true));

        assertTrue(result.isSuccess(), result.getError());
        assertTrue(result.getOutput().contains("3 replacement(s)"), result.getOutput());
        assertEquals("x x x", Files.readString(file));
    }

    // ---- F5: post-write validation ----

    @Test
    void editThatBreaksJsonSucceedsWithAWarning(@TempDir Path root) throws IOException {
        Path file = root.resolve("config.json");
        Files.writeString(file, "{\"count\": 1}");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of("path", "config.json", "old_string", "1}", "new_string", "}"));

        assertTrue(result.isSuccess(), "the file IS written, so the call must not fail: " + result.getError());
        assertTrue(result.getOutput().contains("WARNING"), result.getOutput());
        assertTrue(result.getOutput().contains("no longer valid JSON"), result.getOutput());
    }

    @Test
    void editThatKeepsJsonValidHasNoWarning(@TempDir Path root) throws IOException {
        Path file = root.resolve("config.json");
        Files.writeString(file, "{\"count\": 1}");
        ReadLedger ledger = new ReadLedger();
        EditTool tool = toolWithRead(root, file, ledger);

        ToolResult result = tool.execute(Map.of("path", "config.json", "old_string", "1", "new_string", "2"));

        assertTrue(result.isSuccess(), result.getError());
        assertFalse(result.getOutput().contains("WARNING"), result.getOutput());
    }

    // ---- F6: allowed-roots data ----

    @Test
    void outsideRootsErrorCarriesTheAllowedRoots(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path file = outside.resolve("a.txt");
        Files.writeString(file, "x");
        EditTool tool = new EditTool(root, new ReadLedger());

        ToolResult result = tool.execute(Map.of(
                "path", file.toString(), "old_string", "x", "new_string", "y"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("allowed roots are " + root), result.getError());
    }

    @Test
    void descriptionAdvertisesAllowedRootsAndReadPrecondition(@TempDir Path root, @TempDir Path second) {
        EditTool tool = new EditTool(root, new ReadLedger());
        tool.addRoot(second);

        String description = tool.getDescription();
        assertTrue(description.contains("Allowed roots: " + root + "; " + second + "."), description);
        assertTrue(description.contains("must have been read"), description);
    }
}
