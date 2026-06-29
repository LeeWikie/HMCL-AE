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

/// Baidu AI (百度智能云) general text-recognition OCR client.
///
/// Two-step flow (per <https://ai.baidu.com/ai-doc/OCR/zk3h7xz52>):
/// 1. exchange API Key + Secret Key for an {@code access_token} at the OAuth endpoint;
/// 2. POST the base64 image (form-urlencoded {@code image=...}) to {@code general_basic}
///    with {@code ?access_token=...}. The response is {@code words_result[].words}.
@NotNullByDefault
public final class BaiduOcrClient implements OcrClient {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    private final String endpoint;
    private final String apiKey;
    private final String secretKey;

    public BaiduOcrClient(String endpoint, String apiKey, String secretKey) {
        this.endpoint = endpoint.isEmpty()
                ? "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic" : endpoint;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    @Override
    public String recognize(byte[] imageData, String mimeType) throws Exception {
        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            throw new RuntimeException("Baidu OCR needs both an API Key and a Secret Key.");
        }
        String token = fetchToken();

        String base64 = Base64.getEncoder().encodeToString(imageData);
        String form = "image=" + NetworkUtils.encodeURL(base64);
        String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "access_token=" + token;

        String body = OcrHttp.postForm(url, form, Map.of());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error_code")) {
            throw new RuntimeException("Baidu OCR error " + root.get("error_code").getAsString()
                    + ": " + (root.has("error_msg") ? root.get("error_msg").getAsString() : ""));
        }
        JsonArray words = root.getAsJsonArray("words_result");
        if (words == null || words.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            JsonObject w = words.get(i).getAsJsonObject();
            if (w.has("words")) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(w.get("words").getAsString());
            }
        }
        return sb.toString();
    }

    private String fetchToken() throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + NetworkUtils.encodeURL(apiKey)
                + "&client_secret=" + NetworkUtils.encodeURL(secretKey);
        String body = OcrHttp.postForm(TOKEN_URL, form, Map.of());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("access_token")) {
            throw new RuntimeException("Baidu token request failed: " + body);
        }
        return root.get("access_token").getAsString();
    }
}
