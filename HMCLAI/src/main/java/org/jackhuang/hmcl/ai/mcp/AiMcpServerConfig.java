package org.jackhuang.hmcl.ai.mcp;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/// Persistent descriptor for one configured MCP server.
@NotNullByDefault
public final class AiMcpServerConfig {

    @SerializedName("id")
    private String id;

    @SerializedName("displayName")
    private String displayName;

    @SerializedName("transport")
    private String transport; // "stdio" or "http"

    @SerializedName("command")
    @Nullable
    private String command;

    @SerializedName("url")
    @Nullable
    private String url;

    @SerializedName("enabled")
    private boolean enabled;

    @SerializedName("autoConnect")
    private boolean autoConnect;

    @SerializedName("allowedTools")
    private List<String> allowedTools;

    @SerializedName("exposeResourcesAsTools")
    private boolean exposeResourcesAsTools;

    @SerializedName("lastStatus")
    @Nullable
    private String lastStatus;

    public AiMcpServerConfig() {
        this.id = UUID.randomUUID().toString();
        this.displayName = "MCP Server";
        this.transport = "stdio";
        this.command = null;
        this.url = null;
        this.enabled = false;
        this.autoConnect = true;
        this.allowedTools = new ArrayList<>();
        this.exposeResourcesAsTools = false;
        this.lastStatus = null;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    @Nullable public String getCommand() { return command; }
    public void setCommand(@Nullable String command) { this.command = command; }

    @Nullable public String getUrl() { return url; }
    public void setUrl(@Nullable String url) { this.url = url; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = new ArrayList<>(allowedTools);
    }

    public boolean isExposeResourcesAsTools() { return exposeResourcesAsTools; }
    public void setExposeResourcesAsTools(boolean exposeResourcesAsTools) { this.exposeResourcesAsTools = exposeResourcesAsTools; }

    @Nullable public String getLastStatus() { return lastStatus; }
    public void setLastStatus(@Nullable String lastStatus) { this.lastStatus = lastStatus; }
}
