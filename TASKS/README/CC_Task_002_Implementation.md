# CC Task 005：更新 README 与 README_DEV.md 并推送 main 分支

> **目标：** 基于调研报告结论，重写 README.md，
> 同步更新 README_DEV.md，推送到 GitHub main 分支
> **约束：** 不引入代码片段，用语义级别描述；
> 图片已存在，路径确认后直接引用
> **产出：** 更新后的 README.md + README_DEV.md，推送完成

---

## 背景

CC_Report_001 已完成调研，核心结论如下：
- 完整链路：SmartChatController → StrategyRouter →
  Pipeline → SYSTEM意图 → 转交Agent →
  AgentLoop → system_info_query → 真实数据 → 回答
- 三个质变点：Agent Loop / 策略可插拔 / 工具可扩展
- 两张截图：qa-system.png（"介绍一下你自己"的回答）、
  source.png（Pipeline回答+📚引用来源区域）

---

## 实施步骤

### 1. 确认截图路径

确认 qa-system.png 和 source.png 的实际存放位置，
如不在 assets/ 目录则先移动或创建软链接，
确保 README 中的图片引用路径正确可渲染。

### 2. 重写 README.md

按以下结构重写，**不保留原有"原项目来源"长篇章节**：

---

**结构如下：**

#### 徽章行（保持原有）

#### 一句话简介
说明这是基于 ragent 的增强版本，
核心增强是 Agent Loop 模式 + 智能路由 + 系统自省能力

#### 核心特性（3个，对应3个质变点）

**1. Agent Loop 模式**
- 插入 qa-system.png
- 语义描述：模型自主驱动的 while 循环，
  自主判断何时调工具、何时回答、是否需要继续检索。
  以"介绍一下你自己"为例：
  系统识别出 SYSTEM 意图 → 自动转交 Agent →
  Agent 调用 system_info_query 查询真实数据 →
  返回包含4个真实知识库信息的回答，而非编造

**2. 智能路由（Pipeline + Agent 协同）**
- 语义描述：规则路由自动决策处理策略，
  简单问题走 Pipeline 保证低延迟，
  复杂问题或 SYSTEM 意图自动切换 Agent，
  用户无需手动选择
- 插入 source.png（展示 Pipeline 回答 + ）
- 说明：Pipeline 模式支持引用溯源，
  每条回答附带原始文档来源和相关度评分

**3. 可扩展架构**
- 语义描述（无代码）：
    - 策略层：新增处理模式只需实现 ChatStrategy 接口，
      当前已有 Pipeline / Agent，
      未来可扩展 MultiAgent、WebSearch 等
    - 工具层：新增场景只需实现 Tool 接口并注册，
      当前已有 knowledge_search、system_info_query

#### 与原项目的差异（简表）

| 能力 | 原项目 | 本仓库 |
|------|--------|--------|
| 问答模式 | Pipeline 固定流程 | Pipeline + Agent 自动切换 |
| 元信息问题 | 依赖知识库内容 | Agent 查询真实系统数据 |
| 路由决策 | 手动选择 | 规则自动路由 |
| 扩展方式 | 修改现有流程 | 实现接口即可扩展 |

#### 快速开始（保持原有，补充百炼API Key说明）

#### 技术栈（保持原有）

#### 开发记录（保持原有，指向dev分支）

#### 致谢（简化为一行）
```markdown
基于 [nageoffer/ragent](https://github.com/nageoffer/ragent) 开发，感谢原作者。
```

---

### 3. 更新 README_DEV.md

README_DEV.md 位于 dev 分支，内容主体与 README.md 保持一致，
在此基础上在末尾追加一个章节：

```markdown
## 开发过程记录

### 协作模式
本项目采用 ReAct 式人机协作开发，
规划者（Claude对话）负责任务分解，
执行者（Claude Code）负责代码实现，
详见 [COLLABORATION_PROTOCOL.md](COLLABORATION_PROTOCOL.md)

### 里程碑
| Task | 内容 | 状态 |
|------|------|------|
| Task 001 | 调研 bootstrap 现有结构 | ✅ |
| Task 002 | Agent Loop 最小化合入 | ✅ |
| Task 003 | 功能验证 | ✅ |
| Task 004 | README 写作调研 | ✅ |
| Task 005 | README 更新与发布 | ✅ |
```

---

### 4. 推送到 GitHub

```bash
# 确认在 main 分支
git checkout main

# 提交 README 更新
git add README.md
git commit -m "docs: 重写 README，补充截图与核心特性说明"

# 推送
git push origin main
```

dev 分支单独提交 README_DEV.md：
```bash
git checkout dev
git add README_DEV.md
git commit -m "docs: 同步更新 README_DEV.md"
git push origin dev
```

---

## 注意事项

- 不引入任何代码片段，全部用语义描述
- 截图引用格式：
  `![描述](assets/qa-system.png)`
  路径以实际文件位置为准
- 如发现截图路径不对，在 REPORT 中说明实际路径，
  不要跳过图片引用
- 原有"原项目来源"长篇章节直接删除，
  只保留底部一行致谢