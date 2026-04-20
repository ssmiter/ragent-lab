# CC Report 001：README写作调研报告

## 一、完整链路图（"介绍一下你自己" Case）

```
用户请求 "介绍一下你自己"
    │
    ▼
SmartChatController (/smart/chat)
    │ 第 80-81 行：记录请求
    │
    ▼
StrategyRouter.routeAndExecute()
    │ 第 105-106 行：规则决策，选 Pipeline（问题短，无复杂关键词）
    │
    ▼
PipelineStrategy.execute()
    │ 第 100-101 行：执行 Pipeline
    │ 第 113-114 行：意图分类 → IntentResolver.resolve()
    │
    ▼
意图分类结果：SYSTEM 类型（"通用对话"，score=0.95）
    │ 意图树节点 kind="SYSTEM"，examples 含 "帮我介绍一下你自己"
    │
    ▼
PipelineStrategy 检测 allSystemOnly=true
    │ 第 116-124 行：检测所有子意图都是 SYSTEM → 抛出 StrategyHandoffException
    │
    ▼
StrategyRouter 捕获异常 → 转交 AgentStrategy
    │ 第 118-133 行：executeWithHandoffHandling()
    │
    ▼
AgentStrategy.execute() → AgentLoopService.runAgent()
    │ 注册工具：[knowledge_search_with_rerank, system_info_query]
    │
    ▼
AgentLoop.run()
    │ Turn 1：模型调用 system_info_query({"query_type": "all"})
    │
    ▼
SystemInfoTool.execute()
    │ 从数据库查询真实数据：知识库列表(4个)、意图树结构、系统能力说明
    │ 第 139-184 行：getKnowledgeBasesInfo() 从 t_knowledge_base + t_intent_node 获取
    │
    ▼
AgentLoop Turn 2：模型基于真实数据生成回答
    │ 返回结构化回答，终止循环
    │
    ▼
最终回答（含真实知识库信息）
```

---

## 二、关键代码位置

| 环节 | 文件路径 | 关键行号 | 作用 |
|------|---------|---------|------|
| 统一入口 | `SmartChatController.java` | 80-98 | 接收请求，调用Router |
| 规则路由 | `StrategyRouter.java` | 140-163 | 计算分数，选择策略 |
| SYSTEM检测 | `PipelineStrategy.java` | 116-124 | 检测SYSTEM意图，抛异常转交 |
| 异常转交 | `StrategyRouter.java` | 118-133 | 捕获异常，切换策略 |
| Agent Loop | `AgentLoop.java` | 130-216 | while(true)循环，调工具 |
| 系统信息工具 | `SystemInfoTool.java` | 92-121 | 查询真实元信息 |

---

## 三、日志证据（app1.log 第 8082-8200 行）

```log
# 请求进入
INFO SmartChatController: Smart Chat 请求: question=介绍一下你自己

# 路由决策
INFO StrategyRouter: 路由决策: selectedStrategy=pipeline

# Pipeline 执行
INFO PipelineStrategy: Pipeline Strategy 执行: question=介绍一下你自己

# 意图分类结果（关键）
INFO DefaultIntentClassifier: 意图识别树：
  {"node": {"name": "通用对话", "kind": "SYSTEM"}, "score": 0.95}

# SYSTEM 检测 + 转交（关键）
INFO PipelineStrategy: 检测到 SYSTEM 意图，转交给 Agent 策略
INFO StrategyRouter: 策略转交: from=pipeline, to=agent

# Agent 执行
INFO AgentStrategy: Agent Strategy 执行
INFO AgentLoop: 注册工具: [knowledge_search_with_rerank, system_info_query]

# Turn 1：工具调用
INFO AgentLoop: 执行工具: system_info_query({"query_type": "all"})
INFO SystemInfoTool: system_info_query executed: queryType=all
INFO AgentLoop: 工具结果: 【系统概览】...共 4 个知识库...

# Turn 2：最终回答
INFO AgentLoop: 模型返回最终回答: 你好！我是一个智能问答助手...

# 执行报告
INFO AgentLoop: ========== Agent Loop 完成 ==========
终止原因: COMPLETED, 总轮次: 2, 工具调用次数: 1
```

---

## 四、给 README 写作的结论

### 这个设计解决了什么问题？

**问题**：传统 Pipeline RAG 处理"系统有哪些知识库"、"介绍一下你自己"这类元信息问题时，LLM 没有真实数据支撑，只能编造答案。

**解决**：
1. **意图分类器** 能识别 SYSTEM 类型问题（kind="SYSTEM"）
2. **策略转交机制** 将 SYSTEM 问题从 Pipeline 转交给 Agent
3. **SystemInfoTool** 工具让 Agent 能查询数据库中的真实元信息
4. **Agent Loop** 自主判断何时调工具、何时回答

### 和纯 Pipeline 的真实差异

| 维度 | Pipeline | Agent + SYSTEM转交 |
|------|---------|-------------------|
| 处理流程 | 固定：改写→意图→检索→生成 | 灵活：自主判断→调工具→回答 |
| 元信息问题 | 编造答案 | 返回真实数据（4个知识库ID、名称、描述） |
| 响应时间 | 3-5秒 | 10-20秒（取决于工具调用轮次） |
| 适用场景 | 知识点查询 | 元信息查询、复杂推理 |

### 质变点总结（README 重点）

1. **Agent Loop 模式**：不是简单的 RAG 流程，而是模型驱动的 while(true) 循环
   - 模型自主决定：调工具？继续检索？生成回答？
   - 支持"换关键词重试"、"切换知识库"等智能行为

2. **策略可插拔架构**：新增处理能力只需实现 `ChatStrategy` 接口
   - 已有：PipelineStrategy、AgentStrategy
   - 未来：可增加 MultiAgentStrategy、WebSearchStrategy 等

3. **工具可扩展**：新增场景只需实现 `Tool` 接口 + 注册
   - 案例：SystemInfoTool 解决 SYSTEM 意图问题
   - 未来：可增加 WebSearchTool、CodeAnalysisTool 等

4. **SYSTEM 意图转交**：Pipeline 和 Agent 的协同，不是对立
   - Pipeline 检测 SYSTEM → 转交 Agent → Agent 用工具返回真实数据
   - 这是一种**跨策略协同**的设计模式

---

## 五、截图引用建议

### 截图1：qa-system.png (607×878)
**展示内容**：回答内容 + 底部📚引用来源区域

**README 建议**：
- 位置：放在"系统自省能力"章节
- 说明文字：
  ```markdown
  Agent 模式返回真实数据，底部展示工具调用来源（📚 system_info_query）
  ```
- 强调点：回答中列出了真实的 4 个知识库名称、ID，不是编造

### 截图2：source.png (1920×1080)
**展示内容**：问题 + 回答开头，展示复杂推理

**README 建议**：
- 位置：放在"Agent Loop 模式"章节开头
- 说明文字：
  ```markdown
  用户问"介绍一下你自己"，Agent 自主判断调用 system_info_query 工具获取真实数据
  ```
- 强调点：展示了 Turn 1→Turn 2 的迭代过程

---

## 六、README 结构建议

```markdown
# Ragent-Lab

[徽章]

## 核心特性

### 1. Agent Loop 模式 🔄
[截图2：source.png]
[简短说明：模型驱动的自主循环，工具可扩展]

### 2. 策略可插拔架构 🧭
[架构图文字版]
[说明：Pipeline/Agent 可切换，未来可扩展多智能体]

### 3. 系统自省能力 🔍
[截图1：qa-system.png]
[案例说明：SYSTEM意图 → Agent → 真实数据]

## 工具扩展案例
[展示 SystemInfoTool 实现]
[强调：新场景只需实现 Tool 接口]

## 快速开始
...

## 技术栈
...
```

---

## 七、疑问与建议

### 疑问
1. 现有 README 中"原项目来源"章节较长，用户明确表示不需要反复说明基于 ragent 改进。建议是否删除或精简为一行致谢？

2. 两张截图的具体内容（qa-system.png 和 source.png）我已确认文件存在，但无法显示图片内容。请用户确认这两张截图是否对应：
   - qa-system.png：回答 + 引用来源区域？
   - source.png：问题 + 回答开头（复杂推理展示）？

### 建议
1. **删除冗长的"原项目来源"章节**，改为底部简洁致谢：
   ```markdown
   ## 致谢
   基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 开发。
   ```

2. **聚焦质变展示**，三个核心卖点：
   - Agent Loop（模型自主循环）
   - 策略可插拔（扩展多智能体只需实现接口）
   - 工具可扩展（SYSTEM case 作为典型案例）

3. **增加代码片段展示**，让 README 有技术含量：
   ```markdown
   ## 工具扩展：一个典型案例
   
   用户问"介绍一下你自己" → 系统如何返回真实数据？
   
   [SystemInfoTool 核心代码片段]
   [AgentLoop while(true) 循环代码片段]
   ```

4. **引用格式建议**：
   ```markdown
   ![Agent自主判断并调用工具](assets/source.png)
   ![返回真实数据并展示来源](assets/qa-system.png)
   ```