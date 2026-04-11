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
import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（带 Rerank）- 多知识库版本
 * <p>
 * 支持三个知识库：
 * - ragentdocs: Ragent 项目文档（架构、RAG、MCP、意图树等）
 * - ssddocs: SSD 理论文档（状态空间模型、原论文理论）
 * - dualssddocs: DualSSD 创新文档（块级别状态管理、改进）
 * </p>
 */
@Slf4j
@Component
public class KnowledgeSearchWithRerankMCPExecutor implements MCPToolExecutor {

    private static final String BOOTSTRAP_URL = "http://localhost:9090/api/ragent/retrieve/with-rerank";
    private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String TOOL_ID = "knowledge_search_with_rerank";

    /**
     * 支持的知识库列表
     */
    private static final List<String> VALID_COLLECTIONS = List.of("ragentdocs", "ssddocs", "dualssddocs");

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("""
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

You can search three different knowledge bases:
- ragentdocs: Ragent project documentation (system architecture, RAG implementation, MCP tools, intent tree, chunking strategy, frontend tech stack)
- ssddocs: SSD (State Space Duality) original theory and paper content (state space models, mathematical derivation)
- dualssddocs: DualSSD innovation documentation (improvements over SSD, block-level state management)

If unsure which knowledge base to search, start with the most likely one based on the question topic.
You can search multiple knowledge bases by calling this tool multiple times with different collection values.
""")
                .parameters(Map.of(
                        "query", MCPToolDefinition.ParameterDef.builder()
                                .description("The search query. Be specific and use keywords relevant to the topic.")
                                .required(true)
                                .build(),
                        "collection", MCPToolDefinition.ParameterDef.builder()
                                .description("Which knowledge base to search. Choose based on the question topic.")
                                .required(true)
                                .enumValues(VALID_COLLECTIONS)
                                .build(),
                        "top_k", MCPToolDefinition.ParameterDef.builder()
                                .description("Maximum number of document chunks to return, default 5")
                                .type("integer")
                                .defaultValue(5)
                                .required(false)
                                .build()
                ))
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        String query = request.getStringParameter("query");
        String collection = request.getStringParameter("collection");
        String topKStr = request.getStringParameter("top_k");
        int topK = (topKStr != null) ? Integer.parseInt(topKStr) : 5;

        if (query == null || query.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 query 不能为空");
        }

        if (collection == null || collection.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 collection 不能为空，请指定知识库：ragentdocs、ssddocs 或 dualssddocs");
        }

        if (!VALID_COLLECTIONS.contains(collection)) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM",
                    "无效的知识库: " + collection + "。请选择：ragentdocs、ssddocs 或 dualssddocs");
        }

        try {
            // 构建请求体
            int candidateCount = Math.max(topK * 3, 15);

            JsonObject body = new JsonObject();
            body.addProperty("query", query);
            body.addProperty("topK", topK);
            body.addProperty("candidateCount", candidateCount);
            body.addProperty("collectionName", collection);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BOOTSTRAP_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return MCPToolResponse.error(TOOL_ID, "SERVICE_ERROR", "检索服务异常，状态码: " + response.statusCode());
            }

            // 解析返回结果
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);

            String code = getJsonString(result, "code", "");
            if (!"0".equals(code)) {
                String message = getJsonString(result, "message", "Unknown error");
                return MCPToolResponse.error(TOOL_ID, "SERVICE_ERROR", "检索服务返回错误: " + message);
            }

            JsonArray chunks = result.getAsJsonArray("data");
            if (chunks == null || chunks.isEmpty()) {
                return MCPToolResponse.success(TOOL_ID,
                        String.format("知识库 [%s] 中未找到相关文档片段", collection));
            }

            // 格式化返回结果
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("在知识库 [%s] 中找到 %d 个相关片段：\n\n", collection, chunks.size()));

            for (int i = 0; i < chunks.size(); i++) {
                JsonObject chunk = chunks.get(i).getAsJsonObject();

                String text = getJsonString(chunk, "text", "");
                float score = getJsonFloat(chunk, "score", 0f);
                String sourceLocation = getJsonString(chunk, "sourceLocation", "未知来源");

                sb.append("[").append(i + 1).append("] ")
                  .append("(Rerank分数: ").append(String.format("%.2f", score)).append(") ")
                  .append("(来源: ").append(sourceLocation).append(")\n")
                  .append(text).append("\n\n");
            }

            sb.append("---\n");
            sb.append("分数分布说明（Rerank 精排后）：\n");
            sb.append("- 分数 > 0.85: 高度相关，信息准确\n");
            sb.append("- 分数 0.75-0.85: 中等相关，可信参考\n");
            sb.append("- 分数 < 0.75: 低相关，建议换关键词或换知识库重试\n");

            log.info("knowledge_search_with_rerank 执行完成, collection={}, query={}, 返回{}个片段",
                    collection, query, chunks.size());
            return MCPToolResponse.success(TOOL_ID, sb.toString());

        } catch (Exception e) {
            log.error("knowledge_search_with_rerank 执行异常, collection={}, query={}", collection, query, e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "检索异常: " + e.getMessage());
        }
    }

    private String getJsonString(JsonObject obj, String field, String defaultValue) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsString();
    }

    private float getJsonFloat(JsonObject obj, String field, float defaultValue) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsFloat();
    }
}