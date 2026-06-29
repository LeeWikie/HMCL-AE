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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.DataSizeUnit;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// A read-only tool that lists the screenshots captured for a Minecraft instance.
///
/// It resolves the instance through HMCL's launcher APIs:
/// - [`Profiles#getSelectedProfile()`] / [`Profile#getRepository()`] for the repository,
/// - the optional `instance` parameter (defaulting to [`Profiles#getSelectedInstance()`]),
/// - [`HMCLGameRepository#hasVersion(String)`] to validate the instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the per-instance game directory,
///   then lists image files (.png/.jpg) under {@code screenshots/}.
///
/// Permission level: READ_ONLY. It never modifies any launcher state or file.
@NotNullByDefault
public final class ListScreenshotsTool implements Tool {

    private static final int MAX_RESULTS = 50;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "list_screenshots";
    }

    @Override
    public String getDescription() {
        return "Lists the screenshots (.png/.jpg) captured for a Minecraft instance, sorted by modification time "
                + "(newest first), showing file name, size and modification time, up to 50 entries. "
                + "Parameter: 'instance' (optional string) - the instance id; defaults to the currently selected "
                + "instance. Read-only.";
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

        String instance = InstanceToolSupport.string(parameters, "instance");
        if (instance == null) {
            instance = Profiles.getSelectedInstance();
        }
        if (instance == null) {
            return ToolResult.failure("No instance was specified and no instance is currently selected.");
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance not found: " + instance);
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve instance '" + instance + "': " + e.getMessage());
        }

        Path screenshotsDir;
        try {
            screenshotsDir = repository.getRunDirectory(instance).resolve("screenshots");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the screenshots directory: " + e.getMessage());
        }

        if (!Files.isDirectory(screenshotsDir)) {
            return ToolResult.success("No screenshots directory exists for instance '" + instance + "'.\n"
                    + "Expected location: " + screenshotsDir);
        }

        List<Path> images = new ArrayList<>();
        try (Stream<Path> stream = Files.list(screenshotsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(ListScreenshotsTool::isImage)
                    .forEach(images::add);
        } catch (IOException e) {
            return ToolResult.failure("Failed to list the screenshots directory: " + e.getMessage());
        }

        if (images.isEmpty()) {
            return ToolResult.success("No screenshots found for instance '" + instance + "'.\n"
                    + "Directory: " + screenshotsDir);
        }

        // Sort by modification time, newest first (unreadable times sort last).
        images.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));

        int total = images.size();
        int shown = Math.min(total, MAX_RESULTS);

        StringBuilder sb = new StringBuilder();
        sb.append("Instance: ").append(instance).append('\n');
        sb.append("Directory: ").append(screenshotsDir).append('\n');
        sb.append("Screenshots (").append(total).append(')');
        if (shown < total) {
            sb.append(", showing newest ").append(shown);
        }
        sb.append(":\n");

        for (int i = 0; i < shown; i++) {
            Path image = images.get(i);
            sb.append("  - ").append(image.getFileName());

            long size = -1;
            try {
                size = Files.size(image);
            } catch (IOException ignored) {
                // Size is best-effort.
            }
            sb.append("  (").append(size >= 0 ? DataSizeUnit.format(size) : "unknown size").append(')');

            long modified = lastModified(image);
            if (modified > Long.MIN_VALUE) {
                sb.append("  ").append(TIME_FORMAT.format(java.time.Instant.ofEpochMilli(modified)));
            }
            sb.append('\n');
        }

        return ToolResult.success(sb.toString().trim());
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /// Returns the file's last-modified time in epoch milliseconds, or {@link Long#MIN_VALUE}
    /// if it cannot be read (so such entries sort last).
    private static long lastModified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
