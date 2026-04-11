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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Microcompact 处理器
 *
 * <p>职责：清理旧的 tool_result 内容，保留结构但丢弃细节。</p>
 *
 * <p>核心原则：</p>
 * <ul>
 *   <li>只改发送给模型的 messages 列表，不改 transcript 文件</li>
 *   <li>保留"搜过什么、从哪个库搜的、得分多少"——模型知道之前搜过这个主题</li>
 *   <li>丢弃 chunk 全文——这些是主要的膨胀源</li>
 * </ul>
 *
 * <p>Compact 摘要格式：</p>
 * <pre>
 * [已压缩的搜索结果 - Turn N]
 * 工具: knowledge_search_with_rerank
 * 知识库: ragentdocs
 * 查询: "Ragent 系统架构"
 * 返回: 5 个片段, 最高分 0.95, 来源: ragent_architecture.md, ragent_overview.md 等
 * 完整记录已保存至 transcript 文件
 * </pre>
 */
@Slf4j
public class MicrocompactProcessor {

    private static final Gson GSON = new Gson();

    /**
     * 默认年龄阈值：tool_result 超过 3 轮后被 compact
     */
    private int ageThreshold = 3;

    /**
     * 是否启用 Microcompact
     */
    private boolean enabled = true;

    /**
     * 统计信息
     */
    private int totalCompacted = 0;
    private long totalCharsSaved = 0;

    // 用于解析 knowledge_search_with_rerank 结果的模式
    private static final Pattern COLLECTION_PATTERN = Pattern.compile("searched collection: ([\\w]+)");
    private static final Pattern CHUNK_COUNT_PATTERN = Pattern.compile("Found (\\d+) relevant chunks");
    private static final Pattern SCORE_PATTERN = Pattern.compile("score: ([\\d.]+)");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("source: ([\\w.-]+)");
    private static final Pattern HIGHEST_SCORE_PATTERN = Pattern.compile("highest=([\\d.]+)");

    /**
     * 创建 MicrocompactProcessor（使用默认阈值）
     */
    public MicrocompactProcessor() {
        this(3);
    }

    /**
     * 创建 MicrocompactProcessor
     *
     * @param ageThreshold 年龄阈值（tool_result 超过多少轮后被 compact）
     */
    public MicrocompactProcessor(int ageThreshold) {
        this.ageThreshold = ageThreshold;
    }

    /**
     * 执行 Microcompact
     *
     * <p>扫描 messages 列表，将超龄的 tool_result 替换为摘要</p>
     *
     * @param messages    当前对话的 messages 列表（会被原地修改）
     * @param currentTurn 当前轮次
     * @param transcriptPath transcript 文件路径（用于告知模型完整记录位置）
     * @return 本次 compact 了多少条 tool_result
     */
    public int compact(List<JsonObject> messages, int currentTurn, String transcriptPath) {
        if (!enabled) {
            log.info("Microcompact 已禁用，跳过处理");
            return 0;
        }

        int compactedCount = 0;
        long charsSavedThisRound = 0;

        // 记录每个 tool_result 所属的轮次
        Map<String, Integer> toolResultTurnMap = buildToolResultTurnMap(messages);

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

                // 计算年龄
                int age = currentTurn - turnWhenCreated;
                if (age > ageThreshold) {
                    // 需要 compact
                    String originalContent = msg.has("content") && !msg.get("content").isJsonNull()
                            ? msg.get("content").getAsString() : "";
                    int originalLength = originalContent.length();

                    // 生成摘要
                    String summary = generateSummary(originalContent, turnWhenCreated, transcriptPath);

                    // 替换内容
                    msg.addProperty("content", summary);

                    compactedCount++;
                    charsSavedThisRound += originalLength - summary.length();

                    log.info("Compact tool_result (Turn {}, Age {}, ToolCallId {}): {} chars -> {} chars",
                            turnWhenCreated, age, toolCallId, originalLength, summary.length());
                }
            }
        }

        totalCompacted += compactedCount;
        totalCharsSaved += charsSavedThisRound;

        if (compactedCount > 0) {
            log.info("本轮 Microcompact 完成: compact {} 条 tool_result, 节省 {} 字符",
                    compactedCount, charsSavedThisRound);
        }

        return compactedCount;
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
     * 生成摘要
     *
     * <p>根据原始内容提取关键信息，生成紧凑的摘要</p>
     */
    private String generateSummary(String originalContent, int turn, String transcriptPath) {
        StringBuilder summary = new StringBuilder();
        summary.append("[已压缩的搜索结果 - Turn ").append(turn).append("]\n");

        // 尝试解析 knowledge_search_with_rerank 的结果格式
        if (originalContent.contains("Found") && originalContent.contains("relevant chunks")) {
            // 提取知识库名
            Matcher collectionMatcher = COLLECTION_PATTERN.matcher(originalContent);
            if (collectionMatcher.find()) {
                summary.append("知识库: ").append(collectionMatcher.group(1)).append("\n");
            }

            // 提取片段数量
            Matcher chunkCountMatcher = CHUNK_COUNT_PATTERN.matcher(originalContent);
            if (chunkCountMatcher.find()) {
                summary.append("返回: ").append(chunkCountMatcher.group(1)).append(" 个片段");
            }

            // 提取最高分
            Matcher highestScoreMatcher = HIGHEST_SCORE_PATTERN.matcher(originalContent);
            if (highestScoreMatcher.find()) {
                summary.append(", 最高分 ").append(highestScoreMatcher.group(1));
            }

            // 提取主要来源（取前几个）
            List<String> sources = new ArrayList<>();
            Matcher sourceMatcher = SOURCE_PATTERN.matcher(originalContent);
            int sourceCount = 0;
            while (sourceMatcher.find() && sourceCount < 3) {
                sources.add(sourceMatcher.group(1));
                sourceCount++;
            }
            if (!sources.isEmpty()) {
                summary.append(", 来源: ").append(String.join(", ", sources));
                if (sources.size() < extractTotalSources(originalContent)) {
                    summary.append(" 等");
                }
            }

            summary.append("\n");
        } else if (originalContent.contains("rewrite_query") || originalContent.contains("改写")) {
            // 查询重写工具的摘要
            summary.append("工具: rewrite_query\n");
            // 尝试提取改写后的查询
            if (originalContent.length() > 100) {
                summary.append("结果: ").append(originalContent.substring(0, 100)).append("...\n");
            } else {
                summary.append("结果: ").append(originalContent).append("\n");
            }
        } else {
            // 其他工具的通用摘要
            summary.append("类型: 未知工具\n");
            if (originalContent.length() > 50) {
                summary.append("摘要: ").append(originalContent.substring(0, 50)).append("...\n");
            } else {
                summary.append("内容: ").append(originalContent).append("\n");
            }
        }

        summary.append("完整记录已保存至 transcript 文件");
        if (transcriptPath != null && !transcriptPath.isEmpty()) {
            summary.append(" (").append(transcriptPath).append(")");
        }

        return summary.toString();
    }

    /**
     * 提取总来源数量
     */
    private int extractTotalSources(String content) {
        int count = 0;
        Matcher matcher = SOURCE_PATTERN.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 获取累计统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_compacted", totalCompacted);
        stats.put("total_chars_saved", totalCharsSaved);
        stats.put("age_threshold", ageThreshold);
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
     * 重置统计信息
     */
    public void resetStats() {
        totalCompacted = 0;
        totalCharsSaved = 0;
    }
}