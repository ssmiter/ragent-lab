# CC 任务：实验1 — 真正的 Agent Loop

## 背景

实验0已通过：Agent Loop 骨架 + 百炼 API Function Calling 验证成功。现在要从"空转循环"进化到"真正能回答知识库问题的 Agent Loop"。

**核心目标：** 把 ragent 的 RAG 能力拆成两个工具（搜索 + 生成），注册到 AgentLoop 中，让模型自主决定调用顺序，最终回答用户的知识库问题。

## 任务分两部分

---

## Part A：从 CC 源码提取 System Prompt 设计模式

### 为什么需要这个

实验0没有 system prompt，模型是"裸跑"的。真正的 agent 需要通过 system prompt 告诉模型：
- 你的角色是什么（知识库助手）
- 你有哪些工具可用（以及什么时候该用哪个）
- 你的行为准则（搜索结果不好时可以重试、不确定时告诉用户）

CC 作为一个成熟的 agent，它的 system prompt 设计一定经过大量迭代。我们需要提取它的设计模式作为参考。

### 请在 claude-code-sourcemap 中查找

**目标文件：** 可能在 `src/` 下的 `systemPrompt.ts`、`prompt.ts`、或类似命名的文件

**提取内容：**

1. **System Prompt 的整体结构** — CC 的 system prompt 分哪几个部分？是纯文本还是有模板变量？
2. **工具说明是怎么嵌入的** — 工具的 description 是在 system prompt 里写，还是完全依赖 tools 参数里的 description？两者有没有重复或互补？
3. **行为指导** — CC 的 system prompt 里有没有类似"如果工具失败就重试""如果不确定就问用户"这样的行为指导？
4. **最小示例** — 提取一段 CC system prompt 的关键片段（不需要完整，只要能看出设计模式）

**输出格式：**
```
### CC System Prompt 设计模式

**结构：** [几个部分，各自的职责]
**工具引导：** [怎么告诉模型用工具]
**行为准则：** [什么样的指导语]
**关键片段：** [示例文本]
**对 ragent agent loop 的启示：** [我们的 system prompt 应该怎么写]
```

---

## Part B：实现实验1代码

### 总体设计

在实验0的 AgentLoop 骨架上，替换 EchoTool 为两个真实工具：

```
Agent Loop
  ├── KnowledgeSearchTool  —— 向量检索 + rerank，返回 chunks + 分数
  ├── GenerateAnswerTool   —— 基于 chunks 生成最终回答
  └── System Prompt        —— 引导模型合理使用工具
```

模型的典型决策路径：
```
用户提问 → 模型调 knowledge_search → 看到 chunks + 分数
         → 分数高：调 generate_answer 生成回答 → 终止
         → 分数低：换个查询再调 knowledge_search → 再决策
```

### 实现细节

#### 1. KnowledgeSearchTool

**功能：** 在指定（或默认）知识库中进行向量检索 + rerank

**实现方式：** 调用 ragent 现有的检索能力。请先调查 ragent 的代码，找到：
- 向量检索的入口方法（可能在 SearchService 或 RetrieveService 中）
- Rerank 的入口方法
- 这些方法需要什么参数（query、collectionName、topK 等）

两种实现路径（请评估哪种更简单）：
- **路径A：直接调 Java 方法** — 如果 experiment 模块能依赖 framework 模块，直接注入 Service 调用
- **路径B：走 HTTP** — 调用 ragent 的 `/api/ragent/retrieve` 接口（和 MCP 的 knowledge_search 一样）

**工具定义：**
```json
{
  "name": "knowledge_search",
  "description": "Search the knowledge base for relevant information. Returns ranked text chunks with relevance scores. Use this when you need to find information to answer the user's question. You can call this multiple times with different queries if the initial results are not satisfactory.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "The search query. Be specific and use keywords relevant to the topic."
      },
      "collection": {
        "type": "string",
        "description": "The knowledge base collection to search in. Options: ragentdocs, ssddocs, dualssddocs. If unsure, omit this to search the default collection.",
        "enum": ["ragentdocs", "ssddocs", "dualssddocs"]
      }
    },
    "required": ["query"]
  }
}
```

**返回格式：** 
```
Found 5 relevant chunks (searched collection: ragentdocs):

[Chunk 1] (score: 0.82, source: deployment_guide.md)
<chunk content...>

[Chunk 2] (score: 0.75, source: architecture.md)
<chunk content...>

...

Score distribution: highest=0.82, lowest=0.45, gap between top1 and top2: 0.07
```

注意：**暴露分数信息给模型**——让模型能判断搜索结果的质量。

#### 2. GenerateAnswerTool

**功能：** 基于提供的 context（chunks），让 LLM 生成最终回答

**注意：** 这个工具的存在是为了让 agent loop 中的"生成"步骤也变成显式的工具调用，而不是模型直接在循环回复中生成。这样 agent loop 的模型只负责"决策和编排"，不负责"生成最终答案"。

但我们要考虑一个问题：**是否真的需要这个工具？** 

在 CC 中，模型在最后一轮不调任何工具，直接输出文本回答——生成是隐式的。我们的 agent loop 也可以这样做：模型搜索到满意的 chunks 后，直接在回复中用自然语言回答，不需要调 generate_answer。

**请评估两种方案：**
- **方案A：有 GenerateAnswerTool** — 模型调它来生成回答（更可控，可以注入特定的 RAG prompt）
- **方案B：无此工具** — 模型搜到 chunks 后自己直接回答（更简洁，更像 CC 的模式）

选择你认为更合理的方案实现。

#### 3. System Prompt

基于 Part A 的调查结果，为 ragent agent loop 写一个 system prompt。需要包含：

- **角色定义**：你是一个知识库助手
- **可用工具说明**：你有 knowledge_search 工具（以及 generate_answer，如果选了方案A）
- **行为指导**：
  - 收到用户问题后，先搜索知识库
  - 如果搜索结果分数高（top1 > 0.7），直接回答
  - 如果搜索结果分数低，尝试换个角度重新搜索（最多重试2次）
  - 如果多次搜索都找不到相关信息，诚实告知用户
  - 回答时引用来源文档名
- **格式要求**：回答使用中文（因为知识库是中文文档）

#### 4. AgentLoop 改造

在实验0的 AgentLoop 基础上：
- 添加 system prompt 支持（在 messages 中加入 `{"role": "system", "content": "..."}` 作为第一条消息）
- 增强日志：每轮打印模型的"思考过程"（如果模型在调工具前有文本输出的话）
- 记录完整的工具调用链路（用于后续对比分析）

#### 5. 测试入口

创建 `AgentLoopExperiment1.java`，包含：

**单问题测试：**
```java
String question = "Ragent的意图树是怎么实现的？";
AgentLoopResult result = agentLoop.run(question);
// 打印：轮次、每轮工具调用、最终回答
```

**批量对比测试（如果时间允许）：**
用以下问题测试（这是 ragent 现有的评测问题）：
1. "Ragent系统的整体架构是什么？"
2. "分块策略是怎么实现的？"
3. "意图识别的流程是什么？"
4. "MCP工具是怎么注册和调用的？"
5. "多模型降级策略怎么工作？"
6. "前端是什么技术栈？"

对每个问题，记录：
- agent loop 走了几轮？
- 模型调了什么工具、什么顺序？
- 有没有主动重新搜索的情况？
- 最终回答的质量（和 pipeline 的 ragent_qa 对比）

### 输出

1. CC system prompt 设计模式分析（Part A）
2. 实验1代码（KnowledgeSearchTool + 可能的 GenerateAnswerTool + System Prompt + 测试入口）
3. 至少一个问题的完整运行日志
4. 如果有需要我配置或手动操作的步骤，列出清单

### 关键约束

- **experiment 模块独立** — 不改 ragent 现有代码
- **优先走 HTTP 调用** — 如果直接依赖 framework 模块太复杂，走 HTTP 调 ragent 的接口更简单
- **日志要详尽** — 这是实验，每个决策点都要可观测
- **先跑通一个问题** — 不要一上来就批量测试，先确保单个问题的完整闭环
