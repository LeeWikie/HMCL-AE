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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolParams;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// A controlled-write tool that updates a single installed mod to its newest
/// compatible version and archives the old jar, so two active copies of the same
/// mod can never coexist (the classic "duplicate mods" launch crash).
///
/// This reuses HMCL's native pipeline end to end — no faked matching:
/// - [`HMCLGameRepository#getModManager(String)`] + [`ModManager#getLocalFiles()`]
///   to locate and parse the installed mods,
/// - [`LocalModFile#checkUpdates`] (the same call behind HMCL's "check for updates"
///   button) to hash the local jar, match it to a remote project and find a strictly
///   newer version for the same game version and mod loader,
/// - [`FileDownloadTask`] (with the active [`DownloadProvider`]) to download the new
///   file into the same folder as the old one,
/// - [`LocalModFile#setOld(boolean)`] to archive the superseded old jar with the
///   `.old` suffix — the same convention HMCL's native mod page uses, so its
///   "roll back to previous version" action keeps working after an AI-driven update.
///
/// The mod is resolved by a case-insensitive substring over its file name, display
/// name or mod id (like {@link GetModInfoTool}); the tool refuses to act unless
/// exactly one mod matches.
///
/// Ordering guarantees no active duplicate is left behind: the new file is downloaded
/// and verified first, then the old jar is archived (`*.jar.old` is ignored by the
/// game loaders). When the new version keeps the exact same file name, the old jar is
/// archived BEFORE the download to free the name, and restored if the download fails.
/// NOTE: the game MAY be running while this tool executes (the user can launch it at
/// any time, including outside HMCL) — when the old jar cannot be renamed, the failure
/// is attributed via [GameResourceGuard#checkInstanceNotRunning(String)] and reported
/// truthfully instead of assuming a quiescent file system.
///
/// Permission level: {@link ToolPermission#CONTROLLED_WRITE}. It downloads one file
/// and archives the single old jar it replaces.
@NotNullByDefault
public final class UpdateModTool implements ToolSpec {

    /// Per-source network timeout for the update check, in seconds.
    private static final int CHECK_TIMEOUT_SECONDS = 30;

    /// Maximum time to wait for the download to finish, in seconds.
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 180;

    /// Historical: the superseded old jar used to be trashed/deleted according to this
    /// preference. It is now archived with the `.old` suffix instead (recoverable through the
    /// native mod page's rollback), so the preference no longer applies here; the field is kept
    /// only so the constructor signature — and its call sites — stay stable.
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final java.util.function.BooleanSupplier toRecycleBin;

    /// @param toRecycleBin historical recycle-bin preference; no longer consulted (the old jar
    ///                     is archived as `.old` for native rollback instead of being deleted).
    public UpdateModTool(java.util.function.BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "update_mod";
    }

    @Override
    public ToolPermission getPermission() {
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
                   "mod": {"type": "string", "description": "A case-insensitive substring of the mod's file name, display name or mod id that matches exactly one installed mod (e.g. \\"sodium\\"). Provide this, or 'mods', or 'all'."},
                   "mods": {"type": "array", "items": {"type": "string"}, "description": "Batch: several mod substrings to update in one call; each must match exactly one installed mod. An unmatched/ambiguous entry is reported but does not abort the rest."},
                   "all": {"type": "boolean", "description": "Batch: when true, update every installed mod that has a newer compatible version. Can be slow with many mods; consider check_mod_updates first."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."},
                   "source": {"type": "string", "enum": ["modrinth", "curseforge"], "description": "Optional: restrict the update lookup to a single source. By default both Modrinth and CurseForge are checked and the newest match wins."}
                 },
                 "required": []
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Updates installed mod(s) to the newest compatible version and removes the old jar, so two "
                + "versions of the same mod never coexist (which would crash the game on launch). "
                + "Parameters: mod (a case-insensitive substring of the mod's file name, display name or mod id "
                + "that matches exactly one installed mod), OR mods (an array of such substrings, each matched to one "
                + "installed mod), OR all=true (update every installed mod that has an update — can be slow, run "
                + "check_mod_updates first), "
                + "instance (optional, the instance/version id; defaults to the selected instance), "
                + "source (optional, \"modrinth\" or \"curseforge\"; by default both are checked and the newest wins). "
                + "It hashes the local jar to match it to a remote project (same as the 'check for updates' button), "
                + "downloads the newer file, then archives the old jar with the '.old' suffix (the native mod list "
                + "can roll back to it). Batch calls update each mod independently and report a per-mod summary. "
                + "Use check_mod_updates first to see what can be updated. Network calls are time-bounded.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Batch dispatch: `all=true` updates every installed mod; a `mods` array updates each
        // listed mod. Both reuse the exact single-mod update core (updateResolvedMod) per mod.
        // With neither present the original single-mod path runs unchanged.
        boolean updateAll = parseBooleanFlag(parameters.get("all"));
        List<String> modQueries = extractModQueries(parameters);
        if (updateAll || modQueries != null) {
            return executeBatch(parameters, updateAll, modQueries);
        }
        return executeSingle(parameters);
    }

    /// The original single-mod path, behaviourally unchanged: resolve exactly one installed mod by
    /// substring and update it (or report it already current / ambiguous / missing).
    private ToolResult executeSingle(Map<String, Object> parameters) {
        String query = ToolParams.string(parameters, "mod", "name", "id", "modId", "query");
        if (query.isEmpty()) {
            return ToolResult.failure("Missing required parameter: mod (a substring of the mod file/display name or id).");
        }
        @Nullable RemoteAddon.Source onlySource = parseSource(String.valueOf(parameters.getOrDefault("source", "")).trim());

        ResolveResult resolved = resolve(parameters);
        if (resolved.earlyReturn() != null) {
            return resolved.earlyReturn();
        }

        MatchResult match = matchOne(query, resolved.all(), resolved.target());
        if (match.failure() != null) {
            return match.failure();
        }
        return updateResolvedMod(match.mod(), resolved.target(), resolved.gameVersion(), onlySource);
    }

    /// Updates several mods in one call, reusing {@link #updateResolvedMod} per mod and aggregating
    /// the per-mod receipts. `updateAll` updates every installed mod; otherwise every entry of
    /// `modQueries` is matched to exactly one installed mod (the same matcher the single path uses),
    /// with an unmatched/ambiguous query recorded as unresolved rather than aborting the batch.
    private ToolResult executeBatch(Map<String, Object> parameters, boolean updateAll,
                                    @Nullable List<String> modQueries) {
        @Nullable RemoteAddon.Source onlySource = parseSource(String.valueOf(parameters.getOrDefault("source", "")).trim());

        ResolveResult resolved = resolve(parameters);
        if (resolved.earlyReturn() != null) {
            return resolved.earlyReturn();
        }
        String target = resolved.target();
        String gameVersion = resolved.gameVersion();
        List<LocalModFile> all = resolved.all();

        // Ordered, de-duplicated set of mods to update (dedup by file path so the same mod listed
        // twice, or matched by two queries, is only updated once).
        LinkedHashMap<Path, LocalModFile> byFile = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        if (updateAll) {
            for (LocalModFile mod : all) {
                byFile.putIfAbsent(mod.getFile(), mod);
            }
        } else {
            for (String query : modQueries) {
                MatchResult match = matchOne(query, all, target);
                if (match.failure() != null) {
                    unresolved.add("[UNRESOLVED] \"" + query + "\": " + match.failure().getError());
                } else {
                    byFile.putIfAbsent(match.mod().getFile(), match.mod());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Batch mod update for instance '").append(target).append("' (Minecraft ").append(gameVersion)
                .append("): ").append(updateAll
                        ? "updating all " + all.size() + " installed mod(s)."
                        : modQueries.size() + " mod query/queries.").append('\n');

        int ok = 0;
        int failed = 0;
        for (LocalModFile mod : byFile.values()) {
            ToolResult res = updateResolvedMod(mod, target, gameVersion, onlySource);
            boolean success = res.isSuccess();
            if (success) {
                ok++;
            } else {
                failed++;
            }
            String label = safe(mod.getName()).isBlank() ? mod.getFileName() : mod.getName();
            sb.append('\n').append(success ? "[OK] " : "[FAILED] ").append(label).append('\n');
            String body = success ? res.getOutput() : res.getError();
            sb.append(indentLines(body)).append('\n');
        }
        for (String note : unresolved) {
            sb.append('\n').append(note).append('\n');
        }

        sb.append("\nSummary: ").append(ok).append(" updated or already current / ").append(failed).append(" failed");
        if (!unresolved.isEmpty()) {
            sb.append(" / ").append(unresolved.size()).append(" unresolved");
        }
        sb.append(" of ").append(byFile.size() + unresolved.size()).append(" mod(s).");

        String text = sb.toString().trim();
        // At least one mod updated (or already current) → success; otherwise a failure envelope.
        return ok > 0 ? ToolResult.success(text) : ToolResult.failure(text);
    }

    /// Shared instance resolution + mod listing used by both the single and batch paths. Returns a
    /// populated result, or one whose {@link ResolveResult#earlyReturn()} the caller must return
    /// immediately (a resolution failure, or the "no mods installed" success).
    private static ResolveResult resolve(Map<String, Object> parameters) {
        String target;
        ModManager modManager;
        String gameVersion;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            InstanceToolSupport.ResolvedInstance resolved =
                    InstanceToolSupport.resolveInstance(repo, parameters, false);
            if (resolved.failure() != null) {
                return ResolveResult.early(resolved.failure());
            }
            target = resolved.name();
            modManager = repo.getModManager(target);
            gameVersion = repo.getGameVersion(target).orElse(null);
        } catch (Throwable t) {
            return ResolveResult.early(ToolResult.failure("Could not resolve the instance's mods: " + t.getMessage()));
        }

        if (gameVersion == null || gameVersion.isBlank()) {
            return ResolveResult.early(ToolResult.failure("Could not determine the Minecraft version of instance '" + target
                    + "', which is required to match a compatible update."));
        }

        List<LocalModFile> all;
        try {
            all = new ArrayList<>(modManager.getLocalFiles());
        } catch (Throwable t) {
            return ResolveResult.early(ToolResult.failure("Failed to parse mods in instance '" + target + "': " + t.getMessage()));
        }
        if (all.isEmpty()) {
            return ResolveResult.early(ToolResult.success("Instance '" + target + "' has no mods installed."));
        }
        return ResolveResult.ready(target, gameVersion, all);
    }

    /// Resolves exactly one installed mod by a case-insensitive substring over its file name,
    /// display name or id — the original single-mod matcher, reused verbatim by both paths. Returns
    /// the matched mod, or a failure describing a missing / ambiguous match.
    private static MatchResult matchOne(String query, List<LocalModFile> all, String target) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<LocalModFile> matches = new ArrayList<>();
        for (LocalModFile mod : all) {
            if (safe(mod.getFileName()).toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getName()).toLowerCase(Locale.ROOT).contains(needle)
                    || safe(mod.getId()).toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(mod);
            }
        }
        if (matches.isEmpty()) {
            return MatchResult.miss(ToolResult.failure("No installed mod matches \"" + query + "\" in instance '" + target
                    + "'. Use list_mods to see the available mods."));
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(matches.size()).append(" mods match \"").append(query)
                    .append("\" in instance '").append(target).append("'. Refine 'mod' to one of:\n");
            for (LocalModFile mod : matches) {
                sb.append("  - ").append(mod.getFileName());
                String name = safe(mod.getName());
                if (!name.isBlank() && !name.equalsIgnoreCase(mod.getFileName())) {
                    sb.append("  (").append(name).append(')');
                }
                sb.append('\n');
            }
            return MatchResult.miss(ToolResult.failure(sb.toString().trim()));
        }
        return MatchResult.hit(matches.get(0));
    }

    /// Updates a single, already-resolved mod: the original single-mod update core (find newest
    /// version across sources, download, archive the old jar, format the receipt), unchanged and
    /// now shared by the single and batch paths.
    private ToolResult updateResolvedMod(LocalModFile mod, String target, String gameVersion,
                                         @Nullable RemoteAddon.Source onlySource) {
        // Find the newest available update across the requested source(s).
        DownloadProvider provider = DownloadProviders.getDownloadProvider();
        RemoteAddon.Source[] sources = onlySource != null
                ? new RemoteAddon.Source[]{onlySource}
                : RemoteAddon.Source.values();

        AddonUpdate best = null;
        int failed = 0;
        for (RemoteAddon.Source source : sources) {
            AddonUpdate update;
            try {
                update = ContentToolSupport.callWithTimeout(
                        () -> mod.checkUpdates(provider, gameVersion, source),
                        CHECK_TIMEOUT_SECONDS, "Update check");
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

        if (best == null) {
            String suffix = failed > 0
                    ? " (" + failed + " remote lookup(s) failed or timed out)"
                    : "";
            return ToolResult.success("Mod '" + mod.getFileName() + "' is already up to date, or no newer compatible "
                    + "version could be matched on " + (onlySource != null ? sourceName(onlySource) : "Modrinth/CurseForge")
                    + " for Minecraft " + gameVersion + suffix + ". Nothing was changed.");
        }

        RemoteAddon.Version newVersion = best.targetVersion();
        RemoteAddon.File remoteFile = newVersion.file();
        if (remoteFile == null || remoteFile.url() == null || remoteFile.url().isBlank()
                || remoteFile.filename() == null || remoteFile.filename().isBlank()) {
            return ToolResult.failure("The newer version '" + describe(newVersion)
                    + "' of '" + mod.getFileName() + "' has no downloadable file.");
        }

        Path oldFile = mod.getFile();
        Path destDir = oldFile.getParent();
        if (destDir == null) {
            return ToolResult.failure("Could not resolve the folder of '" + oldFile + "'.");
        }
        Path dest = destDir.resolve(remoteFile.filename());
        boolean sameTarget = dest.equals(oldFile);

        // When the new version keeps the SAME filename, the old jar must vacate the name before
        // the download — archive it as .old up front (nothing else has been changed yet, so a
        // failure here refuses the whole operation), and restore it if the download fails.
        String removalNote = "";
        if (sameTarget) {
            try {
                removalNote = archiveOldJar(mod);
            } catch (Throwable e) {
                return ToggleModTool.fileOperationFailure(target,
                        "Archiving the old jar '" + oldFile.getFileName()
                                + "' (rename to '.old') before downloading the update failed", e);
            }
        }

        // Download the new file (unless it is already present). Downloading first (for a
        // different target name) guarantees we never displace the old jar before the replacement
        // is safely on disk.
        boolean alreadyPresent = !sameTarget && Files.exists(dest);
        if (!alreadyPresent) {
            // Download through the shared helper, which retries with backoff and switches the
            // download source (configured provider → official → BMCLAPI mirror) on failure, and
            // cancels the task on timeout. The factory rebuilds the task for each candidate
            // provider so a retry genuinely switches mirror.
            try {
                ContentToolSupport.runDownloadWithFallback(p -> {
                    FileDownloadTask download = new FileDownloadTask(
                            p.injectURLWithCandidates(remoteFile.url()), dest, remoteFile.getIntegrityCheck());
                    download.setName(newVersion.name());
                    return download;
                }, DOWNLOAD_TIMEOUT_SECONDS, "Download");
            } catch (Exception e) {
                String restoreNote = "";
                if (sameTarget) {
                    try {
                        mod.setOld(false);
                        restoreNote = " The old jar was restored; nothing was changed.";
                    } catch (Throwable r) {
                        restoreNote = " WARNING: the old jar is still archived as '"
                                + mod.getFile().getFileName()
                                + "' — remove its '.old' suffix to restore the mod.";
                    }
                }
                if (ContentToolSupport.isNetworkError(e)) {
                    return ToolResult.failure(ContentToolSupport.networkErrorAdvice(e) + restoreNote);
                }
                return ToolResult.failure("Download failed for the newer version '" + describe(newVersion)
                        + "' of '" + mod.getFileName() + "': " + AbstractContentSearchTool.messageOf(e)
                        + restoreNote);
            }
        }

        // Archive the superseded old jar as .old so no two ACTIVE versions coexist, while the
        // native mod page keeps a rollback target (its "restore previous version" reads exactly
        // this .old convention). For a same-name update this already happened before the download.
        if (!sameTarget) {
            try {
                removalNote = archiveOldJar(mod);
            } catch (Throwable e) {
                boolean running = GameResourceGuard.checkInstanceNotRunning(target) != null;
                String detail = e.getMessage() == null || e.getMessage().isBlank()
                        ? "" : " (" + e.getMessage().trim() + ")";
                return ToolFailures.failure(
                        "Downloaded the new version to '" + dest + "' but FAILED to archive the old jar '"
                                + oldFile.getFileName() + "'" + detail
                                + ". Two versions of the mod are now present, which crashes the game on launch"
                                + (running
                                        ? " — instance '" + target
                                                + "' is currently running and likely holds the old jar open"
                                        : ""),
                        ToolFailures.Retryable.LATER,
                        running ? "the old jar can be moved once the game exits"
                                : "the old jar may be held open by another program",
                        "Quit the game (or close the locking program), then remove or rename the old jar '"
                                + oldFile.getFileName() + "' manually — or retry update_mod");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Updated mod in instance '").append(target).append("' (Minecraft ").append(gameVersion).append("):\n");
        sb.append("  mod     : ").append(safe(mod.getName()).isBlank() ? mod.getFileName() : mod.getName()).append('\n');
        sb.append("  from    : ").append(describe(best.currentVersion())).append('\n');
        sb.append("  to      : ").append(describe(newVersion)).append(" (").append(sourceName(newVersion)).append(")\n");
        sb.append("  new file: ").append(remoteFile.filename());
        if (alreadyPresent && !sameTarget) {
            sb.append(" (already on disk)");
        }
        sb.append('\n');
        sb.append(removalNote).append('\n');
        sb.append("  path    : ").append(dest);
        if (failed > 0) {
            sb.append("\n(Note: ").append(failed).append(" remote lookup(s) failed or timed out and were skipped.)");
        }
        return ToolResult.success(sb.toString());
    }

    /// Archives a superseded mod jar through the native state machine:
    /// [`LocalModFile#setOld(boolean)`] renames it to the `*.jar.old` convention, drops it from
    /// the active list and registers it as a rollback target of its [LocalMod] — so HMCL's native
    /// "roll back to previous version" stays effective after an AI-driven update. Returns the
    /// human-readable receipt line for the success message. Package-private for tests.
    ///
    /// @throws IOException when the rename fails (e.g. the jar is held open by a running game);
    ///                     [`LocalAddonManager#setOld`] propagates the failure instead of
    ///                     swallowing it, so callers can attribute it honestly
    static String archiveOldJar(LocalModFile mod) throws IOException {
        Path before = mod.getFile();
        mod.setOld(true);
        return "  old jar : '" + before.getFileName() + "' archived as '" + mod.getFile().getFileName()
                + "' (kept on disk; the native mod list can roll back to it)";
    }

    /// Parses an optional source filter; returns {@code null} for "both / unspecified".
    @Nullable
    private static RemoteAddon.Source parseSource(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("curse")) {
            return RemoteAddon.Source.CURSEFORGE;
        }
        if (v.startsWith("modr")) {
            return RemoteAddon.Source.MODRINTH;
        }
        return null;
    }

    private static String sourceName(RemoteAddon.Source source) {
        return source == RemoteAddon.Source.CURSEFORGE ? "CurseForge" : "Modrinth";
    }

    @Nullable
    private static String sourceNameOrNull(@Nullable RemoteAddon.Version version) {
        if (version == null || version.self() == null) {
            return null;
        }
        return sourceName(version.self().getType());
    }

    private static String sourceName(RemoteAddon.Version version) {
        String name = sourceNameOrNull(version);
        return name != null ? name : "remote";
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

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    /// Parses the optional `all` batch flag. Accepts a JSON boolean, or the strings
    /// "true"/"1"/"yes"/"all"; anything else (including absent) means "not an update-all".
    private static boolean parseBooleanFlag(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("all");
        }
        return false;
    }

    /// Extracts the optional `mods` batch parameter as an ordered, de-duplicated list of non-blank
    /// mod substrings. Accepts a JSON array (a `List`), a raw `Object[]`, or — leniently — a single
    /// comma-separated string (not whitespace-split, since a mod query may itself contain spaces).
    /// Returns `null` when absent or empty, so the caller falls back to the single-mod path.
    @Nullable
    private static List<String> extractModQueries(Map<String, Object> params) {
        Object raw = params.get("mods");
        if (raw == null) {
            return null;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                addQuery(out, o);
            }
        } else if (raw instanceof Object[] arr) {
            for (Object o : arr) {
                addQuery(out, o);
            }
        } else if (raw instanceof String s) {
            for (String part : s.split(",")) {
                addQuery(out, part);
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private static void addQuery(Set<String> out, @Nullable Object value) {
        if (value != null) {
            String s = value.toString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }

    /// Indents every line of a per-mod receipt by two spaces so it reads as a nested block under
    /// its `[OK]`/`[FAILED]` header in the aggregated batch output.
    private static String indentLines(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(lines[i]);
        }
        return sb.toString();
    }

    /// Outcome of {@link #resolve}: either an {@code earlyReturn} the caller must return as-is (a
    /// resolution failure or the "no mods installed" success), or the resolved target/gameVersion/
    /// mod list.
    private record ResolveResult(@Nullable ToolResult earlyReturn, @Nullable String target,
                                 @Nullable String gameVersion, @Nullable List<LocalModFile> all) {
        static ResolveResult early(ToolResult result) {
            return new ResolveResult(result, null, null, null);
        }

        static ResolveResult ready(String target, String gameVersion, List<LocalModFile> all) {
            return new ResolveResult(null, target, gameVersion, all);
        }
    }

    /// Outcome of {@link #matchOne}: either a {@code failure} (missing / ambiguous match) or the
    /// single matched mod.
    private record MatchResult(@Nullable ToolResult failure, @Nullable LocalModFile mod) {
        static MatchResult miss(ToolResult failure) {
            return new MatchResult(failure, null);
        }

        static MatchResult hit(LocalModFile mod) {
            return new MatchResult(null, mod);
        }
    }
}
