# HMCL-AE · Hello Minecraft! Launcher — Agent Experience

> **在 Minecraft 启动器里嵌入真正的 AI agent,帮你装机、修崩溃、管 mod,不只是聊天。**

[![GPLv3](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.html)
[![Java 8+](https://img.shields.io/badge/Java-8+-orange?style=flat-square)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Win%20%7C%20Linux%20%7C%20macOS%20%7C%20FreeBSD-green?style=flat-square)](https://github.com/LeeWikie/HMCL-AE/blob/ai-feature/docs/PLATFORM.md)

---

## 这是什么

HMCL-AE 是 [HMCL](https://github.com/HMCL-dev/HMCL) 的一个修改版——在上游全部启动器功能以外,**加了一套真正会用工具干活的 AI agent**。

和"嵌一个聊天框接 API"不同,这个 agent:
- 有 **15+ 个工具**:读写文件、搜索网页、搜/装 mod、安装 Minecraft 版本和加载器、改名/删除实例、跑 shell 命令、向用户提问……
- **循环执行**:调工具 → 拿到结果 → 决定下一步 → 再调工具,直到任务完成,最多 25 轮。
- **自我纠正**:工具失败时错误信息会回传给模型,它知道哪步坏了可以换条路。
- **失败不静默**:再也不会有"工具悄悄失败,AI 不知道,继续胡说"的情况。

HMCL-AE 继承了 HMCL 的全部功能——模组管理(Forge/NeoForge/Fabric/Quilt/OptiFine)、整合包、Java 管理、账号登录、Terracotta 联机、跨平台(Win/Linux/macOS/FreeBSD;x86/ARM/RISC-V/MIPS/LoongArch)。

> ⚠️ **这是测试版,仍在密集开发中。** 需要自己配 API key。免费开源。

---

## 它能干什么

### 装 Minecraft,全自动

> **用户**: 「帮我装个最新版 + Fabric,再装 Sodium 和能用上的附属」

AI 会:① 列现有实例 ② 从 HMCL 实时拉取最新版本列表(不靠记忆猜)③ 在 Modrinth 上搜 Sodium 及其附属 ④ 弹出分步向导让你选:版本?加载器?哪些附属? ⑤ 自动装版本+Fabric ⑥ 依次装你选的 mod ⑦ 报告完成,"要帮你启动吗?"

### 诊断崩溃

> **用户**: 「我的 Minecraft 崩了」

AI 会:读 `logs/latest.log` → 找最新的崩溃报告 → 用 HMCL 内置的崩溃规则库(CrashReportAnalyzer,覆盖 20+ 种常见崩溃)匹配已知错误 → 用中文告诉你原因和解决方法。

### 改实例,一键到位

> **用户**: 「把 1.21.1-Fabric 改名为 1.21-Fabric-Modpack」

AI 调 `edit_instance` — 直接改名。不是"请你手动到版本列表右键重命名"。

### 搜内容,装内容

- 搜/装 mod(Modrinth + CurseForge)
- 搜/装资源包(resource packs)
- 搜/装光影(shaders)
- 搜/装整合包(modpacks)
- 搜世界存档

### 联网搜最新信息

配好搜索 Key(Tavily/SearXNG)后,AI 可以搜索最新 MC 新闻、Wiki、文档——并引用来源 URL。

### 自我配置

通过内置的 `config-hmcl-ae` 和 `config-hmcl` 技能,AI 能修改 HMCL-AE 自身和 HMCL 原生的配置文件——换服务商、改 Java 参数、加 MCP 服务器。

---

## 工具列表

### 文件与命令
`read` `write` `edit` `grep` `glob` `shell`

### 网络
`web_search` (Tavily/SearXNG) `web_fetch`

### Minecraft 操作
`list_instances` `list_game_versions`(实时版本列表,不靠记忆) `search_mods` `install_mod` `install_loader`(装版本+加载器,六种加载器全支持) `launch_instance` `edit_instance` `delete_instance` `search_resourcepacks` `install_resourcepack` `search_shaders` `install_shader` `search_modpacks` `install_modpack` `search_worlds` `match_known_errors`(崩溃诊断,复用 HMCL 原生规则)

### 对话
`ask`(分步提问向导,回车=提交) `sleep` `resolve_game_context`(查实例路径/隔离状态)

---

## 安全机制

**不是假的**——安全策略接进了工具执行路径(不是只在提示词里描述)。

- **危险命令检测**:`rm -rf`、`format`、`shutdown`、`reg delete` 等 15+ 种危险 shell 命令会在执行前**弹窗让你确认**。
- **安全模式**:大多数操作(读、写、联网)**自动放行**,只有危险操作才拦截。不是"每次调工具都问"。
- **审批模式三档**:安全(Safe / 默认) / 询问(Ask) / 全开(YOLO)。
- 如果点了"否",AI 会被告知用户拒绝了,让它换一种安全的方式。

详见 [[安全机制|HMCL-AE wiki — 安全机制]]。

---

## 会话编辑

每条消息气泡下方有一行**小图标**(悬浮可见):

- **用户消息**: [复制] [编辑] [重发] [分支] [删除]
- **AI 消息**: [复制] [重生成] [分支] [删除]

- **编辑**:把该消息的文字装回输入框,删除它及后面的内容,你可以修改后重发(agent 上下文会重建)。
- **重发/重生成**:删掉从这里往后的所有消息,以当前上下文重新生成。
- **分支**:把到这条消息为止的对话复制到一个新会话,从那里继续(旧会话不受影响)。
- **删除**:从对话中删除单条消息,不影响其它。

快速切会话有防抖(60ms 合并),不会因为不停点而乱闪/串台。侧栏「新对话」固定顶部、「AI 设置」固定底部,中间只有会话列表滚动。

---

## 怎么开始

### 1. 下载

从 [Releases](https://github.com/LeeWikie/HMCL-AE/releases) 下载 `HMCL-AE-测试版.zip`,解压。

### 2. 运行

- **Windows**: 双击 `HMCL-AE-测试版.exe`
- **Linux / macOS**: `java -jar HMCL-AE-测试版.exe`(exe 同时也是合法 jar,跨平台)

### 3. 配 AI

进入侧栏「AI 助手」(空心圆圈 ⓘ)→「AI 设置」→「服务商」→ 添加,填:
- API 端点(如 `https://api.deepseek.com`)
- API Key
- 模型名(如 `deepseek-chat`)

联网搜索另需在「联网搜索」配(可选,不影响 agent 核心功能)。

### 4. 开始用

直接打字就行,AI 会自己调工具。中文对话效果更好。

---

## 已知局限

诚实列出来,不隐瞒:

1. **安装/下载是阻塞式的**:让 AI 装版本/mod 时,下载期间聊天会"卡住",没有进度条——这是**下一个最优先要解决的问题**。
2. **不同模型差距**:强模型(Claude Opus 4.8、GPT-4.1、DeepSeek-R1)执行复杂流程更稳;弱一些的容易忘步骤。
3. **部分操作工具化未完成**:账号登录(微软 OAuth/离线/authlib)、Java 管理、整合包导出。
4. **彩色 emoji 需手动开 + 联网**:默认系统渲染;要彩色的在聊天设置里开启"彩色 Emoji(联网)"。
5. **数据包管理**未做(需先核对官方格式)。

详细列表见 [[已知问题|HMCL-AE wiki — 已知问题]]。

---

## 许可证

**GPLv3**,附加条款(Section 7):分发修改版时须修改软件名/版本号以区分原版;须保留版权声明。

HMCL-AE 是 [HMCL (Hello Minecraft! Launcher)](https://github.com/HMCL-dev/HMCL) 的修改版,保留原始版权声明。感谢自 2015 年以来超过 120 位 HMCL 贡献者。

[![Contributors](https://contrib.rocks/image?repo=LeeWikie/HMCL-AE)](https://github.com/LeeWikie/HMCL-AE/graphs/contributors)

---

## 相关链接

- [Wiki](https://github.com/LeeWikie/HMCL-AE/wiki) — 使用指南、安全机制、FAQ
- [Issues](https://github.com/LeeWikie/HMCL-AE/issues) — 报 Bug / 提需求
- [Discussions](https://github.com/LeeWikie/HMCL-AE/discussions) — 讨论 / 反馈
- [HMCL 原版](https://github.com/HMCL-dev/HMCL) — 上游项目
