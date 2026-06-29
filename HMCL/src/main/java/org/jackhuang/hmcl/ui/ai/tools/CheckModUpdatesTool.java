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

import org.jackhuang.hmcl.addon.LocalAddonFile.AddonUpdate;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Read-only tool that checks whether the mods installed in an instance have a
/// newer version available on Modrinth / CurseForge.
///
/// This reuses HMCL's native update-check pipeline directly: each
/// [`LocalModFile#checkUpdates`] computes the file's hash, asks the
/// [`RemoteAddonRepository`] which remote version matches that hash
/// (`getRemoteVersionByLocalFile`), then looks for a strictly newer version that
/// targets the same game version and mod loader. This is exactly what HMCL's
/// "check for updates" button does — no faked matching.
///
/// Network calls are bounded with [`ContentToolSupport#callWithTimeout`] per mod,
/// and the number of mods checked is capped so the tool always returns promptly.
///
/// Permission level: READ_ONLY. It only reports; it never downloads or replaces a
/// mod. Use `install_mod` (or search_mods) to actually update.
@NotNullByDefault
public final class CheckModUpdatesTool implements Tool {

    /// Default and hard cap on how many mods to query, to keep total runtime bounded.
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 60;

    /// Per-mod / per-source network timeout, in seconds.
    private static final int PER_CHECK_TIMEOUT_SECONDS = 30;

    @Override
    public String getName() {
        return "check_mod_updates";
    }

    @Override
    public String getDescription() {
        return "Checks the installed mods of an instance for available updates on Modrinth/CurseForge by "
                + "hashing each local jar and matching it to a newer remote version for the same game version and loader. "
                + "Parameters: instance (string, optional: the instance/version id - defaults to the selected instance), "
                + "limit (number, optional: max mods to check, default " + DEFAULT_LIMIT + ", capped at " + MAX_LIMIT + "). "
                + "Returns the list of mods that have a newer version (current -> latest). Read-only: it only reports, it does "
                + "not download or replace anything. Network calls are time-bounded per mod.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = String.valueOf(parameters.getOrDefault("instance", "")).trim();
        int limit = parseLimit(parameters.get("limit"));

        ModManager modManager;
        String target;
        String gameVersion;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            target = instance.isEmpty() ? Profiles.getSelectedInstance() : instance;
            if (target == null || target.isEmpty()) {
                return ToolResult.failure("No instance selected. Use list_instances, or pass instance.");
            }
            if (!repo.hasVersion(target)) {
                return ToolResult.failure("No such instance '" + target + "'. Use list_instances.");
            }
            modManager = repo.getModManager(target);
            gameVersion = repo.getGameVersion(target).orElse(null);
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve the instance: " + t.getMessage());
        }

        if (gameVersion == null || gameVersion.isBlank()) {
            return ToolResult.failure("Could not determine the Minecraft version of instance '" + target
                    + "', which is required to match remote updates.");
        }

        List<LocalModFile> all;
        try {
            all = new ArrayList<>(modManager.getLocalFiles());
        } catch (Throwable t) {
            return ToolResult.failure("Failed to parse mods in instance '" + target + "': " + t.getMessage());
        }

        if (all.isEmpty()) {
            return ToolResult.success("Instance '" + target + "' has no mods installed.");
        }

        DownloadProvider provider = DownloadProviders.getDownloadProvider();
        boolean truncated = all.size() > limit;
        List<LocalModFile> toCheck = truncated ? all.subList(0, limit) : all;

        List<String> updates = new ArrayList<>();
        int checked = 0;
        int failed = 0;
        for (LocalModFile mod : toCheck) {
            checked++;
            AddonUpdate best = null;
            for (RemoteAddon.Source source : RemoteAddon.Source.values()) {
                AddonUpdate update;
                try {
                    update = ContentToolSupport.callWithTimeout(
                            () -> mod.checkUpdates(provider, gameVersion, source),
                            PER_CHECK_TIMEOUT_SECONDS, "Update check");
                } catch (Exception e) {
                    failed++;
                    continue;
                }
                if (update == null) {
                    continue;
                }
                if (best == null || best.targetVersion().datePublished()
                        .isBefore(update.targetVersion().datePublished())) {
                    best = update;
                }
            }
            if (best != null) {
                updates.add("  - " + mod.getFileName()
                        + "\n      current: " + describe(best.currentVersion())
                        + "\n      latest : " + describe(best.targetVersion()));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Update check for instance '").append(target)
                .append("' (Minecraft ").append(gameVersion).append("):\n");
        sb.append("Checked ").append(checked).append(" of ").append(all.size()).append(" mod(s).\n");
        if (updates.isEmpty()) {
            sb.append("All checked mods are up to date (or could not be matched to a remote source).");
        } else {
            sb.append(updates.size()).append(" mod(s) have a newer version available:\n");
            for (String line : updates) {
                sb.append(line).append('\n');
            }
            sb.append("\nThis tool only reports. To update a mod, use install_mod with the newer version, "
                    + "or search_mods to find it.");
        }
        if (failed > 0) {
            sb.append("\n(Note: ").append(failed).append(" remote lookup(s) failed or timed out and were skipped.)");
        }
        if (truncated) {
            sb.append("\n(Note: only the first ").append(limit).append(" of ").append(all.size())
                    .append(" mods were checked. Pass a larger 'limit' to check more.)");
        }
        return ToolResult.success(sb.toString().trim());
    }

    private static String describe(@Nullable RemoteAddon.Version version) {
        if (version == null) {
            return "(unknown)";
        }
        String v = version.version();
        if (v == null || v.isBlank()) {
            v = version.name();
        }
        String name = version.name();
        if (name != null && !name.isBlank() && !name.equals(v)) {
            return v + " (" + name + ")";
        }
        return v == null ? "(unnamed)" : v;
    }

    private static int parseLimit(@Nullable Object value) {
        if (value instanceof Number n) {
            return clamp(n.intValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return clamp(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_LIMIT;
    }

    private static int clamp(int v) {
        if (v <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(v, MAX_LIMIT);
    }
}
