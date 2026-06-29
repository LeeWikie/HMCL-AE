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
import org.jackhuang.hmcl.util.DataSizeUnit;
import org.jackhuang.hmcl.util.platform.SystemInfo;
import org.jackhuang.hmcl.util.platform.hardware.CentralProcessor;
import org.jackhuang.hmcl.util.platform.hardware.GraphicsCard;
import org.jackhuang.hmcl.util.platform.hardware.PhysicalMemoryStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;

/// A read-only tool that reports machine information for diagnostics.
///
/// This reuses HMCL's native hardware detection where possible:
/// - [`SystemInfo#getCentralProcessor()`] for the CPU model and core/thread counts,
/// - [`SystemInfo#getGraphicsCards()`] for GPU model(s),
/// - [`SystemInfo#getPhysicalMemoryStatus()`] / [`SystemInfo#getTotalMemorySize()`]
///   for physical memory (with a `com.sun.management` fallback for the total).
///
/// Permission level: READ_ONLY. It never modifies any launcher or system state.
@NotNullByDefault
public final class SystemInfoTool implements Tool {

    @Override
    public String getName() {
        return "system_info";
    }

    @Override
    public String getDescription() {
        return "Reports machine information for diagnostics: OS name/version/architecture, CPU model and "
                + "logical processor count, GPU model(s), total/used physical memory, and the JVM version and "
                + "max heap. Takes no parameters. Read-only.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            StringBuilder sb = new StringBuilder();

            // Operating system (from system properties).
            sb.append("OS: ")
                    .append(System.getProperty("os.name", "unknown"))
                    .append(' ').append(System.getProperty("os.version", ""))
                    .append(" (").append(System.getProperty("os.arch", "")).append(")\n");

            // CPU: prefer HMCL's native detector, fall back to the logical core count.
            int logicalProcessors = Runtime.getRuntime().availableProcessors();
            CentralProcessor cpu = null;
            try {
                cpu = SystemInfo.getCentralProcessor();
            } catch (Throwable ignored) {
                // Native detection failed; we still report the logical core count below.
            }
            if (cpu != null) {
                sb.append("CPU: ").append(cpu).append('\n');
            }
            sb.append("CPU logical processors: ").append(logicalProcessors).append('\n');

            // GPU(s): best-effort via HMCL's native detector.
            try {
                List<GraphicsCard> graphicsCards = SystemInfo.getGraphicsCards();
                if (graphicsCards != null && !graphicsCards.isEmpty()) {
                    if (graphicsCards.size() == 1) {
                        sb.append("GPU: ").append(graphicsCards.get(0)).append('\n');
                    } else {
                        int index = 1;
                        for (GraphicsCard card : graphicsCards) {
                            sb.append("GPU ").append(index++).append(": ").append(card).append('\n');
                        }
                    }
                }
            } catch (Throwable ignored) {
                // GPU detection is optional; ignore failures.
            }

            // Physical memory: prefer HMCL's native detector, fall back to the JMX bean.
            sb.append("Physical memory: ").append(describeMemory()).append('\n');

            // JVM.
            sb.append("JVM version: ").append(System.getProperty("java.version", "unknown"));
            String vmName = System.getProperty("java.vm.name");
            if (vmName != null && !vmName.isEmpty()) {
                sb.append(" (").append(vmName).append(')');
            }
            sb.append('\n');
            sb.append("JVM max heap: ")
                    .append(DataSizeUnit.format(Runtime.getRuntime().maxMemory()));

            return ToolResult.success(sb.toString().trim());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to collect system information: " + e.getMessage());
        }
    }

    /// Describes total/used physical memory, preferring HMCL's native detector and
    /// falling back to {@code com.sun.management.OperatingSystemMXBean#getTotalMemorySize()}.
    private static String describeMemory() {
        try {
            PhysicalMemoryStatus status = SystemInfo.getPhysicalMemoryStatus();
            if (status != null && status.getTotal() > 0) {
                if (status.hasAvailable()) {
                    return DataSizeUnit.format(status.getUsed())
                            + " used / " + DataSizeUnit.format(status.getTotal()) + " total";
                }
                return DataSizeUnit.format(status.getTotal()) + " total";
            }
        } catch (Throwable ignored) {
            // Fall through to the JMX fallback below.
        }

        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                if (bytes > 0) {
                    return DataSizeUnit.format(bytes) + " total";
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No reliable source available.
        }

        return "unknown";
    }
}
