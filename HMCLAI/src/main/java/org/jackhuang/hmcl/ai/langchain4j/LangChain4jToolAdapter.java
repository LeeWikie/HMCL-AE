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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.CriticalOperations;
import org.jackhuang.hmcl.ai.tools.DangerousCommands;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolConfirmHandler;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/// Adapts HMCL's [`Tool`][org.jackhuang.hmcl.ai.tools.Tool] abstraction
/// into LangChain4j's [`ToolSpecification`] and
/// [`ToolExecutionResultMessage`] types so that native tool-calling in
/// LangChain4j models can invoke HMCL-registered tools.
///
/// Each HMCL tool is mapped to a LangChain4j tool specification whose
/// parameter schema is a single optional `"query"` string property.
/// When a tool execution request is received, the adapter looks up the
/// corresponding HMCL tool in the registry and passes the request arguments
/// as a parameter map.
@NotNullByDefault
public final class LangChain4jToolAdapter {

    private final ToolRegistry registry;
    private final AiExecutionPolicy policy;
    @Nullable
    private final ToolConfirmHandler confirmHandler;
    /// Second, distinct gate for CRITICAL ops (red confirmation), evaluated right before
    /// execution, in ADDITION to (and after) the normal confirm — see {@link CriticalOperations}.
    @Nullable
    private final ToolConfirmHandler criticalConfirmHandler;

    /// Id of the chat session this adapter serves, stamped onto background jobs so their
    /// completion can be routed back to the right conversation for auto-continuation.
    @Nullable
    private volatile String sessionId;

    /// Creates an adapter with no safety enforcement (allow-all). Used by tests and
    /// callers that gate elsewhere.
    public LangChain4jToolAdapter(ToolRegistry registry) {
        this(registry, new AiExecutionPolicy(org.jackhuang.hmcl.ai.AiApprovalMode.YOLO, false), null);
    }

    /// Creates an adapter that enforces {@code policy}, asking {@code confirmHandler} to
    /// confirm any operation the policy marks ASK (e.g. dangerous shell commands in safe mode).
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler) {
        this(registry, policy, confirmHandler, null);
    }

    /// Full constructor: adds a separate {@code criticalConfirmHandler} for the red
    /// second-tier confirmation of catastrophic operations.
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler,
                                  @Nullable ToolConfirmHandler criticalConfirmHandler) {
        this.registry = registry;
        this.policy = policy;
        this.confirmHandler = confirmHandler;
        this.criticalConfirmHandler = criticalConfirmHandler;
    }

    /// Binds the chat session id used to tag background jobs (for completion routing).
    public void setSessionId(@Nullable String sessionId) {
        this.sessionId = sessionId;
    }

    /// Builds LangChain4j [`ToolSpecification`] instances for all non-disabled
    /// tools in the HMCL tool registry.
    ///
    /// Tools that implement {@link ToolSpec} and support structured schema
    /// contribute richer parameter info.  All others use the legacy flat
    /// {@code "query"} parameter.
    ///
    /// @return an unmodifiable list of tool specifications
    public List<ToolSpecification> buildToolSpecifications() {
        List<Tool> tools = registry.list();
        if (tools.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolSpecification> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription());
            JsonObjectSchema schema = null;
            if (tool instanceof ToolSpec spec && spec.supportsStructuredSchema()) {
                schema = parseSchema(spec.getInputSchemaJson());
            }
            builder.parameters(schema != null ? schema : JsonObjectSchema.builder()
                    .addStringProperty("query", "An optional query or input for the tool")
                    .build());
            specs.add(builder.build());
        }
        return Collections.unmodifiableList(specs);
    }

    /// Parses a (flat) JSON Schema string into a LangChain4j [`JsonObjectSchema`],
    /// mapping each `properties` entry to a typed property. Returns null on failure so
    /// the caller can fall back to the flat `"query"` schema.
    @Nullable
    @SuppressWarnings("unchecked")
    private static JsonObjectSchema parseSchema(String json) {
        try {
            Map<String, Object> root = GSON.fromJson(json, MAP_TYPE);
            if (root == null || !(root.get("properties") instanceof Map<?, ?> props) || props.isEmpty()) {
                return null;
            }
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) props).entrySet()) {
                String name = entry.getKey();
                String type = "string";
                String description = "";
                if (entry.getValue() instanceof Map<?, ?> meta) {
                    if (meta.get("type") != null) type = meta.get("type").toString();
                    if (meta.get("description") != null) description = meta.get("description").toString();
                }
                switch (type) {
                    case "integer" -> builder.addIntegerProperty(name, description);
                    case "number" -> builder.addNumberProperty(name, description);
                    case "boolean" -> builder.addBooleanProperty(name, description);
                    default -> builder.addStringProperty(name, description);
                }
            }
            return builder.build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Executes a LangChain4j tool execution request by delegating to the
    /// corresponding HMCL tool.
    ///
    /// The request's `arguments()` JSON string is parsed into a simple
    /// parameter map and passed to {@link Tool#execute}. The result is
    /// wrapped in a LangChain4j
    /// [`ToolExecutionResultMessage`].
    ///
    /// This method follows the "Pi" coding-agent principle: every tool call
    /// MUST yield a result that is fed back to the model so it can
    /// self-correct. It therefore NEVER returns {@code null}. Any failure —
    /// an unknown tool, a tool that throws, or a tool that reports an error —
    /// is surfaced to the model as a normal tool result whose text is clearly
    /// prefixed with {@code "Error:"}. Returning {@code null} would leave the
    /// assistant's tool-use request without a matching tool result, which the
    /// OpenAI/Anthropic APIs reject on the next request.
    ///
    /// @param request the tool execution request from the model
    /// @return a non-null result message to inject into the conversation
    ///         context; carries an {@code "Error: ..."} text on any failure
    public ToolExecutionResultMessage execute(ToolExecutionRequest request) {
        Tool tool = registry.get(request.name());
        if (tool == null) {
            return ToolExecutionResultMessage.from(request,
                    "Error: tool '" + request.name() + "' not found");
        }
        // A disabled tool (plan-mode read-only gating, or an MCP server marked unavailable) must not
        // run even if the model calls it by name from stale context — get() alone doesn't gate this.
        if (registry.isDisabled(request.name())) {
            return ToolExecutionResultMessage.from(request,
                    "Error: tool '" + request.name() + "' is currently disabled and cannot be called.");
        }

        String text;
        try {
            Map<String, Object> parameters = parseArguments(request.arguments());

            // Safety policy: block or confirm dangerous operations before running them.
            ToolPermission perm = (tool instanceof ToolSpec spec)
                    ? spec.getPermission() : ToolPermission.CONTROLLED_WRITE;
            boolean dangerousShell = false;
            if ("shell".equals(tool.getName())) {
                // Scan EVERY alias ShellTool actually reads (command, query, input) and flag if ANY is
                // dangerous. Must NOT use containsKey-chaining: ShellTool resolves via null-coalescing,
                // so {"command":null,"input":"format C:"} would otherwise read the present-but-null
                // "command", see cmd==null, and slip the dangerous command past the gate.
                for (String alias : new String[]{"command", "query", "input"}) {
                    Object v = parameters.get(alias);
                    if (v != null && DangerousCommands.isDangerous(v.toString())) {
                        perm = ToolPermission.DANGEROUS_WRITE;
                        dangerousShell = true;
                        break;
                    }
                }
            }
            AiExecutionPolicy.Decision decision = policy.check(perm);
            if (decision == AiExecutionPolicy.Decision.BLOCK) {
                return ToolExecutionResultMessage.from(request,
                        "Error: blocked by the current approval mode. Ask the user to switch to a "
                                + "less restrictive mode, or accomplish this a safer way.");
            }
            if (decision == AiExecutionPolicy.Decision.ASK) {
                boolean approved = confirmHandler != null
                        && confirmHandler.confirm(tool.getName(), summarizeForConfirm(tool.getName(), parameters));
                if (!approved) {
                    return ToolExecutionResultMessage.from(request,
                            "Error: the user declined to confirm this operation. Do not retry it; "
                                    + "suggest an alternative or ask what they would prefer.");
                }
            } else if (dangerousShell && confirmHandler != null) {
                // A dangerous shell command (format / dd / mkfs / fork bomb / recursive delete, incl.
                // base64-encoded) ALWAYS requires explicit confirmation — even in YOLO, where the
                // policy would otherwise auto-allow. The ONLY bypass is the developer "dangerously
                // skip permissions" toggle, which nulls confirmHandler (so this branch is skipped).
                if (!confirmHandler.confirm(tool.getName(), summarizeForConfirm(tool.getName(), parameters))) {
                    return ToolExecutionResultMessage.from(request,
                            "Error: the user declined to confirm this dangerous command. Do not retry it; "
                                    + "suggest a safer alternative or ask what they would prefer.");
                }
            }

            // Second, distinct gate: CRITICAL/catastrophic ops (delete world/instance, NBT/save
            // writes, deleting files under saves/playerdata/backups) get a RED confirmation right
            // before execution — IN ADDITION to the normal one above, and even in YOLO mode.
            String criticalReason = CriticalOperations.criticalReason(tool.getName(), request.arguments());
            if (criticalReason != null && criticalConfirmHandler != null) {
                String summary = criticalReason + "\n\n" + summarizeForConfirm(tool.getName(), parameters);
                if (!criticalConfirmHandler.confirm(tool.getName(), summary)) {
                    return ToolExecutionResultMessage.from(request,
                            "Error: the user declined this CRITICAL operation at the safety prompt. "
                                    + "Do NOT retry it; explain the risk and ask how they want to proceed.");
                }
            }

            // Background dispatch: long-running tools (installs / downloads / backups) run as a
            // job so the chat stays usable. The model can force it with background=true or opt out
            // with background=false. All confirm/critical gates above already ran synchronously, so
            // the user has approved before the work is detached. The model polls with check_job.
            if (wantsBackground(tool.getName(), parameters)) {
                final Tool bgTool = tool;
                final Map<String, Object> bgParams = parameters;
                String label = summarizeForConfirm(tool.getName(), parameters);
                String jobId = org.jackhuang.hmcl.ai.tools.AiJobManager.getInstance()
                        .submit(sessionId, tool.getName(), label, () -> bgTool.execute(bgParams));
                return ToolExecutionResultMessage.from(request,
                        "已在后台开始执行（任务 #" + jobId + "：" + tool.getName() + "）。聊天不会被占用，"
                                + "你可以继续做别的事；之后用 check_job(jobId=\"" + jobId + "\") 查看结果，"
                                + "用 list_jobs 看全部任务、cancel_job 取消。"
                                + "重要：在 check_job 显示该任务已完成之前，绝不要声称它已完成。");
            }

            ToolResult result = tool.execute(parameters);
            if (result == null) {
                text = "Error: tool '" + request.name()
                        + "' returned no result";
            } else if (result.isSuccess()) {
                text = result.getOutput();
            } else {
                text = "Error: " + result.getError();
            }
        } catch (Throwable t) {
            // Catch Throwable, not just Exception: optional dependencies (e.g. OCR) can throw an
            // Error such as NoClassDefFoundError, which is NOT an Exception. If one escaped, this
            // assistant tool-use request would get no matching tool-result, leaving the agent loop
            // (and the UI Stop button) wedged forever. Always turn any failure into an "Error:"
            // tool-result so the loop keeps a valid, non-null result it can feed back to the model.
            // Never swallow interruption: restore the interrupt flag so the loop's Stop still works.
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = t.getMessage();
            text = "Error: " + (message != null && !message.isBlank()
                    ? message
                    : t.getClass().getSimpleName());
        }

        return ToolExecutionResultMessage.from(request, text);
    }

    /// Tools whose work is inherently long-running (download / install / backup / export) and
    /// therefore default to running as a background job, so a single slow operation never freezes
    /// the chat. The model can still opt out per call with {@code background=false}.
    private static final java.util.Set<String> LONG_TASK_TOOLS = java.util.Set.of(
            "install_loader", "install_mod", "install_modpack", "install_resourcepack",
            "install_shader", "install_datapack", "download_java", "export_modpack",
            "create_world_backup", "restore_world_backup", "import_world");

    /// Decides whether a tool call should run in the background: an explicit {@code background}
    /// argument (boolean or "true"/"false"/"yes"/"no"/"1"/"0") wins; otherwise long-task tools
    /// default to background and everything else runs inline.
    private static boolean wantsBackground(String toolName, Map<String, Object> parameters) {
        Object b = parameters.get("background");
        if (b instanceof Boolean) {
            return (Boolean) b;
        }
        if (b instanceof String) {
            String s = ((String) b).trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("true") || s.equals("yes") || s.equals("1")) {
                return true;
            }
            if (s.equals("false") || s.equals("no") || s.equals("0")) {
                return false;
            }
        }
        return LONG_TASK_TOOLS.contains(toolName);
    }

    /// Parses a JSON arguments string into a flat parameter map.
    ///
    /// This implementation returns an empty map, simplifying parameter
    /// passing for the MVP. Future iterations may implement full JSON
    /// deserialization.
    ///
    /// @param argumentsJson the JSON string from the tool execution request
    /// @return a parameter map; never {@code null}
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final java.lang.reflect.Type MAP_TYPE =
            new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType();

    /// Builds a short human-readable summary of a pending tool call for the confirm dialog.
    private static String summarizeForConfirm(String toolName, Map<String, Object> params) {
        Object cmd = params.containsKey("command") ? params.get("command") : params.get("query");
        String detail = cmd != null ? cmd.toString() : params.toString();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        // Show the model's plain-language description FIRST (the user likely can't read the raw
        // command); the technical detail follows as secondary context.
        Object desc = params.get("description");
        if (desc != null && !desc.toString().isBlank()) {
            return desc + "\n\n（" + toolName + "：" + detail + "）";
        }
        return toolName + ": " + detail;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> result = GSON.fromJson(argumentsJson, MAP_TYPE);
            if (result == null) {
                return Collections.emptyMap();
            }
            // Models often pack a whole JSON object into the single advertised "query"
            // parameter (for tools that don't publish a structured schema). Unwrap it so
            // multi-parameter tools still receive their real keys (action, newName, id, ...).
            Object q = result.get("query");
            if (q instanceof String s) {
                String t = s.trim();
                if (t.startsWith("{") && t.endsWith("}")) {
                    try {
                        Map<String, Object> inner = GSON.fromJson(t, MAP_TYPE);
                        if (inner != null) {
                            for (Map.Entry<String, Object> e : inner.entrySet()) {
                                result.putIfAbsent(e.getKey(), e.getValue());
                            }
                        }
                    } catch (com.google.gson.JsonParseException ignored) {
                        // not a JSON object — leave "query" untouched
                    }
                }
            }
            return result;
        } catch (com.google.gson.JsonParseException e) {
            return Collections.singletonMap("query", argumentsJson);
        }
    }
}
