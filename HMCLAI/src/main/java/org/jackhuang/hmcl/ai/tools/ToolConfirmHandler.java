package org.jackhuang.hmcl.ai.tools;

/// Callback supplied by the UI so the tool-execution layer can ask the user to
/// confirm a dangerous operation before it runs. Implementations block the
/// calling (agent) thread until the user decides and return {@code true} to
/// proceed, {@code false} to decline.
@FunctionalInterface
public interface ToolConfirmHandler {
    boolean confirm(String toolName, String summary);
}
