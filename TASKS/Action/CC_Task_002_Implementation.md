# CC Task 002：Agent Loop 最小化合入实施

> **前置：** 基于 CC_Report_001 的调查结果执行合入。
> **原则：** 最小可用版本。最大化复用现有组件，不做过度设计。
> **目标：** 新增一个 Agent 模式端点，跑通"用户提问 → AgentLoop 调工具检索 → 流式返回回答"的完整链路。

---

## 核心设计决策（请遵循）

1. **不改 LLMService / ChatClient** — AgentLoop 保持自己的 OkHttp 直调百炼 API 方式，不动现有 LLM 调用链。这是 MVP，后续再统一。
2. **工具走进程内调用** — 不走 HTTP 调 mcp-server，直接在 bootstrap 进程内调用检索 + rerank 服务。
3. **SSE 流式输出用最简方案** — AgentLoop 同步跑完所有轮次，拿到最终回答后，通过 SseEmitter 流式返回。不需要改 AgentLoop 内部为流式。
4. **不动 Pipeline** — 原有的 `/rag/v3/chat` 完全不碰，新建独立端点。

---

## 实施步骤

### 步骤 1：搬运核心类到 bootstrap

将 experiment 目录下的以下类复制到 bootstrap 项目中，放在新 package `com.nageoffer.ai.ragent.rag.agentloop`（或参考 bootstrap 已有包结构选择合适位置）：

- `Tool.java`
- `ToolResult.java`
- `AgentLoop.java`
- `AgentLoopResult.java`

**不需要搬的：**
- `McpToolAdapter.java` — 我们不走 MCP 适配，而是直接写一个进程内工具
- `EchoTool.java` / `KnowledgeSearchTool.java` — 实验用的，不需要

**搬运后需要做的调整：**
- 修改 package 声明和 import 路径
- AgentLoop 中调百炼 API 的 URL、API Key 等配置，需要从 bootstrap 的配置体系中读取（查看 BaiLianChatClient 是如何获取 apiKey 和 baseUrl 的，保持一致）
- 确认 Gson 和 OkHttp 依赖在 bootstrap 的 pom.xml 中已存在（大概率已有，因为 BaiLianChatClient 也在用）

### 步骤 2：创建进程内检索工具

新建一个类实现 `Tool` 接口，用于在 AgentLoop 中被调用：

```java
// 建议文件名：KnowledgeSearchWithRerankTool.java
// 放在 agentloop 包下

public class KnowledgeSearchWithRerankTool implements Tool {
    
    private final MilvusRetrieverService retrieverService;
    private final RerankService rerankService;
    
    // 通过构造器注入（由调用方传入 Spring Bean）
    
    @Override
    public String getName() { return "knowledge_search_with_rerank"; }
    
    @Override
    public String getDescription() { /* 参考 mcp-server 中的 KnowledgeSearchWithRerankMCPExecutor 的描述 */ }
    
    @Override
    public String getInputSchema() { /* 参考实验中的 schema 定义 */ }
    
    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 1. 从 input 中取 query 参数
        // 2. 调用 MilvusRetrieverService.retrieve() 获取候选
        // 3. 调用 RerankService.rerank() 精排
        // 4. 格式化结果为文本返回
        // 具体逻辑参考 mcp-server 中 KnowledgeSearchWithRerankMCPExecutor.execute() 的实现
        // 以及 RetrieveController.retrieveWithRerank() 的实现
    }
}
```

**关键：** 看 `KnowledgeSearchWithRerankMCPExecutor` 和 `RetrieveController.retrieveWithRerank()` 的具体实现，复用它们的检索+rerank 逻辑。如果 bootstrap 中已经有一个封装好的 Service 方法可以直接调用（一步完成检索+rerank），就直接用，不要重新组装。

### 步骤 3：创建 AgentLoop Service

新建一个 Spring Service，负责组装和运行 AgentLoop：

```java
@Service
public class AgentLoopService {
    
    @Autowired private MilvusRetrieverService retrieverService;
    @Autowired private RerankService rerankService;
    @Autowired private ConversationMemoryService memoryService;
    
    // 从配置中读取百炼 API 的 key 和 url（和 BaiLianChatClient 一致）
    @Value("${ragent.llm.bailian.api-key}") private String apiKey;
    @Value("${ragent.llm.bailian.base-url}") private String baseUrl;
    
    public void runAgent(String question, String conversationId, String userId, SseEmitter emitter) {
        // 异步执行，不阻塞 Controller 线程
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 加载对话历史（复用现有 memoryService）
                List<ChatMessage> history = memoryService.load(conversationId, userId);
                
                // 2. 组装 system prompt
                String systemPrompt = buildSystemPrompt();
                
                // 3. 构建 messages（system + history + user question）
                // 注意：AgentLoop 用的是 JsonObject 格式的 messages，
                // 需要把 ChatMessage 转成 AgentLoop 能用的格式
                
                // 4. 创建工具
                Tool searchTool = new KnowledgeSearchWithRerankTool(retrieverService, rerankService);
                
                // 5. 创建并运行 AgentLoop
                AgentLoop loop = new AgentLoop(apiKey, baseUrl, systemPrompt, List.of(searchTool), 6);
                AgentLoopResult result = loop.run(messages);
                
                // 6. 拿到最终回答，通过 SseEmitter 流式发送
                //    最简方案：把 finalContent 按字符或按 chunk 分批发送，模拟流式效果
                //    或者直接一次性发送（取决于前端是否需要逐字显示）
                streamToEmitter(result.getFinalContent(), emitter);
                
                // 7. 保存 assistant 回复到对话历史
                memoryService.append(conversationId, userId, ChatMessage.assistant(result.getFinalContent()));
                
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
    }
}
```

**注意：** 以上是伪代码示意，具体实现需要你根据实际的类和方法来调整。关键是：
- 配置项的 key 名称要看 bootstrap 的 application.yml 实际写法
- AgentLoop 构造器参数要看实验代码的实际签名
- messages 格式转换要兼容 AgentLoop 内部的 JsonObject 结构

### 步骤 4：创建 Controller 端点

```java
@RestController
@RequestMapping("/api/ragent")
public class AgentChatController {
    
    @Autowired private AgentLoopService agentLoopService;
    
    @GetMapping(value = "/agent/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter agentChat(
        @RequestParam String question,
        @RequestParam(required = false) String conversationId
    ) {
        SseEmitter emitter = new SseEmitter(0L);  // 无超时，和现有 Pipeline 一致
        String userId = /* 从上下文获取，参考 RAGChatController 的做法 */;
        
        agentLoopService.runAgent(question, conversationId, userId, emitter);
        return emitter;
    }
}
```

**参考 RAGChatController 的实现**，保持一致的：
- 端点风格（GET vs POST、参数传递方式）
- userId 获取方式
- conversationId 生成逻辑
- SseEmitter 配置
- SSE 事件格式（前端期望的数据结构）

### 步骤 5：System Prompt

```text
你是一个知识库问答助手。你有以下工具可用：
- knowledge_search_with_rerank：在知识库中搜索相关内容

使用规则：
1. 收到用户问题后，先判断是否需要搜索知识库
2. 搜索结果中，rerank_score > 0.85 表示高度相关，< 0.75 表示低相关
3. 如果搜索结果质量不够，可以换关键词重新搜索
4. 基于搜索结果回答时，引用具体内容
5. 如果知识库中确实没有相关信息，诚实告知
```

这个 prompt 在实验中已验证有效，直接使用即可。可以硬编码在 AgentLoopService 中，后续再考虑配置化。

---

## 验证方式
注意：我们目前在服务器部署bootstrap和mcp-server的，所以一切完成后我来编译上传到服务器然后重启服务即可，然后我会到网页进行手动访问验证效果

合入完成后，按以下方式验证：

1. **启动 bootstrap**，确认无启动报错
2. **Pipeline 不受影响**：调用 `/rag/v3/chat?question=测试` 确认正常
3. **Agent 模式基本功能**：调用 `/api/ragent/agent/chat?question=Ragent的整体架构是什么`，确认：
   - 返回 SSE 流式响应
   - 日志中能看到 AgentLoop 调用了 knowledge_search_with_rerank 工具
   - 返回内容基于知识库
4. **查看日志**：确认 AgentLoop 的轮次、工具调用、最终回答都有记录

---

## 注意事项

- **最小化原则**：如果某个步骤遇到复杂问题（比如消息格式转换很麻烦），优先选择最简方案绕过，不要陷入完美主义。能跑通就行。
- **配置复用**：百炼 API 的 key、url、模型名等配置，从 bootstrap 现有配置中读取，不要硬编码新的。
- **错误处理**：MVP 阶段做基本的 try-catch + 日志即可，不需要精细的错误恢复。
- **如果发现某个步骤的实际情况与本任务描述不符**（比如 AgentLoop 的构造器参数不同、检索 Service 的接口不一样），以实际代码为准，灵活调整。在最终报告中说明你的实际做法和原因。

---

## 产出要求

完成后生成 `CC_Report_002_Implementation.md`，包含：
1. 实际创建/修改了哪些文件（列出路径）
2. 每个步骤中遇到的问题和你的解决方式
3. 与本任务描述不同的实际做法及原因
4. 验证结果（能否编译通过、能否启动）
5. 剩余待解决的问题（如果有）
