# CC Report 010：Agent 模式知识库路由信息调查

## 一、Agent System Prompt 完整内容

**来源：** `AgentLoopService.java` 第78-92行

```
你是一个知识库问答助手。你有以下工具可用：
- knowledge_search_with_rerank：在知识库中搜索相关内容
- system_info_query：查询系统元信息（知识库列表、系统能力、领域分类等）

使用规则：
1. 收到用户问题后，先判断问题类型：
   - 如果用户询问"系统有哪些知识库"、"系统能做什么"等元信息问题 → 使用 system_info_query
   - 如果用户询问知识库中的具体内容 → 使用 knowledge_search_with_rerank
2. 搜索结果中，rerank_score > 0.85 表示高度相关，< 0.75 表示低相关
3. 如果搜索结果质量不够，可以换关键词重新搜索
4. 基于搜索结果回答时，引用具体内容
5. 如果知识库中确实没有相关信息，诚实告知
6. 对于问候语（如"你好"、"hello"），可以直接回答，无需调用工具
```

**关于知识库的信息：**
- ❌ 未列出具体知识库名称
- ❌ 未说明各知识库的适用场景
- ✅ 只笼统说明"有搜索工具可用"

---

## 二、工具定义详情

### knowledge_search_with_rerank

**getDescription() 返回值（Task 009 修复后动态生成）：**

```
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

Available knowledge bases:
- ragentdocs: Ragent 项目文档
- ssddocs: SSD 理论文档
- dualssddocs: DualSSD 创新文档
- explorationdocs: Agent工程探索

If unsure which knowledge base to search, start with the most likely one based on the question topic.
You can search multiple knowledge bases by calling this tool multiple times with different collection values.
```

**getInputSchema() 输出：**

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "The search query. Be specific and use keywords relevant to the topic."
    },
    "collection": {
      "type": "string",
      "enum": ["ragentdocs", "ssddocs", "dualssddocs", "explorationdocs"],
      "description": "Which knowledge base to search. Choose based on the question topic."
    },
    "top_k": {
      "type": "integer",
      "description": "Maximum number of document chunks to return, default 5",
      "default": 5
    }
  },
  "required": ["query", "collection"]
}
```

**模型看到的完整工具 JSON（还原）：**

```json
{
  "type": "function",
  "function": {
    "name": "knowledge_search_with_rerank",
    "description": "Search a specific knowledge base... Available knowledge bases:\n- ragentdocs: Ragent 项目文档\n- ssddocs: SSD 理论文档\n- dualssddocs: DualSSD 创新文档\n- explorationdocs: Agent工程探索\n...",
    "parameters": {
      "type": "object",
      "properties": {
        "query": { "type": "string", "description": "..." },
        "collection": {
          "type": "string",
          "enum": ["ragentdocs", "ssddocs", "dualssddocs", "explorationdocs"],
          "description": "Which knowledge base to search. Choose based on the question topic."
        },
        "top_k": { "type": "integer", "default": 5 }
      },
      "required": ["query", "collection"]
    }
  }
}
```

### system_info_query

**query_type=knowledge_bases 时的返回内容：**

```
【知识库列表】共 4 个知识库：

1. Ragent 项目文档
   - ID: 1
   - 向量集合: ragentdocs
   - 嵌入模型: qwen3-embedding:8b-fp16

2. SSD 理论文档
   - ID: 2
   - 向量集合: ssddocs
   - 嵌入模型: qwen3-embedding:8b-fp16

3. DualSSD 创新文档
   - ID: 3
   - 向量集合: dualssddocs
   - 嵌入模型: qwen3-embedding:8b-fp16

4. Agent工程探索
   - ID: 4
   - 向量集合: explorationdocs
   - 嵌入模型: qwen3-embedding:8b-fp16
```

**信息粒度分析：**
- ✅ 有知识库名称和 collection 名称
- ❌ **缺失**：每个知识库的描述/适用场景（如"Agent Loop 设计原则"适合哪个库）
- ❌ **缺失**：示例问题（如"Claude Code 源码分析"应该搜哪个库）

---

## 三、Pipeline 意图树信息

### IntentNodeDO 数据库字段（`t_intent_node` 表）

| 字段 | 含义 | 示例 |
|------|------|------|
| `intentCode` | 节点唯一标识 | `exploration-agentloop` |
| `name` | 展示名称 | Agent工程探索 |
| `description` | 语义说明 | Agent Loop 设计原则、CC源码分析 |
| `examples` | 示例问题（JSON数组） | ["Agent Loop 核心价值是什么", "Claude Code 怎么实现 Agent Loop"] |
| `collectionName` | Milvus Collection | `explorationdocs` |
| `kind` | 类型（KB/MCP/SYSTEM） | 0 (KB) |
| `level` | 层级 | 2 (TOPIC) |
| `parentCode` | 父节点 | `domain-exploration` |

### Pipeline 意图分类时 LLM 看到的信息（`buildPrompt` 方法生成）

```
- id=exploration-agentloop
  path=Agent工程探索 > Agent Loop 设计
  description=Agent Loop 设计原则、CC源码分析
  type=KB
  examples=Agent Loop 核心价值是什么 / Claude Code 怎么实现 Agent Loop
```

**意图分类 prompt 还包含：**
- 分数评分标准（0.4-0.8 中等相关，>0.8 强匹配）
- 匹配规则（实体导向 vs 主题导向）
- 输出格式要求（JSON 数组）

### Agent 模式未利用的字段

| 字段 | Pipeline 用途 | Agent 模式状态 |
|------|-------------|--------------|
| `description` | 告诉 LLM 这个节点的知识范围 | ❌ **完全缺失** |
| `examples` | 帮助模型理解典型问题 | ❌ **完全缺失** |
| `fullPath`（路径）| 提供层级上下文 | ❌ **完全缺失** |
| `kind` | 区分 KB/MCP/SYSTEM | ❌ Agent 不区分 |
| `collectionName` | 最终检索的目标库 | ✅ 只给名称，不给语义 |

---

## 四、信息差距分析

| 信息维度 | Pipeline 有的 | Agent 有的 | 差距 |
|---------|-------------|-----------|------|
| 知识库名称 | ✅ collectionName | ✅ enum 列表 | 无 |
| 知识库语义描述 | ✅ description 字段 | ❌ 只有 name | **严重缺失** |
| 示例问题 | ✅ examples 字段 | ❌ 无 | **严重缺失** |
| 层级路径 | ✅ fullPath | ❌ 无 | 中等缺失 |
| 适用场景说明 | ✅ 通过 description 间接表达 | ❌ 无 | **严重缺失** |
| 意图分类 prompt | ✅ 详细的评分规则和匹配规则 | ❌ 只有笼统描述 | **严重缺失** |

---

## 五、根因判断

### Agent 模式路由失败的根本原因

**信息缺失是根本原因。** 具体表现：

1. **模型看到的信息不足以做决策**
   - 工具描述只列出 `collectionName: name` 格式
   - 例如 `explorationdocs: Agent工程探索` — 模型不知道这个库包含"CC源码分析"内容
   - 当用户问"Claude Code 源码"时，模型无法判断应该搜 explorationdocs 还是 ragentdocs

2. **SystemInfoTool 返回的内容也不够**
   - 虽然返回了知识库列表，但只有 ID、名称、collectionName
   - 模型调用 system_info_query 后拿到的是技术元数据，不是语义说明

3. **意图树的丰富信息被完全浪费**
   - Pipeline 模式有 `description`、`examples`、`fullPath` 等丰富语义信息
   - Agent 模式完全没有利用这些信息

4. **不是格式问题，是内容问题**
   - Task 009 的动态化修复已经让工具描述包含知识库名称
   - 但只有名称不足以让模型理解"搜什么问题用哪个库"

---

## 六、修复方向建议

基于调查结果，建议**最小化修复方向**：

### 方案 A：增强工具描述（推荐）

**改动点：** `KnowledgeSearchWithRerankTool.getDescription()` 和 `SystemInfoTool.getKnowledgeBasesInfo()`

**思路：** 从意图树（`t_intent_node` 表）读取每个知识库对应的语义信息，动态拼接到工具描述中。

**预期效果：** 模型看到类似：
```
Available knowledge bases:
- ragentdocs: Ragent 项目文档 (系统架构、RAG实现、MCP工具)
  Suitable for: "系统架构是什么"、"MCP怎么配置"
- explorationdocs: Agent工程探索 (Agent Loop设计原则、CC源码分析)
  Suitable for: "Agent Loop核心价值"、"Claude Code实现原理"
```

**优点：**
- 改动范围小（只改两个 Tool 类）
- 复用意图树已有数据
- 与 Pipeline 共享同一信息源

### 方案 B：动态注入意图树 prompt（更彻底）

**改动点：** `AgentLoopService.SYSTEM_PROMPT` 或 AgentLoop 初始化逻辑

**思路：** 在 Agent 启动时，从意图树读取所有 KB 类型叶子节点，拼接成路由指南注入 System Prompt。

**预期效果：** System Prompt 包含完整的路由规则（类似 Pipeline 的 intent-classifier.st）。

**优点：**
- 信息最完整
- 与 Pipeline 逻辑一致

**缺点：**
- System Prompt 变长（可能影响 token 消耗）
- 需要维护 prompt 模板

### 方案 C：调用意图分类器后再搜索（复杂）

**改动点：** AgentLoop 增加"意图预分类"步骤

**思路：** Agent 收到问题后，先调用 `IntentResolver.resolve()`，拿到 collectionName 再调用搜索工具。

**缺点：**
- 改动复杂度高
- 可能增加响应延迟
- 不符合 Agent"自主决策"的设计理念

---

## 七、结论

Agent 模式路由失败的根本原因是**信息缺失**，不是 Task 009 的动态化修复不够好，而是动态化后只提供了名称，没有提供语义信息。

**最小化修复建议：** 采用方案 A，从意图树读取 `description` 和 `examples`，增强工具描述，让模型看到"每个知识库适合搜什么问题"。