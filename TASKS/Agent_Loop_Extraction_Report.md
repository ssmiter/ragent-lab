# Agent Loop 最小骨架提取报告

本报告从 claude-code-sourcemap 源码提取三个关键信息点，为在 ragent 中实现 mini Agent Loop 提供参考。

---

## 信息点 1：循环驱动的最小骨架

**定位：** `src/query.ts` 第306-1728行

**最小代码片段：**

```typescript
// 状态定义（跨迭代传递）
type State = {
  messages: Message[]
  toolUseContext: ToolUseContext
  turnCount: number
  // ... 其他字段
}

async function* queryLoop(params: QueryParams) {
  // 初始化状态
  let state: State = {
    messages: params.messages,
    toolUseContext: params.toolUseContext,
    turnCount: 1,
    // ...
  }

  // 主循环
  while (true) {
    const { messages, turnCount } = state

    // 1. 调用模型
    for await (const message of deps.callModel({
      messages,
      systemPrompt,
      tools: toolUseContext.options.tools,
      // ...
    })) {
      if (message.type === 'assistant') {
        assistantMessages.push(message)

        // 关键：检查是否有 tool_use
        const toolUseBlocks = message.message.content.filter(
          content => content.type === 'tool_use'
        )
        if (toolUseBlocks.length > 0) {
          toolUseBlocks.push(...toolUseBlocks)
          needsFollowUp = true  // 设置继续标志
        }
      }
    }

    // 2. 终止条件：没有 tool_use
    if (!needsFollowUp) {
      return { reason: 'completed' }
    }

    // 3. 执行工具
    for await (const update of runTools(toolUseBlocks, assistantMessages, canUseTool, toolUseContext)) {
      if (update.message) {
        toolResults.push(update.message)
      }
    }

    // 4. maxTurns 护栏
    const nextTurnCount = turnCount + 1
    if (maxTurns && nextTurnCount > maxTurns) {
      return { reason: 'max_turns', turnCount: nextTurnCount }
    }

    // 5. 更新状态，继续下一轮
    state = {
      messages: [...messages, ...assistantMessages, ...toolResults],
      toolUseContext,
      turnCount: nextTurnCount,
      // ...
    }
  }
}
```

**数据流：**

```
初始 messages → 调用模型 → 检查响应
                              ↓
                    有 tool_use 块？
                    /          \
                  是            否
                  ↓             ↓
            执行工具        return 终止
            收集结果
                  ↓
            更新 state.messages
                  ↓
            检查 maxTurns
                  ↓
            continue 下一轮
```

**祛魅：** 就是一个 `while(true)` 循环，唯一的退出条件是"模型响应中没有 tool_use"，状态在迭代间通过 `state` 对象传递。

**迁移到 ragent 的启示：**

```java
// Java 伪代码
public class AgentLoop {
    private List<Message> messages;
    private int turnCount = 1;
    private int maxTurns;

    public Terminal run() {
        while (true) {
            // 1. 调用 LLM
            LLMResponse response = callModel(messages);

            // 2. 检查是否有 tool_use
            List<ToolUseBlock> toolUses = extractToolUses(response);
            if (toolUses.isEmpty()) {
                return Terminal.completed();  // 终止
            }

            // 3. 执行工具
            List<ToolResult> results = executeTools(toolUses);

            // 4. 护栏检查
            if (++turnCount > maxTurns) {
                return Terminal.maxTurnsReached();
            }

            // 5. 更状态，继续下一轮
            messages.addAll(response.getMessages());
            messages.addAll(results);
        }
    }
}
```

---

## 信息点 2：工具定义的序列化格式

**定位：**
- 工具接口：`src/Tool.ts` 第362-695行
- 工具示例：`src/tools/GlobTool/GlobTool.ts` 第57-198行
- 序列化函数：`src/utils/api.ts` 第119-266行

**最小代码片段：**

```typescript
// 1. 工具定义（使用 buildTool）
export const GlobTool = buildTool({
  name: 'Glob',
  description: async () => 'Find files by name pattern...',
  inputSchema: z.strictObject({
    pattern: z.string().describe('The glob pattern'),
    path: z.string().optional().describe('The directory to search'),
  }),

  async call(input, context) {
    // 执行逻辑
    return { data: { filenames: [...], numFiles: 10 } }
  },

  mapToolResultToToolResultBlockParam(output, toolUseID) {
    return {
      tool_use_id: toolUseID,
      type: 'tool_result',
      content: output.filenames.join('\n'),
    }
  },
})

// 2. 序列化到 API 格式
async function toolToAPISchema(tool: Tool): Promise<BetaTool> {
  return {
    name: tool.name,
    description: await tool.prompt(...),
    input_schema: zodToJsonSchema(tool.inputSchema),  // Zod → JSON Schema
  }
}

// 3. Zod → JSON Schema 转换
function zodToJsonSchema(schema: ZodTypeAny): JsonSchema7Type {
  return toJSONSchema(schema)  // zod/v4 内置方法
}
```

**数据流：**

```
Zod Schema (inputSchema)
        ↓ zodToJsonSchema()
JSON Schema (input_schema)
        ↓
{
  name: "Glob",
  description: "...",
  input_schema: {
    type: "object",
    properties: {
      pattern: { type: "string", description: "..." },
      path: { type: "string", description: "..." }
    },
    required: ["pattern"]
  }
}
        ↓ 发送到 Claude API
tools: [...] 参数
```

**祛魅：** 工具定义就是三个字段：`name`（工具名）、`description`（描述文档）、`input_schema`（JSON Schema 格式的参数定义），用 Zod 定义后自动转换。

**迁移到 ragent 的启示：**

```java
// Java 实现
public class ToolDefinition {
    private String name;
    private String description;
    private JsonNode inputSchema;  // JSON Schema

    // 从 Java 类生成 JSON Schema
    public static ToolDefinition fromClass(Class<?> inputClass) {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaGenerator generator = new JsonSchemaGenerator(mapper);
        JsonNode schema = generator.generateSchema(inputClass);

        return new ToolDefinition()
            .setName("vector_search")
            .setDescription("Search in vector database...")
            .setInputSchema(schema);
    }
}

// 工具接口
public interface Tool {
    String getName();
    String getDescription();
    JsonNode getInputSchema();
    ToolResult call(Map<String, Object> input, ToolContext context);
    ToolResultBlock mapToResult(Object output, String toolUseId);
}
```

---

## 信息点 3：最轻量的护栏实现

**定位：**
- maxTurns：`src/query.ts` 第1705-1711行
- 错误反馈：`src/services/tools/toolExecution.ts` 第396-411行、第1715-1737行

**最小代码片段：**

```typescript
// 1. maxTurns 检查（在工具执行后、下一轮迭代前）
const nextTurnCount = turnCount + 1
if (maxTurns && nextTurnCount > maxTurns) {
  yield createAttachmentMessage({
    type: 'max_turns_reached',
    maxTurns,
    turnCount: nextTurnCount,
  })
  return { reason: 'max_turns', turnCount: nextTurnCount }
}

// 2. 错误反馈（is_error: true 的构造）
// 工具不存在时
yield {
  message: createUserMessage({
    content: [{
      type: 'tool_result',
      content: `<tool_use_error>Error: No such tool available: ${toolName}</tool_use_error>`,
      is_error: true,  // 关键字段
      tool_use_id: toolUse.id,
    }],
  }),
}

// 工具执行异常时
yield {
  message: createUserMessage({
    content: [{
      type: 'tool_result',
      content: `<tool_use_error>${detailedError}</tool_use_error>`,
      is_error: true,
      tool_use_id: toolUse.id,
    }],
  }),
}
```

**数据流：**

```
工具调用
    ↓
异常/错误？
    ↓
构造 tool_result:
{
  type: "tool_result",
  tool_use_id: "xxx",
  content: "<error message>",
  is_error: true    ← 模型看到这个字段知道出错了
}
    ↓
添加到 messages
    ↓
下一轮迭代模型看到错误信息
```

**祛魅：** 护栏就两行代码：`if (turnCount > maxTurns) return` 防无限循环，错误反馈就是在 `tool_result` 里加 `is_error: true` 让模型知道失败了。

**迁移到 ragent 的启示：**

```java
// Java 实现
public class Safeguards {
    private int maxTurns;
    private int currentTurn = 0;

    public boolean shouldContinue() {
        return ++currentTurn <= maxTurns;
    }

    public ToolResult errorResult(String toolUseId, String errorMessage) {
        return ToolResult.builder()
            .toolUseId(toolUseId)
            .type("tool_result")
            .content("<tool_use_error>" + errorMessage + "</tool_use_error>")
            .isError(true)  // 关键字段
            .build();
    }
}

// 在 AgentLoop 中使用
if (!safeguards.shouldContinue()) {
    return Terminal.maxTurnsReached();
}

// 工具执行时
try {
    result = tool.call(input, context);
} catch (Exception e) {
    result = safeguards.errorResult(toolUseId, e.getMessage());
}
```

---

## 总结：20行伪代码骨架

```python
def agent_loop(messages, tools, max_turns=10):
    turn_count = 0

    while True:
        turn_count += 1

        # 调用模型
        response = call_llm(messages, tools)

        # 提取 tool_use
        tool_uses = [b for b in response.content if b.type == 'tool_use']

        # 终止条件：没有工具调用
        if not tool_uses:
            return response

        # 护栏：maxTurns
        if turn_count >= max_turns:
            return Terminal('max_turns')

        # 执行工具
        results = []
        for tool_use in tool_uses:
            try:
                output = tools[tool_use.name].call(tool_use.input)
                results.append(ToolResult(tool_use.id, output))
            except Exception as e:
                results.append(ToolResult(tool_use.id, str(e), is_error=True))

        # 更新消息
        messages.append(response)
        messages.append(UserMessage(results))
```

这就是 Agent Loop 的本质：**循环 → 调模型 → 检查 tool_use → 执行工具 → 拼结果 → 下一轮**。所有复杂的代码（权限、压缩、Hook、错误恢复）都是为了驯服这个简单循环的概率性。