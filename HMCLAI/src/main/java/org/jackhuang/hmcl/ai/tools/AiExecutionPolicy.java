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
/// | CONTROLLED_WRITE  | ✗    | ✓    | ✓    |
/// | DANGEROUS_WRITE   | ✗    | ask  | ✓    |
/// | EXTERNAL_NETWORK  | ✗    | ✓    | ✓    |
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

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled) {
        this.mode = mode;
        this.dangerousConfirmationEnabled = dangerousConfirmationEnabled;
    }

    public AiExecutionPolicy() {
        this(AiApprovalMode.SAFE, true);
    }

    /// Evaluates whether a tool with the given permission is allowed under
    /// the current approval mode.
    public Decision check(ToolPermission permission) {
        switch (mode) {
            case YOLO:
                return Decision.ALLOW;
            case ASK:
                if (permission == ToolPermission.DANGEROUS_WRITE && dangerousConfirmationEnabled) {
                    return Decision.ASK;
                }
                return Decision.ALLOW;
            case SAFE:
            default:
                switch (permission) {
                    case READ_ONLY:
                        return Decision.ALLOW;
                    case CONTROLLED_WRITE:
                        return Decision.ASK;
                    case DANGEROUS_WRITE:
                        return Decision.BLOCK;
                    case EXTERNAL_NETWORK:
                        return Decision.ASK;
                }
        }
        return Decision.ALLOW;
    }

    public AiApprovalMode getMode() {
        return mode;
    }

    public boolean isDangerousConfirmationEnabled() {
        return dangerousConfirmationEnabled;
    }
}
