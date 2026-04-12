# CC Task 011：Agent 模式知识库路由信息增强

> **目标：** 构建 Agent 模式的分层信息供给体系，让模型具备足够的路由判断能力，能根据问题内容选择正确的知识库
> **约束：** 复用意图树（`t_intent_node`）已有数据；三层改动各自独立，按顺序实施；不改 Pipeline 逻辑
> **产出：** `FEEDBACK/CC_Report_011_Agent_Routing_Enhancement.md`

---

## 设计思想

Agent 的上下文是每轮都携带的稀缺资源。路由信息的供给应该分层，而非一次性全量注入：

- **第一层（索引）：** 始终可见，极简——工具描述中每个知识库一句话定位
- **第二层（详情）：** 按需获取，丰富——system_info_query 返回完整描述和示例
- **第三层（策略）：** system prompt 中的行为引导——教模型"怎么选"，而非"有什么"

三层各司其职，信息密度递减，上下文开销可控。

---

## 背景

### Report 010 的关键发现

Agent 模式路由失败的根因是**信息缺失**：

1. **工具描述**只有 `collectionName: name` 格式（如 `explorationdocs: Agent工程探索`），缺少语义说明
2. **system_info_query** 返回技术元数据（ID、embedding 模型），不含内容描述和示例
3. **System Prompt** 未提供任何路由策略引导
4. **意图树**中有丰富的 `description` 和 `examples` 字段，但在 Agent 模式下完全未利用

### 数据来源

路由信息应从意图树（`t_intent_node`）读取，而非 `t_knowledge_base`。意图树包含：
- `description`：知识库内容的语义描述
- `examples`：示例问题（JSON 数组）
- `collectionName`：对应的向量集合
- `name`：展示名称

具体读取方式参考 Pipeline 的 `IntentResolver`（或相关 Service/Mapper），它已经在用这些字段。

---

## 实施步骤

### 第一层：增强工具描述（索引）

**改动文件：** `KnowledgeSearchWithRerankTool.java`（bootstrap 版本）

**目标：** `getDescription()` 返回的知识库列表，从"只有名字"升级为"名字 + 一句话定位"。

**数据来源：** 从意图树读取 KB 类型叶节点的 `name` 和 `description`。由于 description 可能很长，这里**只取精简摘要**（第一句话，或手动截取前 50 字），不是全文。

**预期效果：** 模型看到的工具描述类似：

```
Available knowledge bases:
- ragentdocs: RAG系统架构设计、向量检索、MCP协议、部署配置等技术实现
- explorationdocs: Agent Loop设计原则、CC源码分析、Harness工程方法论
- ssddocs: SSD蛋白质模型理论
- dualssddocs: DualSSD创新方法
```

**关键约束：**
- 每个知识库的描述控制在一行以内（约 30-50 字）
- 如果意图树的 description 过长，取核心关键词组织为一行
- MCP 版本（`KnowledgeSearchWithRerankMCPExecutor.java`）同步改动

**注意：** `getInputSchema()` 中 collection 的 enum 列表保持不变（Task 009 已做好动态化），不需要在 schema 的 description 中重复写知识库说明。

### 第二层：增强 system_info_query 返回内容（详情）

**改动文件：** `SystemInfoTool.java`

**目标：** 当 `query_type=knowledge_bases` 时，返回内容从纯技术元数据升级为包含**语义描述和示例问题**。

**数据来源：** 同样从意图树读取 `description` 和 `examples`。

**预期效果：** 模型调用 system_info_query 后看到：

```
【知识库列表】共 4 个知识库：

1. Ragent 项目文档 (ragentdocs)
   描述：RAG系统架构设计与模块拆解、向量数据库选型（Milvus）、文档解析与分块策略、查询重写与语义增强、意图识别与多路由调度、MCP协议设计与实现...
   适合问题：MCP协议为什么不使用HTTP、向量数据库为什么选择Milvus、SSE流式响应是如何实现的

2. Agent工程探索 (explorationdocs)
   描述：Agent Loop 实验笔记、CC 源码设计模式提取、Pipeline vs Loop 架构对比、上下文压缩策略、Harness 三层壳演进、Codex 团队工作方法...
   适合问题：Agent Loop 核心价值是什么、Pipeline 和 Loop 的本质区别、CC 源码中提取了哪些设计原则

...
```

**关键约束：**
- description 和 examples 可以完整展示（因为这是按需获取，不是常驻上下文）
- 这里要比第一层丰富得多，让模型在不确定时有足够信息做判断
- 如果 SystemInfoTool 当前没有注入意图树相关的 Mapper/Service，需要补充依赖

### 第三层：增强 System Prompt（策略）

**改动文件：** `AgentLoopService.java`

**目标：** 在 system prompt 中增加路由策略引导。不列知识库清单（那是第一层的事），而是教模型怎么做决策。

**预期效果：** system prompt 增加类似以下内容（具体措辞 CC 可根据实际调整）：

```
知识库选择策略：
- 工具描述中列出了每个知识库的简要说明，根据问题内容选择最匹配的知识库
- 如果不确定该搜哪个库，先调用 system_info_query 查看知识库详细描述和示例问题
- 如果在一个知识库中搜索结果质量不理想（rerank_score < 0.75），考虑换一个知识库重试
- 如果问题涉及多个领域（如同时涉及系统实现和设计原则），可以分别搜索多个知识库后综合回答
- 优先根据工具描述中的知识库说明做判断，不要仅根据知识库名称猜测内容范围
```

**关键约束：**
- 这段内容是**静态的策略规则**，不需要动态生成（策略本身不随知识库变化）
- 保持精简，不要超过 10 行，避免占用过多 system prompt 空间
- 融入现有 prompt 的规则体系中（现有的 6 条规则基础上补充）

---

## 验证

修复完成后，用以下测试问题验证路由准确性：

### 测试 1：明确属于 explorationdocs 的问题
- "Agent Loop 的核心价值是什么"
- **预期：** Agent 搜索 explorationdocs

### 测试 2：跨库问题
- "ragent 项目中用到了哪些 Agent Loop 的设计原则"
- **预期：** Agent 搜索 ragentdocs 和 explorationdocs（不限顺序）

### 测试 3：模糊问题
- "Claude Code 的源码设计有什么值得学习的"
- **预期：** Agent 搜索 explorationdocs（不是 ragentdocs）

### 测试 4：回归
- "MCP 协议为什么不使用 HTTP"
- **预期：** Agent 搜索 ragentdocs（不受改动影响）

---

## 产出格式要求

```markdown
# CC Report 011：Agent 模式路由信息增强

## 一、变更清单

| 文件 | 变更类型 | 对应层 | 说明 |
|------|---------|-------|------|

## 二、各层实施详情

### 第一层：工具描述
- 数据来源确认：（从哪个 Mapper/Service 读取的）
- 实际生成的工具描述：（贴出模型看到的完整文本）
- 如果 description 做了截取，说明截取规则

### 第二层：system_info_query
- 依赖注入变更：（如果有）
- 实际返回的完整内容：（贴出）

### 第三层：System Prompt
- 新增的策略文本：（贴出）
- 与现有规则的整合方式

## 三、验证结果

（4 个测试问题的实际 Agent 行为和结果）

## 四、补充说明

- 实施中遇到的问题和处理方式
- 对设计的改进建议（如果有）
```

## 注意事项

- 三层改动按顺序实施，每层完成后可以独立验证
- 第一层的知识库描述要**精简**，这是常驻信息，宁短勿长
- 第二层的返回内容可以**详细**，这是按需信息，丰富更好
- 从意图树读取数据时，注意只取 `kind=KB` 的节点（不含 MCP 和 SYSTEM 类型）
- 如果意图树中有多级结构（DOMAIN → TOPIC），取叶子节点的信息最有价值；如果 DOMAIN 级节点的描述已经足够，取 DOMAIN 级即可——以实际数据为准
- 参考 Pipeline 中 IntentResolver（或 IntentClassifier）如何读取和使用意图树数据，尽量复用已有的查询逻辑
