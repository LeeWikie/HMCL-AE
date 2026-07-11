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
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;

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

    /// Upstream Noto Emoji colour asset base (googlefonts/noto-emoji), 128px PNGs.
    /// Default download source — kept pointing at the original repo to avoid re-distribution.
    private static final String BASE_URL =
            "https://cdn.jsdelivr.net/gh/googlefonts/noto-emoji@main/png/128/";

    /// Flag emoji (regional-indicator pairs) are absent from Noto's `png/128/` directory, so
    /// requesting `emoji_u1f1fa_1f1f8.png` there always 404s. Flags are instead fetched from
    /// Twemoji (jdecked maintained fork, 72x72 PNGs), whose filenames are hyphen-joined code
    /// points with no `emoji_u` prefix and no FE0F (e.g. 1f1fa-1f1f8.png). Verified reachable;
    /// assets are downloaded on demand, not bundled or re-distributed.
    private static final String FLAG_BASE_URL =
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/";

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
    /// Null-safe: imported/legacy messages may carry a null content (BF P1).
    public static boolean containsEmoji(String text) {
        if (text == null || text.isEmpty()) return false;
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
        return toNodes(text, fontSize, Text::new);
    }

    /// Style-preserving variant: emoji still become {@link ImageView}s, but the surrounding text
    /// runs carry the given bold/italic/strikethrough (and the `md-text` style class) so Markdown
    /// emphasis wrapping an emoji — e.g. `**恭喜 🎉**` — is not silently dropped.
    public static List<Node> toNodes(String text, double fontSize, boolean bold, boolean italic, boolean strike) {
        return toNodes(text, fontSize, s -> styledRun(s, fontSize, bold, italic, strike));
    }

    private static List<Node> toNodes(String text, double fontSize, java.util.function.Function<String, Text> textFactory) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (isEmojiBase(cp) || isKeycapStart(text, i, cp)) {
                int clusterEnd = consumeCluster(text, i);
                String cluster = text.substring(i, clusterEnd);
                boolean flag = isFlagCluster(cluster);
                String filename = toFilename(cluster, flag);
                Image image = imageFor(filename, flag);
                if (image != null && !image.isError()) {
                    if (plain.length() > 0) {
                        nodes.add(textFactory.apply(plain.toString()));
                        plain.setLength(0);
                    }
                    ImageView view = new ImageView(image);
                    // Match the surrounding text height and sink slightly so the emoji sits on
                    // the text baseline instead of floating above it (TextFlow aligns an
                    // ImageView by its bottom edge).
                    double size = fontSize * 1.2;
                    view.setFitWidth(size);
                    view.setFitHeight(size);
                    view.setPreserveRatio(true);
                    view.setTranslateY(fontSize * 0.15);
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
            nodes.add(textFactory.apply(plain.toString()));
        }
        return nodes;
    }

    private static Text styledRun(String s, double fontSize, boolean bold, boolean italic, boolean strike) {
        Text t = new Text(s);
        FontWeight weight = bold ? FontWeight.BOLD : FontWeight.NORMAL;
        FontPosture posture = italic ? FontPosture.ITALIC : FontPosture.REGULAR;
        t.setFont(Font.font(null, weight, posture, fontSize));
        if (strike) t.setStrikethrough(true);
        t.getStyleClass().add("md-text");
        return t;
    }

    /// Package-private for tests: the download URL a cluster resolves to. Flag clusters go to
    /// Twemoji, everything else to Noto. Pure — touches neither cache nor network.
    static String assetUrlFor(String cluster) {
        boolean flag = isFlagCluster(cluster);
        return (flag ? FLAG_BASE_URL : BASE_URL) + toFilename(cluster, flag) + ".png";
    }

    /// Consumes an emoji grapheme cluster starting at index `start`: the base plus any
    /// variation selectors, skin-tone modifiers, keycap, and ZWJ-joined emoji. A flag is
    /// exactly two regional indicators consumed as one cluster (BF P9). Package-visible
    /// for unit tests.
    static int consumeCluster(String text, int start) {
        int firstCp = text.codePointAt(start);
        int i = start + Character.charCount(firstCp);
        // Flag = exactly two regional indicators paired into a single cluster.
        if (firstCp >= 0x1F1E6 && firstCp <= 0x1F1FF) {
            if (i < text.length()) {
                int second = text.codePointAt(i);
                if (second >= 0x1F1E6 && second <= 0x1F1FF) {
                    return i + Character.charCount(second);
                }
            }
            return i; // isolated regional indicator: treat as a single-character cluster
        }
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

    /// A flag emoji is a pair of regional-indicator symbols (U+1F1E6–U+1F1FF). Flags use a
    /// different asset source and filename convention than the rest of the emoji set.
    /// Package-visible for unit tests.
    static boolean isFlagCluster(String cluster) {
        int cp0 = cluster.codePointAt(0);
        if (cp0 < 0x1F1E6 || cp0 > 0x1F1FF) return false;
        int j = Character.charCount(cp0);
        if (j >= cluster.length()) return false;
        int cp1 = cluster.codePointAt(j);
        return cp1 >= 0x1F1E6 && cp1 <= 0x1F1FF;
    }

    /// Builds the asset filename for an emoji cluster (extension appended by the caller).
    ///
    /// Non-flag (Noto): "emoji_u" + code points in lowercase hex joined by '_', dropping U+FE0F
    /// but keeping U+200D (ZWJ), zero-padded to ≥4 digits. e.g. 🧋 -> emoji_u1f9cb.
    ///
    /// Flag (Twemoji): the two regional-indicator code points in natural lowercase hex joined by
    /// '-', no prefix, no FE0F. e.g. 🇺🇸 -> 1f1fa-1f1f8.
    /// Package-visible for unit tests.
    static String toFilename(String cluster, boolean flag) {
        StringBuilder sb = new StringBuilder(flag ? "" : "emoji_u");
        boolean first = true;
        int i = 0;
        while (i < cluster.length()) {
            int cp = cluster.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) continue;
            if (!first) sb.append(flag ? '-' : '_');
            // Twemoji flags use the natural (already 5-digit) hex; Noto zero-pads to ≥4 digits so
            // 5-digit pictographs stay unchanged (1f9cb) and ASCII keycap bases match (0031, not 31).
            sb.append(flag ? Integer.toHexString(cp) : String.format("%04x", cp));
            first = false;
        }
        return sb.toString();
    }

    /// Returns the cached emoji image, loading from the on-disk cache if present, otherwise
    /// returns null and starts a background download for next time.
    private static Image imageFor(String filename, boolean flag) {
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
        // Not cached on disk yet: kick a disk download for next launch, and return an
        // async-loading remote image so the colour emoji fills in as soon as it arrives —
        // no monochrome text fallback that would otherwise "cover" the colour version.
        String url = (flag ? FLAG_BASE_URL : BASE_URL) + filename + ".png";
        download(filename, file, url);
        Image remote = new Image(url, true);
        // A failed download must not poison the cache for the rest of the session: evict on
        // error so the next render retries (falling back to monochrome text meanwhile). The
        // listener fires on the FX thread after this method (called on the FX thread) has
        // already put the image, so remove(k, v) cannot race ahead of the put; remove(k, v)
        // only evicts this exact instance, never a newer retry.
        remote.errorProperty().addListener((o, ov, err) -> {
            if (err) MEMORY.remove(filename, remote);
        });
        if (remote.isError()) {
            return null; // failed synchronously (e.g. malformed URL): text fallback, no caching
        }
        MEMORY.put(filename, remote);
        return remote;
    }

    /// Builds the emoji download client. `java.net.http.HttpClient` already follows the
    /// user's proxy via the default `ProxySelector`, but it does NOT consult
    /// `Authenticator.setDefault`, so a username/password proxy would 407-fail every download
    /// unless the authenticator pushed by `ProxyManager` is attached explicitly (only attached
    /// when one has actually been pushed — see [ProxyAuthenticatorHolder#configure]).
    /// Package-visible for unit tests.
    static HttpClient newHttpClient() {
        return ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)))
                .build();
    }

    private static void download(String filename, Path file, String url) {
        if (!downloading.add(filename)) return;
        DOWNLOADER.submit(() -> {
            try {
                HttpClient client = newHttpClient();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
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
    /// A keycap emoji (0️⃣-9️⃣, #️⃣, *️⃣) is an ASCII digit/#/* base followed by an optional U+FE0F
    /// then the combining enclosing keycap U+20E3. Recognised ONLY in that full sequence so plain
    /// digits/#/* in ordinary text are never turned into images.
    private static boolean isKeycapStart(String text, int i, int cp) {
        if (cp != 0x23 && cp != 0x2A && !(cp >= 0x30 && cp <= 0x39)) {
            return false;
        }
        int j = i + 1; // base is a single BMP char
        if (j < text.length() && text.charAt(j) == 0xFE0F) {
            j++;
        }
        return j < text.length() && text.charAt(j) == 0x20E3;
    }

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
