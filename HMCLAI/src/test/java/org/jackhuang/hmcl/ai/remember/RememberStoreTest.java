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
package org.jackhuang.hmcl.ai.remember;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class RememberStoreTest {

    /// redactSecrets scrubs known key/token shapes but leaves ordinary prose alone.
    @Test
    public void testRedactSecretsScrubsKeysKeepsProse() {
        assertFalse(RememberStore.redactSecrets("key: sk-ABCDEFGHIJKLMNOP0123456789").contains("sk-ABCDEF"));
        assertTrue(RememberStore.redactSecrets("api_key=abcdef123456").contains("[REDACTED]"));
        assertTrue(RememberStore.redactSecrets("password: hunter2secret").contains("[REDACTED]"));
        assertEquals("装一个 sodium 光影就行", RememberStore.redactSecrets("装一个 sodium 光影就行"));
    }

    /// A secret in the content is redacted BEFORE the memory file is written to disk.
    @Test
    public void testRememberRedactsBeforeWriting() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-mem-redact-");
        try {
            RememberStore store = new RememberStore(dir);
            store.init();
            RememberStore.Entry e = store.remember("user key", List.of("test"),
                    "The OpenAI key is sk-ABCDEFGHIJKLMNOP0123456789 remember it");
            String saved = Files.readString(e.getFile(), StandardCharsets.UTF_8);
            assertFalse(saved.contains("sk-ABCDEFGHIJKLMNOP"), "raw key must not be persisted");
            assertTrue(saved.contains("[REDACTED]"));
        } finally {
            deleteDir(dir);
        }
    }

    /// Two remembers with identical content (different titles) collapse to a single file.
    @Test
    public void testRememberDeduplicatesIdenticalContent() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-mem-dedup-");
        try {
            RememberStore store = new RememberStore(dir);
            store.init();
            store.remember("t1", List.of(), "用户偏好简体中文，且看不懂代码");
            store.remember("a different title", List.of(), "用户偏好简体中文，且看不懂代码");
            assertEquals(1, store.listAll().size(), "identical content must not create a duplicate");
        } finally {
            deleteDir(dir);
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
