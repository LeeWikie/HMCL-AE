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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`CrashAnalyzerTool`] covering exception extraction,
/// stack frame parsing, and mod list detection from sample crash reports.
public final class CrashAnalyzerToolTest {

    /// A realistic Minecraft crash report for a NullPointerException.
    private static final String SAMPLE_CRASH =
            "---- Minecraft Crash Report ----\n"
                    + "java.lang.NullPointerException: Cannot invoke \"Object.toString()\"\n"
                    + "\tat com.example.Mod.doSomething(Mod.java:42)\n"
                    + "\tat net.minecraft.client.Minecraft.lambda$run$0(Minecraft.java:100)\n"
                    + "\tat net.minecraft.client.Minecraft.run(Minecraft.java:200)\n"
                    + "-- Mod List --\n"
                    + "    - examplemod (Example Mod) 1.0.0\n"
                    + "    - anothermod (Another Mod) 2.0.0\n";

    /// A crash report with no mod list section.
    private static final String CRASH_NO_MODS =
            "---- Minecraft Crash Report ----\n"
                    + "java.lang.OutOfMemoryError: Java heap space\n"
                    + "\tat com.example.BigMod.loadHugeTexture(BigMod.java:99)\n"
                    + "\tat net.minecraft.client.renderer.texture.SimpleTexture.<init>(SimpleTexture.java:55)\n";

    /// A crash report for an incompatible class change.
    private static final String CRASH_INCOMPATIBLE =
            "---- Minecraft Crash Report ----\n"
                    + "java.lang.NoSuchMethodError: net.minecraft.world.World.getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;\n"
                    + "\tat com.example.CompatMod.onWorldLoad(CompatMod.java:15)\n"
                    + "-- Mod List --\n"
                    + "    - compatmod (Compat Mod) 3.0.0\n";

    /// Verifies that a full crash report is parsed correctly, extracting
    /// the exception class, stack frames, and mod list.
    @Test
    public void testParseFullCrashReport() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of("crash_text", SAMPLE_CRASH));

        assertTrue(result.isSuccess(), "should succeed for a valid crash report");

        String output = result.getOutput();
        assertTrue(output.contains("NullPointerException"),
                "should extract the exception class name");
        assertTrue(output.contains("Cannot invoke"),
                "should extract the exception message");
        assertTrue(output.contains("com.example.Mod.doSomething"),
                "should include the first stack frame method");
        assertTrue(output.contains("Mod.java:42"),
                "should include the first stack frame location");
        assertTrue(output.contains("net.minecraft.client.Minecraft.lambda$run$0"),
                "should include the second stack frame method");
        assertTrue(output.contains("Minecraft.java:100"),
                "should include the second stack frame location");
        assertTrue(output.contains("Example Mod"),
                "should extract the Example Mod from the mod list");
        assertTrue(output.contains("Another Mod"),
                "should extract Another Mod from the mod list");
        assertTrue(output.contains("Suspected Cause"),
                "should include a suspected cause section");
    }

    /// Verifies that a crash report without a mod list section is still
    /// parsed successfully, reporting no mods.
    @Test
    public void testParseCrashWithoutMods() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of("crash_text", CRASH_NO_MODS));

        assertTrue(result.isSuccess(), "should succeed even without mod list");
        String output = result.getOutput();
        assertTrue(output.contains("OutOfMemoryError"),
                "should extract the OutOfMemoryError exception");
        assertTrue(output.contains("com.example.BigMod.loadHugeTexture"),
                "should include the stack frame");
        assertTrue(output.contains("none listed"),
                "should report no mods listed");
        assertTrue(output.contains("ran out of memory"),
                "suspected cause should mention memory");
    }

    /// Verifies that the tool detects incompatible class/method issues.
    @Test
    public void testIncompatibleModDetection() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of("crash_text", CRASH_INCOMPATIBLE));

        assertTrue(result.isSuccess());
        String output = result.getOutput();
        assertTrue(output.contains("NoSuchMethodError"),
                "should extract the NoSuchMethodError exception");
        assertTrue(output.contains("com.example.CompatMod.onWorldLoad"),
                "should include the stack frame");
        assertTrue(output.contains("Compat Mod"),
                "should extract the Fabric-style mod entry");
        assertTrue(output.contains("incompatible"),
                "suspected cause should mention incompatibility");
    }

    /// Verifies that the tool name and description are correct.
    @Test
    public void testNameAndDescription() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        assertEquals("analyze_crash", tool.getName(),
                "tool name should match");
        assertFalse(tool.getDescription().isBlank(),
                "description should not be blank");
    }

    /// A missing `crash_text` parameter should return a failure result.
    @Test
    public void testMissingCrashTextParameter() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of());

        assertFalse(result.isSuccess(),
                "should fail when crash_text parameter is missing");
        assertNotNull(result.getError(),
                "error message should be set");
        assertTrue(result.getError().contains("crash_text"),
                "error should mention the missing parameter");
    }

    /// An empty `crash_text` parameter should return a failure result.
    @Test
    public void testEmptyCrashText() {
        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of("crash_text", "   "));

        assertFalse(result.isSuccess(),
                "should fail for blank crash text");
        assertTrue(result.getError().contains("empty"),
                "error should mention empty text");
    }

    /// A crash report with an unrecognized exception type still produces
    /// a valid analysis with a generic suspected cause.
    @Test
    public void testUnknownExceptionType() {
        String unknownCrash =
                "---- Minecraft Crash Report ----\n"
                        + "some.weird.Throwable: Something broke\n"
                        + "\tat com.example.Mod.init(Mod.java:10)\n";

        CrashAnalyzerTool tool = new CrashAnalyzerTool();
        ToolResult result = tool.execute(Map.of("crash_text", unknownCrash));

        assertTrue(result.isSuccess(),
                "should succeed even for unrecognized exception types");
        String output = result.getOutput();
        assertTrue(output.contains("some.weird.Throwable"),
                "should extract the Throwable name");
        assertTrue(output.contains("Something broke"),
                "should extract the message");
        assertTrue(output.contains("Unknown error type")
                        || output.contains("generic"),
                "should provide a generic cause for unrecognized types");
    }
}
