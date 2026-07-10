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
package org.jackhuang.hmcl.ai.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Persists user-facing AI tool permission overrides.
///
/// The global approval mode still lives in {@code AiSettings}; this store only
/// records per-tool overrides. A missing tool entry means "follow global".
///
/// ## Shape, independent of how many global approval modes exist
///
/// Before the original approval-mode merge (see {@link org.jackhuang.hmcl.ai.AiApprovalMode}'s own
/// doc for the SAFE/ASK/YOLO &rarr; single-AUTO &rarr; restored-Auto/Ask/yolo history), an override
/// picked one of the same three tiers as the (then three-way) global mode. During the single-mode
/// era, "override to a specific tier" stopped meaning anything, so {@link OverrideMode} was
/// redesigned to instead directly describe the two ways a per-tool override can DIFFER from the
/// global mode's own computed decision: always skip asking ({@link OverrideMode#ALWAYS_ALLOW}) or
/// always keep asking / stay conservative regardless of the global toggles ({@link
/// OverrideMode#ALWAYS_ASK}). That two-direction shape is orthogonal to how many global modes exist
/// — it reads just as naturally against today's restored three-way `Auto`/`Ask`/`yolo` pick as it
/// did against the single `Auto` mode, so it was NOT reverted back to a three-tier override when
/// the global mode was restored to three values. Both directions are still load-bearing: the
/// "remember this choice" checkbox on a confirmation dialog (see
/// {@code AIMainPage#rememberConfirmDecision}) records exactly one of these two outcomes for that
/// tool/action, and — critically — neither can ever unblock a call the policy has already
/// hard-BLOCKed (Plan Mode, or a DANGEROUS_WRITE call on a possibly-unattended turn — a rule that
/// holds no matter which of the three global modes, including `yolo`, is active); see
/// {@link OverrideMode#apply}.
@NotNullByDefault
public final class AiToolPermissionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum OverrideMode {
        /// No override — defer entirely to the global {@code AiExecutionPolicy} decision for this
        /// call (the common case for every tool the user hasn't customised).
        FOLLOW_GLOBAL("follow-global"),
        /// Always ask (or stay at whatever the global decision already was, if it was stricter)
        /// for this tool/action/path, regardless of the dangerous/file-write confirmation toggles.
        /// Legacy ids `"safe"` and `"ask"` both resolve here — those two modes were already
        /// enforced identically before the merge, so there is nothing to distinguish between them.
        ALWAYS_ASK("ask"),
        /// Always allow this tool/action/path without asking — UNLESS the global decision was
        /// already a non-negotiable BLOCK (Plan Mode, or an unattended DANGEROUS_WRITE call), which
        /// this can never override. Legacy id `"yolo"` resolves here.
        ALWAYS_ALLOW("yolo");

        private final String id;

        OverrideMode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static OverrideMode fromId(@Nullable String id) {
            if (id == null) return FOLLOW_GLOBAL;
            // Legacy alias: "safe" predates this enum's own id space entirely (ALWAYS_ASK's `id` is
            // "ask", reused as-is from before the merge since SAFE and ASK were already enforced
            // identically) — without this, an old "safe" override would silently fall through to
            // the FOLLOW_GLOBAL default below instead of the conservative ALWAYS_ASK it used to mean.
            if ("safe".equalsIgnoreCase(id)) return ALWAYS_ASK;
            for (OverrideMode mode : values()) {
                if (mode.id.equalsIgnoreCase(id)) return mode;
            }
            return FOLLOW_GLOBAL;
        }

        /// Applies this override to an already-computed {@code baseDecision} (the result of
        /// {@code AiExecutionPolicy.check(...)}) for a call classified as {@code permission}.
        ///
        /// A BLOCK is NEVER touched: it always means a non-negotiable safety gate already fired
        /// (Plan Mode, or a DANGEROUS_WRITE call on a possibly-unattended turn — see
        /// {@code AiExecutionPolicy}'s class doc), and no per-tool override is allowed to relax
        /// that. Otherwise {@link #ALWAYS_ALLOW} forces ALLOW outright (the "remembered yes" case),
        /// {@link #ALWAYS_ASK} forces ASK — but only for CONTROLLED_WRITE/DANGEROUS_WRITE, mirroring
        /// the historical Safe/Ask modes, which never gated READ_ONLY/EXTERNAL_NETWORK either — and
        /// {@link #FOLLOW_GLOBAL} passes {@code baseDecision} through unchanged.
        public AiExecutionPolicy.Decision apply(AiExecutionPolicy.Decision baseDecision, ToolPermission permission) {
            if (baseDecision == AiExecutionPolicy.Decision.BLOCK) {
                return baseDecision;
            }
            return switch (this) {
                case FOLLOW_GLOBAL -> baseDecision;
                case ALWAYS_ALLOW -> AiExecutionPolicy.Decision.ALLOW;
                case ALWAYS_ASK -> (permission == ToolPermission.CONTROLLED_WRITE || permission == ToolPermission.DANGEROUS_WRITE)
                        ? AiExecutionPolicy.Decision.ASK
                        : baseDecision;
            };
        }
    }

    /// One path-glob-scoped override row: applies {@code mode} when the call's path parameter
    /// matches {@code glob} (see {@link #matchesGlob}). Evaluated in insertion order; the first
    /// match wins. Scoped to file-path-taking tools (write/edit/read/grep/glob, ...) — see
    /// {@link #getOverride(String, String, String)}.
    public record PathOverride(String glob, OverrideMode mode) {
    }

    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class PersistedPathOverride {
        @SerializedName("glob")
        @Nullable
        private String glob;
        @SerializedName("mode")
        @Nullable
        private String mode;
    }

    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class PersistedData {
        @SerializedName("toolOverrides")
        @Nullable
        private Map<String, String> toolOverrides = null;
        @SerializedName("pathOverrides")
        @Nullable
        private Map<String, List<PersistedPathOverride>> pathOverrides = null;
    }

    private final Path file;
    private final Map<String, OverrideMode> overrides = new LinkedHashMap<>();
    /// Tool name -> ordered list of path-glob rules (see {@link PathOverride}). Empty/absent for
    /// every tool until the user adds one via the settings page.
    private final Map<String, List<PathOverride>> pathOverrides = new LinkedHashMap<>();

    public AiToolPermissionStore(Path file) {
        this.file = file;
    }

    public void load() throws IOException, JsonParseException {
        overrides.clear();
        pathOverrides.clear();
        if (!Files.exists(file)) return;
        String json = Files.readString(file, StandardCharsets.UTF_8);
        PersistedData data = GSON.fromJson(json, PersistedData.class);
        if (data == null) return;
        if (data.toolOverrides != null) {
            for (Map.Entry<String, String> entry : data.toolOverrides.entrySet()) {
                OverrideMode mode = OverrideMode.fromId(entry.getValue());
                if (mode != OverrideMode.FOLLOW_GLOBAL) {
                    overrides.put(entry.getKey(), mode);
                }
            }
        }
        if (data.pathOverrides != null) {
            for (Map.Entry<String, List<PersistedPathOverride>> entry : data.pathOverrides.entrySet()) {
                List<PathOverride> rules = new ArrayList<>();
                for (PersistedPathOverride p : entry.getValue()) {
                    if (p == null || p.glob == null || p.glob.isBlank()) continue;
                    OverrideMode mode = OverrideMode.fromId(p.mode);
                    if (mode == OverrideMode.FOLLOW_GLOBAL) continue;
                    rules.add(new PathOverride(p.glob, mode));
                }
                if (!rules.isEmpty()) {
                    pathOverrides.put(entry.getKey(), rules);
                }
            }
        }
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        PersistedData data = new PersistedData();
        data.toolOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, OverrideMode> entry : overrides.entrySet()) {
            if (entry.getValue() != OverrideMode.FOLLOW_GLOBAL) {
                data.toolOverrides.put(entry.getKey(), entry.getValue().getId());
            }
        }
        data.pathOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, List<PathOverride>> entry : pathOverrides.entrySet()) {
            List<PersistedPathOverride> rules = new ArrayList<>();
            for (PathOverride rule : entry.getValue()) {
                PersistedPathOverride p = new PersistedPathOverride();
                p.glob = rule.glob();
                p.mode = rule.mode().getId();
                rules.add(p);
            }
            if (!rules.isEmpty()) {
                data.pathOverrides.put(entry.getKey(), rules);
            }
        }
        Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    public OverrideMode getOverride(String toolName) {
        return overrides.getOrDefault(toolName, OverrideMode.FOLLOW_GLOBAL);
    }

    /// Resolves the override for one action of a merged domain tool: checks the action-scoped key
    /// (stored as literal string {@code "<toolName>.<action>"}) first, falling back to the
    /// tool-wide key when no action-specific override is set — so a user who only customised
    /// `instance.delete` still gets `instance`'s tool-wide setting (or global) for every other
    /// action, rather than silently losing per-tool control after tools get merged into domains.
    /// Pass {@code action == null} (or blank) to skip straight to the tool-wide lookup.
    public OverrideMode getOverride(String toolName, @Nullable String action) {
        if (action != null && !action.isBlank()) {
            OverrideMode scoped = overrides.get(actionKey(toolName, action));
            if (scoped != null) {
                return scoped;
            }
        }
        return getOverride(toolName);
    }

    public void setOverride(String toolName, OverrideMode mode) {
        if (mode == OverrideMode.FOLLOW_GLOBAL) {
            overrides.remove(toolName);
        } else {
            overrides.put(toolName, mode);
        }
    }

    /// Sets an override scoped to one action of a domain tool (key {@code "<toolName>.<action>"}).
    /// {@code FOLLOW_GLOBAL} removes the action-scoped override so the lookup falls back to the
    /// tool-wide key, not necessarily to the global default.
    public void setOverride(String toolName, String action, OverrideMode mode) {
        String key = actionKey(toolName, action);
        if (mode == OverrideMode.FOLLOW_GLOBAL) {
            overrides.remove(key);
        } else {
            overrides.put(key, mode);
        }
    }

    private static String actionKey(String toolName, String action) {
        return toolName + "." + action;
    }

    public Map<String, OverrideMode> getOverrides() {
        return Collections.unmodifiableMap(overrides);
    }

    /// Resolves the override for a call that ALSO carries a file-path-like parameter (e.g.
    /// `write`/`edit`'s `path`, or `grep`/`glob`'s `path`) — a path-glob rule (see
    /// {@link #setPathOverride}) is tried FIRST, in insertion order, before falling back to the
    /// ordinary tool/action-scoped lookup ({@link #getOverride(String, String)}). Lets a user say
    /// "always allow writes under mods/** without asking, but always ask under saves/**" instead of
    /// only a blanket per-tool override.
    ///
    /// @param toolName the tool being invoked
    /// @param action   the call's resolved `action` parameter, or {@code null}/blank if none
    /// @param path     the call's resolved path-like parameter, or {@code null}/blank if the tool
    ///                 has none (or this call didn't supply one) — skips straight to the ordinary
    ///                 lookup in that case
    public OverrideMode getOverride(String toolName, @Nullable String action, @Nullable String path) {
        if (path != null && !path.isBlank()) {
            for (PathOverride rule : pathOverrides.getOrDefault(toolName, Collections.emptyList())) {
                if (matchesGlob(rule.glob(), path)) {
                    return rule.mode();
                }
            }
        }
        return getOverride(toolName, action);
    }

    /// Returns {@code toolName}'s path-glob rules, in match-priority (insertion) order.
    public List<PathOverride> getPathOverrides(String toolName) {
        return Collections.unmodifiableList(pathOverrides.getOrDefault(toolName, Collections.emptyList()));
    }

    /// Adds (or replaces, if {@code glob} already has a rule for this tool) a path-glob override
    /// row. {@code FOLLOW_GLOBAL} removes the rule instead of storing a no-op one.
    public void setPathOverride(String toolName, String glob, OverrideMode mode) {
        List<PathOverride> rules = pathOverrides.computeIfAbsent(toolName, k -> new ArrayList<>());
        rules.removeIf(r -> r.glob().equals(glob));
        if (mode != OverrideMode.FOLLOW_GLOBAL) {
            rules.add(new PathOverride(glob, mode));
        }
        if (rules.isEmpty()) {
            pathOverrides.remove(toolName);
        }
    }

    /// Removes {@code toolName}'s path-glob rule for {@code glob}, if any.
    public void removePathOverride(String toolName, String glob) {
        List<PathOverride> rules = pathOverrides.get(toolName);
        if (rules == null) return;
        rules.removeIf(r -> r.glob().equals(glob));
        if (rules.isEmpty()) {
            pathOverrides.remove(toolName);
        }
    }

    /// A single `*` matches any run of characters EXCEPT a path separator; `**` matches across
    /// separators too (gitignore/glob convention); `?` matches exactly one non-separator character;
    /// every other character is literal. Both {@code pattern} and {@code path} are normalized to
    /// forward slashes first so the same pattern matches on Windows and POSIX paths alike.
    public static boolean matchesGlob(String pattern, String path) {
        String normalizedPattern = pattern.replace('\\', '/');
        String normalizedPath = path.replace('\\', '/');
        return Pattern.compile(globToRegex(normalizedPattern)).matcher(normalizedPath).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < n && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    re.append(".*");
                    i += 2;
                    // Swallow an immediately-following '/' so "a/**/b" also matches "a/b".
                    if (i < n && glob.charAt(i) == '/') {
                        i++;
                    }
                } else {
                    re.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                re.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    re.append('\\');
                }
                re.append(c);
                i++;
            }
        }
        re.append('$');
        return re.toString();
    }
}
