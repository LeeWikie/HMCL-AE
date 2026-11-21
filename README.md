<div align="center">
  <img src="HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL-AE" width="80"/>

  # HMCL-AE

  **基于 HMCL 的 Minecraft 启动器分支，内置可通过工具调用直接执行启动器操作的 AI 助手。**

  [下载](https://github.com/LeeWikie/HMCL-AE/releases/latest) · [更新日志](CHANGELOG.md) · [问题反馈](https://github.com/LeeWikie/HMCL-AE/issues) · [上游 HMCL](https://github.com/HMCL-dev/HMCL) · [English](docs/README.md)

  <sub>GPLv3 · Java 17+ · Windows / Linux / macOS / FreeBSD · v0.1.1-alpha</sub>
</div>

---

## 概述

HMCL-AE 面向熟悉 Minecraft 启动器、能自备模型 API Key 的玩家与整合包维护者，解决“配环境、装加载器与模组、查崩溃日志、改配置与存档操作繁琐”的问题。它在 [HMCL](https://github.com/HMCL-dev/HMCL) 中集成了一套基于原生 Function Calling 的 Agent：模型不是仅给文字建议，而是直接调用启动器内部 API 完成版本/加载器安装、模组与资源管理、崩溃诊断、账号与存档操作，并复用 HMCL 原有的下载源、镜像、账号体系与 Java 管理能力。

### 与上游 HMCL 的边界

- **HMCL-AE 新增**：
  - `HMCLAI/` 独立 Gradle 子模块：无 UI 依赖的 Agent 核心（会话与上下文压缩、LangChain4j 适配、工具注册与权限策略、护栏与失败信封、技能匹配、MCP 客户端、联网搜索、JSONL 诊断 Trace、内置模型参数库）。
  - `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/`：JavaFX 原生聊天与设置界面、Markdown 渲染与任务进度组件，以及对接 HMCL 核心 API 的 Minecraft 领域工具集与 OCR 客户端。
  - 构建与发布配置调整：Gradle 依赖引入（LangChain4j / CommonMark / RichTextFX / TestFX）、`-PheadlessTest` 无头测试参数与 `ae-release.yml` 发布工作流。
- **继承自上游 HMCL**：
  - `HMCLCore/`、`HMCLBoot/`、`minecraft/libraries/` 与 `HMCL/` 其余模块：多版本与多加载器（Forge / NeoForge / Fabric / Quilt / OptiFine）、整合包、Java 运行时管理、微软/离线/外置登录、Terracotta 联机及跨平台多架构支持。

## 安装与使用

### 直接运行预构建版本

1. 前往 [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest) 下载对应平台产物（`.exe` / `.jar` / `.sh` / `.deb`）。
2. 运行：Windows 双击 `HMCL-<版本>.exe`；Linux / macOS / FreeBSD 执行 `java -jar HMCL-<版本>.jar`（需 Java 17+，推荐 Java 21+）。
3. 配置模型：打开侧栏「AI 助手 -> AI 设置 -> 模型服务」，填入端点、API Key 与模型 ID。

说明：AI 功能需要自备 API Key，不内置免费模型。支持 OpenAI（Completions / Reasoning）、Anthropic Messages API，以及兼容 OpenAI 协议的第三方端点（DeepSeek、智谱、Moonshot 等）。

### 从源码构建与运行

环境要求：JDK 17+（带 JavaFX，CI 使用 Zulu JDK 17 `jdk+fx`）。

```bash
git clone https://github.com/LeeWikie/HMCL-AE.git
cd HMCL-AE
./gradlew :HMCL:run                # 编译并启动启动器
./gradlew :HMCL:makeExecutables    # 生成 .jar / .exe / .sh 到 HMCL/build/libs/
./gradlew :HMCL:makeDeb            # 可选：进一步打包 .deb
```

## 架构与工程要点

仓库包含 4 个主模块：`HMCLAI`（AI 核心，不依赖 JavaFX 界面）、`HMCL`（启动器界面与 Minecraft 领域工具）、`HMCLCore`（游戏仓库/下载/崩溃分析）、`HMCLBoot`（引导）。主要工程实现如下：

- **领域聚合工具集与热开关**：
  - 在 `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/AIMainPage.java` 注册了 **28** 个顶层内置工具（23 个常驻 + 5 个由 `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ToggleToolsBinder.java` 绑定到设置的运行时热开关工具：`shell`、`web_fetch`、`web_search`、`ocr_image`、`nbt`，开关切换即时生效，无需重启）。
  - 为控制工具 Schema 对上下文的占用，将高频 Minecraft 能力收敛为 6 个领域门面工具：`instance`（39 个本地实例/模组/世界/存档备份/资源包/光影/整合包/Java 动作）、`game`（3 个运行态动作）、`search`（6 个外部检索动作，Modrinth 与 CurseForge 并发查询合并）、`account`（5 个账号与皮肤动作）、`job`（3 个后台任务动作）、`nbt`（6 个存档 NBT 读写与玩家数据迁移动作），共分发 **62** 个具体操作。
  - 支持通过 `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/mcp/McpClientManager.java` 接入外部 MCP 服务器（stdio 与 Streamable HTTP 传输），发现的工具以 `mcp.<serverId>.` 前缀注册并支持白名单过滤。
- **护栏、审批策略与失败契约**：
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/AiExecutionPolicy.java` 实现 `Manual` / `Auto` / `yolo` 三档审批模式与独立的 `Plan` 模式（只读排查，拦截所有写操作），并对“无人值守轮次（如后台任务完成后的自动续轮）中的高危写操作”施加不可被任何模式绕过的硬阻断（结构化 `BlockReason`）。
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ToolFailures.java` 统一工具失败输出为结构化错误信封（携带真实候选名单、`Retryable: yes|no|later` 与下一步建议）；`HMCLAI/src/main/java/org/jackhuang/hmcl/ai/langchain4j/TerminalDenialRegistry.java` 负责拒绝态短路，防止模型在被拒后盲目重试。
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ReadLedger.java` 为文件编辑工具提供基于真实路径与字节级 SHA-256 的“先读后写”台账与外部变更陈旧检测；`DangerousCommands.java` 与 `CriticalOperations.java` 拦截高危 Shell 命令（包括禁止误杀 `java` / `javaw` 或启动器自身进程）及高危存档修改。
- **按需技能匹配与模型参数库**：
  - `HMCLAI/src/main/resources/assets/skills/` 内置 **32** 份 `SKILL.json` 操作剧本，由 `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/skills/SkillMatcher.java` 做确定性触发词匹配（NFKC 规范化、中英文区分词边界），避免全量注入系统提示词。
  - `HMCLAI/src/main/resources/assets/ai/model-library.json` 内置 **1,604** 条模型元数据（上下文窗口、最大输出、模态、定价、工具/视觉/推理能力），由 `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/ModelLibrary.java` 按精确匹配与保守规范化（小写 + 去除 `vendor/` 前缀）自动匹配；`ChatAgent.java` 在上下文接近窗口 90% 时自动触发会话压缩。
- **联网搜索、OCR 与可观测性**：
  - 联网搜索（`WebSearchTool.java`）已接入 Tavily、SearXNG、Bocha（博查）、Zhipu（智谱）4 种后端；截图 OCR（`OcrProvider.java`）已接入 OCR.space、视觉大模型、百度 OCR、Google Cloud Vision、Umi-OCR（本地）5 种后端。
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/trace/TraceRecorder.java` 提供可选的按会话 append-only JSONL 诊断 Trace，落盘前经 `Redactor.java` 对敏感字段逐层脱敏。
  - `HMCLAI/build.gradle.kts` 显式锁定 `jackson-databind` / `jackson-core` 至 `2.21.5`，修复 LangChain4j 传递依赖带来的 CVE-2026-54512 / 54513 / 54515。

## 测试与验证

项目使用 JUnit 5、Jimfs 与 TestFX 编写自动化测试。当前仓库包含：

- `HMCLAI/src/test/`：**90** 个测试类，覆盖 Agent 循环与压缩、护栏/审批策略、错误信封、ReadLedger 文件契约、拒绝态短路、Trace 录制与脱敏、MCP 客户端、技能匹配、模型库等。
- `HMCL/src/test/java/org/jackhuang/hmcl/ui/ai/`：**85** 个测试类，覆盖 Minecraft 领域工具单元测试、TestFX 界面测试及硬编码中文扫描测试。
- `HMCLCore/src/test/` 与 `HMCL/src/test/` 上游部分：另有 64 个测试类。

常用验证命令：

```bash
./gradlew :HMCLAI:test                    # 运行 HMCLAI 核心单测（纯 Java，无 GUI 依赖）
./gradlew test -PheadlessTest             # 无头模式运行全仓库单测（TestFX UI 测试自动跳过）
./gradlew test                            # 在有图形界面的环境下运行全套测试（含 TestFX）
./gradlew checkstyle checkTranslations    # 运行代码规范与本地化键检查
```

CI 工作流：

- `.github/workflows/gradle.yml`：在 Ubuntu + Zulu JDK 17 (`jdk+fx`) 上执行 `./gradlew build --no-daemon --parallel` 并归档 `.jar` / `.exe` / `.sh` / `.deb`。
- `.github/workflows/check-codes.yml`：在 Java / properties 变更时执行 `./gradlew checkstyle checkTranslations`（注：`build.gradle.kts` 目前对 `org/jackhuang/hmcl/ai/**` 与 `org/jackhuang/hmcl/ui/ai/**` 暂排除了 checkstyle）。
- `.github/workflows/ae-release.yml`：手动触发构建并发布 GitHub Release，自动附带 `docs/release-notes/` 更新日志与 SHA-256 校验和表。

## 当前状态与已知限制

- **版本状态**：当前为 **0.1.1-alpha**（版本序列自 `0.1.0-alpha` 重置，旧 `0.x-beta` 已废弃），底层 Agent 逻辑仍在演进，允许不兼容变更。
- **高危操作风险**：AI 可执行下载、安装、修改/删除文件、编辑/删除存档等破坏性操作。使用前请自行备份重要存档，并在确认弹窗中核对参数。
- **已知限制**：
  - 虽已提供 `AiJobManager` / `job` 后台任务框架，但多数安装与下载工具默认仍为同步阻塞执行，下载期间对话需等待。
  - 执行稳定性依赖所选模型的工具调用与指令遵循能力，较弱模型在复杂多步流程中更易偏离预期。
  - 整合包导出目前仅支持 Modrinth `.mrpack` 离线打包；数据包工具仅支持列出与从本地 `.zip` 安装，不支持在线检索。
  - 跨会话长期记忆工具（`remember` / `recall`）已完成基础实现，当前在产品侧关闭（待完善）。
  - 彩色 emoji 需在聊天设置中手动开启并联网下载，默认使用系统渲染。

## 许可与致谢

本项目基于 **GPLv3** 协议开源（见 [LICENSE](LICENSE)），并遵循上游 HMCL 的 GPLv3 第 7 条附加条款：分发修改版须合理更改名称或版本号以区别于原版，且不得移除软件内显示的原始版权声明。

HMCL-AE 修改自 [HMCL](https://github.com/HMCL-dev/HMCL)，保留其原始版权声明，感谢 HMCL 及其自 2015 年以来的全体贡献者。

## 联系方式

- 作者：LeeWikie（Neryc）
- 邮箱：me@neryc.net
- 问题反馈：[GitHub Issues](https://github.com/LeeWikie/HMCL-AE/issues)

> 说明：仓库中 `docs/` 下的其余语言 README（`README_zh.md`、`README_ja.md` 等）为继承自上游 HMCL 的未同步原文，尚未覆盖 HMCL-AE 的 AI 功能。
