# CC Task 001：Agent Loop 合入前的系统调查

> **目标：** 搜集 bootstrap 主系统的关键接口信息，为 Agent Loop 合入决策提供依据。
> **约束：** 只读不改，不修改任何代码。
> **产出：** 在项目根目录生成 `CC_Report_001_Investigation.md` 汇报调查结果。

---

## 调查项

### 1. LLM 调用层

找到 bootstrap 项目中负责调用大模型的核心类（可能叫 `LlmService` 或类似名称），回答：

- 文件路径是什么？
- 方法签名有哪些？是否支持传入 `tools` 参数（即 Function Calling / tool_use）？
- 是否有流式调用（streaming）的方法？返回类型是什么（Flux? SSE? 回调?）
- 底层调百炼 API 的方式：直接 HTTP client？OpenAI SDK？某个国产 SDK？
- 有无多模型降级/fallback 机制？怎么配的？

### 2. Chat 端点（Pipeline 模式入口）

找到处理用户问答请求的 Controller，回答：

- 文件路径？端点路径（如 `/api/ragent/rag/v3/chat`）？
- 请求参数结构（DTO 类）：字段有哪些？
- SSE 流式返回的实现方式：用的 `SseEmitter`？`Flux<ServerSentEvent>`？还是其他？
- 对话历史/记忆的加载逻辑在哪？怎么取最近 N 条消息的？

### 3. 检索服务（Retrieve with Rerank）

找到 bootstrap 中 `/api/ragent/retrieve/with-rerank` 端点对应的 Service 层，回答：

- Service 类的文件路径？
- 核心方法签名：输入参数类型、输出返回类型？
- 在 bootstrap 进程内能否直接通过 Spring Bean 调用（不走 HTTP）？
- Rerank 的配置参数（候选数、topK 等）在哪管理的？

### 4. experiment 目录的 AgentLoop 核心代码

浏览 `experiment/src/main/java/.../agentloop/` 下的文件，回答：

- `AgentLoop.java`：怎么调 LLM 的？用什么 HTTP client？请求体怎么组装 tools 和 messages 的？
- `AgentLoop.java`：怎么解析响应中的 `tool_calls`？怎么判断终止条件（`finish_reason`）？
- `McpToolAdapter.java`：适配逻辑是什么？它从 MCP 工具上取了哪些信息（name/description/schema）？
- `Tool.java` 接口：方法签名？
- 各类的 import/依赖：哪些是 experiment 本地的，哪些是外部库？

---

## 汇报格式要求

请在项目根目录生成 `CC_Report_001_Investigation.md`，按以下结构输出：

```markdown
# CC Report 001：系统调查结果

## 1. LLM 调用层
- **文件路径：** ...
- **关键方法签名：**
  ```java
  // 粘贴关键方法签名
  ```
- **Function Calling 支持情况：** 是/否，细节...
- **流式支持情况：** ...
- **底层实现：** ...
- **合入注意点：** ...

## 2. Chat 端点
（同上结构）

## 3. 检索服务
（同上结构）

## 4. AgentLoop 实验代码
（同上结构）

## 5. 综合判断
- LlmService 是否需要扩展以支持 Function Calling？
- 工具接入建议走进程内调用还是 HTTP？
- SSE 流式输出最简可行方案是什么？
- 预计合入的主要改动点和风险点
```

---

## 注意事项

- 优先搜集信息的完整性，不要急于给出实现方案
- 如果某个类/文件找不到，如实说明，不要猜测
- 如果发现任务简报中的假设与实际代码不符，请明确指出
