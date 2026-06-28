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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/// An AI-accessible tool that lets the agent ask the user structured questions
/// (single-choice, multi-choice, or free text) inside the chat and wait for the
/// answers. This is the automation engine: instead of guessing, or dumping a
/// list of "please click these steps yourself", the agent resolves ambiguous
/// requirements (which version, which loader, which optional mods, confirm a
/// destructive action) by asking — then acts on the answers with the other tools.
///
/// Execution model: {@link #execute} runs on the agent's background thread. It
/// hands the parsed questions to a {@link AskUiHandler} (provided by the UI),
/// which renders the question panel on the JavaFX thread and returns a future
/// completed when the user submits. {@code execute} blocks that background thread
/// on the future (never the FX thread). If the user stops the response or
/// switches sessions, the future is cancelled and the tool returns a failure so
/// the model can react.
public final class AskTool implements ToolSpec {

    private static final Gson GSON = new Gson();

    /// A single question presented to the user.
    public record Question(String question, String type, List<String> options, boolean allowCustom) {
    }

    /// UI bridge: render the questions and complete the returned future with one
    /// answer string per question (multi-choice answers are comma-joined), or
    /// complete it exceptionally (e.g. {@link CancellationException}) if the user
    /// cancels.
    @FunctionalInterface
    public interface AskUiHandler {
        CompletableFuture<List<String>> ask(List<Question> questions);
    }

    private final AskUiHandler handler;

    public AskTool(AskUiHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public String getDescription() {
        return "Ask the user one or more structured questions and wait for their answers. "
                + "USE THIS to resolve ambiguous requirements (which Minecraft version, which mod loader, "
                + "which optional mods, etc.) or to confirm a destructive action — instead of guessing or "
                + "telling the user to perform manual steps you could do yourself with other tools. "
                + "Parameter 'questions' is a JSON array; each item is an object: "
                + "{\"question\": string, \"type\": \"single\"|\"multi\", \"options\": [string, ...]}. "
                + "EVERY question MUST be single or multi and MUST include at least one concrete option; "
                + "never use a free-text-only question. A custom '自定义' choice is appended automatically "
                + "for the user to type their own value — do not add it yourself. "
                + "Example: [{\"question\":\"安装哪个版本?\",\"type\":\"single\",\"options\":[\"1.21.1\",\"1.20.1\"]},"
                + "{\"question\":\"哪个加载器?\",\"type\":\"single\",\"options\":[\"Fabric\",\"Forge\",\"NeoForge\",\"Quilt\"]},"
                + "{\"question\":\"安装哪些附属?\",\"type\":\"multi\",\"options\":[\"Sodium Extra\",\"Iris Shaders\"]}].";
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
                   "questions": {"type": "string", "description": "A JSON array of question objects. Each: {question:string, type:'single'|'multi'|'text', options:[string] for single/multi, allowCustom:bool}. Send it as a JSON array (or a JSON-encoded string)."}
                 },
                 "required": ["questions"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        List<Question> questions = parseQuestions(parameters.get("questions"));
        if (questions.isEmpty()) {
            Object q = parameters.get("query");
            if (q != null && !q.toString().isBlank()) {
                questions = List.of(new Question(q.toString(), "text", List.of(), true));
            }
        }
        if (questions.isEmpty()) {
            return ToolResult.failure("ask: provide 'questions' as a JSON array of objects, each "
                    + "{question, type:single|multi|text, options:[...], allowCustom:bool}.");
        }

        CompletableFuture<List<String>> future;
        try {
            future = handler.ask(questions);
        } catch (Exception e) {
            return ToolResult.failure("ask: question UI is unavailable: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        try {
            List<String> answers = future.get();
            StringBuilder sb = new StringBuilder("The user answered:\n");
            for (int i = 0; i < questions.size(); i++) {
                String a = answers != null && i < answers.size() ? answers.get(i) : null;
                sb.append(i + 1).append(". ").append(questions.get(i).question())
                        .append("\n   -> ").append(a == null || a.isBlank() ? "(no answer)" : a).append('\n');
            }
            return ToolResult.success(sb.toString().trim());
        } catch (CancellationException ce) {
            return ToolResult.failure("The user cancelled the questions (did not answer).");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CancellationException) {
                return ToolResult.failure("The user cancelled the questions (did not answer).");
            }
            return ToolResult.failure("ask: " + (cause.getMessage() != null
                    ? cause.getMessage() : cause.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Question> parseQuestions(Object raw) {
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
            } else if (parsed instanceof Map<?, ?> m && m.get("questions") instanceof List<?> l3) {
                list = l3;
            } else {
                return List.of();
            }
        } else {
            return List.of();
        }

        List<Question> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String question = str(m.get("question"));
            if (question == null || question.isBlank()) {
                continue;
            }
            String type = str(m.get("type"));
            type = type == null || type.isBlank() ? "text" : type.toLowerCase();
            List<String> options = new ArrayList<>();
            if (m.get("options") instanceof List<?> opts) {
                for (Object o : opts) {
                    if (o != null) {
                        options.add(o.toString());
                    }
                }
            }
            Object custom = m.get("allowCustom");
            boolean allowCustom = Boolean.TRUE.equals(custom)
                    || (custom != null && "true".equalsIgnoreCase(custom.toString()));
            result.add(new Question(question, type, options, allowCustom));
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
