package org.jackhuang.hmcl.ai.tools;

import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Decides whether a tool invocation is allowed, given its {@link ToolPermission}, whether Plan
/// Mode is active, and whether the current turn may be running unattended.
///
/// # The Auto model
///
/// There is only one {@link AiApprovalMode} now (`AUTO` — see its own doc for the history of the
/// SAFE/ASK/YOLO merge this replaced). Its rules:
///
/// | Permission        | Attended                                   | Possibly unattended |
/// |-------------------|---------------------------------------------|----------------------|
/// | READ_ONLY         | allow                                        | allow                |
/// | EXTERNAL_NETWORK  | allow                                        | allow                |
/// | CONTROLLED_WRITE  | allow, unless the file-write-confirm toggle (or the create-vs-edit/remove split, see {@link EditOrRemoveActions}) says ask | same as attended |
/// | DANGEROUS_WRITE   | ask, unless the dangerous-confirmation toggle is off, in which case allow | **BLOCK — never merely asked** |
///
/// The unattended row is the load-bearing safety fix: previously, picking a more permissive mode
/// (or just flipping the dangerous-confirmation toggle off) could make a destructive command
/// auto-run with genuinely nobody watching — e.g. the synthetic follow-up turn the agent fires on
/// its own once a background job completes, which is exactly the kind of moment a user might have
/// stepped away. Under Auto, "unattended" always wins for DANGEROUS_WRITE: it is never downgraded
/// to a mere ASK, and it cannot be relaxed by the dangerous-confirmation toggle, by Plan Mode being
/// off, or by a per-tool {@link AiToolPermissionStore.OverrideMode#ALWAYS_ALLOW} override (see
/// {@link AiToolPermissionStore.OverrideMode#apply} — it explicitly refuses to touch a BLOCK). The
/// only thing that can still bypass it is the developer-only {@link #dangerouslySkipPermissions}
/// escape hatch, which already bypasses literally everything (Plan Mode included) and is not a
/// user-facing setting.
///
/// The policy never blocks by itself where it returns ASK — callers (e.g. the chat adapter or UI)
/// use `check` to decide whether to proceed, show confirmation, or block.
@NotNullByDefault
public final class AiExecutionPolicy {

    public enum Decision {
        ALLOW,
        ASK,
        BLOCK
    }

    private final AiApprovalMode mode;
    private final boolean dangerousConfirmationEnabled;
    private final boolean fileWriteConfirmEnabled;
    /// Developer-only bypass: when true, {@link #check} allow-alls every permission,
    /// regardless of mode/confirmation flags. See {@code AiSettings.dangerouslySkipPermissions}.
    private final boolean dangerouslySkipPermissions;

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled,
                             boolean fileWriteConfirmEnabled, boolean dangerouslySkipPermissions) {
        this.mode = mode;
        this.dangerousConfirmationEnabled = dangerousConfirmationEnabled;
        this.fileWriteConfirmEnabled = fileWriteConfirmEnabled;
        this.dangerouslySkipPermissions = dangerouslySkipPermissions;
    }

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled,
                             boolean fileWriteConfirmEnabled) {
        this(mode, dangerousConfirmationEnabled, fileWriteConfirmEnabled, false);
    }

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled) {
        this(mode, dangerousConfirmationEnabled, false, false);
    }

    public AiExecutionPolicy() {
        this(AiApprovalMode.AUTO, true, false, false);
    }

    /// Evaluates whether a tool with the given permission is allowed.
    ///
    /// Convenience overload for callers with no tool/action context (and no Plan Mode or
    /// unattended-turn signal to consider) — delegates to
    /// {@link #check(String, String, ToolPermission, boolean, boolean)} with {@code null} action,
    /// {@code planMode=false}, and {@code unattended=false}, which preserves this method's exact
    /// prior behavior: nothing can resolve into {@link EditOrRemoveActions}' forced-ask set without
    /// a tool name, Plan Mode never blocks, and the unattended DANGEROUS_WRITE block never fires.
    public Decision check(ToolPermission permission) {
        return check(null, null, permission, false, false);
    }

    /// Same as {@link #check(String, String, ToolPermission, boolean, boolean)} with
    /// {@code unattended=false} — preserves the exact prior behavior of this overload for callers
    /// that have no unattended-turn signal to pass (e.g. most existing tests).
    public Decision check(@Nullable String toolName, @Nullable String action, ToolPermission permission, boolean planMode) {
        return check(toolName, action, permission, planMode, false);
    }

    /// Full decision, aware of the calling tool (+ its resolved {@code action}, for the
    /// create-vs-edit/remove split — see {@link EditOrRemoveActions}), of Plan Mode, and of whether
    /// the CURRENT turn may be running unattended.
    ///
    /// Plan Mode is investigate-only: any call whose permission is CONTROLLED_WRITE or
    /// DANGEROUS_WRITE is BLOCKed outright (never merely asked) while it's active, so a write can
    /// never sneak through on a stray confirmation click while the user believes the agent is only
    /// planning. READ_ONLY (and EXTERNAL_NETWORK) calls are unaffected — Plan Mode must not stop the
    /// agent from investigating. This check runs BEFORE everything below and wins regardless of any
    /// per-tool override that might otherwise resolve to ALLOW for this call.
    ///
    /// The unattended check runs next: a DANGEROUS_WRITE call is BLOCKed outright — never merely
    /// asked — whenever {@code unattended} is true, regardless of the dangerous-confirmation
    /// toggle. See the class doc for why this is non-negotiable. This intentionally reuses the SAME
    /// {@link ToolPermission#DANGEROUS_WRITE} classification the rest of this method already relies
    /// on (which callers derive from {@link org.jackhuang.hmcl.ai.tools.DangerousCommands} /
    /// {@link org.jackhuang.hmcl.ai.tools.CriticalOperations} for shell commands and the merged
    /// domain tools) rather than inventing a second, parallel "is this dangerous" classification.
    ///
    /// @param toolName the tool being invoked, or {@code null} if the caller has none (e.g. the
    ///                  simpler {@link #check(ToolPermission)} overload)
    /// @param action    the call's resolved {@code action} parameter, or {@code null}/blank if the
    ///                  tool has none
    /// @param permission the call's resolved {@link ToolPermission}
    /// @param planMode  whether Plan Mode is currently active for this call
    /// @param unattended whether the current turn may be running with nobody watching (e.g. a
    ///                   synthetic auto-continuation fired after a background job finished, rather
    ///                   than a turn triggered by a direct, just-now user message) — see
    ///                   {@code LangChain4jToolAdapter}'s `unattendedSupplier` for how this is
    ///                   derived in production
    public Decision check(@Nullable String toolName, @Nullable String action, ToolPermission permission,
                           boolean planMode, boolean unattended) {
        // Developer-only bypass: skip every gate (dangerous + critical + Plan Mode + unattended)
        // outright.
        if (dangerouslySkipPermissions) {
            return Decision.ALLOW;
        }
        if (planMode && (permission == ToolPermission.CONTROLLED_WRITE || permission == ToolPermission.DANGEROUS_WRITE)) {
            return Decision.BLOCK;
        }
        // Non-negotiable safety net (see class doc): a dangerous operation reached while the turn
        // may be unattended is refused outright, not merely asked — there may be nobody present to
        // answer a confirmation prompt, and silently letting it through (or leaving it stuck waiting
        // on a prompt nobody will ever see) are both unacceptable outcomes for a destructive command.
        if (unattended && permission == ToolPermission.DANGEROUS_WRITE) {
            return Decision.BLOCK;
        }
        // PRODUCT DECISION: fileWriteConfirmEnabled=false only ever suppresses confirmation for
        // PURE CREATION — an action that edits or removes something that already existed always
        // asks, regardless of the toggle. See EditOrRemoveActions for the curated classification.
        boolean forcedAsk = toolName != null && permission == ToolPermission.CONTROLLED_WRITE
                && EditOrRemoveActions.isEditOrRemove(toolName, action);
        if (permission == ToolPermission.DANGEROUS_WRITE) {
            return dangerousConfirmationEnabled ? Decision.ASK : Decision.ALLOW;
        }
        if (permission == ToolPermission.CONTROLLED_WRITE && (fileWriteConfirmEnabled || forcedAsk)) {
            return Decision.ASK;
        }
        return Decision.ALLOW;
    }

    /// Returns a copy of this policy with {@code newMode} substituted for the approval mode, every
    /// other flag unchanged. Kept for API compatibility (and used by tests) even though, with a
    /// single {@link AiApprovalMode} value, this is currently always a same-mode copy.
    public AiExecutionPolicy withMode(AiApprovalMode newMode) {
        return new AiExecutionPolicy(newMode, dangerousConfirmationEnabled, fileWriteConfirmEnabled, dangerouslySkipPermissions);
    }

    public AiApprovalMode getMode() {
        return mode;
    }

    public boolean isDangerousConfirmationEnabled() {
        return dangerousConfirmationEnabled;
    }

    public boolean isFileWriteConfirmEnabled() {
        return fileWriteConfirmEnabled;
    }

    public boolean isDangerouslySkipPermissions() {
        return dangerouslySkipPermissions;
    }
}
