# CC 源码探查 Follow-up 报告：压缩层级核实 + 记忆/压缩概念分离

> 探查日期：2026-04-07
> 前置文档：`CC_Task_SourceStudy_MemoryAndContext_Report.md`

---

## 第一步：压缩层级核实

### 1.1 Feature-flagged 模块存在性确认

| 模块 | Feature Flag | 文件路径 | 在 Sourcemap 中 | 默认状态 |
|------|-------------|---------|-----------------|---------|
| Snip | `HISTORY_SNIP` | `services/compact/snipCompact.js` | ❌ 不存在 | **默认关闭**（ant-only） |
| Context Collapse | `CONTEXT_COLLAPSE` | `services/contextCollapse/index.js` | ❌ 不存在 | **默认关闭**（ant-only） |
| Reactive Compact | `REACTIVE_COMPACT` | `services/compact/reactiveCompact.js` | ❌ 不存在 | **默认关闭**（ant-only） |

**关键发现：** 这三个模块在源码中通过 `feature('XXX')` 按需加载，但 **sourcemap 不包含这些文件**——它们被 tree-shaking 从外部构建中排除了。这意味着：

1. **外部资料（博客）引用的是 Ant 内部版本**，包含完整的5层压缩
2. **公开版本只有 2 层**：Microcompact + Autocompact（及 Session Memory Compact）
3. **Feature flag 决定生产路径**：只有开启对应 flag 才会进入这些分支

**代码证据（query.ts:15-20, 115-117）：**

```typescript
const reactiveCompact = feature('REACTIVE_COMPACT')
  ? (require('./services/compact/reactiveCompact.js') as typeof import('./services/compact/reactiveCompact.js'))
  : null

const contextCollapse = feature('CONTEXT_COLLAPSE')
  ? (require('./services/contextCollapse/index.js') as typeof import('./services/contextCollapse/index.js'))
  : null

const snipModule = feature('HISTORY_SNIP')
  ? (require('./services/compact/snipCompact.js') as typeof import('./services/compact/snipCompact.js'))
  : null
```

### 1.2 各层级详细信息

基于 query.ts 中的调用顺序和已确认存在的模块：

| 层级 | 名称 | 触发条件 | 操作 | 是否调 LLM | 在 query.ts 中调用位置 |
|------|------|---------|------|-------------|----------------------|
| 1 | **Snip** | 每轮运行 | 剪裁旧 tool_use，保留结构 | 否（推测） | L401-410: `snipModule!.snipCompactIfNeeded()` |
| 2 | **Microcompact (Time-based)** | 距上次 assistant > N 分钟 | 清除旧 tool_result 内容标记 | **否** | L414-426: `deps.microcompact()` |
| 3 | **Microcompact (Cached)** | tool_result 数量 > 阈值 | cache_edits API 删除 | **否** | 同上（两种策略在 microcompact 内部） |
| 4 | **Context Collapse** | 折叠阈值触发 | 对中间对话做折叠摘要 | **部分**（推测调 LLM 生成折叠摘要） | L440-447: `contextCollapse.applyCollapsesIfNeeded()` |
| 5 | **Autocompact** | Token 超阈值 | 调 LLM 生成全量摘要 | **是** | L454-468: `deps.autocompact()` |
| 6 | **Session Memory Compact** | SM 已初始化 + 触发 | 用预提取记忆替代 LLM 摘要 | **部分**（提取时用） | 在 autocompact 内部调用 |
| 7 | **Reactive Compact** | API 返回 413 (PTL) | 应急压缩 | **是** | L1119-1166: `reactiveCompact.tryReactiveCompact()` |

### 1.3 Autocompact vs Legacy Compact 关系澄清

**结论：它们是同一个东西。**

上次报告中的 "Legacy Compact" 就是 Autocompact 的 LLM 摘要路径：

- `compact.ts` 中的 `compactConversation()` 是 Autocompact 的核心实现
- 当 Session Memory Compact 不可用时，fallback 到这个 "legacy" 路径
- 命名 "legacy" 是相对于新的 Session Memory Compact 而言的

**代码证据（autoCompact.ts:313-321）：**

```typescript
// autoCompactIfNeeded 内部
const compactionResult = await compactConversation(
  messages,
  toolUseContext,
  cacheSafeParams,
  true, // Suppress user questions for autocompact
  undefined, // No custom instructions for autocompact
  true, // isAutoCompact
  recompactionInfo,
)
```

### 1.4 渐进式降级的真实顺序

**代码证据（query.ts:396-468）：**

```typescript
// 1. Apply snip before microcompact
if (feature('HISTORY_SNIP')) {
  const snipResult = snipModule!.snipCompactIfNeeded(messagesForQuery)
  messagesForQuery = snipResult.messages
}

// 2. Apply microcompact before autocompact
const microcompactResult = await deps.microcompact(messagesForQuery, ...)

// 3. Context Collapse: runs BEFORE autocompact
if (feature('CONTEXT_COLLAPSE') && contextCollapse) {
  const collapseResult = await contextCollapse.applyCollapsesIfNeeded(...)
}

// 4. Autocompact (includes Session Memory Compact fallback)
const { compactionResult } = await deps.autocompact(...)
```

**实际顺序：**

```
Snip → Microcompact → Context Collapse → Autocompact
                                            ↓
                                   (内部选择)
                                            ↓
                              Session Memory Compact (优先)
                                            ↓
                              Legacy Compact (fallback)
```

**Reactive Compact 是独立的应急路径：**
- 只在 API 返回 413 (prompt_too_long) 时触发
- 不在主动压缩流程中，是**兜底机制**

### 1.5 断路器机制确认

**代码证据（autoCompact.ts:62-70）：**

```typescript
// Stop trying autocompact after this many consecutive failures.
const MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3

// Circuit breaker: stop retrying after N consecutive failures.
if (
  tracking?.consecutiveFailures !== undefined &&
  tracking.consecutiveFailures >= MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES
) {
  return { wasCompacted: false }
}
```

**确认：** 连续失败 3 次后，断路器触发，跳过后续 autocompact 尝试。

---

## 第二步：记忆系统独立架构图

### 2.1 关键发现：两套并行记忆系统

| 系统 | 目录 | 职责 | 触发时机 |
|------|------|------|---------|
| **Auto Memory** | `memdir/` | 自动记忆管理，持久化到项目目录 | 后台自动 + 模型主动写 |
| **Session Memory** | `services/SessionMemory/` | 会话级记忆，用于压缩 | 后台自动提取 |

**它们是两套独立系统，不是同一套东西的两个角度！**

### 2.2 Auto Memory（`memdir/`）架构

**核心文件：**
- `paths.ts` - 路径解析，启用判断
- `memdir.ts` - MEMORY.md 加载、截断
- `findRelevantMemories.ts` - 话题文件召回
- `memoryScan.ts` - 扫描记忆文件 frontmatter

**三层结构：**

| 温度 | 名称 | 加载时机 | 容量限制 | 召回机制 |
|------|------|---------|---------|---------|
| **热** | MEMORY.md | **每轮**都加载到 system prompt | 200行 / 25KB | 无需召回，始终在上下文 |
| **温** | 话题文件 (.md) | 按需召回 | 最多 **5 个** 文件 | **Sonnet 小模型选择** |
| **冷** | Transcript JSONL | Grep 搜索 | 无限制 | 模型用 Grep/Read 工具 |

**代码证据：**

**MEMORY.md 限制（memdir.ts:35-38）：**
```typescript
export const MAX_ENTRYPOINT_LINES = 200
export const MAX_ENTRYPOINT_BYTES = 25_000
```

**话题文件召回（findRelevantMemories.ts:18-24）：**
```typescript
const SELECT_MEMORIES_SYSTEM_PROMPT = `You are selecting memories that will be useful 
to Claude Code as it processes a user's query. Return a list of filenames for the 
memories that will clearly be useful (up to 5).`
```

**召回模型（findRelevantMemories.ts:98-99）：**
```typescript
const result = await sideQuery({
  model: getDefaultSonnetModel(),  // ← Sonnet 小模型
  ...
})
```

### 2.3 Session Memory（`services/SessionMemory/`）架构

**核心文件：**
- `sessionMemory.ts` - 后台自动提取
- `prompts.ts` - 提取提示词模板

**与 Auto Memory 的区别：**

| 维度 | Auto Memory | Session Memory |
|------|-------------|----------------|
| 目的 | 跨会话持久记忆 | 会话级工作记忆 |
| 存储位置 | `~/.claude/projects/{slug}/memory/` | `~/.claude/session-memory/{id}/notes.md` |
| 消费方 | 模型直接读 | 压缩系统消费 |
| 结构 | 三层热温冷 | 单层 Markdown |

### 2.4 记忆系统架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         记忆系统（常驻运行）                               │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                    Auto Memory (memdir/)                           │  │
│  │                                                                    │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │ 热: MEMORY.md                                            │    │  │
│  │   │   - 每轮加载到 system prompt                             │    │  │
│  │   │   - 限制: 200行 / 25KB                                   │    │  │
│  │   │   - 位置: ~/.claude/projects/{slug}/memory/MEMORY.md     │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  │                              │                                     │  │
│  │                              ▼                                     │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │ 温: 话题文件 (.md)                                       │    │  │
│  │   │   - 按需召回 (Sonnet 选择)                               │    │  │
│  │   │   - 最多 5 个文件                                        │    │  │
│  │   │   - 位置: ~/.claude/projects/{slug}/memory/*.md         │    │  │
│  │   │                                                          │    │  │
│  │   │   召回流程:                                              │    │  │
│  │   │   1. scanMemoryFiles() 扫描 frontmatter                 │    │  │
│  │   │   2. Sonnet 选择最相关的 5 个                            │    │  │
│  │   │   3. 作为 attachment 注入上下文                          │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  │                              │                                     │  │
│  │                              ▼                                     │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │ 冷: Transcript JSONL                                     │    │  │
│  │   │   - 模型用 Grep/Read 搜索                                │    │  │
│  │   │   - 无限制                                               │    │  │
│  │   │   - 位置: ~/.claude/projects/{slug}/{sessionId}.jsonl   │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                 Session Memory (services/SessionMemory/)           │  │
│  │                                                                    │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │ 会话级记忆 (notes.md)                                    │    │  │
│  │   │   - 后台自动提取 (postSamplingHooks)                     │    │  │
│  │   │   - 结构化 Markdown 模板                                 │    │  │
│  │   │   - 位置: ~/.claude/session-memory/{id}/notes.md        │    │  │
│  │   │   - 用途: 被压缩系统消费                                 │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                 后台提取服务                                        │  │
│  │                                                                    │  │
│  │   extractMemories (services/extractMemories/)                     │  │
│  │     - 触发: stopHooks (对话结束时)                                 │  │
│  │     - 运行: forked agent (共享 prompt cache)                       │  │
│  │     - 输出: 写入 Auto Memory 目录                                  │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 第三步：压缩系统独立架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         压缩系统（触发式运行）                             │
│                                                                          │
│   触发入口: query.ts 主循环                                              │
│                                                                          │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Layer 1: Snip (feature: HISTORY_SNIP) [默认关闭]                   │ │
│   │   触发: 每轮运行                                                    │ │
│   │   操作: 剪裁旧 tool_use，只保留结构                                 │ │
│   │   LLM: 否 ❌                                                        │ │
│   │   调用: query.ts:403 snipModule!.snipCompactIfNeeded()             │ │
│   └────────────────────────────────────────────────────────────────────┘ │
│                              │                                           │
│                              ▼                                           │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Layer 2: Microcompact                                              │ │
│   │                                                                    │ │
│   │   2a. Time-based:                                                  │ │
│   │       触发: 距上次 assistant > N 分钟                               │ │
│   │       操作: [Old tool result content cleared]                      │ │
│   │       LLM: 否 ❌                                                    │ │
│   │                                                                    │ │
│   │   2b. Cached: (feature: CACHED_MICROCOMPACT)                      │ │
│   │       触发: tool_result 数量 > 阈值                                │ │
│   │       操作: cache_edits API 删除                                   │ │
│   │       LLM: 否 ❌                                                    │ │
│   │                                                                    │ │
│   │   调用: query.ts:414 deps.microcompact()                          │ │
│   └────────────────────────────────────────────────────────────────────┘ │
│                              │                                           │
│                              ▼                                           │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Layer 3: Context Collapse (feature: CONTEXT_COLLAPSE) [默认关闭]   │ │
│   │   触发: 折叠阈值触发                                                │ │
│   │   操作: 对中间对话做折叠摘要                                        │ │
│   │   LLM: 部分 ⚠️ (生成折叠摘要)                                       │ │
│   │   特点: 保留粒度，不是全量摘要                                       │ │
│   │   调用: query.ts:441 contextCollapse.applyCollapsesIfNeeded()      │ │
│   └────────────────────────────────────────────────────────────────────┘ │
│                              │                                           │
│                              ▼                                           │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Layer 4: Autocompact                                               │ │
│   │   触发: Token 超阈值 (约 200k - 13k buffer = 187k)                │ │
│   │   断路器: MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3                │ │
│   │                                                                    │ │
│   │   ┌──────────────────────────────────────────────────────────────┐ │ │
│   │   │ 4a. Session Memory Compact (优先)                           │ │ │
│   │   │     条件: SM 已初始化 + SM 非空                              │ │ │
│   │   │     操作: 用预提取的 notes.md 作为摘要                       │ │ │
│   │   │     LLM: 部分 ⚠️ (提取时用，压缩时不用)                       │ │ │
│   │   └──────────────────────────────────────────────────────────────┘ │ │
│   │                        │ fallback                                  │ │
│   │                        ▼                                           │ │
│   │   ┌──────────────────────────────────────────────────────────────┐ │ │
│   │   │ 4b. Legacy Compact                                          │ │ │
│   │   │     操作: 调 LLM 生成全量摘要                                 │ │ │
│   │   │     LLM: 是 ✅                                                │ │ │
│   │   └──────────────────────────────────────────────────────────────┘ │ │
│   │                                                                    │ │
│   │   调用: query.ts:454 deps.autocompact()                           │ │
│   └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Layer 5: Reactive Compact (feature: REACTIVE_COMPACT) [默认关闭]  │ │
│   │   触发: API 返回 413 (prompt_too_long) 或媒体大小错误              │ │
│   │   操作: 应急压缩，剥离图片后重试                                   │ │
│   │   LLM: 是 ✅                                                        │ │
│   │   特点: 独立应急路径，不在主动压缩流程中                            │ │
│   │   调用: query.ts:1120 reactiveCompact.tryReactiveCompact()        │ │
│   └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│   输出格式:                                                              │
│   ┌────────────────────────────────────────────────────────────────────┐ │
│   │ Post-Compact Messages                                              │ │
│   │   [Compact Boundary] → system message (标记边界)                   │ │
│   │   [Summary] → user message (含 transcript 路径)                    │ │
│   │   [Messages to Keep] → 保留的近期消息                               │ │
│   │   [Attachments] → 文件/技能/计划等                                 │ │
│   └────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 第四步：两个系统的耦合点图

```
┌─────────────────────────────────────┐         ┌─────────────────────────────────────┐
│      记忆系统（常驻）               │         │      压缩系统（触发式）              │
│                                     │         │                                     │
│  ┌───────────────────────────────┐  │         │  ┌───────────────────────────────┐  │
│  │ Auto Memory                   │  │         │  │ Layer 1: Snip                 │  │
│  │  - MEMORY.md (热)             │  │         │  │ Layer 2: Microcompact         │  │
│  │  - 话题文件 (温)              │  │         │  │ Layer 3: Context Collapse     │  │
│  │  - Transcript JSONL (冷)      │  │         │  │ Layer 4: Autocompact          │  │
│  └───────────────────────────────┘  │         │  │ Layer 5: Reactive Compact     │  │
│                                     │         │  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │         │                                     │
│  │ Session Memory                │◄─┼────①───▶│  Session Memory Compact (Layer 4a)│
│  │  - notes.md                   │  │         │                                     │
│  │  - 后台自动提取               │  │         │                                     │
│  └───────────────────────────────┘  │         │                                     │
│                                     │         │                                     │
│  ┌───────────────────────────────┐  │         │  ┌───────────────────────────────┐  │
│  │ Transcript JSONL              │◄─┼────②───▶│  Compact Summary Message        │  │
│  │  - 完整对话历史               │  │         │  (包含 transcript 路径)          │  │
│  └───────────────────────────────┘  │         │  └───────────────────────────────┘  │
│                                     │         │                                     │
└─────────────────────────────────────┘         └─────────────────────────────────────┘

════════════════════════════════════════════════════════════════════════════════════

耦合点 ①: Session Memory 被 Session Memory Compact 消费
──────────────────────────────────────────────────────

  消费时机: 
    - Autocompact 触发时，优先检查 Session Memory 是否可用
    - 代码: sessionMemoryCompact.ts:514 trySessionMemoryCompaction()

  消费形式:
    - 读取 notes.md 内容作为摘要
    - 替代 LLM 生成的摘要（节省 API 调用）
    
  代码证据 (sessionMemoryCompact.ts:437-503):
    ```typescript
    const sessionMemory = await getSessionMemoryContent()
    
    // 直接使用 session memory 作为摘要
    let summaryContent = getCompactUserSummaryMessage(
      truncatedContent,
      true,
      transcriptPath,
      true,
    )
    ```

════════════════════════════════════════════════════════════════════════════════════

耦合点 ②: Compact Summary 引用 transcript 路径
────────────────────────────────────────────────

  提示文本 (prompt.ts:345-356):
    ```typescript
    let baseSummary = `This session is being continued from a previous 
    conversation that ran out of context. The summary below covers the 
    earlier portion.
    
    ${formattedSummary}
    
    If you need specific details from before compaction (like exact code 
    snippets, error messages, or content you generated), read the full 
    transcript at: ${transcriptPath}
    
    Recent messages are preserved verbatim.`
    ```

  模型可用的恢复工具:
    - Read 工具: 读取 transcript JSONL
    - Grep 工具: 搜索 transcript 内容
    - 无专用 "recall" 工具

  其他记忆资源是否被告知:
    - ❌ MEMORY.md 路径未提及
    - ❌ 话题文件路径未提及
    - ✅ 只有 transcript 路径被明确告知

════════════════════════════════════════════════════════════════════════════════════

生命周期对比:
────────────

  ┌─────────────────┬─────────────────┬───────────────────────────────────────┐
  │                 │ 记忆系统         │ 压缩系统                               │
  ├─────────────────┼─────────────────┼───────────────────────────────────────┤
  │ 运行模式        │ 常驻             │ 触发式                                 │
  │ 触发时机        │ 每轮 + 后台      │ Token 超阈值 / API 错误                │
  │ 共享 state      │ postSamplingHooks│ autoCompactTracking                   │
  │ 共享 hooks      │ extractMemories  │ preCompactHooks / postCompactHooks    │
  │ 持久化          │ 磁盘文件         │ messages + transcript                 │
  └─────────────────┴─────────────────┴───────────────────────────────────────┘

```

---

## 对上次报告的修正清单

| 原内容 | 修正 |
|-------|------|
| "4层压缩" | 实际是 **5层压缩**（加上 Snip），但其中 3 层默认关闭 |
| 未提及 Snip、Context Collapse、Reactive Compact | 已补齐，确认它们是 feature-flagged 模块 |
| "Legacy Compact" 命名可能混淆 | 澄清：Legacy Compact = Autocompact 的 LLM 摘要路径 |
| Session Memory 和 memdir 混在一起 | **核心修正**：它们是**两套独立系统** |
| 未提及话题文件召回机制 | 补充：Sonnet 小模型选择，最多 5 个文件 |
| 未提及 MEMORY.md 硬限制 | 补充：200 行 / 25 KB |
| 未提及断路器机制 | 确认：MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3 |

---

## 核心洞察

### 1. 为什么 CC 用 feature flag 隔离这三个模块？

**分析：**

| 模块 | 隔离原因 |
|------|---------|
| **Snip** | 剪裁策略激进，可能影响模型理解上下文 |
| **Context Collapse** | 折叠摘要可能丢失细节，仍在迭代 |
| **Reactive Compact** | 应急路径，只在 PTL 时需要，非生产路径 |

**设计哲学：**
- 核心功能（Microcompact + Autocompact）始终开启
- 实验性功能用 feature flag 隔离，降低风险
- 允许灰度发布和 A/B 测试

### 2. 被漏掉的三层各自对应什么场景？

| 场景 | 对应层级 | 设计思路 |
|------|---------|---------|
| **日常优化** | Snip + Microcompact | 无 LLM 成本，纯代码优化 |
| **中间态保留** | Context Collapse | 折叠而非完全摘要，保留粒度 |
| **应急兜底** | Reactive Compact | API 错误时才触发，不是主动路径 |

**对 ragent 的启示：**
- Time-based Microcompact 可以直接迁移
- Context Collapse 是"中间态"设计，值得借鉴
- Reactive Compact 是兜底机制，应该实现

### 3. 记忆和压缩作为两套子系统的设计哲学

**记忆系统：回答"存什么"**
- 生命周期：整个会话
- 触发：常驻运行
- 目标：长期保存，按需取回

**压缩系统：回答"怎么省"**
- 生命周期：阈值触发的瞬间
- 触发：按需运行
- 目标：瘦身上下文，保持运行

**分工逻辑：**
- 记忆是"存储侧"，压缩是"运行时侧"
- 记忆决定"什么值得保留"，压缩决定"当前窗口留什么"
- 两者在 Session Memory 处耦合：记忆产物被压缩消费

---

## 对 ragent 的建议更新

### 优先级调整

| 原建议 | 调整后建议 |
|-------|-----------|
| P0: Time-based Microcompact | 保持 P0（零成本，可迁移） |
| P0: Transcript 持久化 | 保持 P0 |
| P1: Session Memory | 拆分为两个系统：Auto Memory + Session Memory |
| 新增: Context Collapse 模式 | P1（中间态折叠，值得借鉴） |
| 新增: Reactive Compact 兜底 | P1（API 错误时的应急路径） |

### 架构建议

1. **记忆和压缩分开设计**
   - 记忆：MEMORY.md + 话题文件 + transcript
   - 压缩：Microcompact → Autocompact → Reactive Compact

2. **增加 feature flag 机制**
   - 允许灰度发布新压缩策略
   - 核心功能始终开启，实验功能可关闭

3. **实现三层记忆**
   - 热：MEMORY.md（每次加载，有硬限制）
   - 温：话题文件（Sonnet 召回，最多 5 个）
   - 冷：Transcript（Grep 搜索）

---

## 参考文件路径

### 压缩系统
- `claude-code-sourcemap/restored-src/src/query.ts` - 压缩流程编排
- `claude-code-sourcemap/restored-src/src/services/compact/autoCompact.ts`
- `claude-code-sourcemap/restored-src/src/services/compact/microCompact.ts`
- `claude-code-sourcemap/restored-src/src/services/compact/sessionMemoryCompact.ts`

### 记忆系统 - Auto Memory
- `claude-code-sourcemap/restored-src/src/memdir/memdir.ts`
- `claude-code-sourcemap/restored-src/src/memdir/findRelevantMemories.ts`
- `claude-code-sourcemap/restored-src/src/memdir/paths.ts`
- `claude-code-sourcemap/restored-src/src/memdir/memoryScan.ts`

### 记忆系统 - Session Memory
- `claude-code-sourcemap/restored-src/src/services/SessionMemory/sessionMemory.ts`
- `claude-code-sourcemap/restored-src/src/services/SessionMemory/prompts.ts`

### 记忆系统 - 提取
- `claude-code-sourcemap/restored-src/src/services/extractMemories/extractMemories.ts`