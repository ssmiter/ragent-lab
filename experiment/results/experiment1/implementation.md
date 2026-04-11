# Agent Loop 实验 1 — 实现报告

## 实验时间

2026-04-05

## 实验目的

将实验0的 Agent Loop 骨架升级为能真正回答知识库问题的完整 Agent：

1. 添加 System Prompt（引导模型行为）
2. 实现 KnowledgeSearchTool（真实的向量检索）
3. 让模型自主决定搜索策略

## 实现内容

### Part A：CC System Prompt 设计模式分析

已完成并保存至 `TASKS/CC_System_Prompt_Patterns.md`。

关键发现：
- **模块化结构**：静态部分（可缓存）+ 动态部分（每轮变化）
- **工具引导**：System Prompt + 工具 Description + 工具 Prompt() 三层互补
- **行为准则**：决策框架、错误处理、安全检查

### Part B：实现代码

#### 1. KnowledgeSearchTool

文件：`experiment/src/main/java/.../tools/KnowledgeSearchTool.java`

**实现方式**：HTTP 调用 ragent 的 `/api/ragent/retrieve` 接口

**关键设计**：
- 返回分数信息，让模型能判断搜索结果质量
- 支持指定 collection（知识库集合）
- 错误信息明确，便于模型理解失败原因

**参数**：
- `query`（必填）：搜索查询
- `collection`（可选）：知识库集合名（默认 ragentdocs）
- `top_k`（可选）：返回数量（默认 5，最大 10）

**返回格式示例**：
```
Found 5 relevant chunks (searched collection: ragentdocs):

[Chunk 1] (score: 0.82, source: deployment_guide.md)
<chunk content...>

[Chunk 2] (score: 0.75, source: architecture.md)
<chunk content...>

Score distribution: highest=0.82, lowest=0.45, gap between top1 and top2: 0.07
```

#### 2. System Prompt

设计原则（基于 CC 模式）：
- **角色定义**：知识库助手
- **工具说明**：knowledge_search 能做什么
- **行为准则**：
  - 先搜索再回答
  - 分数 > 0.8 高相关，直接回答
  - 分数 < 0.6 低相关，换查询重试
  - 最多重试 2 次
  - 找不到信息时诚实告知
- **格式要求**：中文回答，引用来源

完整 System Prompt：
```markdown
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 可用工具
你有以下工具可用：
- knowledge_search: 在知识库中搜索相关信息。返回按相关性排序的文本片段和分数。

# 行为指导
1. 收到用户问题后，先使用 knowledge_search 搜索知识库
2. 观察搜索结果的分数：
   - 分数 > 0.8: 高度相关，可以直接基于搜索结果回答
   - 分数 0.6-0.8: 中等相关，可以参考，但可能需要补充解释
   - 分数 < 0.6: 低相关，建议换一个查询重新搜索
3. 如果第一次搜索结果分数较低（< 0.6），尝试：
   - 使用更具体的关键词
   - 使用文档中可能出现的专业术语
   - 拆解问题，搜索子问题
4. 最多重试 2 次搜索，如果仍然找不到相关信息，诚实告知用户
5. 回答时引用来源文档名（source 字段）

# 语言
始终使用中文回答用户问题。
```

#### 3. AgentLoop 改造

新增功能：
- `systemPrompt` 字段（可选）
- `createSystemMessage()` 方法
- 新构造函数支持传入 System Prompt

#### 4. 测试入口

文件：`experiment/src/main/java/.../AgentLoopExperiment1.java`

测试问题：`"Ragent系统的整体架构是什么？"`

## 运行方式

1. **启动 ragent 服务**（提供向量检索接口）
   ```bash
   # 确保 localhost:9090 可访问
   # 确保 Milvus 向量库已启动并包含数据
   ```

2. **在 IDEA 中运行 AgentLoopExperiment1**

## 预期行为

```
Turn 1:
  - 模型调用 knowledge_search(query="Ragent系统的整体架构")
  - 工具返回 chunks + 分数

Turn 2:
  - 模型判断分数（假设 top1 = 0.82）
  - 分数 > 0.8，模型直接生成回答
  - 循环终止
```

如果分数较低：
```
Turn 1: knowledge_search(query="...") → top1 = 0.45
Turn 2: knowledge_search(query="更具体的关键词") → top1 = 0.75
Turn 3: 模型生成回答
```

## 关键决策

### GenerateAnswerTool 是否需要？

**决策：不需要**

理由：
1. CC 模式是模型在最后一轮直接输出文本回答
2. 让 Agent Loop 更简洁，减少一层抽象
3. 模型本身具备生成能力，无需额外工具

### HTTP vs 直接 Java 调用

**决策：HTTP 调用**

理由：
1. experiment 模块不依赖 bootstrap，避免 Spring 上下文复杂度
2. 与 MCP 工具保持一致的调用方式
3. 独立性更强，便于隔离测试

## 文件清单

| 文件 | 说明 |
|-----|------|
| `TASKS/CC_System_Prompt_Patterns.md` | CC System Prompt 设计模式分析 |
| `experiment/src/.../tools/KnowledgeSearchTool.java` | 知识库检索工具 |
| `experiment/src/.../AgentLoop.java` | 添加 System Prompt 支持 |
| `experiment/src/.../AgentLoopExperiment1.java` | 测试入口 |

## 下一步

在 IDEA 中运行 AgentLoopExperiment1，验证：
1. System Prompt 是否正确引导模型行为
2. 模型是否先搜索再回答
3. 模型是否根据分数判断结果质量
4. 低分数时模型是否尝试重试

记录完整运行日志，保存为 `experiment1_result.md`。