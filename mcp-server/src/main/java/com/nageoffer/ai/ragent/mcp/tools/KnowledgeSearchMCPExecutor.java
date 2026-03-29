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
import java.util.Map;

@Slf4j
@Component
public class KnowledgeSearchMCPExecutor implements MCPToolExecutor {

    private static final String BOOTSTRAP_RETRIEVE_URL = "http://localhost:9090/api/ragent/retrieve";
    private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String TOOL_ID = "knowledge_search";

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("在知识库中检索与问题相关的文档片段，适用于查询技术文档、项目资料、实验记录等已存入知识库的内容")
                .parameters(Map.of(
                        "query", MCPToolDefinition.ParameterDef.builder()
                                .description("要检索的问题或关键词")
                                .required(true)
                                .build(),
                        "collection_name", MCPToolDefinition.ParameterDef.builder()
                                .description("指定检索的知识库集合名称，不填则使用默认集合")
                                .required(false)
                                .build(),
                        "top_k", MCPToolDefinition.ParameterDef.builder()
                                .description("返回的最大文档片段数量，默认5")
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
        String collectionName = request.getStringParameter("collection_name");
        String topKStr = request.getStringParameter("top_k");
        int topK = (topKStr != null) ? Integer.parseInt(topKStr) : 5;

        if (query == null || query.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 query 不能为空");
        }

        try {
            // 构建请求体
            JsonObject body = new JsonObject();
            body.addProperty("query", query);
            body.addProperty("topK", topK);
            if (collectionName != null && !collectionName.isBlank()) {
                body.addProperty("collectionName", collectionName);
            } else {
                body.addProperty("collectionName", "ragentdocs"); // 默认集合
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BOOTSTRAP_RETRIEVE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return MCPToolResponse.error(TOOL_ID, "SERVICE_ERROR", "检索服务异常，状态码: " + response.statusCode());
            }

            // 解析并格式化返回结果
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray chunks = result.getAsJsonArray("data");

            if (chunks == null || chunks.isEmpty()) {
                return MCPToolResponse.success(TOOL_ID, "未找到相关文档片段");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(chunks.size()).append(" 个相关片段：\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                JsonObject chunk = chunks.get(i).getAsJsonObject();
                String text = chunk.has("text") ? chunk.get("text").getAsString() : "";
                float score = chunk.has("score") ? chunk.get("score").getAsFloat() : 0f;
                sb.append("[").append(i + 1).append("] (相关度: ")
                  .append(String.format("%.2f", score)).append(")\n")
                  .append(text).append("\n\n");
            }

            log.info("knowledge_search 执行完成, query={}, 返回{}个片段", query, chunks.size());
            return MCPToolResponse.success(TOOL_ID, sb.toString());

        } catch (Exception e) {
            log.error("knowledge_search 执行异常, query={}", query, e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "检索异常: " + e.getMessage());
        }
    }
}