package org.jackhuang.hmcl.ai.ocr;

import org.jetbrains.annotations.Nullable;

/// OCR provider definitions with default endpoints and credential requirements.
///
/// Mirrors {@link org.jackhuang.hmcl.ai.search.SearchProvider}. The
/// {@link #implemented} flag marks providers that have a working client in
/// {@code org.jackhuang.hmcl.ui.ai.tools.ocr}; providers with {@code implemented=false}
/// are presets only — selecting them surfaces a "尚未接入" notice in the tool.
public enum OcrProvider {

    // ---- Implemented (working clients) ----
    OCR_SPACE  ("OCR.space (免费 key)",       "https://api.ocr.space/parse/image",
            true,  false, false, true,  "免费 key 'helloworld'，或在 ocr.space 申请。语言填 eng/chs。"),
    VISION_LLM ("视觉大模型 (OpenAI 兼容)",    "https://api.openai.com/v1/chat/completions",
            true,  false, false, true,  "把截图发给任意 OpenAI 兼容 /chat/completions 视觉端点，需填模型(如 gpt-4o-mini)。"),
    BAIDU      ("百度 OCR",                    "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic",
            true,  true,  false, true,  "需 API Key + Secret Key（百度智能云控制台）。"),
    GOOGLE     ("Google Cloud Vision",         "https://vision.googleapis.com/v1/images:annotate",
            true,  false, false, true,  "API Key 作为 ?key= 传入，开启 Vision API。"),
    UMI_OCR    ("Umi-OCR (本地)",              "http://127.0.0.1:1224/api/ocr",
            false, false, true,  true,  "本地运行 Umi-OCR 并开启 HTTP 服务，无需 Key。"),

    // ---- Preset only (client not yet implemented) ----
    TENCENT    ("腾讯云 OCR",                  "https://ocr.tencentcloudapi.com",
            true,  true,  false, false, "需 TC3-HMAC-SHA256 签名，暂未接入。"),
    ALIYUN     ("阿里云 OCR",                  "https://ocr-api.cn-hangzhou.aliyuncs.com",
            true,  true,  false, false, "需阿里云签名，暂未接入。"),
    AZURE      ("Azure Computer Vision",       "https://<resource>.cognitiveservices.azure.com/vision/v3.2/read/analyze",
            true,  false, false, false, "Read API 为异步轮询，暂未接入。"),
    PADDLE_OCR ("PaddleOCR (本地)",            "http://127.0.0.1:8868/predict/ocr_system",
            false, false, true,  false, "本地部署形态各异，暂未接入。");

    private final String displayName;
    private final String defaultEndpoint;
    private final boolean requiresApiKey;
    private final boolean requiresSecret;
    private final boolean local;
    private final boolean implemented;
    private final String note;

    OcrProvider(String displayName, String defaultEndpoint, boolean requiresApiKey,
                boolean requiresSecret, boolean local, boolean implemented, String note) {
        this.displayName = displayName;
        this.defaultEndpoint = defaultEndpoint;
        this.requiresApiKey = requiresApiKey;
        this.requiresSecret = requiresSecret;
        this.local = local;
        this.implemented = implemented;
        this.note = note;
    }

    public String getDisplayName()    { return displayName; }
    public String getDefaultEndpoint(){ return defaultEndpoint; }
    public boolean requiresApiKey()   { return requiresApiKey; }
    public boolean requiresSecret()   { return requiresSecret; }
    public boolean isLocal()          { return local; }
    public boolean isImplemented()    { return implemented; }
    /// Whether the vision-LLM backend (which needs a model id) is selected.
    public boolean requiresModel()    { return this == VISION_LLM; }
    public String getNote()           { return note; }

    @Nullable
    public static OcrProvider fromId(String id) {
        if (id == null) return null;
        try { return valueOf(id); }
        catch (IllegalArgumentException e) { return null; }
    }
}
