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

import java.util.Map;

/// Robust parameter extraction for tools.
///
/// Weak models routinely send a tool's value under the WRONG key — a generic
/// `query` / `input` / `value` instead of the declared name, or as `"key=value"`. Rather than
/// hard-failing every such call (which makes the model flail and retry endlessly), tools resolve
/// their PRIMARY parameter through {@link #string} so a single param-name slip still works.
///
/// Use this only for a tool's single primary string parameter. Tools with several distinct
/// required params should read the secondary ones directly (the generic/sole-value fallbacks here
/// would otherwise grab a value meant for a different param).
public final class ToolParams {
    private ToolParams() {
    }

    /// Generic keys a confused model dumps a lone value into when it doesn't know the real name.
    /// Deliberately excludes "name" (too often a real, distinct parameter) — pass that as an alias
    /// only for tools where it genuinely means the primary value.
    private static final String[] GENERIC = {"query", "input", "value", "arg", "args", "text", "content", "q"};

    /// Resolves a primary string parameter: the canonical name, then the tool-specific aliases, then
    /// the common generic dump-keys, then — if the map has exactly ONE non-blank value — that sole
    /// value. Finally strips a leading `key=` prefix the model sometimes bakes into the value.
    /// Returns "" if nothing usable is present.
    public static String string(Map<String, Object> params, String canonical, String... aliases) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        String v = raw(params, canonical);
        if (v.isEmpty()) {
            for (String a : aliases) {
                v = raw(params, a);
                if (!v.isEmpty()) break;
            }
        }
        if (v.isEmpty()) {
            for (String g : GENERIC) {
                v = raw(params, g);
                if (!v.isEmpty()) break;
            }
        }
        if (v.isEmpty()) {
            v = soleValue(params);
        }
        return stripKeyPrefix(v, canonical, aliases);
    }

    private static String raw(Map<String, Object> p, String key) {
        Object o = p.get(key);
        return o == null ? "" : String.valueOf(o).trim();
    }

    /// The single non-blank value if the map has exactly one; otherwise "" (ambiguous — don't guess).
    private static String soleValue(Map<String, Object> p) {
        String only = "";
        for (Object o : p.values()) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            if (!only.isEmpty()) return ""; // more than one candidate
            only = s;
        }
        return only;
    }

    /// Strips a leading `key=` if the model wrote the value as "username=Steve" / "world=MyWorld".
    private static String stripKeyPrefix(String v, String canonical, String... aliases) {
        int eq = v.indexOf('=');
        if (eq <= 0) {
            return v;
        }
        String key = v.substring(0, eq).trim().toLowerCase();
        if (key.equals(canonical.toLowerCase())) {
            return v.substring(eq + 1).trim();
        }
        for (String a : aliases) {
            if (key.equals(a.toLowerCase())) {
                return v.substring(eq + 1).trim();
            }
        }
        for (String g : GENERIC) {
            if (key.equals(g)) {
                return v.substring(eq + 1).trim();
            }
        }
        return v;
    }
}
