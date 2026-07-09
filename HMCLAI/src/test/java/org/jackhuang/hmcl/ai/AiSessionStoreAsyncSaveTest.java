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
package org.jackhuang.hmcl.ai;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.util.AiLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for {@link AiSessionStore#saveAsync()}: the queued/merged asynchronous save must
/// never lose the latest state (each executed save takes a fresh snapshot), and it must not
/// fail (e.g. with a ConcurrentModificationException) while the agent thread keeps mutating
/// the session — {@code copyForStore()} detaches the message list under the session monitor.
public final class AiSessionStoreAsyncSaveTest {

    /// 200 次 addMessage + saveAsync 后,等待保存队列排空,重新加载必须看到全部 200 条消息,
    /// 且落盘文件是合法 JSON。合并标志只允许"少保存",不允许"存旧盖新"。
    @Test
    public void testAsyncSavePersistsAllMessages() throws Exception {
        Path tempDir = Files.createTempDirectory("hmcl-ai-async-save-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession session = store.createSession();

            for (int i = 0; i < 200; i++) {
                session.addMessage(new LlmMessage("user", "msg-" + i));
                store.saveAsync();
            }

            // Sentinel task: the save executor is single-threaded FIFO, so once this returns
            // every previously queued save (including the one covering message #199) has run.
            AiSessionStore.awaitSaveQueue();

            String json = Files.readString(tempDir.resolve(AiSessionStore.FILE_NAME), StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> JsonParser.parseString(json), "store file must be valid JSON");

            AiSessionStore reloaded = new AiSessionStore(tempDir);
            reloaded.load();
            assertEquals(1, reloaded.size());
            AiSession loaded = reloaded.getSession(session.getId());
            assertNotNull(loaded);
            List<LlmMessage> messages = loaded.getMessages();
            assertEquals(200, messages.size(), "all 200 messages must be captured by the final save");
            assertEquals("msg-199", messages.get(199).getContent());
        } finally {
            cleanup(tempDir);
        }
    }

    /// 并发回归:另一线程持续 addMessage 期间连打 saveAsync,任何一次异步保存都不得失败
    /// (saveAsync 内部会把异常记为 AiLog warn——本测试装 sink 捕获并断言为空,从而断言
    /// 没有 ConcurrentModificationException 等异常;copyForStore 快照是既有保障,此为回归网)。
    @Test
    public void testConcurrentAddMessageDoesNotFailSave() throws Exception {
        Path tempDir = Files.createTempDirectory("hmcl-ai-async-save-conc-");
        List<String> warnings = Collections.synchronizedList(new java.util.ArrayList<>());
        AiLog.setSink((warn, message) -> {
            if (warn) {
                warnings.add(message);
            }
        });
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession session = store.createSession();

            final int total = 1000;
            Thread mutator = new Thread(() -> {
                for (int i = 0; i < total; i++) {
                    session.addMessage(new LlmMessage("user", "conc-" + i));
                }
            }, "test-ai-mutator");
            mutator.start();

            while (mutator.isAlive()) {
                store.saveAsync();
                Thread.yield();
            }
            mutator.join();

            // One final save after the mutator finished, then drain the queue: the last
            // executed save takes a snapshot that must contain all `total` messages.
            store.saveAsync();
            AiSessionStore.awaitSaveQueue();

            assertTrue(warnings.isEmpty(),
                    "no async save may fail (e.g. ConcurrentModificationException): " + warnings);

            AiSessionStore reloaded = new AiSessionStore(tempDir);
            reloaded.load();
            AiSession loaded = reloaded.getSession(session.getId());
            assertNotNull(loaded);
            assertEquals(total, loaded.getMessages().size(),
                    "final async save must capture every message added before it");
        } finally {
            AiLog.setSink((warn, message) -> { }); // detach the test sink
            cleanup(tempDir);
        }
    }

    private static void cleanup(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) {
        }
    }
}
