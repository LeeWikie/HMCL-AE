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

import com.google.gson.Gson;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// An AI-accessible tool that lets the agent maintain a visible, ordered TODO list
/// for a multi-step task. Each call REPLACES the whole list, so the model both adds
/// steps and updates their status (pending → in_progress → done) through the same
/// tool. The UI renders the latest list as a pinned card above the chat so the user
/// can see exactly where the agent is.
///
/// Because the structured-schema parser only supports flat fields, the list is passed
/// as a single JSON-string parameter `todos`: an array of
/// `{"content": string, "status": "pending"|"in_progress"|"done"}`.
public final class TodoWriteTool implements ToolSpec {

    private static final Gson GSON = new Gson();

    /// A single TODO entry. {@code status} is one of pending / in_progress / done.
    public record TodoItem(String content, String status) {
    }

    /// UI bridge: receive the freshly parsed list and render it (the implementation is
    /// responsible for marshalling to the FX thread).
    @FunctionalInterface
    public interface TodoUiHandler {
        void update(List<TodoItem> todos);
    }

    private final TodoUiHandler handler;

    public TodoWriteTool(TodoUiHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        return "Maintain a visible TODO checklist for a multi-step task so the user can see your progress. "
                + "Call this at the START of any task that needs several steps to lay out the plan, then call it "
                + "again after EACH step to update statuses — exactly one item should be 'in_progress' at a time. "
                + "Each call REPLACES the entire list. "
                + "Parameter 'todos' is a JSON array string; each item is "
                + "{\"content\": string, \"status\": \"pending\"|\"in_progress\"|\"done\"}. "
                + "Example: [{\"content\":\"查看现有实例\",\"status\":\"done\"},"
                + "{\"content\":\"安装 Fabric 加载器\",\"status\":\"in_progress\"},"
                + "{\"content\":\"安装 Sodium\",\"status\":\"pending\"}].";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.LOCAL;
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
                   "todos": {"type": "string", "description": "A JSON array of todo objects. Each: {content:string, status:'pending'|'in_progress'|'done'}. Send it as a JSON-encoded string. The whole list is replaced on every call."}
                 },
                 "required": ["todos"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        List<TodoItem> todos = parseTodos(parameters.get("todos"));
        if (todos.isEmpty()) {
            return ToolResult.failure("todo_write: provide 'todos' as a JSON array of objects, each "
                    + "{content:string, status:'pending'|'in_progress'|'done'}.");
        }

        try {
            handler.update(todos);
        } catch (Exception e) {
            return ToolResult.failure("todo_write: UI is unavailable: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        int done = 0, inProgress = 0;
        StringBuilder sb = new StringBuilder("Updated TODO list (").append(todos.size()).append(" items):\n");
        for (TodoItem t : todos) {
            String status = t.status();
            if ("done".equals(status)) done++;
            else if ("in_progress".equals(status)) inProgress++;
            String mark = switch (status) {
                case "done" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(mark).append(' ').append(t.content()).append('\n');
        }
        sb.append("(").append(done).append(" done, ").append(inProgress).append(" in progress, ")
                .append(todos.size() - done - inProgress).append(" pending)");
        return ToolResult.success(sb.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private static List<TodoItem> parseTodos(Object raw) {
        List<?> list;
        if (raw instanceof List<?> l) {
            list = l;
        } else if (raw instanceof String s && !s.isBlank()) {
            Object parsed;
            try {
                parsed = GSON.fromJson(s, Object.class);
            } catch (RuntimeException e) {
                return List.of();
            }
            if (parsed instanceof List<?> l2) {
                list = l2;
            } else if (parsed instanceof Map<?, ?> m && m.get("todos") instanceof List<?> l3) {
                list = l3;
            } else {
                return List.of();
            }
        } else {
            return List.of();
        }

        List<TodoItem> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String content = str(m.get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            String status = str(m.get("status"));
            status = status == null || status.isBlank() ? "pending" : status.toLowerCase();
            if (!status.equals("done") && !status.equals("in_progress")) {
                status = "pending";
            }
            result.add(new TodoItem(content.strip(), status));
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
