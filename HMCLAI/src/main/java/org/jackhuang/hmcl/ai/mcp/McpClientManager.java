package org.jackhuang.hmcl.ai.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
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
            List<String> args = List.of(cmd.split("\\s+"));
            McpClient client = DefaultMcpClient.builder()
                    .transport(StdioMcpTransport.builder()
                            .command(args)
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
                McpToolStub stub = new McpToolStub(serverId, toolName,
                        spec.description() != null ? spec.description() : "MCP tool " + toolName);
                // remove old then re-register
                registry.disable(prefixed);
                registry.register(stub);
                registered.add(stub);
            }
        } catch (Exception ignored) {
            // discovery failed, leave stubs unregistered
        }
        serverTools.put(serverId, registered);
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
