# HMCL-AE 详细情况

> 最后更新: 2026-06-28

## 概述

HMCL-AE (Agent Experience) 为 Hello Minecraft! Launcher 集成了 AI 助手功能。用户可以在启动器内部直接与 AI 对话，分析崩溃报告、管理模组、排查启动问题。

## 功能列表

### 已实现

- **多模型对话**: 支持 OpenAI / DeepSeek / Ollama 等多种提供商，可自由切换模型
- **Markdown 渲染**: AI 回复支持粗体、斜体、代码块、表格等格式化
- **流式输出**: token 级流式传输，实时显示 AI 回复
- **崩溃分析**: 自动解析 Minecraft 崩溃报告（异常类型、堆栈、模组列表、置信度评估）
- **模组管理**: 启用/禁用模组（.jar 重命名），文件备份/恢复（SHA-256）
- **日志读取**: 读取 latest.log 和 HMCL 日志
- **提供商管理**: 多提供商配置（API 端点/密钥/模型列表），弹窗式添加/编辑
- **模型管理**: 每个提供商可添加多个模型，支持别名，API 自动获取模型列表
- **会话管理**: 多对话会话，自动命名，搜索历史
- **聊天设置**: Markdown 开关、用户名、字体大小、消息样式等
- **自动补全**: 输入框内 `/` 命令和 `@` 会话引用

### 待实现

- [ ] MCP 支持（需升级 LangChain4j 版本）
- [ ] RAG 知识库（接入 MC Wiki）
- [ ] 消息编辑器（编辑已发送消息重试）
- [ ] 模型批量测试

## 技术架构

```
HMCL-AE
├── HMCL/                    # 主启动器 (JavaFX UI)
│   └── src/main/java/org/jackhuang/hmcl/ui/ai/
│       ├── AIMainPage.java          # AI 聊天主界面 (~3400 行)
│       └── MarkdownMessageView.java # Markdown 渲染组件
├── HMCLAI/                  # AI 核心模块 (独立 Gradle 子项目)
│   └── src/main/java/org/jackhuang/hmcl/ai/
│       ├── agent/           # ChatAgent, ChatAgentFactory
│       ├── langchain4j/     # LangChain4j 适配器
│       ├── llm/             # 原生 HTTP 客户端 (回退方案)
│       ├── tools/           # 工具系统 (CrashAnalyzer, ModToggle, FileBackup, LogReader)
│       ├── markdown/        # Markdown 渲染器
│       └── AiSettings.java  # 设置持久化
└── docs/                    # 文档
```

### 核心依赖

- LangChain4j 1.15.1 (OpenAI 兼容适配)
- commonmark-java 0.24.0 (Markdown 解析)
- JFoenix (Material Design 组件)
- JavaFX 21 + Gson

## 开发环境

- **JDK 17** (编译目标)
- **Gradle 9.4.0**
- 构建: `./gradlew :HMCL:shadowJar`
- 运行: `java -jar HMCL/build/libs/HMCL-3.16.SNAPSHOT.jar`
- 配置文件路径: `.hmcl/ai-settings.json`, `.hmcl/ai-sessions.json`, `.hmcl/ai-chat-settings.json`

## 已知问题

1. `Math.clamp()` 在 JDK 17 下不可用，已改为 `Math.max/Math.min`
2. `langchain4j-mcp` 模块在 1.15.1 版本不存在，需要升级
3. Shadow jar 的 `minimize` 需排除 LangChain4j SPI 文件
4. 流式传输不支持工具调用（工具调用在当前轮先收集完整回复）

## 贡献者

- opencode — 初始架构和 UI 框架
- Neryc (LeeWikie) — 后续完善和维护
