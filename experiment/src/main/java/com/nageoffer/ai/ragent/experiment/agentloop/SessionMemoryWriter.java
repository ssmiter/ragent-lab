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

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SessionMemory 写入器
 *
 * <p>职责：将 Autocompact 生成的摘要追加写入 session_memory.md 文件。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>为"未来"准备——当前会话中模型通过上下文摘要获取信息，session_memory.md 是为下一次会话准备的</li>
 *   <li>格式便于快速浏览——让人（或下一次会话的模型）能快速扫一眼就知道"讨论了什么、搜到了什么关键信息"</li>
 *   <li>追加写入——每次摘要生成后立即写入，防止中断丢失</li>
 * </ul>
 *
 * <p>文件格式示例：</p>
 * <pre>
 * # Session Memory - Run 3 (Autocompact)
 *
 * 生成时间: 2026-04-08 14:30:00
 * 模型: qwen-max (主模型) + qwen-turbo (摘要模型)
 *
 * ---
 *
 * ## Turn 1 - Ragent 系统的整体架构
 *
 * **摘要**: 核心发现: Ragent 采用 MCP 协议实现工具调用，意图树用于多轮对话管理...
 * **来源**: ragentdocs 知识库
 * **原文长度**: 89,097 字符 → 摘要 256 字符
 *
 * ---
 *
 * ## Turn 2 - 意图树工作原理
 *
 * **摘要**: 意图树是一种用于管理多轮对话意图的树状结构，根节点是用户初始意图...
 * **来源**: ragentdocs 知识库，主要来自 intent_tree.md
 * **原文长度**: 105,405 字符 → 摘要 268 字符
 * </pre>
 */
@Slf4j
public class SessionMemoryWriter {

    private final String experimentId;
    private final String memoryPath;
    private BufferedWriter writer;
    private boolean initialized = false;
    private int entryCount = 0;

    // 统计信息
    private long totalOriginalChars = 0;
    private long totalSummaryChars = 0;

    // 当前讨论主题（用于上下文）
    private String currentTopic = "";
    private int currentTurn = 0;

    // 已写入的摘要键集合（避免重复写入）
    private Set<String> writtenKeys = new HashSet<>();

    /**
     * 创建 SessionMemoryWriter
     *
     * @param experimentId 实验ID（用于文件命名）
     * @param baseDir      基础目录路径
     */
    public SessionMemoryWriter(String experimentId, String baseDir) {
        this.experimentId = experimentId;
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(java.time.LocalDateTime.now());
        this.memoryPath = baseDir + "/" + experimentId + "_session_memory_" + timestamp + ".md";
    }

    /**
     * 创建 SessionMemoryWriter（使用默认路径）
     */
    public SessionMemoryWriter(String experimentId) {
        this(experimentId, "experiment/results/experiment4");
    }

    /**
     * 初始化写入器
     * <p>创建文件并写入头部信息</p>
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            Path path = Paths.get(memoryPath);
            Files.createDirectories(path.getParent());
            writer = new BufferedWriter(new FileWriter(memoryPath, false));
            initialized = true;

            // 写入头部
            writeHeader();

            log.info("SessionMemoryWriter 初始化完成，文件路径: {}", memoryPath);
        } catch (IOException e) {
            log.error("SessionMemoryWriter 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 写入文件头部
     */
    private void writeHeader() {
        try {
            writer.write("# Session Memory - " + experimentId + "\n\n");
            writer.write("生成时间: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.LocalDateTime.now()) + "\n");
            writer.write("说明: 此文件记录了对话过程中 Autocompact 生成的检索结果摘要。\n");
            writer.write("用途: 为未来的会话提供'之前讨论中发现了什么关键信息'的参考。\n\n");
            writer.write("---\n\n");
            writer.flush();
        } catch (IOException e) {
            log.error("写入头部失败: {}", e.getMessage());
        }
    }

    /**
     * 设置当前讨论主题
     *
     * @param turn 当前轮次
     * @param topic 用户问题/主题
     */
    public void setTopic(int turn, String topic) {
        this.currentTurn = turn;
        this.currentTopic = topic;
    }

    /**
     * 追加摘要
     *
     * @param turn          轮次
     * @param userQuery     用户问题/主题（可为null）
     * @param summary       摘要内容
     * @param originalChars 原文长度
     */
    public synchronized void appendSummary(int turn, String userQuery, String summary, long originalChars) {
        if (!initialized) {
            init();
        }

        // 生成唯一键用于去重（基于 turn 和摘要内容哈希）
        String key = turn + "_" + (summary != null ? summary.hashCode() : 0);
        if (writtenKeys.contains(key)) {
            log.debug("SessionMemory: 跳过重复摘要 Turn {}", turn);
            return;
        }
        writtenKeys.add(key);

        try {
            entryCount++;

            // 写入条目分隔
            writer.write("## Turn " + turn + "\n\n");

            // 如果有主题信息，写入主题（优先使用传入的 userQuery）
            String topic = (userQuery != null && !userQuery.isEmpty()) ? userQuery : currentTopic;
            if (topic != null && !topic.isEmpty()) {
                writer.write("**主题**: " + topic + "\n\n");
            }

            // 写入摘要（去掉前后标识，只保留核心内容）
            String coreSummary = extractCoreSummary(summary);
            writer.write("**摘要**: " + coreSummary + "\n\n");

            // 写入压缩统计
            writer.write("**压缩统计**: 原文 " + originalChars + " 字符 → 摘要 " + summary.length() + " 字符\n\n");

            // 分隔线
            writer.write("---\n\n");
            writer.flush();

            // 更新统计
            totalOriginalChars += originalChars;
            totalSummaryChars += summary.length();

            log.debug("SessionMemory: 追加 Turn {} 摘要, {} 字符", turn, summary.length());
        } catch (IOException e) {
            log.error("追加摘要失败: {}", e.getMessage());
        }
    }

    /**
     * 追加摘要（兼容旧接口）
     *
     * @param turn          轮次
     * @param summary       摘要内容
     * @param originalChars 原文长度
     */
    public synchronized void appendSummary(int turn, String summary, long originalChars) {
        appendSummary(turn, null, summary, originalChars);
    }

    /**
     * 提取核心摘要内容（去掉标识行）
     */
    private String extractCoreSummary(String summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        // 去掉标记行（如 "[Autocompact-T1]", "[LLM 摘要 - Turn N]", "[自动摘要 - Turn N]"）
        String[] lines = summary.split("\n");
        StringBuilder core = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("[Autocompact-") && !line.startsWith("[LLM 摘要")
                    && !line.startsWith("[自动摘要") && !line.startsWith("完整记录已保存")) {
                core.append(line).append("\n");
            }
        }
        return core.toString().trim();
    }

    /**
     * 写入最终统计
     */
    public synchronized void writeFinalStats(Map<String, Object> autocompactStats) {
        if (!initialized || writer == null) {
            return;
        }
        try {
            writer.write("## 统计汇总\n\n");
            writer.write("- 总摘要条数: " + entryCount + "\n");
            writer.write("- 总原文字符: " + totalOriginalChars + "\n");
            writer.write("- 总摘要字符: " + totalSummaryChars + "\n");
            writer.write("- 压缩率: " + (totalOriginalChars > 0
                    ? String.format("%.1f%%", (1 - totalSummaryChars * 100.0 / totalOriginalChars) * 100)
                    : "N/A") + "\n");

            if (autocompactStats != null) {
                writer.write("\n### Autocompact 统计\n\n");
                writer.write("- LLM 调用次数: " + autocompactStats.get("total_llm_calls") + "\n");
                writer.write("- LLM 总耗时: " + autocompactStats.get("total_llm_time_ms") + " ms\n");
                writer.write("- 摘要模型: " + autocompactStats.get("summary_model") + "\n");
            }

            writer.write("\n---\n\n");
            writer.write("*文件生成于 " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.LocalDateTime.now()) + "*\n");
            writer.flush();
        } catch (IOException e) {
            log.error("写入最终统计失败: {}", e.getMessage());
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
            writer.close();
            initialized = false;
            log.info("SessionMemoryWriter 关闭完成，文件: {}", memoryPath);
            log.info("总计写入 {} 条摘要，原文 {} 字符 → 摘要 {} 字符",
                    entryCount, totalOriginalChars, totalSummaryChars);
        } catch (IOException e) {
            log.error("SessionMemoryWriter 关闭失败: {}", e.getMessage());
        }
    }

    /**
     * 获取 memory 文件路径
     */
    public String getMemoryPath() {
        return memoryPath;
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entry_count", entryCount);
        stats.put("total_original_chars", totalOriginalChars);
        stats.put("total_summary_chars", totalSummaryChars);
        stats.put("memory_path", memoryPath);
        return stats;
    }
}