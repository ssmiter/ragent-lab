# Experiment 1.5：统一接口 + Rerank 检索

## 实验目标

解决 experiment1 遗留的两个问题：
1. **工具接口不统一**：MCP 有 MCPToolExecutor，Agent Loop 有 Tool 接口
2. **检索分数偏低**：裸向量检索无 rerank，分数低导致过度重试

## 核心实现

### McpToolAdapter

将 MCPToolExecutor 适配为 Tool 接口：

```
MCPToolExecutor ──→ McpToolAdapter ──→ Tool
    │                    │              │
    ├─ getToolDefinition() ├─ getName()    ├─ getName()
    ├─ execute(request)    ├─ getDescription() ├─ getDescription()
                          ├─ getInputSchema()  ├─ getInputSchema()
                          ├─ execute(map)      ├─ execute(map)
```

**价值**：工具只需实现一次，内外部 Agent 共用。

### Rerank 检索端点

新增 `/api/ragent/retrieve/with-rerank`：
1. 向量检索 15 个候选
2. Rerank 精排
3. 返回 topK（默认 5）

分数范围：0.7-0.95（比向量分数更准确）

### KnowledgeSearchWithRerankMCPExecutor

调用 rerank 端点，默认 collection: `ragentdocs`。

### 超时配置修复

OkHttpClient 增加 60 秒超时，避免长文本生成时 timeout。

## 结果

✅ 成功运行：

**问题**：`"Ragent系统的整体架构是什么"`
**工具调用**：`knowledge_search_with_rerank(query="Ragent系统 整体架构")`
**返回**：5 个 chunks，top1 分数 0.95
**回答**：完整、结构化的架构说明（带 mermaid 图）

模型直接基于高相关结果回答，未触发重试。

## 关键发现

### Rerank 分数显著提升

| 指标 | 向量检索 | Rerank |
|-----|---------|--------|
| Top1 分数 | 0.65-0.81 | 0.85-0.95 |
| 分数可信度 | 低 | 高 |
| 重试触发 | 多 | 少 |

### System Prompt 阈值调整

```markdown
- 分数 > 0.85: 高度相关，直接回答
- 分数 0.75-0.85: 中等相关，可信参考
- 分数 < 0.75: 低相关，建议重试
```

### Agent vs Pipeline 思考

当前 Agent 思路：原子工具 + 模型自主决策
Pipeline 思路：预定义流程 + 意图树路由

后续可探索：将意图树路由作为工具，让 Agent 自主选择知识库。

## 文件

- [progress.md](./progress.md) - 开发进度记录
- [log.md](./log.md) - 运行日志（完整回答）