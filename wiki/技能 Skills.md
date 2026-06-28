# 技能 Skills

Skills 是 AI agent 可以遵循的**领域工作流**——一段结构化的指引,告诉 agent 怎么操作特定的 HMCL 配置系统。

它们本质上是 `.md` 文件,存放在 `.hmcl/ai-skills/<技能名>/SKILL.md`。

## 内置技能

HMCL-AE 发布时自带两个内置技能:

### config-hmcl-ae —— 修改 AE 自身的配置

让 AI 能够修改属于 HMCL-AE 的配置文件:
- `ai-settings.json` — 提供商列表、API Key、模型、审批模式等
- `ai-mcp-settings.json` — MCP 服务器列表
- `ai-search-settings.json` — 联网搜索配置
- `ai-chat-settings.json` — 聊天设置(用户名、字体大小、消息样式等)
- `.hmcl/ai-skills/` — 技能文件本身

### config-hmcl —— 修改 HMCL 原生配置

让 AI 能够修改 HMCL 启动器的原生配置:
- `launcher-settings.json` — 全局启动器设置
- `game-settings.json` — 游戏预设配置
- `<gameDir>/versions/<实例>/hmcl/instance.json` — 单实例设置(含 Java 参数、内存、窗口等)

## 技能是如何工作的

1. AI 启动时,系统提示词里会列出已启用的技能及其简介。
2. 当 AI 需要操作配置时,它可以 `read` 对应的 SKILL.md 获取完整指引。
3. SKILL.md 包含:配置文件的位置、JSON Schema 版本、关键字段含义、修改后如何生效。

这样 AI 就不需要把 HMCL 的配置结构背下来——需要时读技能文档即可。

## 添加自定义技能

1. 在 `.hmcl/ai-skills/` 下新建一个目录。
2. 在目录里放一个 `SKILL.md`,按"背景 → 你要做的事 → 具体步骤 → 注意事项"结构写。
3. AI 设置页刷新后即可加载。

示例结构:
```
.hmcl/ai-skills/
├── config-hmcl-ae/
│   └── SKILL.md
├── config-hmcl/
│   └── SKILL.md
└── my-custom-skill/        ← 你加的
    └── SKILL.md
```

## MCP 工具

HMCL-AE 也支持通过 **MCP (Model Context Protocol)** 接入外部工具。在 **「AI 设置 → MCP」** 中添加远程 MCP 服务器的 SSE 端点,工具会自动注册。
