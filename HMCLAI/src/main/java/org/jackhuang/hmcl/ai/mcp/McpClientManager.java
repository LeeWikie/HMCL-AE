package org.jackhuang.hmcl.ai.mcp;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.util.AiLog;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/// Manages MCP server connections and registers discovered tools as
/// HMCL tools (with {@link ToolSource#MCP} metadata).
///
/// Tool names are prefixed `mcp.<serverId>.` to avoid collisions.
/// An optional allowlist defined in {@link AiMcpServerConfig#getAllowedTools()}
/// limits which discovered tools are actually registered.
@NotNullByDefault
public final class McpClientManager {

    private static final Gson GSON = new Gson();

    private final ToolRegistry registry;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<Tool>> serverTools = new ConcurrentHashMap<>();

    public McpClientManager(ToolRegistry registry) {
        this.registry = registry;
    }

    /// Outcome of a connect-and-register attempt. Replaces the old bare {@code boolean} so a
    /// failure no longer vanishes into a silent {@code return false} (borrow-list B8 / rewrite #16):
    /// the host can surface {@link #failureReason} in AI 设置 (e.g. as the server's {@code lastStatus}),
    /// and the model — should it later call a tool from this server — gets the precise
    /// "not connected" envelope registered as an {@code mcp.*} placeholder (rewrite #17), not a
    /// generic "tool not found".
    ///
    /// @param success       whether the server connected AND its tools were discovered/registered
    /// @param failureReason a host-facing failure envelope when {@link #success} is false; null otherwise
    public record ConnectResult(boolean success, @Nullable String failureReason) {
        /// A successful connect+discovery.
        public static ConnectResult ok() {
            return new ConnectResult(true, null);
        }

        /// A failed attempt carrying a host-facing failure envelope (never blank).
        public static ConnectResult failed(String failureReason) {
            return new ConnectResult(false, failureReason);
        }
    }

    /// Connects to a local MCP server via stdio and discovers tools.
    public ConnectResult connectAndRegister(AiMcpServerConfig config) {
        String serverId = config.getId();
        if (clients.containsKey(serverId)) return ConnectResult.ok();
        String cmd = config.getCommand();
        if (cmd == null || cmd.isEmpty()) {
            return fail(serverId, connectFailureEnvelope(serverId, "no launch command configured"));
        }
        try {
            McpClient client = DefaultMcpClient.builder()
                    .transport(StdioMcpTransport.builder()
                            .command(buildCommandLine(cmd, config.getArgs()))
                            .environment(config.getEnv())
                            .logEvents(false)
                            .build())
                    .build();
            clients.put(serverId, client);
            String discoveryFailure = discoverAndRegister(serverId, config.getAllowedTools());
            if (discoveryFailure != null) {
                return fail(serverId, discoveryFailure);
            }
            registry.clearMcpServerFailed(serverId);
            return ConnectResult.ok();
        } catch (Exception e) {
            return fail(serverId, connectFailureEnvelope(serverId, describe(e)));
        }
    }

    /// Builds the full argv for the stdio child process: the configured executable followed by
    /// its explicit {@link AiMcpServerConfig#getArgs() args}.
    ///
    /// Falls back to a naive whitespace split of {@code command} only when no args are configured,
    /// for backward compatibility with configs saved before {@code args}/{@code env} existed, where
    /// the whole command line (executable and its arguments together) was crammed into one string.
    ///
    /// Package-private (not {@code private}) so {@code McpClientManagerTest} can exercise both
    /// branches directly without spawning a process.
    static List<String> buildCommandLine(String command, List<String> args) {
        if (args.isEmpty()) {
            return List.of(command.trim().split("\\s+"));
        }
        List<String> commandLine = new ArrayList<>(args.size() + 1);
        commandLine.add(command);
        commandLine.addAll(args);
        return commandLine;
    }

    /// Connects to a remote MCP server via HTTP and discovers tools.
    public ConnectResult connectHttpAndRegister(AiMcpServerConfig config) {
        String serverId = config.getId();
        if (clients.containsKey(serverId)) return ConnectResult.ok();
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            return fail(serverId, connectFailureEnvelope(serverId, "no server URL configured"));
        }
        try {
            McpClient client = DefaultMcpClient.builder()
                    .transport(StreamableHttpMcpTransport.builder()
                            .url(url)
                            .logRequests(false)
                            .logResponses(false)
                            .build())
                    .build();
            clients.put(serverId, client);
            String discoveryFailure = discoverAndRegister(serverId, config.getAllowedTools());
            if (discoveryFailure != null) {
                return fail(serverId, discoveryFailure);
            }
            registry.clearMcpServerFailed(serverId);
            return ConnectResult.ok();
        } catch (Exception e) {
            return fail(serverId, connectFailureEnvelope(serverId, describe(e)));
        }
    }

    /// Records the failed server so a later {@code mcp.<serverId>.*} call returns the rewrite-#17
    /// placeholder envelope, and returns the host-facing {@code failureReason} for the UI.
    private ConnectResult fail(String serverId, String hostReason) {
        registry.markMcpServerFailed(serverId, unavailableToolEnvelope(serverId));
        return ConnectResult.failed(hostReason);
    }

    /// Model-visible placeholder (rewrite #17) registered against a failed MCP server: a call to any
    /// unregistered {@code mcp.<serverId>.*} tool resolves to this instead of the generic
    /// "tool not found", so the model learns the server is down rather than that it misremembered a name.
    private static String unavailableToolEnvelope(String serverId) {
        return ToolFailures.failureEnvelope(
                "MCP server '" + serverId + "' is not connected (connection or tool-discovery failed)",
                ToolFailures.Retryable.LATER,
                "this tool is unavailable until the user reconnects it in AI 设置");
    }

    /// Host/UI-facing failure envelope (rewrite #16) for a connection failure, carrying the concrete
    /// cause ({@code detail}) so the reason is no longer swallowed by a bare {@code return false}.
    private static String connectFailureEnvelope(String serverId, String detail) {
        return ToolFailures.failureEnvelope(
                "MCP server '" + serverId + "' failed to connect: " + detail,
                ToolFailures.Retryable.LATER,
                "check whether the server command/URL/auth is correct, then reconnect in AI 设置",
                "this server's tools are unavailable until reconnected");
    }

    /// Host/UI-facing failure envelope for a server whose transport connected but whose tool
    /// discovery (`listTools`) failed — the socket is up but no tools could be registered.
    private static String discoveryFailureEnvelope(String serverId, String detail) {
        return ToolFailures.failureEnvelope(
                "MCP server '" + serverId + "' connected but tool discovery failed: " + detail,
                ToolFailures.Retryable.LATER,
                "check whether the server command/URL/auth is correct, then reconnect in AI 设置",
                "this server's tools are unavailable until reconnected");
    }

    /// {@code SimpleClassName: message} (or just the class name when the throwable has no message).
    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /// Discovers the server's tools and registers them as HMCL tool stubs.
    ///
    /// @return {@code null} on success; otherwise a host-facing failure envelope describing why
    ///         discovery failed (previously this swallowed the exception with a bare
    ///         {@code catch (Exception ignored)} that did not even log — borrow-list B8 / rewrite #17).
    @Nullable
    private String discoverAndRegister(String serverId, List<String> allowlist) {
        McpClient client = clients.get(serverId);
        if (client == null) return discoveryFailureEnvelope(serverId, "no active client for this server");

        // Remove previously registered stubs for this server
        List<Tool> old = serverTools.getOrDefault(serverId, List.of());
        for (Tool t : old) {
            registry.disable(t.getName());
        }

        List<Tool> registered = new ArrayList<>();
        String failure = null;
        try {
            var specs = client.listTools();
            Set<String> allowed = allowlist.isEmpty() ? null : new HashSet<>(allowlist);
            for (var spec : specs) {
                String toolName = spec.name();
                if (allowed != null && !allowed.contains(toolName)) continue;
                String prefixed = "mcp." + serverId + "." + toolName;
                McpToolStub stub = new McpToolStub(client, serverId, toolName,
                        spec.description() != null ? spec.description() : "MCP tool " + toolName,
                        schemaJsonOf(spec));
                // Disable-then-register-then-enable: the disable call here only matters on
                // RE-discovery (clearing whatever state a previous registration left), but without
                // the matching enable() afterward, EVERY newly discovered tool — including on the
                // very first connection — was left permanently disabled (ToolRegistry.register()
                // never touches the disabled set on its own), so no MCP tool could ever appear in
                // the model's tool manifest or be called at all.
                registry.disable(prefixed);
                registry.register(stub);
                registry.enable(prefixed);
                registered.add(stub);
            }
        } catch (Exception e) {
            // Do NOT swallow silently: at least warn, and hand the reason back to the caller so the
            // server is marked failed (the model then gets rewrite #17 instead of "tool not found").
            AiLog.warn("MCP server '" + serverId + "' connected but tool discovery failed: " + e);
            failure = discoveryFailureEnvelope(serverId, describe(e));
        }
        serverTools.put(serverId, registered);
        return failure;
    }

    /// Serializes a discovered MCP tool's top-level input parameters into the flat JSON-Schema
    /// string the HMCL tool pipeline understands ({@code ToolSpec.getInputSchemaJson} →
    /// {@code LangChain4jToolAdapter.parseSchema}), so the model calls the tool with the right
    /// argument names instead of the fallback {@code "query"}. Returns null when the tool takes
    /// no parameters or the schema can't be read (caller falls back to no structured schema).
    @Nullable
    private static String schemaJsonOf(ToolSpecification spec) {
        try {
            JsonObjectSchema params = spec.parameters();
            if (params == null || params.properties() == null || params.properties().isEmpty()) {
                return null;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, JsonSchemaElement> e : params.properties().entrySet()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("type", jsonType(e.getValue()));
                props.put(e.getKey(), meta);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "object");
            root.put("properties", props);
            root.put("required", params.required() != null ? params.required() : List.of());
            return GSON.toJson(root);
        } catch (Exception e) {
            return null;
        }
    }

    /// Maps a langchain4j schema element to the JSON-Schema `type` keyword the flat parser reads
    /// (it only distinguishes integer/number/boolean; everything else is treated as a string).
    private static String jsonType(JsonSchemaElement el) {
        if (el instanceof JsonIntegerSchema) return "integer";
        if (el instanceof JsonNumberSchema) return "number";
        if (el instanceof JsonBooleanSchema) return "boolean";
        return "string";
    }

    public void disconnect(String serverId) {
        McpClient client = clients.remove(serverId);
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
        List<Tool> tools = serverTools.remove(serverId);
        if (tools != null) {
            for (Tool t : tools) {
                registry.disable(t.getName());
            }
        }
        // A deliberate disconnect is not a failure: drop any stale rewrite-#17 placeholder so a
        // future mcp.* call for this id is not answered as "connection failed".
        registry.clearMcpServerFailed(serverId);
    }

    public int connectedCount() {
        return clients.size();
    }

    public Map<String, McpClient> getClients() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(clients));
    }
}
