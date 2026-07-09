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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/// Flags a `shell` invocation that duplicates what a DEDICATED tool already does — most notably a
/// model that, after a dedicated tool (e.g. `instance(action="mods_install")`) failed or produced
/// the wrong result, reaches for a raw HTTP client (`Invoke-WebRequest` / `curl` / …) and downloads a
/// mod/resource-pack/etc. jar straight from a mod-hosting CDN into the instance's game directory.
/// {@link org.jackhuang.hmcl.ai.agent.AiPromptBuilder}'s system prompt already states this as a
/// hardcoded GOLDEN RULE ("ALWAYS prefer a dedicated tool over shell") — this class is the runtime
/// check that NOTICES when the model ignored it anyway.
///
/// # This is a nudge, not a gate
///
/// Unlike {@link DangerousCommands} / {@link CriticalOperations}, {@link #overlapReason} is
/// evaluated ONLY to surface an extra line in the confirmation dialog the user already sees before
/// the shell command runs — it never blocks, and it never upgrades the call's {@link ToolPermission}.
/// Shell remains a legitimate last resort (the golden rule says "prefer", not "forbid"); this class
/// exists purely so a human approving the command can see *why* it was flagged before clicking
/// confirm, not to enforce a security boundary.
///
/// That distinction is also why this class deliberately does **not** reuse
/// {@link DangerousCommands#normalizeObfuscation} or {@link DangerousCommands#scanEncodedPayloads}:
/// those exist to stop an adversarial actor from hiding a destructive command from a security check,
/// which is a threat model this class isn't defending against. The model that triggers this class is
/// not hiding anything — it is a well-intentioned agent taking a visible shortcut around a dedicated
/// tool it (probably) just failed to use correctly. Adding obfuscation-defeat machinery here would
/// solve a problem that doesn't exist for this use case while adding cost and complexity that does.
///
/// # Detection shape
///
/// Two independent signals are ANDed together, mirroring {@link CriticalOperations}'s
/// `DELETE_VERB && CRITICAL_PATH` structure:
///   - a download verb (`Invoke-WebRequest`/`iwr`, `Invoke-RestMethod`/`irm`, `curl`, `wget`,
///     `Start-BitsTransfer`, `bitsadmin`, `WebClient.DownloadFile`/`DownloadString`,
///     `certutil -urlcache`), AND
///   - a target that is either a Minecraft content directory (`mods`/`saves`/`resourcepacks`/
///     `shaderpacks`/`datapacks`/`.minecraft`) or a known mod-hosting host
///     (Modrinth's CDN/API/site, CurseForge's CDN/API/site).
///
/// A download verb alone (e.g. fetching a changelog or an unrelated file) is not flagged; neither is
/// a game-directory path with no download verb (e.g. `ls .minecraft/mods`).
@NotNullByDefault
public final class ShellToolOverlap {

    private ShellToolOverlap() {
    }

    /// Command-line tools/APIs whose entire purpose is fetching a URL's contents to disk/stdout —
    /// exactly what `instance(action="mods_install")` (and friends) already do through HMCL's own,
    /// version-aware download path.
    private static final Pattern DOWNLOAD_VERB = Pattern.compile(
            "(?i)(invoke-webrequest|\\biwr\\b|invoke-restmethod|\\birm\\b|\\bcurl\\b|\\bwget\\b|"
                    + "start-bitstransfer|\\bbitsadmin\\b|downloadfile|downloadstring|"
                    + "certutil\\b[^\\r\\n]*-urlcache\\b)");

    /// A Minecraft content directory a dedicated tool manages, or a mod-hosting host a dedicated
    /// tool already knows how to query/download from. Matched only WHEN a download verb is also
    /// present (see {@link #overlapReason}).
    private static final Pattern OVERLAP_TARGET = Pattern.compile(
            "(?i)(\\bmods\\b|\\bsaves\\b|\\bresourcepacks\\b|\\bshaderpacks\\b|\\bdatapacks\\b|\\.minecraft\\b|"
                    + "cdn\\.modrinth\\.com|api\\.modrinth\\.com|modrinth\\.com|"
                    + "edge\\.forgecdn\\.net|media\\.forgecdn\\.net|"
                    + "api\\.curseforge\\.com|curseforge\\.com)");

    /// Returns a human-readable Chinese reason when {@code command} looks like a shell-based
    /// re-implementation of a dedicated tool (currently: downloading mod/resource content straight
    /// into the game directory), or {@code null} when it doesn't. Never throws, never blocks — see
    /// the class doc for why this is a confirmation-dialog nudge rather than an execution gate.
    ///
    /// @param command the raw shell command line (as the model would pass it to the `shell` tool)
    @Nullable
    public static String overlapReason(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        boolean downloadVerb = DOWNLOAD_VERB.matcher(command).find();
        boolean overlapsTarget = OVERLAP_TARGET.matcher(command).find();
        if (downloadVerb && overlapsTarget) {
            return "这个 shell 命令看起来是在绕开专属工具，直接从模组下载源下载文件到游戏目录"
                    + "（mods/saves/resourcepacks 等）——这类操作有专属工具（如 instance 的"
                    + " mods_install/modpacks_install），应当优先使用，而不是让 shell 抄近道。";
        }
        return null;
    }
}
