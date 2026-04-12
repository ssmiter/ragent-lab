# CC Task 010：Agent 模式知识库路由信息缺失调查

> **目标：** 搞清楚 Agent 模式下，模型选择搜索哪个知识库的决策依据是什么，与 Pipeline 意图树的路由能力差距在哪里，为后续补齐 Agent 的路由信息提供事实依据
> **约束：** 只读不改，纯调查
> **产出：** `FEEDBACK/CC_Report_010_Agent_Routing_Intelligence_Investigation.md`

---

## 背景

### 问题现象

Task 009 修复知识库白名单动态化后，Agent 可以识别所有知识库名称，但**不知道该搜哪个库**。测试表明：

**测试 1：** 用户问"ragent 项目中用到了哪些 Agent Loop 的设计原则"，Agent 搜了 ragentdocs 5 次，从未尝试 explorationdocs（设计原则内容在 explorationdocs 中）。

**测试 2：** 同类问题走 Pipeline 时，意图树正确识别到 explorationdocs（score 0.92）。

**测试 3：** 用户问"对比 ragent 的 Agent Loop 实现和 Claude Code 的实现"，Agent 搜了 ragentdocs 6 次，打满轮次限制，从未尝试 explorationdocs（Claude Code 源码分析内容全在 explorationdocs 里）。

### 核心问题

Pipeline 模式有意图树提供丰富的路由信息（知识库描述、示例问题、分数排序）。Agent 模式下，模型选择 collection 的依据只有 system prompt 和工具描述。当前这些信息是否足够让模型做出正确的路由决策？

---

## 调查内容

### 1. Agent System Prompt 中的知识库信息

**文件：** `AgentLoopService.java`

需要确认：
- 当前 system prompt 的完整内容（特别是关于知识库的部分）
- prompt 中是否提到了各知识库的名称、描述、适用场景？
- 还是只笼统地说"你有搜索工具可以用"？

### 2. knowledge_search_with_rerank 工具的完整定义

**文件：** `KnowledgeSearchWithRerankTool.java`

Task 009 修复后，这个工具的定义变成了动态生成。需要确认：
- `getDescription()` 当前返回什么内容？是否包含各知识库的说明？
- `getInputSchema()` 中 collection 参数的描述是什么？只有 enum 值列表，还是每个值都有说明？
- **关键：** 请构造一个实际的工具定义输出（即模型在对话中看到的完整工具 JSON），贴在报告中

### 3. system_info_query 工具的返回内容

**文件：** `SystemInfoTool.java`

需要确认：
- 当 query_type=knowledge_bases 时，实际返回给模型的完整内容是什么？
- 是否包含每个知识库的描述、collection 名称、适用场景？
- 信息粒度是否足够让模型判断"这个问题应该搜哪个库"？

### 4. Pipeline 意图树的信息对比

**文件：** IntentResolver 相关代码，`t_intent_node` 表结构

需要确认：
- 意图树节点中存储了哪些字段？（name、description、examples、collectionName 等）
- 这些信息中，哪些在 Agent 模式下完全没被利用？

### 5. 模型实际看到的完整上下文

这是最关键的调查项。请从代码中追踪，当一个 Agent 会话开始时，发送给 LLM 的第一个请求中包含：
- system prompt 全文
- tools 定义列表（每个工具的完整 JSON）
- 用户消息

把这三部分的**实际内容**（不是代码，是运行时的实际值）尽可能完整地还原出来。如果有日志可以直接提取更好（检查 `log/app1.log` 中是否有发送给 LLM 的请求日志）。

---

## 产出格式要求

```markdown
# CC Report 010：Agent 模式知识库路由信息调查

## 一、Agent System Prompt 完整内容

（贴出 prompt 全文，标注关于知识库的部分）

## 二、工具定义详情

### knowledge_search_with_rerank
- getDescription() 返回值：
- getInputSchema() 完整输出：
- 模型看到的工具 JSON（还原或从日志提取）：

### system_info_query
- query_type=knowledge_bases 时的返回内容：

## 三、Pipeline 意图树信息

- 意图节点字段列表：
- Agent 模式未利用的字段：

## 四、信息差距分析

| 信息维度 | Pipeline 有的 | Agent 有的 | 差距 |
|---------|-------------|-----------|------|
| 知识库名称 | | | |
| 知识库描述 | | | |
| 示例问题 | | | |
| 适用场景 | | | |
| ... | | | |

## 五、根因判断

Agent 模式路由失败的根本原因是什么？（信息缺失 / 信息存在但格式不佳 / 其他）

## 六、修复方向建议

基于调查结果，CC 认为最小化的修复方向是什么？（只给建议，不实施）
```

## 注意事项

- 这次纯调查，不要改任何代码
- 重点是还原模型实际看到的信息，而不是代码逻辑。我们需要"站在模型的视角"看它拿到了什么，才能理解它为什么做出错误选择
- 如果 `log/app1.log` 中有发送给 LLM 的请求日志（包含 system prompt 和 tools），直接从日志提取是最准确的
- 如果发现 Task 009 的动态化修复已经把知识库描述放进了工具定义，但模型仍然不会用，那问题就不在信息缺失，而在信息呈现方式——这也是重要发现
