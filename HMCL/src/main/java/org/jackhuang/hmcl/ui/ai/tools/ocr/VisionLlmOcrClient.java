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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/// Vision-LLM OCR client: sends the image (as a base64 data-URI) to any OpenAI-compatible
/// {@code /chat/completions} endpoint that accepts {@code image_url} content, and asks the
/// model to transcribe all text verbatim.
///
/// Works with OpenAI (gpt-4o / gpt-4o-mini), Qwen-VL, GLM-4V and other OpenAI-compatible
/// vision endpoints. The recognized text is taken from {@code choices[0].message.content}.
@NotNullByDefault
public final class VisionLlmOcrClient implements OcrClient {

    private static final String PROMPT =
            "Extract ALL text visible in this image, transcribed verbatim and preserving line breaks. "
            + "Output ONLY the raw text — no commentary, no markdown, no explanation. "
            + "If there is no text, output exactly: (no text)";

    private final String endpoint;
    private final String apiKey;
    private final String model;

    public VisionLlmOcrClient(String endpoint, String apiKey, String model) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model.isEmpty() ? "gpt-4o-mini" : model;
    }

    @Override
    public String recognize(byte[] imageData, String mimeType) throws Exception {
        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageData);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", PROMPT);

        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", messages);
        payload.addProperty("temperature", 0);

        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }

        String body = OcrHttp.postJson(endpoint, payload.toString(), headers);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Vision LLM returned no choices: " + body);
        }
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String text = msg != null && msg.has("content") ? msg.get("content").getAsString() : "";
        return text.trim();
    }
}
