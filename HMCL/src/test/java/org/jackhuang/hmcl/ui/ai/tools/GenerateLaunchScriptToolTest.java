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
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [GenerateLaunchScriptTool]'s deterministic pre-flight contract over a real
/// [ProfileFixture]-backed instance: the metadata/description contract, the shared
/// [InstanceToolSupport#resolveInstance] "unknown instance" guard, the "never overwrite an existing
/// script file" guard, and the "no account selected" guard — this tool's dedicated substitute for
/// the native `Versions.generateLaunchScript`'s interactive `ensureSelectedAccount` sign-in dialog,
/// which a tool call cannot drive.
///
/// The actual generation handoff — `Platform.runLater(() -> new LauncherHelper(...).makeLaunchScript(...))`
/// — is intentionally NOT exercised here. It dispatches onto the JavaFX Application Thread, resolves
/// Java, may download missing game files exactly like a real launch, and reports its own outcome
/// through native success/failure dialogs with no awaitable future to hook (see the tool's class
/// doc). Exercising it would need a real selected account, a running JavaFX toolkit, and possibly
/// network access for missing game files/Java — none of which this test suite fakes. That path is
/// left to the manual test checklist; this class only pins the SYNCHRONOUS checks the tool performs
/// BEFORE that dispatch, every one of which returns a `ToolResult` without ever touching the FX
/// thread or the filesystem beyond a directory-create/existence-check.
public final class GenerateLaunchScriptToolTest {

    private final GenerateLaunchScriptTool tool = new GenerateLaunchScriptTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("generate_launch_script", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains(".bat"), "must document the Windows .bat extension: " + description);
        assertTrue(description.contains(".sh"), "must document the Linux .sh extension: " + description);
        assertTrue(description.contains(".command"), "must document the macOS .command extension: " + description);
        assertTrue(description.contains("account"), "must document the selected-account precondition: " + description);
    }

    @Test
    void missingInstanceFailsWithCandidateEnvelopeBeforeAnyPathWork() throws Exception {
        // 'instance' resolution (the shared InstanceToolSupport.resolveInstance guard) runs before
        // any output-path work or account check, so a named-but-unknown instance must fail here
        // without ever touching the filesystem or Accounts.
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess(), "an unknown instance must not proceed to path/account checks");
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "should list the real instance names: " + result.getError());
        }
    }

    @Test
    void neverOverwritesAnExistingScriptFile() throws Exception {
        // The existing-file check runs BEFORE the account check (mirrors ExportModpackTool /
        // worlds_export), so this branch is reachable and fully deterministic with no account
        // selected at all.
        try (ProfileFixture fx = new ProfileFixture()) {
            String id = fx.createInstance("ScriptExists");
            Path runDir = fx.repository().getRunDirectory(id);
            Files.createDirectories(runDir);
            // Same default-path computation the tool performs when 'target' is omitted:
            // '<runDir>/<instance>.<platformDefaultExtension>'.
            Path existing = runDir.resolve(id + "." + defaultScriptExtensionForCurrentOs());
            Files.writeString(existing, "placeholder script content");

            ToolResult result = tool.execute(Map.of("instance", id));

            assertFalse(result.isSuccess(),
                    "an existing target file must never be overwritten, got: " + result.getOutput());
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
            assertEquals("placeholder script content", Files.readString(existing),
                    "the pre-existing file's content must be left untouched");
        }
    }

    @Test
    void failsWithoutASelectedAccountAndNeverDispatchesGeneration() throws Exception {
        // AccountsFixture (this package) has no "clear the selection" helper, so this inlines the
        // exact same save/restore pattern its own constructor/close() use around the shared,
        // process-wide Accounts.selectedAccount — safe here because Accounts.init() is never called
        // (see AccountsFixture's class doc), so plain get/set carries no listener side effects.
        Account previousAccount = Accounts.getSelectedAccount();
        Accounts.setSelectedAccount(null);
        try (ProfileFixture fx = new ProfileFixture()) {
            String id = fx.createInstance("NoAccountYet");
            Path runDir = fx.repository().getRunDirectory(id);
            Path expectedOutput = runDir.resolve(id + "." + defaultScriptExtensionForCurrentOs());

            ToolResult result = tool.execute(Map.of("instance", id));

            assertFalse(result.isSuccess(),
                    "generation must be refused without a selected account, got: " + result.getOutput());
            assertTrue(result.getError().contains("account"),
                    "the failure must name the missing account precondition: " + result.getError());
            assertFalse(Files.exists(expectedOutput),
                    "no script file may be created when generation is refused before dispatch");
        } finally {
            Accounts.setSelectedAccount(previousAccount);
        }
    }

    /// Mirrors `GenerateLaunchScriptTool.defaultScriptExtension()` (private) so these tests stay
    /// correct on whichever OS they run on, without reaching into the tool's internals.
    private static String defaultScriptExtensionForCurrentOs() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "bat";
            case MACOS -> "command";
            default -> "sh";
        };
    }
}
