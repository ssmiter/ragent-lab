# Agent Loop 底层原理详解

## 核心问题

1. `Turn x` 期间发生了什么？
2. while(true) 循环是一直在运行还是在阻塞？
3. 20秒的等待是在等什么？

---

## 一、完整执行流程（时间线视角）

```
时间轴 ──────────────────────────────────────────────────────────────►

[启动 main 线程]
    │
    ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Turn 1 开始                                                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ 1. turnCount++                                    [耗时: 0.1ms] │ │
│ │ 2. 检查 maxTurns 护栏                             [耗时: 0.1ms] │ │
│ │ 3. 构建 HTTP 请求体（messages + tools）            [耗时: 5ms]   │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                          │                                          │
│                          ▼                                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ 4. OkHttp 发送 POST → 百炼 API                    [耗时: 15-25s] │ │
│ │                                                                  │ │
│ │    ★★★ 这里是 20 秒等待的真相 ★★★                               │ │
│ │    - main 线程 BLOCKED，等待 HTTP 响应                          │ │
│ │    - while 循环暂停在这一行                                      │ │
│ │    - CPU 空闲，可以处理其他线程                                  │ │
│ │                                                                  │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                          │                                          │
│                          ▼                                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ 5. 收到 HTTP 响应                                 [耗时: 50ms]  │ │
│ │ 6. 解析 JSON，提取 tool_calls                     [耗时: 1ms]   │ │
│ │ 7. tool_calls 不为空？继续执行工具                [耗时: 0.1ms] │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                          │                                          │
│                          ▼                                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ 8. 执行工具（如 knowledge_search）                [耗时: 2-5s]  │ │
│ │    - 这里的耗时主要是网络请求到 ragent 服务器                    │ │
│ │    - 也是 HTTP 同步调用，线程阻塞                                │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                          │                                          │
│                          ▼                                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ 9. 将工具结果拼回 messages 列表                   [耗时: 0.1ms] │ │
│ └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼ while(true) 循环继续
┌─────────────────────────────────────────────────────────────────────┐
│ Turn 2 开始                                                          │
│ ... (重复上述流程)                                                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 二、代码逐行解析

### 核心循环结构

```java
while (true) {
    turnCount++;
    log.info("\n---------- Turn {} ----------", turnCount);

    // ① 护栏检查
    if (turnCount > maxTurns) {
        return AgentLoopResult.maxTurnsReached(...);
    }

    // ② 调用 LLM（★ 这里阻塞 20 秒 ★）
    String response = callLLMWithTools(messages);

    // ③ 解析响应
    JsonObject responseJson = gson.fromJson(response, JsonObject.class);
    JsonObject message = extractMessage(responseJson);

    // ④ 检查是否有 tool_calls
    List<ToolCallInfo> toolCalls = extractToolCalls(message);

    if (toolCalls.isEmpty()) {
        // 没有工具调用 = 模型返回最终回答 = 循环终止
        return AgentLoopResult.completed(...);
    }

    // ⑤ 执行工具
    for (ToolCallInfo tc : toolCalls) {
        Tool tool = tools.get(tc.toolName);
        ToolResult result = tool.execute(parseArguments(tc.arguments));

        // ⑥ 将工具结果拼回消息历史
        messages.add(createToolResultMessage(result));
    }
}
```

### 20秒等待的真相

```java
private String callBaiLianAPI(JsonObject requestBody) {
    // 创建 OkHttpClient（设置超时 60 秒）
    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // ← 读超时 60 秒
            .build();

    // 构建 HTTP 请求
    okhttp3.Request request = new okhttp3.Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .post(...)
            .build();

    // ★★★ 同步执行 HTTP 请求 ★★★
    try (okhttp3.Response response = client.newCall(request).execute()) {
        //                    ↑
        //                    └── execute() 是同步调用
        //                        当前线程 BLOCKED，直到收到响应或超时
        //
        // 这 20 秒期间：
        // - main 线程被操作系统挂起（WAITING 状态）
        // - while 循环停在这里，不会继续执行
        // - CPU 可以去执行其他线程
        // - 底层 Socket 在等待网络数据包

        return response.body().string();
    }
}
```

---

## 三、阻塞 vs 非阻塞

### 当前实现：同步阻塞

```
main 线程状态变化：

[RUNNABLE] → 构建 HTTP 请求 → [RUNNABLE]
    ↓
    发送 HTTP 请求 → [WAITING] ← 阻塞在这里，等待网络响应
    ↓                              （约 20 秒）
    收到响应 → [RUNNABLE]
    ↓
    解析 JSON → [RUNNABLE]
    ↓
    执行工具 → [WAITING] ← 工具内部的 HTTP 请求也会阻塞
    ↓                              （约 2-5 秒）
    回到循环开头 → [RUNNABLE]
```

### 为什么选择同步阻塞？

1. **简单直观**：代码执行顺序和逻辑顺序一致
2. **易于调试**：堆栈跟踪清晰，异常处理简单
3. **Agent Loop 场景适合**：
   - 每一轮都依赖上一轮的结果
   - 不存在"并行处理"的需求
   - 串行执行是业务逻辑的自然表达

### 如果用异步会怎样？

```java
// 异步版本（伪代码）
client.newCall(request).enqueue(new Callback() {
    @Override
    public void onResponse(Response response) {
        // 回调在另一个线程执行
        // 需要处理线程同步、状态管理等复杂问题
    }
});

// 问题：
// 1. while 循环结构被打散，变成状态机
// 2. 异常处理复杂化
// 3. 调试困难
// 4. 对于 Agent Loop 这种串行场景，异步没有明显收益
```

---

## 四、while(true) 循环的本质

### 不是"一直运行"，而是"不断暂停"

```java
while (true) {
    // 快速操作（毫秒级）
    turnCount++;
    checkMaxTurns();
    buildRequest();

    // ★ 长时间暂停（秒级）★
    response = callLLM();  // 阻塞 ~20 秒

    // 快速操作（毫秒级）
    parseResponse();
    executeTool();  // 阻塞 ~2 秒

    // 继续下一轮
}
```

### 时间分配（以 Turn 1 为例）

| 操作 | 耗时 | 占比 |
|-----|------|------|
| turnCount++ / 护栏检查 | 0.1ms | 0.0005% |
| 构建 HTTP 请求体 | 5ms | 0.025% |
| 等待 LLM 响应 | 20s | **99.97%** |
| 解析响应 | 1ms | 0.005% |
| 执行工具 | 2s | 9% (相对于 LLM 响应) |
| **Turn 1 总耗时** | **~22s** | |

**结论**：Agent Loop 99% 的时间都在等待 LLM 响应。

---

## 五、与 Claude Code 的对比

### Claude Code 的实现

Claude Code 的 Agent Loop 也是同步阻塞模式，但有以下不同：

1. **流式响应（SSE）**：边生成边接收，不等完整响应
2. **多模型并行**：可以同时调用多个模型
3. **Hook 系统**：在关键节点注入自定义逻辑

### 为什么我们不用 SSE？

当前实验使用的是**非流式 API**：
- 完整响应生成后才返回
- 适合调试和学习（能看到完整的 tool_calls）
- 后续可以升级为 SSE 流式模式

---

## 六、常见误解澄清

### ❌ 误解1：while(true) 会占用大量 CPU

**真相**：while 循环 99% 的时间都在等待网络 I/O，线程被挂起，CPU 几乎空闲。

### ❌ 误解2：阻塞会导致程序"卡死"

**真相**：阻塞是线程级别的，不影响其他线程。如果 main 线程阻塞，JVM 仍然可以处理其他线程、GC 等。

### ❌ 误解3：异步一定比同步快

**真相**：对于串行依赖的场景（如 Agent Loop），异步不能减少总等待时间。异步的价值在于：
- 并行处理多个独立任务
- 不阻塞 UI 线程
- 高并发服务器

---

## 七、实际运行示例

### 日志时间戳分析

```
11:39:04.432  ← Turn 1 开始
11:39:04.434  ← 发送 HTTP 请求
               （阻塞 ~2.3 秒）
11:39:06.770  ← 收到 LLM 响应
11:39:06.774  ← 工具执行完成
11:39:06.774  ← Turn 1 结束

Turn 1 总耗时：2.34 秒
其中等待 LLM：2.3 秒（98%）
```

### 如果响应时间更长

```
11:42:42.296  ← Turn 1 开始
               （阻塞 ~20 秒）
11:42:43.660  ← 收到响应？不对，这里是工具执行时间

实际时间线：
11:42:42.296  ← 发送 HTTP 请求
               （阻塞等待 LLM）
11:42:43.660  ← LLM 响应到达（实际等待 ~1.4 秒）
11:42:43.662  ← 工具执行完成
```

---

## 八、总结

### Agent Loop 的本质

```
Agent Loop = 状态机 + 阻塞式 I/O

每轮 Turn 的生命周期：
  [RUNNABLE: 准备请求]
       ↓
  [WAITING: 等待 LLM] ← 主要时间消耗
       ↓
  [RUNNABLE: 处理响应]
       ↓
  [WAITING: 执行工具] ← 次要时间消耗
       ↓
  [RUNNABLE: 拼接历史]
       ↓
  回到循环开头
```

### 核心洞察

1. **while(true) 不是"一直跑"**，而是"不断暂停-恢复"
2. **20 秒等待是正常的**，这是 LLM 推理的时间
3. **阻塞不是问题**，对于串行场景是合理的架构选择
4. **性能瓶颈在网络**，不在循环结构

### 后续优化方向

如果需要提升性能，可以考虑：

1. **流式响应（SSE）**：边生成边处理，减少感知延迟
2. **并行工具调用**：如果多个工具调用相互独立，可以并行执行
3. **缓存**：对相同的查询缓存 LLM 响应
4. **更快的模型**：使用推理速度更快的模型