# 更新日志

本文件记录 HMCL-AE 各版本的变更。可下载构建见 [Releases](https://github.com/LeeWikie/HMCL-AE/releases)。

## 未发布(开发中)

下一版本的开发进展记录于此,发布版本号与日期待定。本段随开发滚动更新。

## 0.1.2-alpha — 2026-07-15

0.1.1-alpha 之后的开发版本,重点扩充了 AI 助手对本地实例的操作能力,并为 AI 改写文件加入了改前自动备份的保护。

### 重要更新

**AI 助手 — 实例工具动作大幅扩充**

`instance` 工具本轮新增下列动作,均复用 HMCL 原生的对应实现(不另写一套逻辑),权限按动作细分(只读 / 受控写入 / 危险写入)。

配置类:
- `set_java`:设置实例的 Java 运行时选择(mode = auto / version / detected / custom;version 按主版本号选已装 Java,detected / custom 按可执行文件路径)。
- `set_window`:游戏窗口模式与尺寸(windowType = windowed / fullscreen / maximized,窗口模式下附 width / height)。
- `set_launch_behavior`:启动前后行为(启动器可见性、进程优先级、是否允许自动 agent、是否禁用自动生成 game options、是否显示日志窗口、调试日志、跳过游戏完整性检查)。
- `set_graphics`:图形后端与渲染器(default / opengl / vulkan,及各自的渲染器名;不带参数先报告本机支持的渲染器)。
- `set_launch_args`:每实例的启动参数覆盖(游戏命令行参数、环境变量、是否禁用默认 / 优化 JVM 参数、是否跳过 JVM 校验)。
- `list_presets`(只读):列出 HMCL 的全局游戏设置预设。
- `set_memory`:修复自动 / 固定内存切换未正确写入 overrideProperties,导致设置的内存值被预设静默继承的问题。

内容与存档类:
- `mods_rollback`:把模组回滚到归档的某一旧版本(不带 version 则列出可回滚的版本)。
- `resourcepacks_check_updates`:列出有新版本的资源包(apply=true 则下载替换;apply 未设时为只读)。
- `worlds_export`:把世界存档打包为 zip(不覆盖已存在文件)。
- `worlds_duplicate`:把世界复制到新的存档文件夹。
- `datapacks_toggle` / `datapacks_remove`:单个数据包的启停 / 删除(删除为危险动作,优先进回收站)。
- `install_local_content`:把本地已有的文件(mod `.jar`/`.litemod`、资源包、光影)按类型装进实例对应目录,尊重版本隔离;文件类型与 kind 不符即拒绝。

维护与管理类:
- `instance_maintenance`:对应原生"版本管理"菜单的清理操作(scope = clean_junk 清日志与崩溃报告 / redownload_assets 强制重下资源索引 / clear_resources 清资源 / clear_libraries 清依赖库);后两者为危险动作,需 confirm=true 且游戏运行时拒绝;不带 scope 时只报告可回收空间。
- `java_manage`:管理 HMCL 的 Java 运行时注册表(operation = refresh 重扫 / add 用路径登记已有 Java / uninstall 删除 HMCL 下载的托管运行时);uninstall 为危险动作,且只删 HMCL 自己下载的运行时,不碰系统或用户自装的 JDK。
- `generate_launch_script`:生成可在启动器外运行的启动脚本(Windows `.bat` / Linux `.sh` / macOS `.command`),内容与真实启动一致,需已选账户。
- `set_instance_icon`:设置实例图标,复用 HMCL 原生图标系统(内置图标名、'auto',或自定义图片路径)。
- `schematics_list` / `schematics_import` / `schematics_delete` / `schematics_reveal`:管理实例 `schematics/` 下的 Litematica 投影文件(列出 / 导入本地 `.litematic` / 删除 / 在文件管理器中打开)。

`game` 工具:
- `launch` 新增 `testMode`:复用原生"测试游戏"入口,本次启动强制保留启动器窗口并显示日志,不改动持久化的实例设置。

批量与富化:
- `mods_install` 支持 `ids` 数组一次安装多个已知项目(不自动装依赖,逐项回执)。
- `mods_update` 支持 `all=true` 或 `mods` 数组一次更新多个模组(逐项复用单模组更新核心)。
- 实例列表富化:每个实例附带检测到的加载器与本地模组数(纯本地、best-effort)。

**AI 助手 — 知识库(RAG)开发预览,发布版暂时隐藏**
- 知识库(RAG)相关能力仍不成熟,发布版中隐藏其设置与实现:AI 设置中的"知识库"分区、模型的"嵌入"能力勾选、以及 `kb_search` 工具在发布构建中不出现,提示词也不会提及它。代码与已保存配置保留不动,由构建标志 `KNOWLEDGE_BASE_UI_ENABLED` 控制(开发构建可见,或 `-Dhmcl.experimental.ai.kb=true` 强制开启),与定价 UI 的隐藏方式一致。
- 知识库关闭时保证有下位替代:AI 仍可用搜索 / 联网搜索 / 内置知识作答;开启时 `kb_search` 与它们并存,不冲突。

### 变更明细

- **AI 助手 — 工具编码修复(Windows)**:Shell 工具在 Windows + JDK 21 下按控制台编码(`sun.jnu.encoding`,通常 GBK)解码输出,修复中文乱码;文件读取工具改为先按严格 UTF-8 解码、失败再回退本地编码,修复非 UTF-8 文件读取时崩溃。
- **AI 助手 — 搜索结果透出兼容信息**:模组 / 资源包 / 光影搜索结果补充各条目支持的游戏版本与加载器(来自 Modrinth / CurseForge 搜索响应),便于按用户实际需要的加载器 / 版本选择,减少装错构建。
- **AI 助手 — 工具增强**:`grep` 工具支持上下文行(-B / -A,重叠窗口去重);`web_fetch` 在正则抽取前对(不可信)响应体加输入大小上限,消除多 MB 页面上的 O(n²) 回溯风险。
- **界面 — 图标统一**:AI 界面改用 HMCL 原生所采用的 Material Symbols 图标,导航采用未选中 fill=0 / 选中 fill=1 的填充切换,与原生 HMCL 一致。
- **界面 — 对话**:工具调用卡片恢复分组摘要(工具名 + "+N" 折叠)并配 SVG 图标;Markdown 代码块复制按钮改为图标式(悬停涟漪 + 提示,成功时短暂切为对钩)。
- **AI 助手 — 就地改写文本文件前强制备份**:AI 改写既有文本文件前,一律先把原文件快照为同目录 `<名>.bak`;快照失败即拒绝本次编辑、不落盘。这是硬前置,与审批模式无关(自动放行 / yolo / 无人值守下同样生效),不再像之前那样只在"跳过确认弹窗"时才顺带备份。覆盖四条就地改写路径:`edit`、`write` 覆盖既有文件、`set_option`(写 options.txt)、`resourcepacks_toggle`(经原生 ResourcePackManager 改 options.txt)。新建文件与追加写入不备份(不丢原内容);快照不设大小上限,大配置也保留还原点。备份对每个文件只留一份(覆盖式,即"撤销上一次编辑");沿用既有 `FileBackup`,与审批链原有的机会性备份幂等共存,不重复拷贝。

## 0.1.1-alpha — 2026-07-12

0.1.0-alpha 之后的修订版本。启用微软账户登录,并纳入首发后的一批 AI 助手修复。

### 重要更新

**账户 — 微软账户登录可用**
- 发布构建现内置 HMCL-AE 自建 Azure 应用的 Microsoft OAuth client ID,微软正版账户登录不再被"非官方构建"拦截,登录入口恢复可用。通用"非官方构建"安全性提示与此无关,仍会保留。

**AI 助手 — 危险操作防护**
- Shell 工具禁止用于终止 java / javaw 进程;新增对"终止 java / javaw 或启动器自身进程"的危险 / 关键操作判定,避免误杀运行中的游戏或启动器自身。

### 变更明细

- **AI 助手 — 工具**:模组安装前报告依赖状态,避免缺依赖连环崩溃;模组搜索结果透出依赖信息、澄清 loader 描述不参与检索;实例工具补充资源包 / 光影开关,避免裸 shell 改后缀;Ask 工具保留 JSON 语法错误详情。
- **AI 助手 — 崩溃诊断与稳定性**:崩溃诊断补充"日志读取失败 / 进程卡住需终止"分支;危险操作前同步落盘,避免异常退出丢会话;内容审核拒绝错误信息友好化。
- **构建与文档**:README 重写为机械化详细介绍页、新增独立 CHANGELOG;修复 check-codes(全角括号改半角、补标准版权头);修复一处偶发失败的单元测试。

## 0.1.0-alpha — 2026-07-11

Agent 逻辑重写后的首个 alpha 版本,版本序列自本版重置(0.x-beta 系列已废弃)。

### 重要更新

**AI 助手 — Agent 逻辑重构**
- 统一护栏架构:工具调用拒绝态短路、runtime-guard 通道、结构化 BlockReason,消除"假成功"回执。
- 错误处理统一为错误信封(ErrorEnvelope):失败回执携带原因、作用域内候选、后续动作。
- 文件工具契约化:读取台账、写后校验、替换链;写入前强制读取。
- 各工具域(实例 / 世界 / 存档备份 / 模组 / 资源包与光影 / NBT / 搜索 / MCP)失败态统一枚举真实候选名单,不再静默回落或裸串错误。
- 资源占用统一守卫;模组启停、删除、更新收编入状态机;世界备份 / 恢复 / NBT 写入原子性保护;删除实例前运行检测。

**AI 助手 — 诊断 Trace 基建**
- 新增独立 append-only JSONL,逐轮记录 request / response / tool 调用完整内容与 token 计数;可配置开关。
- 反馈上传附带 ai-trace / ui-trace / 元信息。

**AI 助手 — 模型参数库**
- 内置模型元数据由 65 项扩充至 1604 项,涵盖上下文窗口、输出上限、模态、定价。
- 按模型 ID 自动匹配上下文窗口;新增大小写与 provider 前缀的保守规范化匹配。

**AI 助手 — 运行时状态传播修复(单一事实源)**
- 修复下拉切换模型后请求仍使用旧模型。
- 每模型参数(温度 / 最大输出 / 推理强度 / 上下文窗口)接入请求侧;推理强度接入 OpenAI 与 Anthropic 请求。
- 审批模式、联网开关、shell / NBT / OCR 工具开关改为运行时实时生效,不再需要重启。
- 工具权限跨实例磁盘同步;文件系统工具允许根随实例切换替换。

### 变更明细

- **界面 — 对话栏**:两行布局(输入框 + 底部工具栏),模型 / 思考 / 上下文移入工具栏;上下文数值百万级格式;进度条改用原生下载条样式;四类弹窗动画统一、位置按窗口余量自适应;修复空态过高、间距过大;发送后回底、流式仅贴底跟随。
- **界面 — 设置与主题**:设置页分区重构、高级设置精简、模型配置弹窗固定高度滚动;API Key 内联掩码与可见切换;暗色主题配色修复;代码块 One Dark 深色框;定价与成本估算界面归档隐藏。
- **界面 — Markdown**:裸 URL 自动链接;国旗 emoji 换源;代码块横向滚动;emoji 与粗体 / 斜体 / 删除线混排保留样式;字号随档位。
- **AI 助手 — 工具与提示词**:联网搜索、OCR 按开关条件暴露;技能索引扁平化、召回收敛、自适应匹配替代全文注入;提示词注入选中实例的权威 Minecraft 版本与类型;工具描述内嵌反模式修正。
- **安全**:SSRF / DNS 加固;修复路径穿越与符号链接逃逸;锁定 Jackson 依赖 CVE 版本。
- **构建与发布**:新增 `-PheadlessTest` 无头测试;发布产物正文内嵌更新日志与 SHA-256 校验和表格。
- **其他**:i18n 文案全量迁移(约 230 键);繁体中文文案重译;移除仓库内截图目录;停止跟踪运行时状态目录。
