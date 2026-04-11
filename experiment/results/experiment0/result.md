# Agent Loop 实验 0 — 执行结果报告

## 实验时间

2026-04-04 18:06

## 实验目的

验证 Agent Loop 骨架是否正常工作：
- 循环能正确启动
- 模型能正确调用工具
- 工具结果能正确回传
- 循环能正确终止

## 实验配置

| 配置项 | 值 |
|-------|-----|
| 提供商 | 百炼 (DashScope) |
| 模型 | qwen-plus-latest |
| 工具 | echo |
| 最大轮次 | 10 |

## 用户输入

```
Please use the echo tool to echo the message 'hello world'. After receiving the result, provide a brief summary.
```

## 执行过程

### Turn 1

**请求：**
```json
{
  "model": "qwen-plus-latest",
  "messages": [
    {"role": "user", "content": "Please use the echo tool to echo the message 'hello world'..."}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "echo",
        "description": "Echo back the input message...",
        "parameters": {...}
      }
    }
  ]
}
```

**响应（模型调用工具）：**
```json
{
  "choices": [{
    "message": {
      "content": "",
      "role": "assistant",
      "tool_calls": [{
        "function": {
          "arguments": "{\"message\": \"hello world\"}",
          "name": "echo"
        },
        "id": "call_e72bacb0958d45529390fb",
        "type": "function"
      }]
    },
    "finish_reason": "tool_calls"
  }],
  "usage": {"prompt_tokens": 189, "completion_tokens": 19, "total_tokens": 208}
}
```

**工具执行：**
- 工具: `echo`
- 参数: `{"message": "hello world"}`
- 结果: `Echo: hello world`
- 状态: ✓ 成功

### Turn 2

**请求（带工具结果）：**
```json
{
  "messages": [
    {"role": "user", "content": "Please use the echo tool..."},
    {"role": "assistant", "tool_calls": [...]},
    {"role": "tool", "tool_call_id": "call_e72bacb0958d45529390fb", "content": "Echo: hello world"}
  ]
}
```

**响应（模型返回最终回答）：**
```json
{
  "choices": [{
    "message": {
      "content": "The echo tool successfully repeated the message \"hello world\" back as requested.",
      "role": "assistant"
    },
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 226, "completion_tokens": 15, "total_tokens": 241}
}
```

## 执行报告

```
========== Agent Loop 执行报告 ==========
终止原因: COMPLETED
总轮次: 2
工具调用次数: 1

工具调用历史:
  [Turn 1] echo({"message": "hello world"}) -> ✓

最终响应:
The echo tool successfully repeated the message "hello world" back as requested.
========================================
```

## Token 使用

| 轮次 | Prompt Tokens | Completion Tokens | Total |
|-----|---------------|-------------------|-------|
| Turn 1 | 189 | 19 | 208 |
| Turn 2 | 226 | 15 | 241 |
| **总计** | 415 | 34 | 449 |

## 结论

### ✅ 验证通过

1. **循环启动** ✓ - while(true) 循环正确启动
2. **工具调用** ✓ - 模型正确识别并调用 echo 工具
3. **参数解析** ✓ - 模型正确生成 `{"message": "hello world"}` 参数
4. **结果回传** ✓ - 工具结果正确拼接到消息历史
5. **循环终止** ✓ - 模型返回最终回答后循环正确终止
6. **护栏生效** ✓ - maxTurns 护栏就位（本次未触发）

### 关键观察

1. **百炼 API 兼容性确认**：百炼 API 完全支持 OpenAI 格式的 Function Calling
2. **模型行为符合预期**：qwen-plus-latest 正确理解工具定义并调用
3. **循环闭环验证**：`用户问题 → 模型调工具 → 执行工具 → 结果回传 → 模型回答 → 终止` 完整闭环

### 核心骨架验证

```java
while (true) {
    turnCount++;                              // Turn 1, Turn 2
    if (turnCount > maxTurns) return;         // 护栏（未触发）

    response = callLLM(messages, tools);      // 调用百炼 API

    toolCalls = extractToolCalls(response);   // Turn 1: 1个调用, Turn 2: 0个调用
    if (toolCalls.isEmpty()) return response; // Turn 2 触发终止

    results = executeTools(toolCalls);        // echo("hello world") → "Echo: hello world"
    messages.add(response);
    messages.add(results);                    // 回传工具结果
}
```

## 下一步

Agent Loop 基础骨架已验证通过，可以进行后续实验：

1. **实验 1**：将 ragent 的 pipeline 步骤包装为工具
2. **实验 2**：多工具编排与决策
3. **实验 3**：错误恢复与重试机制
4. **实验 4**：上下文压缩与预算控制