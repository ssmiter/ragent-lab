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
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（带 Rerank）- 动态知识库版本
 * <p>
 * 支持的知识库：通过 HTTP 调用 bootstrap 获取，新增知识库无需改代码。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeSearchWithRerankMCPExecutor implements MCPToolExecutor {

    private static final String BOOTSTRAP_URL = "http://localhost:9090/api/ragent/retrieve/with-rerank";
    private static final String KNOWLEDGE_BASE_LIST_URL = "http://localhost:9090/api/ragent/knowledge-base";
    private static final String INTENT_TREE_URL = "http://localhost:9090/api/ragent/intent-tree/trees";
    private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String TOOL_ID = "knowledge_search_with_rerank";

    /**
     * 缓存的知识库列表（简单实现，每次调用时重新获取）
     */
    private List<String> cachedValidCollections = null;

    @Override
    public MCPToolDefinition getToolDefinition() {
        // 动态获取知识库列表
        List<String> validCollections = fetchKnowledgeBases();
        // 从意图树获取 KB 类型叶子节点的语义描述
        Map<String, KbRoutingInfo> routingInfoMap = fetchKbRoutingInfo();

        String kbListStr = validCollections.stream()
                .map(collection -> {
                    KbRoutingInfo info = routingInfoMap.get(collection);
                    if (info != null && info.description != null && !info.description.isBlank()) {
                        String shortDesc = truncateDescription(info.description, 50);
                        return "- " + collection + " (" + shortDesc + ")";
                    }
                    return "- " + collection;
                })
                .collect(Collectors.joining("\n"));

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("""
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

Available knowledge bases (each with content scope):
""" + kbListStr + """

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
                                .enumValues(validCollections)
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

    /**
     * 从意图树获取 KB 类型叶子节点的路由信息
     * 通过 HTTP 调用 bootstrap 的意图树 API
     */
    private Map<String, KbRoutingInfo> fetchKbRoutingInfo() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(INTENT_TREE_URL))
                    .header("Authorization", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("获取意图树失败，状态码: {}", response.statusCode());
                return Map.of();
            }

            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            String code = getJsonString(result, "code", "");
            if (!"0".equals(code)) {
                log.warn("获取意图树返回错误");
                return Map.of();
            }

            JsonArray data = result.getAsJsonArray("data");
            if (data == null || data.isEmpty()) {
                return Map.of();
            }

            // 递归遍历意图树，提取 KB 类型叶子节点
            Map<String, KbRoutingInfo> routingMap = new HashMap<>();
            for (JsonElement element : data) {
                extractKbLeafNodes(element.getAsJsonObject(), routingMap);
            }

            log.debug("从意图树获取到 {} 个 KB 路由信息", routingMap.size());
            return routingMap;

        } catch (Exception e) {
            log.warn("获取意图树异常: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 递归提取 KB 类型叶子节点的路由信息
     */
    private void extractKbLeafNodes(JsonObject node, Map<String, KbRoutingInfo> routingMap) {
        String collectionName = getJsonString(node, "collectionName", null);
        Integer kind = getJsonInt(node, "kind", 0); // 0=KB
        JsonArray children = node.getAsJsonArray("children");

        // 判断是否为 KB 类型叶子节点（有 collectionName）
        if (collectionName != null && !collectionName.isBlank()
                && (kind == null || kind == 0)) {
            String description = getJsonString(node, "description", null);
            String examplesJson = getJsonString(node, "examples", null);
            routingMap.put(collectionName, new KbRoutingInfo(description, examplesJson));
        }

        // 递归处理子节点
        if (children != null && !children.isEmpty()) {
            for (JsonElement child : children) {
                extractKbLeafNodes(child.getAsJsonObject(), routingMap);
            }
        }
    }

    /**
     * 精简描述为一行，控制在指定长度内
     */
    private String truncateDescription(String desc, int maxLen) {
        if (desc == null || desc.isBlank()) {
            return "";
        }
        String cleaned = desc.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        int cutPoint = cleaned.indexOf('。');
        if (cutPoint > 0 && cutPoint < maxLen) {
            return cleaned.substring(0, cutPoint);
        }
        cutPoint = cleaned.indexOf(',');
        if (cutPoint > 0 && cutPoint < maxLen) {
            return cleaned.substring(0, cutPoint).trim();
        }
        return cleaned.substring(0, maxLen - 1) + "...";
    }

    /**
     * KB 路由信息（内部使用）
     */
    private record KbRoutingInfo(String description, String examples) {}

    private Integer getJsonInt(JsonObject obj, String field, Integer defaultValue) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(field).getAsInt();
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 通过 HTTP 调用 bootstrap 获取知识库列表
     */
    private List<String> fetchKnowledgeBases() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(KNOWLEDGE_BASE_LIST_URL))
                    .header("Authorization", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("获取知识库列表失败，状态码: {}, 使用默认列表", response.statusCode());
                return getDefaultCollections();
            }

            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            String code = getJsonString(result, "code", "");
            if (!"0".equals(code)) {
                log.warn("获取知识库列表返回错误，使用默认列表");
                return getDefaultCollections();
            }

            JsonObject data = result.getAsJsonObject("data");
            if (data == null) {
                return getDefaultCollections();
            }

            JsonArray records = data.getAsJsonArray("records");
            if (records == null || records.isEmpty()) {
                return getDefaultCollections();
            }

            List<String> collections = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                JsonObject kb = records.get(i).getAsJsonObject();
                String collectionName = getJsonString(kb, "collectionName", null);
                if (collectionName != null && !collectionName.isBlank()) {
                    collections.add(collectionName);
                }
            }

            log.debug("从 bootstrap 获取到 {} 个知识库", collections.size());
            return collections.isEmpty() ? getDefaultCollections() : collections;

        } catch (Exception e) {
            log.warn("获取知识库列表异常: {}, 使用默认列表", e.getMessage());
            return getDefaultCollections();
        }
    }

    /**
     * 默认知识库列表（兜底方案）
     */
    private List<String> getDefaultCollections() {
        return List.of("ragentdocs", "ssddocs", "dualssddocs");
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        String query = request.getStringParameter("query");
        String collection = request.getStringParameter("collection");
        String topKStr = request.getStringParameter("top_k");
        int topK = (topKStr != null) ? Integer.parseInt(topKStr) : 5;

        // 动态获取有效知识库列表
        List<String> validCollections = fetchKnowledgeBases();

        if (query == null || query.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 query 不能为空");
        }

        if (collection == null || collection.isBlank()) {
            String availableKbs = validCollections.stream().collect(Collectors.joining("、"));
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 collection 不能为空，可用知识库：" + availableKbs);
        }

        if (!validCollections.contains(collection)) {
            String availableKbs = validCollections.stream().collect(Collectors.joining("、"));
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM",
                    "无效的知识库: " + collection + "。可用知识库：" + availableKbs);
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