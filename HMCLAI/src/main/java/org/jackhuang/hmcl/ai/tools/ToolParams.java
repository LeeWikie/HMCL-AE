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
    /// value. A leading `key=` prefix the model sometimes bakes into the value is stripped ONLY on
    /// the generic/sole fallback paths — a value the model sent under the declared name (or an
    /// alias) is taken verbatim, so legitimate `foo=bar` content is never mangled.
    /// Returns "" if nothing usable is present.
    public static String string(Map<String, Object> params, String canonical, String... aliases) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        String v = raw(params, canonical);
        if (!v.isEmpty()) {
            return v;
        }
        for (String a : aliases) {
            v = raw(params, a);
            if (!v.isEmpty()) {
                return v;
            }
        }
        for (String g : GENERIC) {
            v = raw(params, g);
            if (!v.isEmpty()) {
                return stripKeyPrefix(v, canonical, aliases);
            }
        }
        return stripKeyPrefix(soleValue(params, null), canonical, aliases);
    }

    /// Like {@link #string} but for a tool that ALSO declares secondary parameters (e.g. a world
    /// tool with an optional `instance` / `backupId` / `confirm`): the generic and sole-value
    /// fallbacks ignore entries whose key is one of {@code reservedKeys}, so a call that carries
    /// ONLY a secondary parameter can never have that value stolen as the primary one (which
    /// produced misleading "world 'true' was not found" errors that sent the model flailing).
    public static String primary(Map<String, Object> params, String canonical,
                                 String[] reservedKeys, String... aliases) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        String v = raw(params, canonical);
        if (!v.isEmpty()) {
            return v;
        }
        for (String a : aliases) {
            v = raw(params, a);
            if (!v.isEmpty()) {
                return v;
            }
        }
        java.util.Set<String> reserved = new java.util.HashSet<>();
        for (String r : reservedKeys) {
            reserved.add(r.toLowerCase(java.util.Locale.ROOT));
        }
        for (String g : GENERIC) {
            if (reserved.contains(g)) {
                continue;
            }
            v = raw(params, g);
            if (!v.isEmpty()) {
                return stripKeyPrefix(v, canonical, aliases);
            }
        }
        return stripKeyPrefix(soleValue(params, reserved), canonical, aliases);
    }

    /// Like {@link #string} but WITHOUT the generic-dump-key / sole-value fallbacks and WITHOUT any
    /// value rewriting. Use for a tool with SEVERAL required params, where those fallbacks could
    /// grab a value meant for a different param (e.g. set_game_option's key + value).
    public static String strict(Map<String, Object> params, String canonical, String... aliases) {
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
        return v;
    }

    private static String raw(Map<String, Object> p, String key) {
        Object o = p.get(key);
        return o == null ? "" : valueToString(o);
    }

    /// Stringifies a raw JSON value. Gson parses every JSON number as Double, which turns an
    /// integer argument like `12` into `"12.0"` — and a tool would then write `renderDistance:12.0`
    /// into options.txt or look for a world literally named "123.0". Whole numbers are therefore
    /// rendered without the fractional part.
    private static String valueToString(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) <= 9_007_199_254_740_992.0) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(o).trim();
    }

    /// The single non-blank value if the map has exactly one (ignoring {@code reserved} keys, which
    /// belong to other declared parameters); otherwise "" (ambiguous — don't guess).
    private static String soleValue(Map<String, Object> p, java.util.Set<String> reserved) {
        String only = "";
        for (Map.Entry<String, Object> e : p.entrySet()) {
            if (e.getValue() == null) continue;
            if (reserved != null && e.getKey() != null
                    && reserved.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            String s = valueToString(e.getValue());
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
