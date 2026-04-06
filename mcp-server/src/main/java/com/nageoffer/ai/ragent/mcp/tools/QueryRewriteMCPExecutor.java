/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 查询重写工具
 * <p>
 * 将模糊或口语化的用户查询改写为更精确的检索查询。
 * 使用百炼 API 调用 LLM 进行改写。
 * </p>
 */
@Slf4j
@Component
public class QueryRewriteMCPExecutor implements MCPToolExecutor {

    private static final String BAILIAN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String API_KEY = "sk-e468852b76324d17b8131d6d8a58dda8";
    private static final String MODEL = "qwen-plus-latest";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String TOOL_ID = "rewrite_query";

    /**
     * 查询重写 Prompt（简化版，适配 Agent Loop 工具场景）
     */
    private static final String REWRITE_PROMPT = """
你是一个查询改写助手，用于将用户问题改写为更适合知识库检索的查询。

# 任务
将用户问题改写为简洁、精确的自然语言查询，只保留与检索相关的关键信息。

# 改写规则

## 保留内容
- 专有名词（系统名、产品名、模块名等）：原样保留
- 关键限制：时间范围、环境、终端类型、角色身份等
- 业务场景：流程、规范、配置等

## 删除内容
- 礼貌用语："请帮我"、"麻烦"、"谢谢"
- 回答指令："详细说明"、"分点回答"、"一步步分析"
- 无关描述："我是新人"、"我刚入职"
- 口语化表达："咋实现的"、"那个什么东西"

## 禁止行为
- 不得添加原文没有的条件、维度、假设
- 不得修改专有名词的写法
- 保持原问题的语言（中文/英文）

## 特殊场景
- 如果原始查询已经精确清晰，直接返回原文
- 如果原始查询模糊，尝试用技术术语替换口语表达
- 如果无法确定用户意图，保守改写，不要过度臆测

# 输出格式
严格返回 JSON：
{
  "rewrite": "改写后的查询",
  "confidence": "high/medium/low",
  "reason": "简短说明改写原因"
}

confidence 说明：
- high: 原始查询模糊，改写后更适合检索
- medium: 原始查询有改进空间，改写有帮助
- low: 原始查询已足够精确，改写意义不大

# 示例

示例1：模糊查询
输入：之前看过一个什么自适应的东西，好像和分数有关
输出：
{
  "rewrite": "自适应截断策略 分数阈值",
  "confidence": "high",
  "reason": "口语化表述，缺少具体术语，改写后更适合检索"
}

示例2：清晰查询
输入：MCP工具是怎么注册和调用的
输出：
{
  "rewrite": "MCP工具注册和调用流程",
  "confidence": "low",
  "reason": "原始查询已足够精确，微调为查询格式"
}

示例3：口语化查询
输入：那个把文档切成小块的功能咋实现的
输出：
{
  "rewrite": "文档分块策略实现",
  "confidence": "high",
  "reason": "口语化表达转换为技术术语"
}
""";

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("Rewrite a vague or colloquial user query into a more precise search query suitable for knowledge base retrieval. Use this BEFORE calling knowledge_search_with_rerank when the user's question is ambiguous, uses informal language, or could benefit from being rephrased with more specific technical terms. Do NOT use this if the query is already clear and specific.")
                .parameters(Map.of(
                        "original_query", MCPToolDefinition.ParameterDef.builder()
                                .description("The original user query to be rewritten")
                                .required(true)
                                .build(),
                        "intent_hint", MCPToolDefinition.ParameterDef.builder()
                                .description("Optional hint about what the user is really looking for, to guide the rewrite")
                                .required(false)
                                .build()
                ))
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        String originalQuery = request.getStringParameter("original_query");
        String intentHint = request.getStringParameter("intent_hint");

        if (originalQuery == null || originalQuery.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 original_query 不能为空");
        }

        try {
            // 构建请求
            String userPrompt = originalQuery;
            if (intentHint != null && !intentHint.isBlank()) {
                userPrompt = originalQuery + "\n\n用户意图提示：" + intentHint;
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", MODEL);
            requestBody.addProperty("temperature", 0.1);
            requestBody.addProperty("top_p", 0.3);

            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", REWRITE_PROMPT);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userPrompt);

            requestBody.add("messages", GSON.toJsonTree(java.util.List.of(systemMsg, userMsg)));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BAILIAN_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return MCPToolResponse.error(TOOL_ID, "API_ERROR", "百炼 API 调用失败: HTTP " + response.statusCode());
            }

            // 解析响应
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            String content = result.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            // 解析 JSON 输出
            JsonObject rewriteResult = parseRewriteResult(content);
            if (rewriteResult == null) {
                // 解析失败，直接返回原始查询
                return MCPToolResponse.success(TOOL_ID, formatFallbackResult(originalQuery));
            }

            String rewritten = rewriteResult.has("rewrite") ? rewriteResult.get("rewrite").getAsString() : originalQuery;
            String confidence = rewriteResult.has("confidence") ? rewriteResult.get("confidence").getAsString() : "medium";
            String reason = rewriteResult.has("reason") ? rewriteResult.get("reason").getAsString() : "";

            log.info("查询改写完成: original={}, rewritten={}, confidence={}", originalQuery, rewritten, confidence);

            return MCPToolResponse.success(TOOL_ID, formatResult(originalQuery, rewritten, confidence, reason));

        } catch (Exception e) {
            log.error("查询改写执行异常, originalQuery={}", originalQuery, e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "改写异常: " + e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 JSON（处理可能的 markdown 代码块）
     */
    private JsonObject parseRewriteResult(String content) {
        try {
            String cleaned = content;
            // 移除可能的 markdown 代码块
            if (content.contains("```")) {
                cleaned = content.replaceAll("```json\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
            }
            return GSON.fromJson(cleaned, JsonObject.class);
        } catch (Exception e) {
            log.warn("解析改写结果失败, content={}", content);
            return null;
        }
    }

    /**
     * 格式化正常结果
     */
    private String formatResult(String original, String rewritten, String confidence, String reason) {
        return String.format("""
Rewritten query: "%s"
Original: "%s"
Confidence: %s (%s)
""", rewritten, original, confidence, reason);
    }

    /**
     * 格式化兜底结果
     */
    private String formatFallbackResult(String original) {
        return String.format("""
Rewritten query: "%s"
Original: "%s"
Confidence: low (改写失败，使用原始查询)
""", original, original);
    }
}