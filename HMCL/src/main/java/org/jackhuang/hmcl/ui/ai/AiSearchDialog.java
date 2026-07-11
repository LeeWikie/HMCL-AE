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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Cross-session search as a native [JFXDialogLayout] shown via `Controllers.dialog(...)`,
/// replacing the old hand-built full-screen overlay in AIMainPage (blueprint B5 / CP §1).
///
/// The four logic blocks — perform / navigate / select / build-result-row — moved here
/// verbatim from AIMainPage; jumping to a message stays in AIMainPage
/// (`switchToSessionAndScroll`) and is reached through the injected `onSelect` callback.
/// Data access is behind a `Supplier` so tests can feed fake sessions without a store.
///
/// Structure follows InputDialogPane (heading / body / actions); ESC closes via the same
/// key handler pattern as MessageDialogPane. Result rows share the `.ai-list-row` family
/// with the composer autocomplete rows.
public final class AiSearchDialog extends JFXDialogLayout implements DialogAware {

    /// Supplies the sessions to search (production: `sessionStore::listSessions`).
    private final Supplier<List<AiSession>> sessionsSupplier;
    /// Invoked with (sessionId, matchingLine) when a result is chosen; the dialog closes itself.
    private final BiConsumer<String, String> onSelect;
    /// Invoked when the dialog actually leaves the decorator (double-open guard reset).
    private final Runnable onClosed;

    private final JFXTextField searchField = new JFXTextField();
    private final VBox searchResultsBox = new VBox(2);
    private final Label searchStatusLabel = new Label();
    private final Label searchEmptyLabel = new Label(i18n("ai.search.empty"));
    private final List<SearchResult> searchResults = new ArrayList<>();
    private int searchSelectedIndex = -1;

    public AiSearchDialog(Supplier<List<AiSession>> sessionsSupplier,
                          BiConsumer<String, String> onSelect,
                          Runnable onClosed) {
        this.sessionsSupplier = sessionsSupplier;
        this.onSelect = onSelect;
        this.onClosed = onClosed;

        setHeading(new Label(i18n("ai.search")));

        searchField.setPromptText(i18n("ai.search.prompt"));
        searchField.textProperty().addListener((obs, old, val) -> performSearch(val));
        // Keyboard navigation moved verbatim from the old overlay (logic transplant, no changes).
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                navigateSearchResults(1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                navigateSearchResults(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                selectSearchResult();
                e.consume();
            }
        });

        HBox searchInputRow = new HBox(8, SVG.SEARCH.createIcon(16), searchField);
        searchInputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchEmptyLabel.getStyleClass().add("ai-caption"); // rule lands with the B7 css rewrite
        searchEmptyLabel.setPadding(new Insets(32, 16, 32, 16));
        searchEmptyLabel.setVisible(true);

        searchStatusLabel.getStyleClass().add("ai-caption"); // rule lands with the B7 css rewrite
        searchStatusLabel.setPadding(new Insets(8, 0, 0, 0));
        searchStatusLabel.setVisible(false);

        ScrollPane resultsScroll = new ScrollPane(new StackPane(searchResultsBox, searchEmptyLabel));
        resultsScroll.setFitToWidth(true);
        resultsScroll.setPrefViewportHeight(320);
        resultsScroll.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(resultsScroll);
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);

        setBody(new VBox(8, searchInputRow, resultsScroll, searchStatusLabel));

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> close());
        setActions(cancelButton);

        setPrefWidth(560); // same as PromptDialogPane

        // ESC anywhere in the dialog closes — same pattern as MessageDialogPane. When focus is
        // in the field, its filter above consumes ESC first, so this never double-fires.
        onEscPressed(this, cancelButton::fire);
    }

    @Override
    public void onDialogShown() {
        searchField.requestFocus();
    }

    @Override
    public void onDialogClosed() {
        if (onClosed != null) {
            onClosed.run();
        }
    }

    /// Fires the standard close event; the decorator's dialog wrapper picks it up. A fresh
    /// dialog instance is created per open, so no manual state reset is needed on close.
    private void close() {
        fireEvent(new DialogCloseEvent());
    }

    /// Performs a full-text search across all sessions' titles and message content.
    ///
    /// Filters against the given query (case-insensitive containment match) and
    /// updates the results list.
    private void performSearch(String query) {
        searchResultsBox.getChildren().clear();
        searchResults.clear();
        searchSelectedIndex = -1;

        if (query == null || query.trim().isEmpty()) {
            searchStatusLabel.setVisible(false);
            searchEmptyLabel.setVisible(true);
            return;
        }

        searchEmptyLabel.setVisible(false);

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<AiSession> allSessions = sessionsSupplier.get();

        for (AiSession session : allSessions) {
            // Search in title
            String title = session.getTitle();
            boolean titleMatch = title != null && title.toLowerCase(Locale.ROOT).contains(lowerQuery);

            // Search in messages
            List<LlmMessage> messages = session.getMessages();
            for (int i = 0; i < messages.size(); i++) {
                LlmMessage msg = messages.get(i);
                String content = msg.getContent();
                if (content != null) {
                    int idx = content.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
                    if (idx >= 0) {
                        // Extract a preview line around the match
                        int start = Math.max(0, idx - 30);
                        int end = Math.min(content.length(), idx + lowerQuery.length() + 40);
                        String preview = (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
                        searchResults.add(new SearchResult(session, preview));
                        break; // One result per session
                    }
                }
            }

            // If title matched but no message matched, still add the title result
            if (titleMatch && searchResults.stream().noneMatch(r -> r.session.getId().equals(session.getId()))) {
                searchResults.add(new SearchResult(session, title));
            }
        }

        if (searchResults.isEmpty()) {
            searchStatusLabel.setText(i18n("ai.search.no_results", query));
            searchStatusLabel.setVisible(true);
        } else {
            searchStatusLabel.setVisible(false);
            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult result = searchResults.get(i);
                int index = i;
                Node item = buildSearchResultItem(result, index);
                searchResultsBox.getChildren().add(item);
            }
        }
    }

    /// Builds a single clickable search result row with session title and
    /// a truncated matching-message preview.
    private Node buildSearchResultItem(SearchResult result, int index) {
        AiSession session = result.session;
        String title = session.getTitle();
        if (title == null || title.isEmpty()) {
            title = i18n("ai.session.untitled");
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("ai-caption-bold"); // rule lands with the B7 css rewrite

        Label previewLabel = new Label(result.matchingLine);
        previewLabel.getStyleClass().add("ai-caption"); // rule lands with the B7 css rewrite
        previewLabel.setMaxWidth(500);
        previewLabel.setWrapText(true);

        VBox row = new VBox(2, titleLabel, previewLabel);
        row.getStyleClass().add("ai-list-row"); // shared family with the autocomplete rows (CP §1)
        if (index == searchSelectedIndex) {
            row.getStyleClass().add("ai-list-row-selected");
        }
        row.setOnMouseClicked(e -> {
            onSelect.accept(session.getId(), result.matchingLine);
            close();
        });

        return row;
    }

    /// Navigates the search results list by the given delta (-1 for up, +1 for down).
    private void navigateSearchResults(int delta) {
        if (searchResults.isEmpty()) return;
        int newIndex = searchSelectedIndex + delta;
        if (newIndex < 0) newIndex = searchResults.size() - 1;
        if (newIndex >= searchResults.size()) newIndex = 0;
        searchSelectedIndex = newIndex;

        ObservableList<Node> children = searchResultsBox.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Node node = children.get(i);
            node.getStyleClass().remove("ai-list-row-selected");
            if (i == searchSelectedIndex) {
                node.getStyleClass().add("ai-list-row-selected");
            }
        }
    }

    /// Selects the currently highlighted search result and navigates to it.
    private void selectSearchResult() {
        if (searchSelectedIndex >= 0 && searchSelectedIndex < searchResults.size()) {
            SearchResult result = searchResults.get(searchSelectedIndex);
            onSelect.accept(result.session.getId(), result.matchingLine);
            close();
        }
    }

    // ---- Package-private test accessors (TestFX event injection, blueprint A7) ----

    JFXTextField getSearchField() {
        return searchField;
    }

    VBox getResultsBox() {
        return searchResultsBox;
    }

    Label getStatusLabel() {
        return searchStatusLabel;
    }

    Label getEmptyLabel() {
        return searchEmptyLabel;
    }

    int getSelectedIndex() {
        return searchSelectedIndex;
    }

    /// Represents a single cross-session search result.
    private static final class SearchResult {
        final AiSession session;
        final String matchingLine;

        SearchResult(AiSession session, String matchingLine) {
            this.session = session;
            this.matchingLine = matchingLine;
        }
    }
}
