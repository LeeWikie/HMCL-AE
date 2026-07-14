I have all five research reports and don't need to touch the tree — this is a synthesis task. Here is the design doc, returned directly.

---

# HMCL-AE RAG Integration — Implementation Plan (AstrBot-modeled)

## 0. The one architectural decision everything hinges on (read first)

AstrBot's native KB embeds **both** the corpus (at ingest) and the query (at retrieval) with the **same** configured `EmbeddingProvider`, then does FAISS cosine + FTS5 BM25 + RRF **inside its own process**. Vector-space consistency is guaranteed because one provider builds and queries the index.

HMCL-AE cannot naively copy that, because of one hard fact from the `hmcl-rag-wiring` research: the existing `minecraft-knowledge-base` dense index (`kb_data/dense/vectors.npy`, ~8.6k × 384-dim) was built by **Python `fastembed` / `paraphrase-multilingual-MiniLM-L12-v2` (ONNX)**. A query embedded by any OpenAI-compatible `/embeddings` model lands in a **different vector space** → dense retrieval silently returns garbage unless the whole index is rebuilt with that same model.

So there are two coherent designs, and we build to support **both** as explicit "KB source modes" (this is what keeps the embedding picker load-bearing instead of AI-slop):

| Source mode | Who embeds the query | Embedding-model picker | Vector-space risk | Recommended for |
|---|---|---|---|---|
| **REMOTE_HTTP** (default) | The FastAPI server (`kb_server/hybrid.py:52`) | Not required (informational only) | None — server owns both sides | The shipped Minecraft KB (fastembed index already exists) |
| **LOCAL_INDEX** | HMCL-AE in-process via the picked embedding model | **Required + load-bearing** | High — index must be rebuilt with the picked model | Custom user corpora, offline use, future |

**Recommendation:** ship **REMOTE_HTTP as the default and only fully-wired retrieval path in v1**, but build the embedding capability + `EmbeddingModel` runtime + the picker in full, because (a) the user explicitly wants the AstrBot-style embedding-provider separation and picker, (b) it is a cheap, self-contained mirror of the existing `supportsVision` pattern, and (c) it is the sole prerequisite for LOCAL_INDEX, which we scaffold in Phase 4 behind a clearly-labeled "requires index rebuilt with this model" warning. The picker becomes **required and enforced** only when mode = LOCAL_INDEX; in REMOTE_HTTP it is shown but marked "server-managed."

This is faithful to AstrBot's two-layer split (capability type vs adapter) while being honest about the desktop/offline constraint. Nothing below is a stub; each phase has a provable Definition of Done.

---

## 1. Phased build order (user directive: "KB settings options first")

- **Phase 1 — KB settings + embedding capability metadata.** Everything visible/persistable, no retrieval yet. Mirrors AstrBot's `ProviderType.EMBEDDING` flag + `knowledgebase` config block. *Ships as a self-contained, testable increment.*
- **Phase 2 — Embedding runtime provider.** Make the `supportsEmbedding` flag behavioral: a real `EmbeddingModel` build path (mirrors AstrBot's `EmbeddingProvider` + `openai_embedding_source.py`). No retrieval consumer yet, but unit-testable end-to-end against a live `/v1/embeddings`.
- **Phase 3 — Retrieval wiring (REMOTE_HTTP).** Gated `kb_search` tool → FastAPI. This is the user-facing RAG.
- **Phase 4 — Server dense wiring + LOCAL_INDEX scaffold.** Wire `hybrid.py` into a route; implement the local in-process retriever that consumes Phase 2's `EmbeddingModel`.

---

## Phase 1 — KB settings + embedding capability metadata

### 1A. Add the `embedding` model capability (verbatim mirror of `supportsVision`)

This is exactly AstrBot's `ProviderType.EMBEDDING` idea, expressed in HMCL-AE's metadata-flag idiom. Four files, plus data backfill.

**File 1 — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/AiModelEntry.java`** (next to `supportsReasoning`, ~line 89):
```java
@SerializedName("supportsEmbedding")
private boolean supportsEmbedding = false;
public boolean isSupportsEmbedding() { return supportsEmbedding; }
public void setSupportsEmbedding(boolean v) { this.supportsEmbedding = v; }
```
Persistence is automatic — `AiProviderProfile.models` is a Gson-serialized field (`@SerializedName("models")`), and `save()` preserves per-model config via `encodeProfilesForSave → p.setModels(...)` (`AiSettings.java:1328`). **No `AiSettings.PersistedData` edit required.**

**File 2 — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/ModelLibrary.java`** (nested `ModelInfo`, ~246–345):
```java
@SerializedName("supportsEmbedding")
private boolean supportsEmbedding;
public boolean isSupportsEmbedding() { return supportsEmbedding; }
```
No parser change (Gson maps by `@SerializedName`; missing key → `false`). Bump `schemaVersion` in the JSON `2 → 3` for provenance.

**File 3 — `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/AISettingsPage.java`** (the `editModel` dialog):
- Declare `capEmbeddingBox` alongside `capToolsBox/capVisionBox/capReasoningBox` and add it to `modelCapabilityRow(...)` (~648–654).
- Init from entry: `capEmbeddingBox.setSelected(entry.isSupportsEmbedding());` (~649–653).
- Auto-fill on model lookup: in the `ModelLibrary.find(id)` block (701–703) add `capEmbeddingBox.setSelected(info.isSupportsEmbedding());`.
- Write-back in `onAccept()` (752–754): `entry.setSupportsEmbedding(capEmbeddingBox.isSelected());`.

**File 4 — data backfill, `HMCLAI/src/main/resources/assets/ai/model-library.json`**: set `"supportsEmbedding": true` on the 26 embedding-named ids (`mistral-embed`, `gemini-embedding-001`, `cohere-embed-v3-english`, `text-embedding-3-small/large`, …). Missing key → `false` elsewhere; backfill is for UX (auto-fill + picker filtering), not compile.

> Design note (why a flag, not a separate `ProviderType` enum): AstrBot separates providers into distinct instances by `provider_type`. HMCL-AE has no provider-type enum for models — capability is per-`AiModelEntry` booleans. The faithful mirror is therefore a **capability flag on the model entry**, and the KB picker filters on it (§1C). A model may be both chat- and embedding-capable; the flag just governs whether it appears in the embedding picker. Do **not** introduce a parallel provider hierarchy — it would fight the existing `AiProviderProfile`/`AiModelEntry` two-tier model.

### 1B. KB config state — new `AiKbConfig` (mirror `AiSearchConfig`)

Put **all** KB settings in a dedicated config object + JSON file, exactly like search/OCR/MCP do. This is deliberate: `ToggleToolsBinder.bind(...)` (Phase 3) needs a live `BooleanProperty enabledProperty()`, and `AiSearchConfig` already models precisely this shape.

**New file — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/kb/AiKbConfig.java`** (mirror `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/search/AiSearchConfig.java`):

Fields (JavaFX properties + Gson `@SerializedName`):
| Field | Type | Default | Purpose |
|---|---|---|---|
| `enabled` | `BooleanProperty` | `false` | master gate → `enabledProperty()` for `ToggleToolsBinder` |
| `sourceMode` | `ObjectProperty<KbSourceMode>` | `REMOTE_HTTP` | enum `{REMOTE_HTTP, LOCAL_INDEX}` |
| `endpoint` | `StringProperty` | `"https://agentexperience.online"` | FastAPI base; path suffix appended in tool |
| `localIndexPath` | `StringProperty` | `""` | dir holding `vectors.npy`/`doc_ids.npy`/`doc store` for LOCAL_INDEX |
| `embeddingModelRef` | `StringProperty` | `""` | `"profileId::modelId"` of the picked embedding model (AstrBot's `embedding_provider_id` linkage) |
| `topK` | `IntegerProperty` | `5` | final results returned to the model (AstrBot `kb_final_top_k`) |
| `fusionTopK` | `IntegerProperty` | `20` | fusion-stage count for LOCAL_INDEX (AstrBot `kb_fusion_top_k`); sent as a hint to server in REMOTE |

Add a resolver mirroring `AiSettings.resolveTitleNamingModel()` / `TitleNamingSelection` (`AiSettings.java:596–638`):
```java
/** Parse embeddingModelRef ("profileId::modelId"), tolerate bare model id, return null if stale/deleted. */
public @Nullable ResolvedEmbeddingModel resolveEmbeddingModel(AiSettings settings) { ... }
```
Return a small record carrying the resolved `AiProviderProfile` + `AiModelEntry` (so Phase 2 can build `LlmConfig` from it). Null → treat as "not configured" (LOCAL_INDEX disabled with a validation message; REMOTE_HTTP unaffected).

Persistence: own JSON file `{configDir}/ai-kb-config.json`. Wire load/save like `searchConfig`/`ocrConfig`:
- Constructor-inject `AiKbConfig` into `AISettingsPage` from `AIMainPage` (mirror `AIMainPage.java:1176–1199`).
- Config-file `Path` field in `AISettingsPage` (~148–152) + local `saveKbConfig()` writing `GSON.toJson(...)` (mirror `saveSearchConfig()` 2776–2782).
- Load in `AIMainPage` where `searchConfig`/`ocrConfig` are constructed (mirror `AIMainPage.java:195` region).

> Why not stash KB fields in `ai-settings.json` (the `titleNamingModel` route)? Because the tool binder wants a cohesive observable config object; scattering seven fields across `AiSettings`' seven-spot ritual (DTO, default, property, ctor, accessor, `save()`, `setLegacyFieldsFromData()`) for each field is more code and less cohesive than one `AiKbConfig`. The **only** thing in `AiSettings` proper is nothing — KB is fully self-contained. (If a future requirement needs the embedding ref visible to non-KB code, promote `embeddingModelRef` to `AiSettings` following the `titleNamingModel` seven-spot pattern; not needed now.)

### 1C. KB settings tab UI (`AISettingsPage.java`)

New tab, wired like every other section (mirror `buildSearchTab()` 1638–1760 — the closest analog: enable toggle + provider/model picker + endpoint + options).

1. Field decl (~172–188): `private final TabHeader.Tab<Node> knowledgeBaseTab = new TabHeader.Tab<>("aiKnowledgeBaseTab");`
2. Supplier bind (~215–225): `knowledgeBaseTab.setNodeSupplier(this::buildKnowledgeBaseTab);`
3. Register in `TabHeader` ctor (~227).
4. Nav entry (~237–249), under the `services` category: `.addNavigationDrawerTab(tab, knowledgeBaseTab, i18n("ai.settings.nav.knowledge_base"), SVG.BOOKSHELF /* pick an existing SVG const */)`.

`buildKnowledgeBaseTab()` composes `ComponentList` cards:

**Card 1 — "启用" / General**
- `toggleRow(i18n("ai.settings.kb.enable"), i18n("ai.settings.kb.enable.desc"), kbConfig.enabledProperty())` (helper 2473–2480; binds bidirectionally + saves on change — but route the save through `saveKbConfig()`; if `toggleRow` hardcodes `saveAiSettings()`, add a `toggleRow` overload taking a `Runnable onChanged` or inline the row like `buildTitleNamingModelRow` does).
- Source-mode picker: `LineSelectButton<KbSourceMode>` (mirror the reasoning-effort picker 2269–2282), items `{REMOTE_HTTP, LOCAL_INDEX}`, `setNullSafeConverter` → localized labels.

**Card 2 — "嵌入模型" / Embedding model (the AstrBot linkage)**
`buildKbEmbeddingModelRow()` — a near-verbatim copy of `buildTitleNamingModelRow()` (2301–2339), with **one change: filter items to embedding-capable models**:
```java
List<String> items = new ArrayList<>();
items.add(""); // "未选择" sentinel
for (AiProviderProfile p : aiSettings.getProfiles())
    for (AiModelEntry e : p.getModels())
        if (e.isSupportsEmbedding())                    // ← the capability flag earns its keep
            items.add(p.getId() + "::" + e.getId());
LineSelectButton<String> row = new LineSelectButton<>();
row.setTitle(i18n("ai.settings.kb.embedding_model"));
row.setSubtitle(i18n("ai.settings.kb.embedding_model.desc"));
row.setItems(items);
row.setNullSafeConverter(v -> /* "ProviderName · ModelAlias" */);
String cur = kbConfig.getEmbeddingModelRef();
row.setValue(items.contains(cur) ? cur : "");           // stale/deleted → sentinel (mirror resolveTitleNamingModel tolerance)
row.valueProperty().addListener((o, old, v) -> { kbConfig.setEmbeddingModelRef(v == null ? "" : v); saveKbConfig(); });
```
This is exactly AstrBot's KB-create dialog "embedding model dropdown (from configured `provider_type: embedding` providers)". When `sourceMode == REMOTE_HTTP`, disable/annotate the row with `i18n("ai.settings.kb.embedding_model.server_managed")` (informational); when `LOCAL_INDEX`, it is required and drives validation (§1E).

**Card 3 — "知识库来源" / Source**
- Endpoint field: reuse the plain text field pattern from `buildSearchTab` (endpoint uses a `JFXTextField`/apiKey-style row; KB endpoint needs no key today). Row visible when `sourceMode == REMOTE_HTTP`.
- Local index path field + a "browse" button, visible when `sourceMode == LOCAL_INDEX`.
- (Bind visibility to `sourceMode` via a listener that calls `invalidateTab(knowledgeBaseTab)` — helper 290–295.)

**Card 4 — "检索参数" / Retrieval**
- `sliderRow(i18n("ai.settings.kb.top_k"), i18n("ai.settings.kb.top_k.desc"), kbConfig.topKProperty(), 1, 20, i18n("ai.settings.kb.top_k.unit"))` (helper 2531–2558).
- `sliderRow(...fusion_top_k..., kbConfig.fusionTopKProperty(), 1, 50, ...)` — shown only for LOCAL_INDEX (server owns fusion in REMOTE).

Finish with `return wrapScroll(root);`.

### 1D. i18n (base + Simplified Chinese; user works in zh_CN)

Add to `HMCL/src/main/resources/assets/lang/I18N.properties` (base/English — mandatory, IDE-validated) and `I18N_zh_CN.properties`. Keys (cluster near existing `ai.settings.nav.*` 627–639 and model `cap_*` 601–604):

```
ai.settings.nav.knowledge_base           = Knowledge Base            / 知识库
ai.settings.model.cap_embedding          = Embedding                 / 嵌入
ai.settings.kb.enable                     = Enable Knowledge Base     / 启用知识库
ai.settings.kb.enable.desc                = ...                       / 启用后，可让 AI 检索 Minecraft/模组/崩溃知识库
ai.settings.kb.source_mode                = Source                    / 知识库来源
ai.settings.kb.source_mode.remote         = Remote (server)           / 远程服务器
ai.settings.kb.source_mode.local          = Local index               / 本地索引
ai.settings.kb.embedding_model            = Embedding Model           / 嵌入模型
ai.settings.kb.embedding_model.desc       = Model used to embed queries / 用于将检索查询编码为向量的模型
ai.settings.kb.embedding_model.server_managed = Managed by the server / 远程模式下由服务器管理，无需选择
ai.settings.kb.endpoint                   = Endpoint                  / 服务地址
ai.settings.kb.local_path                 = Index directory           / 索引目录
ai.settings.kb.top_k                      = Results returned          / 返回结果数
ai.settings.kb.top_k.desc / .unit         = ...                       / 最终注入模型的知识条数 / 条
ai.settings.kb.fusion_top_k               = Fusion results            / 融合检索数
ai.settings.kb.validation.embedding_required = ...                    / 本地模式必须选择一个嵌入模型
ai.settings.kb.validation.endpoint_required  = ...                    / 请填写服务地址
ai.settings.kb.validation.dim_mismatch    = ...                       / 索引维度与所选模型不一致，请用该模型重建索引
```
Other locales (`_ja/_ru/_es/_ar/_uk/_lzh`) fall back to base silently — optional. Never edit `HMCL/bin/**` or `HMCL/build/**`.

### 1E. Validation (soft-clamp style, matching the codebase)

Follow HMCL-AE's existing "defensive setter + dialog-level check" posture (no schema validation framework):
- `AiKbConfig` setters clamp: `topK = Math.max(1, Math.min(20, v))`, `fusionTopK` similar, `endpoint`/`localIndexPath` trimmed.
- Tab-level (non-blocking, shown as an inline warning `Label` under the relevant card, refreshed via `invalidateTab`):
  - `enabled && sourceMode==LOCAL_INDEX && resolveEmbeddingModel(settings)==null` → `kb.validation.embedding_required`.
  - `enabled && sourceMode==REMOTE_HTTP && endpoint.isBlank()` → `kb.validation.endpoint_required`.
  - LOCAL_INDEX dim check deferred to Phase 4 (needs `EmbeddingModel.dimension()` vs `vectors.npy` shape) → `kb.validation.dim_mismatch`.
- The enable toggle stays flippable even when invalid (so the user can configure in any order), but the **tool is only registered when the config is both enabled AND valid** (Phase 3 binds to a derived observable, mirroring the `web_search` compound bind).

### Phase 1 Definition of Done (provably not a stub)
1. Add/edit-model dialog shows an **Embedding** checkbox; toggling it on `text-embedding-3-small`, saving, reopening HMCL-AE shows it still checked (round-trips through `ai-settings.json`).
2. `ModelLibrary.find("text-embedding-3-small")` auto-fills the Embedding checkbox from the backfilled JSON.
3. New **Knowledge Base** nav entry appears under Services; tab renders all four cards.
4. Embedding-model picker lists **only** models with `supportsEmbedding==true` across all providers, formatted `Provider · Alias`; selecting one persists `embeddingModelRef` to `ai-kb-config.json` and survives restart.
5. top-k / fusion / endpoint / mode all persist and reload; clamps enforced (try setting top-k via edited JSON to 999 → loads as 20).
6. Inline validation warning appears/clears correctly as you flip mode/selection.
7. Tests: extend `AiSettingsTest` for `supportsEmbedding` round-trip; new `AiKbConfigTest` for JSON round-trip + clamps + `resolveEmbeddingModel` stale-ref tolerance; extend `ModelLibraryTest` if JSON backfilled. `./gradlew :HMCLAI:test` green. No retrieval exists yet — that's expected and correct for this phase.

---

## Phase 2 — Embedding runtime provider (make `supportsEmbedding` behavioral)

Mirrors AstrBot's `EmbeddingProvider` base + `openai_embedding_source.py`. HMCL-AE already has `dev.langchain4j.model.openai.OpenAiEmbeddingModel` on the classpath (`langchain4j-open-ai`), so this is thin.

**File — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/langchain4j/LangChain4jModelFactory.java`**: add `buildEmbeddingModel(LlmConfig)` next to `buildChatModel`/`buildStreamingChatModel` (100–174):
```java
public EmbeddingModel buildEmbeddingModel(LlmConfig config) {
    String baseUrl = extractBaseUrl(config.getEndpoint());   // reuse: strips /chat/completions → base; embeddings path /v1/embeddings is derived by lc4j
    return OpenAiEmbeddingModel.builder()
        .baseUrl(baseUrl)
        .apiKey(config.getApiKey())
        .modelName(config.getModel())
        .httpClientBuilder(proxyAwareHttpClientBuilder())    // reuse 87–91 (proxy 127.0.0.1:7890 idiom)
        .timeout(...)
        .build();
}
```
This is the direct analog of AstrBot's `OpenAIEmbeddingProvider.__init__` building `AsyncOpenAI(...).embeddings`. `_normalize_api_base` (AstrBot's `/v1` fix-up) maps to HMCL-AE's existing `AiEndpointNormalizer.normalize(raw, family)` + `extractBaseUrl` — no new normalizer needed; embedding endpoints reuse the OpenAI-completions family's endpoint minus the chat path.

**`LlmConfig` / `AiModelEntry`:** no new fields required for the OpenAI-compatible embeddings call — endpoint/apiKey/model already exist. (AstrBot's `embedding_dimensions` maps to HMCL-AE's `AiModelEntry.contextWindow`-style optional override; if a `dimensions` param is needed, add an optional `embeddingDimensions` int to `AiModelEntry` following the sentinel pattern and pass it via `.dimensions(...)`. Only add it if a target model requires it — YAGNI for text-embedding-3-*, which default fine.)

**Provider builder for LOCAL_INDEX consumption** — a thin `KbEmbedder`:
**New file — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/kb/KbEmbedder.java`**: given a resolved `AiModelEntry`+profile, build an `LlmConfig` (reuse `ChatAgentFactory.buildConfig` path / `resolveEffectiveModelConfig`), call `factory.buildEmbeddingModel(cfg)`, expose `float[] embed(String query)` and `int dimension()`. This is AstrBot's `EmbeddingProvider.get_embedding` + `get_dim`.

### Phase 2 DoD
1. Unit/integration test (network, `@EnabledIfEnvironmentVariable` guarded) `KbEmbedderTest`: with a real OpenAI-compatible `/v1/embeddings` (via proxy), `embed("Creeper")` returns a non-empty `float[]` of `dimension()` length; two calls on same text are stable.
2. `buildEmbeddingModel` honors the proxy builder (assert it routes through 127.0.0.1:7890 config path — reuse existing factory test scaffolding).
3. No consumer yet is fine; the API is exercised by its own test. Not a stub because a real vector comes back.

---

## Phase 3 — Retrieval wiring: gated `kb_search` tool (REMOTE_HTTP)

**Recommendation: dedicated agentic tool, not prompt injection.** Rationale, specific to this codebase and mirroring AstrBot's `kb_agentic_mode=True`:
- The whole agent is a tool-loop by design (`AiPromptBuilder.java:46`, "golden rule: prefer a dedicated tool," `:76`). A `kb_search` tool is idiomatic; the model queries only when it judges KB is relevant, not on every "delete this instance" turn.
- The dormant memory-recall path (`AiPromptBuilder.recallMemoryBlock`, `:751–781`) injects **unconditionally every turn** via `listAll()` (not query-conditioned) and is force-disabled (`AiSettings.isAutoRecallMemory()` returns `false`, `:894`). Reviving it for a 170 MB corpus is the wrong shape — token waste + fires before relevance reasoning. Do **not** use it.
- Hot-toggle without restart is already solved by `ToggleToolsBinder`.

AstrBot's Mode A (direct injection into `extra_user_content_parts`) is the fallback; we expose it later only if users want zero-round-trip prefetch. v1 = Mode B (tool).

**New file — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/kb/KbSearchTool.java`** implementing `ToolSpec` (copy `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/search/WebSearchTool.java` structure):
- `getName()` → `"kb_search"`.
- `getDescription()` → adapt AstrBot's `astr_kb_search` description: *"Query the Minecraft knowledge base for facts, mechanics, mod/config details, or crash-log knowledge. Send a concise keyword query. Cite sources."*
- Params schema: `{ "query": { type: string, description: "concise keyword query" } }`, required `["query"]` (verbatim shape from AstrBot's tool).
- `getPermission()` → `ToolPermission.EXTERNAL_NETWORK`; `getMaxPermission()` same; `getSource()` → reuse `ToolSource.SEARCH` (or add `ToolSource.KB` — see gaps).
- `execute(Map)`:
  - Defense-in-depth enable re-check (mirror `WebSearchTool.java:106`).
  - Branch on `kbConfig.getSourceMode()`:
    - **REMOTE_HTTP:** proxy-aware `HttpClient` (idiom `LangChain4jModelFactory.java:87`) GET/POST `<endpoint>/tools/searchHybrid?query=…&top_k=…` (see Phase 4 for route), parse JSON chunks, format via a shared `KbContextFormatter` (below), **fence results as untrusted** exactly like `WebSearchTool.java:140`.
    - **LOCAL_INDEX:** call `KbEmbedder.embed(query)` (Phase 2) → cosine scan of `localIndexPath/vectors.npy` + doc store → RRF with a local FTS pass (Phase 4). Same formatter + fencing.
  - Return `ToolResult` text, or `"No relevant knowledge found."` on empty (AstrBot parity).

**New file — `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/kb/KbContextFormatter.java`**: port AstrBot's `_format_context()` (`kb_mgr.py`):
```
以下是相关的知识库内容，请参考这些信息回答用户的问题：

【知识 1】
来源: {kb_name} / {doc_name}
内容: {chunk}
相关度: {score:.2f}
...
```
(Localize the header via i18n; keep the source/score structure — it teaches the model to cite.)

**Registration — `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/AIMainPage.java` `registerTools()` (870–992)**, near the SearchTool registration (~930):
```java
// KB enabled AND config valid (mirrors the web_search compound derived observable at 912–920)
ObservableValue<Boolean> kbReady = Bindings.createBooleanBinding(
    () -> kbConfig.isEnabled() && kbConfig.isValid(aiSettings),   // isValid: endpoint set OR (local + embedding resolvable)
    kbConfig.enabledProperty(), kbConfig.sourceModeProperty(),
    kbConfig.endpointProperty(), kbConfig.embeddingModelRefProperty());
ToggleToolsBinder.bind(kbReady, toolRegistry, new KbSearchTool(kbConfig, aiSettings));
```
Hot-toggle: flip the setting → tool registers/unregisters with no restart (same as `ocr_image`/`web_fetch`).

**Prompt doc — `HMCLAI/.../ai/AiPromptBuilder.java` `buildVolatileSuffix`** (after the web block, ~481): add an `isToolRegistered("kb_search")`-gated block (mirror `:467–481`) telling the model to consult `kb_search` for Minecraft/mod/crash questions and to cite `来源`. Keep it **volatile** (not in the cached `TOOLS_GUIDE`), exactly like `web_search`, because registration changes at runtime.

### Phase 3 DoD
1. Enabling KB (REMOTE_HTTP, valid endpoint) makes `toolRegistry.list()` contain `kb_search`; disabling removes it — no restart. `buildVolatileSuffix` documents it **only** while registered (assert both states).
2. Live chat: ask "掉落物多久消失?" → model calls `kb_search` → tool hits FastAPI → formatted, source-cited, fenced-as-untrusted context returns → model answers citing 来源. Verified against the running `agentexperience.online` server (after Phase 4 route exists) or a local `python serve.py`.
3. With KB disabled, the model has no `kb_search` and the prompt never mentions it.
4. Malformed/500 server response → tool returns a graceful "No relevant knowledge found." not a crash; network-off → same (parity with web tools).
5. Test: `KbSearchToolTest` against a stub HTTP server asserting query→formatted-output and the untrusted fence.

---

## Phase 4 — Server dense wiring + LOCAL_INDEX

**Server (repo `Code/minecraft-knowledge-base`, `kb_server/`):** the dense index is currently unused over HTTP — `server.py:84–130` `/tools/searchWiki` calls `db.search(...)` (FTS only); `hybrid.py:HybridSearcher.search()` (`:65`) is not wired to any route. Add **`/tools/searchHybrid`** (or swap `server.py:93`) calling `HybridSearcher.search(query, top_k, fusion_top_k)`. Query embedding then runs server-side in `hybrid.py:52` (`self._model_().embed(["query: " + query])`) — no app-side embedding, no vector-space mismatch. This is the single change that makes REMOTE_HTTP exercise the 13 MB dense index. (Point `KbSearchTool` REMOTE branch at this route.)

**LOCAL_INDEX in-app retriever — new `HMCLAI/.../ai/kb/LocalKbRetriever.java`:** load `vectors.npy`/`doc_ids.npy` + doc store from `localIndexPath`, embed query via `KbEmbedder` (Phase 2), brute-force cosine (top `fusionTopK`), optional local FTS over a bundled SQLite, RRF (port `kb_server/hybrid.py` RRF, `k=60`), return top `topK`. **Hard constraint, surfaced in UI:** the index at `localIndexPath` MUST have been built with the model in `embeddingModelRef` — enforce `EmbeddingModel.dimension() == vectors.shape[1]`, else refuse with `kb.validation.dim_mismatch`. This is where the embedding picker is fully load-bearing and AstrBot-faithful (app embeds query with the same provider that built the index).

### Phase 4 DoD
1. `curl <endpoint>/tools/searchHybrid?query=creeper&top_k=5` returns dense+FTS+RRF-fused results (dense index provably hit — compare vs FTS-only `/searchWiki`).
2. REMOTE_HTTP end-to-end recall improves on a query where lexical fails but semantic succeeds (e.g. a paraphrase).
3. LOCAL_INDEX: point at a directory whose index was rebuilt with the selected OpenAI model → `kb_search` returns cosine-ranked chunks; point at the fastembed index with an OpenAI model selected → dim-mismatch refusal (not silent garbage). Both behaviors tested.

---

## Cross-cutting summary

**Persistence:** embedding capability → automatic via `AiProviderProfile.models` (no `PersistedData` change). All KB settings → `ai-kb-config.json` via `AiKbConfig` + `saveKbConfig()` (mirror `saveSearchConfig` atomic write). No API key on KB endpoint today (none needed); if added later, Base64 on disk like `AiSettings.encodeProfilesForSave`.

**i18n:** every new string has a base `I18N.properties` key (mandatory) + `I18N_zh_CN.properties`; keys enumerated in §1D. `@PropertyKey` validates base at compile.

**Validation:** setter clamps + tab-level inline warnings + tool-registration gate on `isValid`. No hard blocking of the enable toggle (configure-in-any-order UX).

---

## Research gaps / follow-ups (a senior dev should confirm these before Phase 3/4)

1. **Exact FastAPI response JSON schema** of `/tools/searchWiki` (and the new `/searchHybrid`) — field names for chunk text / doc name / score — to write `KbContextFormatter`/`KbSearchTool` parsing. `hmcl-rag-wiring` cited `server.py:84–130` but not the response body shape. *Action: read `kb_server/server.py` + `hybrid.py` return dicts.*
2. **`ToolSource` enum** — confirm whether to add a `KB` member or reuse `SEARCH`; and confirm `ToolPermission.EXTERNAL_NETWORK` is the exact constant. *Action: grep `ToolSource`/`ToolPermission` enums in `HMCLAI/.../ai/tools/`.*
3. **`ToggleToolsBinder.bind` signature** — confirm it accepts `ObservableValue<Boolean>` (for the compound `kbReady` binding) vs only `BooleanProperty`. `web_search`'s compound bind (`AIMainPage.java:912–920`) implies the former; verify.
4. **`toggleRow`/`sliderRow` save hook** — confirm whether they hardcode `saveAiSettings()`; if so, add overloads accepting a `Runnable onChanged` so KB rows persist to `ai-kb-config.json` instead. (Or build the rows inline like `buildTitleNamingModelRow`.)
5. **`AiSearchConfig` exact field/JSON layout + `AIMainPage` load site** — to clone precisely for `AiKbConfig`. Path confirmed (`HMCLAI/.../ai/search/AiSearchConfig.java`); read it before writing the mirror.
6. **`extractBaseUrl` behavior for `/v1/embeddings`** — confirm lc4j `OpenAiEmbeddingModel` appends `/embeddings` to the stripped base (i.e. `extractBaseUrl` yields the right `/v1` root). AstrBot's `_normalize_api_base` had to special-case this (Issue #6855); verify HMCL-AE's normalizer doesn't double-append.
7. **Nav SVG icon** — pick an existing `SVG.*` constant for the KB entry (no new asset). Confirm one that reads as "book/knowledge."
8. **Optional `embeddingDimensions` on `AiModelEntry`** — only if a target embedding model needs an explicit `dimensions` param (AstrBot passes it for SiliconFlow/Qwen). Confirm the intended models don't need it before adding.

---

## Appendix — file-by-file change table

| Phase | File (absolute) | Change |
|---|---|---|
| 1A | `...\HMCLAI\...\ai\AiModelEntry.java` | + `supportsEmbedding` field/getter/setter |
| 1A | `...\HMCLAI\...\ai\ModelLibrary.java` | + `supportsEmbedding` in `ModelInfo` |
| 1A | `...\HMCLAI\...\resources\assets\ai\model-library.json` | backfill 26 embedding ids; `schemaVersion→3` |
| 1A,1C | `...\HMCL\...\ui\ai\AISettingsPage.java` | `capEmbeddingBox` in editModel; new `knowledgeBaseTab` + `buildKnowledgeBaseTab` + `buildKbEmbeddingModelRow` + `saveKbConfig` + path field |
| 1B | `...\HMCLAI\...\ai\kb\AiKbConfig.java` | **new** — config state (mirror `AiSearchConfig`) + `resolveEmbeddingModel` |
| 1B,3 | `...\HMCL\...\ui\ai\AIMainPage.java` | construct/load `AiKbConfig`; pass to `AISettingsPage`; register `kb_search` in `registerTools()` |
| 1D | `...\HMCL\...\lang\I18N.properties` + `I18N_zh_CN.properties` | new keys (§1D) |
| 2 | `...\HMCLAI\...\ai\langchain4j\LangChain4jModelFactory.java` | + `buildEmbeddingModel(LlmConfig)` |
| 2 | `...\HMCLAI\...\ai\kb\KbEmbedder.java` | **new** — `embed`/`dimension` (mirror `EmbeddingProvider`) |
| 3 | `...\HMCLAI\...\ai\kb\KbSearchTool.java` | **new** — `ToolSpec` (mirror `WebSearchTool`) |
| 3 | `...\HMCLAI\...\ai\kb\KbContextFormatter.java` | **new** — port AstrBot `_format_context` |
| 3 | `...\HMCLAI\...\ai\AiPromptBuilder.java` | + gated `kb_search` doc in `buildVolatileSuffix` |
| 4 | `Code\minecraft-knowledge-base\kb_server\server.py` | + `/tools/searchHybrid` route → `HybridSearcher.search` |
| 4 | `...\HMCLAI\...\ai\kb\LocalKbRetriever.java` | **new** — LOCAL_INDEX cosine+FTS+RRF |
| tests | `AiSettingsTest`, **`AiKbConfigTest`**, `ModelLibraryTest`, **`KbEmbedderTest`**, **`KbSearchToolTest`** | per-phase DoD |

**Bottom line:** build Phase 1 first (settings + embedding capability, fully persistent and validated), which is the user's stated priority and is a complete, testable increment on its own. The embedding picker is made load-bearing by filtering on the new `supportsEmbedding` flag (AstrBot's KB→embedding-provider linkage) and by driving the LOCAL_INDEX path; REMOTE_HTTP (Phase 3, the default) is the production RAG and needs only the one-line server route change in Phase 4 to exercise the existing dense index — sidestepping the fastembed vector-space trap entirely.