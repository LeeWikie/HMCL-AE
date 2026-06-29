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
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Base64;
import java.util.Map;

/// Google Cloud Vision OCR client.
///
/// Per <https://cloud.google.com/vision/docs/ocr>: POST to {@code images:annotate?key=KEY}
/// with {@code requests:[{image:{content:<base64>}, features:[{type:TEXT_DETECTION}]}]}.
/// The recognized text is {@code responses[0].fullTextAnnotation.text} (falling back to
/// the first {@code textAnnotations} description).
@NotNullByDefault
public final class GoogleVisionOcrClient implements OcrClient {

    private final String endpoint;
    private final String apiKey;

    public GoogleVisionOcrClient(String endpoint, String apiKey) {
        this.endpoint = endpoint.isEmpty()
                ? "https://vision.googleapis.com/v1/images:annotate" : endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public String recognize(byte[] imageData, String mimeType) throws Exception {
        if (apiKey.isEmpty()) {
            throw new RuntimeException("Google Cloud Vision needs an API key.");
        }

        JsonObject image = new JsonObject();
        image.addProperty("content", Base64.getEncoder().encodeToString(imageData));
        JsonObject feature = new JsonObject();
        feature.addProperty("type", "TEXT_DETECTION");
        JsonArray features = new JsonArray();
        features.add(feature);
        JsonObject request = new JsonObject();
        request.add("image", image);
        request.add("features", features);
        JsonArray requests = new JsonArray();
        requests.add(request);
        JsonObject payload = new JsonObject();
        payload.add("requests", requests);

        String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "key=" + NetworkUtils.encodeURL(apiKey);
        String body = OcrHttp.postJson(url, payload.toString(), Map.of());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray responses = root.getAsJsonArray("responses");
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        JsonObject resp = responses.get(0).getAsJsonObject();
        if (resp.has("error")) {
            JsonObject err = resp.getAsJsonObject("error");
            throw new RuntimeException("Google Vision error: "
                    + (err.has("message") ? err.get("message").getAsString() : err.toString()));
        }
        if (resp.has("fullTextAnnotation")) {
            JsonObject full = resp.getAsJsonObject("fullTextAnnotation");
            if (full.has("text")) return full.get("text").getAsString().trim();
        }
        JsonArray annotations = resp.getAsJsonArray("textAnnotations");
        if (annotations != null && !annotations.isEmpty()) {
            JsonObject first = annotations.get(0).getAsJsonObject();
            if (first.has("description")) return first.get("description").getAsString().trim();
        }
        return "";
    }
}
