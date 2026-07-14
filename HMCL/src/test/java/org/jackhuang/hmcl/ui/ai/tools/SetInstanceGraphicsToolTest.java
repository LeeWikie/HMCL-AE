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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.GraphicsAPI;
import org.jackhuang.hmcl.setting.GameSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceGraphicsTool]'s deterministic contract over a real, throwaway instance
/// ([ProfileFixture]): the metadata/description contract, the "named instance does not exist"
/// guard, the parameter-validation guards for an unknown `graphicsBackend` and an unsupported
/// `openGLRenderer`/`vulkanRenderer` value, the report-only (no-parameters) branch, and — gated
/// behind [org.jackhuang.hmcl.JavaFXLauncher#isStarted], like `SetInstanceJvmArgsToolTest` — the
/// actual write path for `graphicsBackend` plus the `'inherit'` sentinel that clears the override
/// again.
///
/// Unlike [RollbackModTool] (whose `mod` guard fires before any [org.jackhuang.hmcl.setting.Profiles]
/// access), every branch of THIS tool — including its parameter-format guards — sits downstream of
/// `Profiles.getSelectedProfile()` and instance resolution (see the tool source: `graphicsBackend`
/// / `openGLRenderer` / `vulkanRenderer` are only parsed after the instance is resolved), so those
/// guards are exercised below through [ProfileFixture] like every other branch. The one guard that
/// genuinely needs no fixture is the outer "no profile selected" catch that wraps
/// `Profiles.getSelectedProfile()` itself — testable here only because no [ProfileFixture] is
/// open when this method runs: every fixture in this suite restores the pre-construction snapshot
/// of `Profiles`' static `selectedProfile` (null, since nothing else in this module sets it
/// outside a fixture) in its `close()`, so between test methods the tool sees no active profile.
///
/// NOT exercised here (left to the manual checklist): which concrete driver names this tool
/// reports as "supported" for OpenGL/Vulkan. That list comes from
/// [org.jackhuang.hmcl.game.Renderer#getSupported], computed per OS/GPU (Vulkan ICD file probing,
/// vendor detection via [org.jackhuang.hmcl.util.platform.hardware.GraphicsCard]) — the only
/// renderer name guaranteed present on every machine/OS combination is `DEFAULT`, so that is the
/// only one these tests rely on. Asserting a concrete driver (`LLVMPIPE`/`ZINK`/`D3D12`/
/// `NVIDIA_VULKAN`/...) is accepted or rejected would make the test's expected outcome depend on
/// which CI machine/OS runs the build — the same hardware-dependent-branch trap
/// [RollbackModToolTest] and `UpdateModToolTest` document pushing out of the unit-test tier.
public final class SetInstanceGraphicsToolTest {

    private final SetInstanceGraphicsTool tool = new SetInstanceGraphicsTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_instance_graphics", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("graphicsBackend"), "must document the 'graphicsBackend' parameter: " + description);
        assertTrue(description.contains("openGLRenderer"), "must document the 'openGLRenderer' parameter: " + description);
        assertTrue(description.contains("vulkanRenderer"), "must document the 'vulkanRenderer' parameter: " + description);
        assertTrue(description.contains("default/opengl/vulkan"),
                "must enumerate the valid backend values: " + description);
        assertTrue(description.toLowerCase(Locale.ROOT).contains("inherit"),
                "must document the 'inherit' sentinel that clears a per-instance override: " + description);
    }

    @Test
    void namedInstanceThatDoesNotExistIsRejectedWithCandidateList() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Real");

            ToolResult result = tool.execute(Map.of("instance", "Ghost", "graphicsBackend", "opengl"));

            assertFalse(result.isSuccess(), "an unknown instance name must be rejected, got: " + result.getOutput());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Real"),
                    "the failure should list the real instance names (candidate list): " + result.getError());
        }
    }

    @Test
    void noChangeParametersReportCurrentSettingsWithoutMutating() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            String output = result.getOutput();
            assertTrue(output.contains("Graphics backend"), "unexpected message: " + output);
            assertTrue(output.contains("OpenGL renderer"), "unexpected message: " + output);
            assertTrue(output.contains("Vulkan renderer"), "unexpected message: " + output);
            assertTrue(output.contains("Renderer in use"), "unexpected message: " + output);
            assertTrue(output.contains("Renderers supported on this machine"), "unexpected message: " + output);
            assertTrue(output.contains("(inherited)"),
                    "a freshly created instance has no per-field override yet: " + output);
            assertTrue(output.contains(GraphicsAPI.DEFAULT.name()), "must report the default backend: " + output);

            assertEquals(GraphicsAPI.DEFAULT,
                    fx.repository().getEffectiveGameSettings("Vanilla").getInheritable(GameSettings::graphicsBackendProperty),
                    "a report-only call must not create/mutate the instance's settings");
        }
    }

    @Test
    void unknownGraphicsBackendIsRejectedListingValidValues() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla2");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla2", "graphicsBackend", "d3d11"));

            assertFalse(result.isSuccess(), "an unknown backend must be rejected, got: " + result.getOutput());
            assertTrue(result.getError().contains("Unknown graphicsBackend"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("default, opengl, vulkan"),
                    "must list the valid backend values: " + result.getError());
        }
    }

    @Test
    void unsupportedRenderersAreRejectedListingSupportedNames() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla3");

            ToolResult openglResult = tool.execute(Map.of("instance", "Vanilla3", "openGLRenderer", "NOT_A_REAL_RENDERER"));
            assertFalse(openglResult.isSuccess(),
                    "an unsupported OpenGL renderer must be rejected, got: " + openglResult.getOutput());
            assertTrue(openglResult.getError().contains("OpenGL renderer 'NOT_A_REAL_RENDERER' is not supported"),
                    "unexpected message: " + openglResult.getError());
            assertTrue(openglResult.getError().contains("DEFAULT"),
                    "must list the machine's supported OpenGL renderers, which always include DEFAULT: "
                            + openglResult.getError());

            ToolResult vulkanResult = tool.execute(Map.of("instance", "Vanilla3", "vulkanRenderer", "NOT_A_REAL_RENDERER"));
            assertFalse(vulkanResult.isSuccess(),
                    "an unsupported Vulkan renderer must be rejected, got: " + vulkanResult.getOutput());
            assertTrue(vulkanResult.getError().contains("Vulkan renderer 'NOT_A_REAL_RENDERER' is not supported"),
                    "unexpected message: " + vulkanResult.getError());
            assertTrue(vulkanResult.getError().contains("DEFAULT"),
                    "must list the machine's supported Vulkan renderers, which always include DEFAULT: "
                            + vulkanResult.getError());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsGraphicsBackendRegistersOverrideThenInheritClearsIt() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");

            ToolResult setResult = tool.execute(Map.of("instance", "Modded", "graphicsBackend", "opengl"));
            assertTrue(setResult.isSuccess(), "expected success: " + setResult.getError());
            assertTrue(setResult.getOutput().contains("OPENGL"), "unexpected message: " + setResult.getOutput());
            assertTrue(setResult.getOutput().contains("was DEFAULT"), "unexpected message: " + setResult.getOutput());

            // See SetInstanceJvmArgsToolTest's awaitEffectiveJvmArgs for why this polls instead of
            // asserting immediately: ProfileFixture's one-shot background version refresh can still
            // be touching the same in-memory settings map right after fx.createInstance() returns.
            assertEquals(GraphicsAPI.OPENGL, awaitEffectiveBackend(fx, "Modded", GraphicsAPI.OPENGL),
                    "the tool must mutate the SAME in-memory settings object HMCLGameRepository holds");

            GameSettings.Instance settings = fx.repository().getInstanceGameSettings("Modded");
            assertTrue(settings != null && settings.getOverrideProperties().contains(GameSettings.PROPERTY_GRAPHICS_BACKEND),
                    "writing a concrete backend must register the override, or the value silently "
                            + "won't take effect at the next launch (the 'overrideProperties gotcha')");

            ToolResult inheritResult = tool.execute(Map.of("instance", "Modded", "graphicsBackend", "inherit"));
            assertTrue(inheritResult.isSuccess(), "expected success: " + inheritResult.getError());

            assertEquals(GraphicsAPI.DEFAULT, awaitEffectiveBackend(fx, "Modded", GraphicsAPI.DEFAULT));
            GameSettings.Instance settingsAfterInherit = fx.repository().getInstanceGameSettings("Modded");
            assertTrue(settingsAfterInherit == null
                            || !settingsAfterInherit.getOverrideProperties().contains(GameSettings.PROPERTY_GRAPHICS_BACKEND),
                    "'inherit' must remove the per-instance override, mirroring the native inherit button");
        }
    }

    /// Polls the effective graphics-backend value for up to a few seconds, returning as soon as
    /// it matches {@code expected} (or the last-observed value on timeout, so the caller's
    /// assertion still fails with a useful diff instead of this helper swallowing a genuine
    /// mismatch).
    private static GraphicsAPI awaitEffectiveBackend(ProfileFixture fx, String instance, GraphicsAPI expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        GraphicsAPI last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance).getInheritable(GameSettings::graphicsBackendProperty);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }
}
