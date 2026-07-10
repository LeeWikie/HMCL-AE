package org.jackhuang.hmcl.ai.tools;

import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Decides whether a tool invocation is allowed, given its {@link ToolPermission}, whether Plan
/// Mode is active, and whether the current turn may be running unattended.
///
/// # The three approval modes
///
/// See {@link AiApprovalMode}'s own doc for the full SAFE/ASK/YOLO -> single-AUTO ->
/// restored-Auto/Ask/yolo history. Two gates run BEFORE mode is even consulted and apply
/// identically no matter which mode is selected (see the paragraph below the table). Once past
/// those, the remaining decision is:
///
/// | Permission        | `AUTO` (default)                                                                          | `ASK`                               | `yolo`                                                        |
/// |-------------------|--------------------------------------------------------------------------------------------|--------------------------------------|----------------------------------------------------------------|
/// | READ_ONLY         | allow                                                                                      | **ask**                             | allow                                                          |
/// | EXTERNAL_NETWORK  | allow                                                                                      | **ask**                             | allow                                                          |
/// | CONTROLLED_WRITE  | allow, unless the create-vs-edit/remove split (see {@link EditOrRemoveActions}) says ask   | **ask** (no exceptions)             | allow (the edit/remove split does not apply here)              |
/// | DANGEROUS_WRITE   | ask, unless the dangerous-confirmation toggle is off, in which case allow                  | **ask** (regardless of the toggle)  | **allow** (regardless of the toggle -- the old YOLO semantics) |
///
/// `ASK` is deliberately the most conservative pick -- nearly everything asks, which is the entire
/// point of choosing it over `AUTO`. `yolo` is deliberately the most permissive pick -- nearly
/// everything auto-runs without asking, which is the entire point of choosing it over `AUTO`.
///
/// ## The two gates that run before mode is consulted, and that NO mode can relax
///
/// - **Plan Mode**: any call whose permission is CONTROLLED_WRITE or DANGEROUS_WRITE is BLOCKed
///   outright while Plan Mode is active, regardless of mode.
/// - **Unattended DANGEROUS_WRITE** (the load-bearing safety fix — see {@link AiApprovalMode}'s
///   own doc): whenever the current turn may be running unattended, a DANGEROUS_WRITE call is
///   hard-BLOCKed outright — never merely asked, and never merely allowed — no matter which mode
///   is selected. `yolo`'s "allow dangerous operations without asking" and `ASK`'s
///   toggle-independent asking both explicitly do NOT reach this case: this gate runs first and
///   wins regardless. Previously, picking a more permissive mode (or just flipping the
///   dangerous-confirmation toggle off) could make a destructive command auto-run with genuinely
///   nobody watching — e.g. the synthetic follow-up turn the agent fires on its own once a
///   background job completes, which is exactly the kind of moment a user might have stepped
///   away. It cannot be relaxed by the dangerous-confirmation toggle, by Plan Mode being off, by
///   which approval mode is selected, or by a per-tool
///   {@link AiToolPermissionStore.OverrideMode#ALWAYS_ALLOW} override (see
///   {@link AiToolPermissionStore.OverrideMode#apply} — it explicitly refuses to touch a BLOCK).
///   The only thing that can still bypass it is the developer-only
///   {@link #dangerouslySkipPermissions} escape hatch, which already bypasses literally everything
///   (Plan Mode included) and is not a user-facing setting.
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

    /// WHY a {@link Decision#BLOCK} fired — Plan Mode and unattended-dangerous demand DIFFERENT
    /// next actions from the model (keep investigating read-only vs. end the turn), so collapsing
    /// them into one "either...or..." sentence left the model guessing (borrow-list A4).
    public enum BlockReason {
        /// Plan Mode is active: write-capable calls are blocked until the user approves the plan.
        PLAN_MODE,
        /// The current turn may be running unattended: a DANGEROUS_WRITE is refused outright.
        UNATTENDED_DANGEROUS
    }

    /// A {@link Decision} plus, when it is {@link Decision#BLOCK}, the {@link BlockReason} that
    /// caused it ({@code blockReason} is {@code null} for ALLOW/ASK). The plain {@link Decision}
    /// enum is kept unchanged because other layers ({@link AiToolPermissionStore.OverrideMode
    /// #apply} and its callers) compare and switch on it; this record is the reason-carrying
    /// upgrade for callers that need to explain a BLOCK precisely.
    public record Verdict(Decision decision, @Nullable BlockReason blockReason) {
        static final Verdict ALLOW = new Verdict(Decision.ALLOW, null);
        static final Verdict ASK = new Verdict(Decision.ASK, null);

        static Verdict block(BlockReason reason) {
            return new Verdict(Decision.BLOCK, reason);
        }
    }

    private final AiApprovalMode mode;
    private final boolean dangerousConfirmationEnabled;
    /// Developer-only bypass: when true, {@link #check} allow-alls every permission,
    /// regardless of mode/confirmation flags. See {@code AiSettings.dangerouslySkipPermissions}.
    private final boolean dangerouslySkipPermissions;

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled,
                             boolean dangerouslySkipPermissions) {
        this.mode = mode;
        this.dangerousConfirmationEnabled = dangerousConfirmationEnabled;
        this.dangerouslySkipPermissions = dangerouslySkipPermissions;
    }

    public AiExecutionPolicy(AiApprovalMode mode, boolean dangerousConfirmationEnabled) {
        this(mode, dangerousConfirmationEnabled, false);
    }

    public AiExecutionPolicy() {
        this(AiApprovalMode.AUTO, true, false);
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
        return evaluate(toolName, action, permission, planMode, unattended).decision();
    }

    /// Same evaluation as {@link #check(String, String, ToolPermission, boolean, boolean)}, but
    /// returning the reason-carrying {@link Verdict} so a BLOCK can be explained precisely (which
    /// gate fired decides what the model should do next — see {@link BlockReason}). {@code check}
    /// delegates here, so the two can never drift.
    public Verdict evaluate(@Nullable String toolName, @Nullable String action, ToolPermission permission,
                             boolean planMode, boolean unattended) {
        // Developer-only bypass: skip every gate (dangerous + critical + Plan Mode + unattended)
        // outright.
        if (dangerouslySkipPermissions) {
            return Verdict.ALLOW;
        }
        if (planMode && (permission == ToolPermission.CONTROLLED_WRITE || permission == ToolPermission.DANGEROUS_WRITE)) {
            return Verdict.block(BlockReason.PLAN_MODE);
        }
        // Non-negotiable safety net (see class doc): a dangerous operation reached while the turn
        // may be unattended is refused outright, not merely asked — there may be nobody present to
        // answer a confirmation prompt, and silently letting it through (or leaving it stuck waiting
        // on a prompt nobody will ever see) are both unacceptable outcomes for a destructive command.
        // This runs BEFORE the mode switch below and is NOT influenced by which mode is selected —
        // not even `yolo`'s otherwise-unconditional "allow dangerous operations" can reach here.
        if (unattended && permission == ToolPermission.DANGEROUS_WRITE) {
            return Verdict.block(BlockReason.UNATTENDED_DANGEROUS);
        }
        return switch (mode) {
            // ASK is deliberately the most conservative pick: every call asks, full stop — that is
            // the entire reason a user would choose it over AUTO. No exceptions for read-only or
            // network calls, and no exception for the dangerous-confirmation toggle either.
            case ASK -> Verdict.ASK;
            // yolo is deliberately the most permissive pick: everything left standing after the two
            // non-negotiable gates above auto-runs, including DANGEROUS_WRITE while attended and the
            // create-vs-edit/remove split that AUTO enforces for CONTROLLED_WRITE — restoring the old
            // YOLO semantics exactly. The dangerous-confirmation toggle is irrelevant here.
            case YOLO -> Verdict.ALLOW;
            case AUTO -> evaluateAuto(toolName, action, permission);
        };
    }

    /// `AUTO`'s own decision table (see class doc) — unchanged from the single-mode era: everyday
    /// operations stay low-friction, and only a DANGEROUS_WRITE (or an edit/remove-classified
    /// CONTROLLED_WRITE) ever asks.
    private Verdict evaluateAuto(@Nullable String toolName, @Nullable String action, ToolPermission permission) {
        // PRODUCT DECISION (2026-07-10): file-write confirmation is policy-decided, not a user
        // toggle — PURE CREATION runs automatically; an action that edits or removes something
        // that already existed always asks. See EditOrRemoveActions for the curated classification.
        boolean forcedAsk = toolName != null && permission == ToolPermission.CONTROLLED_WRITE
                && EditOrRemoveActions.isEditOrRemove(toolName, action);
        if (permission == ToolPermission.DANGEROUS_WRITE) {
            return dangerousConfirmationEnabled ? Verdict.ASK : Verdict.ALLOW;
        }
        if (forcedAsk) {
            return Verdict.ASK;
        }
        return Verdict.ALLOW;
    }

    /// Returns a copy of this policy with {@code newMode} substituted for the approval mode, every
    /// other flag unchanged — e.g. for switching between `AUTO`/`ASK`/`yolo` without discarding the
    /// dangerous-confirmation / dangerously-skip-permissions flags already configured on this
    /// instance.
    public AiExecutionPolicy withMode(AiApprovalMode newMode) {
        return new AiExecutionPolicy(newMode, dangerousConfirmationEnabled, dangerouslySkipPermissions);
    }

    public AiApprovalMode getMode() {
        return mode;
    }

    public boolean isDangerousConfirmationEnabled() {
        return dangerousConfirmationEnabled;
    }

    public boolean isDangerouslySkipPermissions() {
        return dangerouslySkipPermissions;
    }
}
