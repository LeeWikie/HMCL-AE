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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Locks in the robust param resolution that stops a weak model's wrong param-name from hard-failing
/// a tool (the add_offline_account 'username vs query' class of bug, generalised across all tools).
public final class ToolParamsTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    void stringPrefersCanonicalThenAliasThenGenericThenSole() {
        assertEquals("Steve", ToolParams.string(map("username", "Steve"), "username", "name"));
        assertEquals("Steve", ToolParams.string(map("name", "Steve"), "username", "name"));        // alias
        assertEquals("Steve", ToolParams.string(map("query", "Steve"), "username", "name"));       // generic dump-key
        assertEquals("Steve", ToolParams.string(map("whatever", "Steve"), "username", "name"));    // sole value
        assertEquals("", ToolParams.string(map(), "username"));                                    // nothing
    }

    @Test
    void stringStripsKeyEqualsPrefix() {
        assertEquals("Steve", ToolParams.string(map("query", "username=Steve"), "username", "name"));
        assertEquals("Steve", ToolParams.string(map("username", "name=Steve"), "username", "name"));
    }

    @Test
    void soleValueOnlyWhenUnambiguous() {
        // Two unrelated values present and neither is canonical/alias/generic -> don't guess.
        assertEquals("", ToolParams.string(map("foo", "a", "bar", "b"), "world"));
    }

    @Test
    void strictDoesNotUseGenericOrSoleFallback() {
        assertEquals("X", ToolParams.strict(map("key", "X"), "key", "option"));
        assertEquals("X", ToolParams.strict(map("option", "X"), "key", "option"));  // alias ok
        assertEquals("", ToolParams.strict(map("query", "X"), "key", "option"));    // generic NOT used
        assertEquals("", ToolParams.strict(map("whatever", "X"), "key", "option")); // sole NOT used
    }

    @Test
    void strictKeyAndValueDoNotStealEachOther() {
        Map<String, Object> p = map("key", "fullscreen", "value", "true");
        assertEquals("fullscreen", ToolParams.strict(p, "key", "option"));
        assertEquals("true", ToolParams.strict(p, "value", "val"));
    }
}
