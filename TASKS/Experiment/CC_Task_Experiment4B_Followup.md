# CC Task: 实验 4B 修复 + 重跑

## 要做的事

两件事，按顺序：

### 1. 修复重复压缩 bug

当前问题：同一 tool_result 在每个 Agent Turn 都被重新压缩，导致 LLM 浪费调用。

日志证据：
```
11:02:06.773 Autocompact tool_result (Turn 1, Age 4): 78298 chars -> 394 chars
11:02:34.972 Autocompact tool_result (Turn 1, Age 4): 394 chars -> 336 chars  # 重复压缩
```

修复方向：在已压缩的 tool_result 中添加标记（比如前缀 `[Autocompact]`），AutocompactProcessor 检测到标记就跳过。同时确保 SessionMemoryWriter 不重复写入已有的摘要。

### 2. 用 qwen-max 重跑 Run 3

Run 1/2 用的是 qwen-max，Run 3 用的是 qwen3.6-plus，模型不一致导致行为维度不可比（工具调用 28→8 可能是模型差异而非 Autocompact 效果）。

修复 bug 后，用 **qwen-max** 重跑 Run 3。额度有限，只跑一次。

同时，如果方便的话，在摘要 Prompt 中加入**原始用户问题**作为上下文，让摘要更贴合讨论主题（上次报告中提到的改进点）。

## 输出

1. 修复后的 AutocompactProcessor.java
2. 新的 Run 3 Transcript JSONL（标注为 qwen-max）
3. 新的 session_memory.md
4. 更新 README.md 中的 Run 3 数据（替换旧数据，旧数据可保留在附录作为参考）
5. 重点记录：修复后 LLM 调用次数降了多少，以及 qwen-max 下的工具调用次数是否回到 Run 1/2 的水平

## 运行

```bash
mvnw exec:java -pl experiment -Dexec.mainClass=...AgentLoopExperiment4 -Dexec.args="apiKey autocompact"
```

模型改回 qwen-max，其余参数不变。
