# CC Task 006：可插拔策略架构与智能路由

> **目标：** 设计并实现一个 Strategy + Router 机制，让"加一种新的问答处理模式"像"加一个新 Tool"一样简单。当前的 Pipeline RAG 和 Agent Loop 作为前两个 Strategy 实现，Router 自动为用户选择最合适的策略。
> **约束：** 最小化改动现有代码。不改 Pipeline 和 AgentLoop 的内部逻辑，只是把它们包装成 Strategy。
> **产出：** `CC_Report_006_Strategy_Router.md`

---

## 背景

请先阅读 `COLLABORATION_PROTOCOL.md` 了解协作方式。

ragent 当前有两种问答处理模式：

- **Pipeline RAG**：固定流程，快速稳定，适合简单直接的问题
- **Agentic RAG（AgentLoop）**：灵活的工具调用循环，适合需要多轮检索的复杂问题

但用户必须**手动选择**走哪条路径（不同的前端入口/端点）。我们要做的是：让系统自动完成这个选择，并且让未来新增处理模式的成本趋近于零。

### 核心设计思想

回想一下 Tool 的接口为什么好用：每个 Tool 只需要声明 name、description、parameters、execute，Agent Loop 自动发现和调度。我们要把同样的思路往上提一层——**处理策略（Strategy）也应该是一个可插拔的接口**：

```
Strategy 接口（概念性，具体设计由你决定）：
  - name + description → Router 用来判断"这个问题适合走哪条路径"
  - execute(query, context) → 按该策略处理问题并返回结果
```

Router 的职责：收集所有已注册 Strategy 的描述信息 → 决策选择（LLM 或规则均可）→ 执行选中的 Strategy → 返回结果。

**这样一来，未来加"多知识库路由""领域专家""回答验证"等新模式，只需要实现 Strategy 接口 + 注册，不改任何现有代码。**

---

## 任务内容

### 阶段一：调查现状（先看再动）

摸清两条现有路径的入口和接口边界：

1. Pipeline 模式的 Controller → Service 调用链，输入输出是什么
2. Agent 模式的 Controller → AgentLoopService 调用链，输入输出是什么
3. 两者在请求参数、SSE 输出格式、对话历史处理上的异同
4. 前端当前是怎么区分两种模式的（不同端点？参数标记？）

> 目的是确认：能否用统一的输入输出接口包装这两种模式。如果有结构性差异，在 REPORT 中说明。

### 阶段二：设计 Strategy 接口和 Router

基于调查结果，设计：

1. **Strategy 接口**——所有处理模式的统一抽象。关键要素：
   - 标识和描述（用于路由决策）
   - 统一的执行入口（输入输出格式一致）
   - Spring Bean 自动注册（新 Strategy 实现接口后自动被 Router 发现）

2. **Router 机制**——智能调度层。路由决策方式你来选择最简方案：
   - 可以是一次轻量 LLM 调用（用各 Strategy 的 description 构造 prompt）
   - 可以是基于规则的快速分类（关键词/问题长度/复杂度启发式）
   - 也可以两者结合（规则快速判断明确的，LLM 处理模糊的）
   - MVP 阶段怎么简单怎么来，能跑通就行

3. **统一入口 Controller**——用户只需要一个端点，Router 在内部自动分流

### 阶段三：实现并验证

1. 将 Pipeline 和 AgentLoop 分别包装为 Strategy 实现
2. 实现 Router 和统一入口
3. 验证：
   - 简单问题（如"XX 是什么"）是否被路由到 Pipeline
   - 复杂问题（如"对比 A 和 B 的优缺点"）是否被路由到 AgentLoop
   - SSE 输出在两种策略下是否对前端透明（用户无感知）
   - 原有的 Pipeline 和 Agent 端点是否仍然可用（不破坏现有功能）

---

## 设计原则（供参考，不是死规矩）

- **接口驱动，不是硬编码**：Router 不应该 if-else 判断"是 Pipeline 还是 Agent"，而是遍历所有已注册的 Strategy
- **最大化复用**：Pipeline 和 AgentLoop 的内部逻辑一行不改，只是外面套一层 Strategy 接口
- **渐进式设计**：现在只有两个 Strategy，但接口要能自然支撑未来的第三个、第四个
- **实际代码为准**：以上的接口描述是概念性的，具体的类名、方法签名、参数结构，请根据实际代码风格和 Spring Boot 惯例自行决定

---

## 产出要求

生成 `CC_Report_006_Strategy_Router.md`，内容：

```markdown
# CC Report 006：Strategy + Router 实现

## 1. 调查发现
- Pipeline 和 AgentLoop 的入口/接口对比
- 统一包装的可行性评估
- 发现的兼容性问题（如果有）

## 2. 设计方案
- Strategy 接口定义（实际代码）
- Router 机制设计（路由决策方式、为什么选这种）
- 统一入口的设计

## 3. 实现细节
- 文件变更清单（新增/修改）
- 关键实现决策和理由

## 4. 验证结果
- 路由准确性测试
- SSE 兼容性测试
- 回归测试（原端点是否正常）

## 5. 扩展性评估
- 如果要加第三个 Strategy（比如"多知识库路由"），需要做什么？
- 预估的改动量
```

---

## 注意事项

- 如果发现 Pipeline 和 AgentLoop 的 SSE 输出格式不一致，优先在 Strategy 层做适配，让 Router 返回给前端的格式统一
- Router 的路由决策 MVP 阶段可以先用规则，后续再替换为 LLM 决策，但接口设计要能支持两种方式切换
- 如果发现更好的设计模式（比如 Spring 本身有类似的策略模式支持），直接采用，在 REPORT 中说明选择理由
- 前端尽量不改。如果完全不改前端做不到，在 REPORT 中说明最小改动方案

PS：你还可以了解之前的TASK的执行结果（FEEDBACK目录下）从而高效的理解之前前五个 TASK 积累的认知，，标题名字已经非常清晰的表明了内容的主题
