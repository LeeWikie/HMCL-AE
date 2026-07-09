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
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
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

    /// Connects to a local MCP server via stdio and discovers tools.
    public boolean connectAndRegister(AiMcpServerConfig config) {
        String serverId = config.getId();
        if (clients.containsKey(serverId)) return false;
        try {
            String cmd = config.getCommand();
            if (cmd == null || cmd.isEmpty()) return false;
            McpClient client = DefaultMcpClient.builder()
                    .transport(StdioMcpTransport.builder()
                            .command(buildCommandLine(cmd, config.getArgs()))
                            .environment(config.getEnv())
                            .logEvents(false)
                            .build())
                    .build();
            clients.put(serverId, client);
            discoverAndRegister(serverId, config.getAllowedTools());
            return true;
        } catch (Exception e) {
            return false;
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
    public boolean connectHttpAndRegister(AiMcpServerConfig config) {
        String serverId = config.getId();
        if (clients.containsKey(serverId)) return false;
        try {
            String url = config.getUrl();
            if (url == null || url.isEmpty()) return false;
            McpClient client = DefaultMcpClient.builder()
                    .transport(StreamableHttpMcpTransport.builder()
                            .url(url)
                            .logRequests(false)
                            .logResponses(false)
                            .build())
                    .build();
            clients.put(serverId, client);
            discoverAndRegister(serverId, config.getAllowedTools());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void discoverAndRegister(String serverId, List<String> allowlist) {
        McpClient client = clients.get(serverId);
        if (client == null) return;

        // Remove previously registered stubs for this server
        List<Tool> old = serverTools.getOrDefault(serverId, List.of());
        for (Tool t : old) {
            registry.disable(t.getName());
        }

        List<Tool> registered = new ArrayList<>();
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
        } catch (Exception ignored) {
            // discovery failed, leave stubs unregistered
        }
        serverTools.put(serverId, registered);
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
    }

    public int connectedCount() {
        return clients.size();
    }

    public Map<String, McpClient> getClients() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(clients));
    }
}
