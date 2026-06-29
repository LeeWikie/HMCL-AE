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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link RememberStore} (file-based global memory store).
public final class RememberStoreTest {

    @TempDir
    Path tempDir;

    private RememberStore newStore() throws IOException {
        RememberStore store = new RememberStore(tempDir);
        store.init();
        return store;
    }

    @Test
    public void testRememberCreatesFileAndReturnsEntry() throws IOException {
        RememberStore store = newStore();
        RememberStore.Entry entry = store.remember(
                "java-tip", List.of("java", "crash"), "Use the -Xmx flag.");

        assertNotNull(entry.getFile());
        assertEquals("java-tip", entry.getTitle());
        assertEquals(List.of("java", "crash"), entry.getTags());
        assertNotNull(entry.getCreated(), "created timestamp should be set");
        assertEquals("java-tip.md", entry.getFile().getFileName().toString());
    }

    @Test
    public void testRecallByQueryMatchesContent() throws IOException {
        RememberStore store = newStore();
        store.remember("java-tip", List.of("java"), "Use the -Xmx flag to set heap size.");
        store.remember("forge-note", List.of("forge"), "Install Forge before mods.");

        List<RememberStore.Entry> results = store.recall("heap", null, 10);
        assertEquals(1, results.size());
        assertEquals("java-tip", results.get(0).getTitle());
        assertTrue(results.get(0).getContent().contains("-Xmx"),
                "recalled content should include the stored body");
    }

    @Test
    public void testRecallIsCaseInsensitive() throws IOException {
        RememberStore store = newStore();
        store.remember("note", List.of(), "The Minecraft world saved successfully.");

        assertEquals(1, store.recall("MINECRAFT", null, 10).size());
        assertEquals(1, store.recall("minecraft", null, 10).size());
        assertEquals(0, store.recall("nonexistentword", null, 10).size());
    }

    @Test
    public void testRecallByTagFilter() throws IOException {
        RememberStore store = newStore();
        store.remember("a", List.of("java"), "alpha content");
        store.remember("b", List.of("forge"), "beta content");
        store.remember("c", List.of("java", "crash"), "gamma content");

        List<RememberStore.Entry> javaEntries = store.recall("", "java", 10);
        assertEquals(2, javaEntries.size());
        for (RememberStore.Entry e : javaEntries) {
            assertTrue(e.getTags().contains("java"), "each result must carry the java tag");
        }

        assertEquals(1, store.recall("", "forge", 10).size());
        assertEquals(0, store.recall("", "doesnotexist", 10).size());
    }

    @Test
    public void testRecallQueryAndTagCombined() throws IOException {
        RememberStore store = newStore();
        store.remember("a", List.of("java"), "alpha heap content");
        store.remember("c", List.of("java"), "gamma other content");

        List<RememberStore.Entry> results = store.recall("heap", "java", 10);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).getTitle());
    }

    @Test
    public void testRecallRespectsMaxResults() throws IOException {
        RememberStore store = newStore();
        for (int i = 0; i < 5; i++) {
            store.remember("note-" + i, List.of(), "shared keyword body " + i);
        }
        assertEquals(3, store.recall("shared", null, 3).size());
        assertEquals(5, store.recall("shared", null, 100).size());
    }

    @Test
    public void testListAllReturnsEverything() throws IOException {
        RememberStore store = newStore();
        store.remember("one", List.of(), "first");
        store.remember("two", List.of(), "second");
        store.remember("three", List.of(), "third");

        assertEquals(3, store.listAll().size());
    }

    @Test
    public void testForgetDeletesEntry() throws IOException {
        RememberStore store = newStore();
        store.remember("disposable", List.of(), "to be removed");
        assertEquals(1, store.listAll().size());

        assertTrue(store.forget("disposable"), "forget should report success");
        assertEquals(0, store.listAll().size());

        // Removing a non-existent stem returns false.
        assertFalse(store.forget("does-not-exist"));
    }

    @Test
    public void testForgetAcceptsStemWithExtension() throws IOException {
        RememberStore store = newStore();
        store.remember("withext", List.of(), "body");
        assertTrue(store.forget("withext.md"));
        assertEquals(0, store.listAll().size());
    }

    @Test
    public void testGetReadsBackFrontmatter() throws IOException {
        RememberStore store = newStore();
        store.remember("config-note", List.of("settings", "ui"), "Body text here.");

        RememberStore.Entry got = store.get("config-note");
        assertNotNull(got);
        assertEquals("config-note", got.getTitle());
        assertEquals(List.of("settings", "ui"), got.getTags());
        assertNotNull(got.getContent());
        assertTrue(got.getContent().contains("Body text here."));
    }

    @Test
    public void testGetReturnsNullForMissing() throws IOException {
        RememberStore store = newStore();
        assertNull(store.get("missing"));
    }

    @Test
    public void testDuplicateTitlesGetUniqueFiles() throws IOException {
        RememberStore store = newStore();
        RememberStore.Entry first = store.remember("dup", List.of(), "first body");
        RememberStore.Entry second = store.remember("dup", List.of(), "second body");

        assertNotEquals(first.getFile().getFileName().toString(),
                second.getFile().getFileName().toString(),
                "duplicate titles must produce distinct files");
        assertEquals(2, store.listAll().size());
    }

    @Test
    public void testEmptyTagsParseAsEmptyList() throws IOException {
        RememberStore store = newStore();
        store.remember("notags", List.of(), "no tags here");

        RememberStore.Entry got = store.get("notags");
        assertNotNull(got);
        assertNotNull(got.getTags());
        assertTrue(got.getTags().isEmpty(), "empty frontmatter tags should parse to an empty list");
    }
}
