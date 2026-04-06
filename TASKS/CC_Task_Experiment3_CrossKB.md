# CC 任务：实验3 — 跨知识库综合检索

## 背景

### 实验序列回顾

```
实验0: Agent Loop 骨架验证               → ✅ 循环能跑
实验1/1.5: 单工具 Agent + Rerank        → ✅ 模型能回答知识库问题
实验2: 多工具编排（rewrite + search）     → ✅ 模型能按需重写，编排决策合理
实验3: 跨知识库综合检索（本次）           → 🔬 模型能自主选择和综合多个知识库吗？
```

### 核心假设

**ragent 有三个知识库：**

| 知识库 | 内容 | 特点 |
|--------|------|------|
| `ragentdocs` | Ragent 项目技术文档 | 系统架构、实现细节、MCP、意图树等 |
| `ssddocs` | SSD 原论文理论 | 状态空间模型、理论推导、原始设计 |
| `dualssddocs` | DualSSD 创新 | 在 SSD 基础上的改进，核心是块级别状态管理 |

**当前 Pipeline 的做法：** 用意图树（LLM 分类器）判断用户问题属于哪个知识库，然后路由到对应的单一知识库检索。对于跨知识库的问题（比如"DualSSD 相比原始 SSD 改进了什么"），Pipeline 需要复杂的 if-else 逻辑来处理多库查询和结果合并。

**Agent Loop 的潜力：** 模型自主判断需要搜哪个库，搜完一个发现信息不够时自动搜另一个，最后综合多个来源生成回答。不需要预编程任何路由逻辑。

**统一逻辑：** 知识库选择是一个"不确定性环节"——用户的问题可能明确属于某个库，也可能跨库，甚至可能哪个库都搜不到。把这个不确定性交给模型运行时判断，而不是用代码预编程所有可能的路由。

### 可迁移范式验证

这个实验同时验证我们提炼的范式：
1. 识别不确定性环节（哪个知识库？搜几个？怎么综合？）
2. 暴露为工具（knowledge_search 的 collection 参数）
3. 观察模型决策
4. 根据观测调整

如果这个范式在跨库场景下依然有效，说明它不是 ragent 专属的技巧，而是一个通用方法论。

## 你需要做的事情

### 第一步：确认多知识库检索能力

**请验证：**

1. 当前的 `KnowledgeSearchWithRerankMCPExecutor`（或实验1.5中的检索工具）的 `collection` 参数是否真正生效？
   - 试着分别传 `ragentdocs`、`ssddocs`、`dualssddocs`，确认能搜到不同的内容
   - 如果 collection 参数没有接到后端，需要修复

2. 检查 `/api/ragent/retrieve/with-rerank` 端点是否支持 collection 参数
   - 如果不支持，需要修改端点让它接受 collection 参数并路由到对应的向量库

3. 确认三个知识库的实际 collection name（可能在配置文件或 Milvus 中）
   - 可能不叫 `ragentdocs`/`ssddocs`/`dualssddocs`，需要找到实际名称

**输出：** 确认哪些知识库可以被搜索，实际的 collection name 是什么。

### 第二步：确保工具定义中的 collection 参数正确

更新工具的 JSON Schema，确保 `collection` 参数的 `enum` 值和实际 collection name 一致：

```json
{
  "name": "knowledge_search_with_rerank",
  "description": "Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores. You can specify which knowledge base to search:\n- ragentdocs: Ragent project documentation (architecture, implementation, MCP tools, intent tree)\n- ssddocs: SSD (State Space Duality) original theory and paper content\n- dualssddocs: DualSSD innovation, improvements over SSD, block-level state management\n\nIf unsure which knowledge base to search, start with the most likely one based on the question topic. You can search multiple knowledge bases by calling this tool multiple times with different collection values.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "The search query. Be specific and use keywords relevant to the topic."
      },
      "collection": {
        "type": "string",
        "description": "Which knowledge base to search. Choose based on the question topic.",
        "enum": ["ragentdocs", "ssddocs", "dualssddocs"]
      }
    },
    "required": ["query", "collection"]
  }
}
```

**注意：**
- 把 `collection` 改为 **required**（之前是 optional）——这迫使模型每次搜索都显式选择知识库，我们能清楚观测它的路由决策
- description 中明确说明了每个知识库的内容特征，这是模型做路由决策的唯一线索
- 请根据实际的 collection name 调整 enum 值

### 第三步：更新 System Prompt

在实验2 的 System Prompt 基础上，调整知识库相关的指导：

```markdown
## 知识库说明

你可以搜索三个不同的知识库：

1. **ragentdocs** — Ragent 项目文档
   - 内容：系统架构、RAG 实现、MCP 工具、意图树、分块策略、前端技术栈等
   - 适用：关于 Ragent 系统本身的问题

2. **ssddocs** — SSD 理论文档
   - 内容：状态空间模型（State Space Model）、SSD 原论文理论、数学推导
   - 适用：关于 SSD 原始理论和方法的问题

3. **dualssddocs** — DualSSD 创新文档
   - 内容：DualSSD 的创新设计、块级别状态管理、对 SSD 的改进
   - 适用：关于 DualSSD 具体创新和实现的问题

## 决策流程

收到用户问题后：

1. **判断问题涉及哪个知识库（可能多个）**
   - 明确属于某一个 → 搜那一个
   - 涉及多个主题 → 分别搜索，综合结果
   - 不确定属于哪个 → 从最可能的开始，根据结果决定是否搜其他库

2. **搜索并评估结果**
   - 分数 > 0.85: 高度相关，可直接回答
   - 分数 0.70-0.85: 中等相关，可参考但可能不完整
   - 分数 < 0.70: 低相关，考虑换知识库或换查询角度

3. **需要时搜索多个知识库**
   - 对比类问题（"A 和 B 有什么区别"）→ 两边都搜
   - 综合类问题（"A 怎么应用于 B"）→ 两边都搜后综合
   - 搜了一个库发现信息不足 → 尝试另一个库

4. **回答时注明信息来源**
   - 告诉用户信息来自哪个知识库
   - 如果综合了多个知识库，分别说明

## 工具使用

你有以下工具：
- **rewrite_query**: 模糊问题先重写再搜索（和实验2相同）
- **knowledge_search_with_rerank**: 搜索指定知识库，每次调用指定一个 collection
```

### 第四步：创建实验入口

创建 `AgentLoopExperiment3.java`，注册两个工具（rewrite_query + knowledge_search_with_rerank），使用更新后的 System Prompt。

### 第五步：运行测试并记录结果

**测试问题分三组：**

**A组 — 单库问题（4个，每个明确属于某一个知识库）：**
```
Q1: "Ragent 系统的整体架构是什么？"
  → 预期：搜 ragentdocs
  
Q2: "SSD 模型的核心数学原理是什么？"
  → 预期：搜 ssddocs

Q3: "DualSSD 的块级别状态管理是怎么实现的？"
  → 预期：搜 dualssddocs

Q4: "意图树的节点是怎么配置的？"
  → 预期：搜 ragentdocs
```

**B组 — 跨库问题（4个，需要搜多个知识库才能完整回答）：**
```
Q5: "DualSSD 相比原始 SSD 改进了什么？"
  → 预期：搜 ssddocs（了解原始 SSD）+ 搜 dualssddocs（了解改进）→ 对比回答

Q6: "SSD 的理论在 DualSSD 中是怎么被应用的？"
  → 预期：搜 ssddocs（理论基础）+ 搜 dualssddocs（应用方式）→ 综合回答

Q7: "Ragent 系统中有没有用到状态空间模型的思想？"
  → 预期：可能先搜 ragentdocs，发现没有直接关联，可能再搜 ssddocs/dualssddocs 确认

Q8: "块级别状态和 SSD 原始的状态表示有什么区别？"
  → 预期：搜 dualssddocs（块级别状态）+ 搜 ssddocs（原始状态表示）→ 对比
```

**C组 — 边界/刁难问题（2个）：**
```
Q9: "这三个项目之间是什么关系？"
  → 预期：可能搜所有三个库，或基于已有信息推断

Q10: "状态空间模型最近有什么新进展？"
  → 预期：知识库可能没有最新信息，模型应诚实告知
```

**对每个问题，记录：**

```markdown
### Q[N]: [问题] (组别: A/B/C)

**Agent Loop 执行路径：**
- 总轮次: ?
- 工具调用链（重点关注 collection 选择）:
  [Turn 1] knowledge_search(query="...", collection="ragentdocs") → top1=?
  [Turn 2] knowledge_search(query="...", collection="ssddocs") → top1=?
  [Turn 3] (如有)
  ...
- 最终回答: (摘要)

**路由决策分析：**
- 模型选了哪个/哪些知识库？
- 选择顺序合理吗？
- 有没有不必要的搜索？（搜了不该搜的库）
- 有没有遗漏的搜索？（该搜的库没搜）

**跨库综合能力（B组专用）：**
- 模型是否搜了多个库？
- 综合回答的质量如何？是简单拼接还是真正的融合分析？
- 有没有标注信息来源？
```

### 第六步：汇总对比分析

```markdown
## 路由决策汇总

| 问题 | 组别 | 预期搜索库 | 实际搜索库 | 路由正确？ | 总轮次 |
|------|------|-----------|-----------|-----------|--------|
| Q1   | A    | ragentdocs | ?         | ?         | ?      |
| ...  |      |           |           |           |        |

## 跨库能力评估

| 问题 | 搜了几个库 | 综合质量(1-5) | 是否标注来源 |
|------|-----------|--------------|-------------|
| Q5   | ?         | ?            | ?           |
| ...  |           |              |             |

## 关键观察

1. A组：模型的知识库路由准确率如何？
2. B组：模型是否主动搜索多个库？综合质量如何？
3. C组：边界情况下模型的行为是否合理？
4. 整体：模型的路由决策和 ragent 意图树的路由相比，孰优孰劣？
5. 有没有超预期的行为？（比如模型主动发现了知识库之间的关联）
```

## 关键约束

1. **先确认多库检索真的能工作** — 第一步最重要。如果 collection 参数没有正确传递到 Milvus，后面所有实验都无法进行
2. **先跑一个 A 组 + 一个 B 组问题** — 确认单库路由和跨库检索都能工作，再跑全部
3. **collection 参数必须 required** — 我们需要显式观测模型的每一次路由决策
4. **日志中记录每次搜索的 collection 值** — 这是本次实验的核心观测数据
5. **不改意图树代码** — 我们不是要替换意图树，而是观察模型能否做出等效或更好的路由决策

## 预期产出

1. 多知识库检索能力验证报告
2. 更新后的工具定义和 System Prompt
3. `AgentLoopExperiment3.java`
4. **10个问题的完整运行结果报告**（最重要）
5. 路由决策和跨库综合能力的分析

## 我们最想从这个实验中学到什么

1. **模型的路由直觉** — 给它三个知识库的简短描述，它能正确选择吗？这直接关系到"意图树能否被 loop 吸收"
2. **跨库综合能力** — 这是 Pipeline 的硬伤场景。模型能否搜两个库然后做出融合分析？
3. **范式可迁移性** — 用同样的方法论（识别不确定性→暴露为工具→观察决策），能否快速解决一个新场景？
4. **确定性 vs 不确定性的边界** — 哪些路由决策模型做得好（该交给 loop），哪些做得不好（该留给代码/意图树）

这是到目前为止最接近"真实业务场景"的实验——用户确实会问跨领域的问题，而现有 Pipeline 处理这类问题的代码复杂度很高。如果 Agent Loop 能优雅地处理，这就是范式迁移的最强证据。
