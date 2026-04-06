# ragent 查询重写能力调查报告

## 位置

`bootstrap/src/main/java/.../rag/core/rewrite/`

## 核心接口

**QueryRewriteService.java**
```java
public interface QueryRewriteService {
    // 单一改写
    String rewrite(String userQuestion);

    // 改写 + 拆分多问句
    RewriteResult rewriteWithSplit(String userQuestion);

    // 支持会话历史的改写 + 拆分
    RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history);
}
```

## 结果结构

**RewriteResult.java**
```java
public record RewriteResult(String rewrittenQuestion, List<String> subQuestions) {}
```

## 实现类

**MultiQuestionRewriteService.java**
- 使用 `LLMService` 调用模型
- Prompt 从模板加载（`QUERY_REWRITE_AND_SPLIT_PROMPT_PATH`）
- 输出 JSON 格式：`{"rewrite": "...", "sub_questions": ["...", "..."]}`
- 配置开关：`ragConfigProperties.getQueryRewriteEnabled()`
- 失败兜底：归一化 + 规则拆分

## 关键参数

```java
ChatRequest.builder()
    .messages(messages)
    .temperature(0.1D)
    .topP(0.3D)
    .thinking(false)
    .build();
```

## HTTP 端点

❌ **没有独立暴露**。重写逻辑深度耦合在 RAG pipeline 内部，没有可直接调用的 HTTP 接口。

## 实现路径决策

| 路径 | 可行性 | 评估 |
|-----|-------|------|
| A: 直接调用 Service | ❌ | experiment 模块不依赖 bootstrap |
| B: HTTP 调用端点 | ❌ | 无独立端点（可新增） |
| C: 工具内自实现 | ✅ | 最简单，复用 ragent prompt 模板 |

**决策：路径C**

原因：
1. 重写本质是一次 LLM 调用，prompt 可复用
2. experiment 模块独立，不依赖 Spring 上下文
3. 避免修改 bootstrap（除非需要新增端点供外部使用）

## 备选方案

如果后续需要外部 Agent（如 Claude Code）也能调用重写能力，可以新增端点：

```java
@PostMapping("/rewrite")
public Result<RewriteResult> rewrite(@RequestBody RewriteRequest request) {
    RewriteResult result = queryRewriteService.rewriteWithSplit(request.getQuery());
    return Results.success(result);
}
```

**当前实验选择路径C**，后续可根据需要评估是否新增端点。