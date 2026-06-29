/*
 * Hello Minecraft! Launcher - Agent Experience
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

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for the pure entry-filtering logic of {@link AiDataBackup}.
///
/// Note: {@code backup}/{@code restore} are hardwired to
/// {@code SettingsManager.localConfigDirectory()} (the real {@code .hmcl}
/// directory) with no injectable path, so a full round-trip cannot be tested
/// without touching the user's real data. We therefore exercise the
/// {@code isAllowedEntry} prefix/allow-list guard directly via reflection —
/// this is the deterministic safety filter that decides which zip entries are
/// restored (and, combined with the inline Zip-Slip normalization, blocks
/// writes outside the AE-owned prefixes).
public final class AiDataBackupTest {

    private static boolean isAllowedEntry(String name) {
        try {
            Method m = AiDataBackup.class.getDeclaredMethod("isAllowedEntry", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, name);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("isAllowedEntry not accessible via reflection", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("isAllowedEntry threw", e.getCause());
        }
    }

    @Test
    public void testKnownDataFilesAllowed() {
        assertTrue(isAllowedEntry("ai-settings.json"));
        assertTrue(isAllowedEntry("ai-chat-settings.json"));
        assertTrue(isAllowedEntry("ai-mcp-settings.json"));
        assertTrue(isAllowedEntry("ai-search-settings.json"));
        assertTrue(isAllowedEntry("ai-tool-permissions.json"));
    }

    @Test
    public void testKnownDataDirEntriesAllowed() {
        assertTrue(isAllowedEntry("ai-skills/my-skill.md"));
        assertTrue(isAllowedEntry("ai-memory/note.md"));
        assertTrue(isAllowedEntry("ai-sessions/2026/session.json"));
    }

    @Test
    public void testUnrelatedEntriesRejected() {
        assertFalse(isAllowedEntry("hmcl.json"));
        assertFalse(isAllowedEntry("accounts.json"));
        assertFalse(isAllowedEntry("random.txt"));
        assertFalse(isAllowedEntry("config/secret.json"));
    }

    @Test
    public void testDirNameWithoutTrailingSlashRejected() {
        // Bare directory names (no child path) are not allowed entries.
        assertFalse(isAllowedEntry("ai-skills"));
        assertFalse(isAllowedEntry("ai-memory"));
        assertFalse(isAllowedEntry("ai-sessions"));
    }

    @Test
    public void testPrefixLookalikesRejected() {
        // Names that merely start with an allowed token but are not under its folder.
        assertFalse(isAllowedEntry("ai-skills-evil/file.md"));
        assertFalse(isAllowedEntry("ai-settings.json.bak"));
        assertFalse(isAllowedEntry("not-ai-settings.json"));
    }

    @Test
    public void testZipSlipStyleEntriesRejected() {
        // Path-traversal style names do not match any allow-list prefix and are rejected
        // before the inline Zip-Slip normalization even runs.
        assertFalse(isAllowedEntry("../ai-settings.json"));
        assertFalse(isAllowedEntry("../../etc/passwd"));
        assertFalse(isAllowedEntry("foo/../ai-skills/x.md"));
    }
}
