# CC Report 006：Strategy + Router 实现

## 1. 调查发现

### 1.1 Pipeline 和 AgentLoop 的入口/接口对比

| 特性 | Pipeline | Agent |
|------|----------|-------|
| 端点 | `/rag/v3/chat` | `/agent/chat` |
| 参数 | question, conversationId, deepThinking | question, conversationId |
| 服务层 | RAGChatService.streamChat() | AgentLoopService.runAgent() |
| 流程 | 记忆→改写→意图→检索→生成 | 加载历史→Agent Loop→流式返回 |
| SSE 格式 | META→MESSAGE→FINISH→DONE | META→MESSAGE→FINISH→DONE |

### 1.2 统一包装的可行性评估

**结论：可以统一包装**

两种模式的 SSE 输出格式完全一致：
- `meta`: `{conversationId, taskId}`
- `message`: `{type, delta}`
- `finish`: `{messageId, title}`
- `done`: `[DONE]`

差异点：
1. Pipeline 有 `deepThinking` 参数，Agent 无此参数（Strategy 层可忽略）
2. Agent 需要 `userId` 参数（从 UserContext 获取）

### 1.3 前端模式区分方式

前端通过 `agentModeEnabled` 状态切换端点：
- `agentModeEnabled=false` → `/rag/v3/chat`
- `agentModeEnabled=true` → `/agent/chat`

SSE 处理逻辑对两种模式透明（相同的 handlers）。

---

## 2. 设计方案

### 2.1 Strategy 接口定义

```java
public interface ChatStrategy {
    String getName();          // 策略名称（唯一标识）
    String getDescription();   // 策略描述（用于路由决策）
    void execute(ChatRequest request, SseEmitter emitter); // 执行入口
    int getPriority();         // 优先级（默认 100）
    boolean isEnabled();       // 是否启用（默认 true）
}
```

**设计要点：**
- 每个 Strategy 只需声明 name、description、execute，Router 自动发现和调度
- Spring Bean 自动注册（实现接口 + `@Component` 自动被 Router 发现）
- 优先级用于多策略匹配时的选择排序

### 2.2 Router 机制设计

**路由决策方式：规则决策（MVP 阶段）**

选择规则决策的原因：
1. 无额外 LLM 调用开销，响应速度快
2. 规则可调优，决策逻辑透明可控
3. 后续可无缝切换为 LLM 决策（接口设计已支持）

**规则决策算法：**

```
计算 agentScore 和 pipelineScore：
- Agent 关键词匹配（对比、分析、架构、步骤...）→ +2 分/关键词
- Pipeline 关键词匹配（是什么、如何配置、示例...）→ +2 分/关键词
- 问题长度 > 50 字 → agentScore +1
- 问题长度 < 20 字 → pipelineScore +2
- 包含多个子问题（逗号/问号分隔）→ agentScore +1

决策：
- agentScore > pipelineScore → 选择 Agent
- pipelineScore > 0 → 选择 Pipeline
- 默认：长度 > 50 字 → Agent，否则 Pipeline
```

### 2.3 统一入口设计

新增端点：`/smart/chat`

**特点：**
- Router 自动选择策略，用户无需手动选择
- 支持强制指定策略（`?strategy=pipeline` 或 `?strategy=agent`）
- SSE 输出格式与原有端点完全一致

**与原有端点的关系：**
- `/rag/v3/chat` - Pipeline 模式（保留）
- `/agent/chat` - Agent 模式（保留）
- `/smart/chat` - 智能路由模式（新增）

---

## 3. 实现细节

### 3.1 文件变更清单

| 文件路径 | 类型 | 说明 |
|---------|------|------|
| `strategy/ChatStrategy.java` | 新增 | Strategy 接口定义 |
| `strategy/ChatRequest.java` | 新增 | 统一请求封装 |
| `strategy/PipelineStrategy.java` | 新增 | Pipeline 包装 |
| `strategy/AgentStrategy.java` | 新增 | Agent 包装 |
| `strategy/StrategyRouter.java` | 新增 | 路由决策逻辑 |
| `strategy/SmartChatController.java` | 新增 | 统一入口控制器 |

### 3.2 关键实现决策和理由

| 决策 | 理由 |
|------|------|
| Strategy 不改现有 Service | 最小化改动，只是外层包装 |
| Router 使用规则决策而非 LLM | MVP 最简，无额外开销，可后续升级 |
| 保留原有端点 | 不破坏现有功能，渐进式演进 |
| 新端点 `/smart/chat` | 路径清晰，与原有端点区分 |
| 支持 `forceStrategy` 参数 | 方便调试和用户手动指定 |

---

## 4. 验证结果

### 4.1 编译验证

```bash
./mvnw.cmd compile -pl bootstrap -am -q
```

**结果：✅ 编译通过**

### 4.2 路由准确性测试（模拟）

| 问题 | 预期策略 | 规则判断 |
|------|----------|----------|
| "Ragent 是什么" | Pipeline | 包含"是什么" → Pipeline |
| "对比 Pipeline 和 Agent 的优缺点" | Agent | 包含"对比"、"优缺点" → Agent |
| "系统的整体架构是什么" | Agent | 包含"整体"、"架构" → Agent |
| "如何配置 Milvus" | Pipeline | 包含"如何配置" → Pipeline |
| "根据文档推导最佳实践" | Agent | 包含"推导"、"最佳实践" → Agent |

### 4.3 SSE 兼容性评估

**结论：完全兼容**

Strategy 的 execute 方法直接调用现有 Service，SSE 输出格式不变：
- PipelineStrategy → RAGChatService.streamChat()
- AgentStrategy → AgentLoopService.runAgent()

### 4.4 回归测试（原端点）

**结果：✅ 原端点保留**

- `/rag/v3/chat` - RAGChatController 未修改
- `/agent/chat` - AgentChatController 未修改

---

## 5. 扩展性评估

### 5.1 如果要加第三个 Strategy（比如"多知识库路由"）

需要做的事：
1. 创建 `MultiKBStrategy.java` 实现 `ChatStrategy` 接口
2. 添加 `@Component` 注解
3. 编写 name、description、execute 实现

**预估改动量：1 个新文件，约 50 行代码**

无需修改：
- Router（自动发现新 Strategy）
- Controller（无需修改）
- 前端（可选择使用 `/smart/chat` 或继续使用原有端点）

### 5.2 升级为 LLM 决策的改动

需要做的事：
1. 在 `StrategyRouter` 中添加 `llmDecide()` 方法
2. 构造 prompt：将各 Strategy 的 description 作为候选
3. 调用 LLM 返回选中的策略名称

**预估改动量：约 30 行代码修改**

接口设计已支持：
- `getStrategyInfos()` 方法可获取所有策略描述
- 现有 AIModelProperties 配置可直接使用

---

## 6. 部署注意事项

### 6.1 Nginx 配置

需要添加 `/api/ragent/smart/chat` 的 SSE 代理规则：

```nginx
location /api/ragent/smart/chat {
    proxy_pass http://127.0.0.1:9090;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    chunked_transfer_encoding off;
}
```

### 6.2 前端集成（可选）

前端可以选择：
1. **不改前端**：用户继续手动切换 Pipeline/Agent 模式
2. **新增选项**：添加"智能模式"选项，调用 `/smart/chat`
3. **完全切换**：将默认模式改为智能路由

---

## 7. 总结

**核心成果：**
- 实现了可插拔的 Strategy + Router 机制
- 新增处理模式的成本趋近于零（只需实现接口 + 注册）
- 保留了所有现有功能（无破坏性改动）
- MVP 阶段使用规则决策，可无缝升级为 LLM 决策

**文件结构：**
```
bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/strategy/
├── ChatStrategy.java          # Strategy 接口
├── ChatRequest.java           # 统一请求封装
├── PipelineStrategy.java      # Pipeline 包装
├── AgentStrategy.java         # Agent 包装
├── StrategyRouter.java        # 路由决策逻辑
└── SmartChatController.java   # 统一入口控制器
```

**待后续优化：**
1. 实际部署后的功能验证测试
2. 规则决策算法的调优（基于实际使用数据）
3. 可选升级为 LLM 决策