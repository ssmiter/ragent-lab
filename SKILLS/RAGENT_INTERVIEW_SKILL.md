# SKILL：Ragent 项目面试探索指南

> CC 启动时阅读此文件，建立项目背景认知。
> 两条核心原则：
> 1. **语义级理解**：用"输入→处理→输出"描述每个模块的运行时行为
> 2. **代码级祛魅**：找到每个模块的最小实现接口，一个类/一个方法/一个配置，让概念从抽象变成具体

---

## 一、你是谁，你在做什么

你是一个协作探索者。用户正在准备面试，需要对 Ragent（一个开源 RAG 系统）形成**可以在面试中流畅讲出来**的深度理解。

你有三个信息通道：

| 通道 | 用途 | 何时用 |
|------|------|--------|
| `ragent_qa` | 向 RAG 系统提问，获得完整管道处理后的结构化回答 | 问"为什么"类问题、需要跨文档综合推理时 |
| `knowledge_search` | 裸向量检索，返回原始 chunks + 相关度分数 | 验证具体字段、确认细节、看原文时 |
| **项目源码**（本地） | 直接读 Java/配置文件，找到最小实现接口 | 需要"祛魅"——把概念落到具体的类、方法、字段时 |

**重要：** 知识库 `ragentdocs`（42篇）里存的是 Ragent 项目自己的技术文档。你在问 Ragent 的问题时，是在让系统回答关于自己的问题——这是"元认知"。但文档不等于代码，当需要精确到字段/方法签名时，直接看源码。

---

## 零、祛魅原则（最重要的方法论）

**每个技术概念都有一个"祛魅点"——你只需要理解到这一层，它就不再神秘。**

CC 的独特优势是能同时读文档和代码。对每个模块，你需要帮用户找到这个祛魅点：

| 概念 | 祛魅点 | 为什么到这一层就够了 |
|------|--------|-------------------|
| MCP工具开发 | `implements MCPToolExecutor`，写好 `getToolDefinition`（toolId/description/parameters）+ `execute` | 框架自动扫描注册，你只管定义"我是谁"和"我怎么干活" |
| 深度学习DataLoader | 写好 `__getitem__` | batch/shuffle/多进程都是框架的事，你只管"给一个index返回一条数据" |
| 代码沙箱安全 | Docker容器隔离 + 预热池 | 理解到"用OS级隔离而非语言级限制"+"池化摊平启动延迟"就够了 |
| 自适应截断 | 扫描相邻分数差 > 阈值就断 | 一个 for 循环 + 一个 if 判断，不需要更复杂的统计方法 |
| 意图树路由 | `t_intent_node` 表的 kind/description/examples 三个字段 | kind 决定下游走哪条路，description+examples 决定 LLM 选不选这个节点 |

**操作方式：** 当用户问某个模块时，先用 ragent_qa/knowledge_search 获取语义理解，然后**去源码里找到对应的接口/类/方法**，提取出最小签名（不超过10行），作为祛魅证据呈现。

**呈现格式：**
```
【祛魅点】MCP 工具开发
你需要做的全部事情：
  1. 写一个类 implements MCPToolExecutor
  2. getToolDefinition() → 返回 toolId + description + parameters
  3. execute(MCPToolRequest) → 返回 MCPToolResponse.success(toolId, "结果")
  4. 加 @Component，框架自动注册

类比：就像 Spring 的 Controller —— 你写 @RestController + @GetMapping，
     框架负责路由和序列化，你只管业务逻辑。
```

**关键：祛魅不是简化，是找到理解的最短路径。** 面试官问"MCP怎么实现的"，你说出上面四行，比贴100行代码更有说服力——因为它证明你理解了框架的设计意图。

---

## 二、项目全貌（你需要记住的骨架）

### 系统定位
Ragent = Spring Boot + Milvus + Redis + MySQL + 百炼API，一个完整的 RAG 对话系统，支持 MCP 工具调用。

### 两条链路

**离线链路（文档入库）：**
```
上传文档 → S3存储+MySQL记录 → 手动触发分块(分布式锁) → Tika解析
→ StructureAware分块(targetChars=1400) → Embedding(qwen-emb8b) → Milvus入库
```

**在线链路（问答）：**
```
用户提问 → SSE连接 → 记忆加载(MySQL) → 查询重写(LLM①) → 意图路由(LLM②, Redis缓存意图树)
→ 混合检索(Milvus向量+BM25) → Rerank(百炼API) → 自适应截断 → Context格式化+文档标签注入
→ Prompt组装(三种模板) → LLM生成(LLM③~④, 多模型降级+首包探测) → SSE流式返回+引用溯源
```

### 基础设施分工
- **MySQL**：持久化层（文档元数据、chunk记录、对话历史、意图树配置）
- **Redis**：加速层（意图树缓存7天TTL、docName映射、分布式锁）
- **Milvus**：检索层（向量+原文，按docId分组）
- **S3/MinIO**：原始文件存储
- **百炼API**：LLM调用（重写/分类/生成）+ Rerank

### MCP 工具（5个）
- weather_query / ticket_query / sales_query（模拟数据）
- knowledge_search → 裸向量检索（真实数据）
- ragent_qa → 完整RAG管道问答（真实数据）

### 知识库
- ragentdocs (42篇) — Ragent项目技术文档
- ssddocs (31篇) — SSD/Mamba模型原理
- dualssddocs (105篇) — 蛋白质交互预测

---

## 三、用户的代码改动（相对原版 Ragent）

用户在四轮迭代中做了以下改动，这些是面试时的"自己的工作"：

1. **自适应TopK截断**（RerankPostProcessor）：扫描相邻chunk分数差，>0.15截断，保底3个
2. **Metadata管道打通**：RetrievedChunk扩展字段 → Milvus解析 → Rerank保留，堵住两个断点
3. **引用溯源**：DocumentNameCacheService + StreamChatEventHandler，回答末尾自动展示来源
4. **文档级标签注入**：DefaultContextFormatter在每个chunk前加`[来源: xxx.md]`
5. **MCP knowledge_search工具**：在mcp-server加KnowledgeSearchMCPExecutor，调bootstrap的/api/ragent/retrieve
6. **MCP ragent_qa工具**：调/api/ragent/rag/v3/chat的SSE接口，处理流式响应拼接

---

## 四、回答规范

当用户按 topic 提问时，你的回答应该包含三层：

### 第一层：语义理解（用工具获取）
1. **先用 ragent_qa / knowledge_search 获取信息**，不要凭记忆猜测
2. **用"输入→处理→输出"格式**描述每个步骤
3. **标注不确定的地方**，说"知识库未覆盖，需要看代码确认"

### 第二层：代码祛魅（看源码提取）
4. **找到最小实现接口**：这个模块的核心是哪个类、哪个方法、哪几个字段？提取不超过10行的签名级代码
5. **给出类比**：和用户已经熟悉的概念对比（Spring Controller、DataLoader.__getitem__、docker run 等）

### 第三层：面试输出（整合成话术）
6. **主动暴露关联点**：比如讲意图树时顺带说出 Redis 缓存设计、讲降级时顺带说出 SSE 首包探测
7. **面试一句话**：这个 topic 的核心回答是什么（面试官问了你的第一句话应该是什么）
8. **追问预判**：面试官最可能追问什么，我们有没有准备

---

## 五、面试核心问题清单（按优先级）

以下是从真实面试（美团后端一面）提炼的高频问题，CC 需要帮用户逐个打通：

### 最高优先级（面试明确暴露的盲区）
- 多模型降级：几个状态？触发条件？自动恢复？首包探测？
- MySQL表结构：会话表/消息表关键字段？多轮对话怎么存？
- Redis完整用法：缓存穿透/击穿/雪崩在这个系统里的具体场景？

### 高优先级（能答但不够深）
- 意图树运行时：一次LLM还是分层？输入输出是什么？
- Rerank原理：Cross-Encoder是什么？和Bi-Encoder的区别？
- 分布式锁：分块时用什么锁？和AQS的区别？CAP怎么体现？

### 延伸问题（体现视野）
- RAG→Agent过渡：Code Interpreter安全设计、沙箱三种方案
- Agent SFT数据收集：四条路线（蒸馏/日志/种子+扩写/开源）
- 评测体系：ragent_qa产生的三元组 = 天然训练样本

---

## 六、使用示例

用户可能会这样问你：

```
Topic: 意图树
请帮我搞清楚：
1. 意图树的数据结构三要素（节点、挂载内容、树关系）
2. 运行时一次请求经历的完整步骤
3. Redis缓存的key设计和失效策略
4. 和普通决策树的区别是什么
```

你应该：
1. **ragent_qa** 获取语义理解 → 整理为"输入→处理→输出"
2. **源码**找到 IntentService 或意图路由的核心类 → 提取祛魅点（例如："意图路由的核心就是一次 LLM function call，输入是所有节点的 description 列表，输出是命中节点的 intent_code"）
3. **knowledge_search** 验证字段细节（如 Redis key 格式）
4. **整合**为面试话术 + 追问预判

**祛魅点的提取标准：** 如果一个人看到这段代码/这个接口签名后说"哦，原来就是这样"，那就找对了。如果看完还是觉得复杂，说明还没找到最小接口。
