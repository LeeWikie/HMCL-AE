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

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.Metadata;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/// Renders text with colour emoji as inline images (Twemoji), for the AI chat only.
///
/// Colour emoji are an opt-in feature: when enabled, emoji code points in a string are
/// replaced with downloaded Twemoji PNGs (cached under `.hmcl/emoji-cache/`). Assets are
/// downloaded on demand from the upstream Twemoji repository — they are **not** bundled or
/// re-distributed. When an image is not yet cached, the original (monochrome) emoji text is
/// shown and the download proceeds in the background for next time.
public final class EmojiImages {

    /// Upstream Twemoji asset base (the maintained jdecked/twemoji fork), 72x72 PNGs.
    /// Default download source — kept pointing at the original repo to avoid re-distribution.
    private static final String BASE_URL =
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/";

    private static final Path CACHE_DIR = Metadata.HMCL_LOCAL_HOME.resolve("emoji-cache");

    private static final ConcurrentHashMap<String, Image> MEMORY = new ConcurrentHashMap<>();
    private static final java.util.Set<String> downloading = ConcurrentHashMap.newKeySet();

    private static final ExecutorService DOWNLOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "emoji-downloader");
        t.setDaemon(true);
        return t;
    });

    /// Whether colour-emoji rendering is enabled (opt-in, chat-only). Set from chat settings.
    private static volatile boolean enabled = false;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private EmojiImages() {
    }

    /// Whether the string contains at least one renderable emoji code point.
    public static boolean containsEmoji(String text) {
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isEmojiBase(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /// Splits a string into a list of JavaFX nodes: plain {@link Text} for normal text and
    /// {@link ImageView} for emoji clusters (when the colour image is available). Falls back
    /// to a {@link Text} run for emoji whose image is not yet cached.
    public static List<Node> toNodes(String text, double fontSize) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (isEmojiBase(cp)) {
                int clusterEnd = consumeCluster(text, i);
                String cluster = text.substring(i, clusterEnd);
                String filename = toFilename(cluster);
                Image image = imageFor(filename);
                if (image != null) {
                    if (plain.length() > 0) {
                        nodes.add(new Text(plain.toString()));
                        plain.setLength(0);
                    }
                    ImageView view = new ImageView(image);
                    double size = fontSize * 1.25;
                    view.setFitWidth(size);
                    view.setFitHeight(size);
                    view.setPreserveRatio(true);
                    nodes.add(view);
                } else {
                    plain.append(cluster); // not cached yet — show monochrome text for now
                }
                i = clusterEnd;
            } else {
                plain.appendCodePoint(cp);
                i += charCount;
            }
        }
        if (plain.length() > 0) {
            nodes.add(new Text(plain.toString()));
        }
        return nodes;
    }

    /// Consumes an emoji grapheme cluster starting at index `start`: the base plus any
    /// variation selectors, skin-tone modifiers, keycap, and ZWJ-joined emoji.
    private static int consumeCluster(String text, int start) {
        int i = start + Character.charCount(text.codePointAt(start));
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF)) {
                i += Character.charCount(cp); // modifier
            } else if (cp == 0x200D) {
                i += 1; // ZWJ
                if (i < text.length()) {
                    int next = text.codePointAt(i);
                    i += Character.charCount(next); // the joined emoji
                }
            } else {
                break;
            }
        }
        return i;
    }

    /// Builds the Twemoji filename for an emoji cluster: code points in lowercase hex joined
    /// by '-', dropping U+FE0F (variation selector) per Twemoji's convention.
    private static String toFilename(String cluster) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cluster.length()) {
            int cp = cluster.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(Integer.toHexString(cp));
        }
        return sb.toString();
    }

    /// Returns the cached emoji image, loading from the on-disk cache if present, otherwise
    /// returns null and starts a background download for next time.
    private static Image imageFor(String filename) {
        Image cached = MEMORY.get(filename);
        if (cached != null) return cached;

        Path file = CACHE_DIR.resolve(filename + ".png");
        if (Files.isRegularFile(file)) {
            try {
                Image image = new Image(file.toUri().toString(), false);
                if (!image.isError()) {
                    MEMORY.put(filename, image);
                    return image;
                }
            } catch (RuntimeException ignored) {
            }
        }
        download(filename, file);
        return null;
    }

    private static void download(String filename, Path file) {
        if (!downloading.add(filename)) return;
        DOWNLOADER.submit(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + filename + ".png"))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "HMCL-AE").GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200 && response.body().length > 0) {
                    Files.createDirectories(CACHE_DIR);
                    Files.write(file, response.body());
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            } finally {
                downloading.remove(filename);
            }
        });
    }

    /// Conservative emoji-base detection: pictographs, dingbats, misc symbols, flags and
    /// cards. Deliberately excludes the arrow/general-symbol ranges that the launcher UI
    /// uses, so enabling colour emoji never turns UI glyphs into images.
    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)   // symbols & pictographs (incl. supplemental/extended)
                || (cp >= 0x1F000 && cp <= 0x1F0FF) // mahjong/dominoes/cards
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // regional indicators (flags)
                || (cp >= 0x2600 && cp <= 0x26FF)   // miscellaneous symbols
                || (cp >= 0x2700 && cp <= 0x27BF)   // dingbats
                || cp == 0x2B50 || cp == 0x2B55     // star, circle
                || cp == 0x2764;                    // heart
    }
}
