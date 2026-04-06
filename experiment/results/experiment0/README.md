# Experiment 0：Agent Loop 骨架验证

## 实验目标

验证 `while(true)` 循环骨架是否正常工作：
- 循环能正确启动
- 模型能正确调用工具
- 工具结果能正确回传
- 循环能正确终止

## 实验配置

| 配置项 | 值 |
|-------|-----|
| 提供商 | 百炼 (DashScope) |
| 模型 | qwen-plus-latest |
| 工具 | echo（mock 工具） |
| 最大轮次 | 10 |

## 核心验证点

```java
while (true) {
    turnCount++;                              // 轮次计数
    if (turnCount > maxTurns) return;         // 护栏

    response = callLLM(messages, tools);      // 调用 LLM

    toolCalls = extractToolCalls(response);   // 解析工具调用
    if (toolCalls.isEmpty()) return response; // 无调用则终止

    results = executeTools(toolCalls);        // 执行工具
    messages.add(response);
    messages.add(results);                    // 回传结果
}
```

## 结果

✅ 全部验证通过：
- 循环启动、工具调用、参数解析、结果回传、循环终止
- 百炼 API 完全兼容 OpenAI Function Calling 格式

## 文件

- [result.md](./result.md) - 详细运行日志与 Token 使用分析