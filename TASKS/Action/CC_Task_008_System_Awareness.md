# CC Task 008：系统自省工具 + SYSTEM 意图路由升级

> **目标：** 解决 SYSTEM 类型意图命中后模型编造回答的问题。让 Agent 具备查询系统真实元信息的能力，并让 Pipeline 在遇到自己处理不了的意图类型时能通过 Router 转交给 Agent。
> **约束：** 最小化改动。不改意图分类逻辑，不改 Router 的规则决策，不改 Agent Loop 核心逻辑。
> **产出：** `CC_Report_008_System_Awareness.md`

---

## 背景

请先阅读 `COLLABORATION_PROTOCOL.md` 了解协作方式。可浏览 FEEDBACK 目录下的历史 Report 了解项目上下文。

### 问题现象

用户问"系统里有哪些知识库"→ Strategy Router 规则判断为简单问题 → 走 Pipeline → 意图分类器正确识别为 SYSTEM 类型 → **但 Pipeline 没有获取系统真实信息的能力** → 模型编造了一个虚假的知识库列表。

### 问题根因

两个缺失叠加导致：
1. **Agent 缺少系统信息工具**——即使问题交给 Agent，它也只有 `knowledge_search_with_rerank`，回答不了元信息问题
2. **Pipeline 不能"认输"**——当意图分类命中 SYSTEM 但 Pipeline 无法提供真实数据时，没有机制将请求转交给更有能力的策略

---

## 任务内容

### 阶段一：调查（先看再动）

**1. 系统元信息的数据源**
- 意图树配置数据存储在哪里？通过什么 Service/Repository 可以获取？
- 知识库列表（名称、描述、类型）的数据在哪里？
- 这些数据是否可以在 Agent 的工具中直接通过 Spring Bean 调用获取？

**2. SYSTEM 意图的当前处理流程**
- Pipeline 命中 SYSTEM 意图后，代码走了什么分支？是跳过检索直接让 LLM 回答？还是仍然做了一次（空的）检索？
- 这个分支的处理逻辑在哪个类/方法中？

**3. Strategy 转交机制的可行性**
- 当前 PipelineStrategy 和 StrategyRouter 之间的调用关系是什么？
- PipelineStrategy 能否访问 StrategyRouter 来请求用另一个策略重新执行？
- 是否有循环依赖风险？如何避免？

> 将调查结果记录在 REPORT 中，如果发现架构上有阻碍，提出替代方案。

### 阶段二：实现系统信息工具

**目标：** 新建一个 Tool（和 `knowledge_search_with_rerank` 同级），Agent 调用它可以获取系统真实元信息。

**工具能力（按优先级）：**
1. 获取知识库列表（名称、描述、类型、领域层级）
2. 获取意图树结构概览
3. 获取系统能力说明（有哪些处理模式可用）

**实现原则：**
- 数据源全部来自系统已有的配置/数据库，不新建任何存储
- 工具输出格式对 LLM 友好（结构化文本，不是原始 JSON dump）
- 注册方式和现有 Tool 一致（Agent Loop 自动发现）

### 阶段三：实现 SYSTEM 意图的策略转交

**目标：** Pipeline 命中 SYSTEM 意图时，将请求转交给 Agent 策略处理（此时 Agent 已有系统信息工具可用）。

**设计要点：**
- **通过 StrategyRouter 转交，不是 Pipeline 直接调用 AgentLoopService**——避免 Pipeline 和 Agent 耦合
- 转交机制应该是通用的，不是只为 SYSTEM 硬编码——未来其他意图类型如果 Pipeline 也处理不了，同样可以走这条路径
- 考虑最简实现：可能只需要 PipelineStrategy 在 SYSTEM 意图时抛出一个特定的信号（异常/返回值），Router 捕获后用 Agent 策略重试

**注意：不要引入循环调用**——Router → Pipeline → Router → Agent 这条链路中，第二次 Router 调用应该是直接指定 Agent 策略（相当于 `forceStrategy=agent`），不是再走一次路由决策。

### 阶段四：验证

测试以下场景：

| 测试问题 | 预期行为 |
|---------|---------|
| "系统里有哪些知识库" | Router→Pipeline→SYSTEM意图→转交Agent→调用系统信息工具→返回真实知识库列表 |
| "你好" | Router→Pipeline→SYSTEM意图→转交Agent→正常问候（不需要调工具） |
| "Ragent的向量数据库是什么" | Router→Pipeline→正常KB意图→走Pipeline检索→正常回答（不转交） |
| "对比Pipeline和Agent的优缺点" | Router→Agent→正常Agent流程（不经过Pipeline转交） |

> 如果验证中发现 bug，授权直接修复，在 REPORT 中说明。

---

## 产出要求

生成 `CC_Report_008_System_Awareness.md`：

```markdown
# CC Report 008：系统自省工具 + SYSTEM 意图路由升级

## 1. 调查发现
- 系统元信息数据源调查结果
- SYSTEM 意图当前处理流程
- 策略转交的可行性评估

## 2. 系统信息工具
- 工具设计（名称、描述、能力）
- 数据源和实现方式
- 文件变更清单

## 3. 策略转交机制
- 选择的方案及理由
- 实现细节
- 循环调用的规避方式

## 4. 验证结果
- 各测试场景结果
- 发现的问题和修复

## 5. 遗留项（如果有）
```

---

## 注意事项

- 系统信息工具的 description 要写清楚使用场景（"当用户询问系统有哪些知识库、系统能做什么、有哪些功能时调用"），这是 Agent 决定是否调用的依据
- 策略转交时，对话历史和 conversationId 要正确传递，不能丢失上下文
- "你好"这类简单问候被分类为 SYSTEM 后转交给 Agent，Agent 应该能直接回答而不调用任何工具——这依赖 Agent 的判断能力，不需要特殊处理
- 如果调查发现 SYSTEM 意图在 Pipeline 中已有特殊处理逻辑（比如直接走通用回答模板），评估是否保留该逻辑比转交给 Agent 更简单有效，在 REPORT 中给出对比分析
