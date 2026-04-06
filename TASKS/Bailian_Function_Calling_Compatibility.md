# 百炼 API Function Calling 兼容性报告

## 概述

ragent 项目使用百炼 API（DashScope）调用 LLM，采用 OpenAI 兼容格式：
- 端点：`/compatible-mode/v1/chat/completions`
- 认证：Bearer Token（API Key）
- 格式：OpenAI 兼容

## Function Calling 支持

### 结论：完全支持

百炼 API 完全支持 OpenAI 格式的 Function Calling（工具调用）。

### 请求格式

```json
POST /compatibl
e-mode/v1/chat/completions
Authorization: Bearer sk-xxx
Content-Type: application/json

{
  "model": "qwen-plus-latest",
  "messages": [
    {"role": "user", "content": "Please echo 'hello'"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "echo",
        "description": "Echo back the input message",
        "parameters": {
          "type": "object",
          "properties": {
            "message": {
              "type": "string",
              "description": "The message to echo"
            }
          },
          "required": ["message"]
        }
      }
    }
  ]
}
```

### 响应格式（模型调用工具）

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1712345678,
  "model": "qwen-plus-latest",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_xxx",
            "type": "function",
            "function": {
              "name": "echo",
              "arguments": "{\"message\": \"hello\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ]
}
```

### 响应格式（模型返回最终回答）

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "I've echoed the message for you..."
      },
      "finish_reason": "stop"
    }
  ]
}
```

### 工具结果回传

```json
{
  "model": "qwen-plus-latest",
  "messages": [
    {"role": "user", "content": "Please echo 'hello'"},
    {
      "role": "assistant",
      "tool_calls": [
        {
          "id": "call_xxx",
          "type": "function",
          "function": {
            "name": "echo",
            "arguments": "{\"message\": \"hello\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_xxx",
      "content": "Echo: hello"
    }
  ]
}
```

## 与 OpenAI 格式的差异

### 相同点

| 特性 | 百炼 | OpenAI |
|-----|-----|--------|
| tools 参数结构 | ✓ 相同 | ✓ |
| tool_calls 响应结构 | ✓ 相同 | ✓ |
| tool 消息格式 | ✓ 相同 | ✓ |
| JSON Schema 参数定义 | ✓ 相同 | ✓ |

### 差异点

| 特性 | 百炼 | OpenAI |
|-----|-----|--------|
| 端点路径 | `/compatible-mode/v1/chat/completions` | `/v1/chat/completions` |
| 认证方式 | Bearer Token (API Key) | Bearer Token (API Key) |
| 可用模型 | qwen-plus, qwen-max, qwen-turbo | gpt-4, gpt-3.5-turbo |

## 支持工具调用的模型

| 模型 | 工具调用支持 | 备注 |
|-----|------------|------|
| qwen-plus-latest | ✓ | 推荐 |
| qwen-max | ✓ | 推荐 |
| qwen-turbo | ✓ | 高性价比 |
| qwen-long | ✓ | 长文本 |

## 错误处理

### 工具执行失败

```json
{
  "role": "tool",
  "tool_call_id": "call_xxx",
  "content": "Error: tool execution failed",
  "is_error": true
}
```

注意：百炼 API 可能不支持 `is_error` 字段，需要通过 content 内容告知模型错误信息。

### 常见错误码

| HTTP 状态码 | 含义 | 处理建议 |
|------------|------|---------|
| 400 | 请求格式错误 | 检查 JSON 格式 |
| 401 | 认证失败 | 检查 API Key |
| 429 | 请求限流 | 降低请求频率 |
| 500 | 服务器错误 | 重试 |

## 实现建议

### 1. 扩展 ChatRequest

```java
public class ChatRequest {
    // 现有字段...

    // 新增：工具定义
    private List<ToolDefinition> tools;

    // 新增：工具调用结果（用于回传）
    private List<ToolCallResult> toolResults;
}
```

### 2. 扩展 BaiLianChatClient

在 `buildRequestBody` 方法中添加 tools 序列化：

```java
private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
    JsonObject reqBody = new JsonObject();
    // ... 现有代码 ...

    // 添加工具定义
    if (CollUtil.isNotEmpty(request.getTools())) {
        JsonArray toolsArray = new JsonArray();
        for (ToolDefinition tool : request.getTools()) {
            toolsArray.add(gson.fromJson(tool.toJson(), JsonObject.class));
        }
        reqBody.add("tools", toolsArray);
    }

    return reqBody;
}
```

### 3. 解析工具调用响应

```java
private List<ToolCall> extractToolCalls(JsonObject message) {
    if (!message.has("tool_calls")) {
        return Collections.emptyList();
    }

    JsonArray toolCalls = message.getAsJsonArray("tool_calls");
    List<ToolCall> result = new ArrayList<>();
    for (JsonElement elem : toolCalls) {
        JsonObject tc = elem.getAsJsonObject();
        result.add(new ToolCall(
            tc.get("id").getAsString(),
            tc.getAsJsonObject("function").get("name").getAsString(),
            tc.getAsJsonObject("function").get("arguments").getAsString()
        ));
    }
    return result;
}
```

## 参考资料

- 百炼 API 文档：https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api
- OpenAI Function Calling：https://platform.openai.com/docs/guides/function-calling
- ragent 配置：`bootstrap/src/main/resources/application.yaml`