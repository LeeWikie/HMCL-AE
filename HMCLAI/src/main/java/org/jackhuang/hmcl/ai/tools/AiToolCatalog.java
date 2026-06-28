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

    public static List<Descriptor> builtInDescriptors() {
        return List.of(
                new Descriptor("analyze_crash", "分析粘贴的 Minecraft crash report 文本", ToolSource.LOCAL, ToolPermission.READ_ONLY, CapabilityStatus.AVAILABLE),
                new Descriptor("file_backup", "备份或恢复指定文件；当前仍需要调用方提供安全路径", ToolSource.FILESYSTEM, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.AVAILABLE),
                new Descriptor("mod_toggle", "列出、启用或禁用 mods 目录中的 jar；当前仍需要调用方提供 modsDir", ToolSource.FILESYSTEM, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.AVAILABLE),
                new Descriptor("read_minecraft_log", "读取 Minecraft latest.log 与 HMCL 日志尾部", ToolSource.FILESYSTEM, ToolPermission.READ_ONLY, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("resolve_game_context", "解析当前实例的 mods/logs/crash-reports/config 等目录", ToolSource.FILESYSTEM, ToolPermission.READ_ONLY, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("read_file", "在允许根目录内列目录或读取文本文件", ToolSource.FILESYSTEM, ToolPermission.READ_ONLY, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("list_crash_reports", "列出当前实例 crash-reports 目录下最近的崩溃报告", ToolSource.FILESYSTEM, ToolPermission.READ_ONLY, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("web_search", "执行联网搜索并返回标题、链接与摘要", ToolSource.SEARCH, ToolPermission.EXTERNAL_NETWORK, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("mcp.*", "来自 MCP 服务器的动态工具，按服务器 allowlist 暴露", ToolSource.MCP, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("skill.*", "SKILL.md 技能包声明的工作流与知识能力", ToolSource.SKILL, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.REQUIRES_CONTEXT),
                new Descriptor("write_text_file_safe", "带路径白名单和备份的文本写入", ToolSource.FILESYSTEM, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.PLANNED),
                new Descriptor("backup_directory", "备份目录以支持批量变更回滚", ToolSource.FILESYSTEM, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.PLANNED),
                new Descriptor("restore_backup", "从 HMCL-AE 备份恢复文件或目录", ToolSource.FILESYSTEM, ToolPermission.CONTROLLED_WRITE, CapabilityStatus.PLANNED)
        );
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
