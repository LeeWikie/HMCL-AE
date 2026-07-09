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
import org.jackhuang.hmcl.ai.tools.AiToolPermissionStore;
import org.jackhuang.hmcl.ai.tools.CriticalOperations;
import org.jackhuang.hmcl.ai.tools.DangerousCommands;
import org.jackhuang.hmcl.ai.tools.ShellToolOverlap;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolConfirmHandler;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

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
    /// Per-tool/action (and, for path-taking tools, per-path-glob) permission overrides — see
    /// {@link AiToolPermissionStore}. {@code null} means "no store wired in", i.e. every call
    /// resolves purely from the fixed {@link #policy} (the pre-Part-A behaviour).
    @Nullable
    private final AiToolPermissionStore permissionStore;
    /// Reports whether Plan Mode is currently active, re-queried on EVERY call (not captured once
    /// at construction) since one adapter instance is reused for a session's whole lifetime while
    /// the user can toggle Plan Mode at any time. {@code null} means "no Plan Mode concept for this
    /// caller" (e.g. tests), equivalent to it always being off.
    @Nullable
    private final BooleanSupplier planModeSupplier;
    /// Reports whether the CURRENT turn may be running unattended (nobody necessarily watching to
    /// answer a confirmation prompt — see {@link AiExecutionPolicy}'s class doc). Re-queried on
    /// EVERY call for the same reason {@link #planModeSupplier} is: one adapter instance spans a
    /// session's whole lifetime, and whether a given turn is a direct user message or a synthetic
    /// auto-continuation changes turn to turn. {@code null} means "no such concept for this caller"
    /// (e.g. tests), equivalent to it always being attended.
    @Nullable
    private final BooleanSupplier unattendedSupplier;

    /// Id of the chat session this adapter serves, stamped onto background jobs so their
    /// completion can be routed back to the right conversation for auto-continuation.
    @Nullable
    private volatile String sessionId;

    /// Creates an adapter with no safety enforcement (allow-all). Used by tests and
    /// callers that gate elsewhere.
    public LangChain4jToolAdapter(ToolRegistry registry) {
        this(registry, new AiExecutionPolicy(org.jackhuang.hmcl.ai.AiApprovalMode.AUTO, false), null);
    }

    /// Creates an adapter that enforces {@code policy}, asking {@code confirmHandler} to
    /// confirm any operation the policy marks ASK (e.g. dangerous shell commands).
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler) {
        this(registry, policy, confirmHandler, null);
    }

    /// Adds a separate {@code criticalConfirmHandler} for the red second-tier confirmation of
    /// catastrophic operations. Equivalent to passing {@code null} for both the per-tool
    /// permission store and the Plan Mode supplier (see the full constructor below).
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler,
                                  @Nullable ToolConfirmHandler criticalConfirmHandler) {
        this(registry, policy, confirmHandler, criticalConfirmHandler, null, null);
    }

    /// Adds the per-tool/action/path {@code permissionStore} override (see
    /// {@link AiToolPermissionStore}) and a {@code planModeSupplier} consulted by {@link #execute}
    /// on every call so a per-call effective decision can be resolved instead of always using the
    /// fixed instance built once at agent-construction time. Equivalent to passing {@code null} for
    /// the unattended supplier (see the full constructor below) — i.e. every turn is treated as
    /// attended.
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler,
                                  @Nullable ToolConfirmHandler criticalConfirmHandler,
                                  @Nullable AiToolPermissionStore permissionStore,
                                  @Nullable BooleanSupplier planModeSupplier) {
        this(registry, policy, confirmHandler, criticalConfirmHandler, permissionStore, planModeSupplier, null);
    }

    /// Full constructor: adds an {@code unattendedSupplier} consulted by {@link #execute} on every
    /// call, so a DANGEROUS_WRITE (or CRITICAL) call is hard-blocked instead of merely asked while
    /// the current turn may be running with nobody watching — see {@link AiExecutionPolicy}'s class
    /// doc for why this cannot be relaxed by mode/toggle/per-tool-override.
    public LangChain4jToolAdapter(ToolRegistry registry, AiExecutionPolicy policy,
                                  @Nullable ToolConfirmHandler confirmHandler,
                                  @Nullable ToolConfirmHandler criticalConfirmHandler,
                                  @Nullable AiToolPermissionStore permissionStore,
                                  @Nullable BooleanSupplier planModeSupplier,
                                  @Nullable BooleanSupplier unattendedSupplier) {
        this.registry = registry;
        this.policy = policy;
        this.confirmHandler = confirmHandler;
        this.criticalConfirmHandler = criticalConfirmHandler;
        this.permissionStore = permissionStore;
        this.planModeSupplier = planModeSupplier;
        this.unattendedSupplier = unattendedSupplier;
    }

    /// Binds the chat session id used to tag background jobs (for completion routing).
    public void setSessionId(@Nullable String sessionId) {
        this.sessionId = sessionId;
    }

    /// Looks up a registered tool's declared {@link ToolPermission} for a SPECIFIC call, the same
    /// action-aware resolution used by {@link #execute}: {@link ToolSpec#getPermission(Map)} with
    /// this request's actual parsed arguments when the tool implements {@link ToolSpec}, otherwise
    /// {@link ToolPermission#CONTROLLED_WRITE} (unknown tools are treated as write-capable, never
    /// as safely-repeatable). Used by the chat adapter's loop guards to decide which tools need the
    /// exact-repeat PRE-execution block (writes) versus the general post-execution signature
    /// detector (everything else, including read-only polling like check_job).
    ///
    /// Deliberately takes the whole {@link ToolExecutionRequest} rather than just a tool name: the
    /// merged domain tools (search/instance/game/nbt/account/job) only override the parameterized
    /// {@link ToolSpec#getPermission(Map)} overload — action=list is READ_ONLY, action=delete isn't —
    /// and never override the no-arg {@link ToolSpec#getPermission()}, which defaults to
    /// {@code CONTROLLED_WRITE} and (per {@link ToolSpec}'s one-way delegation) is NOT called by the
    /// parameterized overload. Resolving by name alone would silently fall back to that conservative
    /// no-arg default for every one of those tools, regardless of the actual call's arguments.
    public ToolPermission resolvePermission(ToolExecutionRequest req) {
        Tool tool = registry.get(req.name());
        if (!(tool instanceof ToolSpec spec)) {
            return ToolPermission.CONTROLLED_WRITE;
        }
        try {
            return spec.getPermission(parseArguments(req.arguments()));
        } catch (ArgumentParseException e) {
            // Malformed args: fall back to the conservative no-arg default rather than guessing.
            return spec.getPermission();
        }
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

        Map<String, Object> parameters;
        try {
            parameters = parseArguments(request.arguments());
        } catch (ArgumentParseException e) {
            // Malformed JSON that isn't a plain string either — don't silently degrade it into a
            // {"query": "<garbage>"} map (that can feed corrupted values straight into a tool's real
            // parameters). Echo the tool's own schema back so a weak model can self-correct next turn.
            String schemaHint = (tool instanceof ToolSpec spec && spec.supportsStructuredSchema())
                    ? spec.getInputSchemaJson()
                    : "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}";
            return ToolExecutionResultMessage.from(request,
                    "Error: failed to parse tool arguments as JSON. Re-emit them exactly per this schema:\n"
                            + schemaHint);
        }

        String text;
        try {
            // Safety policy: block or confirm dangerous operations before running them. Uses the
            // action-aware overload so a merged domain tool (e.g. one action=list is READ_ONLY,
            // action=delete is DANGEROUS_WRITE) is gated by what THIS call actually does, not a
            // single permission for the whole tool.
            ToolPermission perm = (tool instanceof ToolSpec spec)
                    ? spec.getPermission(parameters) : ToolPermission.CONTROLLED_WRITE;

            // Resolved once, up front, and reused by every gate below (the per-tool/path override
            // lookup, the EditOrRemove/Plan-Mode-aware policy check, AND the CriticalOperations
            // gate further down) instead of re-deriving it from the raw JSON multiple times.
            Object resolvedActionObj = parameters.get("action");
            String resolvedAction = resolvedActionObj != null ? resolvedActionObj.toString() : null;
            Object resolvedPathObj = parameters.get("path");
            String resolvedPath = resolvedPathObj != null ? resolvedPathObj.toString() : null;

            boolean planMode = planModeSupplier != null && planModeSupplier.getAsBoolean();
            // Whether THIS turn may be running unattended (a synthetic auto-continuation, e.g. the
            // follow-up the agent fires on its own once a background job finishes, rather than a
            // turn triggered by a direct, just-now user message). Re-queried every call, same
            // reasoning as planMode above. See AiExecutionPolicy's class doc: this makes a
            // DANGEROUS_WRITE call BLOCK outright instead of merely asking, non-negotiably.
            boolean unattended = unattendedSupplier != null && unattendedSupplier.getAsBoolean();

            boolean dangerousShell = false;
            // Golden-rule overlap nudge (see ShellToolOverlap's class doc): non-null when the shell
            // command looks like a hand-rolled re-implementation of a dedicated tool (e.g. downloading
            // a mod jar straight from a CDN with curl/Invoke-WebRequest instead of mods_install). This
            // is deliberately NOT folded into `perm`/DANGEROUS_WRITE — shell remains a legitimate last
            // resort — it only (a) gets prepended to the confirm summary below and (b) forces the
            // confirm dialog even when the base policy/permission would otherwise auto-allow, exactly
            // like dangerousShell/mcpTool already do.
            String shellOverlapReason = null;
            if ("shell".equals(tool.getName())) {
                // Scan EVERY alias ShellTool actually reads (command, query, input) for BOTH signals.
                // Must NOT use containsKey-chaining: ShellTool resolves via null-coalescing, so
                // {"command":null,"input":"format C:"} would otherwise read the present-but-null
                // "command", see cmd==null, and slip the dangerous command past the gate.
                for (String alias : new String[]{"command", "query", "input"}) {
                    Object v = parameters.get(alias);
                    if (v == null) {
                        continue;
                    }
                    String aliasValue = v.toString();
                    if (DangerousCommands.isDangerous(aliasValue)) {
                        perm = ToolPermission.DANGEROUS_WRITE;
                        dangerousShell = true;
                    }
                    if (shellOverlapReason == null) {
                        shellOverlapReason = ShellToolOverlap.overlapReason(aliasValue);
                    }
                }
            }
            // MCP tools are external, user-configured server code of unknown effect (see
            // McpToolStub) — like dangerousShell below, they get a policy-independent force-confirm
            // gate, not just the DANGEROUS_WRITE permission classification alone (which Auto would
            // still auto-allow if the user disabled "dangerous confirmation").
            boolean mcpTool = tool instanceof ToolSpec mcpSpec && mcpSpec.getSource() == ToolSource.MCP;
            // Full decision: aware of the real tool name + resolved action (so the EditOrRemove
            // create-vs-edit/remove split applies — see AiExecutionPolicy.check's javadoc), of
            // whether Plan Mode is currently active (so a CONTROLLED_WRITE/DANGEROUS_WRITE call is
            // BLOCKed outright while investigating, never merely asked — see
            // AIMainPage.applyPlanGating's own doc for why the tool is no longer wholesale-disabled
            // for domain facades whose actions span multiple permission levels), and of whether the
            // turn may be unattended (DANGEROUS_WRITE then BLOCKs outright too, see above).
            AiExecutionPolicy.Decision decision = policy.check(tool.getName(), resolvedAction, perm, planMode, unattended);
            // Part A: a per-tool/action (and, for path-taking tools, per-path-glob) override from
            // AiToolPermissionStore is applied AFTER the base decision, never able to relax a
            // non-negotiable BLOCK (Plan Mode, or unattended DANGEROUS_WRITE) — see
            // AiToolPermissionStore.OverrideMode#apply's own doc. FOLLOW_GLOBAL (the default for
            // every tool the user hasn't customised, and the case when no store is wired in at all)
            // passes the base decision through unchanged.
            if (permissionStore != null) {
                AiToolPermissionStore.OverrideMode override =
                        permissionStore.getOverride(tool.getName(), resolvedAction, resolvedPath);
                decision = override.apply(decision, perm);
            }
            if (decision == AiExecutionPolicy.Decision.BLOCK) {
                return ToolExecutionResultMessage.from(request,
                        "Error: blocked — this call is either write-capable while Plan Mode is "
                                + "active, or a dangerous operation while the current turn may be "
                                + "running unattended (nobody is necessarily here to approve it). "
                                + "Wait for a real user turn, or accomplish this a safer way.");
            }
            if (decision == AiExecutionPolicy.Decision.ASK) {
                boolean approved = confirmHandler != null
                        && confirmHandler.confirm(tool.getName(),
                                confirmSummaryWithOverlap(tool.getName(), parameters, shellOverlapReason));
                if (!approved) {
                    return ToolExecutionResultMessage.from(request,
                            "Error: the user declined to confirm this operation. Do not retry it; "
                                    + "suggest an alternative or ask what they would prefer.");
                }
            } else if ((dangerousShell || mcpTool || shellOverlapReason != null) && confirmHandler != null) {
                // A dangerous shell command (format / dd / mkfs / fork bomb / recursive delete, incl.
                // base64-encoded), any MCP tool call, OR a shell command that overlaps a dedicated
                // tool's job (see ShellToolOverlap) ALWAYS requires explicit confirmation — even when
                // the policy would otherwise auto-allow every permission level unconditionally
                // (dangerous confirmation disabled). The ONLY bypass is the developer "dangerously
                // skip permissions" toggle, which nulls confirmHandler (so this branch is skipped)
                // — identical to the shell case. (An unattended turn never reaches this branch at
                // all: dangerousShell/mcpTool resolve DANGEROUS_WRITE, so the BLOCK above already
                // fired; the overlap-only case is not DANGEROUS_WRITE, so it is NOT subject to that
                // block — see ShellToolOverlap's class doc, this is a nudge, not a gate.)
                if (!confirmHandler.confirm(tool.getName(),
                        confirmSummaryWithOverlap(tool.getName(), parameters, shellOverlapReason))) {
                    return ToolExecutionResultMessage.from(request,
                            "Error: the user declined to confirm this operation. Do not retry it; "
                                    + "suggest a safer alternative or ask what they would prefer.");
                }
            }

            // Second, distinct gate: CRITICAL/catastrophic ops (delete world/instance, NBT/save
            // writes, deleting files under saves/playerdata/backups) get a RED confirmation right
            // before execution — IN ADDITION to the normal one above. Pass the already-unwrapped
            // `action` from `parameters` (not a re-derivation from the raw JSON): a call shaped like
            // {"query":"{\"action\":\"delete\",...}"} is unwrapped by parseArguments() above for the
            // normal permission gate, but CriticalOperations' own extractAction() only ever looks at
            // a TOP-LEVEL `action` key in the RAW json — without this, such a call would silently
            // skip the red CRITICAL confirmation.
            String criticalReason = CriticalOperations.criticalReason(
                    tool.getName(), resolvedAction, request.arguments());
            if (criticalReason != null) {
                if (unattended) {
                    // Same non-negotiable rule as the DANGEROUS_WRITE BLOCK above, applied to the
                    // (narrower, but even more catastrophic) CRITICAL classification too: a call
                    // that reaches this gate is refused outright while the turn may be unattended,
                    // never merely asked — see AiExecutionPolicy's class doc.
                    return ToolExecutionResultMessage.from(request,
                            "Error: blocked — this is a CRITICAL operation (" + criticalReason + ") and "
                                    + "the current turn may be running unattended, so it cannot be "
                                    + "auto-approved. Wait for a real user turn, or ask them to run it "
                                    + "directly.");
                }
                if (criticalConfirmHandler != null) {
                    String summary = criticalReason + "\n\n" + summarizeForConfirm(tool.getName(), parameters);
                    if (!criticalConfirmHandler.confirm(tool.getName(), summary)) {
                        return ToolExecutionResultMessage.from(request,
                                "Error: the user declined this CRITICAL operation at the safety prompt. "
                                        + "Do NOT retry it; explain the risk and ask how they want to proceed.");
                    }
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
                                + "你可以继续做别的事；之后用 job(action=\"check\", jobId=\"" + jobId + "\") 查看结果，"
                                + "用 job(action=\"list\") 看全部任务、job(action=\"cancel\", jobId=\"" + jobId
                                + "\") 取消。"
                                + "重要：在 job(action=\"check\") 显示该任务已完成之前，绝不要声称它已完成。"
                                + "如果想在下一条回复里嵌入这个任务的实时进度，可以在文本中写 {{job_progress:" + jobId
                                + "}}，界面会自动动态更新，无需你再发新消息。");
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

    /// Tool name → long-running action set, mirroring {@code CriticalOperations.CRITICAL_ACTIONS}'
    /// shape: an EMPTY set means the whole tool is inherently long-running (download / install /
    /// backup / export) and defaults to a background job so a single slow operation never freezes
    /// the chat; a NON-EMPTY set scopes that default to only those `action` values, for domain
    /// tools merged from several single-purpose tools (a future `instance` tool's action=create
    /// should default to background, action=list shouldn't). The model can always opt out per call
    /// with {@code background=false} regardless of which case applies.
    private static final Map<String, java.util.Set<String>> LONG_TASK_ACTIONS = Map.ofEntries(
            // These used to be separate tools (each its own whole-tool entry); now they're
            // actions on the merged `instance` domain tool — see the tool-domain-merge plan.
            Map.entry("instance", java.util.Set.of(
                    "create", "mods_install", "modpacks_install", "resourcepacks_install",
                    "shaders_install", "worlds_datapacks_install", "download_java", "modpacks_export",
                    "worlds_backup_create", "worlds_backup_restore", "worlds_import")));

    /// Decides whether a tool call should run in the background: an explicit {@code background}
    /// argument (boolean or "true"/"false"/"yes"/"no"/"1"/"0") wins; otherwise long-task tools
    /// (or, for a merged domain tool, long-task actions of it) default to background and everything
    /// else runs inline.
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
        java.util.Set<String> actions = LONG_TASK_ACTIONS.get(toolName);
        if (actions == null) {
            return false;
        }
        if (actions.isEmpty()) {
            return true; // whole-tool default, action-agnostic
        }
        Object action = parameters.get("action");
        return action != null && actions.contains(action.toString());
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

    /// Prefixes the golden-rule overlap nudge (see {@link ShellToolOverlap}) onto the normal confirm
    /// summary, when present — the SAME front-prefixing shape the CRITICAL gate below already uses
    /// for its own {@code criticalReason} (`criticalReason + "\n\n" + summarizeForConfirm(...)`).
    /// {@code shellOverlapReason} is {@code null} for every non-shell tool and for a shell command
    /// that doesn't overlap a dedicated tool, in which case this is exactly {@link #summarizeForConfirm}.
    private static String confirmSummaryWithOverlap(String toolName, Map<String, Object> params,
                                                      @Nullable String shellOverlapReason) {
        String summary = summarizeForConfirm(toolName, params);
        return shellOverlapReason != null ? shellOverlapReason + "\n\n" + summary : summary;
    }

    /// Builds a short human-readable summary of a pending tool call for the confirm dialog.
    ///
    /// PRODUCT DECISION: the headline (first line) is ALWAYS the real, deterministic tool+action
    /// name — NEVER the model-supplied `description` parameter. {@link CriticalOperations}'s red
    /// second-tier confirmation already follows this rule (its reason string is a hardcoded,
    /// non-model-controllable switch on tool+action, never the model's text); this applies the SAME
    /// rule here, for the ordinary DANGEROUS_WRITE/CONTROLLED_WRITE confirm too. Without it, a
    /// poisoned mod/skill/search-result `description` could otherwise write a reassuring false
    /// headline in front of a genuinely destructive call — a prompt-injection-adjacent risk. The
    /// model's `description`, when present, is still shown as helpful context, but demoted to a
    /// clearly-labelled line BELOW the headline, never able to replace or precede it.
    private static String summarizeForConfirm(String toolName, Map<String, Object> params) {
        Object actionObj = params.get("action");
        String action = actionObj != null ? actionObj.toString().trim() : null;

        Object cmd = params.containsKey("command") ? params.get("command") : params.get("query");
        String detail = cmd != null ? cmd.toString() : params.toString();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }

        String qualifiedName = (action != null && !action.isBlank()) ? toolName + "." + action : toolName;
        StringBuilder sb = new StringBuilder("确认: ").append(describeAction(toolName, action))
                .append(" (").append(qualifiedName).append(')');

        Object desc = params.get("description");
        if (desc != null && !desc.toString().isBlank()) {
            // Model-supplied context ONLY — informational, never the headline (see method doc).
            sb.append("\n\n模型说明：").append(desc);
        }
        sb.append("\n\n（").append(toolName).append("：").append(detail).append("）");
        return sb.toString();
    }

    /// Hardcoded, non-model-controllable human-readable label for a tool+action pair — used ONLY
    /// for the confirm-dialog headline built by {@link #summarizeForConfirm}. Every branch here is
    /// a literal switch on {@code toolName}/{@code action}, never on any model-supplied parameter,
    /// so a poisoned tool argument (e.g. a mod's `description`) cannot influence the wording. Falls
    /// back to the raw (still fully deterministic) tool/action name when no curated phrase exists
    /// for a given pair.
    private static String describeAction(String toolName, @Nullable String action) {
        String a = action != null ? action.toLowerCase(Locale.ROOT) : "";
        return switch (toolName) {
            case "instance" -> switch (a) {
                case "delete" -> "删除实例";
                case "create" -> "创建实例";
                case "rename" -> "重命名实例";
                case "mods_delete" -> "删除模组";
                case "mods_update" -> "更新模组";
                case "mods_install" -> "安装模组";
                case "mods_toggle" -> "启用/禁用模组";
                case "modpacks_install" -> "安装整合包";
                case "modpacks_export" -> "导出整合包";
                case "resourcepacks_install" -> "安装资源包";
                case "shaders_install" -> "安装光影包";
                case "worlds_delete" -> "删除存档";
                case "worlds_import" -> "导入存档";
                case "worlds_backup_create" -> "创建存档备份";
                case "worlds_backup_restore" -> "恢复存档备份";
                case "worlds_datapacks_install" -> "安装数据包";
                case "download_java" -> "下载 Java";
                case "set_memory" -> "修改内存分配";
                case "set_option" -> "修改实例选项";
                case "set_isolation" -> "修改实例隔离设置";
                case "clean_logs" -> "清理日志";
                default -> a.isEmpty() ? "执行实例操作" : "执行实例操作 (" + a + ")";
            };
            case "nbt" -> switch (a) {
                case "set" -> "修改 NBT 数据";
                case "copy_player_data" -> "覆盖玩家数据";
                case "transfer_inventory" -> "转移物品栏数据";
                default -> a.isEmpty() ? "执行 NBT 操作" : "执行 NBT 操作 (" + a + ")";
            };
            case "account" -> switch (a) {
                case "set_skin" -> "修改账号皮肤";
                case "add_offline" -> "添加离线账号";
                case "select" -> "切换账号";
                default -> a.isEmpty() ? "执行账号操作" : "执行账号操作 (" + a + ")";
            };
            case "game" -> switch (a) {
                case "launch" -> "启动游戏";
                case "stop" -> "停止游戏";
                default -> a.isEmpty() ? "执行游戏操作" : "执行游戏操作 (" + a + ")";
            };
            case "job" -> "cancel".equals(a) ? "取消后台任务" : "执行任务操作";
            case "write" -> "写入文件";
            case "edit" -> "编辑文件";
            case "shell" -> "执行终端命令";
            default -> a.isEmpty() ? ("执行 " + toolName) : ("执行 " + toolName + "." + a);
        };
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
            // Genuinely malformed JSON (not just "a plain string instead of an object") — surface
            // this as a distinct failure so execute() can teach the model the schema instead of
            // silently handing the raw garbage to a tool as its "query" parameter.
            throw new ArgumentParseException(argumentsJson, e);
        }
    }

    /// Signals that {@link #parseArguments} received text that isn't valid JSON at all (as opposed
    /// to valid JSON that just doesn't unwrap into a nested "query" object). Caught by
    /// {@link #execute} to return a schema-echo error instead of executing with corrupted arguments.
    private static final class ArgumentParseException extends RuntimeException {
        ArgumentParseException(String argumentsJson, Throwable cause) {
            super("Malformed tool arguments JSON: " + argumentsJson, cause);
        }
    }
}
