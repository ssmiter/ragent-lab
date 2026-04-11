# Ragent-Lab

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![Milvus](https://img.shields.io/badge/Milvus-2.6.x-00b3ff.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)

> 基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 的增强版本，新增 Agent Loop 模式、智能路由策略、系统自省能力。

---

## 原项目来源

本项目基于 **nageoffer/ragent** 开发，感谢原作者的优秀架构设计。

**原项目地址**：[https://github.com/nageoffer/ragent](https://github.com/nageoffer/ragent)

原项目是一个企业级 RAG 智能体平台，包含：
- 多路检索引擎（意图定向 + 全局向量检索）
- 意图识别与歧义引导
- 问题重写与拆分
- 会话记忆管理（滑动窗口 + 自动摘要）
- 模型路由与容错（多候选、首包探测、自动降级）
- MCP 工具集成
- 文档入库流水线
- 全链路追踪
- 完整的 React 管理后台

更多详情请访问原项目仓库或官方文档：[https://nageoffer.com/ragent](https://nageoffer.com/ragent)

---

## 本仓库增强功能

### 1. Agent Loop 模式 🔄

基于 Claude Code 源码学习的 Agentic RAG 实现：

```
用户问题 → Agent 自主判断 → 调用工具检索 → 分析结果 → 决定继续检索或回答 → 返回
```

**核心能力**：
- 模型自主决定何时检索、何时回答
- 支持多轮工具调用迭代
- 完整的执行报告（turnCount、toolCalls、toolHistory）
- 与 Pipeline 模式无缝切换

**端点**：`/api/ragent/agent/chat`

### 2. Strategy + Router 智能路由 🧭

可插拔的问答处理策略架构：

| 策略 | 描述 | 适用场景 |
|------|------|---------|
| **Pipeline** | 固定流程 RAG | 简单问题、知识点查询 |
| **Agent** | Agent Loop 模式 | 复杂推理、多维度对比 |

**智能路由端点**：`/api/ragent/smart/chat`

用户无需手动选择，Router 自动决策：
- 规则判断：问题长度、关键词、复杂度
- 可扩展：后续可升级为 LLM 决策

### 3. 系统自省能力 🔍

Agent 可查询系统真实元信息，不再编造答案：

**system_info_query 工具**：
- 查询知识库列表（名称、ID、向量集合）
- 查询意图树结构（领域分类）
- 查询系统能力说明

**SYSTEM 意图策略转交**：
- 用户问"系统有哪些知识库" → Pipeline 检测 SYSTEM 意图 → 转交 Agent → 调用工具 → 返回真实数据

### 4. MCP 工具增强 🔧

新增自定义 MCP 工具：
- `RagentQAMCPExecutor`：问答工具，支持知识库检索

---

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- MySQL 8.0+
- Milvus 2.6+
- Redis 6.0+

### 启动后端

```bash
# 克隆仓库
git clone git@github.com:ssmiter/ragent-lab.git
cd ragent-lab

# 配置 application.yml（数据库、Milvus、百炼 API Key）
# 启动
./mvnw.cmd spring-boot:run -pl bootstrap
```

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

### 访问

- 前端界面：`http://localhost:5173`
- 智能路由端点：`http://localhost:9090/api/ragent/smart/chat`
- Agent 端点：`http://localhost:9090/api/ragent/agent/chat`
- Pipeline 端点：`http://localhost:9090/api/ragent/rag/v3/chat`

---

## 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│                      SmartChatController                     │
│                    /smart/chat (统一入口)                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      StrategyRouter                          │
│              (规则决策：Pipeline vs Agent)                   │
└─────────────────────────────────────────────────────────────┘
              │                               │
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────────┐
│     PipelineStrategy    │     │       AgentStrategy         │
│   (固定流程 RAG)        │     │     (Agent Loop 模式)       │
│                         │     │                             │
│  查询改写 → 意图识别    │     │  自主判断 → 工具调用 → 回答 │
│  → 检索 → Rerank → 生成 │     │                             │
│                         │     │  Tools:                     │
│  SYSTEM意图 → 转交Agent │     │  - knowledge_search         │
│                         │     │  - system_info_query        │
└─────────────────────────┘     └─────────────────────────────┘
```

---

## 分支说明

| 分支 | 内容 |
|------|------|
| **main** | 稳定版本（核心代码，不含开发记录） |
| **dev** | 开发版本（包含 TASKS、SKILLS、FEEDBACK） |

---

## 技术栈

| 类型 | 技术 |
|------|------|
| 后端框架 | Java 17, Spring Boot 3.5, MyBatis Plus |
| 前端框架 | React 18, Vite, TypeScript |
| 向量数据库 | Milvus 2.6 |
| 关系数据库 | MySQL 8.0 |
| 缓存 | Redis + Redisson |
| LLM | 百炼 API（阿里云） |
| MCP | 自研 MCP Server |

---

## 开发记录

完整的开发过程记录位于 `dev` 分支：

- `TASKS/` - 任务指令文档
- `FEEDBACK/` - 执行反馈报告
- `SKILLS/` - 技能文档（MCP、部署等）

详见 [README_DEV.md](README_DEV.md)

---

## 致谢

感谢原项目 [nageoffer/ragent](https://github.com/nageoffer/ragent) 提供的优秀基础架构。

---

## License

Apache License 2.0（继承原项目）