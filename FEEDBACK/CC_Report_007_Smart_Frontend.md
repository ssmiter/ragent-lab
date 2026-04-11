# CC Report 007：前端智能路由接入

## 1. 前端改动

### 1.1 选择方案

**方案 B 变体：默认智能路由 + 手动策略切换**

理由：
- 改动最小（只改端点和文案）
- 用户打开页面默认智能模式，无需操作
- 保留 Agent 按钮作为手动策略切换（调试/高级功能）

### 1.2 文件变更清单

| 文件路径 | 改动内容 |
|---------|---------|
| `chatStore.ts` | 端点改为 `/smart/chat`，Agent 开启时添加 `strategy=agent` 参数 |
| `ChatInput.tsx` | Agent 按钮添加 title 提示，文案改为"强制 Agent 策略" |
| `WelcomeScreen.tsx` | 同上调整 |

### 1.3 核心逻辑变化

**改动前：**
```typescript
const endpoint = agentModeEnabled ? "/agent/chat" : "/rag/v3/chat";
```

**改动后：**
```typescript
const endpoint = "/smart/chat";  // 默认智能路由
const query = buildQuery({
  strategy: agentModeEnabled ? "agent" : undefined,  // Agent 开启时强制指定
  ...
});
```

---

## 2. 验证结果

### 2.1 编译验证

- 后端 `bootstrap`：✅ 编译通过
- 前端 `frontend`：✅ 构建成功（5.67s）

### 2.2 部署验证（待操作者部署后确认）

预期测试项：

| 测试项 | 预期结果 |
|-------|---------|
| 简单问题 "Ragent 是什么" | Router 日志显示 `selectedStrategy=pipeline` |
| 复杂问题 "对比 Pipeline 和 Agent 的优缺点" | Router 日志显示 `selectedStrategy=agent` |
| Agent 按钮开启后发送问题 | Router 日志显示 `强制使用策略: agent` |
| SSE 流式返回 | 格式一致：meta → message → finish → done |
| 原端点 `/rag/v3/chat` 和 `/agent/chat` | 仍可独立访问 |

---

## 3. 验证结果（部署后实测）

### 3.1 路由决策日志分析

| 问题 | Router 决策 | 符合预期 |
|------|-------------|---------|
| "你能综合多个知识库简单介绍一下各个知识库的大致内容或主题吗" | pipeline | ✅ 问题相对简单直接 |
| "不错，我其实是想了解我系统中有哪些知识库..." | pipeline | ✅ 简单查询类问题 |
| "你好，我想了解RAG系统，我的科研项目，以及他们之间的联系" | agent | ✅ 多主题+关联分析类问题 |

### 3.2 Agent 策略执行过程（示例）

问题："RAG系统，我的科研项目，以及他们之间的联系"

```
Turn 1: 调用 knowledge_search_with_rerank 搜索 "RAG系统基本概念和架构"
Turn 2: 基于检索结果生成回答，主动追问用户科研项目细节
终止原因: COMPLETED，总轮次: 2，工具调用: 1 次
```

**关键观察**：
- Agent 模式会主动追问澄清（发现信息不足时）
- Rerank 分数 0.86 表示检索质量较高
- 完整执行报告被记录，便于调试

### 3.3 前端请求验证

浏览器 Network 面板确认请求路径：
```
http://101.42.96.96/api/ragent/smart/chat?question=xxx
```

✅ 智能路由端点生效

---

## 4. 部署踩坑记录

| 坑 | 原因 | 解决方案 |
|----|------|----------|
| 前端显示旧版本 | 浏览器缓存 | 强制刷新 Ctrl+Shift+R |
| 不确定智能模式是否生效 | 以为会有显式 UI 标识 | 智能模式是**默认行为**，无按钮；Agent 按钮变为"强制策略"功能 |
| URL 显示 `/chat` 而不是 `/smart/chat` | 混淆前端路由和后端 API | `/chat` 是 React 前端路由，`/api/ragent/smart/chat` 是后端 API |

---

## 5. 核心洞察：前端路由 vs 后端 API

**两套完全独立的路由系统**：

| 类型 | 示例 | 作用 |
|------|------|------|
| 前端路由 | `/chat`, `/settings` | React 页面切换，不触发后端请求 |
| 后端 API | `/api/ragent/smart/chat` | Spring Boot 接口，返回数据 |

**完整 API 路径组成**：
```
context-path (application.yaml) + Controller @RequestMapping + 方法 @GetMapping
= /api/ragent + /smart + /chat
= /api/ragent/smart/chat
```

用户访问 `http://101.42.96.96/` → 前端页面 → JS 自动请求后端 API

---

## 6. 遗留项

- **策略调优**：后续可根据实际使用数据调整规则决策的权重
- **LLM 决策升级**：Router 接口设计已支持，可无缝切换