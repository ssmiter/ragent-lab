# CC 任务：实验0 — Agent Loop 空转循环验证

## 背景

我们正在 ragent 项目的 `experiment` 目录下实现一个 mini Agent Loop。这是一个从"固定 pipeline"到"模型驱动循环"的范式实验。

**核心骨架（已从 CC 源码提炼）：**

```
while (true) {
    response = callLLM(messages, tools)
    toolUses = extractToolUses(response)
    if (toolUses.isEmpty()) return response   // 模型不调工具 = 终止
    if (turnCount > maxTurns) return "超限"    // 护栏
    results = executeTools(toolUses)           // 执行工具
    messages.add(response)                     // 拼回历史
    messages.add(results)                      // 拼回历史
    turnCount++
}
```

**目标：** 在 ragent 中用百炼 API 跑通这个循环。这是所有后续实验的基础设施。

## 你需要做的事情（按顺序）

### 第一步：调查百炼 API 的 Function Calling 支持

**问题：** ragent 当前通过百炼 API 调用 LLM（qwen 系列），但现有代码只用了 chat 和 streamChat，没有用过 function calling / tools 功能。

**请调查：**

1. 查看 ragent 现有的 LLM 调用代码，找到：
   - 百炼 API 的调用方式（用的是 DashScope SDK？还是直接 HTTP？）
   - 现有的 model 配置（用的哪个模型、API key 怎么管理的）
   - 请求和响应的数据结构

2. 查阅百炼/DashScope 的 function calling 文档或 SDK 源码，确认：
   - 百炼 API 是否支持 tools 参数（类似 OpenAI 的 function calling）
   - 请求格式：tools 定义怎么传（name/description/parameters 的 JSON 结构）
   - 响应格式：tool_use / function_call 在响应中长什么样
   - 是否支持多轮对话中 tool_result 的回传

3. 如果百炼 API 的 function calling 格式和 OpenAI/Claude 不同，记录差异点。

**输出：** 一份简短的兼容性报告，包含请求/响应的示例 JSON。

### 第二步：在 experiment 目录下搭建 AgentLoop 骨架

**位置：** `experiment/src/main/java/com/ragent/experiment/agentloop/`（如果 experiment 模块的目录结构不同，请适配）

**先看一下 experiment 模块当前的结构**，了解它是怎么组织的，然后在合适的位置创建以下类：

#### 2.1 核心类：AgentLoop

```java
/**
 * Mini Agent Loop — 模型驱动的 while(true) 循环
 *
 * 核心逻辑：
 * 1. 调用 LLM（带 tools 参数）
 * 2. 检查响应中是否有 tool_use
 * 3. 有 → 执行工具 → 结果拼回 messages → 继续
 * 4. 没有 → 终止，返回最终回答
 */
public class AgentLoop {
    private final LlmClient llmClient;          // 调用百炼 API
    private final Map<String, Tool> tools;       // 注册的工具
    private final int maxTurns;                  // 护栏：最大轮次
    
    public AgentLoopResult run(String userQuery) {
        // 实现 while(true) 循环
    }
}
```

#### 2.2 工具接口：Tool

```java
/**
 * Agent Loop 的工具接口
 * 对标 CC 源码中的 Tool 接口，最小化版本
 */
public interface Tool {
    String getName();
    String getDescription();
    String getInputSchema();  // JSON Schema 字符串
    ToolResult execute(Map<String, Object> input);
}
```

#### 2.3 测试工具：EchoTool

```java
/**
 * 最简单的工具，用于验证循环骨架
 * 输入什么就返回什么
 */
public class EchoTool implements Tool {
    @Override
    public String getName() { return "echo"; }
    
    @Override
    public String getDescription() { 
        return "Echo back the input message. Use this tool when you want to repeat or confirm something."; 
    }
    
    @Override
    public ToolResult execute(Map<String, Object> input) {
        return ToolResult.success(getName(), "Echo: " + input.get("message"));
    }
}
```

#### 2.4 数据类：ToolResult、AgentLoopResult

根据百炼 API 的实际格式来设计这些数据类。需要包含：
- `ToolResult`：toolUseId、content、isError
- `AgentLoopResult`：finalResponse、turnCount、toolCallHistory（记录每轮调了什么工具）

#### 2.5 简单的测试入口

一个 main 方法或者 Spring Boot 的 CommandLineRunner 或 Test，能直接运行：

```
输入："Please echo the message 'hello world'"
预期：模型调用 echo 工具 → 得到结果 → 模型生成最终回答（包含 "hello world"）→ 循环终止
观测：打印每轮的 turnCount、模型的 tool_use 内容、工具返回结果、最终回答
```

### 第三步：运行并记录结果

如果代码能编译通过但运行需要配置（API key 等），告诉我需要什么配置。

如果能直接运行，记录：
- 循环执行了几轮？
- 每轮模型返回了什么（tool_use 的 JSON）？
- 工具执行结果是什么？
- 模型最终回答是什么？
- 有没有意外行为（比如模型不断调用 echo 不停止）？

## 关键约束

1. **不改 ragent 现有代码**——所有新代码放在 experiment 目录下
2. **尽量复用 ragent 已有的基础设施**——LLM 调用、API key 管理、Spring 配置等，不要重新造轮子
3. **先跑通再优化**——代码可以丑，但必须能验证循环闭环
4. **详细的日志**——每轮循环打印完整的输入输出，这是我们观测实验结果的唯一窗口

## 预期产出

1. 百炼 API function calling 兼容性简报（可以内联在代码注释里）
2. `experiment/` 下的 AgentLoop 骨架代码（可编译）
3. 运行结果记录（如果能跑的话）或者"需要什么才能跑"的清单

## 参考

- CC 源码骨架：见 `TASKS/Agent_Loop_Extraction_Report.md`
- ragent 现有 LLM 调用：在 `framework/` 或 `infra-ai/` 下找 LLM 相关的 service
- 百炼 API 文档：如果本地没有，可以搜索 DashScope SDK 的 function calling 用法
