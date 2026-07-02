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
    void stringStripsKeyEqualsPrefixOnlyOnFallbackPaths() {
        // Value dumped under a generic key with a baked-in name -> recovered and stripped.
        assertEquals("Steve", ToolParams.string(map("query", "username=Steve"), "username", "name"));
        assertEquals("Steve", ToolParams.string(map("whatever", "username=Steve"), "username", "name"));
        // Value sent under the DECLARED name (or an alias) is taken verbatim — legitimate content
        // like a config snippet "text=Welcome" must never be mangled.
        assertEquals("name=Steve", ToolParams.string(map("username", "name=Steve"), "username", "name"));
        assertEquals("text=Welcome", ToolParams.string(map("text", "text=Welcome"), "text", "content"));
    }

    @Test
    void soleValueOnlyWhenUnambiguous() {
        // Two unrelated values present and neither is canonical/alias/generic -> don't guess.
        assertEquals("", ToolParams.string(map("foo", "a", "bar", "b"), "world"));
    }

    /// Gson parses every JSON number as Double; whole numbers must not grow a ".0" suffix
    /// (renderDistance:12.0 in options.txt, or looking for a world literally named "123.0").
    @Test
    void wholeNumbersRenderWithoutFraction() {
        assertEquals("12", ToolParams.string(map("value", 12.0d), "value"));
        assertEquals("123", ToolParams.string(map("world", 123.0d), "world"));
        assertEquals("-4", ToolParams.strict(map("value", -4.0d), "value"));
        assertEquals("2.5", ToolParams.string(map("value", 2.5d), "value")); // real fractions kept
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

    /// primary(): a call carrying ONLY a secondary parameter (backupId/confirm/instance) must not
    /// have that value stolen as the primary one — that produced "world 'true' was not found"
    /// errors that sent the model flailing.
    @Test
    void primaryIgnoresReservedKeysInFallbacks() {
        String[] reserved = {"backupId", "instance"};
        // Normal resolution still works.
        assertEquals("MyWorld", ToolParams.primary(map("world", "MyWorld"), "world", reserved, "save"));
        assertEquals("MyWorld", ToolParams.primary(map("save", "MyWorld"), "world", reserved, "save"));
        assertEquals("MyWorld", ToolParams.primary(map("query", "MyWorld"), "world", reserved, "save"));
        // A lone secondary param is NOT mistaken for the primary.
        assertEquals("", ToolParams.primary(map("backupId", "20260629-153000"), "world", reserved, "save"));
        assertEquals("", ToolParams.primary(map("instance", "1.20.1"), "world", reserved, "save"));
        // Sole-value recovery still fires for a lone NON-reserved key.
        assertEquals("MyWorld", ToolParams.primary(map("worldname", "MyWorld"), "world", reserved, "save"));
        // And a secondary param alongside an unknown key doesn't block recovery of the real value.
        assertEquals("MyWorld", ToolParams.primary(
                map("worldname", "MyWorld", "instance", "1.20.1"), "world", reserved, "save"));
    }
}
