# Microcompact 压缩前后对比案例

模拟条件: 当前 Turn=15, 年龄阈值=3
总压缩数: 26 条 tool_result

---

## 案例 1: ToolCallId=call_6d30032bbbd443f7bb6881

- **原始轮次**: Turn 2
- **年龄**: 13 轮
- **原始字符数**: 105405
- **压缩后字符数**: 68
- **节省**: 105337 字符 (99.9%)

### 原始内容 (前500字符)

```
在知识库 [ragentdocs] 中找到 5 个相关片段：

[1] (Rerank分数: 0.86) (来源: 未知来源)
# 《AI大模型Ragent项目》——MCP之官方Java-SDK深度解析

[来自： 拿个offer-开源&项目实战](https://wx.zsxq.com/group/51121244585524)

![用户头像](https://images.zsxq.com/Fr8uwu8AMapccyWqKi_UbroM7Q8K?e=2127196800&token=kIxbL07-8jAj8w1n4s9zv64FuZZNEATmlU_Vm6zD:W15RCVvEwfGfhrjEog-w_k_J4L4=)马丁

2026年03月18日 22:09

前面几篇 MCP 文章，你用 Spring AI 的 `@McpTool` 注解定义工具，用 `@McpResource` 暴露资源，用 `@McpPrompt` 注册提示词模板，60 行代码就能把一个 MCP Server 跑起来，Claude Desktop 和 Cursor 都能直接调。

...
```

### 压缩后内容

```
[已压缩的搜索结果 - Turn 2]
知识库: ragentdocs, 最高分 0.86
完整记录已保存至 transcript 文件
```

---

## 案例 2: ToolCallId=call_25079e7b98ed457baf8cf2

- **原始轮次**: Turn 2
- **年龄**: 13 轮
- **原始字符数**: 104728
- **压缩后字符数**: 65
- **节省**: 104663 字符 (99.9%)

### 原始内容 (前500字符)

```
在知识库 [ssddocs] 中找到 5 个相关片段：

[1] (Rerank分数: 0.78) (来源: 未知来源)
![](media/image24.png)

![](media/image25.png){width="4.048681102362205in"
height="1.1439020122484689in"}

> **State Space Models** are **Semiseparable Matrix Transformations**
>
> **Inputs** x
>
> Figure 2: (**State Space Models are Semiseparable Matrices**.) As
> sequence transformations, state space models can be represented as a
> matrix transformation M ∈ R(T,T) acting on the sequence dimension T,
> sharing the same ma...
```

### 压缩后内容

```
[已压缩的搜索结果 - Turn 2]
知识库: ssddocs, 最高分 0.78
完整记录已保存至 transcript 文件
```

---

## 案例 3: ToolCallId=call_faa882a225d24c4fa47c47

- **原始轮次**: Turn 7
- **年龄**: 8 轮
- **原始字符数**: 95318
- **压缩后字符数**: 68
- **节省**: 95250 字符 (99.9%)

### 原始内容 (前500字符)

```
在知识库 [ragentdocs] 中找到 5 个相关片段：

[1] (Rerank分数: 0.84) (来源: 未知来源)
# 《AI大模型Ragent项目》——MCP之官方Java-SDK深度解析

[来自： 拿个offer-开源&项目实战](https://wx.zsxq.com/group/51121244585524)

![用户头像](https://images.zsxq.com/Fr8uwu8AMapccyWqKi_UbroM7Q8K?e=2127196800&token=kIxbL07-8jAj8w1n4s9zv64FuZZNEATmlU_Vm6zD:W15RCVvEwfGfhrjEog-w_k_J4L4=)马丁

2026年03月18日 22:09

前面几篇 MCP 文章，你用 Spring AI 的 `@McpTool` 注解定义工具，用 `@McpResource` 暴露资源，用 `@McpPrompt` 注册提示词模板，60 行代码就能把一个 MCP Server 跑起来，Claude Desktop 和 Cursor 都能直接调。

...
```

### 压缩后内容

```
[已压缩的搜索结果 - Turn 7]
知识库: ragentdocs, 最高分 0.84
完整记录已保存至 transcript 文件
```

---

## 案例 4: ToolCallId=call_90da75e1581e4b4589c3a7

- **原始轮次**: Turn 6
- **年龄**: 9 轮
- **原始字符数**: 90608
- **压缩后字符数**: 68
- **节省**: 90540 字符 (99.9%)

### 原始内容 (前500字符)

```
在知识库 [ragentdocs] 中找到 5 个相关片段：

[1] (Rerank分数: 0.87) (来源: 未知来源)
# 《AI大模型Ragent项目》——多轮对话记忆设计

[来自： 拿个offer-开源&项目实战](https://wx.zsxq.com/group/51121244585524)

![用户头像](https://images.zsxq.com/Fr8uwu8AMapccyWqKi_UbroM7Q8K?e=2127196800&token=kIxbL07-8jAj8w1n4s9zv64FuZZNEATmlU_Vm6zD:W15RCVvEwfGfhrjEog-w_k_J4L4=)马丁

2026年03月08日 19:37

到这里，RAG 系列的核心链路已经全部打通了：数据分块 → 元数据管理 → 向量化 → 向量数据库 → 检索策略 → 生成策略 → Function Call → MCP 协议。从数据准备到检索生成，再到工具调用，一条完整的链路。

但你把这套系统上线之后，用户很快就会给你提一个 bug：你们这个 AI 是不是没有记...
```

### 压缩后内容

```
[已压缩的搜索结果 - Turn 6]
知识库: ragentdocs, 最高分 0.87
完整记录已保存至 transcript 文件
```

---

## 案例 5: ToolCallId=call_7b1f1c5f30924e6a80bc15

- **原始轮次**: Turn 1
- **年龄**: 14 轮
- **原始字符数**: 80404
- **压缩后字符数**: 68
- **节省**: 80336 字符 (99.9%)

### 原始内容 (前500字符)

```
在知识库 [ragentdocs] 中找到 5 个相关片段：

[1] (Rerank分数: 0.94) (来源: 未知来源)
# 《AI大模型Ragent项目》——项目模块介绍

[来自： 拿个offer-开源&项目实战](https://wx.zsxq.com/group/51121244585524)

2026年03月18日 22:11

这篇文档带着大家一起看看 Ragent 的项目整体结构，搞清楚每个目录和模块各自负责什么。

> 项目中的代码并不是一成不变的，未来如果有更好的设计思路，会进行重构、优化，甚至新增功能模块。如果发现文档内容和实际代码对不上，请联系马哥，谢谢。

## 根目录文件一览

### 1. `pom.xml`

这是 Maven 父 POM，管理所有子模块的依赖版本和构建插件。打开它你会看到四个 `<module>`：

```
<modules>
    <module>bootstrap</module>
    <module>framework</module>
    <module>infra-ai...
```

### 压缩后内容

```
[已压缩的搜索结果 - Turn 1]
知识库: ragentdocs, 最高分 0.94
完整记录已保存至 transcript 文件
```

---

## 统计摘要

- **总压缩数**: 26 条
- **原始总字符**: 1165731
- **压缩后总字符**: 1952
- **总节省**: 1163779 字符 (99.8%)

---

*报告生成于 2026-04-07*