# CC Report 001：系统调查结果

## 1. LLM 调用层

### 文件路径

| 组件 | 文件路径 |
|------|----------|
| LLMService 接口 | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LLMService.java` |
| ChatClient 接口 | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ChatClient.java` |
| RoutingLLMService（路由实现） | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java` |
| BaiLianChatClient（百炼实现） | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/BaiLianChatClient.java` |
| SiliconFlowChatClient | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/SiliconFlowChatClient.java` |
| OllamaChatClient | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/OllamaChatClient.java` |
| ChatRequest | `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/ChatRequest.java` |

### 关键方法签名

```java
// LLMService 接口（业务层调用）
public interface LLMService {
    // 同步调用（简化模式）
    default String chat(String prompt);
    
    // 同步调用（高级模式）
    String chat(ChatRequest request);
    
    // 流式调用（简化模式）
    default StreamCancellationHandle streamChat(String prompt, StreamCallback callback);
    
    // 流式调用（高级模式）
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback);
}

// ChatClient 接口（底层实现）
public interface ChatClient {
    String provider();
    String chat(ChatRequest request, ModelTarget target);
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);
}

// StreamCallback 回调接口
public interface StreamCallback {
    void onContent(String content);
    default void onThinking(String content) {}
    void onComplete();
    void onError(Throwable error);
    default void setRetrievedChunks(List<RetrievedChunk> chunks) {}
}
```

### Function Calling 支持情况

**当前不支持 tools 参数传入**

ChatRequest 中有预留字段 `enableTools`（Boolean），但：
- BaiLianChatClient 的 `buildRequestBody()` 方法**未处理 tools 参数**
- LLMService 和 ChatClient 接口**未定义 tools 相关参数**
- messages 构建逻辑仅支持 system/user/assistant 三种角色，**不支持 tool 角色**

**需要扩展以支持 Function Calling：**
1. ChatRequest 需新增 `tools` 字段（List<ToolDefinition>）
2. ChatClient.chat() 需支持 tools 参数传递
3. BaiLianChatClient.buildRequestBody() 需组装 tools 到请求体
4. 响应解析需提取 `tool_calls` 字段

### 流式支持情况

**已完整支持流式调用**

- **实现方式：** OkHttp + SSE 流式解析
- **回调接口：** StreamCallback（onContent/onThinking/onComplete/onError）
- **返回类型：** StreamCancellationHandle（可取消句柄）
- **首包探测：** RoutingLLMService 内置 FirstPacketAwaiter，支持首包超时和失败切换
- **事件解析：** OpenAIStyleSseParser（处理 OpenAI 兼容格式的 SSE 流）

### 底层实现

| Provider | HTTP Client | API 格式 | 备注 |
|----------|-------------|----------|------|
| BaiLian（阿里云百炼） | OkHttp | OpenAI 兼容格式 | `POST /compatible-mode/v1/chat/completions` |
| SiliconFlow | OkHttp | OpenAI 兼容格式 | 第三方模型托管平台 |
| Ollama | OkHttp | Ollama API | 本地推理 |

### 多模型降级/fallback 机制

**RoutingLLMService 实现了完整的降级机制**

```java
// 核心逻辑（streamChat 方法）
for (ModelTarget target : targets) {
    ChatClient client = resolveClient(target, label);
    if (client == null) continue;
    
    FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
    ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter);
    
    handle = client.streamChat(request, wrapper, target);
    FirstPacketAwaiter.Result result = awaitFirstPacket(awaiter, handle, callback);
    
    if (result.isSuccess()) {
        wrapper.commit();  // 首包成功，提交缓存内容
        healthStore.markSuccess(target.id());
        return handle;
    }
    
    // 失败处理
    healthStore.markFailure(target.id());
    handle.cancel();
    // 继续下一个候选模型
}
```

**机制特点：**
- ModelSelector 选择候选模型列表（按健康度排序）
- ModelHealthStore 维护模型健康状态
- 首包探测：60 秒超时，无内容或失败则切换下一个
- ProbeBufferingCallback：失败模型的输出会被缓存，成功后才提交，避免污染下游

### 合入注意点

1. **LLMService 接口需扩展**：新增带 tools 参数的 chat/streamChat 方法
2. **ChatRequest 需新增 tools 字段**：支持 OpenAI function calling 格式的工具定义
3. **响应解析需支持 tool_calls**：从 choices[0].message.tool_calls 提取工具调用信息
4. **消息历史需支持 tool 角色**：ChatMessage.Role 需新增 TOOL 枚举值
5. **现有的 RoutingLLMService 降级机制可直接复用**

---

## 2. Chat 端点

### 文件路径

| 组件 | 文件路径 |
|------|----------|
| RAGChatController | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java` |
| RAGChatService 接口 | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java` |
| RAGChatServiceImpl 实现 | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` |

### 端点路径

| 端点 | HTTP 方法 | 路径 | 功能 |
|------|-----------|------|------|
| 流式对话 | GET | `/rag/v3/chat` | SSE 流式问答 |
| 停止任务 | POST | `/rag/v3/stop` | 取消正在进行的对话 |

### 请求参数结构

```java
// RAGChatController.chat() 方法参数
@GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(
    @RequestParam String question,                    // 用户问题（必填）
    @RequestParam(required = false) String conversationId,  // 会话ID（可选）
    @RequestParam(required = false, defaultValue = "false") Boolean deepThinking  // 深度思考模式
);
```

**无独立 DTO 类，参数直接通过 @RequestParam 传入**

### SSE 流式返回实现方式

**使用 Spring SseEmitter**

```java
// RAGChatController.chat()
SseEmitter emitter = new SseEmitter(0L);  // 无超时限制
ragChatService.streamChat(question, conversationId, deepThinking, emitter);
return emitter;
```

**封装类 SseEmitterSender：**
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/web/SseEmitterSender.java`
- 提供线程安全的 sendEvent/complete/fail 方法
- 使用 AtomicBoolean 防止重复关闭

**StreamCallback 与 SseEmitter 的绑定：**
- StreamCallbackFactory 创建回调处理器
- 回调处理器内部使用 SseEmitterSender 发送事件

### 对话历史/记忆加载逻辑

**ConversationMemoryService 接口：**

```java
public interface ConversationMemoryService {
    List<ChatMessage> load(String conversationId, String userId);
    Long append(String conversationId, String userId, ChatMessage message);
    default List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message);
}
```

**DefaultConversationMemoryService 实现：**

```java
@Override
public List<ChatMessage> load(String conversationId, String userId) {
    // 并行加载摘要和历史记录
    CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
        () -> loadSummaryWithFallback(conversationId, userId)
    );
    CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
        () -> loadHistoryWithFallback(conversationId, userId)
    );
    
    // 等待所有任务完成后合并结果
    return CompletableFuture.allOf(summaryFuture, historyFuture)
        .thenApply(v -> attachSummary(summaryFuture.join(), historyFuture.join()))
        .join();
}
```

**历史加载流程：**
1. ConversationMemoryStore 从 MySQL 加载最近 N 条消息
2. ConversationMemorySummaryService 加载对话摘要
3. 摘要作为 system message 前缀添加到历史
4. 追加消息时会触发压缩逻辑（compressIfNeeded）

**RAGChatServiceImpl 的历史使用：**

```java
// 加载历史并追加当前用户问题
List<ChatMessage> history = memoryService.loadAndAppend(
    actualConversationId, userId, ChatMessage.user(question)
);

// 历史用于 query rewrite 和 prompt 构建
RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
List<ChatMessage> messages = promptBuilder.buildStructuredMessages(promptContext, history, ...);
```

### 合入注意点

1. **Agent Loop 需独立的消息历史管理**：现有的 ConversationMemoryService 仅支持 system/user/assistant 角色
2. **SSE 流式输出可直接复用**：SseEmitter + StreamCallback 机制成熟
3. **需新增 Agent Loop 专用端点**：或扩展 `/rag/v3/chat` 支持 agent 模式

---

## 3. 检索服务

### 文件路径

| 组件 | 文件路径 |
|------|----------|
| RetrieveController | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RetrieveController.java` |
| MilvusRetrieverService | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java` |
| RerankService 接口 | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/RerankService.java` |
| RoutingRerankService | `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/RoutingRerankService.java` |

### 端点路径

| 端点 | HTTP 方法 | 路径 | 功能 |
|------|-----------|------|------|
| 原始检索 | POST | `/retrieve` | 向量检索（无 rerank） |
| 检索+重排 | POST | `/retrieve/with-rerank` | 向量检索 + Rerank |

### 核心方法签名

```java
// MilvusRetrieverService
public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam);
public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam);

// RerankService
public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);

// RetrieveController.retrieveWithRerank() 核心流程
int topK = request.getTopK() > 0 ? request.getTopK() : 5;
int candidateCount = request.getCandidateCount() > 0 ? request.getCandidateCount() : Math.max(topK * 3, 15);

// 1. 向量检索获取候选
List<RetrievedChunk> candidates = milvusRetrieverService.retrieve(retrieveRequest);

// 2. Rerank 精排
List<RetrievedChunk> reranked = rerankService.rerank(request.getQuery(), candidates, topK);
```

### 进程内调用可行性

**完全可行，所有服务都是 Spring Bean**

```java
// RetrieveController 注入方式
@RestController
@RequiredArgsConstructor
public class RetrieveController {
    private final MilvusRetrieverService milvusRetrieverService;  // 可直接调用
    private final RerankService rerankService;                    // 可直接调用
}
```

**Agent Loop 可直接注入使用：**
- MilvusRetrieverService.retrieve() — 向量检索
- RerankService.rerank() — 精排重排
- 无需走 HTTP，零网络开销

### Rerank 配置参数管理

**RetrieveWithRerankRequest 内联定义（Controller 内部类）：**

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public static class RetrieveWithRerankRequest {
    private String query;            // 用户查询（必填）
    @Builder.Default
    private int topK = 5;            // 最终返回文档数（默认 5）
    private int candidateCount;      // 候选文档数（默认 15 或 topK*3）
    private String collectionName;   // 向量集合名称
}
```

**RAGDefaultProperties 管理 Milvus 配置：**
- metricType（距离度量类型）
- collectionName（默认集合）
- 其他检索参数

**RoutingRerankService 配置：**
- 通过 ModelSelector 选择 rerank 模型候选
- 支持 fallback 降级机制（与 LLM 类似）

### 合入注意点

1. **检索服务可直接进程内调用**，无需 HTTP 封装
2. **RerankService 已有 fallback 机制**，Agent Loop 可复用
3. **检索参数默认值合理**：candidateCount=15, topK=5
4. **KnowledgeSearchWithRerankMCPExecutor 已实现 MCP 工具封装**，可直接作为 Tool 使用

---

## 4. AgentLoop 实验代码

### 文件结构

```
experiment/src/main/java/com/nageoffer/ai/ragent/experiment/agentloop/
├── AgentLoop.java                # 核心循环逻辑
├── AgentLoopExperiment.java      # 实验 0：基础验证
├── AgentLoopExperiment1.java     # 实验 1：MCP 工具集成
├── AgentLoopExperiment1_5.java   # 实验 1.5：System Prompt
├── AgentLoopExperiment2.java     # 实验 2：多工具协调
├── AgentLoopExperiment3.java     # 实验 3：复杂场景
├── AgentLoopExperiment4.java     # 实验 4：Autocompact
├── AgentLoopResult.java          # 执行结果封装
├── McpToolAdapter.java           # MCP 工具适配器
├── Tool.java                     # 工具接口
├── ToolResult.java               # 工具执行结果
├── MicrocompactProcessor.java    # 微压缩处理器
├── AutocompactProcessor.java     # 自动压缩处理器
├── TranscriptWriter.java         # Transcript 记录
└── SessionMemoryWriter.java      # Session Memory 写入
```

### AgentLoop.java：LLM 调用方式

**直接使用 OkHttp 调用百炼 API（绕过 ChatClient 接口）**

```java
private String callBaiLianAPI(JsonObject requestBody) {
    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build();

    String url = providerConfig.getUrl() + "/compatible-mode/v1/chat/completions";

    okhttp3.Request request = new okhttp3.Request.Builder()
        .url(url)
        .post(okhttp3.RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
        .addHeader("Authorization", "Bearer " + providerConfig.getApiKey())
        .build();

    try (okhttp3.Response response = client.newCall(request).execute()) {
        return response.body().string();
    }
}
```

**请求体组装（带 tools）：**

```java
private String callLLMWithTools(List<JsonObject> messages) {
    JsonObject requestBody = new JsonObject();
    requestBody.addProperty("model", modelCandidate.getModel());

    // 添加消息
    JsonArray messagesArray = new JsonArray();
    for (JsonObject msg : messages) {
        messagesArray.add(msg);
    }
    requestBody.add("messages", messagesArray);

    // 添加工具定义（关键）
    if (!tools.isEmpty()) {
        JsonArray toolsArray = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject toolDef = gson.fromJson(tool.toFunctionDefinition(), JsonObject.class);
            toolsArray.add(toolDef);
        }
        requestBody.add("tools", toolsArray);
    }

    return callBaiLianAPI(requestBody);
}
```

### tool_calls 解析逻辑

```java
private List<ToolCallInfo> extractToolCalls(JsonObject message) {
    List<ToolCallInfo> result = new ArrayList<>();
    
    if (!message.has("tool_calls") || message.get("tool_calls").isJsonNull()) {
        return result;  // 无工具调用
    }

    JsonArray toolCalls = message.getAsJsonArray("tool_calls");
    for (int i = 0; i < toolCalls.size(); i++) {
        JsonObject tc = toolCalls.get(i).getAsJsonObject();
        String id = tc.get("id").getAsString();           // tool_call_id
        JsonObject function = tc.getAsJsonObject("function");
        String name = function.get("name").getAsString();  // 工具名
        String arguments = function.get("arguments").getAsString();  // 参数 JSON
        
        result.add(new ToolCallInfo(id, name, arguments));
    }
    return result;
}
```

### 终止条件判断

```java
// AgentLoop.run() 主循环
while (true) {
    turnCount++;
    
    // 护栏：最大轮次限制
    if (turnCount > maxTurns) {
        return AgentLoopResult.maxTurnsReached(turnCount, toolCallHistory);
    }
    
    String response = callLLMWithTools(messages);
    JsonObject responseJson = gson.fromJson(response, JsonObject.class);
    JsonObject message = extractMessage(responseJson);
    
    List<ToolCallInfo> toolCalls = extractToolCalls(message);
    
    // 关键：无工具调用 = 模型返回最终回答 = 终止
    if (toolCalls.isEmpty()) {
        String finalContent = extractContent(message);
        return AgentLoopResult.completed(finalContent, turnCount, toolCallHistory);
    }
    
    // 有工具调用 → 执行 → 拼回历史 → 继续循环
    for (ToolCallInfo tc : toolCalls) {
        ToolResult result = tool.execute(parseArguments(tc.arguments));
        messages.add(createToolResultMessage(result));
    }
}
```

### McpToolAdapter 适配逻辑

```java
public class McpToolAdapter implements Tool {
    private final MCPToolExecutor executor;
    private final MCPToolDefinition definition;

    @Override
    public String getName() {
        return definition.getToolId();  // MCP 工具 ID
    }

    @Override
    public String getDescription() {
        return definition.getDescription();  // MCP 描述
    }

    @Override
    public String getInputSchema() {
        // 从 MCPToolDefinition.ParameterDef 构建 JSON Schema
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        for (Map.Entry<String, ParameterDef> entry : definition.getParameters().entrySet()) {
            // 构建每个参数的 schema
            JsonObject paramSchema = new JsonObject();
            paramSchema.addProperty("type", paramDef.getType());
            paramSchema.addProperty("description", paramDef.getDescription());
            // 支持 default、enum 等
            properties.add(paramName, paramSchema);
        }
        schema.add("properties", properties);
        
        return gson.toJson(schema);
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 构建 MCPToolRequest，直接调用 MCP 执行器（进程内）
        MCPToolRequest request = MCPToolRequest.builder()
            .toolId(getName())
            .parameters(new HashMap<>(input))
            .build();
        
        MCPToolResponse response = executor.execute(request);
        
        if (response.isSuccess()) {
            return ToolResult.success(getName(), response.getTextResult());
        } else {
            return ToolResult.error(getName(), response.getErrorMessage());
        }
    }
}
```

**适配要点：**
- 从 MCPToolDefinition 提取 name、description、parameters schema
- 执行时直接调用 MCPToolExecutor（进程内，零 HTTP）
- 结果转换为 ToolResult 格式

### Tool 接口签名

```java
public interface Tool {
    String getName();
    String getDescription();
    String getInputSchema();
    ToolResult execute(Map<String, Object> input);
    
    // 默认方法：转换为 OpenAI function calling 格式
    default String toFunctionDefinition() {
        return String.format("""
            {
              "type": "function",
              "function": {
                "name": "%s",
                "description": "%s",
                "parameters": %s
              }
            }
            """,
            getName(),
            getDescription().replace("\"", "\\\""),
            getInputSchema()
        );
    }
}
```

### 各类依赖分析

| 类 | 本地依赖 | 外部库依赖 |
|----|----------|------------|
| AgentLoop | Tool, ToolResult, AgentLoopResult, MCPToolDefinition | Gson, OkHttp |
| McpToolAdapter | Tool, ToolResult, MCPToolExecutor, MCPToolDefinition, MCPToolRequest, MCPToolResponse | Gson |
| Tool | ToolResult | 无 |
| ToolResult | 无 | Lombok |
| AgentLoopResult | 无 | Lombok |

**关键外部依赖：**
- Gson（JSON 处理）
- OkHttp（HTTP 客户端）
- Lombok（简化代码）

### 合入注意点

1. **AgentLoop 绕过了 ChatClient 接口**：合入时需统一到 LLMService/ChatClient 调用链
2. **tools 参数组装逻辑完整**：可直接参考用于扩展 BaiLianChatClient
3. **tool_calls 解析逻辑完整**：可作为响应解析扩展的参考
4. **McpToolAdapter 实现进程内调用**：工具执行无需 HTTP 封装
5. **消息历史使用 JsonObject**：需转换为 ChatMessage 格式以对接现有系统

---

## 5. 综合判断

### LLMService 是否需要扩展以支持 Function Calling？

**需要扩展，但改动可控**

| 扩展点 | 改动量 | 备注 |
|--------|--------|------|
| ChatRequest 新增 tools 字段 | 小 | 预留字段 enableTools 已存在，改为 List<ToolDefinition> |
| ChatMessage.Role 新增 TOOL | 小 | 新增枚举值 |
| LLMService 接口新增方法 | 中 | 可保持现有方法不变，新增带 tools 参数的 overload |
| ChatClient 接口扩展 | 中 | 需支持 tools 参数传递 |
| BaiLianChatClient 响应解析 | 中 | 新增 tool_calls 提取逻辑 |

**推荐方案：**
1. 新增 `LLMService.chatWithTools(ChatRequest request, List<Tool> tools)` 方法
2. ChatRequest 内部新增 tools 字段，由构建者填充
3. BaiLianChatClient.buildRequestBody() 新增 tools 组装逻辑
4. 新增 `ToolCallResponse` 类封装 tool_calls 解析结果

### 工具接入建议走进程内调用还是 HTTP？

**强烈建议进程内调用**

| 方案 | 优势 | 劣势 |
|------|------|------|
| 进程内调用 | 零网络开销、复用 Spring Bean、类型安全、调试方便 | 需统一 Tool 接口 |
| HTTP 调用 | 独立部署、跨进程隔离 | 网络延迟、序列化开销、错误处理复杂 |

**理由：**
1. McpToolAdapter 已实现进程内调用模式
2. MilvusRetrieverService、RerankService 都是 Spring Bean
3. KnowledgeSearchWithRerankMCPExecutor 已封装检索逻辑
4. Agent Loop 与 bootstrap 同进程部署，无隔离需求

**实现路径：**
- McpToolAdapter 直接注入 MCPToolExecutor
- 或新建 BootstrapToolAdapter 封装检索服务

### SSE 流式输出最简可行方案是什么？

**方案 A：复用现有 SseEmitter + StreamCallback（推荐）**

```java
// 新增 AgentStreamCallback 实现 StreamCallback
// 内部持有 AgentLoop 状态，按事件类型发送 SSE

@GetMapping(value = "/agent/chat", produces = "text/event-stream;charset=UTF-8")
public SseEmitter agentChat(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter(0L);
    AgentStreamCallback callback = new AgentStreamCallback(emitter);
    
    // AgentLoop 在每个 turn 的 LLM 响应通过 callback 推送
    agentLoopService.runAsync(question, callback);
    
    return emitter;
}
```

**方案 B：改造 AgentLoop 为流式模式**

- 每次调用 LLM 后，通过 StreamCallback 推送增量内容
- 工具调用事件单独发送（如 `tool_use`、`tool_result`）

### 预计合入的主要改动点和风险点

**主要改动点：**

| 模块 | 改动内容 |
|------|----------|
| infra-ai/chat | ChatRequest 新增 tools、ChatClient 支持 tools 参数、响应解析支持 tool_calls |
| framework/convention | ChatMessage.Role 新增 TOOL、新增 ToolDefinition/ToolCallInfo 类 |
| bootstrap/rag | 新增 AgentLoopService、AgentChatController |
| experiment/agentloop | 统一到 LLMService 调用链、消息历史使用 ChatMessage |

**风险点：**

1. **LLM 接口兼容性**：百炼 API 对 tools 参数的具体格式需验证
2. **消息历史格式**：tool 消息的 role/content 结构需与 API 兼容
3. **多轮对话 token 消耗**：Agent Loop 可能产生大量历史，需压缩机制
4. **超时和取消**：长耗时 Agent 调用需超时控制，与现有 StreamCancellationHandle 集成
5. **错误恢复**：工具执行失败时的处理策略（重试/降级/终止）

---

## 附录：关键代码片段索引

| 功能 | 文件 | 方法/行号 |
|------|------|-----------|
| LLM 路由降级 | RoutingLLMService.java | streamChat() 92-147 |
| 百炼请求构建 | BaiLianChatClient.java | buildRequestBody() 166-192 |
| SSE 发送封装 | SseEmitterSender.java | sendEvent() 67-83 |
| 对话历史加载 | DefaultConversationMemoryService.java | load() 44-74 |
| 检索+重排 | RetrieveController.java | retrieveWithRerank() 68-93 |
| Agent Loop 主循环 | AgentLoop.java | run() 116-223 |
| tools 参数组装 | AgentLoop.java | callLLMWithTools() 230-258 |
| tool_calls 解析 | AgentLoop.java | extractToolCalls() 341-360 |
| MCP 工具适配 | McpToolAdapter.java | execute() 133-151 |