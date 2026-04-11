# CC Report 005：Multi-Agent 架构调研

> **调研时间：** 2026-04-10
> **调研方式：** claude-code 源码分析 + 内部知识库 + 有限网络资源

---

## 1. Multi-Agent 编排模式概览

### 1.1 主流编排模式分类

| 模式 | 结构特点 | 适用场景 | 优点 | 缺点 |
|------|----------|----------|------|------|
| **Orchestrator（中心化）** | 一个主 Agent 负责任务分解和调度，子 Agent 各司其职 | 任务有明确层级、需要全局协调、复杂工作流 | 简单控制流、一致状态、易监控、任务边界清晰 | 单点瓶颈、主 Agent 负担重 |
| **P2P（去中心化）** | Agent 之间平等协作，无中心节点 | 分布式决策、协商型任务、自主探索 | 无单点故障、高度可扩展、灵活 | 协调复杂、调试困难、一致性难保证 |
| **Pipeline/Chain（顺序）** | Agent 按顺序串联，上一个输出是下一个输入 | 数据处理流水线、有明确步骤顺序 | 实现简单、数据流清晰、易于追踪 | 不灵活、无法并行、中间失败全中断 |
| **Hierarchical（层级）** | 多层级的 Agent 树，上层管理下层 | 大规模任务分解、企业级组织结构 | 可扩展、故障隔离、并行执行 | 复杂度高、通信延迟、需要良好分层设计 |
| **Blackboard（黑板）** | 共享知识仓库，Agent 根据变化触发 | 专家系统集成、多源信息融合 | 松耦合、灵活添加 Agent | 共享状态管理复杂、并发问题 |
| **Contract Net（合同网）** | 任务公告 → 投标 → 授权 | 动态负载均衡、资源竞争 | 动态分配、容错 | 通信开销、投标复杂 |

### 1.2 claude-code 实际采用的编排模式

claude-code 主要采用 **Orchestrator + Fork** 模式：

```
┌─────────────────────────────────────────────────────┐
│                    Coordinator                       │
│  ┌───────────────────────────────────────────────  │
│  │ 主 Agent（对话）                                │  │
│  │ - 接收用户需求                                  │  │
│  │ - 分解任务                                      │  │
│  │ - 调度 Agent 工具                               │  │
│  │ - 收集结果并综合                                │  │
│  └───────────────────────────────────────────────  │
│           │                                          │
│           │ AgentTool.spawn()                        │
│           ▼                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ Worker Agent │  │ Worker Agent │  │ Fork Agent │ │
│  │ (background) │  │ (sync)       │  │ (inherit)  │ │
│  └──────────────┘  └──────────────┘  └────────────┘ │
│           │                                          │
│           │ task-notification                        │
│           ▼                                          │
│  ┌───────────────────────────────────────────────┐  │
│  │ 结果通过 <task-notification> 返回给主 Agent    │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**关键文件引用：**
- `src/coordinator/coordinatorMode.ts` — Coordinator 模式的系统 prompt
- `src/tools/AgentTool/AgentTool.tsx` — Agent 工具实现（spawn、fork、background）
- `src/tools/AgentTool/forkSubagent.ts` — Fork 机制实现

---

## 2. A2A 协议核心概念

> **说明：** 由于网络限制无法直接访问 A2A 官方文档，以下基于行业知识整理。

### 2.1 A2A 解决的核心问题

A2A（Agent-to-Agent）协议由 Google 于 2025 年提出，主要解决以下问题：

| 问题 | A2A 的解决方案 |
|------|----------------|
| **Agent 发现** | Agent Card — JSON 格式的元数据文件，描述 Agent 的能力、端点、认证方式 |
| **能力协商** | 通过 Agent Card 中的 skills 字段声明可执行的任务类型 |
| **任务生命周期** | Task 对象管理任务状态（pending → running → completed/failed） |
| **消息标准化** | Message 格式统一，包含 role、content、attachments |
| **状态同步** | Task 状态变更通知，支持异步轮询或推送 |

### 2.2 A2A 与 MCP 的关系

```
┌─────────────────────────────────────────────────────┐
│                    Agent                             │
│                                                      │
│  ┌───────────────────────────────────────────────┐  │
│  │ A2A Protocol                                   │  │
│  │ - Agent-to-Agent 通信                          │  │
│  │ - 能力发现、任务委托                            │  │
│  │ - 跨系统协作                                   │  │
│  └───────────────────────────────────────────────┘  │
│                      │                               │
│                      │                               │
│  ┌───────────────────────────────────────────────┐  │
│  │ MCP Protocol                                   │  │
│  │ - Agent-to-Tool 通信                           │  │
│  │ - 工具注册、调用                               │  │
│  │ - 资源访问                                     │  │
│  └───────────────────────────────────────────────┘  │
│                      │                               │
│                      ▼                               │
│  ┌───────────────────────────────────────────────┐  │
│  │ Tools / Resources                              │  │
│  │ (数据库、文件系统、API...)                     │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**分工：**
- **MCP**：解决 Agent 如何调用外部工具/资源（Agent-to-Tool）
- **A2A**：解决 Agent 如何与其他 Agent 协作（Agent-to-Agent）

### 2.3 对 ragent 的启发

| 场景 | 是否需要 A2A |
|------|-------------|
| **单系统内部的 Agent 协作** | 不需要，轻量方式足够（函数调用、消息队列） |
| **跨系统的 Agent 互操作** | 可能需要，如对接外部 Agent 服务 |
| **开放平台/生态对接** | 需要，便于第三方 Agent 发现和接入 |

**ragent 当前是单系统内部协作，A2A 属于过度设计。**

---

## 3. claude-code 源码分析

### 3.1 核心架构骨架

**入口：** `src/main.tsx` → `src/screens/REPL.tsx` → `src/hooks/useReplBridge.tsx`

**主循环：** `src/query.ts` → `src/QueryEngine.ts`

```
QueryEngine.submitMessage()
    │
    ├─ 1. processUserInput() — 处理用户输入（斜杠命令、技能等）
    │
    ├─ 2. assembleToolPool() — 组装工具池
    │
    ├─ 3. query() — 调用 API
    │      │
    │      ├─ checkAutoCompact() — 检查是否需要压缩
    │      │
    │      ├─ streamResponse() — 流式获取响应
    │      │
    │      ├─ executeToolUse() — 执行工具调用
    │      │      │
    │      │      ├─ AgentTool.spawn() — 创建子 Agent
    │      │      │
    │      │      └─ 其他工具执行
    │      │
    │      └─ 继续循环直到无 tool_use
    │
    └─ 4. handleResult() — 处理结果、更新状态
```

**关键文件：**
| 文件 | 作用 |
|------|------|
| `src/QueryEngine.ts` | 主循环生命周期管理、消息状态、工具执行协调 |
| `src/query.ts` | 单次查询执行逻辑 |
| `src/tools.ts` | 工具池组装入口 |
| `src/utils/toolPool.ts` | 工具池合并、过滤、排序 |

### 3.2 子任务/子 Agent 机制

**三种模式：**

| 模式 | 触发方式 | 特点 | 文件 |
|------|----------|------|------|
| **Worker（后台）** | `AgentTool({ subagent_type: "worker", run_in_background: true })` | 异步执行，结果通过 `<task-notification>` 返回 | `AgentTool.tsx` |
| **Worker（同步）** | `AgentTool({ subagent_type: "worker" })` | 同步执行，阻塞等待结果 | `AgentTool.tsx` |
| **Fork（继承上下文）** | `AgentTool({ prompt: "..." })` 不指定 subagent_type | 继承父 Agent 的完整上下文和工具池，用于缓存共享 | `forkSubagent.ts` |

**Fork 机制关键设计：**

```typescript
// forkSubagent.ts:107-169
export function buildForkedMessages(
  directive: string,
  assistantMessage: AssistantMessage,
): MessageType[] {
  // 1. 克隆父 Agent 的 assistant 消息（包含所有 tool_use）
  // 2. 构建占位符 tool_result（统一内容，用于缓存共享）
  // 3. 添加 per-child directive（唯一的差异部分）
  // 结果：[assistant(all_tool_uses), user(placeholder_results..., directive)]
}
```

**设计意图：** Fork 子 Agent 继承父 Agent 的完整对话上下文，但为了 prompt cache 共享，所有 fork child 的 API 请求前缀必须字节一致。唯一差异是最后的 directive。

### 3.3 上下文管理

**三层机制：**

```
┌─────────────────────────────────────────────────────┐
│                    Context 管理                      │
├─────────────────────────────────────────────────────┤
│                                                      │
│  Layer 1: Compact（摘要压缩）                        │
│  ┌───────────────────────────────────────────────┐  │
│  │ services/compact/compact.ts                    │  │
│  │ - 当 context 超过阈值时触发                     │  │
│  │ - 用 LLM 生成历史摘要                          │  │
│  │ - 替换旧消息为 compact boundary message        │  │
│  └───────────────────────────────────────────────┘  │
│                                                      │
│  Layer 2: Microcompact（微压缩）                     │
│  ┌───────────────────────────────────────────────┐  │
│  │ services/compact/microCompact.ts               │  │
│  │ - 单条消息级别的压缩                           │  │
│  │ - 清理大体积工具结果（如 Read、Bash 输出）     │  │
│  │ - 保留结构标记，替换内容为占位符               │  │
│  └───────────────────────────────────────────────┘  │
│                                                      │
│  Layer 3: Session Memory（会话记忆）                 │
│  ┌───────────────────────────────────────────────┐  │
│  │ services/SessionMemory/sessionMemory.ts        │  │
│  │ - 后台 fork agent 执行                         │  │
│  │ - 提取关键信息写入 MEMORY.md                   │  │
│  │ - 跨 compact 保留的知识                       │  │
│  └───────────────────────────────────────────────┘  │
│                                                      │
└─────────────────────────────────────────────────────┘
```

**关键阈值：**
- `AUTOCOMPACT_BUFFER_TOKENS = 13_000`（`autoCompact.ts:62`）
- `WARNING_THRESHOLD = 20_000 tokens`
- 触发条件：`tokenUsage >= effectiveContextWindow - buffer`

### 3.4 工具注册机制

**入口：** `src/tools.ts`

```typescript
// tools.ts 核心逻辑
export function assembleToolPool(
  builtInTools: Tools,
  mcpTools: Tools,
  permissionContext: ToolPermissionContext,
): Tools {
  // 1. 合并内置工具 + MCP 工具
  // 2. 唯一化（按 name）
  // 3. 分区排序（built-in 前缀，MCP 后缀，用于 prompt cache）
  // 4. 应用权限过滤
  // 5. 应用 coordinator 模式过滤（如果启用）
}
```

**工具定义：** 每个 Tool 实现 `buildTool()` 接口：

```typescript
// Tool.ts
export interface ToolDef {
  name: string;
  inputSchema: z.ZodType;
  outputSchema?: z.ZodType;
  prompt?: (context) => Promise<string>;
  call: (input, context) => Promise<Output>;
}
```

---

## 4. 上下文接力机制

### 4.1 业界方案对比

| 方案 | 原理 | 优点 | 缺点 | claude-code 实现 |
|------|------|------|------|-----------------|
| **摘要压缩** | 用 LLM 生成历史摘要，替换原消息 | 大幅减少 token、保留语义 | 有信息损失、额外 API 调用 | ✅ Compact |
| **滑动窗口** | 只保留最近 N 条消息 | 实现简单、无额外调用 | 丢失早期上下文 | ❌ 未采用 |
| **内容截断** | 截断大体积工具输出 | 精确控制、部分保留 | 可能丢失关键信息 | ✅ Microcompact |
| **外部存储** | 将信息存入文件，Agent 需要时读取 | 无限扩展、可跨会话 | 需要主动读取、增加复杂度 | ✅ Session Memory |
| **Agent 切换** | 新 Agent 以摘要为起点继续 | 彻底重置 context | 切换成本、可能丢失状态 | ❌ 未采用 |

### 4.2 claude-code 的组合策略

```
Context 接近上限
    │
    ├─ Step 1: Microcompact（清理工具结果）
    │      - 替换 Read/Bash 输出为占位符
    │      - 保留结构标记
    │
    ├─ Step 2: Compact（摘要历史）
    │      - 生成 conversation summary
    │      - 创建 compact boundary message
    │      - 替换旧消息
    │
    ├─ Step 3: Session Memory（后台持久化）
    │      - Fork agent 提取关键信息
    │      - 写入 MEMORY.md
    │      - 下次对话可加载
    │
    └─ Step 4: 继续 Agent Loop
```

**关键挑战：**
1. **压缩时机**：既要避免过早压缩导致信息丢失，又要避免过晚触发 API 错误
2. **摘要质量**：LLM 生成的摘要可能遗漏关键细节
3. **一致性**：压缩后的消息结构与原结构需兼容，否则影响工具调用追踪

### 4.3 对用户是否无感？

**答案：部分无感，部分有显式提示。**

- `autoCompact`：静默执行，用户不感知
- 手动 `/compact`：用户主动触发，显示摘要内容
- `compactWarning`：当 context 达到警告阈值时，提示用户即将压缩
- Session Memory：后台执行，用户不感知

---

## 5. 对 ragent 的启发（最重要）

### 5.1 ragent 现状分析

**已有能力：**
```java
// AgentLoop.java — 单 Agent 实现
public AgentLoopResult run(String userQuery) {
    while (true) {
        response = callLLM(messages, tools);
        toolUses = extractToolUses(response);
        if (toolUses.isEmpty()) return response;  // 模型不调工具 = 终止
        if (turnCount > maxTurns) return "超限";
        results = executeTools(toolUses);
        messages.add(response);
        messages.add(results);
        turnCount++;
    }
}
```

**特点：**
- 单 Agent，单工具（`knowledge_search_with_rerank`）
- 无 Multi-Agent 编排能力
- 无上下文压缩机制
- 无任务分解能力
- 简单的 maxTurns 护栏

### 5.2 推荐的 Multi-Agent 场景

| 场景 | 描述 | 编排模式 | 复杂度 |
|------|------|----------|--------|
| **查询路由 Agent** | 主 Agent 分析意图，路由到专门的检索 Agent/生成 Agent | Orchestrator | 低 |
| **领域专家 Agent** | 不同知识领域（如 HR、财务、技术）有专门的检索+回答 Agent | Orchestrator + 领域识别 | 中 |
| **检索增强 Agent** | 一个 Agent 专注检索策略优化，另一个 Agent 专注回答生成 | Pipeline/Chain | 低 |
| **验证 Agent** | 主 Agent 生成回答后，验证 Agent 检查引用准确性、一致性 | Orchestrator（后验证） | 中 |

**最自然的场景：查询路由 + 检索增强**

```
用户问题
    │
    ▼
┌─────────────────────────────────────────────────────┐
│              Router Agent（意图识别）                 │
│  - 分析问题类型（简单问答、复杂分析、多轮对话）       │
│  - 决定检索策略                                      │
│  - 决定是否需要多源检索                              │
└─────────────────────────────────────────────────────┘
    │
    ├─ 简单问答 → Single Retrieval Agent
    │
    ├─ 复杂分析 → Multi-Query Retrieval Agent → Analysis Agent
    │
    └─ 多轮对话 → Context Tracking Agent → Retrieval Agent → Response Agent
```

### 5.3 推荐的编排模式

**推荐：Orchestrator 模式**

**理由：**
1. **知识库问答有明确的任务层级**：意图识别 → 检索策略 → 检索 → Rerank → 生成
2. **需要全局协调**：检索结果需要汇总后才能生成回答
3. **调试友好**：任务边界清晰，便于定位问题
4. **与现有 Pipeline RAG 一致**：Pipeline 模式本质上是 Orchestrator 的简化版

**不推荐的模式：**
- **P2P**：Agent 协商成本高，不适合确定性流程
- **Pipeline**：太僵化，无法处理复杂的意图路由

### 5.4 建议的最小化下一步

**方案 A：检索策略 Agent（改动最小）**

当前单 Agent 已经有 `knowledge_search_with_rerank` 工具。最小改动：

```java
// 新增一个"检索策略 Agent"作为前置步骤
public class RetrievalStrategyAgent {
    public RetrievalPlan analyze(String query) {
        // 分析问题，决定：
        // 1. 是否需要检索
        // 2. 检索关键词
        // 3. 检索次数（可能需要多轮）
        // 4. 检索阈值（rerank score cutoff）
    }
}

// 主 AgentLoop 修改
public AgentLoopResult run(String userQuery) {
    // Step 1: 检索策略分析（可选的子 Agent）
    RetrievalPlan plan = strategyAgent.analyze(userQuery);

    // Step 2: 按策略执行（现有逻辑）
    while (true) {
        // ... 现有 AgentLoop 逻辑
    }
}
```

**改动量：** 新增 1 个类，修改 AgentLoopService 入口逻辑

**方案 B：Fork 子 Agent（参考 claude-code）**

如果需要并行检索或后台处理：

```java
// 新增 ForkExecutor
public class ForkExecutor {
    public Future<AgentLoopResult> fork(String directive, Map<String, Tool> tools) {
        // 创建子 AgentLoop，继承当前上下文
        // 异步执行，返回 Future
    }
}

// 主 Agent 可以并行发起多个检索
List<Future<RetrievalResult>> futures = new ArrayList<>();
for (String keyword : keywords) {
    futures.add(forkExecutor.fork("检索关键词: " + keyword, retrievalTools));
}
// 等待所有结果，汇总
```

**改动量：** 新增 ForkExecutor，修改 AgentLoop 支持异步/上下文继承

**推荐：先做方案 A，验证价值后再考虑方案 B**

---

## 6. 补充：claude-code 的 Agent 工具调用流程

```typescript
// AgentTool.tsx:239-300 核心流程
async call(input: AgentToolInput, context: ToolUseContext) {
    // 1. 检查权限和模式
    if (team_name && name) {
        // 多 Agent 模式：spawn teammate
        return spawnTeammate({ name, prompt, team_name, ... });
    }

    // 2. Fork 模式检查
    if (isForkSubagentEnabled() && !subagent_type) {
        // Fork：继承父 Agent 上下文
        const forkedMessages = buildForkedMessages(directive, assistantMessage);
        return runForkedAgent(forkedMessages, ...);
    }

    // 3. 标准 Worker 模式
    const agentDef = loadAgentDefinition(subagent_type);
    const toolPool = assembleToolPool(agentDef.tools);

    // 4. 执行子 Agent
    if (run_in_background) {
        // 异步执行，注册 LocalAgentTask
        registerAsyncAgent(agentId, task);
        return { status: 'async_launched', agentId, outputFile };
    } else {
        // 同步执行
        return runAgent(prompt, toolPool);
    }
}
```

**结果返回：**
```xml
<task-notification>
<task-id>agent-a1b</task-id>
<status>completed</status>
<summary>Agent "Investigate auth bug" completed</summary>
<result>Found null pointer in src/auth/validate.ts:42...</result>
<usage>
  <total_tokens>N</total_tokens>
  <tool_uses>N</tool_uses>
  <duration_ms>N</duration_ms>
</usage>
</task-notification>
```

---

## 7. 调研局限与后续建议

**局限：**
- A2A 协议无法直接访问官方文档，基于知识库推断
- claude-code 源码量巨大，仅分析了核心骨架

**后续建议：**
1. 若需深入了解 A2A，可手动获取 `https://github.com/google/A2A` 仓库内容
2. 若需实现 Multi-Agent，优先参考 claude-code 的 Coordinator + Fork 模式
3. 先做最小化改动（方案 A），验证后再扩展

---

*报告完成*

人工补充A2Agithub README（https://github.com/a2aproject/A2A) ,如果后续需要，可以fork到本地CC继续调研或通过deepwiki调研
Agent2Agent (A2A) Protocol
PyPI - Version Apache License Ask Code Wiki

🌐 Language
Agent2Agent Protocol Logo
Agent2Agent (A2A) Protocol
An open protocol enabling communication and interoperability between opaque agentic applications.

The Agent2Agent (A2A) protocol addresses a critical challenge in the AI landscape: enabling gen AI agents, built on diverse frameworks by different companies running on separate servers, to communicate and collaborate effectively - as agents, not just as tools. A2A aims to provide a common language for agents, fostering a more interconnected, powerful, and innovative AI ecosystem.

With A2A, agents can:

Discover each other's capabilities.
Negotiate interaction modalities (text, forms, media).
Securely collaborate on long-running tasks.
Operate without exposing their internal state, memory, or tools.
DeepLearning.AI Course
A2A DeepLearning.AI

Join this short course on A2A: The Agent2Agent Protocol, built in partnership with Google Cloud and IBM Research, and taught by Holt Skinner, Ivan Nardini, and Sandi Besen.

What you'll learn:

Make agents A2A-compliant: Expose agents built with frameworks like Google ADK, LangGraph, or BeeAI as A2A servers.
Connect agents: Create A2A clients from scratch or using integrations to connect to A2A-compliant agents.
Orchestrate workflows: Build sequential and hierarchical workflows of A2A-compliant agents.
Multi-agent systems: Build a healthcare multi-agent system using different frameworks and see how A2A enables collaboration.
A2A and MCP: Learn how A2A complements MCP by enabling agents to collaborate with each other.
Why A2A?
As AI agents become more prevalent, their ability to interoperate is crucial for building complex, multi-functional applications. A2A aims to:

Break Down Silos: Connect agents across different ecosystems.
Enable Complex Collaboration: Allow specialized agents to work together on tasks that a single agent cannot handle alone.
Promote Open Standards: Foster a community-driven approach to agent communication, encouraging innovation and broad adoption.
Preserve Opacity: Allow agents to collaborate without needing to share internal memory, proprietary logic, or specific tool implementations, enhancing security and protecting intellectual property.
Key Features
Standardized Communication: JSON-RPC 2.0 over HTTP(S).
Agent Discovery: Via "Agent Cards" detailing capabilities and connection info.
Flexible Interaction: Supports synchronous request/response, streaming (SSE), and asynchronous push notifications.
Rich Data Exchange: Handles text, files, and structured JSON data.
Enterprise-Ready: Designed with security, authentication, and observability in mind.
Getting Started
📚 Explore the Documentation: Visit the Agent2Agent Protocol Documentation Site for a complete overview, the full protocol specification, tutorials, and guides.
📝 View the Specification: A2A Protocol Specification
Use the SDKs:
🐍 A2A Python SDK pip install a2a-sdk
🐿️ A2A Go SDK go get github.com/a2aproject/a2a-go
🧑‍💻 A2A JS SDK npm install @a2a-js/sdk
☕️ A2A Java SDK using maven
🔷 A2A .NET SDK using NuGet dotnet add package A2A
🎬 Use our samples to see A2A in action
Contributing
We welcome community contributions to enhance and evolve the A2A protocol!

Questions & Discussions: Join our GitHub Discussions.
Issues & Feedback: Report issues or suggest improvements via GitHub Issues.
Contribution Guide: See our CONTRIBUTING.md for details on how to contribute.
Private Feedback: Use this Google Form.
Partner Program: Google Cloud customers can join our partner program via this form.
What's next
Protocol Enhancements
Agent Discovery:
Formalize inclusion of authorization schemes and optional credentials directly within the .AgentCard
Agent Collaboration:
Investigate a method for dynamically checking unsupported or unanticipated skills.QuerySkill()
Task Lifecycle & UX:
Support for dynamic UX negotiation within a task (e.g., agent adding audio/video mid-conversation).
Client Methods & Transport:
Explore extending support to client-initiated methods (beyond task management).
Improvements to streaming reliability and push notification mechanisms.
About
The A2A Protocol is an open source project under the Linux Foundation, contributed by Google. It is licensed under the Apache License 2.0 and is open to contributions from the community.