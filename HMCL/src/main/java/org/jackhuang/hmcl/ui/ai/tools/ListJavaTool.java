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
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Read-only tool: lists the Java runtimes HMCL has discovered on this machine.
///
/// Reuses [`JavaManager#getAllJava()`]. Returns each runtime's version, vendor,
/// JRE/JDK kind, CPU architecture and path, marking HMCL-managed (downloaded) ones.
/// The AI should call this instead of probing `java -version` via shell.
@NotNullByDefault
public final class ListJavaTool implements Tool {

    @Override
    public String getName() {
        return "list_java";
    }

    @Override
    public String getDescription() {
        return "Lists the Java runtimes HMCL has discovered (version, vendor, JDK/JRE, architecture, path; "
                + "marks HMCL-managed downloads). Takes no parameters. Read-only. "
                + "Use this instead of running `java -version` via shell.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        List<JavaRuntime> runtimes;
        try {
            runtimes = new ArrayList<>(JavaManager.getAllJava());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while reading Java runtimes.");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read Java runtimes: " + e.getMessage());
        }

        if (runtimes.isEmpty()) {
            return ToolResult.success("No Java runtimes have been discovered yet. "
                    + "HMCL can download a suitable Java automatically when launching a game.");
        }

        runtimes.sort(null); // JavaRuntime implements Comparable (by version/arch/path)
        StringBuilder sb = new StringBuilder();
        sb.append("Discovered Java runtimes (").append(runtimes.size()).append("):\n");
        for (JavaRuntime java : runtimes) {
            sb.append("  - ").append(java.getVersion())
                    .append(" [").append(java.isJDK() ? "JDK" : "JRE").append(']')
                    .append(' ').append(java.getArchitecture());
            String vendor = java.getVendor();
            if (vendor != null && !vendor.isEmpty()) {
                sb.append(" · ").append(vendor);
            }
            if (java.isManaged()) {
                sb.append(" · HMCL-managed");
            }
            sb.append("\n      path: ").append(java.getBinary()).append('\n');
        }
        return ToolResult.success(sb.toString().trim());
    }
}
