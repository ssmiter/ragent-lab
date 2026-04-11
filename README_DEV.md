# Ragent-Lab (Development Branch)

> 基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 原项目的增强版本

本分支包含完整的开发记录、技能文档和反馈报告。

---

## 目录结构

```
ragent-lab/
├── TASKS/                    # 任务记录（开发过程）
│   ├── Action/               # Action 系列任务（Strategy Router、Smart Frontend、System Awareness）
│   └── Agent Loop 相关实验任务
├── SKILLS/                   # 技能文档（MCP、部署、协作等）
├── FEEDBACK/                 # 反馈报告（CC 执行结果）
│   └── DEBUG/                # 调试日志
├── COLLABORATION_PROTOCOL.md # 人机协作协议
├── MAP/                      # 认知地图
├── bootstrap/                # 核心业务代码
├── frontend/                 # React 前端
├── experiment/               # Agent Loop 实验代码
└── mcp-server/               # MCP 服务
```

---

## 核心增强功能

### 1. Agent Loop 模式

基于 Claude Code 源码学习的 Agentic RAG 实现：

- **自主检索决策**：模型判断何时检索、何时回答
- **多轮工具调用**：支持多次检索迭代
- **执行报告**：完整的执行过程记录

详见：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/`

### 2. Strategy + Router 机制

可插拔的问答处理策略架构：

| 策略 | 描述 | 适用场景 |
|------|------|---------|
| Pipeline | 固定流程 RAG | 简单直接的问题 |
| Agent | Agent Loop 模式 | 复杂推理、多轮检索 |

智能路由自动选择最佳策略，用户无需手动切换。

详见：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/strategy/`

### 3. 系统自省能力

Agent 可查询系统真实元信息：

- **system_info_query 工具**：获取知识库列表、意图树结构、系统能力
- **SYSTEM 意图策略转交**：Pipeline → Agent，返回真实数据而非编造

详见：`FEEDBACK/CC_Report_008_System_Awareness.md`

### 4. MCP 工具集成

支持自定义 MCP 工具：

- `RagentQAMCPExecutor`：问答工具
- 完整的 MCP 协议兼容性

详见：`SKILLS/MCP_ITERATION_SKILLS_V2.md`

---

## 开发记录

### Action 系列（Strategy Router 架构）

| 任务 | 状态 | 描述 |
|------|------|------|
| Task 001 | ✅ | Pipeline/Agent 入口调查 |
| Task 002 | ✅ | Agent Loop 合入 bootstrap |
| Task 003 | ✅ | 功能验证 |
| Task 004 | ✅ | 前端集成 |
| Task 005 | ✅ | 多 Agent 架构调研 |
| Task 006 | ✅ | Strategy + Router 实现 |
| Task 007 | ✅ | 前端智能路由接入 |
| Task 008 | ✅ | 系统自省工具 + SYSTEM 意图转交 |

### Agent Loop 实验

| 实验 | 状态 | 描述 |
|------|------|------|
| Experiment 0 | ✅ | Agent Loop 最小实现 |
| Experiment 1 | ✅ | 多工具编排 |
| Experiment 1.5 | ✅ | 百炼兼容性修复 |
| Experiment 2 | ✅ | 多工具检索 |
| Experiment 3 | ✅ | 跨知识库检索 |
| Experiment 4 | ✅ | Transcript & Microcompact |

---

## 技术栈

| 类型 | 技术 |
|------|------|
| 后端 | Java 17, Spring Boot 3.5, MyBatis Plus |
| 前端 | React 18, Vite, TypeScript |
| 向量数据库 | Milvus 2.6 |
| LLM | 百炼 API（阿里云） |
| MCP | 自研 MCP Server |

---

## 快速开始

```bash
# 克隆仓库
git clone git@github.com:ssmiter/ragent-lab.git
cd ragent-lab

# 查看开发记录
ls TASKS/
ls FEEDBACK/

# 启动后端
./mvnw.cmd spring-boot:run -pl bootstrap

# 启动前端
cd frontend && npm install && npm run dev
```

---

## 原项目来源

本项目基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 开发，感谢原作者的优秀架构设计。

原项目核心能力：
- 多路检索引擎
- 意图识别与引导
- 问题重写与拆分
- 模型路由与容错
- MCP 工具集成
- 文档入库流水线

本仓库在原项目基础上新增：
- Agent Loop 模式
- Strategy + Router 智能路由
- 系统自省工具
- 完整的开发记录文档

---

## License

Apache License 2.0（继承原项目）