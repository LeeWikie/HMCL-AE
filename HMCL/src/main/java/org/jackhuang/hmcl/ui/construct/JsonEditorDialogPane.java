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
package org.jackhuang.hmcl.ui.construct;

import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.util.FutureCallback;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;

/// A real JSON/code editor dialog: a [CodeArea][org.fxmisc.richtext.CodeArea] (line numbers +
/// lightweight JSON syntax highlighting) wrapped in the app's standard [DialogPane] chrome
/// (title, accept/cancel, spinner, ESC-to-cancel), replacing the old pattern of shoehorning
/// free-form JSON into a fixed-width [PromptDialogPane] question form.
///
/// This class is deliberately JSON-*shaped* (highlighting assumes JSON grammar) but otherwise
/// domain-agnostic — it knows nothing about what the edited JSON means. Callers supply a
/// [Validator] that re-checks the text after every edit (parsing + whatever structural/field
/// checks make sense for their use case) and returns a user-facing error message, or {@code null}
/// when the text is acceptable; the accept button stays disabled and the message is shown next to
/// it (reusing [DialogPane#warningLabel]) until the text validates.
///
/// Field-level semantics (which JSON keys exist, their types, defaults for missing keys, etc.)
/// belong entirely to the caller, not here — see e.g. {@code AISettingsPage#editMcpServer} for the
/// MCP-server-config-specific reading/validation/writing built on top of this widget.
public class JsonEditorDialogPane extends DialogPane {

    @FunctionalInterface
    public interface Validator {
        /// @return {@code null} if {@code text} is acceptable, otherwise a user-facing error message.
        @Nullable
        String validate(String text);
    }

    private final CompletableFuture<String> future = new CompletableFuture<>();
    private final CodeArea codeArea = new CodeArea();
    private final Validator validator;
    private final FutureCallback<String> callback;

    public JsonEditorDialogPane(String title, String initialText, Validator validator, FutureCallback<String> callback) {
        this(title, initialText, null, validator, callback);
    }

    public JsonEditorDialogPane(String title, String initialText, @Nullable String hint, Validator validator, FutureCallback<String> callback) {
        this.validator = validator;
        this.callback = callback;
        setTitle(title);
        // Clamp to the main window instead of a fixed 760×(700×440): on a small window or at a
        // high UI scale a fixed-size dialog can overflow past the window edge and hide the
        // accept/cancel buttons entirely (bug-hunt 4.1). Falls back to the old fixed values when
        // no main stage exists (bare TestFX harness) or it hasn't been laid out yet.
        Stage stage = Controllers.getStage();
        double dialogWidth = 760;
        double editorHeight = 440;
        if (stage != null && stage.getWidth() > 0) {
            dialogWidth = Math.min(760, stage.getWidth() - 120);
        }
        if (stage != null && stage.getHeight() > 0) {
            editorHeight = Math.min(440, stage.getHeight() - 260);
        }
        setPrefWidth(dialogWidth);

        codeArea.getStyleClass().add("json-editor-code-area");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.replaceText(0, 0, initialText);
        // CodeArea's own change-tracking history should start clean, not with the initial
        // replaceText() above sitting on the undo stack as the sole "undo back to empty" step.
        codeArea.getUndoManager().forgetHistory();

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        scrollPane.setPrefSize(700, editorHeight);
        scrollPane.setMaxWidth(Double.MAX_VALUE);
        scrollPane.setMinHeight(240);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox body = new VBox(8);
        body.setFillWidth(true);
        if (hint != null) {
            HintPane hintPane = new HintPane();
            hintPane.setText(hint);
            body.getChildren().add(hintPane);
        }
        body.getChildren().add(scrollPane);
        setBody(body);

        codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(80))
                .subscribe(ignored -> refresh());
        refresh();
    }

    private void refresh() {
        String text = codeArea.getText();
        codeArea.setStyleSpans(0, computeHighlighting(text));
        String error = validator.validate(text);
        setValid(error == null);
        warningLabel.setText(error == null ? "" : error);
    }

    @Override
    protected void onAccept() {
        setLoading();

        callback.call(codeArea.getText(), new FutureCallback.ResultHandler() {
            @Override
            public void resolve() {
                future.complete(codeArea.getText());
                runInFX(JsonEditorDialogPane.this::onSuccess);
            }

            @Override
            public void reject(String reason) {
                runInFX(() -> onFailure(reason));
            }
        });
    }

    public CompletableFuture<String> getCompletableFuture() {
        return future;
    }

    /// Package-visible so {@code JsonEditorDialogPaneFxTest} can drive live edits directly (e.g.
    /// {@code codeArea().replaceText(...)}) and observe the resulting re-validation — not part of
    /// the public API surface callers outside this package should rely on.
    CodeArea codeArea() {
        return codeArea;
    }

    private static final Pattern JSON_PATTERN = Pattern.compile(
            "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\")\\s*:"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)"
                    + "|(?<NULLVAL>\\bnull\\b)"
                    + "|(?<BRACE>[{}])"
                    + "|(?<BRACKET>[\\[\\]])"
    );

    /// One regex pass producing a lightweight JSON syntax highlighting: object keys, string
    /// values, numbers, booleans, null, and braces/brackets each get their own CSS class (see
    /// `root.css`'s `.json-editor-code-area` rules). Everything else (colons, commas, whitespace)
    /// gets the baseline `json-plain` class — deliberately a real class rather than an empty style
    /// span, so its text color does not depend on whatever default JavaFX happens to give an
    /// unstyled `Text` node (which is not theme-aware and would read black-on-dark in dark mode).
    /// This is a display aid only — it does not validate the JSON; that is the [Validator]'s job.
    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = JSON_PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass;
            int start;
            int end;
            if (matcher.group("KEY") != null) {
                styleClass = "json-key";
                start = matcher.start("KEY");
                end = matcher.end("KEY");
            } else if (matcher.group("STRING") != null) {
                styleClass = "json-string";
                start = matcher.start();
                end = matcher.end();
            } else if (matcher.group("NUMBER") != null) {
                styleClass = "json-number";
                start = matcher.start();
                end = matcher.end();
            } else if (matcher.group("BOOLEAN") != null) {
                styleClass = "json-boolean";
                start = matcher.start();
                end = matcher.end();
            } else if (matcher.group("NULLVAL") != null) {
                styleClass = "json-null";
                start = matcher.start();
                end = matcher.end();
            } else if (matcher.group("BRACE") != null) {
                styleClass = "json-brace";
                start = matcher.start();
                end = matcher.end();
            } else {
                styleClass = "json-bracket";
                start = matcher.start();
                end = matcher.end();
            }
            builder.add(Collections.singleton("json-plain"), start - lastEnd);
            builder.add(Collections.singleton(styleClass), end - start);
            lastEnd = end;
        }
        builder.add(Collections.singleton("json-plain"), text.length() - lastEnd);
        return builder.create();
    }
}
