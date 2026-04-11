# CC Report 002：Agent Loop 最小化合入实施报告

## 1. 实际创建/修改的文件

### 新创建的文件

| 文件路径 | 说明 |
|---------|------|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/Tool.java` | 工具接口，定义 Agent Loop 可调用的工具规范 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/ToolResult.java` | 工具执行结果封装 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/AgentLoopResult.java` | Agent Loop 执行结果封装 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/AgentLoop.java` | Agent Loop 核心循环逻辑 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/KnowledgeSearchWithRerankTool.java` | 进程内检索工具实现 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/AgentLoopService.java` | Agent Loop 服务层，负责组装和运行 AgentLoop |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/AgentChatController.java` | Agent 模式对话控制器 |

### 修改的文件

| 文件路径 | 修改内容 |
|---------|---------|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java` | 添加 `@EnableAsync` 注解，支持异步执行 |

---

## 2. 每个步骤中遇到的问题和解决方式

### 步骤 1：搬运核心类到 bootstrap

**问题 1：依赖传递验证**

需要确认 Gson 和 OkHttp 依赖是否通过现有模块传递到 bootstrap。

**解[DEPLOY_SKILLS.md](..%2F..%2F..%2FDesktop%2F%E4%BF%A1%E6%81%AF%E6%90%9C%E9%9B%86%2Fragent%E9%A1%B9%E7%9B%AE%2F%E9%83%A8%E7%BD%B2%2FDEPLOY_SKILLS.md)决方式：**
- 检查 framework/pom.xml：确认 Gson 依赖存在
- 检查 infra-ai/pom.xml：确认 OkHttp 依赖存在
- bootstrap 依赖 framework 和 infra-ai，依赖链正确

**问题 2：配置获取方式**

AgentLoop 需要百炼 API 的 baseUrl、apiKey 和 modelName。原实验代码直接接收参数，需要改为从 Spring 配置中读取。

**解决方式：**
- AgentLoop 保持构造器接收参数的方式（简单、可测试）
- 在 AgentLoopService 中注入 `AIModelProperties`，从配置中提取 baseUrl、apiKey、modelName 后传给 AgentLoop

### 步骤 2：创建进程内检索工具

**问题 1：代码迁移时的语法错误**

在复制代码时，`sb.append("["` 误写为 `sb.append "["`，导致编译错误。

**解决方式：**
- 编译时发现错误
- 修正为正确的 Java 语法 `sb.append("["`

**问题 2：工具描述和 Schema 定义**

需要提供符合 OpenAI Function Calling 格式的工具描述。

**解决方式：**
- 参考 mcp-server 中 KnowledgeSearchWithRerankMCPExecutor 的描述
- 直接复用 getInputSchema() 方法返回 JSON Schema 字符串

### 步骤 3：创建 AgentLoopService

**问题 1：异步执行支持**

AgentLoop 是同步阻塞的，需要在 Controller 返回后继续执行。

**解决方式：**
- 使用 `@Async` 注解标记 `runAgent` 方法
- 在 RagentApplication 中添加 `@EnableAsync` 启用异步支持

**问题 2：配置获取逻辑**

需要从 AIModelProperties 中正确提取 Provider 配置和 Model 配置。

**解决方式：**
- `getProviderConfig()`：返回第一个有效的 provider（apiKey 和 url 都非空）
- `getModelCandidate()`：优先返回默认模型，否则返回第一个启用的模型

### 步骤 4：创建 Controller 端点

**问题 1：端点路径设计**

需要与现有 Pipeline 模式区分，同时遵循现有的 URL 规范。

**解决方式：**
- 新端点：`GET /agent/chat`（注意：不带 `/api/ragent` 前缀，因为 Spring Boot 已配置 context path）

**问题 2：RequestMapping 与 context path 冲突**

初始代码使用 `@RequestMapping("/api/ragent")`，但 Spring Boot 的 context path 已经是 `/api/ragent`，导致实际路径变成 `/api/ragent/api/ragent/agent/chat`（重复）。

**解决方式：**
- 修改 Controller 的 `@RequestMapping` 为 `/agent`
- 最终路径：context path + request mapping = `/api/ragent/agent/chat`

**问题 3：Nginx 静态资源处理**

请求被 Nginx 当作静态资源处理，返回 `No static resource agent/chat`。

**解决方式：**
- 在 Nginx 配置中添加 `/api/ragent/agent/chat` 的 SSE 代理规则：
```nginx
location /api/ragent/agent/chat {
    proxy_pass http://127.0.0.1:9090;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    chunked_transfer_encoding off;
}
```

---

## 3. 与本任务描述不同的实际做法及原因

| 任务描述 | 实际做法 | 原因 |
|---------|---------|------|
| `@Value("${ragent.llm.bailian.api-key}")` 读取配置 | 注入 `AIModelProperties` 后提取配置 | bootstrap 使用 `ai.*` 配置前缀（通过 AIModelProperties），而非 `ragent.*` 前缀 |
| 流式返回最终回答 | 分批发送，每 5 字符为一个 chunk | MVP 最简方案，后续可改为真正的流式（每个 LLM turn 都推送） |
| `messages.add(message)` 直接添加 JsonObject | 同上，保持一致 | AgentLoop 内部已处理消息格式，无需转换 |
| `@RequestMapping("/api/ragent")` | `@RequestMapping("/agent")` | Spring Boot context path 已是 `/api/ragent`，避免路径重复 |

### 关键设计决策

1. **不改 LLMService / ChatClient**：遵循任务要求，AgentLoop 保持独立的 OkHttp 直调百炼 API
2. **工具走进程内调用**：`KnowledgeSearchWithRerankTool` 直接注入 `MilvusRetrieverService` 和 `RerankService`
3. **SSE 流式输出用最简方案**：AgentLoop 同步跑完所有轮次，拿到最终回答后分批发送
4. **不动 Pipeline**：原有 `/rag/v3/chat` 完全不动，新建独立端点 `/agent/chat`

---

## 4. 部署过程中遇到的问题

### 问题 1：mcp-server pom.xml 配置问题

**现象：** mcp-server 重新部署失败

**原因：** 之前为本地调试添加了 `<classifier>exec</classifier>` 配置，导致可执行 jar 名称变成 `mcp-server-0.0.1-SNAPSHOT-exec.jar`，部署脚本找不到预期的 jar 文件。

**解决：** 恢复 `mcp-server/pom.xml` 到原始状态：
```bash
git checkout -- mcp-server/pom.xml
```

### 问题 2：Controller RequestMapping 与 context path 冲突

**现象：** 访问 `/api/ragent/agent/chat` 返回 `No static resource agent/chat`

**原因：** Spring Boot context path 是 `/api/ragent`，Controller 的 `@RequestMapping("/api/ragent")` 导致路径变成 `/api/ragent/api/ragent/agent/chat`（重复）

**解决：** 修改 `AgentChatController` 的 `@RequestMapping` 为 `/agent`

### 问题 3：Nginx 未配置 SSE 代理

**现象：** SSE 请求被当作普通请求处理，流式响应异常

**解决：** 在 Nginx 配置中添加：
```nginx
location /api/ragent/agent/chat {
    proxy_pass http://127.0.0.1:9090;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    chunked_transfer_encoding off;
}
```

---

## 5. 验证结果

### 编译验证

```bash
./mvnw.cmd compile -pl bootstrap -am -q
```

**结果：编译通过**（无错误输出）

### 部署验证

**问题：初次部署后访问返回 `No static resource agent/chat`**

**原因：**
1. Controller 的 `@RequestMapping("/api/ragent")` 与 Spring Boot context path 冲突
2. Nginx 未配置 SSE 代理规则

**解决：**
1. 修改 `AgentChatController` 的 `@RequestMapping` 为 `/agent`
2. 在 Nginx 添加 `/api/ragent/agent/chat` 的 SSE 代理配置
3. 重新打包并部署

**最终验证：**
- 前端界面能正常访问 Agent 端点
- SSE 流式返回正常工作
- 用户登录认证正常通过

### 功能验证清单

部署后需验证：

1. **Pipeline 不受影响**
   - 调用 `GET /rag/v3/chat?question=测试`
   - 确认返回正常

2. **Agent 模式基本功能**
   - 调用 `GET /api/ragent/agent/chat?question=Ragent的整体架构是什么`
   - 确认：
     - 返回 SSE 流式响应
     - 日志中能看到 AgentLoop 调用了 `knowledge_search_with_rerank` 工具
     - 返回内容基于知识库

3. **日志检查**
   - 确认 AgentLoop 的轮次、工具调用、最终回答都有记录

---

## 6. 剩余待解决的问题

### 已知限制（MVP 范围内，非问题）

1. **非真正流式**：当前 AgentLoop 同步执行完所有轮次后才返回，中间的工具调用过程不可见。后续可改为每个 LLM turn 都推送事件。

2. **历史上下文未集成**：当前 `buildEffectiveQuestion()` 直接返回用户问题，未利用对话历史。后续可加入历史摘要或改写逻辑。

3. **工具列表硬编码**：当前只注册了 `knowledge_search_with_rerank` 一个工具。后续可通过配置或自动扫描注册更多工具。

4. **模型选择简单**：当前只使用默认模型，未利用 ModelSelector 的候选模型和 fallback 机制。

### 后续优化建议

1. **统一 LLM 调用**：将 AgentLoop 的 LLM 调用改为通过 LLMService/ChatClient，复用降级机制

2. **真正的流式输出**：改造 AgentLoop，在每个 turn 的 LLM 响应时推送事件

3. **工具执行事件**：在工具调用前后推送事件，让前端展示工具使用过程

4. **配置化 System Prompt**：将 System Prompt 从硬编码改为配置文件管理

5. **对话历史集成**：利用 ConversationMemoryService 提供多轮对话上下文

---

## 7. 文件清单

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/
├── Tool.java                           # 工具接口
├── ToolResult.java                     # 工具执行结果
├── AgentLoopResult.java                # Agent Loop 执行结果
├── AgentLoop.java                      # Agent Loop 核心逻辑
├── KnowledgeSearchWithRerankTool.java  # 进程内检索工具
└── AgentLoopService.java               # Agent Loop 服务层

bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/
└── AgentChatController.java            # Agent 模式对话控制器

bootstrap/src/main/java/com/nageoffer/ai/ragent/
└── RagentApplication.java              # 添加 @EnableAsync（修改）
```