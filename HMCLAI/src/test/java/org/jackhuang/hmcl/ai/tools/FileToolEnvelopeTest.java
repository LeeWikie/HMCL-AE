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

import java.nio.file.Path;
import java.util.Map;

import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertFailureEnvelope;
import static org.junit.jupiter.api.Assertions.*;

/// Envelope regression lock for the failure texts rewritten across GrepTool, GlobTool and
/// WebFetchTool in this wave (F6 allowed-roots data + F7 IO/Invalid classification), plus the
/// getDescription() additions.
public final class FileToolEnvelopeTest {

    // ---- GrepTool ----

    @Test
    void grepInvalidRegexCarriesTheDialectHint(@TempDir Path dir) {
        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "(unclosed"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Invalid regex"), result.getError());
        assertTrue(result.getError().contains("Java regex, not glob/PCRE"), result.getError());
        assertTrue(result.getError().contains("do not retry unchanged"), result.getError());
    }

    @Test
    void grepOutsideRootsCarriesAllowedRoots(@TempDir Path dir, @TempDir Path outside) {
        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "x", "path", outside.toString()));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("allowed roots are " + dir), result.getError());
    }

    @Test
    void grepDescriptionAdvertisesAllowedRoots(@TempDir Path dir) {
        GrepTool tool = new GrepTool(dir);
        assertTrue(tool.getDescription().contains("Allowed roots: " + dir + "."), tool.getDescription());
    }

    // ---- GlobTool ----

    @Test
    void globInvalidPatternIsRetryableWithSyntaxHint(@TempDir Path dir) {
        GlobTool tool = new GlobTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "a[unclosed"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Invalid glob"), result.getError());
        assertTrue(result.getError().contains("not regex"), result.getError());
    }

    @Test
    void globOutsideRootsCarriesAllowedRoots(@TempDir Path dir, @TempDir Path outside) {
        GlobTool tool = new GlobTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "*.txt", "path", outside.toString()));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("allowed roots are " + dir), result.getError());
    }

    @Test
    void globDescriptionAdvertisesAllowedRoots(@TempDir Path dir) {
        GlobTool tool = new GlobTool(dir);
        assertTrue(tool.getDescription().contains("Allowed roots: " + dir + "."), tool.getDescription());
    }

    // ---- FileReadTool (rewritten error paths) ----

    @Test
    void readOutsideRootsCarriesAllowedRoots(@TempDir Path dir, @TempDir Path outside) {
        FileReadTool tool = new FileReadTool(dir, new ReadLedger());
        ToolResult result = tool.execute(Map.of("path", outside.resolve("f.txt").toString()));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("allowed roots are " + dir), result.getError());
    }

    // ---- WebFetchTool ----

    @Test
    void webFetchMalformedUrlIsRetryableAfterFixing() {
        WebFetchTool tool = new WebFetchTool();
        // Passes the http(s) prefix check but URI.create rejects the space.
        ToolResult result = tool.execute(Map.of("url", "http://example.com/a b"));

        assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Invalid URL"), result.getError());
        assertTrue(result.getError().contains("percent-encode"), result.getError());
    }
}
