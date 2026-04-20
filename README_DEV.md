# Ragent-Lab (Development Branch)

> 企业级 RAG 智能问答平台。在 Pipeline 固定流程基础上引入 Agent Loop 模式，按问题复杂度智能调度——简单问题保持低延迟，复杂问题获得多轮检索深度。

本分支包含完整的开发记录、技能文档和反馈报告。

---

## 目录结构

```
ragent-lab/
├── TASKS/                    # 任务指令（规划者产出）
├── FEEDBACK/                 # 反馈报告（执行者产出）
├── SKILLS/                   # 技能文档
├── COLLABORATION_PROTOCOL.md # 人机协作协议
├── bootstrap/                # 核心业务代码
├── frontend/                 # React 前端
└── mcp-server/               # MCP 服务
```

---

## 核心特性

### Agent Loop 多轮检索

模型自主驱动检索循环：判断何时需要检索、换关键词重试、切换知识库，直到获得足够信息才回答。

详见：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/agentloop/`

### Pipeline 与 Agent 智能协同

两种问答模式自动切换，各司其职。Router 按问题特征规则路由，意图分类识别复杂问题时自动移交 Agent。

详见：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/strategy/`

### 引用溯源可信度

每条回答附带原始文档来源与相关度评分，用户可追溯答案出处。

详见：`FEEDBACK/Action/` 相关报告

---

## 可扩展架构

| 扩展点 | 接口 | 当前实现 |
|--------|------|---------|
| 策略层 | `ChatStrategy` | Pipeline / Agent |
| 工具层 | `Tool` | knowledge_search / system_info_query |

新增处理模式或场景工具只需实现对应接口并注册，无需侵入核心流程。

---

## 开发过程记录

### 协作模式

本项目采用 ReAct 式人机协作：
- **规划者**：需求分析、任务分解
- **执行者**：代码实现、结果反馈

所有任务通过 TASK/FEEDBACK 文件传递。详见 [COLLABORATION_PROTOCOL.md](COLLABORATION_PROTOCOL.md)

### 里程碑

| Task | 内容 | 状态 |
|------|------|------|
| 001 | 调研 bootstrap 结构 | ✅ |
| 002 | Agent Loop 合入 | ✅ |
| 003 | 功能验证 | ✅ |
| 004 | 前端集成 | ✅ |
| 005 | 多 Agent 架构调研 | ✅ |
| 006 | Strategy + Router | ✅ |
| 007 | 前端智能路由 | ✅ |
| 008 | 系统自省 + SYSTEM转交 | ✅ |
| 009-012 | 路由增强与持久化修复 | ✅ |

---

## 技术栈

Spring Boot 3.5 / React 18 / Milvus 2.6 / MySQL 8.0 / Redis / 百炼 API（Function Calling） / SSE

---

## 快速开始

```bash
git clone git@github.com:ssmiter/ragent-lab.git
cd ragent-lab

# 启动后端
./mvnw.cmd spring-boot:run -pl bootstrap

# 启动前端
cd frontend && npm install && npm run dev
```

---

## 致谢

基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 开发。

---

## License

Apache License 2.0