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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/// A dangerous-write tool that launches a Minecraft instance.
///
/// This reuses HMCL's launcher entry point directly:
/// - [`Profiles#getSelectedProfile()`] for the active profile,
/// - [`Profiles#getSelectedInstance()`] as the default instance,
/// - [`Versions#launch(Profile, String, java.util.function.Consumer[])`] for the
///   actual launch (which builds a `LauncherHelper`, prompts for an account if
///   required, and runs the launch task asynchronously).
///
/// Because [`Versions#launch`] must run on the JavaFX Application Thread (it
/// shows dialogs and a progress pane), this tool dispatches the launch via
/// [`Platform#runLater`] and returns immediately, reporting that the launch was
/// started. It does not wait for the game to finish loading.
///
/// Before dispatching the launch, it consumes any {@link WorldBackupManager}
/// pending-first-launch markers for this instance (see
/// {@link WorldBackupManager#consumePendingFirstLaunchBackups}) — this is the other half of
/// the safety net {@link ImportWorldTool} sets up for a freshly-imported world, so an
/// old/incompatible save gets one automatic snapshot before Minecraft ever touches it.
///
/// Permission level: DANGEROUS_WRITE. It starts an external process (the game).
@NotNullByDefault
public final class LaunchInstanceTool implements Tool {

    private final IntSupplier worldBackupMaxMb;

    /// @param worldBackupMaxMb supplies the per-world cap on the total snapshot size, in MB
    ///                         (from AI settings), applied to the automatic pre-launch safety
    ///                         backup of any freshly-imported world (see {@link WorldBackupManager}).
    public LaunchInstanceTool(IntSupplier worldBackupMaxMb) {
        this.worldBackupMaxMb = worldBackupMaxMb;
    }

    @Override
    public String getName() {
        return "launch_instance";
    }

    @Override
    public String getDescription() {
        return "Launches a Minecraft instance of the selected profile. "
                + "Parameters: instance (string, optional: the instance/version id to launch; "
                + "defaults to the currently selected instance); "
                + "testMode (boolean, optional, default false): when true, launches in TEST mode — the "
                + "launcher window is kept open and the game log window is shown so an early crash or "
                + "immediate exit is visible, WITHOUT changing the instance's saved launcher-visibility "
                + "or show-logs settings (a one-time override for this launch only). Use it to diagnose a "
                + "launch that might fail (e.g. after creating an instance or changing mods/loaders). "
                + "Dispatches the launch on the UI thread (an account or download prompt may appear) "
                + "and returns immediately once launching has started. This starts the game process. "
                + "If any world in this instance was imported since its last launch, an automatic safety "
                + "backup of that world is taken first (see worlds_import).";
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

        // Shared resolveInstance range (T4): accepts the 'instance'/'version'/query alias keys,
        // defaults to the currently selected instance, and a named-but-missing one fails with the
        // unified envelope listing the real instance names.
        InstanceToolSupport.ResolvedInstance resolved =
                InstanceToolSupport.resolveInstance(repository, parameters, true);
        if (resolved.failure() != null) {
            return resolved.failure();
        }
        final String id = resolved.name();

        // T17: whether HMCL already tracks a live game process for this instance. Not a refusal —
        // launching is still allowed — but the receipt tells the model (and via it the user) that a
        // second copy may start or the launch may be ignored, which was previously silent.
        boolean alreadyRunning = GameResourceGuard.checkInstanceNotRunning(id) != null;

        // Safety net: back up any world imported into this instance since its last launch,
        // BEFORE Minecraft gets a chance to touch (and potentially corrupt) it. Best-effort —
        // a failed safety backup must never block the user from playing.
        WorldBackupManager.PendingBackupResult pendingBackups =
                WorldBackupManager.consumePendingFirstLaunchBackups(id, worldBackupMaxMb.getAsInt());

        // Safety net 2: an interrupted restore_world_backup (crash/kill between its two directory
        // renames) can leave a world missing from saves/ with its data stranded in hidden
        // .{world}.replaced / .{world}.restoring folders. Surface any such leftovers before the
        // user plays, instead of letting the world silently look "vanished". Best-effort scan.
        List<WorldBackupManager.InterruptedRestoreLeftover> restoreLeftovers =
                WorldBackupManager.scanInterruptedRestores(id);

        // testMode reuses HMCL's native "test game" entry point verbatim: Versions.testGame ==
        // launch(profile, id, LauncherHelper::setTestMode), which forces launcherVisibility=KEEP and
        // showLogs=true for THIS launch only (a transient override on the LauncherHelper — it never
        // touches the persisted per-instance game settings). Everything else about the launch (the
        // pre-launch safety-net backups above, account/download prompts, async dispatch) is identical.
        final boolean testMode = parseBoolean(parameters.get("testMode"));

        try {
            if (testMode) {
                Platform.runLater(() -> Versions.testGame(profile, id));
            } else {
                Platform.runLater(() -> Versions.launch(profile, id));
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to dispatch launch for '" + id + "': " + e.getMessage());
        }

        StringBuilder message = new StringBuilder(baseLaunchReceipt(id, alreadyRunning));
        if (testMode) {
            message.append("\nTEST MODE: launched in test mode — the launcher window is kept open and the "
                    + "game log window is shown so an early crash or immediate exit is visible. This is a "
                    + "one-time override for this launch and does NOT change the instance's saved "
                    + "launcher-visibility or show-logs settings. Watch the log window (or latest.log) for the "
                    + "outcome before reporting whether the game actually started.");
        }
        if (!pendingBackups.backedUpWorlds().isEmpty()) {
            message.append("\nAutomatic pre-launch safety backup taken for freshly-imported world(s): ")
                    .append(String.join(", ", pendingBackups.backedUpWorlds())).append('.');
        }
        if (!pendingBackups.failedWorlds().isEmpty()) {
            message.append("\nWARNING: could not take the automatic safety backup for world(s): ")
                    .append(String.join(", ", pendingBackups.failedWorlds()))
                    .append(" — consider running instance(action=\"worlds_backup_create\") for them manually.");
        }
        if (!restoreLeftovers.isEmpty()) {
            message.append("\nWARNING: found leftover data from an interrupted world-backup restore under saves/:");
            for (WorldBackupManager.InterruptedRestoreLeftover leftover : restoreLeftovers) {
                message.append("\n  - ").append(leftover.path().getFileName())
                        .append(" (world '").append(leftover.world()).append("', ")
                        .append(describeLeftoverKind(leftover.kind())).append(')');
            }
            message.append("\nInform the user: if a world is missing or looks wrong, its pre-restore data is "
                    + "preserved in the hidden '.<world>.replaced' folder — renaming it back to '<world>' recovers "
                    + "it. Once the user confirms their worlds are fine, these leftover files/folders can be deleted.");
        }
        return ToolResult.success(message.toString());
    }

    /// The base launch receipt (before any freshly-imported-world backup / interrupted-restore
    /// notes are appended): the dispatch note plus the T17 advisories — the native login/account
    /// dialog that appears when the token has expired is invisible to the AI and only the user can
    /// complete it, and (when {@code alreadyRunning}) HMCL already tracks a live process so a second
    /// copy may start. Package-visible and pure so the wording can be unit-tested without actually
    /// dispatching a launch.
    static String baseLaunchReceipt(String id, boolean alreadyRunning) {
        StringBuilder message = new StringBuilder("Launching instance '").append(id).append("'. ")
                .append("The launch is dispatched on the launcher UI thread and this returns immediately "
                        + "without waiting for the game to load. ")
                .append("If the account's login/token has EXPIRED, a native account/login dialog will pop "
                        + "up that only the USER can complete manually — you cannot see or act on it, and "
                        + "the game will not start until they do; a download prompt may also appear. ")
                .append("Check the game window or logs for progress, and do not claim the game launched "
                        + "successfully beyond 'the launch was dispatched'.");
        if (alreadyRunning) {
            message.append("\nNote: HMCL is already tracking a running game process for this instance; "
                    + "launching again may start a second copy or be ignored — confirm with the user if "
                    + "that was not intended.");
        }
        return message.toString();
    }

    /// Lenient boolean parse for the optional {@code testMode} flag, matching the convention of the
    /// sibling write tools (e.g. {@link SetInstanceIsolationTool}): a real {@code Boolean} passes
    /// through, the string {@code "true"} (case-insensitive, trimmed) is true, and anything else —
    /// including {@code null}/absent — is false, so an omitted flag simply means a normal launch.
    private static boolean parseBoolean(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    private static String describeLeftoverKind(String kind) {
        return switch (kind) {
            case "replaced" -> "the world's complete pre-restore data, set aside by the restore";
            case "restoring" -> "a staged snapshot copy that was never swapped in";
            case "restore-in-progress" -> "a marker showing the restore died mid-swap";
            default -> kind;
        };
    }
}
