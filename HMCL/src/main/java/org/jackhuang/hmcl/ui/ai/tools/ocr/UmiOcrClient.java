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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Base64;
import java.util.Map;

/// Umi-OCR local HTTP API client (<https://github.com/hiroi-sora/Umi-OCR>).
///
/// POST JSON {@code {"base64": "<raw base64, no data-uri prefix>"}} to
/// {@code http://127.0.0.1:1224/api/ocr}. Response: {@code code} 100 = success with a
/// {@code data} array of {@code {text}} blocks; 101 = no text found; anything else is an
/// error whose message is in {@code data}.
@NotNullByDefault
public final class UmiOcrClient implements OcrClient {

    private final String endpoint;

    public UmiOcrClient(String endpoint) {
        this.endpoint = endpoint.isEmpty() ? "http://127.0.0.1:1224/api/ocr" : endpoint;
    }

    @Override
    public String recognize(byte[] imageData, String mimeType) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("base64", Base64.getEncoder().encodeToString(imageData));

        String body = OcrHttp.postJson(endpoint, payload.toString(), Map.of());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code == 101) {
            return ""; // no text found
        }
        JsonElement data = root.get("data");
        if (code != 100) {
            throw new RuntimeException("Umi-OCR error (code " + code + "): "
                    + (data != null ? data.toString() : body));
        }
        if (data == null || !data.isJsonArray()) {
            return "";
        }
        JsonArray blocks = data.getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject b = blocks.get(i).getAsJsonObject();
            if (b.has("text")) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(b.get("text").getAsString());
            }
        }
        return sb.toString();
    }
}
