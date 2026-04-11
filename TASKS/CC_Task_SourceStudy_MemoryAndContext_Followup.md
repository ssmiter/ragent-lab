# CC 任务(Follow-up):压缩层级核实 + 记忆/压缩的概念分离

> 前置文档:`CC_Task_SourceStudy_MemoryAndContext.md` 和它的 Report
> 本次任务:在前次探查的基础上,**核实遗漏的压缩层级**,并**重画一张能区分"记忆系统"与"压缩系统"的全景图**

---

## 背景:为什么需要这次 follow-up

上一次的探查报告非常有价值,猜测 B 和猜测 C 都得到了清晰的验证。但在和外部资料对比后,我们发现两个需要补强的地方:

### 问题1:压缩层级数量对不上

上次报告找到 **4 层压缩**:
1. Time-based Microcompact
2. Cached Microcompact
3. Session Memory Compact
4. Legacy Compact

但一份引用 CC 源码的外部资料明确提到 **5 级压缩**:
1. **Snip 剪裁**(最轻,旧 tool_use 只保留结构)
2. **Microcompact 微压缩**(把工具结果卸载到缓存)
3. **Context Collapse 折叠**(对中间对话做折叠摘要)
4. **Autocompact 自动压缩**(超阈值触发全量摘要)
5. **Reactive Compact 应急压缩**(API 返回 413 时的兜底)

这份外部资料还引用了 `query.ts` 顶部一段非常关键的代码:

```typescript
// query.ts — 压缩模块按需加载
const reactiveCompact = feature('REACTIVE_COMPACT')
  ? require('./services/compact/reactiveCompact.js') : null
const contextCollapse = feature('CONTEXT_COLLAPSE')
  ? require('./services/contextCollapse/index.js') : null
const snipModule = feature('HISTORY_SNIP')
  ? require('./services/compact/snipCompact.js') : null
```

**强烈怀疑:上次探查漏掉了 feature-flagged 的三个模块**(Snip、Context Collapse、Reactive Compact),因为它们用 `feature('XXX')` 按需加载,在 grep `compact` 关键词时容易被跳过。

我们不是要纠结"4 还是 5"这个数字本身,而是关心**这三个被漏掉的层各自对应什么场景**——它们大概率代表了三种我们没看到的设计决策。

### 问题2:记忆和压缩被混在了一起讲

上次的全景图把 Session Memory 提取流程和压缩流程画在了同一张图里,这会让人误以为"记忆 = 压缩的一种形式"。但实际上它们是**两个独立子系统**:

- **记忆系统**:回答"什么值得长期保存和按需取回"——存储侧设计
  - 三层结构(博客提到):MEMORY.md(热)/ 话题文件(温)/ transcript JSONL(冷)
  - 始终运行的存储管道,生命周期 = 整个会话
  
- **压缩系统**:回答"当前窗口装不下时怎么瘦身"——运行时优化
  - 五层(待核实)从轻到重的渐进降级
  - 触发式运行,生命周期 = 阈值超出的瞬间

它们在**两个点上耦合**:
1. Session Memory(记忆侧产物)被 Session Memory Compact(压缩侧一层)消费
2. Compact 后摘要消息中包含 transcript 路径(压缩侧提示模型如何回到记忆侧)

但**它们不是同一件事**。混在一起讲会丢失各自的设计逻辑。

---

## 你需要做的事情

### 第一步:核实压缩层级

#### 1.1 确认 feature-flagged 模块是否存在

在 `claude-code-sourcemap/restored-src/src/` 下查找以下三个文件,如果存在,确认它们的存在性和大致功能:

```
services/compact/snipCompact.js (或 .ts)
services/contextCollapse/index.js (或 .ts)  
services/compact/reactiveCompact.js (或 .ts)
```

同时在 `query.ts` 中搜索 `feature(` 关键词,列出所有 feature-flagged 的压缩相关模块。

#### 1.2 对每个找到的模块,回答以下问题

| 模块 | 触发条件 | 操作 | 是否调 LLM | 信息去向 | 在 query.ts 中的调用位置 |
|---|---|---|---|---|---|
| Snip | ? | ? | ? | ? | ? |
| Microcompact (Time-based) | ✅ 已知 | ✅ 已知 | 否 | ✅ 已知 | ? |
| Microcompact (Cached) | ✅ 已知 | ✅ 已知 | 否 | ✅ 已知 | ? |
| Context Collapse | ? | ? | ? | ? | ? |
| Autocompact | ? | ? | ? | ? | ? |
| Reactive Compact | ? (验证 API 413) | ? | ? | ? | ? |
| Session Memory Compact | ✅ 已知 | ✅ 已知 | 部分 | ✅ 已知 | ? |
| Legacy Compact | 上次发现的"调 LLM 摘要" | 是不是就是 Autocompact 的一种实现? | 是 | ✅ 已知 | ? |

#### 1.3 关键澄清:Autocompact 和 Legacy Compact 是什么关系?

上次报告中的 "Layer 4: Legacy Compact" 是不是博客里说的 "Autocompact"?还是两个不同的东西?
- 如果是同一个,为什么命名不一致?是历史遗留命名吗?
- 如果不是,各自的触发场景区别是什么?

#### 1.4 渐进式降级的真实顺序

外部资料说的顺序是:Snip → Microcompact → Context Collapse → Autocompact → Reactive Compact。

**请在 query.ts 或 autoCompact.ts 里找到决定调用顺序的代码**,确认实际的降级顺序是什么。是否真的"从最轻到最重"?是否有"跳过中间层"的情况?

---

### 第二步:画出"记忆系统"独立的架构图

**只画记忆系统,不混入压缩**。基于以下文件深入:

```
memdir/memdir.ts                  ← MEMORY.md 加载、截断
memdir/findRelevantMemories.ts    ← 话题文件按需召回
services/SessionMemory/           ← 已探查过
utils/sessionStorage.ts           ← Transcript JSONL
```

**要回答的具体问题:**

1. **MEMORY.md 是什么时候加载的?** 每轮都重载?还是会话开始时加载一次?在哪个文件、哪个函数?

2. **话题文件的召回流程**:
   - 触发时机(每轮?新对话?)
   - 召回用什么模型(博客说是 Sonnet 小模型,核实)
   - `findRelevantMemories.ts` 中召回提示词的完整内容(贴出)
   - 最多召回几个文件(博客说 5,核实)

3. **三层记忆的"温度"对应的实际数据流**:
```
   热(MEMORY.md)    → 每次都加载到上下文 → 大小有硬限制(博客说 200行/25KB,核实)
   温(话题文件)     → 按需召回         → 召回逻辑是?
   冷(transcript)   → Grep 搜索        → 模型用什么工具搜?Read/Grep/专用工具?
```

4. **记忆和 Session Memory 的关系**:
   - `services/SessionMemory/` 和 `memdir/` 是同一套东西的两个角度,还是两套并行系统?
   - 如果是两套,各自管什么?

**输出格式:** 一张只画记忆系统的架构图(文本版),清晰标出三层结构、加载时机、容量限制、召回机制。

---

### 第三步:画出"压缩系统"独立的架构图

**只画压缩系统,不混入记忆的存储细节**(但可以标注"此处消费/产出记忆系统的数据")。

基于第一步核实后的真实层级数,画一张图,要包含:

1. **每一层的触发条件**(token 阈值?消息数?时间?API 错误?)
2. **每一层的输入和输出**(改的是什么?改成什么?)
3. **降级的顺序和跳过条件**(在什么情况下从 Layer N 直接跳到 Layer N+2?)
4. **是否调 LLM**(用不同标记区分)
5. **断路器机制**(博客提到 `MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3`,核实并标注)

---

### 第四步:画出两个系统的"耦合点"图

这是这次任务最关键的输出。前两步把两个系统拆开讲清楚,这一步要把它们的**接触面**画出来——但只画接触面,不重复前两步的内容。

要回答的核心问题:

1. **记忆 → 压缩的输入**:Session Memory 的产物在哪一层压缩中被消费?(上次报告说是 Layer 3)被消费的具体形式是什么?
2. **压缩 → 记忆的提示**:压缩后的 summary message 中如何引导模型回到记忆系统?(博客说是 transcript 路径,核实其他记忆资源——MEMORY.md、话题文件——是否也被告知)
3. **生命周期对比**:记忆是"始终在跑",压缩是"按需触发"——这个区别在代码层面如何体现?有没有共享的 state 或 hooks?

**输出格式:**

```
┌─────────────────────┐         ┌─────────────────────┐
│   记忆系统(常驻)    │         │   压缩系统(触发式)  │
│                     │         │                     │
│  MEMORY.md          │         │  Layer 1: Snip      │
│  话题文件           │ ←──①──→ │  Layer 2: Micro     │
│  Session Memory     │         │  Layer 3: SM Compact│
│  Transcript JSONL   │ ←──②──→ │  Layer 4: Auto      │
│                     │         │  Layer 5: Reactive  │
└─────────────────────┘         └─────────────────────┘

耦合点 ①:Session Memory 被 SM Compact 消费
        - 消费时机:?
        - 消费形式:?
        
耦合点 ②:Compact summary 引用 transcript 路径
        - 提示文本:?
        - 模型可用的恢复工具:?
```

---

## 关键约束

1. **聚焦补漏 + 概念分离**——不重复上次报告里已经验证的内容(可以直接引用上次的发现),只补充缺失的层级和厘清两个系统的边界
2. **代码证据优先**——每个层级的确认都要有文件路径和关键代码片段,尤其是 feature-flagged 模块的实际实现
3. **不要把记忆和压缩混在一张图里**——这是上次报告的主要遗憾,这次必须分开画。耦合点单独成图
4. **诚实标注 feature flag 状态**——如果某个模块在源码中存在但默认 feature flag 是关闭的,明确标注"默认关闭/默认开启",这影响"它是不是 CC 当前生产路径的一部分"

---

## 预期产出

1. **压缩层级核实表**——回答"到底是几层、各自做什么、Autocompact vs Legacy Compact 是不是一回事"
2. **独立的记忆系统架构图**——三层热温冷
3. **独立的压缩系统架构图**——核实后的真实层级数
4. **耦合点图**——两个系统在哪两个点上接触,接触的具体形式是什么
5. **对上次报告的修正清单**——哪些地方需要修订,新增/修改了什么(简短列表即可)

---

## 我们最想从这次补漏中学到什么

1. **"记忆"和"压缩"作为两套不同的子系统,各自的设计哲学是什么?** 它们都在解决"上下文有限"这个问题,但解法的层级和触发逻辑完全不同。理解这种分工会让 ragent 的设计更清晰——不能用"一个记忆系统"打天下,而是要分清楚"我要存什么"和"我要在 runtime 怎么省 token"是两件事。

2. **被漏掉的三层(Snip/Collapse/Reactive)各自对应什么场景?** 上次找到的四层都是"主动的、阈值触发的",但 Reactive Compact 是 API 错误兜底——这是一种完全不同的触发模式。Context Collapse 是对中间对话做折叠——这是介于"完全保留"和"完全摘要"之间的中间态。这两种模式我们之前完全没意识到,可能是 ragent 应该借鉴的。

3. **为什么 CC 用 feature flag 把这三个模块隔离?** 是因为它们还在实验中?是因为不同用户群体启用不同策略?还是因为它们有性能/正确性风险需要灰度?这个问题的答案直接告诉我们"在 ragent 里实现这些功能时,应该不应该也加 feature flag"。

最终目标和上次一样——不是复制 CC 的实现,而是理解它的设计逻辑后,判断哪些机制以什么方式迁移到 ragent 最合适。
