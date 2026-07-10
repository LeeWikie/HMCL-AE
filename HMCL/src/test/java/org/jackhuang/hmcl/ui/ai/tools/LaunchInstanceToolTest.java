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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers the wave-3 group ④ changes to {@link LaunchInstanceTool}:
///   - T4: a missing named instance fails with the shared resolveInstance envelope (candidate
///     list), BEFORE any launch is dispatched, so no game process is started;
///   - T17: [LaunchInstanceTool#baseLaunchReceipt] tells the model the expired-login native
///     dialog is invisible to the AI, and (only when the instance is already running) that a
///     second copy may start. The receipt is unit-tested directly so the wording can be checked
///     without actually launching Minecraft.
public final class LaunchInstanceToolTest {

    private final LaunchInstanceTool tool = new LaunchInstanceTool(() -> 5);

    @Test
    void missingInstanceFailsWithCandidateEnvelopeAndNoLaunch() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess(), "a missing instance must not launch anything");
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "should list the real instance names: " + result.getError());
        }
    }

    @Test
    void baseReceiptWarnsAboutInvisibleExpiredLoginDialog() {
        String receipt = LaunchInstanceTool.baseLaunchReceipt("MyInstance", false);

        assertTrue(receipt.contains("MyInstance"), receipt);
        assertTrue(receipt.contains("EXPIRED"), "must warn the expired-login dialog exists: " + receipt);
        assertTrue(receipt.contains("native account/login dialog"),
                "must name the native dialog the AI cannot complete: " + receipt);
        assertTrue(receipt.contains("cannot see or act on it"),
                "must state the dialog is invisible to the AI: " + receipt);
        assertFalse(receipt.contains("already tracking"),
                "an idle instance must not get the already-running note: " + receipt);
    }

    @Test
    void baseReceiptAddsAlreadyRunningNoteWhenInstanceIsRunning() {
        String receipt = LaunchInstanceTool.baseLaunchReceipt("MyInstance", true);

        assertTrue(receipt.contains("already tracking a running game process"),
                "a running instance must get the second-copy advisory: " + receipt);
    }
}
