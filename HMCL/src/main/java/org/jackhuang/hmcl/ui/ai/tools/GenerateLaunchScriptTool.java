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

import javafx.application.Platform;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A controlled-write tool that generates a runnable launch script (a `.bat`/`.ps1` on Windows,
/// a `.sh`/`.bash`/`.command` on macOS/Linux) for a Minecraft instance — the exact command HMCL
/// would use to start the game, written to a file the user can run outside the launcher.
///
/// This reuses HMCL's native "generate launch script" pipeline directly. The native context-menu
/// entry point ([`org.jackhuang.hmcl.ui.versions.Versions#generateLaunchScript`]) opens a
/// [`javafx.stage.FileChooser`] to pick the output file, which cannot be driven from a tool call,
/// so this tool replicates only the NON-UI orchestration around it — the same three preconditions
/// `Versions.generateLaunchScript` establishes before it hands off:
/// 1. version readiness (its `checkVersionForLaunching`) — resolved here via
///    [`HMCLGameRepository#isLoaded`] + [`InstanceToolSupport#resolveInstance`];
/// 2. account readiness (its `ensureSelectedAccount`) — a launch script bakes in the selected
///    account's launch/auth context, so an account must already be selected; because a tool cannot
///    drive the interactive `CreateAccountPane` dialog and await it, this fails with a clear message
///    instead of popping a sign-in dialog;
/// 3. the output-path/extension normalization (its `isValidScriptExtension` /
///    `getDefaultScriptExtension`) — copied verbatim so an explicit valid extension is honored and
///    anything else gets the platform default appended, exactly as the native code does.
///
/// It then hands off to the SAME leaf call the native code uses —
/// `new LauncherHelper(profile, account, id).makeLaunchScript(file)` — so the actual script is
/// produced by HMCL's real launch pipeline ([`LauncherHelper#makeLaunchScript`] runs the full
/// `launch0()`: Java resolution, missing-game-file completion, login, `LaunchOptions` build, then
/// [`org.jackhuang.hmcl.launch.DefaultLauncher#makeLaunchScript`] writes the file). Nothing about
/// the script content is reimplemented here.
///
/// ### Threading / async contract
///
/// `LauncherHelper.makeLaunchScript` calls `FXUtils.checkFxUserThread()` and constructs a JavaFX
/// progress dialog, so both the `LauncherHelper` construction and the call must happen on the
/// JavaFX Application Thread; this dispatches them via [`Platform#runLater`]. Like the sibling
/// {@link LaunchInstanceTool}, it returns as soon as generation is DISPATCHED and does not wait:
/// the native pipeline signals its own outcome through a progress dialog and a success/failure
/// dialog ({@code version.launch_script.success} / {@code version.launch_script.failed}), and there
/// is no awaitable future to hook — reconstructing one would mean duplicating the whole pipeline,
/// the fragile path this deliberately avoids. The receipt therefore tells the model not to claim
/// the script was written beyond "generation was dispatched".
///
/// To keep a clean synchronous error surface, the target path is validated and normalized, its
/// parent directory created, and the account checked BEFORE dispatch; only the UI-bound handoff is
/// deferred. An existing file at the target is never overwritten (mirroring the sibling
/// {@link ExportModpackTool} / {@code worlds_export}).
///
/// Permission level: CONTROLLED_WRITE. It writes a single new script file (and may download any
/// missing game files first, exactly like a real launch minus starting the game); it never modifies
/// the instance itself.
@NotNullByDefault
public final class GenerateLaunchScriptTool implements Tool {

    @Override
    public String getName() {
        return "generate_launch_script";
    }

    @Override
    public String getDescription() {
        return "Generates a runnable launch script for a Minecraft instance of the selected profile — the exact "
                + "command HMCL would use to start the game, written to a file that can be run outside the launcher "
                + "(.bat on Windows, .sh on Linux, .command on macOS). "
                + "Parameters: target (optional string, the output script path; absolute, or relative to the "
                + "instance's game directory; a bare directory, or a name without a recognized script extension, is "
                + "auto-completed — defaults to '<gameDir>/<instance>.<ext>'). An explicit .bat/.ps1 (Windows) or "
                + ".sh/.bash/.command/.ps1 (macOS/Linux) extension is honored; otherwise the platform default "
                + "extension is appended. "
                + "instance (optional string, the instance/version id; defaults to the currently selected instance). "
                + "Requires an account to already be selected: the script bakes in the selected account's launch/auth "
                + "context, and this tool cannot complete an interactive sign-in. "
                + "WRITES a new script file (never overwrites an existing one) and may download any missing game files "
                + "first, exactly like a real launch minus starting the game. "
                + "The generation runs on the launcher UI thread (a native progress dialog appears) and reports its "
                + "own success/failure dialog; this returns as soon as generation is dispatched, so do not claim the "
                + "script was written beyond 'generation was started' until the user confirms.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }

        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        // Generic aliases are NOT accepted for instance resolution: this tool also takes a 'target'
        // path, so the generic 'query' fallback must not be misread as the instance id (mirrors
        // ExportModpackTool, which likewise has a 'target').
        InstanceToolSupport.ResolvedInstance resolved =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (resolved.failure() != null) {
            return resolved.failure();
        }
        final String id = resolved.name();

        // Resolve and normalize the output path (native Versions.generateLaunchScript's
        // FileChooser + isValidScriptExtension/getDefaultScriptExtension logic, replicated).
        final String defaultExt = defaultScriptExtension();
        final Path output;
        try {
            Path runDir = repository.getRunDirectory(id);
            String targetParam = InstanceToolSupport.string(parameters, "target");

            Path candidate;
            if (targetParam == null) {
                // No target given: default into the instance's game directory (the same directory the
                // native FileChooser opens in) as '<instance>.<ext>', mirroring ExportModpackTool's
                // '<instance>.mrpack' / worlds_export's '<world>.zip' defaults.
                candidate = runDir.resolve(sanitize(id) + "." + defaultExt);
            } else {
                Path p = Path.of(targetParam);
                // A relative target is resolved against the instance game dir (as documented), matching
                // the native FileChooser's initial directory.
                candidate = p.isAbsolute() ? p : runDir.resolve(p);
            }
            candidate = candidate.toAbsolutePath().normalize();

            // A directory target (existing) gets the default filename appended.
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve(sanitize(id) + "." + defaultExt);
            }

            Path fileName = candidate.getFileName();
            if (fileName == null) {
                return ToolResult.failure("The 'target' path '" + candidate + "' has no file name; "
                        + "give a full file path such as '" + runDir.resolve(sanitize(id) + "." + defaultExt) + "'.");
            }

            // Honor a valid explicit extension; otherwise append the platform default (native appends
            // rather than replaces, e.g. 'script.txt' -> 'script.txt.bat').
            String ext = FileUtils.getExtension(candidate);
            if (!isValidScriptExtension(ext)) {
                candidate = candidate.resolveSibling(fileName + "." + defaultExt);
            }
            output = candidate;
        } catch (Throwable e) {
            return ToolResult.failure("Could not prepare the output script path: " + e.getMessage());
        }

        // Never overwrite an existing file (consistent with ExportModpackTool / worlds_export; the
        // native FileChooser gets an explicit OS-level overwrite confirmation this tool cannot show).
        if (Files.exists(output)) {
            return ToolResult.failure("A file already exists at the target path: " + output
                    + ". Pass a different 'target' to avoid overwriting it.");
        }

        // Create the parent directory up front for a clean pre-flight error (the underlying
        // DefaultLauncher.makeLaunchScript also creates it, but only later on the async pipeline
        // where a failure would surface merely as a native error dialog).
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Throwable e) {
            return ToolResult.failure("Could not create the target directory for '" + output + "': " + e.getMessage());
        }

        // Account readiness (native ensureSelectedAccount). A launch script embeds the selected
        // account's launch/auth context; without a selected account there is nothing to bake in, and
        // this tool cannot drive the interactive sign-in dialog the native flow would pop.
        Account account = Accounts.getSelectedAccount();
        if (account == null) {
            return ToolResult.failure("No account is currently selected, but a launch script needs one to bake in its "
                    + "launch/auth context. Sign in or select an account first (see the accounts tool), then retry.");
        }

        // Hand off to the SAME native leaf call as Versions.generateLaunchScript. Both the
        // LauncherHelper construction (it builds a JavaFX progress dialog) and makeLaunchScript
        // (FXUtils.checkFxUserThread) must run on the JavaFX Application Thread, so dispatch there and
        // return immediately — the pipeline reports its own success/failure via native dialogs.
        final Profile fProfile = profile;
        try {
            Platform.runLater(() -> {
                try {
                    new LauncherHelper(fProfile, account, id).makeLaunchScript(output);
                } catch (Throwable t) {
                    LOG.warning("Failed to start launch-script generation for instance '" + id + "'", t);
                }
            });
        } catch (IllegalStateException e) {
            // JavaFX runtime not started (should not happen inside the running launcher UI).
            return ToolResult.failure("Cannot generate the launch script: the JavaFX runtime is unavailable.");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to dispatch launch-script generation for '" + id + "': " + e.getMessage());
        }

        return ToolResult.success("Generating a launch script for instance '" + id + "':\n  " + output + "\n"
                + "The generation is dispatched on the launcher UI thread (a native progress dialog appears) and this "
                + "returns immediately without waiting for it to finish. It resolves Java, completes any missing game "
                + "files (a download may run), and bakes in the currently selected account. "
                + "On success the script is written to the path above and HMCL shows a success dialog; on failure it "
                + "shows an error dialog. If the account's login/token has EXPIRED, a native account/login dialog only "
                + "the USER can complete may pop up first. "
                + "Do not claim the script was generated beyond 'generation was dispatched' until the user confirms.");
    }

    /// Whether {@code ext} is a script extension valid for the current OS — copied verbatim from
    /// {@code Versions.isValidScriptExtension} so this tool accepts exactly what the native flow does.
    private static boolean isValidScriptExtension(String ext) {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return ext.equalsIgnoreCase("bat") || ext.equalsIgnoreCase("ps1");
        }
        return ext.equalsIgnoreCase("sh") || ext.equalsIgnoreCase("bash")
                || ext.equalsIgnoreCase("command") || ext.equalsIgnoreCase("ps1");
    }

    /// The platform default script extension — copied verbatim from
    /// {@code Versions.getDefaultScriptExtension}.
    private static String defaultScriptExtension() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "bat";
            case MACOS -> "command";
            default -> "sh";
        };
    }

    /// Makes an instance id safe to use as a file name for the default output path (mirrors
    /// {@link ExportModpackTool}'s sanitize).
    private static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "launch" : s;
    }
}
