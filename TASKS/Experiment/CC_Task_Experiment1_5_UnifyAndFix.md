# CC 任务：实验1.5 — 统一工具接口 + 修复分数 + 对比测试

## 背景

实验1成功验证了 Agent Loop 能驱动真实的知识库搜索。现在需要解决两个问题：

1. **工具接口不统一**：Agent Loop 有自己的 Tool 接口，MCP 有 MCPToolExecutor 接口，同一个检索能力要写两套实现
2. **检索分数偏低**：当前 KnowledgeSearchTool 走的是裸向量检索（/api/ragent/retrieve），没有经过 rerank，导致分数普遍低于 System Prompt 设定的阈值，模型过度重试

## 你需要做的事情

### 第一步：阅读 MCP SKILLS 文档

请先阅读以下文件，了解 ragent 现有的 MCP 工具开发模式：

```
SKILLS/MCP_ITERATION_SKILLS_V1.md
SKILLS/MCP_ITERATION_SKILLS_V2.md
```

重点关注：
- `MCPToolExecutor` 接口的定义（getToolDefinition + execute）
- `MCPToolRequest` 和 `MCPToolResponse` 的数据结构
- 已有的 `KnowledgeSearchMCPExecutor` 和 `RagentQaMCPExecutor` 是怎么实现的
- 工具是怎么被自动注册的（`DefaultMCPToolRegistry` + `@Component`）

### 第二步：设计并实现 McpToolAdapter

**目标：** 写一个适配器，把任何 `MCPToolExecutor` 转换成 Agent Loop 的 `Tool` 接口。

**位置：** `experiment/src/main/java/.../agentloop/McpToolAdapter.java`

**核心逻辑：**

```java
/**
 * 将 MCPToolExecutor（MCP 工具格式）适配为 Agent Loop 的 Tool 接口。
 * 
 * 这样做的好处：
 * 1. 工具只需要实现一次（MCPToolExecutor）
 * 2. 外部 Agent（CC）通过 MCP 协议调用
 * 3. 内部 Agent Loop 通过适配器直接调用（进程内，零 HTTP）
 * 4. 模型看到的工具定义完全一致
 */
public class McpToolAdapter implements Tool {
    
    private final MCPToolExecutor executor;
    
    public McpToolAdapter(MCPToolExecutor executor) {
        this.executor = executor;
    }
    
    @Override
    public String getName() {
        return executor.getToolDefinition().getToolId();
    }
    
    @Override
    public String getDescription() {
        return executor.getToolDefinition().getDescription();
    }
    
    @Override
    public String getInputSchema() {
        // 从 MCPToolDefinition 的 parameters 转换为 JSON Schema 字符串
        // 需要看一下 MCPToolDefinition 的 parameters 具体是什么类型
        return convertParametersToJsonSchema(executor.getToolDefinition().getParameters());
    }
    
    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 构造 MCPToolRequest
        MCPToolRequest request = new MCPToolRequest();
        request.setToolId(getName());
        request.setArguments(input);  // 需要确认字段名
        
        // 直接调用 executor（进程内）
        MCPToolResponse response = executor.execute(request);
        
        // 转换为 ToolResult
        if (response.isSuccess()) {
            return ToolResult.success(getName(), response.getContent());
        } else {
            return ToolResult.error(getName(), response.getErrorMessage());
        }
    }
}
```

**请根据实际的 MCPToolExecutor 接口调整这段代码。** 关键是：
- 看 `MCPToolDefinition` 的字段（toolId、description、parameters 的具体类型）
- 看 `MCPToolRequest` 的字段（怎么传参数）
- 看 `MCPToolResponse` 的字段（怎么取返回值）

### 第三步：创建走完整链路的检索工具

实验1的问题是裸检索没有 rerank，分数低。有两个解决方案：

**方案A：复用 RagentQaMCPExecutor**
- 这个工具走完整 RAG 管道（重写→意图→检索→rerank→截断→生成）
- 但它返回的是最终回答，不是 chunks
- 对 Agent Loop 来说，如果用这个工具，模型就没法看到 chunks 和分数，失去了"判断结果质量→决定是否重试"的能力

**方案B：创建一个新的 MCPToolExecutor，走检索+rerank 但不生成**
- 调用 ragent 的检索服务（向量检索 + rerank + 自适应截断）
- 返回 chunks + rerank 后的分数
- 不做最终生成，让 Agent Loop 的模型自己生成

**方案C：改进现有的 KnowledgeSearchMCPExecutor**
- 在现有裸检索的基础上，加上 rerank 步骤
- 返回 rerank 后的 chunks + 分数

**请评估哪个方案最简单可行，然后实现它。** 优先选能复用现有代码最多的方案。

关键需求：
- 工具返回的结果必须包含 **rerank 后的分数**（不是原始向量距离分数）
- 工具返回的结果必须包含 **chunk 内容 + 来源文档名**
- 分数必须暴露给模型（写在工具返回的文本里）

### 第四步：调整 System Prompt 中的分数阈值

根据 rerank 后的实际分数分布，调整 System Prompt：

**之前的基线数据（裸检索）：** 平均 top1 score 0.71，最高 0.81，最低 0.65

**Rerank 后的分数分布通常更高（0.7-0.95 区间）。** 但具体值需要实验确认。

**临时方案：** 先把阈值设得宽松一些

```markdown
2. 观察搜索结果的分数：
   - 分数 > 0.7: 高度相关，可以直接基于搜索结果回答
   - 分数 0.5-0.7: 中等相关，可以参考，但可能不够全面
   - 分数 < 0.5: 低相关，建议换一个查询重新搜索
```

等跑完6个问题后，根据实际分数分布再微调。

### 第五步：更新 AgentLoop 使用适配器注册工具

在 `AgentLoopExperiment1.java`（或新建 `AgentLoopExperiment1_5.java`）中：

```java
// 之前：手动创建 KnowledgeSearchTool
// agentLoop.registerTool(new KnowledgeSearchTool());

// 现在：通过适配器注册 MCP 工具
MCPToolExecutor searchExecutor = new ImprovedKnowledgeSearchExecutor(...);
agentLoop.registerTool(new McpToolAdapter(searchExecutor));
```

### 第六步：运行6个测试问题并记录结果

**测试问题列表：**

```
Q1: "Ragent系统的整体架构是什么？"
Q2: "分块策略是怎么实现的？"
Q3: "意图识别的流程是什么？"
Q4: "MCP工具是怎么注册和调用的？"
Q5: "多模型降级策略怎么工作？"
Q6: "前端是什么技术栈？"
```

**对每个问题，记录：**

```markdown
### Q[N]: [问题]

**Agent Loop 路径：**
- 总轮次: ?
- 工具调用链: [Turn 1] search("...") → score=? | [Turn 2] search("...") → score=? | ...
- 最终回答: (摘要)
- 是否触发重试: 是/否
- 重试原因: 分数低于阈值 / 模型主动判断 / 未重试

**Pipeline 对比（如果可用）：**
- ragent_qa 的回答: (摘要)
- 哪个更好: Agent Loop / Pipeline / 持平
```

如果直接跑 ragent_qa 对比太复杂，至少记录 Agent Loop 的完整数据。

## 预期产出

1. `McpToolAdapter.java` — 适配器实现
2. 改进后的检索工具（走 rerank 链路）
3. 更新后的 System Prompt（阈值调整）
4. 6个问题的运行结果报告
5. 遇到的问题和你的解决方案

## 最重要的观测目标

跑完6个问题后，我们最想知道的是：

1. **模型在哪些问题上主动重试了？** 重试的查询比原始查询更好吗？
2. **rerank 后的分数分布是什么样的？** 是否需要进一步调整阈值？
3. **Agent Loop 的回答质量和 pipeline 相比如何？** 有没有 loop 明显更好的案例？
4. **有没有模型做出"愚蠢"决策的情况？** 比如明明分数很高却还要重试，或者分数很低却直接回答？

这些观测数据将决定我们是继续优化还是推进实验2（加 rewrite_query 工具）。

## 后续思考：Agent 思路 vs Pipeline 思路

当前实现是典型的 **Agent 思路**：
- 工具是原子能力（检索 → rerank → 返回结果）
- 模型自主决定调用时机、参数、重试策略
- System Prompt 定义行为边界和决策准则

对比 **Pipeline 思路**（后端实现）：
- 预定义流程：rewrite → intent → retrieve → rerank → truncate → generate
- 每个步骤是固定的，由代码编排
- 意图树路由决定检索哪些 collection

**可探索的融合点**：
- 将意图树路由作为一个独立工具，让 Agent 自主决定搜索哪个知识库
- Agent Loop 的优势：可以根据第一次检索结果的质量，动态调整策略
- Pipeline 的优势：确定性流程、可控的延迟、可复用的工作流

**TODO**: 后续实验可对比两种思路的效果差异，特别是在复杂问题（需要多知识库联合检索）的场景下。
