# HMCL-AE 界面重设计执行方案(对标 Cherry Studio 类客户端)

> 输入:6 维度共 68 条审计发现。去重合并后为 **38 项**,分 R1(14 项)/ R2(15 项)/ R3(9 项)三批。
> 涉及文件以 `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/AIMainPage.java`(下称 **AIMainPage**)、`AISettingsPage.java`(下称 **AISettingsPage**)、`HMCLAI/src/main/java/org/jackhuang/hmcl/ai/` 下的数据/协议层、`HMCL/src/main/resources/assets/css/root.css` 为主。

---

## 0. 三条架构主线(重大架构级改动,单独标注 ★)

所有零散项都挂在这三条主线或独立小项上。主线内部有严格的分步顺序,**先落数据层,再落渲染层**,避免返工。

### ★ 主线 A:消息模型与渲染层重写(影响 R1-A1/A2/A3、R2-B1/B2/B3/B4/B7/B8)

现状根因:会话持久化只有 user/assistant 纯文本,工具/系统/事件/思考全部丢失或降级;渲染靠 `onComplete → loadSessionMessages()` 全量重建,导致闪烁、工具卡消失、操作条补挂。

分步落地顺序(每步可独立提交、向后兼容旧会话文件):

| 步骤 | 内容 | 落点 |
|---|---|---|
| A-1 | **扩展消息模型**:`LlmMessage`(HMCLAI/llm)增加字段 `role` 扩展值 `tool`/`event`、`turnId`、`timestamp`、`model`、`reasoning`、`toolPayload`(JSON: name/displayName/argsJson/resultText/success/durationMs)。Gson 缺省兼容旧文件(旧消息无 turnId 时按"一条一回合"回放) | `LlmMessage.java`、`AiSession.java`、`AiSessionStore.java` |
| A-2 | **写入侧接通**:`ChatAgent`/`LangChain4jToolAdapter` 每次工具调用完成按序写 tool 消息(用 `ToolExecutionRequest.id()` 配对,弃工具名 FIFO);发送/完成时写 timestamp/model/turnId;`submitExternalPrompt` 增加 silent 变体写 event 角色 | `ChatAgent.java`、`LangChain4jToolAdapter.java`、AIMainPage `submitExternalPrompt(≈2807)` |
| A-3 | **回合容器渲染**:`startAiResponse` 改为首 token 时创建单个 VBox turn(头行仅一次),onToken/onToolActivity/onToolResult/思考块都追加进该容器;usage footer 与操作条挂容器底部 | AIMainPage `startAiResponse(≈2507)` |
| A-4 | **重放路径统一**:`loadSessionMessages` 按 turnId 分组重建回合容器,tool 消息重建 ToolCard、event 消息渲染事件胶囊——live 与重放共用同一组组件 | AIMainPage `loadSessionMessages(≈2167)` |
| A-5 | **删除全量重渲染**:`onComplete` 不再调 `loadSessionMessages`,流式 finalize 原位补挂操作条 | AIMainPage `onComplete(≈2624)` |
| A-6 | **流式实时 Markdown**:流式期间 ~200ms 节流重渲染 `MarkdownMessageView` + 尾部 ▍闪烁光标 + 首 token 前三点跳动动画 | AIMainPage、`MarkdownMessageView.java` |

### ★ 主线 B:提供商设置页重构(影响 R1-A11、R2-B9/B10/B11、R3)

分步落地顺序:

| 步骤 | 内容 | 落点 |
|---|---|---|
| B-1 | **解耦"查看"与"使用"**:设置页点提供商只驱动详情,不写 `selectedProfileId`;全局默认改存结构化 `{profileId, modelId}` 双字段(弃 `"Provider / Model"` 字符串拆分);聊天模型选择器数据源 = 所有 enabled 提供商模型聚合 | AISettingsPage L204-211/L319-333、`AiSettings.java`、AIMainPage `setupModelSelector(≈993)` |
| B-2 | **统一 ModelPicker 控件** + "默认模型"设置区(默认聊天模型/话题命名模型,值为结构化引用);废弃 `getCachedModels()` 拼接字符串路径;合并两个重复的自动命名开关 | AISettingsPage L1859-1872、L2254-2269 |
| B-3 | **两步式添加提供商**:第一步搜索式服务商列表(PROVIDER_PRESETS + 末行"自定义…"),第二步只填 名称/API Key/可选地址;协议自动写入,仅自定义路径显示"API 类型"三选一;删除 presetBox 混合下拉 | AISettingsPage `createProfile()/editProfile()` L350-427 |
| B-4 | **主从布局(XL,最后做)**:providerTab 重构为 左列提供商列表(logo+名称+行内 JFXToggleButton 启用)/右列 TransitionPane 详情(API Key 行+检查按钮→接口地址→内联模型列表→"高级"折叠:协议/删除);字段失焦自动保存,删除 editProfile 弹窗 | AISettingsPage L241-466 |

> B-1/B-2/B-3 各自独立可发布,B-4 依赖 B-1(否则左列点击仍污染全局默认)。

### ★ 主线 C:组合器(输入区)重构(影响 R1-A6/A7、R2-B12)

| 步骤 | 内容 | 落点 |
|---|---|---|
| C-1 | **单行 TextField → 多行 TextArea**(含 IME 守卫、Enter/Shift+Enter、自动增高 1~8 行) | AIMainPage :253, 1268-1292 |
| C-2 | **附件与文本分离**:`List<AttachedFile>` 数据模型,内容发送时组装,chip 独立删除 | AIMainPage 1720-1810 |
| C-3 | **卡片化布局**:VBox 圆角卡片 = [chip 区]+[TextArea]+[工具栏行:左附件/思考/计划,右发送圆钮],发送键图标化(纸飞机↔停止方块) | AIMainPage `buildComposer(≈1279-1415)`、root.css 2377-2485 |

> C-1 与 C-2 必须一起或先后紧跟(C-2 修复 clearFileChip 清空用户文字的 bug);C-3 可后置。

---

## 已知 Bug 修复重叠对照表(合并实施,不要重复做两遍)

| 已知 bug | 合并进哪一项 | 说明 |
|---|---|---|
| **工具卡片完成后消失(持久化)** | R1-A1(主线 A-1/A-2/A-4) | 根因就是 tool 消息未结构化持久化 + onComplete 全量重渲染,修 bug 即做重设计,不要先打"Tool result for"字符串补丁 |
| **后台续跑气泡改系统样式** | R1-A3(event 角色 + 事件胶囊) | 假用户气泡问题由 A-2 的 silent/event 通道一次解决,崩溃注入、/plan 提示、续跑上限提示同批迁移 |
| **草稿保护** | R1-A6/A7(主线 C-1/C-2) | clearFileChip 检测 `[File:` 清空整框是草稿丢失主因;TextArea 重构时同时做"切会话保留各会话草稿"(session→draft 字符串 Map,切换时存取) |
| **内联编辑守卫** | R1-A2(操作条重构)+ R2-B8(停止/继续) | 操作条重建时统一加守卫:`isStreaming() && streamSessionId==currentSessionId` 时编辑/重发/删除禁用置灰(tooltip 说明),而非事后 toast |
| **流式中切设置(切会话)** | R1-A5(会话级流式状态) | 停止按钮误杀、半截回复消失、静默吞发送,三症状一并修 |

---

## R1 —— 立刻做(高感知收益;含全部 bug 重叠项)

### A1. 工具卡片持久化 + 结构化渲染 ★主线 A-1/A-2/A-4
合并:chat-main 工具卡、agent-ux 工具卡(两条同根因)。**Impact HIGH / Effort L**
- **文件**:见主线 A 表;UI 侧 AIMainPage `ToolCard(≈3419)`。
- **规格**:
  - ToolCard 统一形态:header = 状态图标(转圈/✓/✗)+ **中文动词化标题**(新建 `toolName→中文动作` 映射表,如 install_mod→"安装模组")+ 成功/失败 chip + 右侧 chevron(展开旋转 180°);默认折叠。
  - 卡片体 = "参数"(键值对渲染,非原始 JSON)+ "结果"(截断 10 行,可复制),等宽字体,复用 `ai-tool-card` 样式。
  - 连续 ≥3 次调用折叠为"执行了 N 个操作"分组头,默认收起。
  - **删除 `toolActivityBox` 顶部日志条**(≈897-909,信息已被卡片覆盖)。
  - 配对改用 call id,修复同名并发错配。

### A2. 消息操作条:hover 显示 + 原位补挂(含内联编辑守卫)★主线 A-5
合并:chat-main 操作条、ia-visual 操作条。**HIGH / M**
- **文件**:AIMainPage `attachMessageActions`、`onComplete(≈2624)`;root.css。
- **规格**:
  - 操作条建进每条消息容器内,CSS 实现 `.ai-bubble-wrapper:hover .ai-bubble-actions { -fx-opacity: 1; }` + FadeTransition 120ms;managed=true 预留 24px。
  - 高频只留 复制+重新生成(用户侧加 编辑),分支/删除收进 "⋯" JFXPopup;删除加确认。
  - 流式 finalize 直接为该回合挂操作条,**删除 onComplete 里的 loadSessionMessages 调用**(消除闪烁/滚动跳动)。
  - 守卫:本会话流式中,编辑/重发/删除按钮置灰 + tooltip"生成完成后可用"。

### A3. 系统/事件消息:居中胶囊 + event 角色(后台续跑改造)★主线 A-1/A-2
合并:chat-main 系统消息、agent-ux 后台续跑假用户气泡。**HIGH / M**
- **文件**:AIMainPage `addSystemMessage`、`onBackgroundJobComplete(≈2659)`、`submitExternalPrompt(≈2807)`;`LlmMessage`。
- **规格**:
  - `addSystemMessage` 改居中:HBox(Pos.CENTER) + `.ai-system-pill`(-monet-surface-container 底、-monet-on-surface-variant、11px、padding 4 12、圆角 10)。
  - 后台任务完成:居中胶囊"⏱ 后台任务「安装 Fabric」已完成,AI 继续处理…",原始结果折叠在展开区;`submitExternalPrompt` silent 变体照常入模型上下文+持久化(synthetic 标记),UI 按事件条渲染;重放同样还原。
  - 需用户行动的系统提示(未配置服务商)升级为带按钮的居中卡片。
  - "(已停止)"占位同步改为事件条样式。

### A4. AI 回复宽度自适应 + 平铺样式
合并:chat-main 480 气泡、ia-visual 480 硬编码(同一处代码)。**HIGH / M**
- **文件**:AIMainPage `createAiBubble/bubbleTextNode`、`MarkdownMessageView.java`(MAX_WIDTH=470)、root.css `.ai-bubble`。
- **规格**:
  - messageList 外套居中容器:maxWidth 820,居中于滚动区。
  - 建 `DoubleBinding bubbleMaxWidth = clamp(320, viewportWidth*0.78, 860)` 绑定 `scrollPane.viewportBoundsProperty()`,**所有** `setMaxWidth(480)`(气泡/名字标签/todo/工具卡)与 MarkdownMessageView 改绑此值;宽度变化 >24px 才更新(防 setCache 重栅格化抖动)。
  - 默认消息样式切 flat(AI 无气泡底色),bubble 留作聊天设置选项;用户消息 maxWidth = 列宽 66%,右对齐色块不变。

### A5. 会话级流式状态(流式中切会话)
来源:sessions 流式切换。**HIGH / M**
- **文件**:AIMainPage `startAiResponse/sendMessage/enterStreamingState(2507/2411/2865)`、`refreshSessionList`。
- **规格**(短期方案,不做真并发):
  1. 记录 `streamSessionId`;发送按钮仅当 `流中会话==当前会话` 时显示停止;在其他会话点发送 → toast"另一会话正在生成,请稍候或先停止"(不再静默 return)。
  2. 侧栏该会话行 `setRightGraphic(12px JFXSpinner)`。
  3. 断流续显:`fullContent`(+所属会话 id)提为页面字段;loadSessionMessages 末尾检测 in-flight 流属本会话时用当前值重建 streamingBubble,后续 onToken 正常追加。
  - 长期 per-session 并发按已有 XL 规划,另列不混入。

### A6. 多行输入框 + IME 守卫 + 草稿保护 ★主线 C-1
合并:composer 输入框、ia-visual 输入框(含"设置承诺了不存在的 Shift+Enter")。**HIGH / M**
- **文件**:AIMainPage :253、1268-1292、`handleInputKeyPress`;聊天设置抽屉 :3784 副标题。
- **规格**:
  - TextField → TextArea(`setWrapText(true)`,保留 `ai-input-field` 样式):监听 textProperty 量行数,prefHeight 钳制 1 行(34px)~8 行(≈180px),超出内部滚动;发送后回落 1 行。
  - 键位统一走 KEY_PRESSED 过滤器(移除 setOnAction/defaultButton):Enter 发送、Shift+Enter 换行;"回车发送"关闭时对调(Enter 换行、Ctrl+Enter 发送)。
  - **IME 守卫**:`setOnInputMethodTextChanged` 监听合成串,composing 期间 Enter 直接放行不拦截。
  - 草稿保护:页面持 `Map<sessionId, draft>`,切会话/切走页面时保存,回来恢复。
  - placeholder:"输入消息,Enter 发送,Shift+Enter 换行"。斜杠/@ 补全按 caret 位置迁移。

### A7. 附件通道分离 + 多附件 ★主线 C-2
合并:composer 附件、ia-visual 附件(同一处)。**HIGH / M**
- **文件**:AIMainPage `handleFileUpload/clearFileChip(1720-1767)`、`handleDragDropped(1785-1810)`、`sendMessage`。
- **规格**:
  - `selectedFilePath` → `List<AttachedFile>{path,name,size}`,内容延迟到 sendMessage 读取,组装为 `[附件: 名称]\n内容` 拼入送模型的 userInput;用户气泡只显示原文 + 小文件卡片。
  - fileChipArea 改 FlowPane 多 chip:文件图标+文件名+大小+独立 ×(只删自己);**删除检测 `[File:` 清空整框的逻辑**。
  - >200KB 提示自动截断或转工具读取。

### A8. 斜杠补全默认选中第一项
来源:composer autocomplete(回车被无声吞掉)。**HIGH / S**
- **文件**:AIMainPage 1945-1960、2016-2050。
- **规格**:弹出即 `autocompleteSelectedIndex=0`;navigateAutocomplete 范围 0..size-1 循环;输入与命令完全相等(如 `/crash `)时 Enter 直接发送;首项默认带 `ai-autocomplete-item-selected` 高亮。顺带把补全列表覆盖全部斜杠命令+中文说明(为 R2-B14 计划模式铺路)。

### A9. 空会话不堆积 + 新建聚焦
来源:sessions createSession。**HIGH / S**
- **文件**:AIMainPage `createSession(1149-1165)`。
- **规格**:当前会话空且标题默认 → 只 `showChatView()+inputField.requestFocus()` 后 return;切会话/退出页面时清理"空且非当前"会话;新建后聚焦输入框;加 Ctrl+N 页面级 accelerator。

### A10. 会话行:重命名/置顶/"⋯"菜单 + 图标/时间
合并:sessions 会话行操作、sessions 行信息、ia-visual 会话列表(三条同落点)。**HIGH / M**
- **文件**:AIMainPage `buildSessionItem(≈759-793)`、`refreshSessionList(736-747)`;`AiSession.java`、`AiSessionStore.java`。
- **规格**:
  - rightAction DELETE → MORE_VERT"⋯",弹 JFXPopup(重命名/置顶/删除);整行 `setOnContextMenuRequested` 弹同一菜单;悬停删除可保留。
  - 重命名 = Controllers.prompt 预填当前标题 → setTitle → refreshSessionList + save;**headerTitle 点击也可重命名**(hover 显示编辑小图标)。
  - 置顶 = AiSession 加 `boolean pinned`(Gson 兼容),listSessions 排序 pinned 优先再 updatedAt;置顶行标题前小图钉。
  - 图标 FOLDER → CHAT/气泡类;subtitle 显示相对时间(今天 HH:mm/昨天/周x/M月d日);`setTooltip(完整标题)`。
  - 删除确认文案带会话名:『删除会话"{标题}"?』。

### A11. 拉取模型对话框:去掉"确定=静默全加"
来源:settings showLoadModelsDialog。**HIGH / S**
- **文件**:AISettingsPage L706-814。
- **规格**:每行 ➕ 点击变 ✓(再点移除),即时写入+toast;已有模型置灰打勾;底部按钮改"全部添加(N)"(次要样式,N 随过滤实时变、只作用可见行)+"关闭"(主按钮无副作用);"添加模型"主路径改为先开此对话框,底部留"手动输入模型 ID…"链接。

### A12. 黑话文案全面人话化(纯 I18N,不动逻辑)
合并:ia-visual 文案黑话、composer 思考档位、agent-ux 权限徽标文案、ia-visual 硬编码符号。**HIGH / M**
- **文件**:`I18N_zh_CN.properties` ai.* 段、AIMainPage 思考弹窗(1366-1410)/approvalBadge(≈1127)、AISettingsPage :436 等。
- **规格**(逐项替换表):
  - YOLO → "全自动/自动放行"(徽章"自动放行·高风险",保留 error 配色);safe/ask/yolo UI 词表 → 谨慎/普通/全自动(代码 id 不动)。
  - Endpoint → "接口地址" + 灰字"从服务商控制台复制,通常以 /v1 结尾"。
  - 思考档位:none=关闭、low=快速、medium=标准、high=深入、xhigh=更深入、max=极限;每档副标题一句人话;灯泡按钮非 none 时着色 -monet-primary + 右下小圆点徽标;选中态用选中样式而非 title-label。
  - 温度/TopP/惩罚/种子 → "开发者选项"折叠卡(ComponentSublist,默认收起);上下文窗口 → "模型记忆长度"。
  - "请先配置 API 端点" → "还没有连接 AI 模型,点这里去配置"(可点跳转)。MCP服务器 → "扩展工具(MCP)";图片 OCR → "截图识字(OCR)"。
  - ⛔/✦/✓/▶/○ 字符:⛔ 删除(靠 MessageType 图标)、分支 ✦ → "(分支)"、todo 符号换 14px SVG。
  - "后台任务""思考:"等硬编码进 I18N。

### A13. 错误卡片 ErrorCard
合并:chat-main showAiError、ia-visual 错误态(同一处)。**MEDIUM / S**
- **文件**:AIMainPage `showAiError(3087-3136)`、`statusLabel`。
- **规格**:新建 ErrorCard(VBox):行1 = SVG.WARNING 16px + 错误标题(连接失败/密钥无效/请求超时);行2 = 灰字人话建议(401→"去 AI 设置>模型服务 检查 API 密钥",超时→"检查网络或代理");行3 = 重试(主)+ 401 时"去设置"跳 providerTab。-monet-error-container 系 token、8px 圆角、与 AI 内容列同宽对齐。半截回复**不再**加 ai-bubble-error 类,尾部灰字"(已中断)";statusLabel 不再承载错误;删除裸 add 的重试按钮及其 parent 判断逻辑。

### A14. 首启未配置引导 + 空状态改版
合并:chat-main buildEmptyState、ia-visual 首次体验(同一处)。**HIGH→MEDIUM / M**
- **文件**:AIMainPage `buildEmptyState()`、`setupModelSelector()`。
- **规格**:
  - 正常态:标题"嗨,我可以帮你玩 Minecraft" + 副标"装整合包、修崩溃、管存档、加模组,直接说就行";chips → 2×2 场景卡(SVG 图标+短标题+一行例句,-monet-surface-container 底 12px 圆角,hover 抬升):修复崩溃/安装整合包/优化卡顿/管理模组。
  - 未配置态:场景卡换成引导卡"先连接一个 AI 模型"+三步说明+主按钮"去配置"(navigate 到 providerTab 并自动弹添加对话框);composer 置灰,placeholder"配置模型后即可对话";`onSettingsChanged` 回调刷新;模型选择器无模型时显示"未配置 →"跳设置而非假选项。

### R1 快速清理包(全部 S,一个提交批量做)
- **TODO 卡/标题硬编码色**:root.css 加 `.ai-todo-done{-fx-text-fill:-monet-tertiary}` `.ai-todo-doing{-fx-text-fill:-monet-primary;-fx-font-weight:bold}` `.ai-todo-pending{-fx-text-fill:-monet-on-surface-variant}`;`updateTodoCard(≈3366)` 删 setStyle 改挂类;headerTitle(977)/AISettingsPage:680 内联样式移入 CSS;全文件 grep `setStyle(` 清理。
- **AI 入口升位**:`RootPage.java 229-246`,AI 单独 `startCategory("助手")` 置于"版本"后、"设置"前;可选 subtitle"崩溃分析 / 装模组 / 找资源"。
- **API Key 粘贴清洗**:setApiKey 保存时 `replaceAll("\\s+","")`;眼睛按钮加 tooltip。(多 Key 轮询归 R3)
- **侧栏搜索入口**:rebuildChatSidebar topBox"新建对话"下加 AdvancedListItem(SVG.SEARCH,"搜索对话",onAction=showSearchOverlay);头部放大镜保留。
- **权限徽标可点 + 人话**(agent-ux,HIGH/S):approvalBadge 改 JFXPopup 下拉三项——"谨慎:只查看不动手,任何改动先问我 / 普通(推荐):重要操作先问我,小事自动做 / 全自动:全部自动执行,不再询问(有风险)";选全自动保留警告弹窗;设置页同控件同文案;SAFE 默认态不显示徽标、ASK 灰、全自动红(顶栏减负,合并 ia-visual 顶栏冗余的徽标部分);planBadge 同样可点退出。

---

## R2 —— 第二批

### B1. 思考过程(reasoning)渲染 ★主线 A(可与 A-3 并行开工)
**HIGH / L**。目标用户主力模型是 DeepSeek,优先级实际很高,放 R2 首位。
- **文件**:`LlmStreamCallback.java`(加 `onReasoningToken(String)`)、各协议适配层(`LangChain4jChatAdapter` 等透传 reasoning_content/thinking)、`LlmMessage.reasoning` 持久化(A-1 已留字段)、AIMainPage 回合容器顶部。
- **规格**:折叠卡"思考过程 · x 秒"+chevron,-monet-surface-container 底、-monet-on-surface-variant 12px;流式期间自动展开跟随滚动,首个正文 token 到达自动折叠,点击可重开;历史重载还原为折叠态。

### B2. 回合容器 + 流式实时 Markdown + 三点动画 ★主线 A-3/A-6
合并:chat-main 分段气泡、chat-main typing 指示(两条互为前后件)。**MEDIUM / M+L**
- **规格**:见主线 A-3/A-6。补充:删除每段重复的 "AI" 标签;`setStatus/typingTimeline` 底部"分析中…"移除 typing 用途;三点动画 = 3 个 6px Circle 交错 FadeTransition。

### B3. AI 回合头行:头像 + 模型名 + 时间
来源:chat-main bubbleName。**MEDIUM / M**。依赖 A-1(timestamp/model 字段)。
- **规格**:删用户侧名字标签;AI 头行 = 20px 圆形 SVG.AI 头像 + 模型别名 + HH:mm(12px,-monet-on-surface-variant),token 用量并入同行尾部(替代单独 usage footer 行);跨天插入居中日期分隔线。

### B4. 滚动跟随修复 + 回到底部按钮
来源:chat-main stickToBottom。**HIGH / M**(未进 R1 仅因依赖渲染层稳定后调试更省)
- **规格**:stickToBottom 改 `vvalueProperty` 监听(内容增长引起的变化打标忽略,用户引起的 vvalue<0.97 置 false);messagesArea(StackPane)右下角 40px 圆形 JFXButton(向下箭头,-monet-surface-container-high+elevation,BOTTOM_RIGHT 边距 16),仅 !stickToBottom 淡入,点击滚 1.0 恢复跟随;可加新消息计数小红点。

### B5. 审批改内联卡片 + diff 预览 ★(架构级,agent 线程阻塞模型不变)
来源:agent-ux 审批。**HIGH / L**
- **文件**:AIMainPage `confirmDangerousOperation(≈2087)/confirmCriticalOperation(≈2111)`、`LangChain4jToolAdapter.summarizeForConfirm(≈340)`、`AiToolPermissionStore`。
- **规格**:
  1. 两级审批统一为一张内联审批卡(插 messageList 底部,agent 线程仍阻塞 future):普通=默认边框,高危=红色强调边(-monet-error 系)+"此操作可能不可恢复"警示行,**取消第二个弹窗**。
  2. 内容:description 一句话首行 + 可展开技术细节;edit/write_file 渲染逐行红绿 diff(EditTool 已有 old/new);shell 用等宽代码块;**summarizeForConfirm 非 shell 工具逐参数键值渲染,禁止 params.toString()**。
  3. 按钮三件套:允许一次 / 本次会话总是允许该工具(写会话级覆盖)/ 拒绝(附可选输入框"告诉 AI 换个做法"回填拒绝理由)。
  4. 删 120s 自动拒绝:悬置时暂停计时,>10 分钟才提示过期。

### B6. ask 面板迁入消息流
来源:agent-ux ask。**HIGH / M**。依赖 A-1(答案作为消息持久化)。
- **文件**:AIMainPage `showAskPanel(≈2893)/buildAskControl(≈2976)`、`ui/ai/tools/AskTool.java`。
- **规格**:ask 渲染为 messageList 内助手侧内联卡;≤3 题同屏(每题一组 chip/复选),>3 题步进;单选 = JFXButton chip 点击高亮;**取消默认选中与 defaultButton**(防 Enter 误提交);提交后卡片就地转只读(选中打勾),汇总答案以用户消息持久化("我选择:1.21.1 / Fabric");停止/切会话时转"已取消"只读态。

### B7. 后台任务进度卡 + 任务条改造
来源:agent-ux jobs。**HIGH / L**。依赖 A-1/A-4(卡片持久化)。
- **文件**:AIMainPage `refreshJobsPane(≈2702)`、`buildComposer jobsPane(1318-1350)`、`LangChain4jToolAdapter(≈264)`、`AiJobManager`。
- **规格**:
  1. 对话内:wantsBackground 分支回调插入持久化 JobProgressCard(任务名=job.getLabel() 人话版),订阅 ToolProgress/AiJobManager 更新 JFXProgressBar+百分比+速度;完成/失败原地定格(✓绿/✗红可展开)。
  2. 上拉条:每 job 一行 = 状态点+label+迷你进度条+单独取消;类目映射中文。
  3. 派发文案人话:"已在后台开始「安装 Fabric」,你可以继续聊天,完成后我会自动继续";check_job 等指令只进模型上下文(model-facing 返回值与 UI 事件分两条通道)。

### B8. 停止/继续体验
来源:agent-ux 停止。**MEDIUM / M**。与 C-3 发送键图标化同批。
- **规格**:sendBtn 空闲=纸飞机/流式=停止方块(同一圆钮换 graphic);stopResponse 后被截断气泡下渲染"▶ 继续"chip(点击发送固定续写指令,新消息时移除);停止时 activeToolCard 非空 → "正在停止…"禁用态,工具返回后复位、卡片标"已中断"。

### B9. 连通性"检查"按钮(提供商粒度)★主线 B
来源:settings 测试。**HIGH / M**
- **文件**:AISettingsPage `buildModelSectionTitle(292-306)`、`showTestModelsDialog(819-940)`。
- **规格**:API Key 行尾"检查"JFXButton:用该提供商默认模型跑 testConnectionSync,按钮态 转圈→绿 CHECK_CIRCLE 常驻/红 ERROR+人话错误(401→"API Key 不正确"、404→"API 地址或模型名不对"、超时→"网络不通,可能需要代理");批量测试树移入"高级"折叠,火箭图标从分区标题删除;结果符号统一 SVG+monet 语义色。

### B10. 默认模型区 + ModelPicker ★主线 B-1/B-2
来源:settings 默认模型、settings 解耦(见主线 B 表,此处为排期占位)。**HIGH / L+M**

### B11. 两步式添加提供商 ★主线 B-3(见主线表)。**HIGH / M**

### B12. 组合器卡片化 ★主线 C-3(见主线表)。**HIGH / M**

### B13. TODO 摘要条 + 持久化
来源:agent-ux todo(R1 已修颜色,此处做形态)。**MEDIUM / M**
- **规格**:改单行摘要条:迷你进度环(done/total)+当前 in_progress 项+展开箭头,默认折叠;todos 持久化到 AiSession(随 todo_write 覆写),切会话恢复;整单完成显示"✓ 全部完成"3 秒后收起为小徽标。

### B14. 计划模式可见入口
来源:agent-ux /plan。**MEDIUM / M**
- **规格**:composer 工具栏(思考灯泡旁)加"计划"切换钮(激活 -monet-primary 高亮,tooltip"先出方案、批准后再动手"),与 /plan 双向同步;计划模式下方案气泡尾部附"按计划执行 / 调整方案"按钮(执行自动退出计划模式续跑)。

### B15. 第二批杂项(S/M 打包)
- **自动标题合并**(sessions):单开关"自动命名会话"(默认开)+"命名模型"下拉(与 B-2 合并实现);LLM 命名开启时占位"新对话",返回后一次替换+侧栏行 150ms 淡入;回落截断上限 20 字。
- **会话时间分组**(sessions):refreshSessionList 按 updatedAt 分桶(置顶/今天/昨天/近7天/近30天/更早),小节 Label 11px -monet-on-surface-variant、不可点击。
- **侧栏折叠**(sessions):header 最左加 JFXButton(SVG.MENU)切换 setLeft(sidebarRoot/null),状态入 chatSettings;widthProperty<700px 自动收起。
- **拖拽全区 + 遮罩**(composer):DragOver/Dropped 移到 chatView 根;StackPane 层 dropOverlay(-monet-scrim 半透明+居中"松开以添加文件",`ai-drop-overlay`),ENTERED 显示/EXITED/DROPPED 隐藏;不支持类型 snackbar 提示;多文件进 chip(依赖 A7)。
- **设置页 IA 重排**(settings + ia-visual 两条合并):侧栏顺序 模型服务(含默认模型)→全局设置→技能与工具→MCP/搜索/OCR(可合并为"扩展能力"一个 tab)→数据与记忆→帮助与关于(合一页);默认选中=第一项;审批模式只留"技能与工具"一处;分类标题统一中文;聊天抽屉按钮换图标(SVG.TEXT_FIELDS)+tooltip"显示设置",抽屉只留纯显示项,流式输出/回车发送/自动滚动/工具调用显示迁入全局设置,抽屉底部"更多设置 →";推理强度只留 灯泡(会话临时)+每模型默认 两处。
- **反馈通道统一**(settings):删 providerFeedback Label,成功→toast,对话框内失败→DialogPane.onFailure 内联;"至少保留一个配置"改禁用按钮+tooltip(配合 B-1 后可允许全部禁用)。
- **备份细节**(settings):行序 备份→精简备份→恢复→导出;文件名加 LocalDate;恢复成功弹"[立即重启][稍后]";恢复前自动先备份一份。
- **i18n 迁移**(ia-visual):所有硬编码中文提 ai.* key(en 同步),SLASH_COMMANDS 描述改 key;先 AIMainPage 后 AISettingsPage;**与 UltraPlan i18n P0 合并执行**。

---

## R3 —— 锦上添花

| 项 | 来源 | 规格要点 |
|---|---|---|
| C1. 场景预设(轻量助手) | sessions 无助手概念 | 内置 3-5 预设(装模组/崩溃医生/联机帮手/纯净问答)=系统提示词片段+开场引导;入口=空状态预设卡+头部下拉;AiSession 加 presetId,ChatAgentFactory 拼接;侧栏行用预设图标。**M-L,依赖 A14 空状态** |
| C2. 图片附件/多模态 | composer 图片 | FileChooser 加图片过滤;Ctrl+V 粘贴截图→临时目录→48px 缩略 chip;LLM 层按 Anthropic/OpenAI 多模态格式组装 image 块;不支持视觉的模型 snackbar"已忽略"。**L,依赖 A7 + settings 能力位** |
| C3. 提供商 logo | settings logo | 捆绑 12 预设服务商 SVG/PNG(assets/img/ai-providers),ProviderPreset 加 iconId,添加时写入 profile(不按 endpoint 猜);副标题精简为"已启用 · N 个模型 / 未填 API Key"。**M,B-4 前做可直接进主从左列** |
| C4. 模型能力徽章 + 分组 | settings badges | ModelChoice 右侧徽章 HBox(眼睛=视觉/扳手=工具/灯泡=推理,14px monet token+tooltip);>8 个按 id 前缀 ComponentSublist 折叠;API 拉取时 ModelLibrary.find 自动回填能力位 | 
| C5. @提及可读化 + @文件 | composer @ | 候选两行(标题+相对时间·消息数);插入"@会话标题",内部 title→id 映射;@文件合并 latest.log/最新 crash-report 为附件 chip;空时显示"没有可引用的会话" |
| C6. composer 模型 chip | composer | 发送键左侧小 chip 显当前模型别名,点击弹与 header 同源菜单(setupModelSelector 抽公共方法);依赖 B-1 结构化引用 |
| C7. 上下文占用指示 | composer | 工具栏 contextIndicator:"上下文 N 条 · 约 X tokens"(LlmUsage.estimate 求和);>70% 转 -monet-error+tooltip 建议 /compact;点击触发 compactConversation 确认 |
| C8. 键盘可达性 | ia-visual a11y | 建议 chip/工具卡 header 改 JFXButton 或 setFocusTraversable+Enter;思考档位换 JFXRadioButton;:focused 边框 -monet-primary;全页过一遍 Tab 顺序 |
| C9. 杂项 | 多处 | 分支改良(标题"(分支)"R1 已做文案,此处补 parentId/subtitle"分支自:xx"+toast+fork 图标);多 Key 逗号轮询;headerSubtitle 去重(只留会话元信息);会话行导出入口(复用 ExportConversationTool) |

---

## 实施顺序与依赖(建议提交序)

```
R1:
 1. A-1/A-2 数据模型奠基(LlmMessage/AiSession 扩展 + 写入侧)   ← 一切持久化类改动的地基
 2. A1 工具卡持久化 + A3 事件胶囊(bug:工具卡片持久化、后台续跑气泡)
 3. A2 操作条 + 删 onComplete 重渲染(bug:内联编辑守卫)
 4. A5 会话级流式状态(bug:流式中切设置)
 5. A6+A7 输入区 TextArea+附件分离(bug:草稿保护)
 6. A4 宽度自适应;A8/A9/A10/A11;A13/A14
 7. A12 文案人话化 + 快速清理包(纯文案/CSS,随时可插队)
R2:
 8. B1 reasoning → B2 回合容器/流式MD → B3 头行 → B4 滚动   (渲染层按序)
 9. B5 审批内联卡 → B6 ask 进消息流 → B7 任务进度卡 → B13 TODO → B8 停止/继续 → B14 计划按钮 (agent 体验线)
10. 主线B:B-1 解耦 → B10 默认模型 → B11 两步添加 → B9 检查按钮 → (最后) B-4 主从布局 XL
11. B12 组合器卡片化;B15 杂项包(i18n 与 UltraPlan P0 合并)
R3:按需排,C3/C4 建议赶在 B-4 主从布局合入前完成以免二次改列表行。
```

**风险提示**:主线 A 的 A-3~A-6(回合容器+实时 Markdown)是全案唯一"重写级"改动,建议在 A-1/A-2 合入并稳定一个版本后再动;R1 阶段 A1/A2/A3 先在现有逐气泡渲染骨架上落地(tool/event 消息重放已可工作),不必等回合容器。