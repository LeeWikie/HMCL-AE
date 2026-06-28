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

import javafx.scene.image.Image;
import org.jackhuang.hmcl.Metadata;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/// Renders emoji as inline MONOCHROME (black-and-white) images, rasterised locally from the
/// bundled Noto Emoji font via AWT/Java2D. This is the default emoji rendering for the AI chat:
/// it fills the gaps the system font lacks (e.g. newer emoji like 🧋 bubble tea / 🧃 juice)
/// while keeping the native black-and-white look — with no network required.
///
/// IMPORTANT: the font is loaded ONLY through {@link java.awt.Font#createFont} for offline
/// rasterisation; it is NEVER registered with JavaFX ({@code javafx.scene.text.Font.loadFont}),
/// so it cannot enter Prism's font-fallback chain and therefore cannot affect text rendering
/// anywhere else in the launcher UI. (A global JavaFX fallback font was the cause of an earlier
/// launcher-wide aliasing regression, which is why this path uses AWT rasterisation instead.)
public final class EmojiMonoImages {

    private static final int RASTER_SIZE = 72;
    private static final Path CACHE_DIR = Metadata.HMCL_LOCAL_HOME.resolve("emoji-mono-cache");
    private static final ConcurrentHashMap<String, Image> MEMORY = new ConcurrentHashMap<>();

    private static final Object FONT_LOCK = new Object();
    private static volatile boolean fontInitialised = false;
    private static Font baseFont; // AWT font, already derived to RASTER_SIZE; null if unavailable

    private EmojiMonoImages() {
    }

    private static Font font() {
        if (fontInitialised) return baseFont;
        synchronized (FONT_LOCK) {
            if (fontInitialised) return baseFont;
            try (InputStream in = EmojiMonoImages.class.getResourceAsStream("/assets/font/NotoEmoji-Regular.ttf")) {
                if (in != null) {
                    baseFont = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont((float) RASTER_SIZE);
                }
            } catch (Exception ignored) {
                baseFont = null;
            }
            fontInitialised = true;
            return baseFont;
        }
    }

    /// Returns a black-and-white image for the emoji cluster, or {@code null} if it cannot be
    /// rendered (font unavailable, or the glyph is not in the font) so the caller can fall back
    /// to plain text. Synchronous: rasterises on first use, then serves from memory/disk cache.
    public static Image imageFor(String cluster, String key) {
        Image cached = MEMORY.get(key);
        if (cached != null) return cached;

        Path file = CACHE_DIR.resolve(key + ".png");
        if (Files.isRegularFile(file)) {
            try {
                Image image = new Image(file.toUri().toString(), false);
                if (!image.isError()) {
                    MEMORY.put(key, image);
                    return image;
                }
            } catch (RuntimeException ignored) {
            }
        }

        Font f = font();
        if (f == null) return null;

        // AWT cannot shape ZWJ sequences; render the base code points (minus VS16/ZWJ).
        String draw = stripModifiers(cluster);
        if (draw.isEmpty() || f.canDisplayUpTo(draw) != -1) {
            return null; // glyph not present in Noto Emoji -> let the caller show text
        }

        try {
            BufferedImage img = new BufferedImage(RASTER_SIZE, RASTER_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(f);
            g.setColor(Color.BLACK);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(draw);
            int x = Math.max(0, (RASTER_SIZE - w) / 2);
            int y = (RASTER_SIZE - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(draw, x, y);
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            byte[] bytes = bos.toByteArray();
            try {
                Files.createDirectories(CACHE_DIR);
                Files.write(file, bytes);
            } catch (Exception ignored) {
            }
            Image image = new Image(new ByteArrayInputStream(bytes), RASTER_SIZE, RASTER_SIZE, true, true);
            if (image.isError()) return null;
            MEMORY.put(key, image);
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripModifiers(String cluster) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cluster.length()) {
            int cp = cluster.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x200D) continue; // variation selector / ZWJ
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }
}
