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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// A tool that downloads a Mojang-provided Java runtime suitable for a given
/// Minecraft version (or an explicit Java major version) on this platform.
///
/// This reuses HMCL's native Java provisioning pipeline directly:
/// - [`GameJavaVersion#getMinimumJavaVersion(GameVersionNumber)`] / [`GameJavaVersion#get(int)`]
///   to map a Minecraft version or Java major to the right runtime component,
/// - [`GameJavaVersion#getSupportedVersions(Platform)`] to check platform availability,
/// - [`JavaManager#getDownloadJavaTask(DownloadProvider, Platform, GameJavaVersion)`]
///   (with [`DownloadProviders#getDownloadProvider()`]) to download + register it,
/// - [`ContentToolSupport#runTaskBlocking`] to honor the synchronous tool contract.
///
/// If a matching runtime is already installed it is reported and no download happens.
/// If the Java version cannot be determined or is not offered for this platform, the
/// tool degrades to a recommendation instead of failing silently.
///
/// Permission level: it WRITES (downloads and installs a Java runtime into HMCL's
/// managed directory) and uses the network.
@NotNullByDefault
public final class DownloadJavaTool implements Tool {

    /// Java runtime downloads can be tens of megabytes; allow a generous window.
    private static final int TIMEOUT_SECONDS = 600;

    @Override
    public String getName() {
        return "download_java";
    }

    @Override
    public String getDescription() {
        return "Downloads and installs a Mojang-provided Java runtime suitable for a Minecraft version on this machine. "
                + "Parameters (one of): gameVersion (e.g. '1.20.1') — picks the minimum required Java automatically; "
                + "or javaVersion (an integer Java major: 8, 16, 17, 21 or 25). "
                + "WRITES: downloads and registers a Java runtime in HMCL's managed directory, and uses the network. "
                + "If a matching runtime is already installed, no download is performed. Returns the installed runtime's path.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        GameJavaVersion target;
        String requestedDescription;

        String javaVersionParam = str(parameters.get("javaVersion"));
        String gameVersionParam = str(parameters.get("gameVersion"));
        String queryParam = str(parameters.get("query"));

        if (javaVersionParam != null) {
            int major = InstanceToolSupport.parseInt(parameters.get("javaVersion"), Integer.MIN_VALUE);
            if (major == Integer.MIN_VALUE) {
                return ToolResult.failure("Parameter 'javaVersion' must be an integer Java major version, got: " + javaVersionParam);
            }
            target = GameJavaVersion.get(major);
            requestedDescription = "Java " + major;
            if (target == null) {
                return ToolResult.failure("HMCL cannot download Java " + major
                        + ". Available majors are: 8, 16, 17, 21, 25.");
            }
        } else {
            String gameVersion = gameVersionParam != null ? gameVersionParam : queryParam;
            if (gameVersion == null || gameVersion.trim().isEmpty()) {
                return ToolResult.failure("Provide either 'gameVersion' (e.g. '1.20.1') or 'javaVersion' (e.g. 17).");
            }
            gameVersion = gameVersion.trim();
            requestedDescription = "Minecraft " + gameVersion;
            try {
                target = GameJavaVersion.getMinimumJavaVersion(GameVersionNumber.asGameVersion(gameVersion));
            } catch (Throwable e) {
                return ToolResult.failure("Could not parse game version '" + gameVersion + "': " + e.getMessage());
            }
            if (target == null) {
                return ToolResult.success("Minecraft " + gameVersion + " is older than 1.13, for which Mojang does not "
                        + "provide a managed Java runtime. Recommendation: use a Java 8 runtime (HMCL ships one via "
                        + "'javaVersion'=8) — call this tool again with javaVersion=8 if you want HMCL to download Java 8.");
            }
        }

        // Platform availability check.
        Platform platform = Platform.SYSTEM_PLATFORM;
        if (!GameJavaVersion.getSupportedVersions(platform).contains(target)) {
            return ToolResult.success("Java " + target.majorVersion() + " (needed for " + requestedDescription + ") is not "
                    + "offered by Mojang for this platform (" + platform + "). "
                    + "Recommendation: install a Java " + target.majorVersion()
                    + " runtime manually and add it to HMCL, or choose a different runtime.");
        }

        // Already installed?
        try {
            for (JavaRuntime java : JavaManager.getAllJava()) {
                if (java.getParsedVersion() == target.majorVersion()) {
                    return ToolResult.success("A suitable Java " + target.majorVersion() + " runtime (needed for "
                            + requestedDescription + ") is already installed:\n  " + java.getBinary()
                            + "\nNo download was necessary.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while checking installed Java runtimes.");
        } catch (Throwable ignored) {
            // If enumeration fails, fall through and attempt the download anyway.
        }

        DownloadProvider provider;
        try {
            provider = DownloadProviders.getDownloadProvider();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the download provider: " + e.getMessage());
        }

        try {
            Task<JavaRuntime> task = JavaManager.getDownloadJavaTask(provider, platform, target);
            ContentToolSupport.runTaskBlocking(task, TIMEOUT_SECONDS, "Java " + target.majorVersion() + " download");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to download Java " + target.majorVersion() + " for "
                    + requestedDescription + ": " + e.getMessage());
        }

        // Report the freshly installed runtime path if we can find it.
        String path = "(installed into HMCL's managed Java directory)";
        try {
            for (JavaRuntime java : JavaManager.getAllJava()) {
                if (java.isManaged() && java.getParsedVersion() == target.majorVersion()) {
                    path = java.getBinary().toString();
                    break;
                }
            }
        } catch (Throwable ignored) {
            // Best-effort only.
        }

        return ToolResult.success("Downloaded and installed Java " + target.majorVersion() + " for "
                + requestedDescription + ".\n  " + path
                + "\nHMCL will now be able to use it automatically when launching matching instances.");
    }

    @Nullable
    private static String str(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
