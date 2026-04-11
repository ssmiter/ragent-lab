# CC 任务：实验4A — Transcript 持久化 + Microcompact

## 背景

### 我们走到哪了

```
实验0-3:  Agent Loop 从骨架到跨库综合     → ✅ 价值跃迁验证完成
CC 源码:  记忆/压缩两轮探查              → ✅ 子系统观确立
本次:     Phase 1A — 给 Agent Loop 装上记忆基础设施
```

### 为什么做这个

实验0-3有一个隐含条件：**对话都很短**（最长 Q7 也就 3 轮）。上下文窗口完全够用，不需要任何记忆或压缩机制。

但真实场景中，用户可能围绕一个主题连续追问 15-20 轮。每轮 knowledge_search_with_rerank 返回 ~7000 字符的检索结果（5 chunks × ~1400 字符），10 轮搜索后光历史检索结果就有 ~70000 字符——大部分是和当前问题无关的旧结果，但它们占着上下文窗口、消耗着模型注意力。

这次任务做两件事，都是**纯代码逻辑，不调 LLM，不改模型决策行为**：

1. **Transcript 持久化** — 把对话完整记录写到文件，以后压缩了也能回看
2. **Microcompact** — 清理旧的 tool_result 内容，给上下文窗口腾空间

### 核心目标（带着去做，不是复现 CC）

我们不是要照搬 CC 的实现。CC 是编程智能体，面对的是代码仓库；ragent 是知识问答系统，面对的是文档检索结果。两者的上下文膨胀来源不同：

- CC 的膨胀来源：文件内容读取（Read 工具返回整个文件）
- ragent 的膨胀来源：**检索结果**（每次搜索返回多个 chunk 的全文）

所以 Microcompact 的设计要针对 ragent 的实际膨胀源，而不是套用 CC 的策略。

**本次实验要回答的核心问题：**

1. 长对话场景下，上下文膨胀的实际速度是多少？（用 transcript 数据量化）
2. 清理旧检索结果后，模型行为是否受影响？（对比 compact 前后的回答质量）
3. 什么样的 compact 策略（保留什么、丢弃什么）最适合检索场景？

---

## 你需要做的事情

### 第一步：Transcript 持久化

#### 1.1 设计 Transcript 格式

在 AgentLoop 每轮执行时，把完整的对话记录写入 JSONL 文件。每一行是一个 JSON 对象，代表一条 message。

```jsonl
{"turn":1,"role":"system","content":"你是一个知识助手...","timestamp":"2026-04-07T14:05:00"}
{"turn":1,"role":"user","content":"Ragent 系统的整体架构是什么？","timestamp":"2026-04-07T14:05:01"}
{"turn":1,"role":"assistant","content":"让我搜索相关文档。","tool_calls":[{"name":"knowledge_search_with_rerank","args":{"collection":"ragentdocs","query":"Ragent 系统架构"}}],"timestamp":"2026-04-07T14:05:03"}
{"turn":1,"role":"tool","tool_call_id":"xxx","name":"knowledge_search_with_rerank","content":"在知识库 [ragentdocs] 中找到 5 个相关片段...（完整内容）","timestamp":"2026-04-07T14:05:05"}
{"turn":2,"role":"assistant","content":"根据检索结果，Ragent 的架构...（完整回答）","timestamp":"2026-04-07T14:05:20"}
```

**关键要求：**
- 文件路径：`experiment/results/experiment4/transcripts/{实验ID}_{时间戳}.jsonl`
- **完整记录，不做任何截断** — transcript 是"冷存储"，它的职责是"什么都不丢"
- 包含 token 统计（如果百炼 API 返回 usage 信息，记录 prompt_tokens 和 completion_tokens）
- 每轮写入后立即 flush，不要等对话结束——防止异常中断丢失数据

#### 1.2 在 AgentLoop 中集成

在 `AgentLoop.java` 中添加 Transcript 写入逻辑。**不改现有循环骨架**，只在关键位置插入写入调用：

```
位置1：循环开始前 → 写入 system message
位置2：每轮 LLM 调用前 → 写入 user message（如果有新的）
位置3：LLM 响应后 → 写入 assistant message（含 tool_calls）
位置4：工具执行后 → 写入 tool result message
位置5：循环结束 → 写入统计摘要行
```

建议实现为一个独立的 `TranscriptWriter` 类，AgentLoop 持有它的引用但不依赖它——即使 TranscriptWriter 出错也不影响主循环。

#### 1.3 同时输出对话统计

每轮结束时，在日志中打印当前 messages 列表的统计信息：

```
[Turn 3] messages 统计: 总条数=8, 估算总字符数=23456, 
  其中 tool_result 字符数=18200 (占比 77.6%),
  其中 assistant 字符数=3200 (占比 13.7%),
  其中 user 字符数=2056 (占比 8.7%)
```

这个统计是我们判断"什么时候该 compact"的数据基础。不需要精确的 token 计数（那需要 tokenizer），字符数就够了。

---

### 第二步：Microcompact 实现

#### 2.1 核心逻辑

Microcompact 的目标是：**清理旧的 tool_result 内容，保留结构但丢弃细节。**

具体来说，当一条 tool_result message 的"年龄"超过阈值时（距离当前轮次超过 N 轮），把它的完整内容替换为一行摘要。

**替换前（原始 tool_result，假设 Turn 1 的搜索结果）：**
```
在知识库 [ragentdocs] 中找到 5 个相关片段：

片段1 (相关度: 0.95):
来源: ragent_architecture.md
内容: Ragent 系统采用分层架构设计，核心模块包括...(800字)

片段2 (相关度: 0.88):
来源: ragent_overview.md  
内容: 系统基于 Spring Boot 构建...(600字)

...(共5个片段，总计约7000字符)
```

**替换后（compact 摘要）：**
```
[已压缩的搜索结果 - Turn 1]
工具: knowledge_search_with_rerank
知识库: ragentdocs
查询: "Ragent 系统架构"
返回: 5 个片段, 最高分 0.95, 来源: ragent_architecture.md, ragent_overview.md 等
完整记录已保存至 transcript 文件
```

**这样做的理由：**
- 保留了"搜过什么、从哪个库搜的、得分多少"——模型知道之前搜过这个主题
- 丢弃了 chunk 全文——这些是主要的膨胀源
- 告知了 transcript 路径——如果后续需要，模型可以知道完整记录存在（为任务 B 的 Autocompact 铺路）

#### 2.2 触发条件

建议的初始参数（可调整）：

```java
int COMPACT_AGE_THRESHOLD = 3;  // tool_result 超过 3 轮后被 compact
```

即：当前是 Turn 5，那么 Turn 1 和 Turn 2 的 tool_result 会被 compact，Turn 3/4/5 的保留原样。

**为什么是 3 而不是其他数字：** 从实验3 的 Q7 来看，模型通常在 2-3 轮内完成一个子任务（搜索→补全→回答）。3 轮的窗口保证了当前子任务的搜索结果完整可见，更早的结果可以安全压缩。这个数字可以在实验中调整。

#### 2.3 实现位置

Microcompact 应该在**每轮 LLM 调用前**执行——在 messages 列表发送给模型之前，扫描并替换旧的 tool_result。

```
AgentLoop 主循环中的位置：

while(true) {
    // ← 在这里执行 Microcompact（扫描 messages，替换旧 tool_result）
    response = callLLM(messages, tools)
    ...
}
```

**关键约束：Microcompact 改的是发送给模型的 messages 列表，不改 transcript 文件。** Transcript 始终保持完整。这就是"信息不丢弃，只换存储位置"——信息从"上下文窗口"移到了"transcript 文件"。

#### 2.4 实现为独立类

建议实现为 `MicrocompactProcessor` 类：

```java
public class MicrocompactProcessor {
    private int ageThreshold = 3;  // 可配置
    
    /**
     * 扫描 messages 列表，将超龄的 tool_result 替换为摘要
     * @param messages 当前对话的 messages 列表（会被原地修改）
     * @param currentTurn 当前轮次
     * @return 本次 compact 了多少条 tool_result
     */
    public int compact(List<Message> messages, int currentTurn) { ... }
}
```

这个类不依赖 AgentLoop 的其他组件，可以独立测试。

---

### 第三步：构造长对话场景并运行

#### 3.1 设计一个 15-20 轮的对话脚本

我们需要一个**多轮追问场景**来触发 Microcompact。建议设计一个"研究助理"场景，用户围绕一个主题连续深入追问：

```
Turn 1:  "Ragent 系统的整体架构是什么？"                    → ragentdocs
Turn 2:  "其中的意图树是怎么工作的？"                        → ragentdocs
Turn 3:  "SSD 模型的基本原理是什么？"                        → ssddocs (话题切换)
Turn 4:  "DualSSD 在 SSD 基础上做了什么改进？"              → dualssddocs + ssddocs
Turn 5:  "块级别状态管理具体是怎么实现的？"                  → dualssddocs
Turn 6:  "回到 Ragent，它的记忆管理是怎么做的？"            → ragentdocs (话题切回)
Turn 7:  "对话历史是怎么存储的？"                            → ragentdocs
Turn 8:  "SSD 的状态压缩思想能不能用于 Ragent 的记忆管理？"  → 跨库综合
Turn 9:  "具体怎么把块级状态应用到对话记忆中？"              → 跨库深化
Turn 10: "Ragent 的检索用的什么模型？"                       → ragentdocs
Turn 11: "Rerank 的具体流程是什么？"                         → ragentdocs
Turn 12: "SSD 和 Transformer 的注意力机制有什么区别？"       → ssddocs
Turn 13: "DualSSD 用了什么替代注意力的方案？"               → dualssddocs
Turn 14: "总结一下 Ragent、SSD、DualSSD 三个项目的关系"     → 跨库综合
Turn 15: "基于以上讨论，你觉得 Ragent 最值得改进的是什么？"  → 综合推理
```

**注意：这只是参考脚本。** 实际执行时，每轮的提问应该基于模型上一轮的回答自然展开——你可以根据模型的实际回答调整后续问题，保持对话的自然性。如果模型在某一轮给出了特别有意思的观点，可以追问深入。

#### 3.2 运行方式

由于是多轮对话，`AgentLoopExperiment4.java` 需要支持**交互式输入**或**脚本式输入**：

**方案A（推荐 — 脚本式）：** 把问题列表写在代码里或配置文件中，自动按顺序提问。每轮模型回答完后自动发下一个问题。好处是可完全复现。

**方案B（交互式）：** 从 stdin 读取用户输入，手动逐轮提问。好处是可以根据模型回答调整问题。

建议先用方案A跑一遍脚本式的，拿到完整数据。如果发现有趣的行为再用方案B做交互式追问。

#### 3.3 对比实验设计

跑两次：

**Run 1 — 无 Microcompact（baseline）：** 关闭 Microcompact，15 轮对话全程保留所有 tool_result。记录每轮的 messages 统计和模型行为。**观察：到第几轮上下文开始膨胀到什么程度？模型回答质量有没有因为噪音变多而下降？**

**Run 2 — 开启 Microcompact：** 开启 Microcompact（ageThreshold=3），同样的问题跑一遍。记录每轮的 messages 统计、compact 了多少条、模型行为。**观察：compact 后模型行为有没有变化？回答质量是提升了还是下降了？模型会不会在回答中引用已经被 compact 掉的旧搜索结果？**

---

### 第四步：记录结果

#### 4.1 Transcript 数据分析

对 Run 1（baseline）的 transcript 做分析：

```markdown
## 上下文膨胀曲线

| Turn | messages 总字符 | tool_result 字符 | tool_result 占比 | 本轮新增字符 |
|------|---------------|-----------------|-----------------|------------|
| 1    | ?             | ?               | ?%              | ?          |
| 2    | ?             | ?               | ?%              | ?          |
| ...  |               |                 |                 |            |
| 15   | ?             | ?               | ?%              | ?          |

关键发现：
- 上下文膨胀主要来自什么？（预期：tool_result 占比 > 70%）
- 第几轮开始接近模型的上下文限制？
- 模型在后期轮次的回答质量是否有下降趋势？
```

#### 4.2 Microcompact 效果对比

```markdown
## Microcompact 效果

| 指标 | Run 1 (无 compact) | Run 2 (有 compact) |
|------|-------------------|-------------------|
| Turn 15 时 messages 总字符 | ? | ? |
| Turn 15 时 tool_result 占比 | ? | ? |
| 被 compact 的 tool_result 数 | 0 | ? |
| 节省的字符数 | 0 | ? |
| 回答质量有无明显变化 | baseline | ? |
| 模型是否引用了被 compact 的旧结果 | N/A | 是/否 |
```

#### 4.3 意外观察

```markdown
## 意外发现

（列出任何超预期或反预期的观察，比如：
- 模型在后期是否开始"忘记"早期讨论的内容？
- 模型在 Turn 14（总结题）时是否引用了早期的搜索结果？
- compact 后模型有没有表现出困惑？
- 模型有没有主动提到之前搜索过某个话题？
）
```

---

## 关键约束

1. **所有改动在 experiment/ 目录内** — 不碰 ragent 主管道代码（bootstrap/mcp-server）
2. **Transcript 完整不截断** — 这是冷存储，永远保留原始信息
3. **Microcompact 只改发送给模型的 messages，不改 transcript** — "信息不丢弃，只换存储位置"
4. **先跑 baseline（无 compact）再跑 compact 版** — 没有 baseline 就没法评估效果
5. **不做 Autocompact（LLM 摘要）** — 那是任务 B 的事。本次只做纯代码逻辑的 Microcompact
6. **存储选择不受限** — 不是非得用 MySQL。Transcript 用 JSONL 文件完全合适。根据实际需要选择最简单合适的方式

## 预期产出

1. `TranscriptWriter.java` — Transcript 持久化实现
2. `MicrocompactProcessor.java` — Microcompact 实现
3. `AgentLoopExperiment4.java` — 支持多轮对话的实验入口
4. **Run 1 的 transcript 文件 + 统计分析**（最重要 — 这是我们第一次看到长对话的真实数据）
5. **Run 2 的 transcript 文件 + 与 Run 1 的对比分析**
6. 结果报告：`experiment/results/experiment4/README.md`

## 我们最想从这次实验中学到什么

1. **ragent Agent Loop 场景下，上下文膨胀的实际速度和形态是什么？** 这个数据我们从来没有过。它直接决定 Autocompact 的触发阈值应该设在哪里。

2. **清理旧检索结果对模型行为的影响是什么？** 可能有三种结果：完全没影响（模型本来就不看旧结果）、质量提升（噪音减少注意力更集中）、质量下降（模型需要回顾旧结果但看不到了）。每种结果对任务 B 的设计都有不同含义。

3. **compact 摘要中应该保留什么信息？** 当前设计保留了"搜了什么库、查询是什么、最高分多少"。实验中如果发现模型需要更多信息（比如 chunk 的来源文档名），我们可以调整摘要格式。这是一个通过观察逐步调优的过程。

这次实验的定位不是"做一个完美的记忆系统"，而是**获取数据和建立直觉**——长对话是什么样的？膨胀有多快？compact 有没有用？有了这些数据，任务 B（Autocompact + MEMORY.md + 实验4完整版）的设计就有了扎实的基础。
