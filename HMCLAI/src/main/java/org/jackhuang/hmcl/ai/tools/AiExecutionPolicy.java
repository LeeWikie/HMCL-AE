package org.jackhuang.hmcl.ai.tools;

import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jetbrains.annotations.NotNullByDefault;

/// Decides whether a tool invocation is allowed based on its
/// {@link ToolPermission} and the current {@link AiApprovalMode}.
///
/// # Rules
///
/// | Permission        | Safe | Ask  | YOLO |
/// |-------------------|------|------|------|
/// | READ_ONLY         | ✓    | ✓    | ✓    |
/// | CONTROLLED_WRITE  | ✓    | ✓    | ✓    |
/// | DANGEROUS_WRITE   | ask  | ask  | ✓    |
/// | EXTERNAL_NETWORK  | ✓    | ✓    | ✓    |
///
/// Safe mode only confirms DANGEROUS operations (not every write/network call).
///
/// The policy never blocks by itself — callers (e.g. the chat adapter or UI)
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
        this(AiApprovalMode.SAFE, true, false, false);
    }

    /// Evaluates whether a tool with the given permission is allowed under
    /// the current approval mode.
    public Decision check(ToolPermission permission) {
        // Developer-only bypass: skip every gate (dangerous + critical) outright.
        if (dangerouslySkipPermissions) {
            return Decision.ALLOW;
        }
        switch (mode) {
            case YOLO:
                return Decision.ALLOW;
            case ASK:
                if (permission == ToolPermission.DANGEROUS_WRITE && dangerousConfirmationEnabled) {
                    return Decision.ASK;
                }
                if (permission == ToolPermission.CONTROLLED_WRITE && fileWriteConfirmEnabled) {
                    return Decision.ASK;
                }
                return Decision.ALLOW;
            case SAFE:
            default:
                // Safe mode = ONLY dangerous operations require confirmation; read-only,
                // controlled writes and network calls run automatically — unless the user
                // opts into confirming controlled (file) writes too.
                if (permission == ToolPermission.DANGEROUS_WRITE) {
                    return dangerousConfirmationEnabled ? Decision.ASK : Decision.ALLOW;
                }
                if (permission == ToolPermission.CONTROLLED_WRITE && fileWriteConfirmEnabled) {
                    return Decision.ASK;
                }
                return Decision.ALLOW;
        }
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
