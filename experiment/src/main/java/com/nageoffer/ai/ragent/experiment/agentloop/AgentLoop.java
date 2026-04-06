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

package com.nageoffer.ai.ragent.experiment.agentloop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mini Agent Loop — 模型驱动的 while(true) 循环
 *
 * <p>核心逻辑（从 CC 源码提炼）：</p>
 * <pre>
 * while (true) {
 *     response = callLLM(messages, tools)
 *     toolUses = extractToolUses(response)
 *     if (toolUses.isEmpty()) return response   // 模型不调工具 = 终止
 *     if (turnCount > maxTurns) return "超限"    // 护栏
 *     results = executeTools(toolUses)           // 执行工具
 *     messages.add(response)                     // 拼回历史
 *     messages.add(results)                      // 拼回历史
 *     turnCount++
 * }
 * </pre>
 *
 * <p>这是 Agent Loop 的本质：循环 → 调模型 → 检查 tool_use → 执行工具 → 拼结果 → 下一轮。
 * 所有复杂的代码（权限、压缩、Hook、错误恢复）都是为了驯服这个简单循环的概率性。</p>
 */
@Slf4j
public class AgentLoop {

    private final ChatClient llmClient;
    private final AIModelProperties.ProviderConfig providerConfig;
    private final AIModelProperties.ModelCandidate modelCandidate;
    private final Map<String, Tool> tools;
    private final int maxTurns;
    private final String systemPrompt;  // 新增：System Prompt
    private final Gson gson = new Gson();

    /**
     * 创建 AgentLoop 实例
     *
     * @param llmClient      LLM 客户端（用于调用百炼 API）
     * @param providerConfig 提供商配置（API Key 等）
     * @param modelCandidate 模型配置
     * @param tools          注册的工具集合
     * @param maxTurns       最大轮次限制（护栏）
     */
    public AgentLoop(
            ChatClient llmClient,
            AIModelProperties.ProviderConfig providerConfig,
            AIModelProperties.ModelCandidate modelCandidate,
            Map<String, Tool> tools,
            int maxTurns) {
        this(llmClient, providerConfig, modelCandidate, tools, maxTurns, null);
    }

    /**
     * 创建 AgentLoop 实例（带 System Prompt）
     *
     * @param llmClient      LLM 客户端（用于调用百炼 API）
     * @param providerConfig 提供商配置（API Key 等）
     * @param modelCandidate 模型配置
     * @param tools          注册的工具集合
     * @param maxTurns       最大轮次限制（护栏）
     * @param systemPrompt   System Prompt（可选）
     */
    public AgentLoop(
            ChatClient llmClient,
            AIModelProperties.ProviderConfig providerConfig,
            AIModelProperties.ModelCandidate modelCandidate,
            Map<String, Tool> tools,
            int maxTurns,
            String systemPrompt) {
        this.llmClient = llmClient;
        this.providerConfig = providerConfig;
        this.modelCandidate = modelCandidate;
        this.tools = tools;
        this.maxTurns = maxTurns;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 运行 Agent Loop
     *
     * @param userQuery 用户问题
     * @return 执行结果
     */
    public AgentLoopResult run(String userQuery) {
        log.info("========== Agent Loop 启动 ==========");
        log.info("用户问题: {}", userQuery);
        log.info("注册工具: {}", tools.keySet());
        log.info("最大轮次: {}", maxTurns);
        if (systemPrompt != null) {
            log.info("System Prompt: {}", systemPrompt.length() > 100
                    ? systemPrompt.substring(0, 100) + "..." : systemPrompt);
        }

        // 消息历史
        List<JsonObject> messages = new ArrayList<>();
        // 工具调用历史
        List<AgentLoopResult.ToolCallRecord> toolCallHistory = new ArrayList<>();
        // 轮次计数
        int turnCount = 0;

        try {
            // 添加 System Prompt（如果有）
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(createSystemMessage(systemPrompt));
            }

            // 添加用户消息
            messages.add(createUserMessage(userQuery));

            // 主循环
            while (true) {
                turnCount++;
                log.info("\n---------- Turn {} ----------", turnCount);

                // 护栏：检查最大轮次
                if (turnCount > maxTurns) {
                    log.warn("达到最大轮次限制: {}", maxTurns);
                    return AgentLoopResult.maxTurnsReached(turnCount, toolCallHistory);
                }

                // 1. 调用 LLM（带 tools 参数）
                log.debug("调用 LLM...");
                String response = callLLMWithTools(messages);
                log.info("LLM 原始响应:\n{}", prettyJson(response));

                // 解析响应
                JsonObject responseJson = gson.fromJson(response, JsonObject.class);
                JsonObject message = extractMessage(responseJson);

                // 2. 检查是否有 tool_calls
                List<ToolCallInfo> toolCalls = extractToolCalls(message);

                if (toolCalls.isEmpty()) {
                    // 没有工具调用 = 模型返回最终回答
                    String finalContent = extractContent(message);
                    log.info("模型返回最终回答: {}", finalContent);
                    log.info("========== Agent Loop 完成 ==========");
                    return AgentLoopResult.completed(finalContent, turnCount, toolCallHistory);
                }

                // 3. 有工具调用 → 执行工具
                log.info("模型请求调用 {} 个工具", toolCalls.size());

                // 将 assistant 消息添加到历史
                messages.add(message);

                // 执行每个工具调用
                for (ToolCallInfo tc : toolCalls) {
                    log.info("执行工具: {}({})", tc.toolName, tc.arguments);

                    Tool tool = tools.get(tc.toolName);
                    ToolResult result;

                    if (tool == null) {
                        log.warn("工具不存在: {}", tc.toolName);
                        result = ToolResult.error(tc.toolName, "工具不存在: " + tc.toolName);
                        result.setToolCallId(tc.toolCallId);
                    } else {
                        try {
                            // 解析参数
                            Map<String, Object> input = parseArguments(tc.arguments);
                            result = tool.execute(input);
                            result.setToolCallId(tc.toolCallId);
                        } catch (Exception e) {
                            log.error("工具执行失败: {}", e.getMessage(), e);
                            result = ToolResult.error(tc.toolName, e.getMessage());
                            result.setToolCallId(tc.toolCallId);
                        }
                    }

                    log.info("工具结果: {} (isError={})", result.getContent(), result.isError());

                    // 记录历史
                    toolCallHistory.add(AgentLoopResult.ToolCallRecord.builder()
                            .turn(turnCount)
                            .toolName(tc.toolName)
                            .toolCallId(tc.toolCallId)
                            .input(tc.arguments)
                            .output(result.getContent())
                            .success(!result.isError())
                            .build());

                    // 4. 将工具结果拼回消息历史
                    messages.add(createToolResultMessage(result));
                }
            }
        } catch (Exception e) {
            log.error("Agent Loop 执行失败", e);
            return AgentLoopResult.error(e.getMessage(), turnCount, toolCallHistory);
        }
    }

    /**
     * 调用 LLM（带 tools 参数）
     *
     * <p>百炼 API 使用 OpenAI 兼容格式，支持 tools 参数</p>
     */
    private String callLLMWithTools(List<JsonObject> messages) {
        // 构建请求体
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelCandidate.getModel());

        // 添加消息
        JsonArray messagesArray = new JsonArray();
        for (JsonObject msg : messages) {
            messagesArray.add(msg);
        }
        requestBody.add("messages", messagesArray);

        // 添加工具定义
        if (!tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : tools.values()) {
                JsonObject toolDef = gson.fromJson(tool.toFunctionDefinition(), JsonObject.class);
                toolsArray.add(toolDef);
            }
            requestBody.add("tools", toolsArray);
        }

        log.debug("请求体:\n{}", prettyJson(requestBody.toString()));

        // 使用现有的 ChatClient 调用
        // 注意：这里需要扩展 ChatClient 接口支持自定义请求体
        // 暂时使用简化的 HTTP 调用
        return callBaiLianAPI(requestBody);
    }

    /**
     * 直接调用百炼 API（绕过 ChatClient 接口限制）
     */
    private String callBaiLianAPI(JsonObject requestBody) {
        try {
            // 设置更长的超时时间（连接10秒，读写60秒）
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String url = providerConfig.getUrl() + "/compatible-mode/v1/chat/completions";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .post(okhttp3.RequestBody.create(
                            requestBody.toString(),
                            okhttp3.MediaType.parse("application/json")))
                    .addHeader("Authorization", "Bearer " + providerConfig.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    throw new RuntimeException("API 调用失败: HTTP " + response.code() + " - " + body);
                }
                return response.body().string();
            }
        } catch (Exception e) {
            throw new RuntimeException("调用百炼 API 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 辅助方法 ====================

    private JsonObject createSystemMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "system");
        msg.addProperty("content", content);
        return msg;
    }

    private JsonObject createUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", content);
        return msg;
    }

    private JsonObject extractMessage(JsonObject responseJson) {
        return responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message");
    }

    private String extractContent(JsonObject message) {
        if (message.has("content") && !message.get("content").isJsonNull()) {
            return message.get("content").getAsString();
        }
        return "";
    }

    /**
     * 提取工具调用信息
     *
     * <p>OpenAI 格式的 tool_calls 结构：</p>
     * <pre>
     * "tool_calls": [
     *   {
     *     "id": "call_xxx",
     *     "type": "function",
     *     "function": {
     *       "name": "echo",
     *       "arguments": "{\"message\": \"hello\"}"
     *     }
     *   }
     * ]
     * </pre>
     */
    private List<ToolCallInfo> extractToolCalls(JsonObject message) {
        List<ToolCallInfo> result = new ArrayList<>();

        if (!message.has("tool_calls") || message.get("tool_calls").isJsonNull()) {
            return result;
        }

        JsonArray toolCalls = message.getAsJsonArray("tool_calls");
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonObject tc = toolCalls.get(i).getAsJsonObject();
            String id = tc.get("id").getAsString();
            JsonObject function = tc.getAsJsonObject("function");
            String name = function.get("name").getAsString();
            String arguments = function.get("arguments").getAsString();

            result.add(new ToolCallInfo(id, name, arguments));
        }

        return result;
    }

    private Map<String, Object> parseArguments(String arguments) {
        Map<String, Object> result = new HashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return result;
        }
        JsonObject args = gson.fromJson(arguments, JsonObject.class);
        for (String key : args.keySet()) {
            JsonElement elem = args.get(key);
            if (elem.isJsonPrimitive()) {
                if (elem.getAsJsonPrimitive().isNumber()) {
                    result.put(key, elem.getAsNumber());
                } else if (elem.getAsJsonPrimitive().isBoolean()) {
                    result.put(key, elem.getAsBoolean());
                } else {
                    result.put(key, elem.getAsString());
                }
            } else {
                result.put(key, elem.toString());
            }
        }
        return result;
    }

    private JsonObject createToolResultMessage(ToolResult result) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("tool_call_id", result.getToolCallId());
        msg.addProperty("content", result.getContent());
        return msg;
    }

    private String prettyJson(String json) {
        try {
            return gson.toJson(gson.fromJson(json, JsonObject.class));
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 工具调用信息
     */
    private record ToolCallInfo(String toolCallId, String toolName, String arguments) {}
}