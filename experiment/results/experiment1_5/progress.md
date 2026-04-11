# Agent Loop 实验 1.5 进度记录

## 运行时间
2026-04-05

## 当前状态：初步可用

### 已完成的核心实现

1. **McpToolAdapter** (`experiment/src/main/java/.../agentloop/McpToolAdapter.java`)
   - 将 MCPToolExecutor 适配为 Agent Loop 的 Tool 接口
   - 工具只需实现一次，内外部 Agent 共用

2. **Rerank 检索端点** (`bootstrap/.../RetrieveController.java:67-89`)
   - `/api/ragent/retrieve/with-rerank`
   - 流程：向量检索(15候选) → Rerank精排 → 返回topK(5)

3. **KnowledgeSearchWithRerankMCPExecutor** (`mcp-server/.../KnowledgeSearchWithRerankMCPExecutor.java`)
   - 调用 rerank 端点，默认 collection: `ragentdocs`
   - 分数范围：0.7-0.95 (Rerank后)

4. **AgentLoopExperiment1_5** (`experiment/.../AgentLoopExperiment1_5.java`)
   - 使用 McpToolAdapter 注册 MCP 工具
   - System Prompt 阈值：>0.85 高相关，<0.75 低相关

### 测试运行记录

**Q1: "Ragent系统的整体架构是什么"**

服务器日志确认检索成功：
```
retrieve-with-rerank 完成: query=Ragent系统 整体架构, candidates=15, topK=5, 返回5个chunk
```

Agent Loop 报告：
```
终止原因: ERROR
总轮次: 2
工具调用次数: 1
[Turn 1] knowledge_search_with_rerank -> ✓
```

**问题分析**：Turn 1 工具调用成功，Turn 2 调用百炼 API 时出现 timeout 或其他错误。

### 待解决问题

1. **百炼 API timeout** - 网络问题，需要用户后续排查
2. **完整的 6 问题测试** - API 问题解决后继续

### 技术要点总结

| 组件 | 作用 | 状态 |
|-----|------|-----|
| McpToolAdapter | MCP → Tool 适配 | ✓ |
| /retrieve/with-rerank | 检索+精排端点 | ✓ |
| KnowledgeSearchWithRerankMCPExecutor | MCP 工具实现 | ✓ |
| AgentLoopExperiment1_5 | 测试入口 | ✓ |
| collectionName | 默认 ragentdocs | ✓ (写死) |

### 后续方向

1. 解决百炼 API 网络问题
2. 完成剩余 5 个问题的测试
3. 分析 Rerank 分数分布，微调阈值
4. 对比 Agent Loop vs Pipeline 回答质量
5. 探索意图树路由作为工具的可能性