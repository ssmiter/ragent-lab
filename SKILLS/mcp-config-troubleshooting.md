# Claude Code MCP 配置排坑指南

> 本文档记录了 Ragent 项目 MCP Server 配置过程中遇到的所有问题及解决方案，供后续参考。

## 最终配置

### `.mcp.json` 配置文件

```json
{
  "mcpServers": {
    "ragent-knowledge": {
      "type": "http",
      "url": "http://localhost:9099/mcp",
      "timeout": 30000,
      "headers": {
        "Accept": "application/json"
      }
    }
  }
}
```

### SSH 隧道命令（保持安全性）

```bash
ssh -L 9099:localhost:9099 -L 9090:localhost:9090 ubuntu@101.42.96.96 -N
```

---

## 排坑记录

### 坑 1：协议版本错误

**问题**：服务器返回 `protocolVersion: "2026-02-28"`，但 MCP 官方版本是 `"2024-11-05"`

**现象**：MCP 健康检查失败，curl 测试正常

**解决**：修改 `MCPDispatcher.java`

```java
// 错误
result.put("protocolVersion", "2026-02-28");

// 正确
result.put("protocolVersion", "2024-11-05");
```

**文件位置**：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/endpoint/MCPDispatcher.java:65`

---

### 坹 2：JSON-RPC 响应格式违反规范（最关键）

**问题**：Lombok `@Data` 会序列化所有字段，成功响应包含 `error: null`

**现象**：
```json
// 错误格式 - 包含 error: null
{"jsonrpc":"2.0","id":"1","result":{...},"error":null}

// 正确格式 - 不应包含 error 字段
{"jsonrpc":"2.0","id":"1","result":{...}}
```

**原因**：JSON-RPC 2.0 规范要求成功响应只包含 `result`，失败响应只包含 `error`，两者不能同时存在

**解决**：在 `JsonRpcResponse.java` 添加 Jackson 注解

```java
import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // 关键！
public class JsonRpcResponse {
    // ...
}
```

**文件位置**：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/JsonRpcResponse.java`

---

### 坑 3：inputSchema 序列化包含 null 字段

**问题**：`tools/list` 响应中 `inputSchema.properties` 包含 `"enumValues": null`

**现象**：
```json
// 错误格式
"properties": {
  "query": {
    "type": "string",
    "description": "...",
    "enumValues": null  // ← 多余字段，不符合 JSON Schema 规范
  }
}

// 正确格式
"properties": {
  "query": {
    "type": "string",
    "description": "..."
  }
}
```

**原因**：`MCPToolSchema.PropertyDef` 使用 Lombok `@Data`，会序列化所有字段包括 null

**解决**：给 MCPToolSchema 及其内部类添加 `@JsonInclude(NON_NULL)`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // 关键！
public class MCPToolSchema {
    // ...

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)  // 内部类也需要
    public static class InputSchema {
        // ...
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)  // 内部类也需要
    public static class PropertyDef {
        // ...
    }
}
```

**文件位置**：
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/protocol/MCPToolSchema.java`
- `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/core/MCPToolDefinition.java`

**这是导致 MCP 工具无法加载到 Claude Code 工具列表的根本原因！**

---

### 坑 4：缺少 ping 方法

**问题**：MCP 健康检查可能调用 `ping` 方法，服务器未实现

**现象**：调用 `ping` 返回 `Unknown method: ping`

**解决**：在 `MCPDispatcher.java` 添加 ping 处理

```java
return switch (method) {
    case "initialize" -> handleInitialize(id);
    case "ping" -> handlePing(id);  // 新增
    case "tools/list" -> handleToolsList(id);
    case "tools/call" -> handleToolsCall(id, request.getParams());
    default -> JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + method);
};

private JsonRpcResponse handlePing(Object id) {
    return JsonRpcResponse.success(id, new LinkedHashMap<>());
}
```

---

### 坑 5：知识库检索服务地址和认证

**问题**：MCP Server 调用知识库检索时 URL 和认证不正确

**原始代码**：
```java
private static final String BOOTSTRAP_RETRIEVE_URL = "http://localhost:9090/api/ragent/retrieve";
// 无 Authorization header
```

**解决**：修改为公网地址 + 添加认证

```java
private static final String BOOTSTRAP_RETRIEVE_URL = "http://101.42.96.96/api/ragent/retrieve";
private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";

HttpRequest httpRequest = HttpRequest.newBuilder()
    .uri(URI.create(BOOTSTRAP_RETRIEVE_URL))
    .header("Content-Type", "application/json")
    .header("Authorization", AUTH_TOKEN)  // 新增
    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
    .build();
```

**SSH 隧道模式下改回**：
```java
private static final String BOOTSTRAP_RETRIEVE_URL = "http://localhost:9090/api/ragent/retrieve";
```

**文件位置**：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/tools/KnowledgeSearchMCPExecutor.java`

---

### 坑 6：配置文件传输类型

**问题**：最初使用 `"type": "streamable-http"`，但 Claude Code 使用 `"type": "http"`

**解决**：使用 `claude mcp add` 命令添加配置

```bash
claude mcp add --transport http ragent-knowledge http://localhost:9099/mcp -s project
```

---

### 坑 7：SSH 隧道端口映射

**问题**：只映射了 9099（MCP Server），未映射 9090（Bootstrap 检索服务）

**解决**：同时映射两个端口

```bash
ssh -L 9099:localhost:9099 -L 9090:localhost:9090 ubuntu@101.42.96.96 -N
```

---

## 验证步骤

### 1. 测试 MCP 端点连通性

```bash
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

期望输出（不含 error 字段）：
```json
{"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"ragent-mcp-server","version":"0.0.1"}}}
```

### 2. 测试健康检查

```bash
claude mcp list
```

期望输出：
```
ragent-knowledge: http://localhost:9099/mcp (HTTP) - ✓ Connected
```

### 3. 测试知识库搜索

```bash
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"knowledge_search","arguments":{"query":"milvus","top_k":3}}}'
```

---

## 关键总结

| 坑 | 严重程度 | 解决难度 | 核心原因 |
|----|----------|----------|----------|
| JSON-RPC 响应格式 | ⭐⭐⭐⭐⭐ | 低 | 不了解 JSON-RPC 规范 |
| inputSchema null 字段 | ⭐⭐⭐⭐⭐ | 低 | Lombok 序列化所有字段 |
| 协议版本 | ⭐⭐⭐⭐ | 低 | 随意填写版本号 |
| 缺 ping 方法 | ⭐⭐⭐ | 低 | 未实现完整 MCP 方法集 |
| 检索服务认证 | ⭐⭐⭐ | 低 | 忽略认证要求 |
| SSH 隧道端口 | ⭐⭐ | 低 | 只映射一个端口 |

---

## 相关文件修改清单

```
mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/
├── endpoint/MCPDispatcher.java           # protocolVersion + ping 方法
├── protocol/JsonRpcResponse.java         # @JsonInclude 注解
├── protocol/MCPToolSchema.java           # @JsonInclude 注解
├── core/MCPToolDefinition.java           # @JsonInclude 注解
└── tools/KnowledgeSearchMCPExecutor.java # URL + Authorization

.mcp.json                                 # MCP 配置文件
```

---

## 参考资料

- [MCP 规范 2024-11-05](https://spec.modelcontextprotocol.io/specification/2024-11-05/)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
- [Claude Code MCP 配置指南](https://docs.anthropic.com/claude-code/mcp)