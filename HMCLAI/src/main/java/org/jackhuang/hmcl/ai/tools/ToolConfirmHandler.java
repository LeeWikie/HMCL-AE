package org.jackhuang.hmcl.ai.tools;

/// Callback supplied by the UI so the tool-execution layer can ask the user to
/// confirm a dangerous operation before it runs. Implementations block the
/// calling (agent) thread until the user decides and return {@code true} to
/// proceed, {@code false} to decline.
///
/// Implementations MAY also time out and fail safe to {@code false} if the user never responds
/// within a bounded window (the shipped UI implementation does, at 120s/180s) — in that case the
/// caller-facing outcome is currently indistinguishable from an explicit user decline (both surface
/// to the model as "the user declined to confirm this operation"), since there is no third state
/// for "no response / dialog dismissed without a decision".
@FunctionalInterface
public interface ToolConfirmHandler {
    boolean confirm(String toolName, String summary);
}
