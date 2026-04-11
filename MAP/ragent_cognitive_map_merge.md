# Ragent 认知地图 v1

> **这份文档是什么：** 不是第五篇 SKILLS，不是面试八股文集，不是源码注释。这是一张认知地图——把四轮迭代的碎片化认知、两轮 CC 代码级探索、一轮分歧交叉验证，压缩成一个可以从任意节点进入、沿链路走通全局的网状结构。每个节点同时呈现三层：语义级（这是什么）、祛魅级（代码里长什么样）、输出级（面试怎么说）。
>
> **谁在用它：** 我自己。每次面试前翻一遍恢复全景记忆；每次迭代后更新对应节点；每次有新想法时，在"未探索的边"里找到它应该挂靠的位置。
>
> **和其他文档的关系：** SKILLS 是单轮迭代的冻结记录，负责凝固认知链条；MCP SKILLS 是工具书，负责给 LLM 装上手；CC 探索记录是一次性的 work memory。这份文档把它们全部消化，提炼成一个可以不断跟进的活图谱。
>
> **版本：** v1 · 2026-04-01 · 基于 RAG SKILLS V1/V2 + MCP SKILLS V1/V2 + 两轮 CC 探索 + 分歧验证

---

## 一、系统全景：两条链路 + 基础设施层

### 1.1 一句话定位

Ragent 是一个 **Spring Boot + Milvus + 百炼 API** 的 RAG 系统，手搓而非框架（不用 LangChain/SpringAI），自由度极高但需要自己搭管道。我在上面做了两件事：**让它从黑盒变成可观测可信赖的知识助手**（RAG 迭代四轮），**让它从被动回答变成可被 Agent 主动调用的工具**（MCP 迭代两轮）。

为什么不用框架？不是排斥框架，而是框架的高抽象度屏蔽了 RAG 的核心环节——LangChain 封装了检索链但你看不到 rerank 的请求体长什么样，SpringAI 的 RAG Prompt 模板包含 "no prior knowledge" 约束会抑制工具调用倾向。手搓的代价是管道要自己搭，收益是每个环节都可以自由定制——自适应截断、metadata 管道修复、文档级标签注入，这些改动在框架里要么做不了要么需要深度 hack。

### 1.2 离线链路（文档入库）

```
上传文档 → S3存储 + MySQL记录
    → 手动触发分块（Redisson分布式锁, tryLock(5,30,SECONDS), 粒度=文档级）
    → Tika解析（PDF/DOCX/MD → 纯文本）
    → StructureAware分块（targetChars=1400, 不切断标题/代码块, 递归回退）
    → Embedding（qwen-emb8b, 4096维）
    → Milvus入库（FloatVector, 按docId分组）+ MySQL记录chunk元数据
```

关键数字：178 篇文档（ragentdocs 42 + ssddocs 31 + dualssddocs 105），三个 Collection。

**StructureAware 分块的设计逻辑：** 不是简单按字符数切，而是先识别文档结构（标题、代码块、段落），在结构边界处切分。targetChars=1400 是目标而非硬限制——如果一个代码块有 2000 字符，宁可整块保留也不切断。当无法在结构边界切分时，递归回退到更细粒度的切分策略。这保证了每个 chunk 在语义上是完整的。

### 1.3 在线链路（问答）

```
用户提问
 → SSE连接建立
 → 记忆加载（MySQL直读, 最近4条消息）
 → 查询重写（LLM①, 开关控制, 指代消解+术语归一化）
 → 意图路由（LLM②, 从Redis加载意图树, 只取叶子节点打分）
 → 【分支】
     ├─ KB意图 → 向量检索（Milvus ANN）→ 去重 → Rerank（百炼API）→ 自适应截断
     ├─ MCP意图 → 参数提取（LLM③）→ MCP工具调用 → 结果注入context
     └─ 兜底 → 全局检索（所有Collection并行, topK×3扩大召回）→ Rerank后筛选
 → Context格式化 + 文档标签注入（[来源: xxx.md]）
 → Prompt组装（三种模板：KB/MCP/兜底）
 → LLM生成（LLM④, 流式SSE, 多模型降级+首包探测）
 → 引用溯源（文档名+分数）
 → SSE流式返回
```

**一个关键认知：重写和意图分类是顺序执行不互斥的。** 改写结果是意图分类的输入——先把"它怎么存的"消解为"Ragent的会话存储结构"，再拿这个清晰的查询去做意图匹配。这两步的顺序关系容易被忽略。

### 1.4 基础设施分工

| 组件     | 角色        | 存什么                                      | 宕机影响                                                     |
| -------- | ----------- | ------------------------------------------- | ------------------------------------------------------------ |
| MySQL    | 持久化层    | 文档元数据、chunk记录、对话历史、意图树配置 | 系统不可用                                                   |
| Redis    | 加速+协调层 | 意图树缓存、分布式锁、SSE消息中转、全局限流 | 致命：应用启动失败(Snowflake ID)；严重：分块/对话不可用；降级：意图识别变慢 |
| Milvus   | 检索层      | 向量(4096维) + 原文，按docId分组            | 检索不可用                                                   |
| S3/MinIO | 存储层      | 原始文件                                    | 新上传失败，已入库不受影响                                   |
| 百炼API  | 智能层      | LLM调用(重写/分类/生成) + Rerank            | 触发多模型降级                                               |

### 1.5 LLM调用精确清单

一次请求最少 2 次 LLM 调用（意图分类 + 最终生成），典型路径 3 次（+查询重写），MCP 路径 4 次（+参数提取），后台异步摘要可能第 5 次。

| 步骤          | 调用方式           | 条件                 | 耗时   |
| ------------- | ------------------ | -------------------- | ------ |
| ① 查询重写    | chat（同步）       | 开关开启时（默认开） | 0.5-1s |
| ② 意图分类    | chat（同步）       | 每次                 | 0.5-1s |
| ③ MCP参数提取 | chat（同步）       | 仅MCP意图            | 0.5-1s |
| ④ 最终生成    | streamChat（流式） | 每次                 | 2-40s  |
| ⑤ 对话摘要    | chat（异步）       | 后台触发             | 1-2s   |

性能瓶颈在第④步（LLM 推理本身慢），流式返回让用户感知延迟降低。离线阶段还有 Embedding 调用，但不计入在线链路。

### 1.6 这个系统的三个"没有"（以及我们怎么手动补的）

```
没有评测机制 ← 因为 → 没有标注数据集 ← 因为 → 没有自动记录用户案例
```

我们手动补了这条链的起点：6个测试问题 = 手动评测集，分数分布基线 = 手动指标，SKILLS文档 = 手动用户案例。ragent_qa 工具是第一步自动化——每次调用自动积累 (question, answer, references) 三元组，这是天然的 SFT 训练数据。

评测集（100-200条）比训练集更重要——我们的 6 个测试问题是起点，日常使用中的 bad case 应该持续积累进来。

---

## 二、六大组件深度拆解

> 每个组件的结构：**祛魅点**（一眼看穿的最小接口）→ **运行时步骤** → **面试话术** → **追问预判**。

### 2.1 意图树

#### 祛魅点

意图"树"在路由时其实是**扁平的**。`classifyTargets` 方法从 Redis 加载意图树后，直接 `flatten + filter(isLeaf)`，只取叶子节点。树的层级唯一的运行时作用是生成 `path` 字段（"业务系统 > OA系统 > 登录指南"），嵌入 prompt 让 LLM 理解节点的上下文语境。本质上是"**带层级标签的意图列表 + LLM 分类器**"。

类比：DispatcherServlet 匹配——URL 对应问题，HandlerMapping 对应意图树，Handler 对应下游（KB检索 / MCP工具）。

#### 核心代码签名

```java
// 输入：一个问题字符串；输出：(节点, 分数) 的有序列表
List<ClassifyTarget> classifyTargets(String question)
// 内部四步：
IntentTreeData data = loadIntentTreeData();        // Redis读（miss则DB→Redis）
String systemPrompt = buildPrompt(data.leafNodes); // 叶子节点拼进 intent-classifier.st 模板
String raw = llmService.chat(request);             // 一次LLM调用，temperature=0.1
return parseAndSort(raw);                          // JSON解析 + score降序
```

#### 数据结构（t_intent_node 关键字段）

| 字段                   | 用途                                          |
| ---------------------- | --------------------------------------------- |
| intent_code            | 唯一标识                                      |
| kind                   | 0=知识库检索, 1=系统(闲聊), 2=MCP工具         |
| level / parentId       | 层级关系（仅展示和path字段用）                |
| description + examples | **决定路由成败的关键**——给LLM做分类的文本输入 |
| param_prompt_template  | MCP专属，提取工具参数的自定义prompt           |
| collection_name        | KB专属，关联Milvus的collection                |
| mcp_tool_id            | MCP专属，关联mcp-server的toolId               |

#### 缓存设计

Redis key = `ragent:intent:tree`，TTL = 7天。一致性模型：**主动失效**（控制台改意图树时 `clearIntentTreeCache()`）+ **TTL兜底**。属于最终一致性。

#### 兜底策略

所有叶子节点 score < 0.6 时，`VectorGlobalSearchChannel.isEnabled()` 返回 true → 在所有 Collection 中并行向量检索，topK × 3 扩大召回，Rerank 后筛选。不会返回"无法理解"——**宁可给不够精准的答案也不拒绝用户**。

#### 我做的优化

description 里加了详细技术关键词 + 10个示例问题 + **排他性指引**（"什么不在这个库里"）。零代码改动，意图路由命中率从 83% 提升到 100%。这体现了"理解模块的输入是什么，就知道怎么优化它"——意图树的 description 本质上是在教意图识别模型"这个知识库里有什么"。

#### 面试话术

**Q8 意图识别怎么实现的？**

> 意图识别的核心是一次 LLM 路由匹配。系统从 Redis 加载意图树，取出所有叶子节点的 id、path、description、examples，拼成一个 prompt 发给 LLM，LLM 返回 JSON 数组，每个元素是节点 id 和置信度 score，系统按 score 降序取 topK，阈值 0.6。命中节点的 kind 字段区分类型：KB 类型走向量检索，MCP 类型走工具调用。MCP 节点还有 param_prompt_template 字段，额外做一次 LLM 调用从问题中提取工具参数。意图树缓存在 Redis，key 是 `ragent:intent:tree`，TTL 7天，控制台修改时主动失效。

**追问：没有命中怎么办？** → 不会返回"无法理解"。系统有全局检索兜底通道，当所有节点 score 低于阈值时自动启用——在所有 Collection 中并行向量检索，召回量扩大 3 倍，再经过 Rerank 筛选。

**追问：节点多了性能怎么办？** → 当前一次 LLM 调用传所有叶子节点，适合 50 个以内。节点多了可以按 Domain 分组并行，接口已预留扩展点。

**追问：和普通决策树有什么不同？** → 传统决策树是规则匹配逐层判断。意图树的匹配器是 LLM，一次调用对所有叶子节点打分。树结构只用来生成 path 字段帮助 LLM 理解语境，本质是"带层级标签的扁平列表 + LLM 分类器"。

---

### 2.2 多模型降级与熔断

#### 祛魅点

降级就是一个 **for 循环 + 三态状态机**。两个核心类各管一件事：`ModelHealthStore` 管状态（纯内存 Map，不依赖外部组件），`ModelRoutingExecutor` 管执行（遍历候选列表，跳过熔断的，try-catch 切换下一个）。

```java
// 降级执行的全部逻辑：
for (ModelTarget target : candidates) {
    if (!healthStore.allowCall(target.id())) continue;  // 熔断的跳过
    try { return caller.call(client, target); }          // 尝试调用
    catch { healthStore.markFailure(target.id()); }      // 失败标记，继续循环
}
throw new RemoteException("No model available");         // 全挂了
```

自己实现而非 Hystrix/Sentinel，因为是单体应用 + 2-3 个模型，不需要分布式熔断框架。

#### 状态机

```
[CLOSED 正常放行] --连续失败2次--> [OPEN 拒绝所有调用]
[OPEN] --30秒后自动--> [HALF_OPEN 允许一次探测]
[HALF_OPEN] --探测成功--> [CLOSED]
[HALF_OPEN] --探测失败--> [OPEN 再等30秒]
```

参数：failureThreshold=2, openDuration=30s, 首包超时=60s（硬编码）。

#### 流式场景的精巧设计：ProbeBufferingCallback

流式调用不能简单 try-catch（SSE 是持续的）。设计了一个"缓冲代理"：先把早期 SSE 事件存起来，确认首包成功后才 commit 回放给用户。失败了就丢弃缓冲、cancel 连接、切下一个模型。用户感知不到中间的探测过程。

#### 和 Hystrix 的关键区别

连续失败计数 vs 滑动窗口百分比。LLM API 故障是突发性的（过载/限流），不像微服务那样有"慢慢变差"的渐变过程，连续 2 次失败就足以判断。单机内存 Map 足够，不需要分布式统计。

#### Rerank 也有降级

Rerank 配置了两个候选：`qwen3-rerank`（主力，百炼API）→ `rerank-noop`（兜底，直接返回原始结果不做重排序）。NoopRerankClient 不调外部 API，几乎不可能失败。所以 **Rerank 失败不会让整个请求报错**——降级为"不做精排"，用户可能感知到回答质量下降，但不会看到错误页面。

#### 面试话术

**Q12 多模型降级策略怎么实现的？**

> 自己实现的轻量熔断器，核心是 ModelHealthStore 管理健康状态，ModelRoutingExecutor 执行降级遍历。调用时遍历按优先级排序的候选模型列表，跳过处于熔断状态的模型，try-catch 失败后自动切换下一个。

**Q13 手动还是自动？** → 完全自动。每次调用失败后自动 markFailure，连续失败达到阈值触发熔断，30秒后自动进入半开状态探测恢复。

**Q14 三个状态怎么流转？**

> 经典三态：CLOSED 正常放行，连续失败 2 次进入 OPEN 拒绝所有调用，30 秒后自动转 HALF_OPEN 允许一次探测——探测成功回 CLOSED，失败回 OPEN 再等 30 秒。感知方式分两种：同步调用捕获异常即标记失败；流式调用用首包探测，60 秒内没收到第一个 SSE 事件就判定失败。流式场景还有 ProbeBufferingCallback 设计，探测期间先缓存所有输出，确认成功才回放给用户。

**追问：和 Hystrix 区别？** → 统计方式不同。我们用连续失败计数，Hystrix 用滑动窗口百分比。选连续计数是因为 LLM API 故障是突发性的。另外单机内存 Map，不需要 Hystrix 的分布式复杂度。

**追问：所有模型都挂了？** → 返回"大模型调用失败，请稍后再试"。不会永远挂——30 秒后第一个模型自动进入半开探测，系统自愈。

---

### 2.3 检索排序链

#### 祛魅点

检索就是"**广撒网 → 精挑选 → 去噪音**"三步流水线。当前是**纯向量检索 + Cross-Encoder Rerank + 自适应截断**，没有 BM25（只有枚举预留，没有实现类）。

#### ⚠️ 重要分歧（文档 vs 源码）

知识库文档（教程）说"已实现 BM25 混合检索 + RRF 融合"。**源码实际：没有。** BM25 只是 `KEYWORD_ES` 枚举预留；RRF 不存在，连注释掉的代码都没有。这是"框架支持的能力全集" vs "实际部署的实例"的差异。面试时如实说，反而体现你区分了"框架能力"和"实际实现"。

这个分歧本身就是 RAG 系统的一个经典 bad case——知识库无法区分"文档在讲框架能力"和"项目实际用了什么"。解决思路是文档入库时加身份标签（教程标记为"参考设计"，部署配置标记为"当前实现"）。

#### 检索通道

```java
// 两个 SearchChannel 实现：
IntentDirectedSearchChannel  // 优先级1，意图命中时走
VectorGlobalSearchChannel    // 优先级3，兜底全局检索
// 预留未实现：
KEYWORD_ES                   // 优先级2，BM25 扩展点
```

#### PostProcessor 链

```
多通道检索结果
 → DeduplicationPostProcessor(order=1)：按chunkId去重，同chunk多通道出现取最高分
 → RerankPostProcessor(order=10)：调百炼 qwen3-rerank 重打分 + 自适应截断
 → 最终chunks
```

#### Rerank 请求体（面试能画出来的程度）

```json
{
  "model": "qwen3-rerank",
  "input": { "query": "用户问题", "documents": ["chunk1文本", "chunk2文本"] },
  "parameters": {"top_n": 10, "return_documents": true}
}
```

这就是 Cross-Encoder 的本质——query 和每个 document 拼在一起让模型打分。和 Bi-Encoder（向量检索）的区别：Bi-Encoder 分别编码再算余弦（可预计算，快），Cross-Encoder 拼一起过完整 Transformer（不可预计算，慢但精）。标准做法是 Bi-Encoder 召回 + Cross-Encoder 精排。

#### 自适应截断（我做的）

扫描相邻 chunk 的 rerank 分数差，遇到超过 0.15 的陡降就截断，保底 3 个。三种分数分布形态：断崖型（答案集中，截断生效）、中等断崖型、平缓衰减型（知识分散，不截断）。Q4 截断后回答质量反而提升——更少噪音让 LLM 注意力更集中。体现了"**上下文质量 > 上下文数量**"。

#### 面试话术

**Q6 关键字检索怎么做的？**

> 当前系统是纯向量检索，没有实现 BM25。架构预留了 KEYWORD_ES 扩展点，但评估后当前场景——内部技术文档——语义匹配比精确关键词更重要，纯向量检索加 Rerank 已满足需求。如果后续遇到订单号、错误码这类精确查询场景，可以接入 ES 实现 BM25。

**Q9 粗排和细排？**

> 粗排是 Milvus 向量近似检索，Bi-Encoder 把 query 编码成向量做 ANN，召回 TopK 乘倍率的候选 chunk。细排调百炼 Rerank API，qwen3-rerank 模型，Cross-Encoder 架构——把 query 和每个 chunk 拼一起输入模型，输出相关性分数。Cross-Encoder 精度高因为能捕捉 query-document 间的细粒度交互，但不能预计算所以只能精排。

**Q5 embedding 质量不好怎么办？**

> 如果 embedding 质量不好，召回阶段就会漏掉相关 chunk，后面 Rerank 再精准也救不回来——GIGO 问题。三条路：换更强的 embedding 模型（当前 qwen-emb8b），加 BM25 补充语义盲区（已预留扩展点），对 embedding 做独立评估（用标注数据算 Recall@K）。

---

### 2.4 MySQL 核心表设计

#### 祛魅点

五张表，两条链，两种锁。`t_knowledge_base → t_knowledge_document → t_knowledge_chunk` 是离线链路的三级结构（知识库→文档→分块），`t_conversation → t_message` 是在线链路的两级结构（会话→消息）。类比电商：知识库=店铺，文档=商品，chunk=SKU；会话=订单，消息=订单项。

#### 离线链路三张表

**t_knowledge_base：** knowledgeBaseId（业务主键）、name、collectionName（Milvus Collection 名）、embeddingModel（不同知识库可用不同 embedding）、status。

**t_knowledge_document：** documentId、knowledgeBaseId（外键）、title、fileSize、chunkCount、**status**（`pending → running → success / failed`，四态状态机）、s3Key。

**t_knowledge_chunk：** chunkId、documentId（外键）、chunkIndex（序号）、content、charCount、**contentHash**（幂等去重）。chunk 在 MySQL 和 Milvus **双写**——MySQL 存管理维度（谁的第几块），Milvus 存检索维度（向量+原文）。删除文档时先删 MySQL 再清 Milvus，否则出现"幽灵 chunk"（MySQL 没记录但 Milvus 还能检索到）。

#### 在线链路两张表

**t_conversation：** conversationId（业务主键）、userId（多用户隔离）、title、lastTime。

**t_message：** messageId、conversationId（外键）、**role**（只有 `user` / `assistant`，没有 system/tool）、content（TEXT）、createTime、deleted。多轮对话 = 多条 message，一轮 = 一条 user + 一条 assistant。System prompt 是运行时根据意图节点动态拼装的，不持久化——这是一个"只存对用户可见的内容"的设计哲学。

#### 两种分布式锁

| 场景     | 方案                       | 锁key                          | 参数            | 选型原因                       |
| -------- | -------------------------- | ------------------------------ | --------------- | ------------------------------ |
| 分块操作 | Redisson tryLock           | `knowledge:chunk:lock:{docId}` | 等待5s, 过期30s | 用户触发，高频，需要高性能互斥 |
| 定时任务 | 数据库字段锁（CAS UPDATE） | lockOwner + lockUntil 字段     | 15分钟过期+续期 | 低频，不想额外依赖Redis        |

**CAS UPDATE 的本质：** `UPDATE t_schedule SET lock_owner='我' WHERE lock_until < NOW()` 和 Java `AtomicInteger.compareAndSet(expect, update)` 是同一个模式——先比较再修改，原子执行，返回成功/失败。Java CAS 靠 CPU cmpxchg 保证原子性，数据库 CAS 靠行级锁保证原子性。代码里看不到显式的 "CAS" 关键字，是因为 `UPDATE ... WHERE 条件` 天然就是 CAS 语义——WHERE 是 Compare，SET 是 Swap。`return scheduleMapper.update(...) > 0` 直接判断返回值——affected rows > 0 就是 CAS 成功。

**Redisson vs 手写 SETNX：** Redisson 封装了看门狗（不传 leaseTime 时后台线程每 10 秒续期）、可重入、公平锁等。Ragent 显式设了 30 秒过期，看门狗不会启动。30 秒只保护"检查状态+删旧块+提交异步任务"这几步同步操作，真正分块在异步线程跑。

**和 CAP 的联系：** Redisson 锁依赖 Redis 单点（非 RedLock），Redis 宕机则锁失效——选 AP（可用性优先，极端情况重复分块，contentHash 做兜底去重）。数据库锁选 CP（MySQL 保证一致性，但性能低）。

#### 面试话术

**Q17 会话怎么存储的？**

> 两张表，经典一对多。t_conversation 存会话元信息——conversationId 业务主键、userId、title、lastTime。每轮对话产生两条记录存在 t_message 表，通过 conversationId 关联。查询时按 createTime 升序取最近 N 轮拼成 messages 给 LLM。

**Q19 多轮对话一条还是多条？**

> 多条。一轮 = 一条 user + 一条 assistant，十轮就是二十条 message。

**Q20 message 表什么结构？**

> 五个核心字段：conversationId 关联会话，role 区分角色只有 user 和 assistant，content 用 TEXT 存完整内容，createTime 排序，deleted 逻辑删除。没有存 system prompt——运行时动态拼装的。

**追问：分布式锁怎么实现的？**

> 系统里有两种锁。分块操作用 Redisson 的 tryLock，等待 5 秒、30 秒自动过期，锁粒度是文档级别。定时任务用数据库字段锁，CAS UPDATE lockOwner 和 lockUntil 实现乐观锁。选两种方案因为场景不同——分块是高频操作需要高性能，定时任务低频不想额外依赖 Redis。

**追问：锁和事务什么关系？**

> 先拿锁再开事务，事务在锁的保护范围内。顺序：tryLock → 开事务 → 检查状态 + 删旧块 + 提交异步任务 → 提交事务 → unlock。如果反过来，事务提交了但锁已过期，其他实例可能读到中间状态。

---

### 2.5 Redis 全用法

#### 祛魅点

系统里 Redis 有**五种角色**，不是"缓存万金油"，而是针对具体场景选择合适的数据结构。CC 侦察发现共 13 种用法，远超直觉预期的 4-5 种。

#### 五种角色全览

**角色一：加速层（可降级）**——意图树缓存。String 类型存 JSON，key = `ragent:intent:tree`，7 天 TTL，miss 回源 MySQL。最基本的缓存用法。一致性模型：主动失效（控制台改意图树时清缓存）+ TTL 兜底。

**角色二：协调层（互斥）**——三把 Redisson 锁。分块锁（30秒，防并发分块）、摘要锁（5分钟，防并发摘要生成）、防重提交锁（手动释放，防用户双击）。核心都是 `redissonClient.getLock(key).tryLock(wait, lease, unit)`。三把锁都显式设了 leaseTime，所以**看门狗都不会启动**——只有不传 leaseTime 时看门狗才会每 10 秒自动续期。

**角色三：通信层（跨实例）**——两个 Pub/Sub Topic。SSE 取消通知（用户点"停止生成"，需要通知到处理请求的那个实例）和排队释放通知（一个请求完成，通知排队中的请求）。多实例部署时，用户 HTTP 请求可能落在实例 A，但 SSE 连接在实例 B——需要跨实例广播。Pub/Sub 是最轻量的选择（相比 MQ）。

**角色四：限流层（流量控制）**——`ChatQueueLimiter` 一个类用了四种 Redis 数据结构组合：`RPermitExpirableSemaphore`（信号量，控制最大并发数50）+ `ZSET`（排队队列，score是序号保证FIFO）+ `AtomicLong`（序列号生成器）+ `Pub/Sub`（permit释放时通知等待者，避免纯轮询）。这套设计和库存预扣减一脉相承——信号量的 acquire 就是 decrement，release 就是 increment，加了排队和通知。

**角色五：基础设施层（启动依赖）**——Snowflake ID 初始化用 Redis Hash 存 workerId 和 datacenterId，保证多实例不冲突。这是唯一一个"Redis 宕机就启动不了"的**硬依赖**。

**补充：docName 映射不在 Redis 里。** 用的是本地 ConcurrentHashMap，启动时从 MySQL 全量加载。docName 几乎不变，本地缓存零网络开销——这是一个值得记住的设计决策。

#### 缓存穿透 / 击穿 / 雪崩在这个系统里的具体表现

**穿透**（查一个不存在的key，每次都穿透到DB）：意图树只有一个固定 key，不存在穿透。docName 映射用本地内存启动时全量加载，查不到说明文档不存在——**通过启动预热彻底避免穿透**。如果扩展到用户级缓存，可以用布隆过滤器拦截不存在的 key。

**击穿**（热key过期瞬间大量请求打到DB）：意图树 key 有 7 天 TTL，是典型热点 key——每次用户提问都要读它。方案是双重保护：控制台修改时主动失效 + TTL 兜底。如果担心失效瞬间并发加载，可以加互斥锁（只让一个线程回源，其他等待）。

**雪崩**（大量key同时过期）：系统里 Redis key 的 TTL 差异很大（意图树 7 天、取消标记 30 分钟、信号量 600 秒），天然分散了过期时间，不容易雪崩。如果真要防，可以给 TTL 加随机偏移。

#### Redis 宕机影响分级

- 🔴 致命：应用无法启动（Snowflake ID 依赖 Redis Hash）
- 🔴 不可用：分块操作、SSE 对话（限流失败）、防重提交
- 🟡 降级：意图识别变慢（回源 MySQL）、跨实例取消失效、摘要跳过
- 🟢 无影响：定时任务（DB 锁）、docName 缓存（本地内存）

#### 面试话术

**Q21 缓存穿透/击穿/雪崩？结合项目说。**

> 穿透：我们意图树缓存只有一个固定 key，不存在穿透。如果扩展到用户级缓存，可以用布隆过滤器。击穿：意图树是典型热点 key，用主动失效加 TTL 兜底双重保护。雪崩：系统 Redis key 的 TTL 差异大（7天/30分钟/600秒），天然分散了过期时间。

**追问：布隆过滤器原理？**

> 本质是 bit 数组加 K 个哈希函数。写入时把元素经过 K 次哈希映射到 K 个位置置 1。查询时检查 K 个位置是否全为 1——全 1 说明可能存在（有误判），有 0 说明一定不存在（无漏判）。百万级数据只需几 MB。缺点是不支持删除（置 0 会影响其他元素）。

**追问：Redis 挂了会怎样？**

> 最严重的是启动失败——Snowflake ID 初始化依赖 Redis。运行时分三级：SSE 对话和分块完全不可用（限流和锁都依赖 Redis），意图识别降级到直查 MySQL（变慢但可用），定时任务和文档名缓存不受影响（分别用数据库锁和本地内存）。

**追问：限流怎么做的？**

> Redisson 的 RPermitExpirableSemaphore 控制全局最大并发 50。超出的进 ZSET 排队，按序号 FIFO。permit 释放时 Pub/Sub 通知等待者，避免轮询。每个 permit 有 600 秒过期兜底，防泄漏。和库存预扣减同一个思路——acquire 就是 decrement，release 就是 increment，加了排队和通知。

---

### 2.6 MCP 工具体系

#### 祛魅点

MCP 之于 Agent，就像 USB 之于外设——标准化工具的发现和调用协议。实现一个新工具只需要 `implements MCPToolExecutor`，写好 `getToolDefinition`（toolId、description、parameters）和 `execute` 方法，加 `@Component` 就完事。`DefaultMCPToolRegistry` 启动时自动扫描注册，无需改框架代码。

这和深度学习的 DataLoader 是同一个抽象层次——你只需要理解 `__getitem__` 的核心逻辑，框架管剩下的。

#### 架构：两个进程，五个工具

```
bootstrap (9090)                    mcp-server (9099)
  ├─ RAG全链路                        ├─ MCP协议层（SSE + JSON-RPC 2.0）
  ├─ RetrieveController（轻量检索）    ├─ weather_query  (模拟)
  └─ /rag/v3/chat（完整管道SSE）       ├─ ticket_query   (模拟)
                                      ├─ sales_query    (模拟)
      ↑ HTTP调用                       ├─ knowledge_search → POST /api/ragent/retrieve
                                      └─ ragent_qa        → GET /api/ragent/rag/v3/chat (SSE)
```

bootstrap 是 RAG 引擎本体，mcp-server 是协议适配层。两个真实工具（knowledge_search 和 ragent_qa）本质上是在 mcp-server 里加了 Executor，通过 HTTP 调 bootstrap 的接口。**不改 Ragent 任何代码**——Canal 式的旁路接入。

#### 两个真实工具的定位差异

| 工具             | 走的路径                                       | 返回内容              | 适用场景                             |
| ---------------- | ---------------------------------------------- | --------------------- | ------------------------------------ |
| knowledge_search | 裸向量检索（无rerank、无截断）                 | 原始chunks + scores   | 需要原文片段、验证引用、CC自己做推理 |
| ragent_qa        | 完整RAG管道（重写→意图→检索→rerank→截断→生成） | 结构化回答 + 引用来源 | 需要完整答案、评测对比               |

ragent_qa 的技术点是处理 SSE 流式响应——读取所有 `data:` 行拼接成完整文本。

#### 首次定量评测

用同一组 6 个测试问题，分别通过两个工具获取结果，CC 同时充当执行者和评判者。

**量化汇总：** ragent_qa 正确回答率 6/6（100%）；knowledge_search 平均 top1 score 0.71（最高 0.81 Q4 MCP专题，最低 0.65 Q6 前端）。

**关键发现：** 完整管道在"为什么"类问题上优势显著——需要跨文档综合推理的问题，裸检索只能返回分散片段，完整管道能整合成结构化答案。简单事实查询差异不大。裸检索的 score 分布印证了一个规律：问题越聚焦，向量检索越准。

#### 踩坑复现

两轮 MCP 迭代踩了同一个坑两次：bootstrap 有全局路径前缀 `/api/ragent`，Controller 的 `@RequestMapping` 不能重复写 `/api`。V1 的 RetrieveController 踩过一次 404，V2 的 ragent_qa 又踩了一次——**说明 SKILLS 的价值：V1 记录过这个前缀问题，V2 立刻定位到根因。**

#### FC vs MCP（面试必考区分点）

FC（Function Calling）是 LLM 侧的能力，MCP 是工具侧的协议，它们互补不替代。

```
FC 视角（LLM 决定调什么）：
  用户问题 → LLM 看到 tools 列表 → 输出 {"name":"weather", "args":{"city":"北京"}}
  → 应用层拿到 JSON 去调工具 → 结果塞回 LLM → 生成最终回答

MCP 视角（工具怎么被发现和调用）：
  Agent 启动 → 连接 MCP Server → tools/list → 知道有哪些工具
  → Agent 决定调用 → JSON-RPC 请求 → MCP Server 执行返回

Ragent 里两者的组合：
  意图路由命中 MCP 节点 → param_prompt_template 做参数提取（FC 的思路）
  → 拿到参数后走 MCP 协议调 mcp-server → Executor 执行
```

#### 面试话术

**Q15 MCP 工具调用原理？**

> MCP 是标准化工具注册和调用的协议，基于 JSON-RPC 2.0。我们的 mcp-server 实现了 SSE 端点和 POST 端点，Agent 连接后通过 tools/list 发现可用工具，通过 tools/call 执行调用。实现新工具只需要 implements MCPToolExecutor，定义 toolId、description、parameters 和 execute 方法，加 @Component 自动注册。

**Q16 FC 和 MCP 什么关系？**

> FC 是 LLM 输出结构化工具调用指令的能力，MCP 是标准化工具注册和调用的协议。FC 解决"LLM 怎么知道该调什么工具、传什么参数"，MCP 解决"工具怎么被发现、怎么被统一调用"。在我们系统里，意图路由+参数提取用的是 FC 思路，工具执行走的是 MCP 协议。

**追问：你做过数字化评估吗？**

> 有。用 6 个测试问题分别通过 knowledge_search（裸向量检索）和 ragent_qa（完整 RAG 管道）获取结果做对比。ragent_qa 正确率 100%，knowledge_search 平均 top1 score 0.71。关键发现是完整管道在跨文档综合推理上优势显著，简单事实查询差异不大。这组数据既是评测基线，也是后续任何改动的对比参照。

---

## 三、分歧教训：文档层 vs 实现层的四处裂缝

> 这一节的价值不在于纠错，而在于暴露了 RAG 系统的一个本质局限——**知识库无法区分"文档在讲框架能力"和"项目实际用了什么"**。每处分歧都是面试时可以主动提的深度观察。

### 3.1 四处分歧全览

| 维度         | 知识库文档（ragent_qa 回答）              | 源码实际（CC 验证）                      | 根因                 |
| ------------ | ----------------------------------------- | ---------------------------------------- | -------------------- |
| BM25         | 已实现，Milvus 2.5+ 原生 text_sparse 字段 | 未实现，只有 KEYWORD_ES 枚举预留         | 教程描述框架能力全集 |
| 合并策略     | RRF 融合（RRFRanker）                     | 简单去重 + 取最高分                      | 教程描述理想方案     |
| Rerank 模型  | BAAI/bge-reranker-v2-m3 via SiliconFlow   | qwen3-rerank via 百炼 API                | 文档是作者示例配置   |
| LLM 调用次数 | 1-2 次（改写/工具/生成互斥）              | 3-5 次（重写+意图+生成顺序执行，不互斥） | ragent_qa 推理错误   |

### 3.2 根因分析

前三处分歧的根因是同一个：知识库的 42 篇 ragentdocs 文档包含了项目作者写的教程（《第10小节：向量检索策略与召回优化》等），这些文档描述的是 **Ragent 框架支持的能力全集**——框架确实支持 BM25、RRF、多种 Rerank 模型。但**我实际部署的实例**用的是百炼 API 的 qwen3-rerank，BM25 通道没有启用，合并走的简单去重。

第四处（LLM 调用次数）是 ragent_qa 的推理错误——它从文档中读到"查询重写是可选的"就推断为"互斥"，实际代码中重写和意图分类是顺序执行的固定链路。

### 3.3 这个发现本身就是面试素材

面试时可以主动提：

> 我在做定量评测时发现，ragent_qa 对检索链路的回答和源码实际实现有出入——知识库里的教程文档描述了框架支持的全部能力（BM25 混合检索、RRF 融合），但实际部署只启用了纯向量检索+简单去重。这暴露了 RAG 系统无法区分"文档在讲框架能力"和"项目实际用了什么"。解决思路是文档入库时加身份标签——教程标记为"参考设计"，部署配置标记为"当前实现"，让 LLM 在生成时区分引用来源。

### 3.4 方法论启示

**交叉验证是必要的。** ragent_qa（知识库）和 CC（源码）各有盲区：知识库有信息但不区分身份，CC 有精确代码但缺全局语义。两者交叉验证才能逼近真相。

**以代码为准，但理解文档的价值。** 文档描述的"框架能力全集"不是错误信息——它告诉你系统的设计空间有多大，哪些能力可以通过配置启用。面试时说"架构预留了 BM25 扩展点"比说"我们实现了 BM25"更诚实也更有深度。

---

## 四、面试全链路速查

> 对应美团暑期后端一面面经的问题序列，每题标注核心答案出处和追问预判。

### 4.1 全链路讲述（Q1/Q7）

**Q1 离线+在线+架构完整讲一遍**

> 离线链路：文档上传到 S3 存储，手动触发分块——Tika 解析提取纯文本，StructureAware 分块按结构边界切分（targetChars=1400，不切断标题和代码块），qwen-emb8b 编码成 4096 维向量存入 Milvus，同时 MySQL 记录 chunk 元数据。分块操作用 Redisson 分布式锁保护，文档级粒度。
>
> 在线链路：用户提问后建立 SSE 连接，加载最近 4 条消息做上下文。查询重写做指代消解和术语归一化，然后意图路由——从 Redis 加载意图树，LLM 对所有叶子节点打分，命中节点的 kind 字段决定走向量检索还是 MCP 工具调用。向量检索后经过去重、Cross-Encoder Rerank 精排、自适应截断去噪音，拼装 context 加上文档来源标签，最终 LLM 流式生成回答并附着引用溯源。
>
> 基础设施：MySQL 做持久化，Redis 做缓存/锁/限流/跨实例通信，Milvus 做向量检索，百炼 API 提供 LLM 和 Rerank 能力，多模型降级保证可用性。

### 4.2 分块与 Embedding（Q2/Q3）

**Q2 分块怎么做的？**

> StructureAware 分块，先识别文档结构再在结构边界切分。targetChars=1400 是目标不是硬限制，代码块超长宁可整块保留。和简单按字符数切的区别是保证了每个 chunk 语义完整。

**Q3 Embedding 用什么模型？**

> 百炼平台的 qwen-emb8b，8B 参数量，输出 4096 维向量。知识库表有 embeddingModel 字段，不同知识库可以用不同模型——这是一个扩展点。

### 4.3 检索排序（Q5/Q6/Q9/Q10/Q11）

详见 Section 2.3 检索排序链。核心三句话：粗排 Bi-Encoder 向量召回，细排 Cross-Encoder Rerank 精排，自适应截断去噪音。当前纯向量检索无 BM25（预留扩展点）。LLM 基础路径 3 次调用，MCP 路径 4 次，摘要可能第 5 次。

### 4.4 意图识别（Q7/Q8）

详见 Section 2.1 意图树。核心：带层级标签的扁平列表 + LLM 分类器，一次调用对所有叶子节点打分，阈值 0.6，兜底全局检索。

### 4.5 多模型降级（Q12/Q13/Q14）

详见 Section 2.2。核心：for 循环 + 三态状态机，连续失败 2 次熔断，30 秒半开探测，流式场景 ProbeBufferingCallback 缓冲代理。

### 4.6 MCP 与工具调用（Q15/Q16）

详见 Section 2.6。核心区分：FC 是 LLM 侧能力（决定调什么），MCP 是工具侧协议（怎么被发现和调用）。Ragent 里意图路由+参数提取是 FC 思路，工具执行走 MCP 协议。

### 4.7 数据库与存储（Q17/Q18/Q19/Q20）

详见 Section 2.4。核心：五张表两条链，会话表+消息表一对多，role 只有 user/assistant，分布式锁两种方案（Redisson + DB CAS）。

### 4.8 Redis（Q21/Q22）

详见 Section 2.5。核心：五种角色（加速/协调/通信/限流/基础设施），穿透用固定 key 规避，击穿用主动失效+TTL 双保险，雪崩靠 TTL 天然分散。

### 4.9 数字化评估（Q4）

> 做过。6 个测试问题 × 2 个通道（裸向量检索 vs 完整 RAG 管道）的对比评测。ragent_qa 正确率 100%，knowledge_search 平均 top1 score 0.71。关键发现：完整管道在跨文档综合推理上优势显著，简单事实查询差异不大。这组数据是后续任何改动的对比基线。
>
> 评测过程中还发现了知识库文档与实际实现的分歧（BM25/RRF/Rerank 模型），这本身就是 RAG 系统的一个经典 bad case——知识库无法区分文档身份。

### 4.10 算法题（Q22/Q23/Q24）

这部分是通用算法能力，不在认知地图范围内。面经记录的是合并区间（Q22）、接雨水（Q23）、LRU 缓存（Q24）。

---

## 五、从 RAG 到 Agent：当前系统在这条路上走到了哪里

### 5.1 演进路线图

```
RAG（被动回答）→ RAG+MCP（可被调用）→ Agent（主动规划）→ 自进化智能体
     V1/V2完成        MCP V1/V2完成        ← 我们在这里的门口        未来
```

**已经走通的：** 系统能回答问题（RAG），能被外部 Agent 通过 MCP 调用（CC 查知识库），有可观测性（日志/分数/引用溯源），有定量评测基线。

**正在门口的：** Agent 主动规划。当前 CC 不会主动去搜 SKILLS，需要人告诉它"去知识库找之前的记录"。理想状态是 CC 启动时 system prompt 里有一句"知识库里有 SKILLS 文档，遇到项目相关问题先搜"，形成自动的 ReAct 循环。

**未来的理想闭环：** 人策展 SKILLS → 入库 → CC 自己找到 SKILLS → 恢复上下文 → 协作 → 产生新知识 → 人策展新 SKILLS。当前断点在"CC 不会主动搜"这一步。

### 5.2 自动化飞轮的当前断点

```
理想链路：人写SKILLS → 入知识库 → Agent搜到 → 恢复上下文 → 协作产生新知识 → 人策展新SKILLS
                                      ↑ 断点在这里
当前链路：人写SKILLS → 入知识库 → 人告诉Agent去搜 → 恢复上下文 → 协作 → 人写新SKILLS
```

三个可能的修复方向：CC 的 CLAUDE.md 里加知识库使用指引（最低成本）；把 knowledge_search 拆成三个按知识库分开的工具让 description 做路由（MCP V2 未验证的接线点 1）；给 SKILLS 文档加 metadata 标签让检索优先返回（接线点 3）。

### 5.3 Agent SFT 数据采集

ragent_qa 每次调用自动产生 (question, answer, references) 三元组——这是天然的 SFT 训练数据，属于"线上日志"路线。

Agent SFT 训练数据的四条路线：强模型蒸馏（用 GPT-4 生成训练数据）、线上日志（我们在做的）、人工种子+LLM 扩写、开源数据集。评测集（100-200条）比训练集更重要——6 个测试问题是起点，bad case 应该持续积累。

### 5.4 RAG 向 Agent 的安全维度

当 RAG 从"被动回答"变成"主动执行工具"，安全边界发生了本质变化：

**当前（RAG）：** 最坏情况是回答错误——把参考论文的内容当成自己的工作（V2 阶段五发现的问题）。影响范围限于信息质量。

**未来（Agent + 工具调用）：** 最坏情况是执行了不该执行的操作。如果 Agent 能调数据库写入、调外部 API、调文件系统，一次错误的工具调用可能造成不可逆的影响。

**最小化代价最大化收益的安全策略：** Docker 容器隔离 + 资源池化。每次工具调用在沙箱里执行，失败了丢掉容器，不影响宿主。这和安全领域的思路一致——不是防止所有攻击，而是把爆炸半径控制在可接受范围。

---

## 六、文档体系的定位与分工

> 不是所有文档都该做同一件事。每种文档有自己的角色。

### 6.1 文档角色分工

| 文档类型               | 代表                       | 角色                                   | 类比                                     |
| ---------------------- | -------------------------- | -------------------------------------- | ---------------------------------------- |
| SKILLS                 | RAG_ITERATION_SKILLS V1/V2 | 冻结的认知链条，记录"为什么这样做"     | Git commit——不可变的历史快照             |
| MCP SKILLS             | MCP_ITERATION_SKILLS V1/V2 | 工具书，给 LLM 装上手的操作手册        | API 文档——怎么调、踩了什么坑             |
| CC 探索记录            | 01/02 两篇对话记录         | 一次性的 work memory，过程中的思考痕迹 | 实验笔记——探索过程有价值但不适合直接复用 |
| **认知地图（本文档）** | Ragent 认知地图 v1         | 活图谱，消化所有文档后的压缩与提炼     | 地图——可以从任意节点进入，沿链路走通全局 |

### 6.2 为什么需要认知地图

SKILLS 是线性叙事（"我们做了什么→发现了什么→怎么改的"），适合回顾单轮迭代但不适合跨轮检索。CC 探索记录是对话流（"猜测→验证→修正"），信息密度高但噪音也多。

认知地图把它们都消化了，以**组件**而非**时间**为索引。想知道意图树怎么工作？直接翻 2.1，不需要从 RAG V1 的"阶段二"开始读。想准备面试？翻 Section 4 速查表。想继续迭代？翻各组件末尾的"追问预判"和 Section 5 的线索。

### 6.3 迭代机制

这份文档不是写完就定的。迭代规则：

- **新一轮 SKILLS 完成后**：更新对应组件的祛魅点和面试话术，在系统状态快照里更新数字
- **发现新分歧时**：加入 Section 3 的分歧表
- **面试后**：根据实际被问到的问题和自己的回答质量，调整 Section 4 的优先级和话术
- **新 idea 产生时**：挂到 Section 5 的"未探索的边"

---

## 七、认知收获与方法论

> 不是八股文，是四轮迭代和两轮探索中真正内化的东西。

### 7.1 关于系统理解

- **分数分布形态是问题和知识库匹配模式的"指纹"。** 断崖型=答案集中，平缓型=知识分散。改动切分策略、embedding 模型、知识库范围中的任何一个，都会改变这个指纹。
- **上下文质量 > 上下文数量。** 10 个 chunk 里 7 个是噪音时，LLM 注意力被稀释。这和深度学习中"小而干净的数据集优于大而嘈杂的数据集"是同一个道理。
- **每个模块不是孤立的，存在信息传递链。** 意图识别的 score 影响检索通道选择，检索的 topK × multiplier 决定候选池大小，rerank 的截断决定最终送给 LLM 的内容。改动一个节点，沿链路传播影响。
- **配置错误比算法缺陷更致命。** collection 名写错一个字符，路由全部偏移。先查配置，再查算法。

### 7.2 关于方法论

- **"直觉→猜测→验证→思考→调整"比"先学完再动手"更高效。** 不需要通读全部源码再开始优化。定位到关键三个文件、理解数据流方向、找到作者留的 TODO，就足以开始第一个实验。
- **调查先行，执行在后。** 给 CC 的指令永远先调查，拿到信息后再设计执行。零信息的执行（zeroshot）成功率远低于有信息基础的执行。
- **控制变量。** 每次只改一个东西（加意图描述、加分数过滤、加自适应截断），才能准确归因。
- **知道什么时候停下来。** 子查询并行诊断做完发现当前不是瓶颈——放弃。引用溯源对 LLM 检索增强为零——接受。不是所有发现的问题都值得现在解决。
- **最小化撬动 > 系统重构。** DocumentNameCacheService 一个 HashMap，DefaultContextFormatter 改一个方法，胜过重新设计整个 prompt 架构。

### 7.3 关于协作模式

```
V1 角色：思考者（Claude）+ 执行者（CC）
V2 角色：决策者（人）+ 思考者（Claude）+ 后端CC + 文档CC + 被观察者（Ragent）
```

核心模式是**人工 ReAct 循环**：思考者生成 Action，人执行并返回 Observation，思考者基于 Observation 生成下一个 Action。MCP 打通后，部分 Action 可以自动化（CC 直接查知识库），但规划和判断仍然是人的工作。

---

## 八、当前系统状态快照

```
服务器: 腾讯云轻量 2C4G, Ubuntu 22.04, 101.42.96.96
中间件: MySQL 8, Redis 7, Milvus 2.6.6, RustFS 1.0.0-alpha.72, Nginx

服务进程:
  bootstrap     → 9090 (systemd: ragent.service)
  mcp-server    → 9099 (systemd: ragent-mcp.service)

知识库:
  ragentdocs    → 42篇，Ragent项目技术文档
  ssddocs       → 31篇，SSD/Mamba模型原理
  dualssddocs   → 105篇，蛋白质交互预测（含9篇废弃标注）

意图树:
  ROOT
  ├── Ragent项目 (DOMAIN/KB, ragentdocs) — 含详细描述+10个示例问题+排他性指引
  └── 科研项目 (DOMAIN)
      ├── SSD模型研究 (CATEGORY/KB, ssddocs) — 强调"纯模型理论"
      └── 蛋白质交互预测 (CATEGORY/KB, dualssddocs) — 强调"改SSD做蛋白质任务"

已注册 MCP 工具（5个）:
  weather_query / ticket_query / sales_query → 模拟数据
  knowledge_search → 裸向量检索（真实数据）
  ragent_qa → 完整RAG管道问答（真实数据）

代码改动（相对原版 Ragent，V1+V2+MCP V1/V2）:
  RAG V1: chunk详情日志、分数分布日志、自适应TopK截断(阈值0.15,保底3)
  RAG V2: metadata管道修复(3个断点)、引用溯源、文档级标签注入、子查询诊断日志
  MCP V1: RetrieveController + KnowledgeSearchMCPExecutor
  MCP V2: RagentQaMCPExecutor（SSE流式处理）

LLM 配置: 百炼 (路由/重写/意图/生成), 备选 siliconflow
Rerank: 百炼 qwen3-rerank, 降级 rerank-noop

评测基线:
  6个测试问题 × 2个通道 = 12组数据
  ragent_qa 正确率 100%, knowledge_search 平均 top1 score 0.71
  自适应截断: Q3/Q4 触发(10→3), Q1/Q2/Q5/Q6 不触发
```

---

## 九、未探索的边

> 这些不是待办清单，而是在迭代中自然浮现的线索。当被真实需求暴露为痛点时，才是值得投入的时机。

### 接线点（MCP V2 提出，未实施）

- **接线点 1（最高优先级）：** 把 knowledge_search 拆成三个按知识库分开的工具，description 复用意图树描述，验证"MCP 工具描述能否替代意图树路由"——如果等效，意味着意图树是多余的中间层
- **接线点 2：** knowledge_search 返回值加 score 分布摘要，让 CC 自己做截断决策
- **接线点 3：** 给 SKILLS 文档加 metadata 标签，检索优先返回 SKILLS 类文档

### 检索质量深化

- rerank 前后的 chunk 排名变化尚未观察——不知道 rerank 到底是大幅重排还是微调顺序
- embedding 对中文技术文档的理解质量未独立评估——所有优化建立在"embedding 够好"的未验证假设上
- 自适应截断阈值 0.15 基于 6 个样本，扩大后可能需要调整
- 评测只有二元判断（正确/不正确），缺少 Recall@K、Precision@K 等细粒度指标

### 规模化挑战

- 跨领域文档导入后，意图树路由准确性需重新验证
- 文档更新和版本管理机制不存在——旧版本 chunk 和新版本 chunk 共存
- `text.hashCode()` 的碰撞风险在几百篇文档量级可能成为隐性 bug

### 范式对比中的洞察

**Supermemory** 的后端提取逻辑是黑盒（开源仓库缺 extracting 阶段代码），**OpenViking** 的"目录递归检索"和我们的"意图树路由"是同一个问题的两种解法——前者用向量分数做路由（自动化但依赖 embedding），后者用 LLM 做路由（精准但依赖人工配置）。我们的 SKILLS 文档本质是人工版的 L0/L1 摘要——质量更高但吞吐量受限于人。





阅读完的一些思路：

1.车道机制：发现一次提问太多问题，chunk会分散，注意力也会分散，自适应TopK已经发现现在只提问一个特别集中的问题效果相对较好，如果触发截断，命中的前几个chunk分数相对较高（这也是LLM rerank后的，不过chunk目前没法入对话这是小问题，真正的问题是rerank后的分数实际上是和原始的分数有可能一定虚高的，毕竟是大模型打分，然而这种情况对比让LLM完成复杂的数学计算然后直接生成JSON其实还算可行，后续可以挖掘这方面），这里需要前置查询改写与意图识别这里可能要新增或者最小化修改完成因果序列查询，并流式执行。

2.数据“懒收集”：例如chunk被召回时统计频次
3.流匹配，借鉴扩散模型中流匹配的最小化路径，动量最小化的路径就是加速度为0的路径的无数直线的和，形象的例子就是我们可能进行多个空间的探索，才能“绕弯路”到最终可用的需要状态，而我们现在假如让LLM as Judge少量样本也会面临这样的问题，而大量样本上下文显然不可行，而且每次送入的不一样，会导致标准也会不一，因此这里亟需一种可行的“创新”来实现样本的收集，例如在线用户的对话后的实际反馈，例如用户在查询中的表明的隐式的“赞美或埋怨”的信息，并通过对话轮次自动从conversation级别的这么多的自然语言中自动提出这次对话的满意度，然后基于这些满意度有其中的调用信息被我们通过预先设计的机制保留，接着就可以让LLM来基于这些“数据”以及我们需要的“优化目标”例如让效果更好，更准确，并附上其中的各种信息例如这个对话对应的可溯源链路状态，包括响应时间，chunk数，是否触发车道机制或其他机制，意图树的命中效果如何，最后按照深度学习批次的概念总出数据化的内容，然后让LLM作为所谓的简单深度学习预测器，自然语言即向量数据（其中就用几个块加prompt拼接就能塑造数据空间，这些块需要包括我们之前收集的信息Observation本身加元数据描述，系统状态作为输入影响例如我甚至可以把能搜集到的信息都整理进来当然这个应该与前面的Observation不同，这里更需要系统的各种参数状态，或者说是一种更大程度，更高层次的ReAct）
4.补充：CC源码相关内容可以灵活运用，两者都是手头有的资源，不局限于各自的思路，只为打造好用的助手，为使用者带来更好的体验

---

*文档版本 v1 · 2026-04-01 · 持续迭代中*