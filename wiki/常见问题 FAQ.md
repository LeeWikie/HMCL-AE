# 常见问题 FAQ

### 为什么需要 API Key?不是说能免费玩吗?

HMCL-AE **启动器本身是免费的、开源的**。但 AI agent 需要调用大语言模型——这些模型的 API 费用由各自的**服务商**(OpenAI、DeepSeek、Anthropic 等)收取,**不归 HMCL-AE 承担**。

你需要自己去对应服务商注册并获取 API Key。

### 哪些模型推荐?

- **性价比**: DeepSeek V3(便宜、中文好、国内直连)
- **智能度**: Claude Opus 4.8、GPT-4.1(推理强,但贵)
- **中间方案**: DeepSeek-R1(推理强,中文一流)

### 为什么要"联网搜索 Key"?web_search 不是浏览器自带的吗?

`web_search` 调用的是**专业搜索引擎 API**(Tavily / SearXNG),它们专门为 AI 搜索设计(返回结构化摘要 + 链接)。这和你在浏览器里打字查不一样——agent 需要结构化搜出来的结果。这些服务有些需要 Key(Tavily),有些可以自托管(SearXNG)。

### 我的彩色 emoji 不显示

需要在**聊天设置**(聊天界面内的设置图标)里打开「彩色 Emoji(联网)」,并且确保能联网——emoji 图片是从 Noto Emoji 仓库**按需下载**的,有网络访问才会加载。

### 改过的设置怎么没生效?

HMCL 对某些设置**退出时覆写**(如 GameSettings)。所以如果通过 `config-hmcl` skill 修改实例配置,需要在其对应字段标记为"覆盖"(override)才会保留。具体看 `config-hmcl` 技能文档。

### 换个实例怎么样才能让 AI 知道?

AI 对话会自动拿到"当前选中实例"的信息。你在启动器主界面左上角切换实例后,新建会话或在已有会话里问 AI,它就能看到新实例了。

### 我的 API Key 会不会泄露?

- 代码层面:API Key 以 Base64 编码存储在 `.hmcl/ai-settings.json`(**不是加密**)。建议确保运行环境可信(自己的电脑)。
- 隐私层面:AI 请求直接发往**你自己配的服务商**——HMCL-AE 的作者不会接触到你的 Key 和对话内容。

### 支持哪些操作系统/CPU?

继承了 HMCL 的全平台支持:Windows、Linux、macOS、FreeBSD;x86、ARM、RISC-V、MIPS、LoongArch。详见 [PLATFORM.md](https://github.com/LeeWikie/HMCL-AE/blob/ai-feature/docs/PLATFORM.md)。

### 为什么是 GPLv3?

因为 HMCL 是 GPLv3。HMCL-AE 作为修改版,**必须使用相同的许可证**。GPLv3 有"传染性"——任何基于 GPLv3 代码的修改都必须同样以 GPLv3 开源。这是法律要求,也是选择。

### OAuth 登录(微软账号)什么时候能自动化?

在计划中——离线账号做起来简单,但微软 OAuth 需要走浏览器/设备码流程的交互。排在 Beta 之后的批次。
