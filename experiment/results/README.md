# Agent Loop 实验系列

本目录记录 Agent Loop 架构演进的完整实验过程。

## 实验目录

| 实验 | 目标 | 状态 |
|-----|------|------|
| [experiment0](./experiment0/) | 验证 Agent Loop 骨架（echo 工具） | ✅ 完成 |
| [experiment1](./experiment1/) | 真实 Agent Loop（向量检索） | ✅ 完成 |
| [experiment1_5](./experiment1_5/) | 统一接口 + Rerank 检索 | ✅ 完成 |
| [experiment2](./experiment2/) | 多工具编排（rewrite + search） | ✅ 完成 |
| [experiment3](./experiment3/) | 跨知识库综合检索 | ✅ 核心验证完成 |

## 核心演进

```
experiment0: while(true) + tool_calls 骨架验证
    ↓
experiment1: 添加 System Prompt + KnowledgeSearchTool
    ↓
experiment1_5: McpToolAdapter 统一接口 + Rerank 检索链路
    ↓
experiment2: 双工具编排 + 按需重写决策
    ↓
experiment3: 多知识库路由 + 跨库综合
```

## 关键发现汇总

### System Prompt 设计模式

CC 的三层引导：
1. **System Prompt**：静态角色定义、行为准则
2. **Tool Description**：工具能力说明
3. **Tool Result Prompt**：返回内容中的隐式引导

### 工具接口统一

`McpToolAdapter` 实现：
- MCPToolExecutor（外部 Agent 用 MCP 协议调用）
- Tool 接口（内部 Agent Loop 进程内调用）
- 同一个工具实现，两种调用方式

### Agent Loop 底层原理

详见 [agent_loop_internals.md](./agent_loop_internals.md)

核心洞察：
- while(true) 不是"一直跑"，而是"暂停-恢复"模式
- 99% 时间在等待 LLM 响应（网络阻塞）
- 同步阻塞模型适合串行依赖场景

### Agent vs Pipeline 对比

| 维度 | Agent Loop | Pipeline |
|-----|-----------|----------|
| 编排方式 | 模型自主决策 | 代码预定义流程 |
| 灵活性 | 高（可动态调整） | 低（固定步骤） |
| 可控性 | 低（依赖模型） | 高（确定性流程） |
| 适用场景 | 探索性、复杂决策 | 生产级、稳定流程 |

### 实验2核心结论

**"按需重写"优于"无脑总是重写"**：

| 场景 | Agent Loop | Pipeline |
|-----|-----------|----------|
| 清晰问题（Q1） | 2轮，跳过重写 ✅ | 固定重写，浪费1次LLM调用 |
| 口语问题（Q10） | 3轮，重写后高分 ✅ | 重写后检索，效果相当 |
| 无匹配问题（Q7） | 5轮，多角度尝试 ⚠️ | 固定流程，1次重写就放弃 |

**超预期发现**：
- 模型学会了使用 `intent_hint` 参数引导重写
- 低分时模型主动换角度重试
- 知识库无匹配时诚实回答，不胡编乱造

### 实验3核心结论

**"跨库综合"是 Agent Loop 的杀手级场景，更有"研究助理"级质变**：

| 问题 | 模型行为 | 价值层级 |
|-----|---------|---------|
| Q1-Q2 | 单库路由 | Level 1: 检索 |
| Q5 | 跨库并行调用 | Level 3: 综合 |
| Q6 | 理论应用分析 | Level 4: 推理 |
| **Q7** | **主动补全 + 创新建议** | **Level 4+: 研究助理** |

**Q7 的重大发现**：
- 模型在 Turn 2 发现信息不足，**主动去 ssddocs 补全理论基础**
- 不只是检索+拼接，而是生成**迁移可行性分析**和**具体实现方案**
- 这是从"回答问题"到"生成见解"的质变

**Agent Loop 价值跃迁**：

```
Level 1: 单库检索
Level 2: 多工具编排
Level 3: 跨库综合
Level 4: 研究助理 ← 质变点！
```

**Agent Loop vs Pipeline（最终对比）**：

| 能力 | Pipeline | Agent Loop |
|-----|----------|------------|
| 知识补全 | ❌ 固定流程 | ✅ 按需补全 |
| 深度推理 | ❌ 需要预编程 | ✅ 模型自主 |
| 创新建议 | ❌ 无法生成 | ✅ 可生成 |
| 新场景适配 | ❌ 需要修改代码 | ✅ 无需修改 |