# CC Task 003：Agent Loop 功能验证

> **前置：** Task 002 已完成代码合入和部署，端点可访问。
> **目标：** 验证 Agent Loop 的完整功能链路，发现并修复问题。
> **产出：** `CC_Report_003_Verification.md`

---

## 验证步骤

### 1. Pipeline 回归验证（最重要，确认没搞坏）

通过 curl 或浏览器访问 Pipeline 模式端点，确认原有功能正常：

```bash
curl -N "http://localhost:9090/api/ragent/rag/v3/chat?question=测试"
```

记录：是否正常返回 SSE 流式响应？

### 2. Agent 模式基本功能验证

```bash
curl -N "http://localhost:9090/api/ragent/agent/chat?question=Ragent的整体架构是什么"
```

观察并记录：
- 是否返回 SSE 流式响应？
- 响应内容是什么？是否基于知识库的信息？
- 响应耗时大约多久？

如果报错，记录完整的错误信息。

### 3. 日志验证

查看 bootstrap 的运行日志（tail 最近的日志文件或 stdout），寻找以下关键信息：

- AgentLoop 是否启动？
- 是否调用了 `knowledge_search_with_rerank` 工具？
- 工具返回了什么结果？（检索到的 chunk 内容和 rerank 分数）
- AgentLoop 跑了几轮？
- 最终回答是什么？
- 有无异常堆栈？

### 4. 边界情况测试（如果基本功能通过）

测试以下场景：

```bash
# 模糊问题（测试模型是否会搜索+判断+可能重试）
curl -N "http://localhost:9090/api/ragent/agent/chat?question=它怎么存的"

# 知识库外的问题（测试模型是否诚实说不知道）
curl -N "http://localhost:9090/api/ragent/agent/chat?question=今天天气怎么样"

# 空问题（测试错误处理）
curl -N "http://localhost:9090/api/ragent/agent/chat?question="
```

记录每个场景的响应。

---

## 如果遇到问题

如果 Agent 模式不能正常工作，请按以下顺序排查：

1. **先看日志**：确认请求是否到达了 AgentLoopService
2. **百炼 API 调用**：AgentLoop 是否成功调通了百炼 API？apiKey/baseUrl/model 配置是否正确？
3. **工具执行**：KnowledgeSearchWithRerankTool 是否成功执行？MilvusRetrieverService 和 RerankService 是否正常注入？
4. **SSE 输出**：最终回答是否成功通过 SseEmitter 发出？

**如果发现 bug，直接修复**，不需要等下一个 Task。在报告中记录问题和修复方式即可。

---

## 汇报格式

生成 `CC_Report_003_Verification.md`，包含：

```markdown
# CC Report 003：功能验证结果

## 1. Pipeline 回归
- 测试命令：...
- 结果：通过/失败
- 响应摘要：...

## 2. Agent 模式基本功能
- 测试命令：...
- 结果：通过/失败
- 响应内容摘要：...
- 耗时：...
- SSE 事件格式：...（粘贴实际收到的 SSE 数据样本）

## 3. 日志分析
- AgentLoop 轮次：...
- 工具调用记录：...
- 检索结果质量：...（rerank 分数）
- 异常信息：...

## 4. 边界情况测试
（每个场景的结果）

## 5. 发现的问题和修复
（如果有 bug 并已修复，记录问题描述、根因、修复方式）

## 6. 当前状态总结
- Agent 模式是否可用：是/否
- 剩余问题：...
```
