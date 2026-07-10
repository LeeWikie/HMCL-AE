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

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.animation.Motion;

/// Shared clickable header for the collapsible AI cards (reasoning card / tool card /
/// tool-call group card / todo card).
///
/// Design decisions (see the UI refactor blueprint, task A5 / CP §P3):
/// - The whole row is the hot zone: a plain `HBox` + [FXUtils#onClicked] — deliberately
///   **no ripple** (JFXButton ripples were visual noise on these 12px-density cards);
///   pressed/hover feedback is pure CSS via `.ai-collapse-header:hover`.
/// - A single [SVG#KEYBOARD_ARROW_DOWN] chevron expresses state by rotation only:
///   collapsed = -90° (pointing right), expanded = 0° (pointing down).
/// - The rotation animates over 150ms with [Motion#EASE_IN_OUT_CUBIC_EMPHASIZED] when
///   animations are enabled ([AnimationUtils#isAnimationEnabled] guard, same as
///   `ComponentSublistWrapper`); a running animation is stopped before restarting from
///   the current angle, so rapid toggles during streaming stay idempotent.
/// - Content show/hide is the consumer's job: bind the content node's `visible`/`managed`
///   to [#expandedProperty()]. This class never touches sibling layout.
final class CollapseHeader extends HBox {

    private static final double COLLAPSED_ROTATE = -90;
    private static final double EXPANDED_ROTATE = 0;

    private final Node chevron;
    private final Label title;
    private final BooleanProperty expanded = new SimpleBooleanProperty(this, "expanded", false);

    private Node trailing;
    private Timeline rotateAnimation;

    /// Leading SVG icon shown at the row's start in compact mode (thinking = lightbulb, tool =
    /// puzzle piece). Null in the default (todo) layout.
    private Node leadingIcon;
    /// Rich-summary label (caption tier) shown between the title and the chevron in compact mode:
    /// e.g. "instance ✓ · search ✓ · +3" or "用时 3 秒". Created lazily by [#useCompactLayout].
    private Label summary;

    CollapseHeader(String titleText) {
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE); // whole-row hot zone: let parents stretch us edge to edge
        getStyleClass().add("ai-collapse-header");

        chevron = SVG.KEYBOARD_ARROW_DOWN.createIcon(16);
        chevron.setRotate(expanded.get() ? EXPANDED_ROTATE : COLLAPSED_ROTATE);

        title = new Label(titleText);
        title.getStyleClass().add("ai-tool-card-header");

        getChildren().addAll(chevron, title);

        expanded.addListener((obs, was, is) -> rotateChevron(is));
        FXUtils.onClicked(this, this::toggle);
    }

    /// Switches this header to the compact "capsule" layout used by the inline subordinate cards
    /// (reasoning / tool / tool-call group) since the B3 redesign: a leading SVG icon, the title,
    /// a rich summary slot, and a TRAILING chevron — laid out fit-content (hugging its content)
    /// instead of the default full-width whole-row form the todo card still uses. Idempotent.
    ///
    /// @param icon the 16px leading icon (may be null); mouse-transparent so the whole row stays
    ///             the click hot zone
    void useCompactLayout(Node icon) {
        if (getStyleClass().contains("ai-collapse-header-compact")) {
            return;
        }
        getStyleClass().add("ai-collapse-header-compact");
        // fit-content: the enclosing card hugs the collapsed capsule and only grows to full width
        // once the body is revealed (see wrapCard + the .ai-tool-card CSS).
        setMaxWidth(USE_PREF_SIZE);

        summary = new Label();
        summary.getStyleClass().add("ai-collapse-summary");
        summary.setMouseTransparent(true);
        summary.setManaged(false);
        summary.setVisible(false);

        // A spacer pushes the chevron to the trailing edge when the header is stretched wide
        // (expanded state); collapsed, everything simply hugs left with the chevron last.
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        spacer.setMouseTransparent(true);
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        getChildren().clear();
        if (icon != null) {
            icon.setMouseTransparent(true);
            leadingIcon = icon;
            getChildren().add(icon);
        }
        getChildren().addAll(title, summary, spacer, chevron);
    }

    /// Sets (or clears) the compact-mode rich summary. Blank text hides the slot so it never
    /// reserves layout space. No-op if [#useCompactLayout] was not called.
    void setSummary(String text) {
        if (summary == null) {
            return;
        }
        boolean has = text != null && !text.isBlank();
        summary.setText(has ? text : "");
        summary.setManaged(has);
        summary.setVisible(has);
    }

    Label getSummaryLabel() {
        return summary;
    }

    BooleanProperty expandedProperty() {
        return expanded;
    }

    boolean isExpanded() {
        return expanded.get();
    }

    void setExpanded(boolean value) {
        expanded.set(value);
    }

    /// Flips the expanded state. Also the injection point for tests (A7: `fire()`-style
    /// event injection or direct method calls, no physical robot).
    void toggle() {
        expanded.set(!expanded.get());
    }

    /// The title label itself — consumers (e.g. ToolCard's calling→done `setText`) keep
    /// mutating the very same Label instance.
    Label getTitleLabel() {
        return title;
    }

    /// The chevron node, exposed so consumers can hide it while a card is not yet
    /// expandable (e.g. ToolCard before `complete(...)` produces a summary).
    Node getChevron() {
        return chevron;
    }

    /// Optional trailing slot appended after the title (status icons etc.). Passing
    /// `null` clears the slot.
    void setTrailing(Node node) {
        if (trailing != null) {
            getChildren().remove(trailing);
        }
        trailing = node;
        if (node != null) {
            getChildren().add(node);
        }
    }

    private void rotateChevron(boolean expandedNow) {
        double target = expandedNow ? EXPANDED_ROTATE : COLLAPSED_ROTATE;
        // Stop before restarting so a mid-flight toggle re-targets from the current angle
        // instead of stacking animations (idempotence; same idiom as
        // ComponentSublistWrapper.java's expandAnimation handling).
        if (rotateAnimation != null && rotateAnimation.getStatus() == Animation.Status.RUNNING) {
            rotateAnimation.stop();
        }
        if (AnimationUtils.isAnimationEnabled()) {
            rotateAnimation = new Timeline(new KeyFrame(
                    Motion.SHORT3, // 150ms
                    new KeyValue(chevron.rotateProperty(), target, Motion.EASE_IN_OUT_CUBIC_EMPHASIZED)));
            rotateAnimation.play();
        } else {
            chevron.setRotate(target);
        }
    }
}
