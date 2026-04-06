# CC System Prompt 设计模式分析

## 结构概览

CC 的 system prompt 采用**模块化分段结构**，分为静态部分和动态部分：

```
System Prompt
├── 静态部分（可缓存，跨会话复用）
│   ├── Intro Section        — 角色定义、能力说明
│   ├── System Section       — 系统规则（权限、消息格式）
│   ├── Doing Tasks Section  — 执行任务的准则
│   ├── Actions Section      — 危险操作确认机制
│   ├── Using Tools Section  — 工具使用偏好
│   ├── Tone & Style Section — 输出风格
│   └── Output Efficiency    — 简洁原则
│
├── [BOUNDARY MARKER] — 静态/动态分界线
│
└── 动态部分（每轮可能变化）
    ├── Session Guidance   — 会话特定指导（Agent 工具、Skill 等）
    ├── Memory             — 用户记忆（CLAUDE.md 等）
    ├── Env Info           — 环境信息（CWD、平台、模型）
    ├── Language           — 语言偏好
    ├── MCP Instructions   — MCP 服务器指令
    └── Tool Results       — 工具结果处理提示
```

## 工具引导方式

### 1. System Prompt 中的工具引导

**在 `Using your tools` 段落中**，告诉模型偏好哪些工具：

```
# Using your tools
 - Do NOT use the Bash tool to run commands when a relevant dedicated tool is provided.
   - To read files use Read instead of cat, head, tail, or sed
   - To edit files use Edit instead of sed or awk
   - To search for files use Glob instead of find or ls
   - To search the content of files, use Grep instead of grep or rg
 - Break down and manage your work with the TodoWrite tool...
 - You can call multiple tools in a single response...
```

### 2. 工具的 description 字段

每个工具的 `description()` 方法返回**简洁的能力描述**：

```typescript
// GlobTool
export const DESCRIPTION = `
- Fast file pattern matching tool that works with any codebase size
- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths sorted by modification time
- Use this tool when you need to find files by name patterns
- When you are doing an open ended search that may require multiple rounds
  of globbing and grepping, use the Agent tool instead
`
```

### 3. 工具的 prompt 方法

复杂工具有专门的 `prompt()` 方法，返回**详细的使用指南**：

```typescript
// BashTool 的 prompt 包含：
// - 沙箱配置
// - Git 操作指南（commit、PR 创建流程）
// - 并行命令执行规则
// - 超时和后台运行
```

### 关键洞察

**工具定义和 System Prompt 是互补的**：

| 层级 | 位置 | 内容 |
|-----|------|------|
| 工具发现 | API `tools` 参数 | name + description + input_schema |
| 工具偏好 | System Prompt | 告诉模型"用什么工具代替什么命令" |
| 工具详解 | 工具的 `prompt()` 方法 | 复杂工作流、安全协议、边界情况 |

## 行为准则设计模式

### 1. 决策框架

```
If you encounter an obstacle, do not use destructive actions as a shortcut...
If you notice the user's request is based on a misconception, say so...
If an approach fails, diagnose why before switching tactics...
```

### 2. 错误处理

```
If the user denies a tool you call, do not re-attempt the exact same tool call.
Instead, think about why the user has denied the tool call and adjust your approach.
```

### 3. 安全检查

```
Be careful not to introduce security vulnerabilities such as command injection,
XSS, SQL injection, and other OWASP top 10 vulnerabilities.
```

### 4. 质量控制（Ant-only）

```
Before reporting a task complete, verify it actually works: run the test,
execute the script, check the output.
```

## 关键片段

### 角色定义

```
You are an interactive agent that helps users with software engineering tasks.
Use the instructions below and the tools available to you to assist the user.
```

### 环境信息

```
# Environment
 - Primary working directory: /path/to/project
 - Is a git repository: Yes
 - Platform: darwin
 - Shell: zsh
 - OS Version: Darwin 24.3.0
```

### 行动准则

```
# Executing actions with care
Carefully consider the reversibility and blast radius of actions.
Generally you can freely take local, reversible actions like editing files
or running tests. But for actions that are hard to reverse, affect shared
systems beyond your local environment, or could otherwise be risky or
destructive, check with the user before proceeding.
```

## 对 ragent Agent Loop 的启示

### 1. System Prompt 应包含

```markdown
# 角色定义
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 可用工具
你有以下工具可用：
- knowledge_search: 在知识库中搜索相关信息
- generate_answer: 基于搜索结果生成最终回答（可选）

# 行为指导
1. 收到用户问题后，先使用 knowledge_search 搜索知识库
2. 如果搜索结果分数高（top1 > 0.7），使用搜索结果回答
3. 如果搜索结果分数低，尝试换个查询重新搜索（最多重试 2 次）
4. 如果多次搜索都找不到相关信息，诚实告知用户
5. 回答时引用来源文档名

# 语言
始终使用中文回答用户问题。
```

### 2. 工具 Description 设计

```typescript
// 简洁明了，告诉模型"这个工具做什么，什么时候用"
const KNOWLEDGE_SEARCH_DESCRIPTION = `
- 在知识库中搜索相关信息
- 返回按相关性排序的文本片段和分数
- 当需要查找信息来回答用户问题时使用此工具
- 如果初次搜索结果不满意，可以用不同的查询多次调用
`;
```

### 3. 工具 Prompt（可选）

如果工具使用复杂，可以添加更详细的 `prompt()` 方法：

```typescript
async prompt() {
  return `
# knowledge_search 使用指南

## 参数说明
- query: 搜索查询，使用与主题相关的关键词
- collection: 知识库集合名称（可选）

## 分数解读
- 分数 > 0.8: 高度相关，可以直接引用
- 分数 0.6-0.8: 中等相关，可以参考
- 分数 < 0.6: 低相关，建议换查询重试

## 最佳实践
1. 使用具体的关键词而非泛泛的问题
2. 如果第一次搜索结果不理想，尝试：
   - 使用同义词
   - 拆分子问题
   - 使用文档中的专业术语
`;
}
```

### 4. 最小可行 System Prompt

```markdown
你是一个知识库助手。使用 knowledge_search 工具在知识库中搜索信息来回答用户问题。

行为准则：
- 先搜索再回答
- 搜索分数低时可以重试（最多2次）
- 找不到信息时诚实告知
- 用中文回答
- 引用来源
```

这就是 Agent Loop 需要的全部"引导"——剩下的由模型自己决定。