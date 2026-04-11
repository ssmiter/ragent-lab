# Experiment 1：真实 Agent Loop

## 实验目标

将骨架升级为能真正回答知识库问题的完整 Agent：
1. 添加 System Prompt（引导模型行为）
2. 实现 KnowledgeSearchTool（真实向量检索）
3. 让模型自主决定搜索策略（重试、换查询）

## 核心实现

### System Prompt 设计（基于 CC 模式）

```markdown
你是一个知识库助手...

# 可用工具
- knowledge_search: 在知识库中搜索...

# 行为指导
1. 收到问题后先搜索
2. 观察分数判断质量（>0.8 高，<0.6 低）
3. 低分时换关键词重试
4. 最多重试 2 次
5. 找不到时诚实告知

# 语言
始终使用中文
```

### KnowledgeSearchTool

HTTP 调用 `/api/ragent/retrieve`，返回 chunks + 分数。

关键设计：
- 返回分数信息，让模型判断质量
- 分数分布摘要写入返回文本（隐式引导）

## 结果

✅ 模型正确遵循引导：
- 先搜索再回答
- 根据分数判断结果质量
- 低分时尝试换查询重试
- 无法回答时诚实告知

## 问题发现

- 分数阈值需要根据实际分布调整
- 原始向量分数偏低（0.65-0.85），导致模型过度重试

## 文件

- [implementation.md](./implementation.md) - 详细实现记录
- [result.md](./result.md) - 运行结果分析