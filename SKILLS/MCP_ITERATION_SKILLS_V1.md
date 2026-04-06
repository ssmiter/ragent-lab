# MCP 系统迭代 V1：从启用到打通知识库检索工具

> 承接 RAG_ITERATION_SKILLS_V2，这一轮的主题是"让 Ragent 从被动回答变成可被外部 Agent 主动调用的工具"。
> 2026-03-27，最小可行实现完成。

---

## 一、我们做了什么，为什么这样做

### 起点

RAG 系统已经能回答问题，但每次使用都需要人工传话——CC 不知道知识库里有什么，Claude 不知道 Ragent 的实现细节。"人工 ReAct 循环"的瓶颈在于：**规划者是人，工具执行靠手动。**

MCP 要解决的问题：让 Agent 自己知道"我能用哪些工具"，自己决定什么时候调用，自己拿到结果。

### 核心认知

**MCP 之于 Agent，就像 USB 之于外设。** 没有标准之前，每个工具都要单独对接；有了 MCP，任何实现了协议的工具都能被任何支持 MCP 的 Agent 调用。

Ragent 的 mcp-server 模块已经完整实现了 MCP 协议层（SSE 端点、POST 端点、JSON-RPC 2.0 握手），我们不需要碰协议，只需要：
1. 把 mcp-server 进程跑起来
2. 实现 `MCPToolExecutor` 接口，注册新工具
3. 在意图树配置节点，触发工具调用

**从"了解 MCP"到"把整个 Ragent 系统变成一个 MCP Server"** ——这是这一轮最重要的视角转变。

---

## 二、实施路径与关键决策

### 阶段一：侦察现状

**关键发现（来自服务器日志 + 代码扫描）：**

| 项目 | 发现 |
|------|------|
| 进程状态 | mcp-server 从未部署，服务器上只有 bootstrap jar |
| 端口冲突 | 文档写 9091，但 9091 被 Milvus 占用；实际代码配置是 9099 |
| 连接配置 | bootstrap 的 `application.yaml` 里有 `rag.mcp.servers[0].url=http://localhost:9099` |
| 内置工具 | weather_query / ticket_query / sales_query（均为模拟数据） |

**教训：文档和代码不一致时，永远以代码为准。**

### 阶段二：启动 mcp-server

**部署方式：** 和 bootstrap 完全一致，打包 jar + systemd 管理。

```bash
# 打包
./mvnw clean package -DskipTests -pl mcp-server -am

# 上传
scp mcp-server\target\mcp-server-0.0.1-SNAPSHOT.jar ubuntu@服务器IP:/home/ubuntu/ragent/

# systemd 配置（/etc/systemd/system/ragent-mcp.service）
[Unit]
Description=Ragent MCP Server
After=network.target ragent.service

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/ragent
ExecStart=/usr/bin/java -jar /home/ubuntu/ragent/mcp-server-0.0.1-SNAPSHOT.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/home/ubuntu/ragent/mcp.log
StandardError=append:/home/ubuntu/ragent/mcp.log

[Install]
WantedBy=multi-user.target
```

**验证成功的标志：**
```
# mcp.log
MCP 工具注册成功, toolId: weather_query
MCP 工具注册成功, toolId: ticket_query  
MCP 工具注册成功, toolId: sales_query
Started MCPServerApplication in 4.599 seconds

# app.log（重启 bootstrap 后）
MCP Server [default] 返回 3 个工具   ← 之前是 ERROR，现在变成 INFO
注册远程 MCP 工具: toolId=weather_query, server=default
```

### 阶段三：验证 weather_query 工具调用链路

在数据库 `t_intent_node` 表插入 MCP 类型节点：

```sql
INSERT INTO `t_intent_node` (
  `intent_code`, `name`, `level`, `kind`, `mcp_tool_id`,
  `description`, `examples`, `param_prompt_template`,
  `sort_order`, `enabled`
) VALUES (
  'weather_query', '天气查询', 1, 2, 'weather_query',
  '用户询问某个城市的天气情况，包括当前天气、温度、天气预报等实时气象信息',
  '["北京今天天气怎么样？","上海明天会下雨吗？","深圳这周的天气预报"]',
  '从用户问题中提取城市名称和时间范围。返回JSON格式：{"city": "城市名", "days": 天数}。',
  10, 1
);
```

**意图树字段说明：**

| 字段 | 说明 |
|------|------|
| `kind = 2` | MCP 工具调用类型（0=RAG知识库，1=系统，2=MCP） |
| `mcp_tool_id` | 对应 mcp-server 注册的 toolId |
| `param_prompt_template` | 专属字段，LLM 用它从用户问题里提取工具参数 |
| `description + examples` | 决定意图路由能否命中，逻辑和 RAG 节点完全一致 |

插入后必须清除 Redis 意图树缓存才生效。

**验证日志：**
```
MCP 参数提取完成, toolId: weather_query, 使用自定义提示词: true, 参数: {city=北京, days=1}
```

### 阶段四：实现 KnowledgeSearchTool（核心创新）

**动机：** weather_query 是模拟数据，意义有限。真正有价值的是把 Ragent 自己的检索能力暴露为 MCP 工具——让 CC 能直接查知识库，不需要人工传话。

**架构决策：**

```
方案A（全链路）：query → 重写 → 意图识别 → RetrievalEngine → chunks
  ✗ 入参需要 SubQuestionIntent，构造复杂

方案B（向量检索层）：query → MilvusRetrieverService.retrieve(RetrieveRequest) → chunks  
  ✓ 直接调用，20行代码，先跑通再升级
```

选方案B作为最小可行实现。

**新增两个文件：**

**文件1：bootstrap 轻量检索接口**

```java
// bootstrap/.../rag/controller/RetrieveController.java
@RestController
@RequiredArgsConstructor
@RequestMapping("/retrieve")   // 实际路径：/api/ragent/retrieve
public class RetrieveController {
    private final MilvusRetrieverService milvusRetrieverService;

    @PostMapping
    public Result<List<RetrievedChunk>> retrieve(@RequestBody RetrieveRequest request) {
        List<RetrievedChunk> chunks = milvusRetrieverService.retrieve(request);
        return Results.success(chunks);
    }
}
```

**文件2：mcp-server 知识库检索工具**

```java
// mcp-server/.../tools/KnowledgeSearchMCPExecutor.java
@Slf4j
@Component
public class KnowledgeSearchMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "knowledge_search";
    private static final String BOOTSTRAP_URL = "http://localhost:9090/api/ragent/retrieve";
    private static final String AUTH_TOKEN = "你的Token";

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("在知识库中检索技术文档、项目资料、实验记录等内容")
                .parameters(Map.of(
                        "query", ParameterDef.builder().description("检索问题").required(true).build(),
                        "collection_name", ParameterDef.builder().description("知识库集合名，不填用默认").required(false).build(),
                        "top_k", ParameterDef.builder().description("返回片段数，默认5").type("integer").build()
                ))
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        String query = request.getStringParameter("query");
        String collectionName = request.getStringParameter("collection_name");
        // collectionName 为空时用默认值，传入时覆盖
        String collection = (collectionName != null && !collectionName.isBlank()) 
                ? collectionName : "ragentdocs";
        // ... HTTP 调用 bootstrap，格式化返回 chunks
    }
}
```

---

## 三、踩坑记录

| 坑 | 原因 | 解决 |
|----|------|------|
| 端口 9091 冲突 | Milvus 占用了 9091，文档描述是旧版本 | 实际端口 9099，以代码为准 |
| 404 not found | bootstrap 有全局路径前缀 `/api/ragent`，Controller 不能重复写 `/api` | `@RequestMapping("/retrieve")` 而不是 `/api/retrieve` |
| Windows curl 多行命令报错 | `\` 换行在 Windows curl 不支持 | 改成单行，字符串内用 `\"` 转义 |
| collection not found | 未传 collectionName 时用了默认值 `rag_default_store`，实际集合名是 `ragentdocs` | 显式传入或设置正确默认值 |
| B000001 系统异常 | 认证通过但执行报错 | 看 `app.log` 的堆栈，不要只看返回码 |

---

## 四、MCPToolExecutor 实现规范

实现新工具的完整模板，基于实际代码确认的 API 签名：

```java
@Component
public class XxxMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "xxx_tool";

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("工具描述，LLM 根据此决定何时调用")
                .parameters(Map.of(
                        "param1", MCPToolDefinition.ParameterDef.builder()
                                .description("参数说明")
                                .required(true)   // 必填
                                .build(),
                        "param2", MCPToolDefinition.ParameterDef.builder()
                                .description("可选参数")
                                .type("integer")  // 默认 string
                                .defaultValue(5)
                                .required(false)
                                .build()
                ))
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        // 取参数（注意：是 getStringParameter，不是 getParam）
        String param1 = request.getStringParameter("param1");
        
        // 返回结果
        return MCPToolResponse.success(TOOL_ID, "结果内容");
        // 返回错误
        // return MCPToolResponse.error(TOOL_ID, "ERROR_CODE", "错误描述");
    }
}
```

**注册方式：** 只需加 `@Component`，`DefaultMCPToolRegistry` 启动时自动扫描注册，无需改任何框架代码。

---

## 五、当前系统状态快照

```
服务进程:
  bootstrap     → 9090 (systemd: ragent.service)
  mcp-server    → 9099 (systemd: ragent-mcp.service)

已注册 MCP 工具（4个）:
  weather_query    → 天气查询（模拟数据）
  ticket_query     → 工单查询（模拟数据）
  sales_query      → 销售查询（模拟数据）
  knowledge_search → 知识库检索（真实数据）✅ 本轮新增

意图树 MCP 节点:
  weather_query    → 已配置并验证
  knowledge_search → 待配置意图节点（下一步）

检索接口:
  POST /api/ragent/retrieve
  Body: {"query":"...", "topK":5, "collectionName":"ragentdocs"}
  Header: Authorization: <token>
  返回: {"code":"0", "data":[{"id":"...","text":"...","score":0.xx}]}
```

---

## 六、未完成的线索

**关于 KnowledgeSearchTool 的升级方向：**
- 当前走向量检索层（无 rerank），后续可升级为调全链路检索（含 rerank + 自适应截断）
- `collectionName` 目前硬编码默认值，后续可从意图节点的 `collection_name` 字段动态读取
- 认证 Token 硬编码在代码里，应改为配置文件注入

**关于更大的闭环：**
- 知识库检索 MCP 工具跑通后，下一步是在意图树配置 `knowledge_search` 节点，让 Ragent 对话时能自动路由到知识库检索工具
- 再下一步：CC 本地配置 MCP，直接连 Ragent 的 mcp-server，实现"CC 自己查知识库"的自动化 ReAct

**关于 SFT 的连接点：**
- knowledge_search 工具调用产生的 (query, chunks, 最终回答) 三元组，是天然的 SFT 训练数据
- 每次工具调用都是一次"系统如何理解问题、检索什么、回答什么"的完整记录
