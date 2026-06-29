package org.jackhuang.hmcl.ai.ocr;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// Persisted configuration for the image-OCR feature, mirroring
/// {@link org.jackhuang.hmcl.ai.search.AiSearchConfig}.
///
/// Serialized to `{configDir}/ai-ocr-settings.json`. The provider id maps to an
/// {@link OcrProvider}; provider-specific credentials live in {@link #apiKey} /
/// {@link #secretKey}; {@link #model} is used by the vision-LLM backend; and
/// {@link #language} hints OCR engines that need an explicit language code.
@NotNullByDefault
public final class AiOcrConfig {

    /// The file name used for persisting OCR settings.
    public static final String FILE_NAME = "ai-ocr-settings.json";

    @SerializedName("provider")
    private String provider = OcrProvider.OCR_SPACE.name();

    @SerializedName("endpoint")
    private String endpoint = OcrProvider.OCR_SPACE.getDefaultEndpoint();

    @SerializedName("apiKey")
    private String apiKey = "";

    /// Secondary credential required by some providers (e.g. Baidu Secret Key,
    /// Tencent/Aliyun secret). Unused by single-key providers.
    @SerializedName("secretKey")
    private String secretKey = "";

    /// Model id for the vision-LLM backend (e.g. `gpt-4o-mini`, `qwen-vl-max`).
    @SerializedName("model")
    private String model = "";

    /// Optional language hint (e.g. `eng`, `chs`, `auto`) for engines that need it.
    @SerializedName("language")
    private String language = "";

    @SerializedName("enabled")
    private boolean enabled = false;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /// Resolves the configured provider enum, falling back to {@link OcrProvider#OCR_SPACE}
    /// when the stored id is unknown.
    public OcrProvider resolveProvider() {
        OcrProvider p = OcrProvider.fromId(provider);
        return p != null ? p : OcrProvider.OCR_SPACE;
    }
}
