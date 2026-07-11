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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
/// on the future — bounded by {@link #timeout}/{@link #timeoutUnit} so a panel
/// that never gets answered (the user navigates away, closes the window
/// improperly, or a UI bug leaves it un-dismissable) can't hang the turn forever
/// — never the FX thread. If the user stops the response or switches sessions,
/// or the wait times out, the future is cancelled and the tool returns the same
/// "user did not respond" failure so the model can react.
public final class AskTool implements ToolSpec {

    private static final Gson GSON = new Gson();

    /// How long {@link #execute} waits for the user to answer before giving up and treating it
    /// like a cancellation. Generous on purpose — the user may be reading through several
    /// questions — but bounded so a stuck/abandoned panel can never hang the turn forever.
    private static final long DEFAULT_TIMEOUT = 10;
    private static final TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.MINUTES;

    /// Returned whenever the user cancels the questions OR the wait times out — the model can't
    /// tell the two apart and shouldn't need to, since both mean "no answer is coming".
    private static final String NOT_ANSWERED_MESSAGE = "The user cancelled the questions (did not answer).";

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
    private final long timeout;
    private final TimeUnit timeoutUnit;

    public AskTool(AskUiHandler handler) {
        this(handler, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }

    /// Package-private: lets tests inject a short timeout so the never-answered path can be
    /// exercised without actually waiting {@link #DEFAULT_TIMEOUT} {@link #DEFAULT_TIMEOUT_UNIT}.
    AskTool(AskUiHandler handler, long timeout, TimeUnit timeoutUnit) {
        this.handler = handler;
        this.timeout = timeout;
        this.timeoutUnit = timeoutUnit;
    }

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public String getDescription() {
        return "Ask the user one or more structured questions via a UI dialog (single/multi-select "
                + "buttons, or a free-text box) and wait for their answers. RESERVE this tool for cases "
                + "where the structured dialog genuinely helps: (a) 2+ concrete discrete options to pick "
                + "from (single- or multi-choice), and/or (b) bundling 2+ related sub-questions into ONE "
                + "dialog — a free-text sub-question is fine there, riding alongside at least one single/"
                + "multi question. Do NOT call this tool for a SINGLE open-ended free-text question, or a "
                + "vague/fuzzy opinion or preference with no discrete options (e.g. \"which mods do you "
                + "want?\", \"any preference?\", \"what do you think?\") — just ask that directly in your "
                + "own response text and end the turn normally; the user replies with an ordinary chat "
                + "message, no tool call needed. "
                + "Parameter 'questions' is a JSON array; each item is an object: "
                + "{\"question\": string, \"type\": \"single\"|\"multi\"|\"text\", \"options\": [string, ...]}. "
                + "'single'/'multi' questions MUST include at least one concrete option. A custom '自定义' "
                + "choice is appended automatically to single/multi questions for the user to type their "
                + "own value — do not add it yourself. 'text' is only for a free-text sub-question bundled "
                + "alongside at least one single/multi question in the SAME call — never send 'text' as "
                + "the lone question. "
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
                   "questions": {"type": "string", "description": "A JSON array of question objects. Each: {question:string, type:'single'|'multi'|'text', options:[string] for single/multi, allowCustom:bool}. Send it as a JSON array (or a JSON-encoded string). Only include a 'text' question bundled alongside a single/multi question in the same array — never as the sole item; for one standalone open-ended question, don't call this tool at all, just ask in your response text."}
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
            List<String> answers = future.get(timeout, timeoutUnit);
            StringBuilder sb = new StringBuilder("The user answered:\n");
            for (int i = 0; i < questions.size(); i++) {
                String a = answers != null && i < answers.size() ? answers.get(i) : null;
                sb.append(i + 1).append(". ").append(questions.get(i).question())
                        .append("\n   -> ").append(a == null || a.isBlank() ? "(no answer)" : a).append('\n');
            }
            return ToolResult.success(sb.toString().trim());
        } catch (TimeoutException te) {
            // Nobody answered within the deadline. Cancel the future ourselves (rather than
            // leaving it dangling) so the UI side — which is watching for the future's
            // completion, not polling a clock — dismisses the now-stale panel right away instead
            // of leaving it visible/answerable indefinitely after the turn has already moved on.
            future.cancel(true);
            return ToolResult.failure(NOT_ANSWERED_MESSAGE);
        } catch (CancellationException ce) {
            return ToolResult.failure(NOT_ANSWERED_MESSAGE);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CancellationException) {
                return ToolResult.failure(NOT_ANSWERED_MESSAGE);
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
            type = type == null || type.isBlank() ? "text" : type.toLowerCase(Locale.ROOT);
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
