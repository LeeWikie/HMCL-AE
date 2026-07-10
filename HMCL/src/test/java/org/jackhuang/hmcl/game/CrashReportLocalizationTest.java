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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ui.ai.tools.KnownErrorMatcherTool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.*;

/// L8 regression lock: the crash rule→text switch used to exist twice (in `GameCrashWindow`
/// and in `KnownErrorMatcherTool`) and drift apart on every new rule. Both callers now
/// delegate to [CrashReportLocalization]; these tests pin the shared recipes for every
/// special-cased branch and verify the AI-tool caller embeds exactly the shared text.
/// (`GameCrashWindow`'s consistency is structural — its message loop calls the same shared
/// method — and needs no Stage to prove.)
public final class CrashReportLocalizationTest {

    /// Runs the real analyzer on a crafted log and returns the result matched by `rule`.
    private static CrashReportAnalyzer.Result analyzeFor(String log, CrashReportAnalyzer.Rule rule) {
        Set<CrashReportAnalyzer.Result> results = CrashReportAnalyzer.analyze(log);
        return results.stream()
                .filter(r -> r.getRule() == rule)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected rule " + rule + " to match: " + log));
    }

    @Test
    public void tooOldJavaUsesResolvedJavaVersion() {
        CrashReportAnalyzer.Result result = analyzeFor(
                "java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main version 60.0",
                CrashReportAnalyzer.Rule.TOO_OLD_JAVA);

        assertEquals(
                i18n("game.crash.reason.too_old_java", CrashReportAnalyzer.getJavaVersionFromMajorVersion(60)),
                CrashReportLocalization.getReasonText(result));
    }

    @Test
    public void modResolutionMissingTranslatesFabricIds() {
        CrashReportAnalyzer.Result result = analyzeFor(
                "ModResolutionException: Could not find required mod: modmenu requires {fabricloader @ [*]}",
                CrashReportAnalyzer.Rule.MOD_RESOLUTION_MISSING);

        String dest = i18n("game.crash.reason.mod_resolution_mod_version.any", "Fabric");
        assertEquals(
                i18n("game.crash.reason.mod_resolution_missing", "modmenu", dest, dest),
                CrashReportLocalization.getReasonText(result));
    }

    @Test
    public void forestOptifineFamilyBlamesOptiFine() {
        CrashReportAnalyzer.Result result = analyzeFor(
                "cpw.mods.modlauncher.InvalidLauncherSetupException: Invalid Services found OptiFine",
                CrashReportAnalyzer.Rule.NEOFORGE_FOREST_OPTIFINE);

        assertEquals(i18n("game.crash.reason.mod", "OptiFine"),
                CrashReportLocalization.getReasonText(result));
    }

    @Test
    public void defaultBranchFormatsRuleGroupNames() {
        CrashReportAnalyzer.Result result = analyzeFor(
                "java.lang.OutOfMemoryError: Java heap space",
                CrashReportAnalyzer.Rule.OUT_OF_MEMORY);

        assertEquals(i18n("game.crash.reason.out_of_memory"),
                CrashReportLocalization.getReasonText(result));
    }

    @Test
    public void absentCaptureGroupDegradesGracefullyInsteadOfThrowing() {
        // A Result whose matcher has none of the rule's named groups — the historical
        // KnownErrorMatcherTool behavior (fall back to the bare rule key) must be preserved
        // for both callers now that the switch is shared.
        Matcher foreign = Pattern.compile("x").matcher("x");
        assertTrue(foreign.find());
        CrashReportAnalyzer.Result result =
                new CrashReportAnalyzer.Result(CrashReportAnalyzer.Rule.TOO_OLD_JAVA, "x", foreign);

        String text = assertDoesNotThrow(() -> CrashReportLocalization.getReasonText(result));
        assertEquals(i18n("game.crash.reason.too_old_java"), text);
    }

    @Test
    public void translatesWellKnownFabricModIds() {
        assertEquals("Fabric", CrashReportLocalization.translateFabricModId("fabricloader"));
        assertEquals("Fabric API", CrashReportLocalization.translateFabricModId("fabric"));
        assertEquals("Minecraft", CrashReportLocalization.translateFabricModId("minecraft"));
        assertEquals("sodium", CrashReportLocalization.translateFabricModId("sodium"));
    }

    @Test
    public void parsesFabricModIdVersionTokens() {
        assertEquals(i18n("game.crash.reason.mod_resolution_mod_version", "sodium", "0.5.8"),
                CrashReportLocalization.parseFabricModId("{sodium @ 0.5.8}"));
        assertEquals(i18n("game.crash.reason.mod_resolution_mod_version.any", "Fabric API"),
                CrashReportLocalization.parseFabricModId("{fabric @ [*]}"));
        assertEquals("plainmod", CrashReportLocalization.parseFabricModId("plainmod"));
    }

    /// Caller-consistency: the AI tool's report must embed exactly the shared text — the same
    /// string `GameCrashWindow` renders for the same [CrashReportAnalyzer.Result].
    @Test
    public void knownErrorMatcherToolEmbedsSharedReasonText() {
        String log = "java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main version 61.0";
        CrashReportAnalyzer.Result result = analyzeFor(log, CrashReportAnalyzer.Rule.TOO_OLD_JAVA);
        String shared = CrashReportLocalization.getReasonText(result);

        ToolResult toolResult = new KnownErrorMatcherTool().execute(Map.of("text", log));

        assertTrue(toolResult.isSuccess(), "matcher tool should succeed on a known crash log");
        assertTrue(toolResult.getOutput().contains(shared),
                "tool output must contain the shared reason text\n--- shared ---\n" + shared
                        + "\n--- output ---\n" + toolResult.getOutput());
    }
}
