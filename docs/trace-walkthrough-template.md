<!--
用途：分析一份诊断反馈（简化 UI trace）时，复制本文件为新文件（建议命名
trace-walkthrough-<反馈ID>.md），按下面的结构填写。视觉风格已经和用户对齐过（2026-07-09
定稿），不要再回退成纯素文本版——具体约定见下。

数据来源：优先使用"简化 UI trace"——文件名 `ui-trace.jsonl`，随诊断上传 zip 一起提供，
2026-07-09 已实现并验证（数据源直接来自 AiSessionStore 的会话消息模型，不是从原始
trace 里过滤出来的）。逐行 JSONL，每行是以下三种之一：
  {"type":"user","ts":...,"text":"..."}
  {"type":"assistant","ts":...,"text":"...","model":"..."}
  {"type":"tool","ts":...,"turnId":"...","name":"...","args":"...","result":"...","success":true}
已脱敏（走 Redactor），不含系统提示词/技能正文/<turn-context>/原始HTTP请求响应体。
只有反馈是 2026-07-09 之前上传的（还没有这份文件）才需要退回读原始 ai-trace.jsonl 自行
提炼（更费token，仅在没有简化版时使用）。

━━━ 视觉风格约定（2026-07-09 定稿，经过几轮试错才稳定下来）━━━

关键教训：不要用 <style> 块或 class="..." ——这个 md 渲染管线的安全过滤器会把整个
<style> 块连内容一起吃掉，class 也不生效，改了几版才发现。能用的只有：
  ① 原生 Markdown 元素（表格、引用块 >、粗斜体、代码span）——这些保证有边框/底色，
     不依赖任何自定义样式，渲染器自带默认样式。
  ② 直接写在标签上的内联 style="..." 属性——这个能生效（背景色、边框、圆角都可以）。
  绝对不要：<style> 标签、class 属性、外部/内部样式表。每处需要样式的地方都必须重复写
  完整的内联 style，即使显得啰嗦——这是为了保证能在这个渲染管线里显示出来。

标注徽标（内联彩色小标签，5 种，直接复制对应一行去用，把文字换掉）：

✅ 已修复：
<span style="background:var(--bg-success);color:var(--text-success);border:1px solid var(--border-success);padding:2px 10px;border-radius:10px;font-weight:600">✅ 已修复</span>

⏳ 仍待处理：
<span style="background:var(--bg-warning);color:var(--text-warning);border:1px solid var(--border-warning);padding:2px 10px;border-radius:10px;font-weight:600">⏳ 仍待处理</span>

🆕 新发现：
<span style="background:var(--bg-accent);color:var(--text-accent);border:1px solid var(--border-accent);padding:2px 10px;border-radius:10px;font-weight:600">🆕 新发现</span>

❓ 待确认：
<span style="background:var(--bg-pro);color:var(--text-pro);border:1px solid var(--border-pro);padding:2px 10px;border-radius:10px;font-weight:600">❓ 待确认</span>

✔️ 无问题：
<span style="background:var(--surface-1);color:var(--text-secondary);border:1px solid var(--border);padding:2px 10px;border-radius:10px;font-weight:600">✔️ 无问题</span>

标注写法（统一两段式，不要写成单行，也不要写成大段落——保持这个节奏）：
  > <span style="...">✅ 已修复</span> 🎯 **一句话标题**
  >
  > 一到三句话说明：根因在哪个文件/哪个函数，今晚/近期有没有对应修复，代价是什么。

对话步骤：永远用表格（角色 | 内容 两列），不要写成"**用户**：..."这种行内文字——
哪怕这个回合只有一步也用表格，保持每个回合结构一致。角色列用图标：
  👤 用户　🤖 助手　🔧 工具（正常）　❌ 工具（报错/失败）　🚦 护栏事件　💬 用户回答（夹在ask结果里的原话）

优先级原则（2026-07-09 用户明确强调）：还原真实发生的顺序和内容优先于精简——
不要把连续多次同类调用折叠成"共N次调用（详见下表）"这种占位摘要，要按真实顺序把每一步
展开（工具名+真实参数，比如真实的搜索关键词/mod名），保持哪怕啰嗦也要真实。如果同一批里
有多个连续同类调用，可以合并成一行但必须列出每次调用的真实参数，例如：
  🔧 工具×5 | `search(mods)`：Embeddium / JEI / Jade / Xaero's World Map / Xaero's Minimap
额外的证据表格（时间戳对比、报错列表等）作为对话表格之后的补充，不能替代真实内容。

原则：
  - 只标注真的值得说的节点，不必逐条工具调用都写一句——正常、符合预期的步骤直接跳过。
  - 标注要具体到文件/函数/根因，不要只说"这里有问题"。
  - 如果这条反馈牵出了新发现，随手也补进相关的历史记录/状态文件里，避免下次重复发现。
-->

# 🔍 <反馈ID> 会话回放

| 📅 上传时间 | 💻 客户端 | 🤖 模型 | 🆔 反馈ID |
|---|---|---|---|
| `<ISO时间戳>` | `<version>` / `<os>` | `<model>` | `<F-XXXXXX>` |

### 📊 本轮体检总览

<span style="background:var(--bg-success);color:var(--text-success);border:1px solid var(--border-success);padding:3px 12px;border-radius:14px;font-weight:600">✅ 已修复 × &lt;N&gt;</span>　<span style="background:var(--bg-warning);color:var(--text-warning);border:1px solid var(--border-warning);padding:3px 12px;border-radius:14px;font-weight:600">⏳ 仍待处理 × &lt;N&gt;</span>　<span style="background:var(--bg-accent);color:var(--text-accent);border:1px solid var(--border-accent);padding:3px 12px;border-radius:14px;font-weight:600">🆕 新发现 × &lt;N&gt;</span>　<span style="background:var(--bg-pro);color:var(--text-pro);border:1px solid var(--border-pro);padding:3px 12px;border-radius:14px;font-weight:600">❓ 待确认 × &lt;N&gt;</span>

修复进度：`<用🟩表示已修复、🟥表示未修复，按总数拼，比如 🟩🟩🟩🟩🟥>` `<已修复数>/<总数>`

共 `<N>` 个用户回合，按真实时间顺序回放如下。

---

## 🎬 回合 1 — "<用户这句话的原文，节选即可>"

| 角色 | 内容 |
|---|---|
| 👤 用户 | <原始消息文本> |
| 🔧 工具 | `<工具名>(<真实参数>)` → `<真实结果摘要>` |
| 🤖 助手 | <回复文本，尽量原文> |

<!-- 如果这一步有问题，紧跟一条两段式标注；没问题就跳过，继续下一行 -->
> <span style="background:var(--bg-success);color:var(--text-success);border:1px solid var(--border-success);padding:2px 10px;border-radius:10px;font-weight:600">✅ 已修复</span> 🎯 **<一句话标题>**
>
> <根因 + 修法，一到三句话>

<!-- 如果同一批里有多个连续同类调用（比如连续5次search），额外加一张时间戳对比表证明
     "是否并行"这类问题，不要用它替代上面对话表格里的真实内容：

| 🔢 批次 | 调用数 | 首个时间戳 | ⏲️ 间隔 | 判定 |
|:---:|:---:|---|:---:|:---:|
| 第一批 | 5 | `...` | ~...ms | 🐌 串行 / ⚡ 并行 |

-->

---

## 🎬 回合 2 — "<...>"

<!-- 重复上面的结构：对话表格 → （可选）证据表格 → 逐条两段式标注 -->

---

## 🏁 小结

<!-- 三五句话：这份反馈整体反映了什么问题？有没有真正的崩溃/数据丢失？
     问题主要集中在哪个层面（护栏误报/工具没走并行/措辞不准确/边界情况报错/纯UX建议）？
     哪些是新发现、需要单独跟进？收尾可以用一个徽标点出唯一悬而未决的项，例如：

唯一悬而未决：<span style="background:var(--bg-warning);color:var(--text-warning);border:1px solid var(--border-warning);padding:2px 10px;border-radius:10px;font-weight:600">⏳ <一句话></span>
-->
