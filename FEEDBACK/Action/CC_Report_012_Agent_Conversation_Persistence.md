# CC Report 012：Agent 模式对话持久化

## 一、调查结果

### Pipeline 持久化机制

**存储位置：**
| 表名 | 字段 | 用途 |
|------|------|------|
| `t_conversation` | `conversationId`, `userId`, `title`, `lastTime` | 会话元信息 |
| `t_message` | `conversationId`, `userId`, `role`, `content` | 消息内容 |

**写入时机和内容：**
1. **用户消息保存时机**：`RAGChatServiceImpl.streamChat()` 第 94 行
   - 调用 `memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question))`
   - 逻辑：先加载历史，再追加用户消息
   
2. **Assistant 消息保存时机**：`StreamChatEventHandler.onComplete()` 第 174-175 行
   - 调用 `memoryService.append(conversationId, userId, ChatMessage.assistant(finalAnswer))`

3. **Conversation 创建时机**：`MySQLConversationMemoryStore.append()` 第 83-90 行
   - 当追加 USER 消息时，自动调用 `conversationService.createOrUpdate()`
   - 会生成对话标题（通过 LLM）

**读取/恢复接口：**
| 接口 | 返回内容 |
|------|---------|
| `/conversations` | 会话列表（conversationId, title, lastTime） |
| `/conversations/{conversationId}/messages` | 消息列表（role, content, createTime） |

**数据模型：**
- 一个 `Conversation` 包含多个 `Message`
- `Message` 的 role 字段区分 `user` 和 `assistant`

---

### Agent 当前状态（修复前）

**是否有保存逻辑：**
| 保存内容 | 是否有 | 代码位置 |
|---------|-------|---------|
| 用户问题 | ❌ 无 | — |
| Assistant 回复 | ✅ 有 | `AgentLoopService.java` 第 186-195 行 |

**conversationId 管理方式：**
- 前端传入（与 Pipeline 一致）
- 后端不生成

**问题分析：**
```
Agent 模式流程（修复前）：
  1. memoryService.load() → 加载历史
  2. AgentLoop.run() → 执行
  3. memoryService.append(assistant) → 保存回复
  
缺失环节：用户问题未保存
  
后果：
  - t_message 缺少用户消息
  - t_conversation 未创建（因为 MySQLConversationMemoryStore.append() 只在 USER 消息时创建）
  - 前端历史对话列表看不到 Agent 模式的对话
```

---

### Agent 执行过程数据（后续参考）

**当前处理方式：**
- 执行报告在内存中生成后直接 SSE 推送，无持久化
- `AgentLoopResult.toolCallHistory` 存储工具调用历史，但仅在日志中打印

**可扩展的持久化点：**
| 数据 | 当前状态 | 可扩展方案 |
|------|---------|-----------|
| tool_call_history | 仅日志打印 | 保存到 message 的 metadata 字段 |
| 执行报告 | SSE 推送后丢失 | transcript 文件持久化 |
| 检索结果 | 内存中临时 | 可参考 Experiment 4 的 compact 策略 |

---

### 实验中的相关发现

**相关实验：** `experiment/results/experiment4/`

**核心发现：**
1. **Transcript 持久化**：`TranscriptWriter.java` 将 Agent Loop 完整对话写入 JSONL 文件
2. **Microcompact 策略**：保留"搜过什么、从哪个库、得分多少"，丢弃 chunk 全文
3. **SessionMemory**：跨会话的知识沉淀，为未来对话提供快速索引

**可借鉴的设计思路：**
- Agent 执行过程的持久化可采用 transcript 格式
- metadata 字段可存储 tool_call_history 的摘要（类似 compact 策略）
- SessionMemory 思路可用于"长期记忆"功能

---

## 二、方案选择

**选择了方向 A（直接复用 Pipeline 的保存逻辑）**

**原因：**
1. `ConversationMemoryService` 是通用服务，Agent 可直接调用
2. 数据模型完全兼容：`user` + `assistant` 消息结构一致
3. 最小改动：只需添加一行 `memoryService.append()` 保存用户问题

**与 Pipeline 的复用程度：**
| 组件 | 复用情况 |
|------|---------|
| ConversationMemoryService | 100% 复用 |
| MySQLConversationMemoryStore | 100% 复用 |
| ConversationService | 100% 复用（自动触发） |
| 数据表结构 | 100% 兼容 |

**为 Agent 特有数据预留的扩展点：**
- `t_message` 表无 metadata 字段，如需保存 tool_call_history，可新增字段或新建表
- 当前方案仅保存"用户问题 + 最终回答"，满足基本需求
- Agent 执行过程的完整持久化是后续任务

---

## 三、变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `AgentLoopService.java` | 增强 | 添加用户问题保存逻辑，修正流程顺序 |

**具体变更：**

1. **流程顺序调整**（第 131-151 行）：
   - 原流程：加载历史 → 执行 → 保存回复
   - 新流程：加载历史 → **保存用户问题** → 执行 → 保存回复

2. **新增代码**：
```java
// 4. 保存用户问题到对话历史
//    同时会创建/更新 Conversation 记录（生成标题）
if (StrUtil.isNotBlank(conversationId) && StrUtil.isNotBlank(userId)) {
    try {
        memoryService.append(conversationId, userId, ChatMessage.user(question));
        log.debug("保存用户问题到对话历史");
    } catch (Exception e) {
        log.warn("保存用户问题失败: {}", e.getMessage());
    }
}
```

3. **文档注释更新**：增加对话持久化说明

---

## 四、验证结果

### 编译验证
✅ 编译成功：`./mvnw.cmd compile -pl bootstrap -q`

### 功能验证（待用户执行）

**验证步骤：**

1. **保存验证**：
   - Agent 模式下提一个问题
   - 查询数据库：
     ```sql
     SELECT * FROM t_message WHERE conversationId = '<conversationId>' ORDER BY createTime;
     SELECT * FROM t_conversation WHERE conversationId = '<conversationId>';
     ```
   - 预期：t_message 有 user + assistant 两条记录，t_conversation 有标题

2. **恢复验证**：
   - 刷新前端页面或重新进入
   - 预期：历史对话列表显示 Agent 模式的对话（有标题）

3. **继续对话**：
   - 在恢复的对话基础上继续提问
   - 预期：历史消息加载正常，新对话正常保存

4. **Pipeline 回归**：
   - Pipeline 模式正常工作
   - 预期：无任何影响（改动仅在 AgentLoopService）

---

## 五、后续方向

### Agent 执行过程持久化的可行路径

| 方案 | 描述 | 优先级 |
|------|------|--------|
| metadata 字段扩展 | 在 t_message 新增 metadata 字段存储 tool_call_history | 中 |
| transcript 文件持久化 | 参考 Experiment 4，写入 JSONL 文件 | 低 |
| 新建 agent_execution 表 | 专门存储 Agent 执行过程 | 低 |

**建议**：metadata 字段扩展最简单，可优先考虑。

### 与上下文记忆/压缩的关联

- Experiment 4 的 Microcompact 策略可应用于 Agent 模式的上下文管理
- SessionMemory 思路可用于跨会话的长期记忆
- 当前方案为后续记忆功能奠定了基础（对话能保存）

### 数据模型后续可能的扩展

| 扩展项 | 用途 |
|--------|------|
| `t_message.metadata` | 存储 tool_call_history、检索来源等 |
| `t_conversation.mode` | 标识对话模式（pipeline/agent） |
| `t_agent_transcript` | 存储完整的 Agent 执行 transcript |

---

*报告完成于 2026-04-13*