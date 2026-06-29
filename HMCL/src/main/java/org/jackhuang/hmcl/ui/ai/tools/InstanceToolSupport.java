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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// Shared helpers for instance-lifecycle AI tools that drive HMCL's
/// {@link HMCLGameRepository} (rename / duplicate / delete an instance).
///
/// These tools reuse the exact repository calls performed by the native
/// versions context menu in {@code org.jackhuang.hmcl.ui.versions.Versions}.
final class InstanceToolSupport {

    private InstanceToolSupport() {
    }

    /// Returns the trimmed string value of a parameter, or {@code null} if absent or blank.
    @Nullable
    static String string(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /// Resolves the primary instance-name parameter, honouring the {@code query} fallback.
    @Nullable
    static String instanceName(Map<String, Object> parameters) {
        String instance = string(parameters, "instance");
        return instance != null ? instance : string(parameters, "query");
    }

    /// Parses a boolean parameter; accepts {@link Boolean} and the string {@code "true"}.
    /// Returns {@code false} for any other value (including absent).
    static boolean bool(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    /// Parses an integer parameter from {@code args} under {@code key}, returning {@code def}
    /// when the value is absent, blank, or unparseable.
    ///
    /// Gson decodes every JSON number as a {@link Double}, so an integer argument arrives as
    /// e.g. {@code 4096.0}; a naive {@code Integer.parseInt(String.valueOf(value))} would throw
    /// on that. This accepts a {@link Number} directly and tolerates a trailing {@code ".0"} on
    /// string values.
    static int parseInt(Map<String, Object> args, String key, int def) {
        return parseInt(args.get(key), def);
    }

    /// Parses an already-resolved parameter value as an integer (see {@link #parseInt(Map, String, int)}).
    /// Useful when the value may come from one of several keys (e.g. a {@code query} fallback).
    static int parseInt(@Nullable Object value, int def) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            String text = value.toString().trim();
            if (!text.isEmpty()) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    // Tolerate a number that arrived as a string with a trailing ".0".
                    try {
                        return (int) Math.round(Double.parseDouble(text));
                    } catch (NumberFormatException ignored2) {
                        // fall through to default
                    }
                }
            }
        }
        return def;
    }

    /// The currently selected profile's game repository.
    static HMCLGameRepository repository() {
        Profile profile = Profiles.getSelectedProfile();
        return profile.getRepository();
    }
}
