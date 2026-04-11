# CC Report 008：系统自省能力 + SYSTEM 意图策略转交

## 1. 问题背景

用户询问系统元信息（如"系统里有哪些知识库"），Pipeline 让 LLM 直接回答 → 无真实数据 → 编造答案。

### 1.1 根因分析

Pipeline 的意图分类器（LLM 驱动）能正确识别 SYSTEM 意图，但处理分支 `streamSystemResponse()` 只调用 LLM，没有数据支撑。

### 1.2 正确方案

将 SYSTEM 意图请求转交给 Agent，Agent 有 `system_info_query` 工具可返回真实的系统元信息。

---

## 2. 实现方案

### 2.1 系统信息工具

| 属性 | 值 |
|------|-----|
| 工具名 | `system_info_query` |
| 描述 | 查询系统元信息（知识库列表、系统能力、领域分类） |
| 参数 | `query_type`: all / knowledge_bases / intent_tree / system_capabilities |

**数据源**：

| 信息 | 来源 |
|------|------|
| 知识库列表 | `t_knowledge_base` 表 → `KnowledgeBaseMapper` |
| 意图树结构 | `t_intent_node` 表 → `IntentNodeMapper` |

### 2.2 策略转交机制

**触发点**：PipelineStrategy 层的 SYSTEM 意图预检测

**架构修正原因**：

原始设计在 `RAGChatServiceImpl.streamChat()` 内抛出异常，但该方法被 AOP 包装后在异步线程执行，异常无法传播回 Router。

修正方案：将检测移至 PipelineStrategy（同步执行），在进入异步线程前抛出异常。

**执行流程**：

```
PipelineStrategy.execute()  [nio-9090-exec-X 主线程]
  │
  ├─ queryRewriteService.rewriteWithSplit()  ← 同步
  ├─ intentResolver.resolve()                 ← 同步
  ├─ allSystemOnly 检测                       ← 同步
  │
  ├─ if allSystemOnly=true:
  │     throw StrategyHandoffException       ← 异步前抛出，可传播
  │
  └─ else:
        ragChatService.streamChat()           ← 异步执行，无需再检测
```

---

## 3. 文件变更

| 文件 | 类型 | 改动说明 |
|------|------|---------|
| `agentloop/SystemInfoTool.java` | 新增 | 系统信息查询工具 |
| `agentloop/AgentLoopService.java` | 修改 | 注册工具，更新 System Prompt |
| `strategy/StrategyHandoffException.java` | 新增 | 策略转交信号异常 |
| `strategy/StrategyRouter.java` | 修改 | 增加 executeWithHandoffHandling |
| `strategy/PipelineStrategy.java` | 修改 | 新增 SYSTEM 意图预检测 |
| `service/impl/RAGChatServiceImpl.java` | 修改 | 移除 SYSTEM 检测逻辑 |

---

## 4. 验证结果

### 4.1 测试场景 1：问候语

**问题**："你好"

**日志**：

```
[nio-9090-exec-8] StrategyRouter: 路由决策: selectedStrategy=pipeline
[nio-9090-exec-8] PipelineStrategy: Pipeline Strategy 执行
[sify_executor_1] 意图识别: general-chat (SYSTEM), score=0.95
[nio-9090-exec-8] PipelineStrategy: 检测到 SYSTEM 意图，转交给 Agent 策略
[nio-9090-exec-8] StrategyRouter: 策略转交: from=pipeline, to=agent
[nio-9090-exec-8] AgentStrategy: Agent Strategy 执行

[scheduling-1] Agent Loop: Turn 1
[scheduling-1] 模型返回最终回答: 你好！有什么我可以帮你的吗？
[scheduling-1] Agent Loop 完成: turnCount=1, toolCalls=0
```

**结果**：✅ SYSTEM 意图正确转交，Agent 直接回复问候语（无需工具）

---

### 4.2 测试场景 2：知识库查询

**问题**："当前知识库的组织是怎样的"

**日志**：

```
[nio-9090-exec-4] StrategyRouter: 路由决策: selectedStrategy=pipeline
[nio-9090-exec-4] PipelineStrategy: Pipeline Strategy 执行
[sify_executor_1] 意图识别: general-chat (SYSTEM), score=0.95
[nio-9090-exec-4] PipelineStrategy: 检测到 SYSTEM 意图，转交给 Agent 策略
[nio-9090-exec-4] StrategyRouter: 策略转交: from=pipeline, to=agent
[nio-9090-exec-4] AgentStrategy: Agent Strategy 执行

[scheduling-1] Agent Loop: Turn 1
[scheduling-1] 执行工具: system_info_query({"query_type": "knowledge_bases"})
[scheduling-1] 工具结果: 【知识库列表】共 4 个知识库...

[scheduling-1] Agent Loop: Turn 2
[scheduling-1] 模型返回最终回答: 当前系统中共有 4 个知识库...
[scheduling-1] Agent Loop 完成: turnCount=2, toolCalls=1
```

**Agent 最终回答**（真实数据）：

```
当前系统中共有 4 个知识库，其组织结构如下：

1. dualssd - 向量集合: dualssddocs - DualSSD 创新文档
2. ragent - 向量集合: ragentdocs - Ragent 项目文档
3. ssd - 向量集合: ssddocs - SSD 原始理论
4. exploration - 向量集合: explorationdocs - 探索性内容

所有知识库均使用 qwen-emb-8b 嵌入模型。
```

**结果**：✅ SYSTEM 意图正确转交，Agent 调用工具返回真实数据

---

### 4.3 关键验证点

| 验证项 | 状态 | 证据 |
|--------|------|------|
| 异步线程异常传播 | ✅ | 主线程 `nio-9090-exec-X` 抛出异常，Router 正确捕获 |
| 意图分类器语义理解 | ✅ | "当前知识库的组织是怎样的" 正确识别为 SYSTEM |
| Agent 工具调用 | ✅ | `system_info_query` 返回真实知识库列表 |
| SSE 流式输出 | ✅ | 前端收到完整回答 |

---

## 5. 技术要点

### 5.1 为什么不在 Service 层抛异常

`RAGChatServiceImpl.streamChat()` 被 `@ChatRateLimit` AOP 包装：

```
Controller → Router → PipelineStrategy
              │
              └─ [entry_executor_0 异步线程] RAGChatServiceImpl
                     └─ throw Exception → 被 AOP 捕获 → 无法传播
```

将检测移至 Strategy 层（同步）：

```
Controller → Router → PipelineStrategy.execute()  [主线程]
              │
              ├─ SYSTEM 检测 (同步)
              ├─ throw Exception → Router 捕获 ✓
              │
              └─ else: streamChat() (异步)
```

### 5.2 为什么不使用关键词匹配

关键词穷举追不上用户表达。"当前知识库的组织是怎样的"不含任何预设关键词。

Pipeline 内部的 LLM 意图分类器具备语义理解能力，能正确识别此类问题。

---

## 6. 总结

| 项目 | 结果 |
|------|------|
| 编译 | ✅ 通过 |
| 问候语测试 | ✅ 通过 |
| 知识库查询测试 | ✅ 通过 |
| 异常传播机制 | ✅ 正确 |

**核心改进**：
- PipelineStrategy 同步执行 SYSTEM 意图检测
- 异常在异步线程前抛出，正确传播到 Router
- Agent 使用 `system_info_query` 返回真实数据

**变更量**：3 新增文件 + 4 修改文件