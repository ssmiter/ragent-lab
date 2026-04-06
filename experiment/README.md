# Agent Loop 实验 0 — 空转循环验证

## 实验目的

验证 Agent Loop 骨架是否正常工作：
- 循环能正确启动
- 模型能正确调用工具
- 工具结果能正确回传
- 循环能正确终止

## 目录结构

```
experiment/
├── pom.xml
└── src/main/java/com/nageoffer/ai/ragent/experiment/agentloop/
    ├── Tool.java              # 工具接口
    ├── ToolResult.java        # 工具执行结果
    ├── AgentLoop.java         # Agent Loop 核心实现
    ├── AgentLoopResult.java   # 执行结果报告
    ├── AgentLoopExperiment.java  # 测试入口
    └── tools/
        └── EchoTool.java      # Echo 测试工具
```

## 运行方式

### 前置条件

1. **Java 17+**
   ```bash
   # 检查 Java 版本
   java -version
   # 应该显示 17 或更高版本
   ```

2. **百炼 API Key**
   - 从阿里云百炼控制台获取：https://bailian.console.aliyun.com/
   - 或使用环境变量：`DASHSCOPE_API_KEY`

### 编译

```bash
# 在项目根目录执行
./mvnw.cmd compile -pl experiment -am -DskipTests
```

### 运行

```bash
# 方式 1：通过环境变量配置 API Key
DASHSCOPE_API_KEY=sk-xxx ./mvnw.cmd exec:java -pl experiment -Dexec.mainClass=com.nageoffer.ai.ragent.experiment.agentloop.AgentLoopExperiment

# 方式 2：通过命令行参数传递 API Key
./mvnw.cmd exec:java -pl experiment -Dexec.mainClass=com.nageoffer.ai.ragent.experiment.agentloop.AgentLoopExperiment -Dexec.args="sk-xxx"
```

## 预期结果

```
╔══════════════════════════════════════════════════════════════╗
║       Agent Loop 实验 0 — 空转循环验证                        ║
╚══════════════════════════════════════════════════════════════╝

配置信息:
  - 提供商: 百炼 (DashScope)
  - 模型: qwen-plus-latest
  - 工具: [echo]
  - 最大轮次: 10

用户问题: Please use the echo tool to echo the message 'hello world'...

---------- Turn 1 ----------
调用 LLM...
LLM 原始响应: {...}
模型请求调用 1 个工具
执行工具: echo({"message": "hello world"})
工具结果: Echo: hello world (isError=false)

---------- Turn 2 ----------
调用 LLM...
LLM 原始响应: {...}
模型返回最终回答: I've echoed the message "hello world" for you...

========== Agent Loop 执行报告 ==========
终止原因: COMPLETED
总轮次: 2
工具调用次数: 1

工具调用历史:
  [Turn 1] echo({"message": "hello world"}) -> ✓

最终响应:
I've echoed the message "hello world" for you...
========================================
```

## 百炼 API Function Calling 格式说明

### 请求格式

```json
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
        "description": "Echo back the input message...",
        "parameters": {
          "type": "object",
          "properties": {
            "message": {"type": "string", "description": "The message to echo"}
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
  "choices": [
    {
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
      }
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
      }
    }
  ]
}
```

### 工具结果回传格式

```json
{
  "messages": [
    {"role": "user", "content": "Please echo 'hello'"},
    {"role": "assistant", "tool_calls": [...]},
    {"role": "tool", "tool_call_id": "call_xxx", "content": "Echo: hello"}
  ]
}
```

## 注意事项

1. **百炼 API 兼容性**：百炼 API 使用 OpenAI 兼容格式，Function Calling 格式与 OpenAI 一致
2. **模型选择**：需要选择支持 Function Calling 的模型，如 qwen-plus、qwen-max
3. **轮次控制**：maxTurns 用于防止无限循环，建议设置合理值（如 10）
4. **错误处理**：工具执行失败时会返回 `is_error: true`，模型可以看到错误信息并可能重试