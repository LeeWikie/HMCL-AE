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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
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

    /// Resolves an optional string-valued parameter that also honours the generic {@code query}
    /// alias, distinguishing "key present (even as an empty string)" — a deliberate value,
    /// including an explicit clear/reset request — from "key absent entirely" (report-only /
    /// nothing to do). Unlike {@link #instanceName}, an empty string is returned as-is rather
    /// than treated as absent, since some callers (e.g. clearing a free-text setting) need to
    /// tell "clear it" apart from "the caller didn't say anything about it".
    ///
    /// @param key the primary parameter name to look up first
    /// @return the value under {@code key} if present (even if {@code null} or blank), else the
    ///         value under {@code query} if THAT key is present, else {@code null} if neither key
    ///         was supplied at all
    @Nullable
    static Object presentOrQueryFallback(Map<String, Object> parameters, String key) {
        if (parameters.containsKey(key)) {
            return parameters.get(key);
        }
        if (parameters.containsKey("query")) {
            return parameters.get("query");
        }
        return null;
    }

    /// Returns whether the given JVM-args string mentions a heap-size flag ({@code -Xmx}/{@code
    /// -Xms}), case-insensitively. Used to attach a non-blocking advisory note steering the model
    /// back to the dedicated {@code set_memory} action instead of duplicating heap size here.
    static boolean mentionsHeapSizeFlag(String jvmArgs) {
        String lower = jvmArgs.toLowerCase(Locale.ROOT);
        return lower.contains("-xmx") || lower.contains("-xms");
    }

    /// The currently selected profile's game repository.
    static HMCLGameRepository repository() {
        Profile profile = Profiles.getSelectedProfile();
        return profile.getRepository();
    }

    /// Outcome of {@link #resolveInstance}: exactly one of {@code name} / {@code failure} is set.
    record ResolvedInstance(@Nullable String name,
                            @Nullable org.jackhuang.hmcl.ai.tools.ToolResult failure) {
    }

    /// Resolves which instance an instance-scoped tool should operate on.
    ///
    /// Rules (learned from a real session where the model asked about instance "Simple Love"
    /// under the key {@code query} and the tool silently answered about the SELECTED instance,
    /// sending the whole conversation down a wrong path):
    /// - an explicit {@code instance} parameter wins; with {@code allowGenericAliases} the
    ///   {@code name}/{@code query}/{@code version} keys are honoured too (for tools whose ONLY
    ///   string parameter is the instance, so a mis-named key can't mean anything else);
    /// - a NAMED instance that doesn't exist is a hard failure listing the real instance names —
    ///   never a silent fallback to the selected instance;
    /// - no name at all → the selected instance, or a failure when none is selected.
    static ResolvedInstance resolveInstance(HMCLGameRepository repository,
                                            Map<String, Object> parameters,
                                            boolean allowGenericAliases) {
        String name = string(parameters, "instance");
        if (name == null && allowGenericAliases) {
            for (String alias : new String[]{"name", "query", "version"}) {
                name = string(parameters, alias);
                if (name != null) {
                    break;
                }
            }
        }
        if (name != null) {
            if (!repository.hasVersion(name)) {
                return new ResolvedInstance(null, instanceNotFoundFailure(repository, name));
            }
            return new ResolvedInstance(name, null);
        }
        String selected = Profiles.getSelectedInstance();
        if (selected == null) {
            return new ResolvedInstance(null, ToolFailures.failure(
                    "No instance is selected and no 'instance' parameter was given",
                    ToolFailures.Retryable.YES,
                    "the tool needs to know which instance to act on",
                    "available instances: " + availableInstanceNames(repository)
                            + ". Call instance(action=\"list\"), then retry with instance=<name>"));
        }
        return new ResolvedInstance(selected, null);
    }

    /// The unified "named instance does not exist" failure envelope, carrying the real instance
    /// names (the candidate list the model is looking for). Shared by [#resolveInstance] and the
    /// tools that resolve the instance name themselves before validating it — notably the
    /// confirm-gated delete/rename, which deliberately must NOT fall back to the selected instance
    /// the way [#resolveInstance] does, so they cannot simply delegate the whole resolution here.
    static ToolResult instanceNotFoundFailure(HMCLGameRepository repository, String name) {
        return ToolFailures.failure(
                "Instance '" + name + "' does not exist in the selected profile",
                ToolFailures.Retryable.YES,
                "the name is wrong, not a missing instance — use the exact id",
                "available instances: " + availableInstanceNames(repository)
                        + ". Use instance(action=\"list\") to refresh, or omit 'instance' for the "
                        + "currently selected one");
    }

    /// Comma-joined instance ids of the repository, capped so a huge library can't flood a
    /// tool-error message. Package-visible so the NBT tool suite ({@link NbtToolSupport#requireInstance})
    /// can reuse the same candidate list in its own "no such instance" envelope.
    static String availableInstanceNames(HMCLGameRepository repository) {
        java.util.List<String> names;
        try {
            names = repository.getDisplayVersions()
                    .map(v -> v.getId())
                    .limit(21)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Throwable e) {
            // Building an error message must never itself throw — e.g. the repository has not been
            // refreshed yet (its version map is still null). Degrade to a safe placeholder.
            return "(unavailable — call instance(action=\"list\"))";
        }
        if (names.isEmpty()) {
            return "(none — no instances installed)";
        }
        if (names.size() > 20) {
            return String.join(", ", names.subList(0, 20)) + ", … (use instance(action=\"list\") for the full list)";
        }
        return String.join(", ", names);
    }
}
