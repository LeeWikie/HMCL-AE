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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.CrashReportAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// An AI tool that matches a Minecraft log / crash report against HMCL's curated
/// crash-rule knowledge base and returns the matched known issues together with
/// plain-language causes and suggested fixes.
///
/// This tool is a thin wrapper around {@link CrashReportAnalyzer}, the launcher's
/// existing crash-report analyzer that already ships dozens of regex rules for the
/// most common Minecraft launch/crash failures (out of memory, outdated/too-new
/// Java, missing or duplicate mods, OptiFine incompatibilities, corrupt
/// installations, and so on). Reusing it keeps the AI's "fast localization" pass in
/// sync with the rules HMCL shows in its native crash window.
///
/// The agent should call this first for a quick, deterministic diagnosis, then fall
/// back to its own reasoning over the full log when nothing matches.
///
/// Lives in the HMCL module (not HMCLAI) because {@link CrashReportAnalyzer} and the
/// i18n resources it relies on live in HMCLCore, which HMCLAI does not depend on.
///
/// @see Tool
/// @see CrashReportAnalyzer
public final class KnownErrorMatcherTool implements Tool {

    /// Maximum number of stack-trace keywords (suspected mods) to report when no rule matches.
    private static final int MAX_KEYWORDS = 12;

    /// Pattern used to translate Fabric "{modid @ version}" tokens into readable names.
    private static final Pattern FABRIC_MOD_ID = Pattern.compile("\\{(?<modid>.*?) @ (?<version>.*?)}");

    /// Returns the unique name `"match_known_errors"`.
    @Override
    public String getName() {
        return "match_known_errors";
    }

    /// Returns a human-readable description of what the tool does.
    @Override
    public String getDescription() {
        return "Matches a Minecraft log or crash report against HMCL's built-in crash-rule "
                + "knowledge base and returns the matched known issues with their plain-language "
                + "cause and suggested fix. Use this first for fast, deterministic localization; "
                + "if nothing matches, fall back to your own reasoning over the full log. "
                + "Parameter: 'text' (string, the crash/log text; alias 'log').";
    }

    /// Analyzes the provided crash/log text.
    ///
    /// @param parameters a map that must contain `"text"` (or `"log"`) with a non-blank value
    /// @return a successful result listing the matched known issues, or a failure result
    ///         describing what went wrong
    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object raw = parameters.get("text");
        if (raw == null) {
            raw = parameters.get("log");
        }
        if (!(raw instanceof String)) {
            return ToolResult.failure("Missing or invalid 'text' parameter: "
                    + "a non-empty crash/log string is required (you may also use 'log').");
        }

        String log = ((String) raw).trim();
        if (log.isEmpty()) {
            return ToolResult.failure("Cannot analyze empty log text.");
        }

        Set<CrashReportAnalyzer.Result> matched;
        try {
            matched = CrashReportAnalyzer.analyze(log);
        } catch (RuntimeException e) {
            return ToolResult.failure("Crash analysis failed: " + e.getMessage());
        }

        // De-duplicate by rule (the same rule can match multiple times), keeping rule order.
        EnumMap<CrashReportAnalyzer.Rule, CrashReportAnalyzer.Result> byRule =
                new EnumMap<>(CrashReportAnalyzer.Rule.class);
        for (CrashReportAnalyzer.Result result : matched) {
            byRule.putIfAbsent(result.getRule(), result);
        }

        StringBuilder out = new StringBuilder();
        out.append("=== Known Issue Matcher (HMCL crash-rule knowledge base) ===\n");

        if (byRule.isEmpty()) {
            out.append("No known issue matched the built-in crash rules.\n");

            // Best-effort: surface suspected third-party packages from the stack trace,
            // which is what HMCL itself shows when no rule matches.
            List<String> keywords = new ArrayList<>(safeKeywords(log));
            if (!keywords.isEmpty()) {
                if (keywords.size() > MAX_KEYWORDS) {
                    keywords = keywords.subList(0, MAX_KEYWORDS);
                }
                out.append("Suspected components from the stack trace (may indicate the culprit mod): ")
                        .append(String.join(", ", keywords)).append('\n');
            }
            out.append("\nNo curated rule matched — fall back to your own reasoning over the full log.\n");
            return ToolResult.success(out.toString().trim());
        }

        out.append("Matched ").append(byRule.size()).append(" known issue(s):\n");
        int index = 1;
        for (CrashReportAnalyzer.Result result : byRule.values()) {
            out.append('\n').append('[').append(index++).append("] ")
                    .append(result.getRule().name()).append('\n');
            out.append(describe(result)).append('\n');
        }

        out.append("\nThese diagnoses come from HMCL's curated crash-rule database. "
                + "If they look incomplete, refine with your own reasoning over the full log.\n");
        return ToolResult.success(out.toString().trim());
    }

    /// Builds the plain-language cause + fix message for a matched rule, mirroring the
    /// logic HMCL uses in its native crash window (`GameCrashWindow`).
    ///
    /// @param result the matched analyzer result
    /// @return a localized, human-readable cause + fix description
    private static String describe(CrashReportAnalyzer.Result result) {
        CrashReportAnalyzer.Rule rule = result.getRule();
        Matcher matcher = result.getMatcher();
        try {
            switch (rule) {
                case TOO_OLD_JAVA:
                    return i18n("game.crash.reason.too_old_java",
                            CrashReportAnalyzer.getJavaVersionFromMajorVersion(
                                    Integer.parseInt(matcher.group("expected"))));
                case MOD_RESOLUTION_CONFLICT:
                case MOD_RESOLUTION_MISSING:
                case MOD_RESOLUTION_COLLECTION:
                    return i18n("game.crash.reason." + rule.name().toLowerCase(Locale.ROOT),
                            translateFabricModId(matcher.group("sourcemod")),
                            parseFabricModId(matcher.group("destmod")),
                            parseFabricModId(matcher.group("destmod")));
                case MOD_RESOLUTION_MISSING_MINECRAFT:
                    return i18n("game.crash.reason." + rule.name().toLowerCase(Locale.ROOT),
                            translateFabricModId(matcher.group("mod")),
                            matcher.group("version"));
                case MOD_FOREST_OPTIFINE:
                case TWILIGHT_FOREST_OPTIFINE:
                case PERFORMANT_FOREST_OPTIFINE:
                case JADE_FOREST_OPTIFINE:
                case NEOFORGE_FOREST_OPTIFINE:
                    return i18n("game.crash.reason.mod", "OptiFine");
                default:
                    return i18n("game.crash.reason." + rule.name().toLowerCase(Locale.ROOT),
                            Arrays.stream(rule.getGroupNames())
                                    .map(matcher::group)
                                    .toArray());
            }
        } catch (RuntimeException e) {
            // Missing i18n key, format mismatch, or absent capture group — degrade gracefully.
            return i18n("game.crash.reason." + rule.name().toLowerCase(Locale.ROOT));
        }
    }

    /// Translates well-known Fabric mod ids into friendly names.
    private static String translateFabricModId(String modName) {
        switch (modName) {
            case "fabricloader":
                return "Fabric";
            case "fabric":
                return "Fabric API";
            case "minecraft":
                return "Minecraft";
            default:
                return modName;
        }
    }

    /// Parses a Fabric "{modid @ version}" token into a readable, localized requirement string.
    private static String parseFabricModId(String modName) {
        Matcher matcher = FABRIC_MOD_ID.matcher(modName);
        if (matcher.find()) {
            String modid = matcher.group("modid");
            String version = matcher.group("version");
            if ("[*]".equals(version)) {
                return i18n("game.crash.reason.mod_resolution_mod_version.any", translateFabricModId(modid));
            } else {
                return i18n("game.crash.reason.mod_resolution_mod_version", translateFabricModId(modid), version);
            }
        }
        return translateFabricModId(modName);
    }

    /// Extracts suspected stack-trace keywords without throwing on malformed input.
    private static Set<String> safeKeywords(String log) {
        try {
            return CrashReportAnalyzer.findKeywordsFromCrashReport(log);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }
}
