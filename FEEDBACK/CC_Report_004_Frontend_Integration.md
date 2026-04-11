# CC Report 004：前端集成

## 1. 前端调查结果

- **技术栈**：React 18 + TypeScript + Vite + Radix UI + Zustand
- **聊天组件结构**：
  - `ChatPage.tsx` → `MessageList` + `ChatInput`
  - `chatStore.ts` (Zustand) 管理消息发送和 SSE 接收
  - `useStreamResponse.ts` hook 处理 SSE 解析
- **SSE 实现方式**：fetch + ReadableStream，支持以下事件类型：
  - `event: meta` → `{conversationId, taskId}`
  - `event: message` → `{type, delta}` (type: "response" 或 "think")
  - `event: finish` → `{messageId, title}`
  - `event: done` → `[DONE]`
  - `event: error` → 错误信息
- **SSE 格式兼容性**：Agent 端点原格式 `event: content` + 纯文本，与前端期望不兼容
- **现有 UI 中可复用的机制**：
  - "深度思考"开关按钮（样式、交互模式可复用）
  - Zustand 状态管理模式可复用

## 2. 实施方案选择

- **选择了方案 A**（在发送区域加切换开关）
- **原因**：前端已有"深度思考"按钮作为参考模式，实现成本最低
- **与预期方案的差异**：
  - Agent 模式按钮使用琥珀色（amber）主题，与深度思考的蓝色区分
  - Agent 模式开启时自动禁用深度思考（互斥）
  - 后端 SSE 格式需要调整以匹配前端期望

## 3. 具体改动

### 后端改动

| 文件 | 变更说明 |
|------|---------|
| `AgentChatController.java` | 修复路径：`@GetMapping("/agent/chat")` → `@GetMapping("/chat")`，实际路径从 `/agent/agent/chat` 变为 `/agent/chat` |
| `AgentLoopService.java` | 调整 SSE 格式，使用与 Pipeline 一致的格式：发送 `meta`/`message`/`finish`/`done` 事件，数据使用 JSON 格式 |
| `SSEEventType.java` | 添加 `ERROR("error")` 枚举值 |

### 前端改动

| 文件 | 变更说明 |
|------|---------|
| `chatStore.ts` | 添加 `agentModeEnabled` 状态和 `setAgentModeEnabled` 方法；sendMessage 根据 agentModeEnabled 选择不同端点 |
| `ChatInput.tsx` | 添加 Agent 模式切换按钮（Sparkles 图标，琥珀色主题）；Agent 模式开启时禁用深度思考按钮 |
| `WelcomeScreen.tsx` | 同步添加 Agent 模式切换按钮（欢迎页输入框）；与 ChatInput 保持一致的交互逻辑 |

> **注意**：`WelcomeScreen.tsx` 是欢迎页组件，用户首次进入时看到的是这个组件而非 `ChatInput.tsx`。需要在两个组件中同步添加 Agent 按钮，否则用户在欢迎页无法切换模式。

## 4. SSE 格式处理

**调整后端 Agent SSE 格式**，使其与 Pipeline 一致：

| 事件 | 原格式 | 新格式 |
|------|--------|--------|
| meta | 无 | `event: meta`, data: `{conversationId, taskId}` |
| content | `event: content`, data: 纯文本 | `event: message`, data: `{type: "response", delta: "文本"}` |
| done | `event: done`, data: 空 | `event: finish` + `event: done`, data: `{messageId, title}` + `[DONE]` |
| error | `event: error`, data: 纯文本 | 保持 `event: error` |

## 5. 路径修复

- **修复前路径**：`/agent/agent/chat`（双重 agent）
- **修复后路径**：`/agent/chat`
- **前端调用**：`agentModeEnabled ? "/agent/chat" : "/rag/v3/chat"`

## 6. 验证结果

由于本地环境缺少编译工具（mvn/npm lint），未能进行编译验证。建议部署后测试：

1. **Pipeline 模式是否正常**：关闭 Agent 模式，发送问题，检查 SSE 流式显示
2. **Agent 模式是否正常**：开启 Agent 模式，发送问题，检查 SSE 流式显示
3. **模式切换 UI 效果**：
   - Agent 按钮（琥珀色）点击后高亮 + 动态圆点
   - Agent 开启时深度思考按钮变灰（禁用）
   - 提示信息相应切换

## 7. 遗留问题和建议

1. **Agent 模式暂不支持对话历史持久化**：`conversationId` 参数传递了，但 `messageId` 返回可能需要调整
2. **Agent 模式不支持取消**：Pipeline 有 `/rag/v3/stop` 端点，Agent 模式目前缺少取消机制
3. **中间过程不展示**：MVP 阶段用户只看到最终回答，后续可扩展添加 `event: thinking`/`event: tool_call` 事件
4. **建议编译验证**：请在本地或部署环境运行 `mvn compile` 和前端构建，确保无语法错误

## 8. 踩坑记录

| 坑 | 原因 | 解决方案 |
|----|------|----------|
| 欢迎页看不到 Agent 按钮 | `WelcomeScreen.tsx` 是独立的欢迎页组件，有自己的输入框，未同步添加 Agent 按钮 | 在 `WelcomeScreen.tsx` 中同步添加 Agent 模式按钮，与 `ChatInput.tsx` 保持一致 |
| 模式按钮闪烁后消失 | 消息发送后从欢迎页切换到聊天页，状态从 WelcomeScreen → ChatInput，两个组件状态共享（Zustand），但 UI 需分别实现 | 确保 WelcomeScreen 和 ChatInput 都有模式切换按钮 |