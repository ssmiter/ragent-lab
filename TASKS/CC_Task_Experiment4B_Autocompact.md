# CC Task: 实验 4B — Autocompact + 摘要积累

## 背景

实验 4A 已完成，核心发现：
- Microcompact 将旧 tool_result 压缩为 68 字符索引，节省 94% 上下文，模型行为不受影响
- 但 68 字符（"知识库: ragentdocs, 最高分 0.86"）信息量极低——模型只知道"搜过"，不知道"搜到了什么"
- 压缩后的上下文结构趋近于 Pipeline（只有模型自己的历史结论），Loop 的信息优势被压缩到仅剩近期几轮的 chunk 窗口

**本次实验的核心问题：** 如果让 LLM 为旧检索结果生成一段有信息量的摘要（而不是 68 字符索引），模型在后续轮次的表现会更好吗？这些摘要如果被积累起来，是否构成一份有价值的"会话知识"？

## 已有资源

- `experiment/results/experiment4/` — 4A 的完整数据（Run 1 baseline + Run 2 Microcompact）
- `TranscriptWriter.java` — Transcript 持久化组件（4A 已实现）
- `MicrocompactProcessor.java` — Microcompact 处理器（4A 已实现）
- `AgentLoopExperiment4.java` — 多轮对话实验入口（4A 已实现）
- 4A 的 15 轮对话问题脚本（可复用）

## 目标

### 目标一：实现 AutocompactProcessor

在 Microcompact 的基础上，新增一个 AutocompactProcessor，核心区别：

- **Microcompact**（已有）：纯代码逻辑，把旧 tool_result 替换为 68 字符索引
- **Autocompact**（新增）：调 LLM 为旧 tool_result 生成 200-300 字符的摘要

摘要应该保留的不是原文的缩写，而是**经过理解后的要点**。具体 Prompt 设计留给你，但方向是：让 LLM 回答"这次检索中最重要的发现是什么、来自哪里、和当前讨论有什么关系"。

**LLM 选择：** 摘要生成用小模型（qwen-turbo 或类似的便宜快速模型），不用主模型。这是元任务，不需要深度推理。如果百炼 API 有合适的轻量模型，优先选用。

### 目标二：摘要积累到 session_memory.md

每次 Autocompact 生成摘要时，除了替换到上下文中，同时把摘要**追加写入**一个 `session_memory.md` 文件。格式自行设计，但应该让人（或下一次会话的模型）能快速扫一眼就知道"这次对话中讨论了什么、搜到了什么关键信息"。

这个文件不需要模型在当前会话中读取——它是为**未来**准备的。当前会话中模型通过上下文里的摘要获取信息。

### 目标三：对比实验

用 4A 相同的 15 轮对话问题，跑 Run 3（Autocompact）。

**Run 1 和 Run 2 的数据已经有了**（在 experiment4 目录下），不需要重跑。Run 3 需要输出和 4A 相同格式的数据，以便直接对比。

重点观测：
- Run 3 vs Run 2 的上下文字符对比（摘要比索引多占多少空间？）
- Run 3 的 Turn 14-15 总结题回答质量（是否比 Run 2 更详细？能否引用更多具体概念？）
- session_memory.md 的最终内容（人工判断其质量）
- Autocompact 的 LLM 调用次数和耗时（成本评估）

## 实现约束

1. **在 experiment/ 目录内完成**，不碰 ragent 主管道代码
2. **复用 4A 的基础设施**（TranscriptWriter、AgentLoop、实验入口），在此基础上扩展
3. **Autocompact 的触发条件和 Microcompact 保持一致**（相同的 turn 窗口阈值），这样两者的对比才公平
4. **模型选择用 qwen-max**（和 4A 一致），摘要生成用轻量模型

## 输出

1. `AutocompactProcessor.java` — Autocompact 处理器
2. Run 3 的 Transcript JSONL
3. `session_memory.md` — 会话结束后的摘要积累文件
4. 更新 `experiment/results/experiment4/README.md`，增加 Run 3 的数据和三轮对比分析
5. 如果在实现过程中发现了更好的摘要设计、更合理的触发策略、或者任何预期之外的现象，**请在报告中详细记录**——意外发现往往比预设问题的答案更有价值

## 运行方式

```bash
# Run 3 - autocompact
mvnw exec:java -pl experiment -Dexec.mainClass=...AgentLoopExperiment4 -Dexec.args="apiKey autocompact"
```

参数命名和入口复用 4A 的结构，新增 "autocompact" 模式即可。
