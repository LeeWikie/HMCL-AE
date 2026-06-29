/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.ocr.AiOcrConfig;
import org.jackhuang.hmcl.ai.ocr.OcrProvider;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.ui.ai.tools.ocr.OcrClient;
import org.jackhuang.hmcl.ui.ai.tools.ocr.OcrClientFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/// AI tool that runs OCR on an image (typically a Minecraft screenshot) and returns the
/// recognized text, so the agent can read crash/error messages that the user only has as
/// a screenshot.
///
/// The backend is chosen by {@link AiOcrConfig} (configured in AI 设置 > OCR). The image
/// can be given as an absolute path, or as a file name relative to the selected instance's
/// {@code screenshots/} directory (pair with {@code list_screenshots}).
@NotNullByDefault
public final class OcrImageTool implements Tool {

    private static final int MAX_OUTPUT_CHARS = 8000;

    private final AiOcrConfig config;

    public OcrImageTool(AiOcrConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "ocr_image";
    }

    @Override
    public String getDescription() {
        return "Runs OCR on an image and returns the text inside it. Use this to READ a screenshot "
                + "(e.g. a crash/error the user only provided as a picture) — pair with list_screenshots. "
                + "Parameters: 'image' (required) - an absolute image path, or a file name relative to the "
                + "instance's screenshots/ directory; 'instance' (optional) - the instance id (defaults to the "
                + "selected instance). Requires OCR to be enabled and configured in AI 设置 > OCR.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (!config.isEnabled()) {
            return ToolResult.failure("OCR is not enabled. Ask the user to open AI 设置 > OCR, enable it and "
                    + "pick a provider (OCR.space has a free key; or a vision LLM / Baidu / Google Vision / "
                    + "local Umi-OCR).");
        }

        String image = InstanceToolSupport.string(parameters, "image");
        if (image == null) {
            return ToolResult.failure("No 'image' provided. Pass an absolute image path or a screenshot file name.");
        }

        Path resolved = resolveImage(image, InstanceToolSupport.string(parameters, "instance"));
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return ToolResult.failure("Image not found: " + image
                    + (resolved != null ? " (looked at " + resolved + ")" : "")
                    + ". Use list_screenshots to get a valid file name, or pass an absolute path.");
        }

        OcrProvider provider = config.resolveProvider();
        OcrClient client = OcrClientFactory.build(config);
        if (client == null) {
            return ToolResult.failure("The selected OCR provider '" + provider.getDisplayName()
                    + "' is not implemented yet (" + provider.getNote() + "). Switch to OCR.space, a vision LLM, "
                    + "Baidu, Google Vision or local Umi-OCR in AI 设置 > OCR.");
        }

        byte[] data;
        try {
            data = Files.readAllBytes(resolved);
        } catch (Exception e) {
            return ToolResult.failure("Failed to read image '" + resolved + "': " + e.getMessage());
        }
        if (data.length == 0) {
            return ToolResult.failure("Image file is empty: " + resolved);
        }

        String mime = mimeOf(resolved);
        try {
            String text = client.recognize(data, mime);
            if (text == null || text.isBlank()) {
                return ToolResult.success("(no text recognized in " + resolved.getFileName() + ")");
            }
            if (text.length() > MAX_OUTPUT_CHARS) {
                text = text.substring(0, MAX_OUTPUT_CHARS) + "\n…(truncated)";
            }
            return ToolResult.success("OCR text from " + resolved.getFileName()
                    + " (via " + provider.getDisplayName() + "):\n\n" + text);
        } catch (Exception e) {
            return ToolResult.failure("OCR failed via " + provider.getDisplayName() + ": " + e.getMessage());
        }
    }

    /// Resolves the image argument to a concrete path: an absolute/existing path is used
    /// directly; otherwise it is treated as a file name under the instance's screenshots dir.
    @Nullable
    private static Path resolveImage(String image, @Nullable String instanceParam) {
        try {
            Path direct = Path.of(image);
            if (direct.isAbsolute() || Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (RuntimeException ignored) {
            // Not a valid path on its own — fall through to screenshots-dir resolution.
        }

        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repository = profile.getRepository();
            String instance = instanceParam != null ? instanceParam : Profiles.getSelectedInstance();
            if (instance == null || !repository.hasVersion(instance)) {
                return null;
            }
            return repository.getRunDirectory(instance).resolve("screenshots").resolve(image);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String mimeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "image/png";
    }
}
