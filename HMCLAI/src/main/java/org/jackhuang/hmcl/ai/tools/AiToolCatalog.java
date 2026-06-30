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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Central catalog of HMCL-AE tool and filesystem capabilities.
///
/// This is intentionally UI-free so the JavaFX settings page, prompt builder,
/// and future AEL engine can share one capability map instead of maintaining
/// divergent hard-coded lists.
@NotNullByDefault
public final class AiToolCatalog {
    private AiToolCatalog() {
    }

    public record Descriptor(String name, String description, ToolSource source,
                             ToolPermission permission, CapabilityStatus status) {
    }

    public enum CapabilityStatus {
        AVAILABLE,
        REQUIRES_CONTEXT,
        PLANNED
    }

    /// Intentionally empty: the live {@link ToolRegistry} is the single source of truth for what
    /// tools actually exist, so {@link #descriptorsForRegistry} derives the whole list from real,
    /// registered tools. The previous hard-coded list had drifted (renamed/phantom tools like
    /// {@code analyze_crash}, {@code read_file}, {@code mod_toggle}) and made the permission UI
    /// show tools that don't exist while omitting the real ones.
    public static List<Descriptor> builtInDescriptors() {
        return List.of();
    }

    public static List<Descriptor> descriptorsForRegistry(ToolRegistry registry) {
        List<Descriptor> descriptors = new ArrayList<>(builtInDescriptors());
        for (Tool tool : registry.listAll()) {
            descriptors.add(new Descriptor(
                    tool.getName(),
                    tool.getDescription(),
                    registry.getSource(tool.getName()),
                    registry.getPermission(tool.getName()),
                    registry.isDisabled(tool.getName()) ? CapabilityStatus.REQUIRES_CONTEXT : CapabilityStatus.AVAILABLE));
        }
        return descriptors.stream()
                .sorted(Comparator.comparing(Descriptor::source)
                        .thenComparing(Descriptor::status)
                        .thenComparing(Descriptor::name))
                .toList();
    }
}
