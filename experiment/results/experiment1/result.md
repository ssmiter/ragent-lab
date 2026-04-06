# Agent Loop 实验 1 运行结果

## 运行时间
2026-04-05

## 实验配置
- 模型: qwen-plus-latest (百炼)
- 工具: knowledge_search
- 最大轮次: 10
- System Prompt: 已配置（知识库助手行为指导）

## 运行结果

```
终止原因: COMPLETED
总轮次: 3
工具调用次数: 2

工具调用历史:
  [Turn 1] knowledge_search({"query": "Ragent系统 整体架构"}) -> ✓
  [Turn 2] knowledge_search({"query": "Ragent 架构图 分层架构 系统设计"}) -> ✓
```

## 模型行为分析

### Turn 1
- 模型正确调用 `knowledge_search`
- 返回 5 个结果，最高分 ~0.64
- 分数低于 System Prompt 中定义的 0.8 阈值

### Turn 2
- 模型决定换一个更具体的查询重试
- 使用更专业关键词："架构图 分层架构 系统设计"
- 返回结果仍不理想

### Turn 3
- 模型基于两次搜索结果生成最终回答
- 诚实告知分数低于 0.7，无法提供完整架构说明
- 给出建议：查阅 GitHub、视频课程、技术博客

## 关键发现

### ✅ 成功验证的功能

1. **Agent Loop 骨架**
   - `while(true)` 循环正常工作
   - 模型不调用工具时自动终止
   - `maxTurns` 护栏未触发（正常退出）

2. **System Prompt 引导**
   - 模型遵循"先搜索再回答"指令
   - 模型根据分数判断结果质量
   - 模型尝试换查询重试（符合指导）

3. **KnowledgeSearchTool**
   - HTTP 调用 ragent 检索服务成功
   - 返回分数信息供模型判断
   - 错误处理健壮（修复了 JsonNull 问题）

### 📝 观察到的模型决策

| 行为 | 符合预期 | 说明 |
|-----|---------|------|
| 先调用工具 | ✓ | 收到问题后立即搜索 |
| 观察分数 | ✓ | 注意到最高分 ~0.64 |
| 尝试重试 | ✓ | 换了更具体的查询 |
| 诚实回答 | ✓ | 未编造信息，告知知识库不足 |

## 修复的问题

### JsonNull 异常
**问题**: `sourceLocation` 字段在 JSON 中为 `null`，直接调用 `getAsString()` 抛出 `UnsupportedOperationException`

**修复**: 添加 `getJsonString()` 和 `getJsonFloat()` 辅助方法，安全处理 `JsonNull`

```java
private String getJsonString(JsonObject obj, String field, String defaultValue) {
    if (!obj.has(field) || obj.get(field).isJsonNull()) {
        return defaultValue;
    }
    return obj.get(field).getAsString();
}
```

## 结论

实验 1 成功验证了：

1. **Agent Loop 核心骨架** 可以驱动模型进行多轮工具调用
2. **System Prompt** 可以有效引导模型行为（搜索→判断→重试→诚实回答）
3. **KnowledgeSearchTool** 实现了真实的向量检索能力

模型展示出良好的决策能力：不盲目相信低分结果、尝试改进查询、无法回答时诚实告知。

## 后续改进方向

1. **分数阈值调优**: 当前 0.8 可能过高，考虑 0.7 或动态调整
2. **重试策略**: 限制最多 2 次重试，避免无限循环
3. **工具调用记录**: 在 System Prompt 中告知模型已搜索过的关键词，避免重复