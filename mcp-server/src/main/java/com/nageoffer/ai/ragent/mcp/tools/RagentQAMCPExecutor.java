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
public class RagentQAMCPExecutor implements MCPToolExecutor {

    private static final String RAGENT_QA_URL = "http://localhost:9090/api/ragent/rag/v3/chat";
    private static final String AUTH_TOKEN = "0ec3d3621baa40a1ba9629a887a6d4c2";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String TOOL_ID = "ragent_qa";

    @Override
    public MCPToolDefinition getToolDefinition() {
        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("向Ragent发送问题走完整RAG管道（重写→意图→检索→重排→生成），返回完整回答")
                .parameters(Map.of(
                        "query", MCPToolDefinition.ParameterDef.builder()
                                .description("要问的问题")
                                .required(true)
                                .build()
                ))
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        String query = request.getStringParameter("query");

        if (query == null || query.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAM", "参数 query 不能为空");
        }

        try {
            // GET 请求，参数编码
            String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            String url = RAGENT_QA_URL + "?question=" + encodedQuery;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", AUTH_TOKEN)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            // SSE 流式响应，读取所有 data: 行拼接
            HttpResponse<String> response = HTTP_CLIENT.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return MCPToolResponse.error(TOOL_ID, "SERVICE_ERROR", "问答服务异常，状态码: " + response.statusCode());
            }

            // 解析 SSE 格式，拼接所有 data: 行
            StringBuilder result = new StringBuilder();
            String body = response.body();
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    result.append(line.substring(5).trim());
                }
            }

            log.info("ragent_qa 执行完成, query={}, 响应长度={}", query, result.length());
            return MCPToolResponse.success(TOOL_ID, result.toString());

        } catch (Exception e) {
            log.error("ragent_qa 执行异常, query={}", query, e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "问答异常: " + e.getMessage());
        }
    }
}