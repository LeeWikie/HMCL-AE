package org.jackhuang.hmcl.ai.mcp;

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;

/// A read-only adapter that wraps one MCP tool definition as an HMCL
/// {@link Tool} (with safe metadata).  Real invocation would use
/// the MCP client; this placeholder reports that the tool is advertised
/// but that runtime execution through the MCP protocol will be wired later.
@NotNullByDefault
final class McpToolStub implements ToolSpec {

    private final String serverId;
    private final String toolName;
    private final String toolDescription;

    McpToolStub(String serverId, String toolName, String toolDescription) {
        this.serverId = serverId;
        this.toolName = toolName;
        this.toolDescription = toolDescription;
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.MCP;
    }

    @Override
    public String getName() {
        return "mcp." + serverId + "." + toolName;
    }

    @Override
    public String getDescription() {
        return "[MCP:" + serverId + "] " + toolDescription;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        return ToolResult.success("MCP tool " + toolName + " on server " + serverId + " received input, but runtime invocation is pending. Parameters: " + parameters);
    }
}
