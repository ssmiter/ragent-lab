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
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Autocompact 处理器
 *
 * <p>职责：用轻量 LLM 为旧 tool_result 生成有信息量的摘要。</p>
 *
 * <p>与 Microcompact 的核心区别：</p>
 * <ul>
 *   <li>Microcompact：纯代码逻辑，生成 68 字符索引（"搜过什么库、得分多少"）</li>
 *   <li>Autocompact：调 LLM，生成 200-300 字符摘要（"搜到了什么关键发现"）</li>
 * </ul>
 *
 * <p>摘要设计原则：</p>
 * <ul>
 *   <li>不是原文缩写，而是经过理解后的要点</li>
 *   <li>回答"这次检索最重要的发现是什么、来自哪里、和讨论有什么关系"</li>
 *   <li>保留可追溯性（知识库名、文档来源）</li>
 * </ul>
 *
 * <p>摘要格式示例：</p>
 * <pre>
 * [LLM 摘要 - Turn 2]
 * 核心发现: Ragent 采用 MCP 协议实现工具调用，意图树用于多轮对话管理。
 * 来源: ragentdocs 知识库，主要来自 ragent_architecture.md 和 mcp_tools.md。
 * 关联: 这些架构设计是后续讨论记忆管理的基础。
 * </pre>
 */
@Slf4j
public class AutocompactProcessor {

    private static final Gson GSON = new Gson();

    /**
     * 默认年龄阈值：tool_result 超过 3 轮后被 compact
     */
    private int ageThreshold = 3;

    /**
     * 是否启用 Autocompact
     */
    private boolean enabled = true;

    /**
     * 摘要生成用的轻量模型（默认 qwen-turbo）
     */
    private String summaryModel = "qwen-turbo";

    /**
     * API Key
     */
    private String apiKey;

    /**
     * API URL
     */
    private String apiUrl = "https://dashscope.aliyuncs.com";

    /**
     * 摘要标记前缀（用于检测已压缩的内容，避免重复压缩）
     */
    private static final String AUTOCOMPACT_MARKER = "[Autocompact-";

    /**
     * 已处理的 toolCallId 集合（避免重复处理）
     */
    private Set<String> processedToolCallIds = new HashSet<>();

    /**
     * 统计信息
     */
    private int totalCompacted = 0;
    private long totalCharsSaved = 0;
    private int totalLLMCalls = 0;
    private long totalLLMTimeMs = 0;
    private int skippedCount = 0;  // 因已压缩而跳过的数量

    /**
     * SessionMemory 写入器（可选）
     */
    private SessionMemoryWriter sessionMemoryWriter;

    /**
     * 摘要生成的 Prompt 模板（包含用户问题上下文）
     */
    private static final String SUMMARY_PROMPT_TEMPLATE_WITH_CONTEXT = """
你是一个信息提取专家。请为以下检索结果生成一段简洁的摘要（控制在 200-300 字符）。

用户原始问题：%s

要求：
1. 提取"这次检索最重要的发现是什么"
2. 说明"信息来自哪个知识库、哪些文档"
3. 说明这些发现与用户问题的关系（如何帮助回答问题）
4. 不要缩写原文，而是提取理解后的要点
5. 摘要应该让后续轮次的模型能快速了解"之前讨论中发现了什么关键信息"

检索结果原文：
%s

请直接输出摘要，不要有任何前言或解释。
""";

    /**
     * 摘要生成的 Prompt 模板（无上下文）
     */
    private static final String SUMMARY_PROMPT_TEMPLATE = """
你是一个信息提取专家。请为以下检索结果生成一段简洁的摘要（控制在 200-300 字符）。

要求：
1. 提取"这次检索最重要的发现是什么"
2. 说明"信息来自哪个知识库、哪些文档"
3. 如果能推断出"这些信息与当前讨论主题的关系"，简要说明
4. 不要缩写原文，而是提取理解后的要点
5. 摘要应该让后续轮次的模型能快速了解"之前讨论中发现了什么关键信息"

检索结果原文：
%s

请直接输出摘要，不要有任何前言或解释。
""";

    /**
     * 创建 AutocompactProcessor（使用默认阈值）
     *
     * @param apiKey API Key
     */
    public AutocompactProcessor(String apiKey) {
        this(3, apiKey);
    }

    /**
     * 创建 AutocompactProcessor
     *
     * @param ageThreshold 年龄阈值（tool_result 超过多少轮后被 compact）
     * @param apiKey       API Key
     */
    public AutocompactProcessor(int ageThreshold, String apiKey) {
        this.ageThreshold = ageThreshold;
        this.apiKey = apiKey;
    }

    /**
     * 设置 SessionMemory 写入器
     */
    public void setSessionMemoryWriter(SessionMemoryWriter writer) {
        this.sessionMemoryWriter = writer;
    }

    /**
     * 执行 Autocompact
     *
     * <p>扫描 messages 列表，将超龄的 tool_result 替换为 LLM 生成的摘要</p>
     *
     * @param messages    当前对话的 messages 列表（会被原地修改）
     * @param currentTurn 当前轮次
     * @param transcriptPath transcript 文件路径（用于告知模型完整记录位置）
     * @param userQuery   当前用户问题（用于生成更相关的摘要，可为null）
     * @return 本次 compact 了多少条 tool_result
     */
    public int compact(List<JsonObject> messages, int currentTurn, String transcriptPath, String userQuery) {
        if (!enabled) {
            log.info("Autocompact 已禁用，跳过处理");
            return 0;
        }

        int compactedCount = 0;
        long charsSavedThisRound = 0;

        // 记录每个 tool_result 所属的轮次和用户问题
        Map<String, Integer> toolResultTurnMap = buildToolResultTurnMap(messages);
        Map<Integer, String> turnUserQueryMap = buildTurnUserQueryMap(messages);

        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i);
            String role = msg.has("role") ? msg.get("role").getAsString() : "";

            if ("tool".equals(role)) {
                String toolCallId = msg.has("tool_call_id")
                        ? msg.get("tool_call_id").getAsString() : "";

                // 获取该 tool_result 所属的轮次
                Integer turnWhenCreated = toolResultTurnMap.get(toolCallId);
                if (turnWhenCreated == null) {
                    continue;  // 无法确定轮次，跳过
                }

                // 检查是否已处理过
                if (processedToolCallIds.contains(toolCallId)) {
                    skippedCount++;
                    continue;
                }

                // 计算年龄
                int age = currentTurn - turnWhenCreated;
                if (age > ageThreshold) {
                    // 需要 compact
                    String originalContent = msg.has("content") && !msg.get("content").isJsonNull()
                            ? msg.get("content").getAsString() : "";

                    // 检查内容是否已被压缩（包含标记）
                    if (originalContent.contains(AUTOCOMPACT_MARKER) ||
                        originalContent.contains("[LLM 摘要") ||
                        originalContent.contains("[自动摘要")) {
                        skippedCount++;
                        processedToolCallIds.add(toolCallId);  // 标记为已处理
                        log.debug("跳过已压缩的 tool_result (Turn {}, ToolCallId {})", turnWhenCreated, toolCallId);
                        continue;
                    }

                    int originalLength = originalContent.length();

                    // 获取该轮次的用户问题作为上下文
                    String contextQuery = turnUserQueryMap.getOrDefault(turnWhenCreated, userQuery);

                    // 调用 LLM 生成摘要
                    long llmStartTime = System.currentTimeMillis();
                    String summary = generateSummaryWithLLM(originalContent, turnWhenCreated, transcriptPath, contextQuery);
                    long llmEndTime = System.currentTimeMillis();
                    long llmTimeMs = llmEndTime - llmStartTime;

                    totalLLMCalls++;
                    totalLLMTimeMs += llmTimeMs;

                    // 替换内容
                    msg.addProperty("content", summary);

                    // 标记为已处理
                    processedToolCallIds.add(toolCallId);

                    compactedCount++;
                    charsSavedThisRound += originalLength - summary.length();

                    log.info("Autocompact tool_result (Turn {}, Age {}, ToolCallId {}): {} chars -> {} chars, LLM耗时 {}ms",
                            turnWhenCreated, age, toolCallId, originalLength, summary.length(), llmTimeMs);

                    // 写入 SessionMemory（只写入一次）
                    if (sessionMemoryWriter != null) {
                        sessionMemoryWriter.appendSummary(turnWhenCreated, contextQuery, summary, originalLength);
                    }
                }
            }
        }

        totalCompacted += compactedCount;
        totalCharsSaved += charsSavedThisRound;

        if (compactedCount > 0 || skippedCount > 0) {
            log.info("本轮 Autocompact 完成: compact {} 条, 跳过 {} 条已压缩, 节省 {} 字符, LLM调用 {} 次, 耗时 {}ms",
                    compactedCount, skippedCount, charsSavedThisRound, compactedCount, totalLLMTimeMs);
        }

        return compactedCount;
    }

    /**
     * 执行 Autocompact（兼容旧接口）
     */
    public int compact(List<JsonObject> messages, int currentTurn, String transcriptPath) {
        return compact(messages, currentTurn, transcriptPath, null);
    }

    /**
     * 构建轮次与用户问题的映射
     */
    private Map<Integer, String> buildTurnUserQueryMap(List<JsonObject> messages) {
        Map<Integer, String> map = new HashMap<>();
        int currentTurn = 0;

        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";

            if ("user".equals(role)) {
                currentTurn++;
                String content = msg.has("content") && !msg.get("content").isJsonNull()
                        ? msg.get("content").getAsString() : "";
                map.put(currentTurn, content);
            }
        }

        return map;
    }

    /**
     * 构建 tool_result 与轮次的映射
     *
     * <p>通过分析 assistant 消息中的 tool_calls 来确定每个 tool_result 的所属轮次</p>
     */
    private Map<String, Integer> buildToolResultTurnMap(List<JsonObject> messages) {
        Map<String, Integer> map = new HashMap<>();
        int currentTurn = 0;

        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";

            if ("user".equals(role)) {
                // user 消息通常标志着新一轮的开始
                currentTurn++;
            } else if ("assistant".equals(role)) {
                // assistant 消息中的 tool_calls 属于当前轮次
                if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                    JsonArray toolCalls = msg.getAsJsonArray("tool_calls");
                    for (JsonElement elem : toolCalls) {
                        JsonObject tc = elem.getAsJsonObject();
                        String id = tc.has("id") ? tc.get("id").getAsString() : "";
                        if (!id.isEmpty()) {
                            map.put(id, currentTurn);
                        }
                    }
                }
            }
        }

        return map;
    }

    /**
     * 调用 LLM 生成摘要
     *
     * <p>使用轻量模型（qwen-turbo）为检索结果生成有信息量的摘要</p>
     */
    private String generateSummaryWithLLM(String originalContent, int turn, String transcriptPath, String userQuery) {
        try {
            // 截断过长内容（避免发送过大的内容给轻量模型）
            String contentForLLM = originalContent.length() > 8000
                    ? originalContent.substring(0, 8000) + "...[截断]"
                    : originalContent;

            // 根据是否有用户问题选择模板
            String prompt;
            if (userQuery != null && !userQuery.isEmpty()) {
                prompt = SUMMARY_PROMPT_TEMPLATE_WITH_CONTEXT.formatted(userQuery, contentForLLM);
            } else {
                prompt = SUMMARY_PROMPT_TEMPLATE.formatted(contentForLLM);
            }

            // 调用百炼 API
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", summaryModel);
            requestBody.addProperty("temperature", 0.3);  // 低温度，保证摘要一致性

            JsonArray messagesArray = new JsonArray();
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", prompt);
            messagesArray.add(userMsg);
            requestBody.add("messages", messagesArray);

            // 调用 API
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String url = apiUrl + "/compatible-mode/v1/chat/completions";

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .post(okhttp3.RequestBody.create(
                            requestBody.toString(),
                            okhttp3.MediaType.parse("application/json")))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    log.warn("LLM 摘要生成失败: HTTP {} - {}", response.code(), body);
                    // 失败时使用 fallback 摘要
                    return generateFallbackSummary(originalContent, turn, transcriptPath);
                }

                String responseStr = response.body().string();
                JsonObject responseJson = GSON.fromJson(responseStr, JsonObject.class);

                String llmSummary = responseJson.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                // 格式化最终摘要
                return formatFinalSummary(llmSummary, turn, transcriptPath);
            }
        } catch (Exception e) {
            log.warn("LLM 摘要生成异常: {}", e.getMessage());
            // 异常时使用 fallback 摘要
            return generateFallbackSummary(originalContent, turn, transcriptPath);
        }
    }

    /**
     * 格式化最终摘要
     *
     * <p>在 LLM 生成的摘要前添加标识（用于后续检测），后添加位置说明</p>
     */
    private String formatFinalSummary(String llmSummary, int turn, String transcriptPath) {
        StringBuilder sb = new StringBuilder();
        // 使用 AUTOCOMPACT_MARKER 格式，便于检测已压缩内容
        sb.append("[Autocompact-T").append(turn).append("]\n");
        sb.append(llmSummary);

        // 控制长度（LLM 摘要 + 标识不超过 350 字符）
        if (sb.length() > 350) {
            String truncated = sb.substring(0, 340) + "...";
            sb.setLength(0);
            sb.append(truncated);
        }

        sb.append("\n完整记录已保存至 transcript 文件");
        if (transcriptPath != null && !transcriptPath.isEmpty()) {
            sb.append(" (").append(transcriptPath).append(")");
        }

        return sb.toString();
    }

    /**
     * Fallback 摘要生成（当 LLM 调用失败时）
     *
     * <p>使用类似 Microcompact 的简单摘要，保证有内容可用</p>
     */
    private String generateFallbackSummary(String originalContent, int turn, String transcriptPath) {
        StringBuilder summary = new StringBuilder();
        // 使用 AUTOCOMPACT_MARKER 格式
        summary.append("[Autocompact-T").append(turn).append("-Fallback]\n");

        // 提取关键信息
        if (originalContent.contains("Found") && originalContent.contains("relevant chunks")) {
            // 尝试提取关键片段
            String firstChunk = extractFirstChunkSummary(originalContent);
            summary.append("关键发现: ").append(firstChunk).append("\n");
        } else {
            // 其他情况取前 150 字符
            String truncated = originalContent.length() > 150
                    ? originalContent.substring(0, 150) + "..."
                    : originalContent;
            summary.append("内容摘要: ").append(truncated).append("\n");
        }

        summary.append("完整记录已保存至 transcript 文件");
        if (transcriptPath != null && !transcriptPath.isEmpty()) {
            summary.append(" (").append(transcriptPath).append(")");
        }

        return summary.toString();
    }

    /**
     * 提取第一个 chunk 的摘要
     */
    private String extractFirstChunkSummary(String content) {
        // 尝试提取第一个 chunk 的标题或前几行
        if (content.contains("```") || content.contains("#")) {
            int start = content.indexOf("#");
            if (start >= 0) {
                int end = content.indexOf("\n", start);
                if (end > start && end - start < 100) {
                    return content.substring(start, end);
                }
            }
        }
        return "检索到相关文档片段";
    }

    /**
     * 获取累计统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_compacted", totalCompacted);
        stats.put("total_chars_saved", totalCharsSaved);
        stats.put("total_llm_calls", totalLLMCalls);
        stats.put("total_llm_time_ms", totalLLMTimeMs);
        stats.put("skipped_count", skippedCount);
        stats.put("age_threshold", ageThreshold);
        stats.put("summary_model", summaryModel);
        stats.put("enabled", enabled);
        return stats;
    }

    /**
     * 设置年龄阈值
     */
    public void setAgeThreshold(int ageThreshold) {
        this.ageThreshold = ageThreshold;
    }

    /**
     * 获取年龄阈值
     */
    public int getAgeThreshold() {
        return ageThreshold;
    }

    /**
     * 设置是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置摘要模型
     */
    public void setSummaryModel(String summaryModel) {
        this.summaryModel = summaryModel;
    }

    /**
     * 获取摘要模型
     */
    public String getSummaryModel() {
        return summaryModel;
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        totalCompacted = 0;
        totalCharsSaved = 0;
        totalLLMCalls = 0;
        totalLLMTimeMs = 0;
        skippedCount = 0;
        processedToolCallIds.clear();
    }
}