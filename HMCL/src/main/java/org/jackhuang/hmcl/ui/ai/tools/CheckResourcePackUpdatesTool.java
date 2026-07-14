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
import org.jackhuang.hmcl.addon.resourcepack.ResourcePackFile;
import org.jackhuang.hmcl.addon.resourcepack.ResourcePackManager;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Checks whether an instance's installed resource packs have a newer version available on
/// Modrinth / CurseForge and — when {@code apply=true} — downloads those newer versions and
/// replaces the old files.
///
/// This reuses HMCL's native resource-pack update pipeline end to end (no faked matching), the
/// resource-pack twin of {@link CheckModUpdatesTool} / {@link UpdateModTool}:
/// - the check phase calls [`ResourcePackFile#checkUpdates`] per pack across both sources — the
///   exact call behind the native "check for updates" button ({@code
///   ResourcePackListPage.checkUpdates()} → {@code AddonCheckUpdatesTask}): it hashes the local
///   `.zip`, asks the [RemoteAddonRepository] which remote version matches that hash
///   (`getRemoteVersionByLocalFile`), then finds a strictly newer version published for the same
///   Minecraft version;
/// - the apply phase replays the native {@code AddonUpdatesPage.AddonUpdateTask} sequence for each
///   pack: [`ResourcePackFile#setOld(boolean)`] archives the current file to `*.old` (freeing its
///   name), the newer file is downloaded into the same `resourcepacks` folder, and — because a
///   resource pack keeps no rollback copy ([`ResourcePackFile#keepOldFiles()`] is `false`, unlike
///   a mod) — the superseded `*.old` file is then deleted, exactly as the native task does.
///
/// Only `.zip` packs can be hash-matched; an unpacked-folder pack's {@code checkUpdates} always
/// returns {@code null} (native behaviour) and is silently skipped.
///
/// Network calls are bounded with [`ContentToolSupport#callWithTimeout`] per pack (check) and
/// [`ContentToolSupport#runDownloadWithFallback`] per download (apply, with mirror fallback and an
/// integrity check), and the number of packs scanned is capped so the tool always returns promptly.
///
/// Permission level: READ_ONLY when only checking (`apply` unset/false); CONTROLLED_WRITE when
/// `apply=true`, since it then downloads and replaces files (the same class as
/// `resourcepacks_install`). A file operation that cannot take effect because a running game holds
/// the pack open is attributed truthfully via [GameResourceGuard]. Updating the resource packs of
/// a modpack-derived instance can diverge from the modpack author's bundled setup — mirroring the
/// native {@code resourcepack.update_in_modpack.warning} confirmation, an apply on such an instance
/// includes an explicit risk note in its result.
@NotNullByDefault
public final class CheckResourcePackUpdatesTool implements ToolSpec {

    /// Default and hard cap on how many packs to query, to keep total runtime bounded.
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 60;

    /// Per-pack / per-source network timeout for the update check, in seconds.
    private static final int PER_CHECK_TIMEOUT_SECONDS = 30;

    /// Maximum time to wait for a single pack download to finish, in seconds.
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    @Override
    public String getName() {
        return "check_resourcepack_updates";
    }

    @Override
    public ToolPermission getPermission() {
        // Default (check-only) is read-only; the apply-aware overload below elevates apply=true.
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        return parseBooleanFlag(parameters.get("apply"))
                ? ToolPermission.CONTROLLED_WRITE
                : ToolPermission.READ_ONLY;
    }

    @Override
    public ToolPermission getMaxPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "apply": {"type": "boolean", "description": "false (default) = only check and list the resource packs that have a newer version; true = download the newer versions and replace the old files."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."},
                   "limit": {"type": "number", "description": "Optional: max resource packs to check, default 25, capped at 60."}
                 },
                 "required": []
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Checks the installed resource packs of an instance for available updates on Modrinth/CurseForge and, "
                + "when apply=true, downloads the newer versions and replaces the old files. It hashes each local .zip "
                + "pack and matches it to a strictly newer remote version for the same Minecraft version (the exact "
                + "pipeline behind HMCL's resource pack 'check for updates' button). "
                + "Parameters: apply (boolean, optional, default false — false only lists what can be updated, true "
                + "performs the update), instance (optional, the instance/version id; defaults to the selected instance), "
                + "limit (number, optional: max packs to check, default " + DEFAULT_LIMIT + ", capped at " + MAX_LIMIT + "). "
                + "Unpacked-folder resource packs cannot be hash-matched and are skipped. With apply=false it is read-only "
                + "and only reports. With apply=true it archives each old file, downloads the newer one, then removes the "
                + "superseded file (resource packs keep no rollback copy); updating a modpack instance's packs may diverge "
                + "from the modpack's bundled setup (a warning is included). Network calls are time-bounded per pack.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        boolean apply = parseBooleanFlag(parameters.get("apply"));
        int limit = parseLimit(parameters.get("limit"));

        HMCLGameRepository repo;
        String target;
        String gameVersion;
        ResourcePackManager manager;
        try {
            Profile profile = Profiles.getSelectedProfile();
            repo = profile.getRepository();
            if (!repo.isLoaded()) {
                repo.refreshVersions();
            }
            // Shared resolveInstance rules: defaults to the selected instance, and a named-but-missing
            // one fails with the unified envelope listing the real names.
            InstanceToolSupport.ResolvedInstance resolved =
                    InstanceToolSupport.resolveInstance(repo, parameters, false);
            if (resolved.failure() != null) {
                return resolved.failure();
            }
            target = resolved.name();
            gameVersion = repo.getGameVersion(target).orElse(null);
            manager = new ResourcePackManager(repo, target);
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve the instance's resource packs: " + t.getMessage());
        }

        if (gameVersion == null || gameVersion.isBlank()) {
            return ToolResult.failure("Could not determine the Minecraft version of instance '" + target
                    + "', which is required to match remote resource pack updates.");
        }

        List<ResourcePackFile> all;
        try {
            all = new ArrayList<>(manager.getLocalFiles());
        } catch (Throwable t) {
            return ToolResult.failure("Failed to read resource packs in instance '" + target + "': " + t.getMessage());
        }

        if (all.isEmpty()) {
            return ToolResult.success("Instance '" + target + "' has no resource packs installed.");
        }

        DownloadProvider provider = DownloadProviders.getDownloadProvider();
        boolean truncated = all.size() > limit;
        List<ResourcePackFile> toCheck = truncated ? all.subList(0, limit) : all;

        // Check phase (shared by both modes): find the newest available update per pack across sources.
        final String mcVersion = gameVersion;
        List<Pending> pending = new ArrayList<>();
        int checked = 0;
        int failed = 0;
        for (ResourcePackFile pack : toCheck) {
            checked++;
            AddonUpdate best = null;
            for (RemoteAddon.Source source : RemoteAddon.Source.values()) {
                AddonUpdate update;
                try {
                    update = ContentToolSupport.callWithTimeout(
                            () -> pack.checkUpdates(provider, mcVersion, source),
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
                pending.add(new Pending(pack, best));
            }
        }

        return apply
                ? reportApply(repo, manager, target, mcVersion, all.size(), checked, failed, truncated, limit, pending)
                : reportCheckOnly(target, mcVersion, all.size(), checked, failed, truncated, limit, pending);
    }

    /// Read-only report (apply=false): lists the packs that have a newer version, current -> latest.
    private static ToolResult reportCheckOnly(String target, String gameVersion, int total, int checked,
                                              int failed, boolean truncated, int limit, List<Pending> pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource pack update check for instance '").append(target)
                .append("' (Minecraft ").append(gameVersion).append("):\n");
        sb.append("Checked ").append(checked).append(" of ").append(total).append(" resource pack(s).\n");
        if (pending.isEmpty()) {
            sb.append("All checked resource packs are up to date (or could not be matched to a remote source).");
        } else {
            sb.append(pending.size()).append(" resource pack(s) have a newer version available:\n");
            for (Pending p : pending) {
                sb.append("  - ").append(p.pack().getFileNameWithExtension())
                        .append("\n      current: ").append(describe(p.update().currentVersion()))
                        .append("\n      latest : ").append(describe(p.update().targetVersion()))
                        .append(" (").append(sourceName(p.update().targetVersion())).append(")\n");
            }
            sb.append("\nThis tool only reports. Call it again with apply=true to download and replace them.");
        }
        appendNotes(sb, failed, truncated, limit, total);
        return ToolResult.success(sb.toString().trim());
    }

    /// Write report (apply=true): applies each pending update via the native archive -> download ->
    /// delete-old sequence and aggregates the per-pack receipts.
    private ToolResult reportApply(HMCLGameRepository repo, ResourcePackManager manager, String target,
                                   String gameVersion, int total, int checked, int failed, boolean truncated,
                                   int limit, List<Pending> pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource pack updates for instance '").append(target)
                .append("' (Minecraft ").append(gameVersion).append("):\n");

        boolean isModpack;
        try {
            isModpack = repo.isModpack(target);
        } catch (Throwable t) {
            isModpack = false;
        }
        if (isModpack) {
            // Mirrors the native resourcepack.update_in_modpack.warning confirmation (which can't be a
            // dialog here — the permission gate is the user's consent); state the risk and proceed.
            sb.append("WARNING: instance '").append(target).append("' was installed from a modpack. Updating its "
                    + "resource packs replaces files the modpack bundled, which may diverge from the modpack "
                    + "author's intended setup. Proceeding as requested.\n");
        }

        if (pending.isEmpty()) {
            sb.append("All checked resource packs are up to date (or could not be matched to a remote source); "
                    + "nothing to update.");
            appendNotes(sb, failed, truncated, limit, total);
            return ToolResult.success(sb.toString().trim());
        }

        int ok = 0;
        int failedApply = 0;
        for (Pending p : pending) {
            ToolResult res = applyOne(p.pack(), p.update(), manager, target);
            boolean success = res.isSuccess();
            if (success) {
                ok++;
            } else {
                failedApply++;
            }
            sb.append('\n').append(success ? "[OK] " : "[FAILED] ")
                    .append(p.pack().getFileNameWithExtension()).append('\n');
            sb.append(success ? res.getOutput() : indent(res.getError())).append('\n');
        }

        sb.append("\nSummary: ").append(ok).append(" updated / ").append(failedApply)
                .append(" failed of ").append(pending.size()).append(" resource pack(s) with an available update.");
        appendNotes(sb, failed, truncated, limit, total);

        String text = sb.toString().trim();
        // At least one pack updated → success; every apply failed → failure envelope.
        return ok > 0 ? ToolResult.success(text) : ToolResult.failure(text);
    }

    /// Applies a single resource pack update, replaying the native {@code AddonUpdateTask} sequence
    /// for the (always, for resource packs) {@code useRemoteFileName=false} / not-disabled case:
    /// archive the current file to `*.old` (frees its name), download the newer file into the same
    /// folder, then delete the superseded `*.old` file (resource packs keep no rollback copy).
    private static ToolResult applyOne(ResourcePackFile pack, AddonUpdate update,
                                       ResourcePackManager manager, String target) {
        RemoteAddon.Version newVersion = update.targetVersion();
        RemoteAddon.File remoteFile = newVersion.file();
        if (remoteFile == null || remoteFile.url() == null || remoteFile.url().isBlank()
                || remoteFile.filename() == null || remoteFile.filename().isBlank()) {
            return ToolResult.failure("The newer version '" + describe(newVersion)
                    + "' of '" + pack.getFileNameWithExtension() + "' has no downloadable file.");
        }

        Path directory = manager.getDirectory();
        String originalFileName = pack.getFile().getFileName().toString();
        // For resource packs useRemoteFileName is always false (the native ResourcePackFile.checkUpdates
        // keeps the local name); honour the flag generically all the same.
        String fileName = update.useRemoteFileName() ? remoteFile.filename() : originalFileName;
        Path dest = directory.resolve(fileName);

        // 1. Archive the current file to '*.old', freeing its name for the download — exactly what the
        //    native AddonUpdateTask does first. Nothing else has changed yet, so a rename failure here
        //    refuses the whole operation (attributed to a running game when one holds the file open).
        try {
            pack.setOld(true);
        } catch (Throwable e) {
            return ToggleModTool.fileOperationFailure(target,
                    "Archiving the old resource pack '" + originalFileName
                            + "' (rename to '.old') before downloading the update failed", e);
        }

        // 2. Download the newer file. runDownloadWithFallback retries with backoff and switches the
        //    source (configured provider -> official -> BMCLAPI mirror) on failure, cancelling on timeout.
        try {
            ContentToolSupport.runDownloadWithFallback(p -> {
                FileDownloadTask download = new FileDownloadTask(
                        p.injectURLWithCandidates(remoteFile.url()), dest, remoteFile.getIntegrityCheck());
                download.setName(newVersion.name());
                return download;
            }, DOWNLOAD_TIMEOUT_SECONDS, "Download");
        } catch (Exception e) {
            // Restore the archived pack; nothing else was changed.
            String restoreNote;
            try {
                pack.setOld(false);
                restoreNote = " The old file was restored; nothing was changed.";
            } catch (Throwable r) {
                restoreNote = " WARNING: the old file is still archived as '" + pack.getFile().getFileName()
                        + "' — remove its '.old' suffix to restore the pack.";
            }
            if (ContentToolSupport.isNetworkError(e)) {
                return ToolResult.failure(ContentToolSupport.networkErrorAdvice(e) + restoreNote);
            }
            return ToolResult.failure("Download failed for the newer version '" + describe(newVersion)
                    + "' of '" + originalFileName + "': " + AbstractContentSearchTool.messageOf(e) + restoreNote);
        }

        // 3. Delete the superseded '*.old' file — resource packs keep no rollback copy
        //    (keepOldFiles() == false), matching the native AddonUpdateTask. A delete failure does NOT
        //    fail the update (the newer pack is already in place); report it as a non-fatal note.
        String oldNote = "";
        if (!pack.keepOldFiles()) {
            try {
                pack.delete();
            } catch (Throwable e) {
                oldNote = "\n      note   : the superseded file '" + pack.getFile().getFileName()
                        + "' could not be removed and remains on disk";
            }
        }

        return ToolResult.success("      from   : " + describe(update.currentVersion())
                + "\n      to     : " + describe(newVersion) + " (" + sourceName(newVersion) + ")"
                + "\n      file   : " + fileName + oldNote);
    }

    /// Appends the shared trailing notes (failed lookups / truncation) to a report.
    private static void appendNotes(StringBuilder sb, int failed, boolean truncated, int limit, int total) {
        if (failed > 0) {
            sb.append("\n(Note: ").append(failed).append(" remote lookup(s) failed or timed out and were skipped.)");
        }
        if (truncated) {
            sb.append("\n(Note: only the first ").append(limit).append(" of ").append(total)
                    .append(" resource packs were checked. Pass a larger 'limit' to check more.)");
        }
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

    private static String sourceName(@Nullable RemoteAddon.Version version) {
        if (version == null || version.self() == null) {
            return "remote";
        }
        return version.self().getType() == RemoteAddon.Source.CURSEFORGE ? "CurseForge" : "Modrinth";
    }

    /// Indents every line of a per-pack failure by six spaces so it reads as a nested block under
    /// its `[FAILED]` header in the aggregated apply output.
    private static String indent(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("      ").append(lines[i]);
        }
        return sb.toString();
    }

    /// Parses the optional `apply` flag. Accepts a JSON boolean, or the strings "true"/"1"/"yes";
    /// anything else (including absent) means "check only".
    private static boolean parseBooleanFlag(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            return t.equals("true") || t.equals("1") || t.equals("yes");
        }
        return false;
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

    /// A pack with an available update (the newest match found across sources), carried from the
    /// check phase into the apply / report phase.
    private record Pending(ResourcePackFile pack, AddonUpdate update) {
    }
}
