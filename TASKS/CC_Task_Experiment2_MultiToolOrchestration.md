# CC 任务：实验2 — 多工具编排（rewrite_query + knowledge_search）

## 背景

### 实验序列回顾

```
实验0: Agent Loop 骨架验证（EchoTool）         → ✅ 百炼 API 兼容
实验1: 单工具 Agent（裸检索）                   → ✅ 模型会自主重试，但分数偏低导致过度重试
实验1.5: 统一接口 + Rerank                     → ✅ McpToolAdapter 跑通，rerank 分数让模型决策合理
实验2: 多工具编排（本次）                       → 🔬 核心问题：模型能做出比 pipeline 更好的编排决策吗？
```

### 核心假设

**ragent 的 pipeline 对每个查询都执行固定流程：重写 → 检索 → rerank → 生成。不管问题简单还是复杂，重写步骤总是被执行。**

**Agent Loop 有潜力更聪明：模型看到问题后自己判断——简单的直接搜，模糊的先重写再搜。这种"按需重写"应该在某些场景下优于"无脑总是重写"。**

### 统一逻辑

Agent Loop 的本质是一台**上下文优化机器**——每轮循环的唯一目标是：给模型补充它此刻最需要的信息，让它做出最好的决策。

工具不是"模型的手"，是**模型的感官**——通过工具获取它自身不具备的信息。rewrite_query 工具给了模型一个新的感官：把模糊的用户意图转化为精确的检索查询。

## 你需要做的事情

### 第一步：调查 ragent 现有的查询重写能力

**请查找：**

1. ragent 的查询重写逻辑在哪里？可能的位置：
   - framework 模块下的某个 Service（QueryRewriteService？RewriteService？）
   - infra-ai 模块下的 LLM 调用
   - 或者在 RAG pipeline 的某个环节中内联实现

2. 重写的具体实现：
   - 输入是什么？（原始 query + 对话历史？还是只有 query？）
   - 输出是什么？（单个重写后的 query？还是多个候选？）
   - 用的什么模型？什么 prompt？
   - 是否有 HTTP 接口可以直接调用？

3. 如果找不到独立的重写接口，确认 ragent_qa 的完整 pipeline 中重写步骤在哪一环。

**输出：** 简短报告，包含重写逻辑的位置、接口签名、输入输出格式。

### 第二步：实现 QueryRewriteTool

**目标：** 创建一个 MCPToolExecutor，包装 ragent 的查询重写能力。

**实现路径选择（请评估）：**

- **路径A：直接调用 ragent 的重写 Service（如果 experiment 模块能依赖 framework）**
- **路径B：走 HTTP 调用 ragent 的接口（如果有独立接口）**
- **路径C：在工具内部自己实现一个简单的重写（调百炼 API，用专门的 rewrite prompt）**

如果 ragent 的重写逻辑深度耦合在 pipeline 内部、不好单独抽出来，**路径C 完全可行**——重写本质就是一次 LLM 调用，prompt 可以简单写：

```
你是一个查询重写助手。用户的原始问题可能模糊、口语化或不够精确。
请将其改写为更适合向量检索的精确查询。

规则：
1. 保留核心语义，不要臆测用户没说的内容
2. 把口语化表达转为技术术语
3. 如果原始查询已经足够精确，直接返回原文
4. 输出只包含重写后的查询，不要解释

原始查询：{query}
重写后查询：
```

**工具定义：**
```json
{
  "name": "rewrite_query",
  "description": "Rewrite a vague or colloquial user query into a more precise search query suitable for knowledge base retrieval. Use this BEFORE calling knowledge_search when the user's question is ambiguous, uses informal language, or could benefit from being rephrased with more specific technical terms. Do NOT use this if the query is already clear and specific.",
  "parameters": {
    "type": "object",
    "properties": {
      "original_query": {
        "type": "string",
        "description": "The original user query to be rewritten"
      },
      "intent_hint": {
        "type": "string",
        "description": "Optional hint about what the user is really looking for, to guide the rewrite"
      }
    },
    "required": ["original_query"]
  }
}
```

**返回格式：**
```
Rewritten query: "自适应截断策略 分数阈值 相邻分数差"
Original: "之前看过一个什么自适应的东西，好像和分数有关？"
Confidence: high (原始查询模糊，重写后更适合检索)
```

**关键设计决策：** 在返回中暴露 confidence 信息，让 Agent Loop 的模型能判断重写是否有意义。

### 第三步：更新 System Prompt

在实验1.5的 System Prompt 基础上，增加关于双工具编排的指导。

**新增的行为指导（核心部分）：**

```markdown
## 你的工具

你有两个工具：

1. **rewrite_query** — 将模糊或口语化的查询改写为更精确的检索查询
   - 当用户问题模糊、口语化、或包含不精确的表述时使用
   - 当用户问题已经清晰具体时，跳过重写，直接搜索
   
2. **knowledge_search_with_rerank** — 在知识库中搜索相关信息
   - 使用精确的关键词或短语
   - 观察返回的分数判断结果质量

## 决策流程

收到用户问题后，你需要判断：

**路径A — 直接搜索：** 如果问题清晰、使用了技术术语、目标明确
  → 直接调用 knowledge_search_with_rerank

**路径B — 先重写再搜索：** 如果问题模糊、口语化、或你不确定最佳检索词
  → 先调用 rewrite_query 获得更精确的查询
  → 然后用重写后的查询调用 knowledge_search_with_rerank

**路径C — 搜索后重试：** 如果首次搜索结果分数低于 0.75
  → 考虑用不同角度的查询再搜一次（可以先 rewrite_query 再搜，或直接换关键词）
  → 最多重试 2 次

注意：不是每个问题都需要重写。对于清晰的技术问题，直接搜索通常更有效。
```

**请根据实验1.5的实际 System Prompt 进行合并修改，不要从头重写。**

### 第四步：更新 AgentLoop 入口

创建 `AgentLoopExperiment2.java`：

```java
// 注册两个工具
McpToolAdapter rewriteTool = new McpToolAdapter(queryRewriteExecutor);
McpToolAdapter searchTool = new McpToolAdapter(knowledgeSearchWithRerankExecutor);
agentLoop.registerTool(rewriteTool);
agentLoop.registerTool(searchTool);
```

### 第五步：运行测试并记录结果

**测试问题分两组：**

**A组 — 正常问题（6个，来自之前的评测集）：**
```
Q1: "Ragent系统的整体架构是什么？"
Q2: "分块策略是怎么实现的？"
Q3: "意图识别的流程是什么？"
Q4: "MCP工具是怎么注册和调用的？"
Q5: "多模型降级策略怎么工作？"
Q6: "前端是什么技术栈？"
```

**B组 — 刁难问题（4个，专门设计来测试编排能力）：**
```
Q7: "之前看过一个什么自适应的东西，好像和分数有关？"
  → 预期：模型应该先重写（"自适应截断 分数"），再搜索
  
Q8: "MCP 工具注册后是怎么被 Agent Loop 调用的？"
  → 预期：可能需要搜两个主题（MCP 注册 + Agent Loop 调用），或先重写再搜
  
Q9: "ragent 有没有用 LangChain？"
  → 预期：搜索后可能找不到相关内容，模型应诚实告知
  
Q10: "那个把文档切成小块的功能咋实现的"
  → 预期：模型应该先重写（口语 → "文档分块策略 实现"），再搜索
```

**对每个问题，记录以下信息：**

```markdown
### Q[N]: [问题] (组别: A/B)

**Agent Loop 执行路径：**
- 总轮次: ?
- 工具调用链: 
  [Turn 1] rewrite_query("...") → "重写结果" 
  [Turn 2] knowledge_search("...") → top1=?, top3=?
  [Turn 3] (如有重试)
  ...
- 最终回答: (摘要，2-3句话)
- 模型是否选择重写: 是/否
- 重写是否有帮助: (如果重写了，对比重写前后的 query 质量)

**编排决策分析：**
- 模型选了路径 A/B/C 中的哪一个？
- 这个选择合理吗？（你的判断）
- 如果选择不合理，原因是什么？（description 不清晰？分数信号不够？）
```

### 第六步：汇总对比分析

跑完10个问题后，生成一份汇总表：

```markdown
## 编排决策汇总

| 问题 | 组别 | 是否重写 | 搜索次数 | 总轮次 | top1分数 | 回答质量(1-5) |
|------|------|---------|---------|--------|---------|--------------|
| Q1   | A    | ?       | ?       | ?      | ?       | ?            |
| ...  | ...  | ...     | ...     | ...    | ...     | ...          |

## 关键观察

1. A组（正常问题）中，模型选择重写了哪些？不重写是否更好？
2. B组（刁难问题）中，模型的编排策略和预期一致吗？
3. 有没有模型做出"聪明"决策的案例？（比如正确判断不需要重写）
4. 有没有模型做出"愚蠢"决策的案例？（比如清晰问题也去重写）
5. rewrite_query 返回的重写质量如何？有没有"越改越差"的情况？
```

## 关键约束

1. **遵循 McpToolAdapter 模式** — rewrite_query 工具实现为 MCPToolExecutor，通过适配器注册到 Agent Loop
2. **不改 ragent 现有代码**（bootstrap 的新端点除外，如果需要的话）
3. **先跑通一个 B 组问题**（比如 Q10），确认双工具编排闭环，再跑全部10个
4. **日志详尽** — 每轮的完整输入输出，特别是模型在调工具前的"思考文本"（如果有的话）

## 预期产出

1. ragent 查询重写能力的调查报告（简短）
2. `QueryRewriteMCPExecutor.java`（或类似命名）— 重写工具实现
3. 更新后的 System Prompt（双工具版本）
4. `AgentLoopExperiment2.java` — 实验入口
5. **10个问题的完整运行结果报告**（最重要的产出）
6. 编排决策汇总分析

## 我们最想从这个实验中学到什么

1. **模型的编排判断力** — 它能区分"需要重写"和"不需要重写"的场景吗？
2. **重写的投入产出比** — 重写步骤带来的检索质量提升，是否值得额外的一轮 LLM 调用？
3. **刁难问题的表现** — 在 pipeline 可能处理不好的场景下，Agent Loop 有没有结构性优势？
4. **工具描述的影响** — rewrite_query 的 description 写法是否影响模型的调用决策？如果模型该重写时不重写，或不该重写时乱重写，首先检查 description

这些数据将决定：loop 的价值是"等效替代 pipeline"还是"在特定场景下真正超越"。
