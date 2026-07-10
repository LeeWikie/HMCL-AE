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
package org.jackhuang.hmcl.game;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The single source of truth for turning a matched [CrashReportAnalyzer.Result] into the
/// localized plain-language "cause + suggested fix" text shown to users.
///
/// Historically this rule→text switch lived twice — once in `GameCrashWindow` (the native
/// crash window) and once in the AI `KnownErrorMatcherTool` — and every new
/// [CrashReportAnalyzer.Rule] special case had to be mirrored by hand, which is a drift
/// factory. Both callers now delegate here so the wording can never diverge again.
///
/// Lives in the HMCL module (next to `LauncherHelper`, in the same package as HMCLCore's
/// [CrashReportAnalyzer]) because the `game.crash.reason.*` i18n resources it renders are
/// bundled with the HMCL module, not HMCLCore.
public final class CrashReportLocalization {

    /// Pattern used to translate Fabric "{modid @ version}" tokens into readable names.
    private static final Pattern FABRIC_MOD_ID = Pattern.compile("\\{(?<modid>.*?) @ (?<version>.*?)}");

    private CrashReportLocalization() {
    }

    /// Builds the localized plain-language cause + fix message for a matched crash rule.
    ///
    /// Degrades gracefully (falls back to the rule's bare i18n key) on a missing i18n key,
    /// format mismatch, or absent regex capture group, so a knowledge-base edit can never
    /// turn the crash window or the AI tool into an exception fountain.
    ///
    /// @param result the matched analyzer result
    /// @return a localized, human-readable cause + fix description
    public static String getReasonText(CrashReportAnalyzer.Result result) {
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
    public static String translateFabricModId(String modName) {
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
    public static String parseFabricModId(String modName) {
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
}
