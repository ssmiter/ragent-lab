# CC 源码探查报告：记忆与上下文管理机制

> 探查日期：2026-04-07
> 探查目标：理解 Claude Code 的记忆与上下文管理机制，验证三个核心猜测，评估对 ragent 的迁移价值

## 背景

### 我们走到哪了

```
实验0-1.5: Agent Loop 基础验证           → ✅ 骨架可用，模型决策合理
实验2:     多工具编排                     → ✅ 按需重写优于无脑重写
实验3:     跨知识库综合                   → ✅ 质变发现：研究助理级能力
本次:      CC 源码深入（记忆/上下文管理）  → 🔬 带着猜测去验证
```

### 为什么现在做这个

我们的实验有一个隐含条件——**对话都很短**（最长 Q7 也就 3 轮），上下文窗口完全够用。但真实场景中对话可能持续几十轮，工具输出可能非常长。问题从"怎么给模型好的信息"变成"**怎么在信息过载时保持信息质量**"。

---

## 第一步：核心文件清单

### 压缩相关核心文件

| 文件路径 | 职责 |
|---------|------|
| `commands/compact/compact.ts` | 压缩命令入口，协调各压缩路径 |
| `services/compact/compact.ts` | **核心压缩逻辑**，调用 LLM 生成摘要 |
| `services/compact/microCompact.ts` | **微压缩**，不调 LLM，清除旧工具结果 |
| `services/compact/sessionMemoryCompact.ts` | **Session Memory 压缩**，使用预提取的记忆 |
| `services/compact/autoCompact.ts` | 自动压缩触发机制和阈值管理 |
| `services/compact/prompt.ts` | 压缩提示词模板 |

### 记忆相关核心文件

| 文件路径 | 职责 |
|---------|------|
| `services/SessionMemory/sessionMemory.ts` | **Session Memory 后台自动提取** |
| `services/SessionMemory/prompts.ts` | Session Memory 提示词和模板 |
| `commands/memory/memory.tsx` | `/memory` 命令，编辑 CLAUDE.md 等 |
| `utils/claudemd.ts` | CLAUDE.md 加载、缓存和优先级管理 |

### 消息与存储

| 文件路径 | 职责 |
|---------|------|
| `utils/messages.ts` | 消息创建、compact boundary 标记 |
| `utils/sessionStorage.ts` | Transcript JSONL 持久化存储 |

---

## 第二步：三个核心问题的回答

### 问题1：CC 有没有让模型主动管理工作记忆的机制？

**是否存在记忆工具：否**

CC **没有**让模型主动"记笔记"的工具（如 `note_tool` / `remember` / `recall`）。

**工作记忆更新机制：**

1. **Session Memory 是后台自动提取**
   - 通过 `postSamplingHooks` 在对话间隙自动触发
   - 使用 `runForkedAgent` 在独立子智能体中运行，不占用主对话上下文
   - 触发条件：token 阈值 + tool call 阈值 或 自然对话断点

   ```typescript
   // sessionMemory.ts:272-350
   const extractSessionMemory = sequential(async function (context) {
     // 只在主 REPL 线程运行
     if (querySource !== 'repl_main_thread') return

     // 检查 gate 和阈值
     if (!shouldExtractMemory(messages)) return

     // 使用 forked agent 在后台提取
     await runForkedAgent({
       promptMessages: [createUserMessage({ content: userPrompt })],
       querySource: 'session_memory',
       forkLabel: 'session_memory',
     })
   })
   ```

2. **记忆存储在磁盘文件**
   - Session Memory: `~/.claude/session-memory/{sessionId}/notes.md`
   - CLAUDE.md 体系: 用户/项目/本地三级记忆文件
   - 通过 `/memory` 命令可编辑，但模型不主动调用

3. **记忆更新是确定性的代码逻辑**
   - 提取时机由 token 计数和 tool call 计数决定（代码自动判断）
   - 提取内容由子智能体的 LLM 决定（但这是"委托"而非"主动工具"）

**结论：** 模型**不能主动**管理工作记忆。记忆提取是后台自动的确定性流程，模型在主对话中感知不到这个过程。

---

### 问题2：CC 的上下文压缩哪些层是纯代码逻辑，哪些层有模型参与？

| 层级 | 名称 | 触发条件 | 操作 | 是否涉及 LLM | 信息去向 |
|------|------|---------|------|-------------|---------|
| 1 | Time-based Microcompact | 距上次 assistant 消息超过阈值（分钟） | 内容清除旧 tool_result | **否** | 替换为 `[Old tool result content cleared]` |
| 2 | Cached Microcompact | tool_result 数量超过阈值 | 通过 cache_edits API 删除 | **否** | 从缓存中移除，本地消息不变 |
| 3 | Session Memory Compact | 自动压缩触发 + SM 已初始化 | 用预提取的记忆替代 LLM 摘要 | **部分**（提取时用了 LLM，压缩时不用） | 摘要注入 context，原文在 transcript |
| 4 | Legacy Compact | 上述路径都不可用 | **调用 LLM 生成摘要** | **是** | 摘要注入 context，原文在 transcript |

**详细分析：**

#### Layer 1: Time-based Microcompact (microCompact.ts:412-530)

```typescript
// 纯代码逻辑，不调 LLM
function maybeTimeBasedMicrocompact(messages, querySource) {
  const trigger = evaluateTimeBasedTrigger(messages, querySource)
  if (!trigger) return null

  // 直接替换内容，不涉及 LLM
  const result = messages.map(message => {
    if (block.type === 'tool_result' && clearSet.has(block.tool_use_id)) {
      return { ...block, content: TIME_BASED_MC_CLEARED_MESSAGE }
    }
  })
}
```

#### Layer 2: Cached Microcompact (microCompact.ts:253-399)

```typescript
// 通过 cache_edits API 删除，不修改本地消息
async function cachedMicrocompactPath(messages, querySource) {
  const toolsToDelete = mod.getToolResultsToDelete(state)
  const cacheEdits = mod.createCacheEditsBlock(state, toolsToDelete)
  pendingCacheEdits = cacheEdits  // 在 API 层处理

  return { messages }  // 本地消息不变
}
```

#### Layer 3: Session Memory Compact (sessionMemoryCompact.ts:514-630)

```typescript
// 使用预提取的 session memory，压缩时不调 LLM
export async function trySessionMemoryCompaction(messages, agentId) {
  const sessionMemory = await getSessionMemoryContent()
  if (!sessionMemory || await isSessionMemoryEmpty(sessionMemory)) return null

  // 直接使用磁盘上的 session memory 作为摘要
  const compactionResult = createCompactionResultFromSessionMemory(
    messages, sessionMemory, messagesToKeep, ...
  )
  return compactionResult  // 不调用 LLM 生成摘要
}
```

#### Layer 4: Legacy Compact (compact.ts:387-763)

```typescript
// 调用 LLM 生成摘要
export async function compactConversation(messages, context, ...) {
  const compactPrompt = getCompactPrompt(customInstructions)
  const summaryRequest = createUserMessage({ content: compactPrompt })

  // 调用 LLM 生成摘要
  summaryResponse = await streamCompactSummary({
    messages: messagesToSummarize,
    summaryRequest,
    ...
  })
}
```

**渐进式降级模式：**

```
Layer 1 (最轻) → 清除内容标记，不换存储
     ↓ 仍然超出
Layer 2 (轻) → 缓存编辑删除，保留本地
     ↓ 仍然超出
Layer 3 (中) → 使用预提取记忆，不需要实时 LLM
     ↓ 仍然超出
Layer 4 (重) → 实时调用 LLM 生成摘要
```

---

### 问题3：上下文被压缩后，模型知不知道？能不能主动要回来？

**模型是否被告知压缩发生：是**

压缩后，系统会在 messages 中注入：
1. **Compact Boundary Message** - 系统 message 标记边界
2. **Summary User Message** - 包含摘要内容

```typescript
// messages.ts:4530-4555
export function createCompactBoundaryMessage(
  trigger: 'manual' | 'auto',
  preTokens: number,
  ...
): SystemCompactBoundaryMessage {
  return {
    type: 'system',
    subtype: 'compact_boundary',
    content: `Conversation compacted`,
    compactMetadata: { trigger, preTokens, userContext, messagesSummarized },
  }
}
```

**告知方式（prompt.ts:337-374）：**

```typescript
export function getCompactUserSummaryMessage(summary, ...) {
  let baseSummary = `This session is being continued from a previous conversation
that ran out of context. The summary below covers the earlier portion.

${formattedSummary}

If you need specific details from before compaction (like exact code snippets,
error messages, or content you generated), read the full transcript at: ${transcriptPath}

Recent messages are preserved verbatim.`
}
```

**被压缩信息的存储位置：**

| 信息类型 | 存储位置 | 格式 |
|---------|---------|------|
| 完整对话历史 | `~/.claude/projects/{project}/{sessionId}.jsonl` | JSONL (Transcript) |
| Session Memory | `~/.claude/session-memory/{sessionId}/notes.md` | Markdown |
| Compact Summary | 注入到 messages 中 | User Message |

**模型是否能主动恢复：部分能**

1. **能**：通过 **Read 工具读取 transcript 文件**
   - 摘要消息中告知了 transcript 路径
   - 模型可以自主决定读取

2. **不能**：没有专门的 `read_transcript` 工具
   - 需要模型自己决定是否读取
   - 没有自动"恢复上下文"的机制

**关键代码证据（compact.ts:613-624）：**

```typescript
const transcriptPath = getTranscriptPath()
const summaryMessages: UserMessage[] = [
  createUserMessage({
    content: getCompactUserSummaryMessage(
      summary,
      suppressFollowUpQuestions,
      transcriptPath,  // 告知模型 transcript 路径
    ),
    isCompactSummary: true,
    isVisibleInTranscriptOnly: true,
  }),
]
```

---

## 第三步：记忆系统全景图

```
用户输入
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    [Messages 构建]                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ System Prompt + CLAUDE.md + Tools + Context             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           │                                      │
│                           ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Conversation Messages (User/Assistant/Tool)             │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  Token Count Check     │  ← 确定性，代码自动
              │  (autoCompact.ts)      │
              └────────────────────────┘
                           │
            ┌──────────────┴──────────────┐
            │                              │
            ▼ 超过阈值                     ▼ 未超过
┌───────────────────────┐         ┌─────────────────┐
│  压缩决策树            │         │  直接发给模型   │
│  (渐进式降级)         │         └─────────────────┘
└───────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────────────┐
│ Layer 1: Time-based Microcompact                                   │
│   触发：距上次 assistant > N 分钟                                   │
│   操作：[Old tool result content cleared]                          │
│   LLM：否 ❌                                                        │
└───────────────────────────────────────────────────────────────────┘
            │ 仍然超出
            ▼
┌───────────────────────────────────────────────────────────────────┐
│ Layer 2: Cached Microcompact                                       │
│   触发：tool_result 数量 > 阈值                                     │
│   操作：cache_edits 删除                                            │
│   LLM：否 ❌                                                        │
└───────────────────────────────────────────────────────────────────┘
            │ 仍然超出
            ▼
┌───────────────────────────────────────────────────────────────────┐
│ Layer 3: Session Memory Compact                                    │
│   触发：SM 已初始化 + SM 非空                                       │
│   操作：用预提取的 notes.md 作为摘要                                 │
│   LLM：部分 ⚠️ (提取时用了，压缩时不用)                              │
└───────────────────────────────────────────────────────────────────┘
            │ SM 不可用
            ▼
┌───────────────────────────────────────────────────────────────────┐
│ Layer 4: Legacy Compact                                            │
│   触发：上述路径都失败                                              │
│   操作：调用 LLM 生成详细摘要                                        │
│   LLM：是 ✅                                                        │
└───────────────────────────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────────────┐
│                    Post-Compact Messages                           │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ [Compact Boundary] → system message                         │  │
│  │ [Summary] → user message (含 transcript 路径)               │  │
│  │ [Messages to Keep] → 保留的近期消息                          │  │
│  │ [Attachments] → 文件/技能/计划等                             │  │
│  └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────┘
            │
            ▼
         发给模型
            │
            ▼
┌───────────────────────────────────────────────────────────────────┐
│                    后台并行流程                                     │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ Session Memory Extraction (forked agent)                    │  │
│  │   触发：postSamplingHooks                                   │  │
│  │   条件：token 阈值 + (tool call 阈值 OR 自然断点)            │  │
│  │   存储：~/.claude/session-memory/{sessionId}/notes.md       │  │
│  └─────────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ Transcript Persistence                                      │  │
│  │   每条消息追加写入 JSONL                                     │  │
│  │   存储：~/.claude/projects/{project}/{sessionId}.jsonl      │  │
│  └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────┘

            图例：
            ─────→ 确定性流程（代码自动）
            - - - → 不确定性流程（需要 LLM 决策）
            □□□□□→ 磁盘存储
```

---

## 第四步：对比分析与迁移评估

### 猜测验证

| 猜测 | 验证结果 | 证据 |
|------|---------|------|
| A: 模型通过工具管理记忆 | ❌ **错误** | CC 没有让模型主动调用的记忆工具。Session Memory 是后台自动提取的确定性流程，通过 `postSamplingHooks` 在对话间隙触发。 |
| B: 确定性归代码/不确定性归模型 | ✅ **正确** | 清除内容、缓存删除、阈值判断 = 代码自动；摘要生成内容 = LLM 决策。边界清晰。 |
| C: 五层压缩 = 原则4+原则5 | ⚠️ **部分正确** | 实际是 **四层**（非五层），但"信息不丢弃"原则成立：所有被压缩内容都在 transcript 中保留，可通过 Read 工具恢复。"渐进式降级"模式也存在：从最轻的清除标记到最重的 LLM 摘要。 |

### 对 ragent 的迁移价值评估

| CC 机制 | ragent 当前状态 | 迁移可行性 | 优先级 |
|---------|---------------|-----------|--------|
| **Time-based Microcompact** | 无上下文压缩 | ⭐⭐⭐⭐ 高 - 纯代码，无 LLM 成本 | P0 |
| **Cached Microcompact** | 无缓存机制 | ⭐⭐ 低 - 需要先实现 prompt caching | P2 |
| **Session Memory** | 无结构化记忆 | ⭐⭐⭐ 中 - 需要 forked agent 基础设施 | P1 |
| **Compact Boundary + Transcript** | 无持久化 transcript | ⭐⭐⭐⭐ 高 - 告知模型压缩发生是关键 | P0 |
| **渐进式降级压缩策略** | 单一压缩策略 | ⭐⭐⭐⭐ 高 - 设计模式可复用 | P0 |

### 意外发现

1. **Session Memory 使用 forked agent 而非主对话**
   - 在独立上下文中运行，不干扰用户对话
   - 可以复用主对话的 prompt cache
   - 这是"委托"模式的体现：确定性逻辑委托给子智能体

2. **压缩后的"恢复能力"是设计过的**
   - Summary 消息中明确告知 transcript 路径
   - 模型可以自主决定是否读取历史
   - 这是一种"被动可用"的恢复机制

3. **Microcompact 有两种并行策略**
   - Time-based: 时间间隔超过阈值时清除（缓存已过期）
   - Cached: 数量超过阈值时通过 API 删除（缓存仍有效）
   - 都不涉及 LLM，纯粹是代码优化

4. **Post-compact 恢复了大量上下文**
   - 文件附件：最近读取的 5 个文件
   - 技能附件：已调用的技能内容
   - 计划附件：当前 plan mode 状态
   - 这确保压缩后模型不会"失忆"关键工作状态

---

## 核心洞察

### 1. CC 在"确定性 vs 不确定性"的边界画在哪里？

**边界非常清晰：**

| 确定性（代码自动） | 不确定性（LLM 决策） |
|------------------|-------------------|
| Token 计数和阈值判断 | 摘要内容生成 |
| 清除标记替换 | Session Memory 提取内容 |
| 缓存编辑操作 | 是否读取 transcript |
| 触发时机判断 | - |
| 文件/技能恢复选择 | - |

**设计原则：** 凡是可以用规则判断的，绝不调 LLM。只有真正需要"理解"和"决策"的内容才交给模型。

### 2. "信息不丢弃，只换存储位置"在实践中是怎么做的？

**具体实现：**

1. **Transcript 持久化** - 每条消息追加写入 JSONL 文件
2. **Compact Summary 注入路径** - 摘要消息中包含 transcript 路径
3. **Read 工具作为恢复通道** - 模型可以读取 transcript
4. **Session Memory 作为结构化备份** - 预提取的关键信息

**与 ragent 当前机制的对比：**

| 维度 | ragent 当前 | CC 实现 |
|------|------------|---------|
| 旧对话处理 | 直接丢弃 | 持久化到 JSONL |
| 恢复能力 | 无 | Read 工具 + transcript 路径 |
| 摘要告知 | 无 | 明确告知压缩发生 + 摘要内容 |
| 结构化记忆 | 无 | Session Memory 预提取 |

### 3. 模型对自身上下文状态的感知程度

**模型知道：**
- 压缩发生了（通过 boundary message）
- 摘要内容是什么
- Transcript 在哪里可以读取

**模型不知道：**
- 具体丢失了哪些内容
- 何时该主动恢复

**设计启示：**
- 不需要模型"知道"压缩细节
- 提供恢复通道（Read transcript）就够了
- 模型根据任务需要自主决定是否恢复

---

## 对 ragent 的建议

### 最值得迁移的（P0）

1. **Transcript 持久化 + 告知路径**
   - 实现"信息不丢弃"的核心
   - 摘要消息中告知 transcript 路径
   - 让模型可以按需恢复

2. **Time-based Microcompact**
   - 零 LLM 成本的优化
   - 纯代码逻辑，易于实现
   - 清除旧的 tool_result 标记

3. **渐进式降级压缩策略**
   - 设计模式可复用
   - 从轻到重的多层策略

### 可以延后的（P1-P2）

1. **Session Memory** (P1)
   - 需要 forked agent 基础设施
   - 可以先做简化版

2. **Cached Microcompact** (P2)
   - 依赖 API 特性（prompt caching）
   - 需要先实现缓存机制

### 不需要迁移的

- 模型主动记忆工具（CC 也没有）
- 完整复制 CC 的实现细节

---

## 参考文件路径

### 压缩核心
- `claude-code-sourcemap/restored-src/src/services/compact/compact.ts`
- `claude-code-sourcemap/restored-src/src/services/compact/microCompact.ts`
- `claude-code-sourcemap/restored-src/src/services/compact/sessionMemoryCompact.ts`
- `claude-code-sourcemap/restored-src/src/services/compact/autoCompact.ts`

### 记忆核心
- `claude-code-sourcemap/restored-src/src/services/SessionMemory/sessionMemory.ts`
- `claude-code-sourcemap/restored-src/src/services/SessionMemory/prompts.ts`

### 消息与存储
- `claude-code-sourcemap/restored-src/src/utils/messages.ts`
- `claude-code-sourcemap/restored-src/src/utils/sessionStorage.ts`