# CC Task 011：Agent 模式知识库路由信息增强

> **目标：** 让 Agent 模式能根据问题内容选择正确的知识库搜索，而不是只搜名字最像的那个
> **约束：** 复用意图树已有数据，最小化改动，不改 Pipeline 逻辑
> **产出：** `FEEDBACK/CC_Report_011_Agent_Routing_Enhancement.md`

---

## 前置阅读

**在开始任务之前，请按顺序阅读以下文件，建立背景理解：**

1. `COLLABORATION_PROTOCOL.md` — 了解协作模式和执行规范
2. `FEEDBACK/CC_Report_009_KnowledgeBase_Dynamic_Awareness.md` — 上一轮修复：知识库白名单动态化
3. `FEEDBACK/CC_Report_010_Agent_Routing_Intelligence_Investigation.md` — 本轮调查结果：Agent 路由失败的根因

Report 010 是本任务的直接输入，包含了所有定位事实。请务必完整阅读后再继续。

---

## 背景

### ragent 系统的两种问答模式

- **Pipeline 模式：** 固定流程，通过意图树（`t_intent_node`）做路由——LLM 根据节点的 description 和 examples 判断该搜哪个知识库，准确率高
- **Agent 模式：** 模型自主决策，通过工具调用搜索知识库——但模型决策的依据只有工具描述中的信息

### 问题

Agent 模式搜不对知识库。测试表明：

- 用户问"Agent Loop 的设计原则"，Agent 搜了 ragentdocs 5 次，从未尝试 explorationdocs（答案在后者中）
- 同样的问题走 Pipeline，意图树正确识别到 explorationdocs（score 0.92）

### 根因（Report 010 已确认）

Agent 的工具描述中，每个知识库只有一个极简的名字（如 `explorationdocs: Agent工程探索`），缺少内容描述和示例问题。模型无法据此判断"关于 Agent Loop 设计原则的问题应该搜 explorationdocs"。

而 Pipeline 的意图树中，同一个知识库有丰富的 description 和 examples——这些信息在 Agent 模式下完全未被利用。

---

## 设计思想

Agent 的上下文是每轮都携带的稀缺资源。我们不应该把所有路由信息一次性塞进去，而是**分层供给**：

| 层 | 位置 | 信息密度 | 生命周期 |
|----|------|---------|---------|
| 索引 | 工具描述 | 极简（每库一行） | 常驻每轮 |
| 详情 | system_info_query 返回值 | 丰富（描述+示例） | 按需获取 |
| 策略 | system prompt | 行为规则 | 常驻每轮 |

现在只有 4 个知识库，全量放进工具描述也不是问题。但这个分层设计是面向未来的——当知识库增长到 50 个时，索引层依然简短，详情层按需获取，不会撑爆上下文。

---

## 第一阶段：确认现状（先做这步，不改代码）

Report 010 的调查距今可能已有代码变动（Task 009 的修复等），请先确认以下内容：

### 1. 当前工具描述的实际内容

读取 `KnowledgeSearchWithRerankTool.java` 的 `getDescription()` 方法，确认 Task 009 修复后，模型看到的完整工具描述是什么。

### 2. 当前 system_info_query 的实际返回

读取 `SystemInfoTool.java`，确认 `query_type=knowledge_bases` 时返回的完整内容。

### 3. 当前 system prompt 的完整内容

读取 `AgentLoopService.java` 中的 SYSTEM_PROMPT，确认当前 prompt 全文。

### 4. 意图树中可用的路由信息

查看 Pipeline 如何使用意图树数据（IntentResolver / IntentClassifier 相关代码），确认：
- 从哪个 Mapper/Service 读取意图树节点？
- KB 类型节点包含哪些字段（description、examples、collectionName）？
- 如何复用这些查询逻辑？

**确认完成后，再进入第二阶段。如果发现现状与 Report 010 描述有重大差异，在 REPORT 中说明，不必勉强按原计划实施。**

---

## 第二阶段：实施

基于第一阶段确认的实际情况，增强 Agent 的路由信息。以下是三个改动方向，优先级从高到低：

### 改动 1：增强工具描述（索引层）

**目标：** 让模型在选择 collection 时，能看到每个知识库的简要内容说明，而不只是一个名字。

**方向：** 从意图树读取 KB 类型节点的 description，精简为一行（约 30-50 字），拼接到 `getDescription()` 的返回值中。

**约束：**
- 每个知识库的说明控制在一行。如果意图树 description 很长，提取关键词
- MCP 版本（`KnowledgeSearchWithRerankMCPExecutor.java`）同步改动
- 这是常驻信息，宁短勿长

### 改动 2：增强 system_info_query 返回内容（详情层）

**目标：** 当模型不确定该搜哪个库、主动调用 system_info_query 时，能拿到每个知识库的完整描述和示例问题。

**方向：** 在现有返回格式中补充 description 和 examples 字段。数据从意图树读取。

**约束：**
- 这是按需信息，可以详细
- 与改动 1 的数据应来自同一个源头（意图树），确保一致

### 改动 3：增强 System Prompt（策略层）

**目标：** 教模型"怎么选知识库"，而不是告诉它"有哪些知识库"（后者是改动 1 和 2 的事）。

**方向：** 在现有 system prompt 的使用规则中，补充路由策略。比如：
- 根据工具描述中的知识库说明选择，不要只看名字
- 搜索结果不理想时考虑换库
- 跨领域问题可以搜多个库
- 不确定时先用 system_info_query 查看详情

**约束：**
- 这是静态规则，不需要动态生成
- 保持精简（不超过 10 行），融入现有规则体系

---

## 验证

修复完成后，用以下问题测试（通过 Agent 模式）：

| 测试 | 问题 | 预期行为 |
|------|------|---------|
| 路由准确性 | "Agent Loop 的核心价值是什么" | 搜索 explorationdocs |
| 跨库能力 | "ragent 项目中用到了哪些 Agent Loop 的设计原则" | 搜索 ragentdocs 和 explorationdocs |
| 边界判断 | "Claude Code 的源码设计有什么值得学习的" | 搜索 explorationdocs |
| 回归 | "MCP 协议为什么不使用 HTTP" | 搜索 ragentdocs |

---

## 产出格式要求

```markdown
# CC Report 011：Agent 模式路由信息增强

## 一、现状确认

### 工具描述（改动前）
（贴出实际内容）

### system_info_query 返回（改动前）
（贴出实际内容）

### System Prompt（改动前）
（贴出完整内容）

### 意图树数据来源
- 使用的 Mapper/Service：
- KB 节点的 description 和 examples 样例：

## 二、变更清单

| 文件 | 变更类型 | 对应层 | 说明 |
|------|---------|-------|------|

## 三、各层实施详情

### 索引层：工具描述（改动后）
（贴出模型看到的完整文本）

### 详情层：system_info_query 返回（改动后）
（贴出完整内容）

### 策略层：System Prompt（改动后）
（贴出完整内容）

## 四、验证结果

（4 个测试问题的 Agent 执行报告摘要：搜了哪些库、结果质量）

## 五、补充说明

- 实施中的判断和取舍
- 发现的问题或改进建议
```

## 注意事项

- 这是一个新的执行会话，请先阅读前置文件建立背景
- 第一阶段确认现状后再动手，不要基于 Report 010 的内容假设代码没有变化
- 从意图树读取数据时，只取 `kind=KB` 的节点
- 参考 Pipeline 的 IntentClassifier/IntentResolver 如何使用意图树数据，尽量复用已有查询逻辑
- 如果发现比三层方案更好的实现方式，在 REPORT 中建议，可以直接采用
