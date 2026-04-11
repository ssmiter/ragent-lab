# CC Task 005：Multi-Agent 架构模式调研

> **目标：** 调研 Multi-Agent 的核心编排模式和设计模式，找到对 ragent 系统最有启发的架构骨架。
> **约束：** 只找核心骨架，不陷入框架细节。每个调研点以"这对我们的系统意味着什么"收尾。
> **产出：** `CC_Report_005_MultiAgent_Research.md`

---

## 背景

ragent 系统目前已有：
- **Pipeline RAG**：固定流程的检索增强生成
- **Agentic RAG（单 Agent）**：一个 AgentLoop，自主决定搜索策略，调用 `knowledge_search_with_rerank` 工具

下一步我们想理解：如果要从单 Agent 演进到 Multi-Agent，核心的架构选择是什么？不是要立刻实现，而是要看清楚方向。

---

## 调研问题（按优先级排列）

### 1. Multi-Agent 编排模式有哪几种？

业界主流的 Multi-Agent 编排模式做一个分类梳理。重点关注：

- **Orchestrator 模式**（中心化）：一个主 Agent 负责任务分解和调度，子 Agent 各司其职
- **P2P 模式**（去中心化）：Agent 之间平等协作，无中心节点
- **Pipeline/Chain 模式**：Agent 按顺序串联，上一个的输出是下一个的输入
- **Hierarchical 模式**：多层级的 Agent 树

每种模式的适用场景、优缺点是什么？哪种最适合"知识库问答"这类场景？

### 2. A2A（Agent-to-Agent）协议

Google 提出了 A2A 协议，调研：

- A2A 的核心概念是什么？（Agent Card、Task、Message 等）
- 它解决的核心问题是什么？（Agent 发现？能力协商？状态同步？）
- 它与 MCP（Model Context Protocol）的关系：MCP 解决 Agent-to-Tool，A2A 解决 Agent-to-Agent，两者如何配合？
- 对我们的启发：ragent 是否需要这种级别的协议？还是说在单系统内部，更轻量的方式就够了？

### 3. claude-code 源码中的 Agent 编排

在 claude-code 开源代码（claude-code-sourcemap目录）中寻找以下核心逻辑：

- **主循环结构**：它的 Agent Loop 和我们的有什么异同？
- **子任务/子 Agent 机制**：claude-code 是否有将复杂任务拆分给子 Agent 执行的能力？如果有，拆分和汇总的逻辑在哪？
- **上下文管理**：长对话中 context 怎么处理的？有没有压缩/摘要/截断机制？
- **工具注册机制**：工具是怎么注册和发现的？（和我们的 Tool 接口做对比）

> **调研方法：** 先看项目的 README 和目录结构，找到核心入口文件，然后顺着调用链往下看。不需要读懂每一行代码，只需要画出核心骨架。
> **代码位置：** 可以通过 `git clone https://github.com/anthropics/claude-code` 获取（如果网络不通，在 REPORT 中说明，我们另想办法）。

### 4. 上下文接力（Context Relay）

当单个 Agent 的 context window 接近上限时：

- 业界有哪些处理方式？（压缩摘要？切换新 Agent？滑动窗口？）
- claude-code 或其他开源项目中有没有实际的"上下文接力"实现？（旧 Agent 生成摘要 → 新 Agent 以摘要为起点继续）
- 这种接力对用户来说是否真的可以做到无感？技术上的关键挑战是什么？

### 5. 对 ragent 的启发与映射

这是最重要的部分。基于以上调研，回答：

- **ragent 最自然的 Multi-Agent 场景是什么？** 举例：是把"查询重写 Agent""检索 Agent""回答生成 Agent"拆开？还是按知识领域拆分多个专家 Agent？还是其他方式？
- **推荐的编排模式是什么？** 为什么？
- **最小化的下一步是什么？** 如果我们要从当前的单 AgentLoop 迈出 Multi-Agent 的第一步，改动最小、价值最大的方向是什么？

---

## 调研边界（不要做的事）

- **不要做框架对比评测**（CrewAI vs AutoGen vs LangGraph 等），这些框架的存在可以提及，但不要深入比较
- **不要写实现代码**，这是纯调研任务
- **不要追求面面俱到**，每个问题找到核心答案即可，宁可精简也不要冗长
- **不要读 claude-code 的每个文件**，只看和 Agent 编排直接相关的核心文件

---

## 产出格式

生成 `CC_Report_005_MultiAgent_Research.md`，结构：

```markdown
# CC Report 005：Multi-Agent 架构调研

## 1. Multi-Agent 编排模式概览
（分类、对比表、适用场景）

## 2. A2A 协议核心概念
（解决什么问题、核心设计、与 MCP 的关系）

## 3. claude-code 源码分析
- 核心架构骨架（附文件路径引用）
- 子任务机制
- 上下文管理
- 工具注册

## 4. 上下文接力机制
（业界方案、技术挑战）

## 5. 对 ragent 的启发（最重要）
- 推荐的 Multi-Agent 场景
- 推荐的编排模式及理由
- 建议的最小化下一步
```

---

## 注意事项

- 调研中如果发现某个方向对 ragent 特别有价值但不在上述列表中，自行补充到 REPORT 中
- 如果 claude-code 仓库无法克隆（网络限制），改为基于公开文档和博客调研，在 REPORT 中说明
- 保持"对我们意味着什么"的视角贯穿全文，纯学术性的内容点到即止
