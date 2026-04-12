# CC Task 009：Agent 模式知识库动态感知修复

> **目标：** 定位并修复 Agent 模式下知识库白名单硬编码问题，使 Agent 工具验证和 system prompt 能自动感知所有已配置的知识库，新增知识库时无需手动改代码
> **约束：** 最小化改动，复用现有的意图树/知识库配置数据源，不改 Pipeline 逻辑
> **产出：** `FEEDBACK/CC_Report_009_KnowledgeBase_Dynamic_Awareness.md`

---

## 背景

请先阅读 `COLLABORATION_PROTOCOL.md` 了解协作方式。可浏览 FEEDBACK 目录下的历史 Report 了解项目上下文。

### 问题现象

用户新增了知识库 `explorationdocs`（意图节点：Agent工程探索，domain=exploration），Pipeline 模式可以正常检索该知识库，但 Agent 模式调用 `knowledge_search_with_rerank` 工具时报错：

```
无效的知识库: explorationdocs。请选择：ragentdocs、ssddocs 或 dualssddocs
```

### 问题日志

详见项目根目录 `log/app1.log`，关键片段：

```
[Turn 1] system_info_query({"query_type": "knowledge_bases"}) -> ✓
[Turn 3] knowledge_search_with_rerank({"query": "CC源码探索进度和应用情况", "collection": "explorationdocs"}) -> ✗
  错误: 无效的知识库: explorationdocs。请选择：ragentdocs、ssddocs 或 dualssddocs
```

Agent 通过 system_info_query 拿到了知识库列表（可能包含 explorationdocs），但工具执行层的验证拒绝了它。

### 根因假设

Agent 模式的工具链中存在**硬编码的知识库白名单**（只有 ragentdocs、ssddocs、dualssddocs），新增的 explorationdocs 不在其中。Pipeline 不受影响是因为它通过意图路由树动态获取 collection 名称。

---

## 具体步骤

### 阶段一：定位（只读）

#### 1. 确认工具验证逻辑位置

找到 `knowledge_search_with_rerank` 工具的实现代码，定位以下内容：
- 这个工具的 Java 类在哪里
- 白名单（ragentdocs、ssddocs、dualssddocs）是在哪里定义的（硬编码在代码里？配置文件？数据库？）
- 验证 collection 参数的逻辑在哪一行

#### 2. 确认 system_info_query 的数据来源

找到 `system_info_query` 工具的实现：
- 它返回知识库列表时，数据从哪里读取的（数据库？配置？意图树？）
- 它返回的列表是否已包含 explorationdocs
- 如果已包含，说明 system_info_query 是动态的，但 knowledge_search 的验证不是——两者数据源不一致

#### 3. 确认 Agent system prompt 的组装逻辑

找到 Agent 模式的 system prompt 是在哪里组装的：
- prompt 中关于知识库的描述是硬编码的字符串，还是动态拼接的？
- 如果是硬编码的，内容是什么？是否只提到了三个旧知识库？

#### 4. 对比 Pipeline 的知识库解析

简要确认 Pipeline 模式如何获取 collection 名称（应该是通过意图树节点的 collection 字段），作为参考。

### 阶段二：修复

基于阶段一的发现，进行最小化修复。以下是预期的修复方向（以实际代码为准，如果发现更好的方式请采用）：

#### 修复点 1：工具验证动态化

将 `knowledge_search_with_rerank` 的 collection 白名单改为动态获取。优先方案：
- 复用 system_info_query 使用的同一个数据源（确保一致性）
- 或者从意图树配置中读取所有 collection
- 兜底：从 Milvus 直接查询已有的 collection 列表

#### 修复点 2：System Prompt 动态化

将 Agent 的 system prompt 中关于知识库的描述改为动态生成：
- 每次创建 Agent 会话时，从配置/数据库读取当前所有知识库信息
- 包含：collection 名称、描述、适用场景
- 这样新增知识库后，Agent 的 prompt 自动包含新库信息，不需要改代码

#### 修复点 3：工具描述动态化（如果适用）

检查 `knowledge_search_with_rerank` 工具注册时的描述（toolDescription）：
- 如果描述中列出了具体的 collection 名称作为参数说明，也需要动态化
- 这直接影响模型选择 collection 的准确性

### 阶段三：验证

修复完成后进行基本验证：
- 调用 Agent 模式，询问关于 explorationdocs 中内容的问题，确认能正常检索
- 确认原有三个知识库（ragentdocs、ssddocs、dualssddocs）不受影响
- 检查 system_info_query 返回的列表与工具验证的白名单是否一致

---

## 产出格式要求

```markdown
# CC Report 009：Agent 模式知识库动态感知

## 一、定位结果

### 1. 工具验证逻辑
- 文件路径：
- 白名单定义位置：（代码行 / 配置项 / 数据库表）
- 验证逻辑描述：

### 2. system_info_query 数据来源
- 文件路径：
- 数据来源：
- 是否已包含 explorationdocs：

### 3. System Prompt 组装
- 文件路径：
- 当前知识库描述内容：（截取关键部分）
- 是否硬编码：

### 4. Pipeline 对比
- collection 获取方式：

### 5. 根因确认
- 一句话总结根因

## 二、修复内容

### 变更文件清单
| 文件 | 变更类型 | 说明 |
|------|---------|------|

### 具体改动说明
（每个修复点的代码改动和理由）

## 三、验证结果

### explorationdocs 检索测试
- 测试方法：
- 结果：

### 回归测试
- 原有知识库是否正常：

## 四、设计说明

### 动态化方案
- 数据源选择：（用了哪个数据源，为什么）
- 一致性保障：（system_info_query、工具验证、prompt 三处是否使用同一数据源）

### 未来新增知识库的流程
- 新增知识库后，需要做什么（理想情况：只需在意图树配置中添加节点，Agent 模式自动感知）
- 还需要做什么（如果有残留的手动步骤）
```

## 注意事项

- 阶段一完成后请先记录发现，再开始修复。如果实际情况比预期复杂（比如白名单不是简单硬编码，而是有缓存机制），在 REPORT 中说明，我们可以讨论后再定修复方案
- 修复应确保 **单一数据源原则**：system_info_query、工具验证、prompt 描述三处的知识库信息应来自同一个源头，避免不一致
- 日志文件 `log/app1.log` 可以辅助确认问题复现路径
- 如果发现除了白名单之外还有其他导致问题的因素（比如 Milvus 中 collection 未创建），也请在 REPORT 中指出
