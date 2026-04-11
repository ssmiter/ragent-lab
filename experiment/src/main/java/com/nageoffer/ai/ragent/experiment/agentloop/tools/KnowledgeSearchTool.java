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

package com.nageoffer.ai.ragent.experiment.agentloop.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.experiment.agentloop.Tool;
import com.nageoffer.ai.ragent.experiment.agentloop.ToolResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 知识库检索工具
 *
 * <p>通过 HTTP 调用 ragent 的向量检索服务，返回相关文档片段和分数。</p>
 *
 * <p>特点：</p>
 * <ul>
 *   <li>返回分数信息，让模型能判断搜索结果质量</li>
 *   <li>支持指定 collection（知识库集合）</li>
 *   <li>错误信息明确，便于模型理解失败原因</li>
 * </ul>
 */
public class KnowledgeSearchTool implements Tool {

    private static final String NAME = "knowledge_search";
    private static final String DESCRIPTION = """
Search the knowledge base for relevant information.
Returns ranked text chunks with relevance scores.
Use this when you need to find information to answer the user's question.
You can call this multiple times with different queries if the initial results are not satisfactory.

Parameters:
- query: The search query. Be specific and use keywords relevant to the topic.
- collection: The knowledge base collection to search in. Options: ragentdocs, ssddocs, dualssddocs.
              If unsure, omit this to search the default collection (ragentdocs).
- top_k: Number of results to return (default: 5, max: 10)
""";

    private static final String RETRIEVE_URL = "http://localhost:9090/api/ragent/retrieve";
    private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public String getInputSchema() {
        // 构建 JSON Schema 并转为字符串
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");
        queryProp.addProperty("description", "The search query. Be specific and use keywords relevant to the topic.");
        properties.add("query", queryProp);

        JsonObject collectionProp = new JsonObject();
        collectionProp.addProperty("type", "string");
        collectionProp.addProperty("description", "The knowledge base collection to search in. Options: ragentdocs, ssddocs, dualssddocs. If unsure, omit this to search the default collection.");
        JsonArray enumArray = new JsonArray();
        enumArray.add("ragentdocs");
        enumArray.add("ssddocs");
        enumArray.add("dualssddocs");
        collectionProp.add("enum", enumArray);
        properties.add("collection", collectionProp);

        JsonObject topKProp = new JsonObject();
        topKProp.addProperty("type", "integer");
        topKProp.addProperty("description", "Number of results to return. Default: 5, Max: 10");
        topKProp.addProperty("default", 5);
        topKProp.addProperty("maximum", 10);
        properties.add("top_k", topKProp);

        schema.add("properties", properties);

        JsonArray requiredArray = new JsonArray();
        requiredArray.add("query");
        schema.add("required", requiredArray);

        return GSON.toJson(schema);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        String collection = (String) arguments.get("collection");
        Integer topK = arguments.containsKey("top_k")
                ? ((Number) arguments.get("top_k")).intValue()
                : 5;

        if (query == null || query.isBlank()) {
            return ToolResult.error(NAME, "参数 query 不能为空");
        }

        // Clamp topK
        topK = Math.min(Math.max(topK, 1), 10);

        // Default collection
        if (collection == null || collection.isBlank()) {
            collection = "ragentdocs";
        }

        try {
            // 构建请求体
            JsonObject body = new JsonObject();
            body.addProperty("query", query);
            body.addProperty("topK", topK);
            body.addProperty("collectionName", collection);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(RETRIEVE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            System.out.println("[KnowledgeSearchTool] 响应状态码: " + response.statusCode());
            System.out.println("[KnowledgeSearchTool] 响应体: " + responseBody);

            if (response.statusCode() != 200) {
                return ToolResult.error(NAME, "检索服务异常，状态码: " + response.statusCode() + ", body: " + responseBody);
            }

            // 解析响应 - 更健壮的处理
            if (responseBody == null || responseBody.isBlank()) {
                return ToolResult.error(NAME, "响应体为空");
            }

            JsonObject result = GSON.fromJson(responseBody, JsonObject.class);

            // 检查响应结构
            if (!result.has("data") || result.get("data").isJsonNull()) {
                // 可能是错误响应
                String message = result.has("message") ? result.get("message").getAsString() : "未知错误";
                return ToolResult.error(NAME, "检索返回无数据: " + message);
            }

            JsonElement dataElement = result.get("data");
            if (!dataElement.isJsonArray()) {
                return ToolResult.error(NAME, "data 字段不是数组: " + dataElement.toString());
            }

            JsonArray chunks = dataElement.getAsJsonArray();
            if (chunks.isEmpty()) {
                return ToolResult.success(NAME, formatEmptyResult(collection));
            }

            return ToolResult.success(NAME, formatResult(chunks, collection));

        } catch (Exception e) {
            System.err.println("[KnowledgeSearchTool] 异常: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            return ToolResult.error(NAME, "检索异常: " + e.getMessage());
        }
    }

    /**
     * 格式化检索结果，暴露分数信息让模型判断质量
     */
    private String formatResult(JsonArray chunks, String collection) {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(chunks.size()).append(" relevant chunks (searched collection: ")
          .append(collection).append("):\n\n");

        float highestScore = 0f;
        float lowestScore = 1f;

        for (int i = 0; i < chunks.size(); i++) {
            JsonObject chunk = chunks.get(i).getAsJsonObject();
            String text = getJsonString(chunk, "text", "");
            float score = getJsonFloat(chunk, "score", 0f);
            String sourceLocation = getJsonString(chunk, "sourceLocation", "unknown");

            highestScore = Math.max(highestScore, score);
            lowestScore = Math.min(lowestScore, score);

            sb.append("[Chunk ").append(i + 1).append("] (score: ")
              .append(String.format("%.2f", score))
              .append(", source: ").append(sourceLocation).append(")\n");

            // Truncate long text to avoid excessive tokens
            String displayText = text.length() > 500 ? text.substring(0, 500) + "..." : text;
            sb.append(displayText).append("\n\n");
        }

        // Add score distribution summary
        sb.append("Score distribution: highest=").append(String.format("%.2f", highestScore))
          .append(", lowest=").append(String.format("%.2f", lowestScore));

        if (chunks.size() > 1) {
            JsonObject firstChunk = chunks.get(0).getAsJsonObject();
            JsonObject secondChunk = chunks.get(1).getAsJsonObject();
            float firstScore = getJsonFloat(firstChunk, "score", 0f);
            float secondScore = getJsonFloat(secondChunk, "score", 0f);
            sb.append(", gap between top1 and top2: ")
              .append(String.format("%.2f", firstScore - secondScore));
        }

        return sb.toString();
    }

    /**
     * 安全获取 JSON 字符串字段，处理 null 和缺失情况
     */
    private String getJsonString(JsonObject obj, String field, String defaultValue) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsString();
    }

    /**
     * 安全获取 JSON 浮点数字段，处理 null 和缺失情况
     */
    private float getJsonFloat(JsonObject obj, String field, float defaultValue) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsFloat();
    }

    private String formatEmptyResult(String collection) {
        return "No relevant chunks found in collection: " + collection + ". " +
               "Try using different keywords or a different query.";
    }
}