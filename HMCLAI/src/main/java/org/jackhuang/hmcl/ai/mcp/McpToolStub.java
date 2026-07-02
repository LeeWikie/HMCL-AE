package org.jackhuang.hmcl.ai.mcp;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// Adapter that wraps one discovered MCP tool as an HMCL {@link Tool} and actually
/// executes it by delegating to the live langchain4j {@link McpClient}.
///
/// Names are prefixed `mcp.<serverId>.` in the HMCL registry to avoid collisions, but
/// the MCP server only knows its own bare {@code toolName}, so that is what we send on
/// {@link #execute}. The tool's input schema (discovered from the server) is exposed
/// through {@link #getInputSchemaJson()} so the model calls it with the right arguments.
@NotNullByDefault
final class McpToolStub implements ToolSpec {

    private static final Gson GSON = new Gson();

    private final McpClient client;
    private final String serverId;
    private final String toolName;
    private final String toolDescription;
    @Nullable
    private final String inputSchemaJson;

    McpToolStub(McpClient client, String serverId, String toolName, String toolDescription,
                @Nullable String inputSchemaJson) {
        this.client = client;
        this.serverId = serverId;
        this.toolName = toolName;
        this.toolDescription = toolDescription;
        this.inputSchemaJson = inputSchemaJson;
    }

    @Override
    public ToolPermission getPermission() {
        // An MCP tool is external, user-configured code of unknown effect: treat it as a
        // controlled write (never silently "read-only"), so ASK mode confirms it before running.
        return ToolPermission.CONTROLLED_WRITE;
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
    public boolean supportsStructuredSchema() {
        return inputSchemaJson != null;
    }

    @Override
    public String getInputSchemaJson() {
        return inputSchemaJson != null ? inputSchemaJson : ToolSpec.super.getInputSchemaJson();
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            String args = GSON.toJson(parameters != null ? parameters : Map.of());
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(args)
                    .build();
            // langchain4j (1.16) returns a ToolExecutionResult; we surface its text to the model.
            String result = client.executeTool(request).resultText();
            return ToolResult.success(result != null ? result : "");
        } catch (Exception e) {
            String msg = e.getMessage();
            return ToolResult.failure("MCP tool '" + toolName + "' on server '" + serverId
                    + "' failed: " + (msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName()));
        }
    }
}
