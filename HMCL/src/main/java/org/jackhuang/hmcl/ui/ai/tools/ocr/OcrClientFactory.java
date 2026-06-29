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
package org.jackhuang.hmcl.ui.ai.tools.ocr;

import org.jackhuang.hmcl.ai.ocr.AiOcrConfig;
import org.jackhuang.hmcl.ai.ocr.OcrProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Builds an {@link OcrClient} from an {@link AiOcrConfig}, routing on the configured
/// {@link OcrProvider}. Returns {@code null} for providers that are presets only (no
/// client implemented yet) so callers can surface a clear "尚未接入" message.
@NotNullByDefault
public final class OcrClientFactory {

    private OcrClientFactory() {
    }

    @Nullable
    public static OcrClient build(AiOcrConfig config) {
        OcrProvider provider = config.resolveProvider();
        return switch (provider) {
            case OCR_SPACE -> new OcrSpaceClient(config.getEndpoint(), config.getApiKey(), config.getLanguage());
            case VISION_LLM -> new VisionLlmOcrClient(config.getEndpoint(), config.getApiKey(), config.getModel());
            case BAIDU -> new BaiduOcrClient(config.getEndpoint(), config.getApiKey(), config.getSecretKey());
            case GOOGLE -> new GoogleVisionOcrClient(config.getEndpoint(), config.getApiKey());
            case UMI_OCR -> new UmiOcrClient(config.getEndpoint());
            // Preset-only providers — client not yet implemented.
            case TENCENT, ALIYUN, AZURE, PADDLE_OCR -> null;
        };
    }
}
