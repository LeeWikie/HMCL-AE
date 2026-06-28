# 配置 AI 提供商

## 支持的协议族

| 协议族 | 说明 | 示例 |
|---|---|---|
| **OpenAI Completions** | OpenAI `/v1/chat/completions` 兼容 | OpenAI、DeepSeek、Groq、Moonshot、智谱等 |
| **OpenAI Reasoning** | 支持 reasoning 模式(如 o1/o3/DeepSeek-R1) | OpenAI o1/o3、DeepSeek-R1 |
| **Anthropic** | Anthropic Messages API | Claude 系列 |
| **REST API** | 旧协议,已废弃,不建议使用 | — |

## 常见服务商配置参考

### OpenAI
- 端点: `https://api.openai.com`
- 模型: `gpt-4o` / `gpt-4.1`
- 需要境外网络访问

### DeepSeek
- 端点: `https://api.deepseek.com`
- 模型: `deepseek-chat` / `deepseek-reasoner`
- 国内可直连,性价比高

### Anthropic
- 端点: `https://api.anthropic.com`
- 模型: `claude-sonnet-4-6` / `claude-opus-4-8`
- 需要境外网络访问

### 其他兼容服务
任何遵循 OpenAI `/v1/chat/completions` 格式的端点都可以——选择「OpenAI Completions」协议族并填入对应端点。

## 模型发现

HMCL-AE 会尝试调用提供商的 `/v1/models`(或 Anthropic 的对应端点)获取模型列表。如果列表为空或拉取失败,可以在模型卡片的对话框里**手动输入模型名**。

## 联网搜索

如果你希望 AI 能搜索最新信息(新闻、Wiki、文档),需要在 **「AI 设置 → 联网搜索」** 里额外配置搜索 Key:

- **Tavily**: 需要 API Key(默认指向 `https://api.tavily.com/search`)
- **SearXNG**: 自托管实例,填入你自己的 SearXNG 端点(可不填 Key)

## API Key 存储

API Key 会被 **Base64 编码**后存储在 `.hmcl/ai-settings.json` 中。这**不是加密**,仅防止一眼读取。请确保你的运行环境可信。
