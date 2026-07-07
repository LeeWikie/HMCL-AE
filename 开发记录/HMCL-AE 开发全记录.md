# HMCL-AE 开发全记录

> 本文档由 Claude Code 会话历史（2026-06-27 ~ 07-05）汇总而成，与 git 提交历史交叉核对。
> 细节分段见 [`会话纪要/`](会话纪要/)，最新状态见 [`最新开发进度.md`](最新开发进度.md)。
> 生成时间：2026-07-07。

---

## 一、项目是什么

**HMCL-AE** 是 Minecraft 启动器 **HMCL（Hello Minecraft! Launcher）** 的分支，一句话定位：

> **会动手的 Minecraft 启动器** —— 在 HMCL 里内置一个真正调用工具干活的 AI Agent（装版本、配模组、修崩溃它自己做，不只是陪聊）。

- 仓库：`https://github.com/LeeWikie/HMCL-AE`（`upstream` = HMCL-dev/HMCL，**严禁推 upstream**）
- 许可：GPLv3（改名分支，依 §7c 用区分性名称 HMCL-AE，保留上游作者 huangyuhui 署名/版权/开源链接）
- 栈：Java 17+ / JavaFX(Prism) + JFoenix / LangChain4j / commonmark / Gson / Gradle
- 模块：`HMCL`（UI）· `HMCLAI`（AI 核心，刻意保持 **JavaFX-free** 以便未来剥离为独立引擎 AEL）· `HMCLCore` · `HMCLBoot`
- 与"套个聊天框"的差别：真 function calling、工具循环执行、报错自我纠正、复用启动器本体能力（下载源/镜像/账号/Java 管理）。

### 技术选型定调（首日确定）
| 领域 | 选择 | 理由 |
|---|---|---|
| LLM 框架 | LangChain4j 1.16.2 | 原生 function calling + MCP + Anthropic，替代手写 HTTP |
| Markdown | commonmark-java 0.24.0 → TextFlow 原生节点 | HMCL 刻意避开 WebView（WebKit 体积） |
| MCP | `langchain4j-mcp:1.16.2-beta26` | 关键坑：MCP 模块永远 `-beta`，版本号与主库不同步 |
| Agent 设计 | 以 **Pi**（earendil-works/pi）为蓝本 | 极简系统提示词 + 无步数上限工具循环 + 少量通用工具 + skill 教路径，而非堆窄工具 |
| RAG | **明确放弃** | 由用户另一个知识库项目以外部 MCP Server 形式提供 |
| 联网搜索 | 独立于 MCP、直接填 Key | 降低小白配置门槛 |

### 一以贯之的原则
- **全面复用 HMCL 原生组件，禁止网页皮肤**：HTML 原型只作功能蓝图，不作视觉皮肤；反复清除"用了原生件又自搓 CSS 毁掉"的反模式。
- **安全分层**：只读默认 / allowlist 根目录 / 写前备份 / 普通危险确认 + 红色高危二次确认（YOLO 也触发）/ 危险命令永远硬拦（唯一逃生口 `dangerouslySkipPermissions`）。
- **弱模型鲁棒性为硬约束**：内置默认模型是薅免费额度的弱模型（mercury-2 基线），故参数容错、简单 schema、强工具纪律、透明重试。
- **对外文案铁律**：README/release/UI/commit/源码注释只平铺事实，禁自夸、禁"诚实列出"式自标、禁提任何外部项目名。
- **少而精**：中途退役"100 个功能/设置"目标——硬加功能是负价值，改为好默认。

---

## 二、开发时间线

> HMCL-AE 的开发几乎全程由 AI（Claude Code）在用户 LeeWikie/Neryc 的高强度指挥下完成，跨约 9 天、多次通宵无人值守 + 交互督战。

### 第 0 天 · 起源（2026-06-27 之前）
前身智能体 **opencode（代号 Sisyphus，底层 DeepSeek V4 Flash）** 当天搭好整体骨架：核心基础设施、`chat.html`/`ai-prototype.html` 高保真原型、若干窄工具（CrashAnalyzer/FileBackup/ModToggle），最后把 HTML 原型翻译成 3235 行的 `AIMainPage.java`，留下 4 个空壳设置页、若干 bug，**未提交任何 commit** 就停手。

### 第 1 天 · 接手与打通（06-27）→ 会话 01/02
- 全量审读代码库，从实现状态反向重建 TODO。
- 修 opencode 遗留编译/运行坑：`Math.clamp`(Java 21)、缺失 i18n key、LangChain4j JDK HttpClient 缺失、shadowJar `minimize` 删 SPI。
- 把 AI 设置改为真正的 HMCL 子页面（原生导航 + 返回栈）；侧边栏/气泡/抽屉全面原生化。
- **核心功能重构 Phase 0-6**：commonmark Markdown、LangChain4j 原生 function calling 多轮循环、真流式、标题栏模型选择器、依赖升级 1.16.2 + Anthropic、退役手写 `LlmClient`(-317 行)、MCP 接入、token 预算上下文管理。
- 4 空壳 tab 实装；网络搜索 10 提供商枚举。
- 首次公开推仓被制止 → **仓库改名 HMCL-AE 并设私有**，立"不碰远程、专心写代码"约束。

### 第 2 天 · 用量、配置重构、Pi 式 Agent（06-28）→ 会话 03/04
- **#21 Token/用量显示**：`LlmUsage` DTO 全链路，用量+成本页脚（`showCost` 开关，4 项单价）。
- **服务商配置整体原生化重构**：`AiModelEntry`（模型为一等对象，携带别名/高级/定价）、两组单选（当前提供商 + 默认模型）、🔄加载/🧪测试多提供商三态树弹窗。
- **Pi 式 Agent 架构落地**：`streamTurn` 递归工具循环（MAX_TOOL_CYCLES=25）、极简系统提示词、系统信息注入、Claude-Code 式通用工具集（read/write/edit/grep/glob/shell/web_fetch）、渐进式披露 skill（内置 config-hmcl-ae / config-hmcl）。
- **ask 工具**：分步向导式结构化提问，阻塞循环收集答案。
- **首个发布 v0.1.0-beta**：jar/exe 打包、README 完整介绍页、`.deb` 改名 `hmcl-ae`。
- 夜间无人值守大扩充：约 44 个原生工具（账号/Java/记忆/世界/mod/游戏选项/系统信息/剪贴板…）、10 全局设置接线、`/compact`/`/plan`/TodoWrite 卡片、场景 skill。

### 第 3 天 · CLI 测试台 + NBT 旗舰 + 安全体系（06-29~30）→ 会话 05
- **AiCli 无界面测试台**：headless 起 FX toolkit、注册 51 工具、真模型端到端冒烟，16 场景并行冒烟脚本 + 37 单测；抓到并修 headless 真 bug（cache index null、list_java 卡死）。
- **11 个新工具**（数据包/世界/mod 更新/整合包导出/SLP ping/Java 下载…）。
- **存档 NBT 编辑套件**（旗舰）：基于 `org.glavo:HelloNBT`，7 个 NBT 工具 + 离线 UUID 计算 + 跨存档搬玩家数据，全部占用检测/路径限定/写前备份/原子写。
- **两级安全体系**：`CriticalOperations` 红色高危二次确认 + `DangerousCommands`（含 base64/UTF-16LE 编码命令扫描、Windows 删除写法全补）+ `dangerouslySkipPermissions` 开发者开关。
- **异步地基 `AiJobManager`**（后台任务 + 完成后自动续轮）、进度总线 `ToolProgress`、世界备份引擎 `WorldBackupManager`。
- **规划转向**：UltraPlan 12 代理审计出真 backlog（隐私合规/成本/注入/幽灵工具…），50 问结构化规划定 round-two；退役"100 功能"目标。
- **发布 v0.2.0-beta**；国内搜索直连（Bocha/Zhipu，默认 Bocha）；修 20+ 真 bug + 9 回归测试类。

### 第 4~5 天 · 审计驱动的系统化修复（06-30~07-02）→ 会话 06
- 4 大扇出 workflow（约 57 子代理，410 万 token）：UI 风格审计、UI 三 bug 根因、功能调研、24 路代码审计。
- **P1 数据/安全**修完 12/15（配置丢数据、双 Base64 毁 Key、SSRF、restore 毁档、危险门绕过…）+ 6 回归测试。
- **P2 UI**：选择框系统性错位根因锁定为上游 #6118 回归并修复；测试对话框/三态 checkbox/API Key 掩码/设置四类重排/内联编辑消息/keycap emoji…
- **#34 rebrand 核心**：NAME/FULL_NAME/About → HMCL-AE，保留上游署名。
- **ToolParams 举一反三**：弱模型猜错参数名是系统性问题，抽共享 helper 套 17 个工具。
- **发布 v0.2.1-beta** + GitHub Actions `AE Release` workflow（CI 构建上传，替代本地传大文件）。

### 第 6~7 天 · 16 领域缺口审计 + 自主 /loop 修复（07-02~07-05）→ 会话 06
- 产出 `docs/agent-client-gap-audit.md`（506 行，16 领域全覆盖），新增 net-new 任务 **#53–#65**。
- 结论：Agent 内核 / MC 深度集成 / 执行防护 = 被低估的强项；短板 = 合规底座、MCP 死功能、流式切会话 bug、思考过程折叠、i18n。
- **自主 /loop 逐个修完 13 项**（每项编译+测试+提交+推送+写更新日志）：
  MCP 工具真执行(#57)、流式切会话(#59)、思考过程折叠(#53)、代码块高亮复制(#54)、会话导入(#60)、CI 修复、API 层日志(#44)、记忆脱敏去重(#55)、无进展检测(#42)、注入防御(#63)、记忆逐条删除(#56)、成本护栏(#62)、隐私同意(#61)。
- 收尾在与用户讨论 #65 更新渠道部署约束中被 `/compact` 截断（详见最新进度）。

---

## 三、里程碑与版本

| 版本 | 时间 | 内容 |
|---|---|---|
| v0.1.0-beta | 06-28 | 首个可用发布：AI Agent、原生 UI、约 44 工具、Markdown、流式 |
| v0.2.0-beta | 06-29 | NBT 套件、安全体系、CLI 测试台、后台任务、备份引擎、国内搜索 |
| **v0.2.1-beta** | 06-30 | 当前最新发布：P1 数据/安全修复、UI 重整、rebrand、AE Release CI |
| 开发中（未发布） | 07-02~ | 16 领域缺口审计后的 /loop 批量修复（思考折叠/成本护栏/隐私同意/注入防御…） |

> git 上共约 **126 条 AI 相关提交**（`feat(ai)`/`fix(ai)`/`ui(ai)`/`docs(ai)`/`ci(ae)` 等），时间 2026-06-28 → 07-03，均已合入 `ai-feature` 与 `main`。

---

## 四、关键工程教训（写入长期记忆的那些）

1. **worktree 陈旧基线陷阱**：子代理从会话起点旧提交分叉，看不到会话内新提交——并行改现有文件会互相覆盖甚至复活死代码。→ 子代理只新建文件，改现有文件用主树 fork 或自己做。
2. **子代理并行的硬边界**：只读分析可海量并行；写操作必须"文件不重叠"分组、不编译不提交、主循环集中整合。
3. **额度管理**：18 代理海量扇出可瞬间打爆 5h 滚动额度且零产出 → 无中断风险时才大扇出，否则 solo 小步编译提交（防中断丢进度）。
4. **弱模型参数猜错是系统性问题**：抽共享 `ToolParams` helper 全量套用，而非逐个打补丁。
5. **危险门必须扫全部参数别名**：`containsKey` 链选参会被 `{command:null,input:"format C:"}` 绕过。
6. **"核查待办 > 第 N 次重审"**：核查规划 P0 是否真做，比反复审已修代码更能挖出真遗漏。

---

## 五、源会话索引

| # | 会话 ID（`.claude/projects/D--WorkSpace-Code-HMCL/`） | 时间 | 规模 | 主题 |
|---|---|---|---|---|
| 01 | `ecd67b0f-…` | 06-27 12:41→19:01 | 4733 行 / 10.9MB | 接手 opencode，项目起源与设计定调 |
| 02 | `2199ac5e-…` | 06-27→06-28 | 7460 行 / 20MB | 接手与全面原生化、Phase 0-6 重构 |
| 03 | `5fef0f52-…` | 06-28 10:13→13:56 | 1711 行 / 4.4MB | 用量显示、服务商配置重构 |
| 04 | `80c5ae7b-…`(1/3) | 06-28→06-29 | — | Pi 式 Agent、工具集、v0.1.0-beta |
| 05 | `80c5ae7b-…`(2/3) | 06-29→06-30 | — | CLI 测试台、NBT 套件、安全体系、v0.2.0-beta |
| 06 | `80c5ae7b-…`(3/3) | 06-30→07-05 | 17612 行 / 50MB(全) | 审计修复、rebrand、v0.2.1-beta、缺口审计 |

> 另有若干短会话（probe/channel/resume）与 JavaFX AISettings channel 交互记录，无实质开发内容，未收录。
