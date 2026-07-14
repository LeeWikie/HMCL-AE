/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.ai;

import com.jfoenix.controls.JFXProgressBar;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The inline tool-call cards shown in the conversation, extracted verbatim out of {@code AIMainPage}
/// (which had grown past 6000 lines): {@link ToolCard} is one tool invocation, {@link ToolCallGroupCard}
/// collapses a run of consecutive calls into one summary card. Both are package-private, static-free
/// top-level classes here; they reference only {@code AIMainPage.AI_BUBBLE_MAX_WIDTH} and shared UI
/// helpers, so moving them out changed no behaviour (the {@code ToolCardFxTest} / {@code RedlineStructureFxTest}
/// suites exercise them and are updated to the new top-level names).

/// A tool-call card shown inline in the conversation, in chronological order: it appears
/// when the agent invokes a tool ("调用中") and is updated in place when the tool finishes
/// ("已完成"/"失败"). The result text is collapsible — click the header to expand it.
final class ToolCard extends VBox {
    private final CollapseHeader header;
    private final Label result = new Label();
    private final String toolName;
    /// Set once complete() stores a non-blank result. Gates the result binding so clicking
    /// the header of a card with nothing to show keeps doing nothing (pre-CollapseHeader
    /// behavior), and the chevron only appears once there is something to expand.
    private final BooleanProperty hasResult = new SimpleBooleanProperty(false);

    /// Live progress UI (hidden until the first progress event, removed once the tool finishes).
    private final JFXProgressBar progressBar = new JFXProgressBar();
    private final Label progressLabel = new Label();
    private final VBox progressBox = new VBox(2, progressLabel, progressBar);
    private boolean finished = false;
    /// Terminal success, valid once [#finished]; drives the group card's rich summary marks.
    private boolean success = false;
    /// Wall-clock start of the call, for the "· N 秒" elapsed suffix on the summary.
    private final long startNanos = System.nanoTime();

    ToolCard(String toolName) {
        super(2);
        this.toolName = toolName;
        getStyleClass().add("ai-tool-card");
        setMaxWidth(AIMainPage.AI_BUBBLE_MAX_WIDTH - 16); // 704 — unified subordinate-card width (VS §3.3)

        // Whole-row hot zone replaces the old "only the header Label is clickable" hitbox.
        // The chevron stays hidden until complete() delivers an expandable result, so a
        // running/plain card doesn't advertise a toggle it does not have.
        header = new CollapseHeader(i18n("ai.tool.calling", toolName));
        header.useCompactLayout(SVG.EXTENSION.createIcon(16));
        header.getTitleLabel().setWrapText(true);
        header.getChevron().setVisible(false);
        header.getChevron().setManaged(false);

        result.setWrapText(true);
        result.setMaxWidth(AIMainPage.AI_BUBBLE_MAX_WIDTH - 40);
        result.getStyleClass().add("ai-tool-card-result");
        result.visibleProperty().bind(header.expandedProperty().and(hasResult));
        result.managedProperty().bind(result.visibleProperty());

        progressLabel.setWrapText(true);
        progressLabel.setMaxWidth(AIMainPage.AI_BUBBLE_MAX_WIDTH - 40);
        progressLabel.getStyleClass().add("ai-tool-card-result");
        progressBar.setPrefWidth(AIMainPage.AI_BUBBLE_MAX_WIDTH - 40);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBox.setVisible(false);
        progressBox.setManaged(false);

        getChildren().addAll(header, progressBox, result);
    }

    /// Applies a live progress update. {@code fraction < 0} renders an indeterminate bar.
    /// JavaFX thread only. No-op after the tool has finished.
    void updateProgress(double fraction, @Nullable String message) {
        if (finished) {
            return;
        }
        if (!progressBox.isVisible()) {
            progressBox.setVisible(true);
            progressBox.setManaged(true);
        }
        progressBar.setProgress(fraction < 0 || Double.isNaN(fraction) ? -1.0 : Math.min(fraction, 1.0));
        String pct = (fraction >= 0 && !Double.isNaN(fraction))
                ? " " + Math.round(fraction * 100) + "%" : "";
        progressLabel.setText((message == null || message.isBlank() ? "" : message.strip()) + pct);
    }

    String getToolName() {
        return toolName;
    }

    /// Terminal success flag — only meaningful after [#complete]. Read by the group card to
    /// tally its success/failure status-count chips.
    boolean isSuccess() {
        return success;
    }

    boolean isFinished() {
        return finished;
    }

    /// Set by the enclosing {@link ToolCallGroupCard} so it can refresh its rich summary the
    /// moment a grouped child reaches its terminal state (add() happens at call time, before
    /// the result is known). Null for standalone cards.
    @Nullable
    private Runnable completionListener;

    void setCompletionListener(@Nullable Runnable listener) {
        this.completionListener = listener;
    }

    /// Updates the card once its tool finishes; stores the (collapsible) result text.
    void complete(boolean success, @Nullable String summary) {
        finished = true;
        this.success = success;
        progressBox.setVisible(false);
        progressBox.setManaged(false);
        header.getTitleLabel().setText(i18n(success ? "ai.tool.done" : "ai.tool.failed", toolName));
        getStyleClass().add(success ? "ai-tool-card-ok" : "ai-tool-card-fail");
        // Swap the puzzle-piece leading icon for a themed SVG status icon (CHECK_CIRCLE on
        // success, CANCEL on failure), replacing the old Unicode ✓/✗ mark that used to be
        // concatenated into the summary text — the same SVG-status-icon treatment the todo rows
        // already use (see updateTodoCard above; 2026-07-11 feedback). The ai-feedback-success /
        // -error classes carry the native success=tertiary / error=error tint for the .svg fill.
        Node statusIcon = (success ? SVG.CHECK_CIRCLE : SVG.CANCEL).createIcon(16);
        statusIcon.getStyleClass().add(success ? "ai-feedback-success" : "ai-feedback-error");
        header.setLeadingIcon(statusIcon);
        // The summary now carries only the elapsed time (the ✓/✗ mark moved to the leading icon).
        long secs = Math.round((System.nanoTime() - startNanos) / 1_000_000_000.0);
        header.setSummary(secs >= 1 ? i18n("ai.reasoning.duration", secs) : "");
        if (summary != null && !summary.isBlank()) {
            String text = summary.strip();
            // UI-side hard cap (BF 2-3): a Label with hundreds of KB stalls layout. The full
            // result is still persisted and visible in the ai-trace log; this only trims what
            // the collapsible card renders.
            if (text.length() > 4000) {
                text = text.substring(0, 4000) + "\n" + i18n("ai.tool.result_truncated");
            }
            result.setText(text);
            hasResult.set(true);
            // Freshly-completed cards start collapsed, exactly like the old click-to-reveal
            // Label — even if the whole-row hot zone was toggled while the tool was running.
            header.setExpanded(false);
            header.getChevron().setVisible(true);
            header.getChevron().setManaged(true);
        }
        if (completionListener != null) {
            completionListener.run();
        }
    }
}

/// Collapses a RUN of consecutive tool calls (no assistant text between them) into one card
/// with a "已调用 N 个工具" summary header, so a turn that calls e.g. `instance` ten times in
/// a row shows one collapsible row instead of ten separate cards stacked in the conversation
/// ("连续的工具调用需要收纳在一起"). Individual {@link ToolCard}s are unchanged — they just
/// live inside this card's body instead of directly in {@code messageList} once a run reaches
/// 2+ calls (see {@code appendToolCard}, which decides when to promote a solo card into one
/// of these).
final class ToolCallGroupCard extends VBox {
    private final CollapseHeader header;
    private final VBox body = new VBox(2);
    private final java.util.List<ToolCard> cards = new java.util.ArrayList<>();

    ToolCallGroupCard() {
        super(2);
        getStyleClass().add("ai-tool-card");
        setMaxWidth(AIMainPage.AI_BUBBLE_MAX_WIDTH - 16); // 704 — unified subordinate-card width (VS §3.3)

        header = new CollapseHeader(i18n("ai.tool.group.summary", 0)); // placeholder, overwritten by the 1st add()
        header.useCompactLayout(SVG.EXTENSION.createIcon(16));
        body.visibleProperty().bind(header.expandedProperty()); // starts collapsed (default false)
        body.managedProperty().bind(header.expandedProperty());
        getChildren().addAll(header, body);
    }

    void add(ToolCard card) {
        body.getChildren().add(card);
        cards.add(card);
        header.getTitleLabel().setText(i18n("ai.tool.group.summary", cards.size()));
        // A card is added at call time (result unknown), so refresh the rich summary now AND
        // again when it completes — that's when its success/failure state becomes known.
        card.setCompletionListener(this::rebuildSummary);
        rebuildSummary();
    }

    /// Builds the compact "B3" rich summary: the first three tools shown by NAME, each name
    /// followed by a small themed SVG status icon (CHECK_CIRCLE on success, CANCEL on failure),
    /// joined by " · ", with any remaining calls folded into a trailing " · +N" tail — e.g.
    /// {@code t0 ✓ · t1 ✗ · t2 ✓ · +2}, only with real SVG icons in place of the ✓/✗ marks
    /// (the 2026-07-11 SVG-status-icon improvement over B3's Unicode marks; mirrors the todo rows
    /// and the tool card's leading status icon). Rendered as a TextFlow — tool names and the
    /// " · " / "+N" separators are Text runs, the status marks are {@link SVGContainer} icons
    /// tinted by ai-feedback-success / -error — and installed into the header's summary slot.
    /// While no child has finished the summary stays blank, since the header title's
    /// "已调用 N 个工具" already carries the running total.
    private void rebuildSummary() {
        boolean anyFinished = false;
        for (ToolCard c : cards) {
            if (c.isFinished()) {
                anyFinished = true;
                break;
            }
        }
        if (!anyFinished) {
            header.setSummary(""); // nothing finished yet
            return;
        }
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
        // .ai-collapse-summary carries the 11px caption size; font is inherited by the child Text
        // runs (a Text is a Shape, so it takes its colour from summaryText's inline -fx-fill).
        flow.getStyleClass().add("ai-collapse-summary");
        flow.setMouseTransparent(true);
        int shown = Math.min(cards.size(), 3);
        for (int i = 0; i < shown; i++) {
            ToolCard c = cards.get(i);
            if (i > 0) {
                flow.getChildren().add(summaryText(" · "));
            }
            flow.getChildren().add(summaryText(c.getToolName()));
            if (c.isFinished()) {
                flow.getChildren().add(summaryText(" ")); // hair of space between the name and its mark
                flow.getChildren().add(summaryStatusIcon(c.isSuccess()));
            }
        }
        int overflow = cards.size() - shown;
        if (overflow > 0) {
            flow.getChildren().add(summaryText(" · +" + overflow));
        }
        header.setSummaryNode(flow);
    }

    /// A caption-tinted Text run for the B3 rich summary (tool name / " · " separator / "+N"
    /// tail). The colour is set inline to the themed on-surface-variant: a Text is a Shape, so
    /// the .ai-collapse-summary rule's {@code -fx-text-fill} would not reach it — only the
    /// inherited font size does.
    private static javafx.scene.text.Text summaryText(String s) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        t.setStyle("-fx-fill: -monet-on-surface-variant;");
        return t;
    }

    /// A ~14px themed status icon for the B3 rich summary: CHECK_CIRCLE (tertiary) on success,
    /// CANCEL (error) on failure. An {@link SVGContainer} (not a bare SVGPath) so the
    /// {@code .ai-feedback-success/-error .svg} rule tints the glyph and it lays out at 14px
    /// inside the TextFlow instead of reserving the icon's full 24px design box.
    private static SVGContainer summaryStatusIcon(boolean success) {
        SVGContainer icon = (success ? SVG.CHECK_CIRCLE : SVG.CANCEL).createIcon(14);
        icon.getStyleClass().add(success ? "ai-feedback-success" : "ai-feedback-error");
        return icon;
    }
}
