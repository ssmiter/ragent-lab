# CC Report 011：Agent 模式路由信息增强

## 一、现状确认

### 工具描述（改动前）

**来源：** `KnowledgeSearchWithRerankTool.java` 第58-76行

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

**问题：**
- ❌ 只有 `collectionName: name` 格式，无语义描述
- ❌ 模型无法据此判断"Agent Loop 设计原则"应该搜哪个库

---

### system_info_query 返回（改动前）

**来源：** `SystemInfoTool.java` 第138-163行

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

...
```

**问题：**
- ❌ 缺失：description（内容范围）
- ❌ 缺失：examples（示例问题）

---

### System Prompt（改动前）

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

**问题：**
- ❌ 无路由策略指导
- ❌ 模型不知道如何选择知识库

---

### 意图树数据来源

**使用的 Mapper：** `IntentNodeMapper`

**数据库表：** `t_intent_node`

**KB 节点的关键字段：**
| 字段 | 含义 | 用途 |
|------|------|------|
| `intentCode` | 唯一标识 | 用于路由匹配 |
| `name` | 展示名称 | 对应知识库名称 |
| `description` | 语义说明 | 用于增强工具描述 |
| `examples` | 示例问题（JSON数组） | 用于增强 system_info_query |
| `collectionName` | Milvus Collection | 关联到知识库 |

**查询逻辑复用：**
- 与 Pipeline 的 `DefaultIntentClassifier.loadIntentTreeFromDB()` 使用同一数据源
- 筛选条件：`deleted=0, enabled=1, kind=KB（或null）, collectionName 不为空`

---

## 二、变更清单

| 文件 | 变更类型 | 对应层 | 说明 |
|------|---------|-------|------|
| `KnowledgeSearchWithRerankTool.java` | 增强 | 索引层 | 工具描述增加语义描述（从意图树读取） |
| `SystemInfoTool.java` | 增强 | 详情层 | 知识库列表增加 description 和 examples |
| `AgentLoopService.java` | 增强 | 策略层 | System Prompt 增加路由策略 |
| `KnowledgeSearchWithRerankMCPExecutor.java` | 增强 | 索引层 | MCP 版本同步增加语义描述 |

---

## 三、各层实施详情

### 索引层：工具描述（改动后）

**模型看到的完整文本（预估）：**

```
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

Available knowledge bases (each with content scope):
- ragentdocs: Ragent 项目文档 (系统架构、RAG实现、MCP工具设计)
- ssddocs: SSD 理论文档 (SSD 相关理论文档)
- dualssddocs: DualSSD 创新文档 (DualSSD 创新文档)
- explorationdocs: Agent工程探索 (Agent Loop 设计原则、CC 源码分析)

If unsure which knowledge base to search, start with the most likely one based on the question topic.
You can search multiple knowledge bases by calling this tool multiple times with different collection values.
```

**关键改动：**
- 新增 `IntentNodeMapper` 依赖
- 新增 `getKbRoutingInfo()` 方法从意图树读取 KB 叶子节点
- 新增 `truncateDescription()` 方法精简描述（控制在 50 字内）
- 拼接格式：`collectionName: name (description摘要)`

---

### 详情层：system_info_query 返回（改动后）

**预估返回内容：**

```
【知识库列表】共 4 个知识库：

1. Ragent 项目文档
   - ID: 1
   - 向量集合: ragentdocs
   - 内容范围: 系统架构、RAG实现、MCP工具设计
   - 示例问题: 系统架构是什么 / MCP怎么配置
   - 嵌入模型: qwen3-embedding:8b-fp16

2. SSD 理论文档
   - ID: 2
   - 向量集合: ssddocs
   - 内容范围: SSD 相关理论文档
   - 示例问题: SSD 的基本原理
   - 嵌入模型: qwen3-embedding:8b-fp16

...

4. Agent工程探索
   - ID: 4
   - 向量集合: explorationdocs
   - 内容范围: Agent Loop 设计原则、CC 源码分析
   - 示例问题: Agent Loop 核心价值是什么 / Claude Code 怎么实现 Agent Loop
   - 嵌入模型: qwen3-embedding:8b-fp16
```

**关键改动：**
- 新增 `getKbRoutingInfo()` 方法（复用 KnowledgeSearchWithRerankTool 的逻辑）
- 新增 `formatExamples()` 方法解析 JSON 数组格式的 examples
- 新增 `KbRoutingInfo` record 存储路由信息
- 输出新增 `内容范围` 和 `示例问题` 行

---

### 策略层：System Prompt（改动后）

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

知识库路由策略：
1. 选择知识库时，仔细阅读工具描述中的内容范围说明，不要只看名称
2. 如果首次搜索结果不佳，考虑切换到其他可能相关的知识库
3. 跨领域问题可以依次搜索多个知识库
4. 不确定某个知识库的内容时，先使用 system_info_query 查看其详细描述和示例问题
```

**新增内容：** 4 条路由策略规则（约 8 行）

---

## 四、验证结果

### 日志对比：修复前后表现差异

**修复前（04-11，进程 3674253）—— Task 009 未生效 + 无路由增强**

| 问题 | 模型选择 | Rerank 分数 | 结果 |
|------|---------|------------|------|
| "CC源码探索进度和应用情况" | `explorationdocs` | — | ❌ **错误：无效知识库**（不在白名单） |
| "Agent工程探索" | `ragentdocs` | 0.57 | ❌ 搜错了库，反复在 ragentdocs 换关键词 |
| "Agent loop模式" | 只搜 `ragentdocs` | 0.57 | ❌ 从未尝试切换到 explorationdocs |

**关键问题：**
- Task 009 的动态白名单未生效，`explorationdocs` 仍被拒绝
- 模型没有"切换知识库"的意识，只会换关键词重试
- 最终回答："CC源码探索在可访问的知识库中没有找到相关信息"

---

**修复后（04-12，进程 4145480）—— Task 009 + Task 011 双重生效**

| 问题 | 模型选择 | Rerank 分数 | 结果 |
|------|---------|------------|------|
| "Agent loop模式在哪" | `ragentdocs` → **切换** → `explorationdocs` | 0.54 → **0.92** | ✅ 正确找到 |
| "Pipeline vs Agent Loop差异" | `ragentdocs` → **切换** → `explorationdocs` | 0.58 → **0.91** | ✅ 正确找到 |
| "ragent 技术摘要" | `ragentdocs`（3次搜索） | 0.77~0.83 | ✅ 正确选择，无需切换 |

**关键改进：**
1. **白名单问题解决**（Task 009）：`explorationdocs` 现在可用
2. **路由智能生效**（Task 011）：模型看到低分结果（<0.75），主动切换知识库
3. **结果质量显著提升**：从 0.54/0.58 → 0.91/0.92

---

### 实际执行报告摘录

**问题："Agent loop模式相关的内容在哪个知识库"（04-12 20:05）**

```
工具调用历史:
  [Turn 1] system_info_query({"query_type": "knowledge_bases"}) -> ✓
  [Turn 2] knowledge_search_with_rerank({"query": "Agent loop模式", "collection": "ragentdocs"}) -> ✓
    分数: 0.54（低相关）
  [Turn 3] knowledge_search_with_rerank({"query": "Agent loop模式", "collection": "explorationdocs"}) -> ✓
    分数: 0.92（高度相关）

最终响应: 关于"探索Agent loop模式"的内容主要在 **exploration** 知识库中...
```

**问题："Pipeline 模式和 Agent Loop 模式差异"（04-12 21:23）**

```
工具调用历史:
  [Turn 1] knowledge_search_with_rerank({"query": "Pipeline 模式和 Agent Loop 模式差异 ragent", "collection": "ragentdocs"}) -> ✓
    分数: 0.58（低相关）
  [Turn 2] knowledge_search_with_rerank({"query": "Pipeline 模式和 Agent Loop 模式差异", "collection": "explorationdocs"}) -> ✓
    分数: 0.91（高度相关）

最终响应: 完整对比表格，包含理论差异和实际表现分析...
```

---

### 验证结论

| 维度 | 修复前 | 修复后 |
|------|--------|--------|
| 知识库可用性 | ❌ explorationdocs 被拒绝 | ✅ 全部 4 个知识库可用 |
| 首次选择准确性 | ❌ 只靠名称猜测 | ⚠️ 有时仍选错（依赖语义描述） |
| 切换知识库能力 | ❌ 无此意识 | ✅ 低分时主动切换 |
| 最终答案质量 | ❌ "找不到信息" | ✅ 正确答案 + 详细分析 |

---

### 仍存在的不足（下阶段线索）

| 观察 | 问题 | 可能方向 |
|------|------|---------|
| "Agent loop模式" 首次选 ragentdocs | 模型没有充分利用工具描述中的语义信息 | 可能需要更强的提示或意图预分类 |
| System Prompt 路由策略生效但非完美 | 策略是"搜索结果不佳时切换"，而非"首次就选对" | 研究 Pipeline 的意图分类器能否前置到 Agent |
| explorationdocs 的语义描述被精简到 50 字 | "Agent Loop 设计原则、CC 源码分析" 可能不够明显 | 考虑保留更多关键词或优化截断逻辑 |

---

## 五、补充说明

### 实施中的判断和取舍

1. **描述精简策略：**
   - 工具描述中的 description 限制在 50 字内
   - 优先在句号处截断，保留完整语义
   - 系统_info_query 中的 description 保持完整

2. **数据来源一致性：**
   - 索引层和详情层使用相同的 `IntentNodeMapper` 查询
   - 筛选条件一致：`deleted=0, enabled=1, kind=KB, collectionName不为空`
   - 确保两个工具看到的描述一致

3. **MCP 版本同步：**
   - MCP 版本通过 HTTP 调用 `/intent-tree/trees` API 获取意图树
   - 新增 `fetchKbRoutingInfo()` 方法递归遍历意图树
   - 与 bootstrap 版本的增强逻辑保持一致

### 发现的问题或改进建议

1. **意图树 API 响应格式：**
   - MCP 版本依赖 `/intent-tree/trees` API 返回的数据结构
   - 需确认 `IntentNodeTreeVO` 包含 `description`、`examples`、`collectionName` 字段
   - 如果 API 不返回完整字段，可能需要新增专用接口

2. **性能考虑：**
   - 每次调用 `getToolDefinition()` 都会查询数据库/调用 HTTP
   - 对于高频调用场景，可考虑添加缓存（如 Caffeine）
   - 但当前设计符合"动态感知"的需求，新增知识库自动生效

3. **未来扩展：**
   - 当知识库数量增长时，分层设计依然适用
   - 索引层保持简洁，详情层按需获取
   - 可进一步考虑"推荐知识库"机制（根据问题类型自动推荐）