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
/// tool. The list serves TWO audiences at once: it's the agent's own durable plan —
/// something to check completed work off against instead of quietly losing track of
/// it mid-task — and the UI renders the same latest list as a pinned card above the
/// chat so the user can see exactly where the agent is. Neither purpose replaces the
/// other.
///
/// Entries are TASK-UNIT granularity, not one line per sub-item: installing a batch of
/// mods is one entry ("安装 Mods") covering the whole batch, plus a second entry for
/// verification — never a separate line per mod. See {@link #getDescription()}'s example.
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

    /// The list accepted on the previous successful call, used only to detect unfinished items
    /// that were silently dropped from the plan (a non-blocking discipline echo). Session-scoped:
    /// one tool instance per chat page, and tool calls run sequentially, so a plain field is safe.
    private List<TodoItem> previousTodos = List.of();

    public TodoWriteTool(TodoUiHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        return "Maintain a persistent TODO checklist for a multi-step task. This serves BOTH you and the "
                + "user, not just one: it's YOUR OWN durable plan — check items off against it instead of "
                + "quietly losing track of what you already finished — and it's also a live progress card the "
                + "user sees above the chat. "
                + "GRANULARITY IS TASK-UNIT, NOT PER-ITEM: each entry is one coherent phase of work, never one "
                + "line per sub-item — e.g. installing a batch of mods is a SINGLE entry '安装 Mods' covering "
                + "the whole batch, plus a second entry '验证安装结果'; do NOT create a separate line per mod. "
                + "Call this at the START of any task that needs several such phases to lay out the plan, then "
                + "call it again the moment a phase actually finishes to check it off — exactly one item "
                + "'in_progress' at a time. "
                + "Each call REPLACES the entire list, so when the plan changes this MUST be an UPDATE to the "
                + "existing list: keep every item that's still valid, adjust what changed, carry over anything "
                + "already finished as 'done'. NEVER silently drop unfinished items and start a brand-new list "
                + "in their place — that discards real progress and the user's visibility into it. "
                + "Parameter 'todos' is a JSON array string; each item is "
                + "{\"content\": string, \"status\": \"pending\"|\"in_progress\"|\"done\"}. "
                + "Example: [{\"content\":\"安装 Mods\",\"status\":\"in_progress\"},"
                + "{\"content\":\"验证安装结果\",\"status\":\"pending\"}].";
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
                   "todos": {"type": "string", "description": "A JSON array of task-unit todo objects (not one per sub-item). Each: {content:string, status:'pending'|'in_progress'|'done'}. Send it as a JSON-encoded string. The whole list is replaced on every call, so send an UPDATE of the existing list — carry over finished items as 'done', never drop them to start a new list."}
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

        // Non-blocking discipline echo: surface rule violations the model itself declared so it can
        // self-correct NOW, instead of being caught 15 cycles later by the runtime staleness guard.
        // These do NOT fail the call — the list is already accepted and rendered.
        if (inProgress > 1) {
            sb.append("\n[WARNING] ").append(inProgress).append(" items are 'in_progress' at once — ")
                    .append("the rule is exactly ONE at a time; double-check this reflects reality.");
        }
        List<String> dropped = droppedUnfinished(previousTodos, todos);
        if (!dropped.isEmpty()) {
            sb.append("\n[WARNING] ").append(dropped.size()).append(" unfinished item(s) from the previous "
                    + "list are gone without being marked 'done': ").append(String.join("; ", dropped))
                    .append(" — if that was intentional keep going, otherwise carry them over.");
        }

        previousTodos = todos;
        return ToolResult.success(sb.toString().trim());
    }

    /// Returns the contents of items that were NOT 'done' in {@code previous} and are absent (by
    /// exact content) from {@code current} — i.e. unfinished work silently dropped from the plan.
    private static List<String> droppedUnfinished(List<TodoItem> previous, List<TodoItem> current) {
        if (previous.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> currentContents = new java.util.HashSet<>();
        for (TodoItem t : current) {
            currentContents.add(t.content());
        }
        List<String> dropped = new ArrayList<>();
        for (TodoItem t : previous) {
            if (!"done".equals(t.status()) && !currentContents.contains(t.content())) {
                dropped.add(t.content());
            }
        }
        return dropped;
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
