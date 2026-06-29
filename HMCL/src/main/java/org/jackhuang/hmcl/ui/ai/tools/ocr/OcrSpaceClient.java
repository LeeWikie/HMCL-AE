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
import java.util.LinkedHashMap;
import java.util.Map;

/// OCR.space free OCR API client.
///
/// Verified against <https://ocr.space/ocrapi>: POST form-urlencoded to
/// {@code /parse/image} with the API key in the {@code apikey} header and the image as a
/// {@code base64Image} data-URI. The response is JSON: {@code ParsedResults[].ParsedText},
/// with {@code IsErroredOnProcessing} / {@code ErrorMessage} on failure. The public free
/// key {@code helloworld} is used when none is configured.
@NotNullByDefault
public final class OcrSpaceClient implements OcrClient {

    private final String endpoint;
    private final String apiKey;
    private final String language;

    public OcrSpaceClient(String endpoint, String apiKey, String language) {
        this.endpoint = endpoint.isEmpty() ? "https://api.ocr.space/parse/image" : endpoint;
        this.apiKey = apiKey.isEmpty() ? "helloworld" : apiKey;
        this.language = language;
    }

    @Override
    public String recognize(byte[] imageData, String mimeType) throws Exception {
        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageData);
        StringBuilder form = new StringBuilder();
        form.append("base64Image=").append(NetworkUtils.encodeURL(dataUri));
        form.append("&OCREngine=2");
        form.append("&isOverlayRequired=false");
        if (!language.isEmpty()) {
            form.append("&language=").append(NetworkUtils.encodeURL(language));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("apikey", apiKey);

        String body = OcrHttp.postForm(endpoint, form.toString(), headers);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        if (root.has("IsErroredOnProcessing") && root.get("IsErroredOnProcessing").getAsBoolean()) {
            throw new RuntimeException("OCR.space error: " + errorMessage(root));
        }

        JsonArray results = root.getAsJsonArray("ParsedResults");
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            JsonObject r = results.get(i).getAsJsonObject();
            if (r.has("ParsedText")) {
                text.append(r.get("ParsedText").getAsString());
            }
        }
        return text.toString().trim();
    }

    private static String errorMessage(JsonObject root) {
        if (root.has("ErrorMessage")) {
            return root.get("ErrorMessage").isJsonArray()
                    ? root.getAsJsonArray("ErrorMessage").toString()
                    : root.get("ErrorMessage").getAsString();
        }
        return "unknown error";
    }
}
