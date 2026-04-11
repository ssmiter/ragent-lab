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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transcript 持久化写入器
 *
 * <p>职责：将 Agent Loop 的完整对话记录写入 JSONL 文件。</p>
 *
 * <p>核心原则：</p>
 * <ul>
 *   <li>完整记录，不做任何截断 — transcript 是"冷存储"</li>
 *   <li>每轮写入后立即 flush，防止异常中断丢失数据</li>
 *   <li>独立组件，即使出错也不影响主循环</li>
 * </ul>
 *
 * <p>JSONL 格式示例：</p>
 * <pre>
 * {"turn":1,"role":"system","content":"...","timestamp":"2026-04-07T14:05:00"}
 * {"turn":1,"role":"user","content":"...","timestamp":"2026-04-07T14:05:01"}
 * {"turn":1,"role":"assistant","content":"...","tool_calls":[...],"timestamp":"..."}
 * {"turn":1,"role":"tool","tool_call_id":"xxx","name":"xxx","content":"...","timestamp":"..."}
 * </pre>
 */
@Slf4j
public class TranscriptWriter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final String experimentId;
    private final String transcriptPath;
    private BufferedWriter writer;
    private boolean initialized = false;
    private int currentTurn = 0;

    // 统计信息
    private int totalMessages = 0;
    private long totalCharacters = 0;
    private long toolResultCharacters = 0;
    private long assistantCharacters = 0;
    private long userCharacters = 0;
    private long systemCharacters = 0;

    // Token 统计（如果 API 返回）
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int totalTokens = 0;

    /**
     * 创建 TranscriptWriter
     *
     * @param experimentId 实验ID（用于文件命名）
     * @param baseDir      基础目录路径
     */
    public TranscriptWriter(String experimentId, String baseDir) {
        this.experimentId = experimentId;
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now());
        this.transcriptPath = baseDir + "/" + experimentId + "_" + timestamp + ".jsonl";
    }

    /**
     * 创建 TranscriptWriter（使用默认路径）
     */
    public TranscriptWriter(String experimentId) {
        this(experimentId, "experiment/results/experiment4/transcripts");
    }

    /**
     * 初始化写入器
     * <p>创建文件并准备好写入</p>
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            Path path = Paths.get(transcriptPath);
            Files.createDirectories(path.getParent());
            writer = new BufferedWriter(new FileWriter(transcriptPath, false));
            initialized = true;
            log.info("TranscriptWriter 初始化完成，文件路径: {}", transcriptPath);
        } catch (IOException e) {
            log.error("TranscriptWriter 初始化失败: {}", e.getMessage());
            // 不抛出异常，保持组件独立性
        }
    }

    /**
     * 关闭写入器
     */
    public synchronized void close() {
        if (!initialized || writer == null) {
            return;
        }
        try {
            // 写入统计摘要行
            writeSummary();
            writer.close();
            initialized = false;
            log.info("TranscriptWriter 关闭完成，文件: {}", transcriptPath);
        } catch (IOException e) {
            log.error("TranscriptWriter 关闭失败: {}", e.getMessage());
        }
    }

    /**
     * 开始新一轮
     */
    public void startTurn(int turn) {
        this.currentTurn = turn;
    }

    /**
     * 写入 system message
     */
    public void writeSystemMessage(String content) {
        if (!initialized) {
            init();
        }
        Map<String, Object> record = new HashMap<>();
        record.put("turn", currentTurn);
        record.put("role", "system");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());

        writeRecord(record);
        systemCharacters += content.length();
        totalMessages++;
    }

    /**
     * 写入 user message
     */
    public void writeUserMessage(String content) {
        if (!initialized) {
            init();
        }
        Map<String, Object> record = new HashMap<>();
        record.put("turn", currentTurn);
        record.put("role", "user");
        record.put("content", content);
        record.put("timestamp", Instant.now().toString());

        writeRecord(record);
        userCharacters += content.length();
        totalCharacters += content.length();
        totalMessages++;
    }

    /**
     * 写入 assistant message（含 tool_calls）
     *
     * @param messageJson LLM 响应的 message JSON 对象
     */
    public void writeAssistantMessage(JsonObject messageJson) {
        if (!initialized) {
            init();
        }
        Map<String, Object> record = new HashMap<>();
        record.put("turn", currentTurn);
        record.put("role", "assistant");
        record.put("timestamp", Instant.now().toString());

        // 提取 content
        if (messageJson.has("content") && !messageJson.get("content").isJsonNull()) {
            String content = messageJson.get("content").getAsString();
            record.put("content", content);
            assistantCharacters += content.length();
            totalCharacters += content.length();
        } else {
            record.put("content", "");
        }

        // 提取 tool_calls（如果有）
        if (messageJson.has("tool_calls") && !messageJson.get("tool_calls").isJsonNull()) {
            JsonArray toolCalls = messageJson.getAsJsonArray("tool_calls");
            record.put("tool_calls", GSON.fromJson(toolCalls, List.class));
        }

        writeRecord(record);
        totalMessages++;
    }

    /**
     * 写入 tool result message
     *
     * @param toolCallId   工具调用 ID
     * @param toolName     工具名称
     * @param content      工具结果内容
     * @param isError      是否为错误结果
     */
    public void writeToolResultMessage(String toolCallId, String toolName, String content, boolean isError) {
        if (!initialized) {
            init();
        }
        Map<String, Object> record = new HashMap<>();
        record.put("turn", currentTurn);
        record.put("role", "tool");
        record.put("tool_call_id", toolCallId);
        record.put("name", toolName);
        record.put("content", content);
        record.put("is_error", isError);
        record.put("timestamp", Instant.now().toString());

        writeRecord(record);
        toolResultCharacters += content.length();
        totalCharacters += content.length();
        totalMessages++;
    }

    /**
     * 更新 token 统计（从 API 响应中提取）
     *
     * @param responseJson LLM API 响应 JSON
     */
    public void updateTokenStats(JsonObject responseJson) {
        if (responseJson.has("usage") && !responseJson.get("usage").isJsonNull()) {
            JsonObject usage = responseJson.getAsJsonObject("usage");
            if (usage.has("prompt_tokens")) {
                promptTokens += usage.get("prompt_tokens").getAsInt();
            }
            if (usage.has("completion_tokens")) {
                completionTokens += usage.get("completion_tokens").getAsInt();
            }
            if (usage.has("total_tokens")) {
                totalTokens += usage.get("total_tokens").getAsInt();
            }
        }
    }

    /**
     * 获取当前 messages 统计信息
     *
     * @param messages 当前消息列表
     * @return 统计信息字符串
     */
    public String getMessagesStats(List<JsonObject> messages) {
        // 实时统计当前 messages 列表
        long currentTotalChars = 0;
        long currentToolResultChars = 0;
        long currentAssistantChars = 0;
        long currentUserChars = 0;
        long currentSystemChars = 0;

        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            String content = msg.has("content") && !msg.get("content").isJsonNull()
                    ? msg.get("content").getAsString() : "";
            int charCount = content.length();

            currentTotalChars += charCount;
            switch (role) {
                case "tool" -> currentToolResultChars += charCount;
                case "assistant" -> currentAssistantChars += charCount;
                case "user" -> currentUserChars += charCount;
                case "system" -> currentSystemChars += charCount;
            }
        }

        double toolResultPercent = currentTotalChars > 0
                ? (currentToolResultChars * 100.0 / currentTotalChars) : 0;
        double assistantPercent = currentTotalChars > 0
                ? (currentAssistantChars * 100.0 / currentTotalChars) : 0;
        double userPercent = currentTotalChars > 0
                ? (currentUserChars * 100.0 / currentTotalChars) : 0;

        return String.format(
                "[Turn %d] messages 统计: 总条数=%d, 估算总字符数=%d,\n" +
                        "  其中 tool_result 字符数=%d (占比 %.1f%%),\n" +
                        "  其中 assistant 字符数=%d (占比 %.1f%%),\n" +
                        "  其中 user 字符数=%d (占比 %.1f%%)",
                currentTurn, messages.size(), currentTotalChars,
                currentToolResultChars, toolResultPercent,
                currentAssistantChars, assistantPercent,
                currentUserChars, userPercent
        );
    }

    /**
     * 写入单条记录
     */
    private void writeRecord(Map<String, Object> record) {
        if (!initialized || writer == null) {
            return;
        }
        try {
            String jsonLine = GSON.toJson(record);
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();  // 立即 flush，防止中断丢失
        } catch (IOException e) {
            log.error("TranscriptWriter 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 写入统计摘要行（在文件末尾）
     */
    private void writeSummary() {
        if (!initialized || writer == null) {
            return;
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("_type", "summary");
        summary.put("experiment_id", experimentId);
        summary.put("transcript_path", transcriptPath);
        summary.put("total_turns", currentTurn);
        summary.put("total_messages", totalMessages);
        summary.put("total_characters", totalCharacters);
        summary.put("tool_result_characters", toolResultCharacters);
        summary.put("assistant_characters", assistantCharacters);
        summary.put("user_characters", userCharacters);
        summary.put("system_characters", systemCharacters);
        summary.put("prompt_tokens", promptTokens);
        summary.put("completion_tokens", completionTokens);
        summary.put("total_tokens", totalTokens);
        summary.put("timestamp", Instant.now().toString());

        try {
            String jsonLine = GSON.toJson(summary);
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("TranscriptWriter 摘要写入失败: {}", e.getMessage());
        }
    }

    /**
     * 获取 transcript 文件路径
     */
    public String getTranscriptPath() {
        return transcriptPath;
    }

    /**
     * 获取累计统计信息
     */
    public Map<String, Object> getCumulativeStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_messages", totalMessages);
        stats.put("total_characters", totalCharacters);
        stats.put("tool_result_characters", toolResultCharacters);
        stats.put("assistant_characters", assistantCharacters);
        stats.put("user_characters", userCharacters);
        stats.put("system_characters", systemCharacters);
        stats.put("prompt_tokens", promptTokens);
        stats.put("completion_tokens", completionTokens);
        stats.put("total_tokens", totalTokens);
        return stats;
    }
}