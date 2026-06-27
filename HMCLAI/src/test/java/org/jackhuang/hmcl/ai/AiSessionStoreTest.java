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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class AiSessionStoreTest {

    /// Verifies that creating a session auto-selects it as current.
    @Test
    public void testCreateSessionAutoSelects() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession session = store.createSession();

            assertEquals(session.getId(), store.getCurrentSessionId());
            assertEquals(1, store.size());
            assertSame(session, store.getCurrentSession());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that listing sessions returns them sorted by most-recently-updated.
    @Test
    public void testListSessionsSortedByUpdated() throws Exception {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession s1 = store.createSession();
            Thread.sleep(10); // Ensure distinct timestamps
            AiSession s2 = store.createSession();

            List<AiSession> list = store.listSessions();
            assertEquals(2, list.size());
            // s2 was created later, so it should come first.
            assertEquals(s2.getId(), list.get(0).getId());
            assertEquals(s1.getId(), list.get(1).getId());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that deleting the current session falls back to another session.
    @Test
    public void testDeleteCurrentSessionFallsBack() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession s1 = store.createSession();
            AiSession s2 = store.createSession();

            assertEquals(s2.getId(), store.getCurrentSessionId());
            assertTrue(store.deleteSession(s2.getId()));
            assertEquals(s1.getId(), store.getCurrentSessionId());
            assertEquals(1, store.size());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that deleting the only session clears current session id.
    @Test
    public void testDeleteOnlySessionClearsCurrentId() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            AiSession session = store.createSession();
            assertTrue(store.deleteSession(session.getId()));
            assertNull(store.getCurrentSessionId());
            assertEquals(0, store.size());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that getting a non-existent session returns null.
    @Test
    public void testGetNonExistentSessionReturnsNull() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            assertNull(store.getSession("nonexistent"));
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that session store persists and loads correctly.
    @Test
    public void testSaveLoadRoundTrip() throws Exception {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            // Create and save
            AiSessionStore store1 = new AiSessionStore(tempDir);
            AiSession s1 = store1.createSession();
            s1.setTitle("My Chat");
            Thread.sleep(10);
            AiSession s2 = store1.createSession();
            s2.setTitle("Another Chat");
            store1.save();

            // Load into a new store instance
            AiSessionStore store2 = new AiSessionStore(tempDir);
            store2.load();

            assertEquals(2, store2.size());
            assertEquals(s2.getId(), store2.getCurrentSessionId());

            AiSession loaded = store2.getSession(s1.getId());
            assertNotNull(loaded);
            assertEquals("My Chat", loaded.getTitle());

            loaded = store2.getSession(s2.getId());
            assertNotNull(loaded);
            assertEquals("Another Chat", loaded.getTitle());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that loading from a non-existent file leaves the store empty.
    @Test
    public void testLoadFromMissingFileReturnsEmpty() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            store.load();
            assertEquals(0, store.size());
            assertNull(store.getCurrentSessionId());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that setting an unknown current session id is silently ignored.
    @Test
    public void testSetCurrentSessionIdOnUnknownIdIsIgnored() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            store.createSession(); // auto-selects
            String currentBefore = store.getCurrentSessionId();

            store.setCurrentSessionId("nonexistent-id");
            assertEquals(currentBefore, store.getCurrentSessionId(),
                    "Setting unknown current session id should be ignored");
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that setting current session id to null is allowed.
    @Test
    public void testSetCurrentSessionIdToNull() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            AiSessionStore store = new AiSessionStore(tempDir);
            store.createSession();
            assertNotNull(store.getCurrentSessionId());

            store.setCurrentSessionId(null);
            assertNull(store.getCurrentSessionId());
            assertNull(store.getCurrentSession());
        } finally {
            cleanup(tempDir);
        }
    }

    /// Verifies that saving a store with invalidated current session id
    /// upon load falls back to the first session.
    @Test
    public void testRecoveryFromInvalidCurrentSessionId() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-sessions-test-");
        try {
            // Manually write a store file with a bogus currentSessionId.
            Files.createDirectories(tempDir);
            String badJson = "{\"currentSessionId\":\"bogus-id\",\"sessions\":[]}";
            Path file = tempDir.resolve(AiSessionStore.FILE_NAME);
            Files.writeString(file, badJson, StandardCharsets.UTF_8);

            AiSessionStore store = new AiSessionStore(tempDir);
            store.load();
            assertNull(store.getCurrentSessionId(), "Bogus id with no sessions should yield null");
        } finally {
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
