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
package org.jackhuang.hmcl.ui.ai;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Unit tests for the streaming attachment reader (bugfix 7a): `readAttachmentHead` must return
/// small files verbatim, cap oversized files at ATTACHMENT_MAX_CHARS (+ truncation marker) without
/// reading the rest, and propagate IOException for missing files. Also covers the null-safe
/// `isToolMessage` (P1) since it lives in the same class and became package-visible for testing.
///
/// The methods under test are pure statics, but they live on AIMainPage — a Control subclass —
/// whose CLASS initialization needs a live FX toolkit; merely loading it without one poisons the
/// class for every later FX test in the same JVM fork, so the toolkit is set up here as well.
public final class AttachmentReadTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping (see class doc)");
        FxToolkit.registerPrimaryStage();
    }

    /// Mirrors AIMainPage.ATTACHMENT_MAX_CHARS (private constant; value is part of the contract
    /// asserted here — if the cap changes, this test should be updated deliberately).
    private static final int MAX_CHARS = 200_000;
    private static final String TRUNCATION_SUFFIX = "\n…(文件过大，已截断)";

    @TempDir
    Path tempDir;

    @Test
    public void smallFileIsReadVerbatim() throws IOException {
        String content = "第一行日志\nsecond line\n";
        Path file = tempDir.resolve("small.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, AIMainPage.readAttachmentHead(file),
                "a file below the cap must be returned unchanged, without a truncation marker");
    }

    @Test
    public void oversizedFileIsTruncatedAtTheCap() throws IOException {
        String big = "x".repeat(MAX_CHARS + 4096);
        Path file = tempDir.resolve("huge.log");
        Files.writeString(file, big, StandardCharsets.UTF_8);

        String head = AIMainPage.readAttachmentHead(file);
        assertEquals(MAX_CHARS + TRUNCATION_SUFFIX.length(), head.length(),
                "head must be exactly the cap plus the truncation marker");
        assertTrue(head.endsWith(TRUNCATION_SUFFIX), "truncated read must end with the marker");
        assertEquals(big.substring(0, MAX_CHARS),
                head.substring(0, MAX_CHARS), "the kept prefix must be the file's own prefix");
    }

    @Test
    public void exactlyAtCapIsTreatedAsTruncationBoundary() throws IOException {
        // n == remain hits the `n >= remain` branch: content is kept whole but the marker is
        // appended — acceptable (the cap is a soft UI bound), asserted so the boundary is frozen.
        String content = "y".repeat(MAX_CHARS);
        Path file = tempDir.resolve("exact.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        String head = AIMainPage.readAttachmentHead(file);
        assertTrue(head.startsWith(content.substring(0, 1024)));
        assertEquals(MAX_CHARS + TRUNCATION_SUFFIX.length(), head.length());
    }

    @Test
    public void missingFileThrowsIoException() {
        assertThrows(IOException.class,
                () -> AIMainPage.readAttachmentHead(tempDir.resolve("does-not-exist.log")));
    }

    @Test
    public void buildAttachmentTextReportsUnreadableFilesInline() throws IOException {
        Path good = tempDir.resolve("ok.txt");
        Files.writeString(good, "内容OK", StandardCharsets.UTF_8);
        Path missing = tempDir.resolve("gone.txt");

        String result = AIMainPage.buildAttachmentText("问题描述", List.of(good, missing));
        assertTrue(result.startsWith("问题描述"), "user text must stay first");
        assertTrue(result.contains("[附件: ok.txt]"), "attachment header per file");
        assertTrue(result.contains("内容OK"), "readable file content included");
        assertTrue(result.contains("[附件: gone.txt]"));
        assertTrue(result.contains("(读取失败："), "unreadable file reported inline, send not failed");
    }

    @Test
    public void buildAttachmentTextWithoutFilesReturnsTextUnchanged() {
        assertEquals("原文", AIMainPage.buildAttachmentText("原文", List.of()));
    }

    @Test
    public void isToolMessageIsNullSafe() {
        assertFalse(AIMainPage.isToolMessage(null), "imported messages may lack content (P1)");
        assertFalse(AIMainPage.isToolMessage("ordinary text"));
        assertTrue(AIMainPage.isToolMessage("Tool result for glob: ..."));
    }
}
