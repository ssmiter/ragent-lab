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
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.mcp.tools.KnowledgeSearchWithRerankMCPExecutor;
import com.nageoffer.ai.ragent.mcp.tools.QueryRewriteMCPExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Loop 实验 4 — Transcript 持久化 + Compact 系列对比
 *
 * <p>核心目标：</p>
 * <ul>
 *   <li>长对话场景下，上下文膨胀的实际速度是多少？</li>
 *   <li>清理旧检索结果后，模型行为是否受影响？</li>
 *   <li>什么样的 compact 策略最适合检索场景？</li>
 * </ul>
 *
 * <p>实验设计：</p>
 * <ul>
 *   <li>Run 1 — 无 Compact（baseline）：关闭任何 Compact，记录膨胀曲线</li>
 *   <li>Run 2 — Microcompact：纯代码逻辑，生成 68 字符索引</li>
 *   <li>Run 3 — Autocompact：调 LLM，生成 200-300 字符摘要</li>
 * </ul>
 */
@Slf4j
public class AgentLoopExperiment4 {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * 知识库助手的 System Prompt（支持多知识库、多轮追问）
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库研究助手，帮助用户深入探索知识库内容。

# 知识库说明

你可以搜索三个不同的知识库：

1. **ragentdocs** — Ragent 项目文档
   - 内容：系统架构、RAG 实现、MCP 工具、意图树、分块策略、前端技术栈等
   - 适用：关于 Ragent 系统本身的问题

2. **ssddocs** — SSD 理论文档
   - 内容：状态空间模型（State Space Model）、SSD 原论文理论、数学推导
   - 适用：关于 SSD 原始理论和方法的问题

3. **dualssddocs** — DualSSD 创新文档
   - 内容：DualSSD 的创新设计、块级别状态管理、对 SSD 的改进
   - 适用：关于 DualSSD 具体创新和实现的问题

# 你的工具

1. **rewrite_query** — 将模糊或口语化的查询改写为更精确的检索查询
   - 当用户问题模糊、口语化时使用
   - 当用户问题已经清晰具体时，跳过重写

2. **knowledge_search_with_rerank** — 搜索指定知识库
   - 每次调用必须指定 collection 参数
   - 返回 Rerank 分数（通常 0.7-0.95）

# 多轮对话策略

用户可能会围绕一个主题连续追问，你需要：
1. 记住之前讨论的主题，在后续追问中保持上下文连贯
2. 当用户切换话题时，识别并开始新的检索方向
3. 在回答中适当引用之前的讨论内容
4. 如果发现信息不足，主动搜索补充

# 回答格式

每次回答时：
1. 先给出直接回答
2. 说明信息来源（哪个知识库、哪个文档）
3. 如果是多来源综合，说明每个来源的贡献
4. 如果有不确定的地方，明确标注

# 语言
始终使用中文回答用户问题。
""";

    /**
     * 15轮对话脚本（多轮追问场景）
     */
    private static final List<String> CONVERSATION_SCRIPT = List.of(
            "Ragent 系统的整体架构是什么？",
            "其中的意图树是怎么工作的？",
            "SSD 模型的基本原理是什么？",
            "DualSSD 在 SSD 基础上做了什么改进？",
            "块级别状态管理具体是怎么实现的？",
            "回到 Ragent，它的记忆管理是怎么做的？",
            "对话历史是怎么存储的？",
            "SSD 的状态压缩思想能不能用于 Ragent 的记忆管理？",
            "具体怎么把块级状态应用到对话记忆中？",
            "Ragent 的检索用的什么模型？",
            "Rerank 的具体流程是什么？",
            "SSD 和 Transformer 的注意力机制有什么区别？",
            "DualSSD 用了什么替代注意力的方案？",
            "总结一下 Ragent、SSD、DualSSD 三个项目的关系",
            "基于以上讨论，你觉得 Ragent 最值得改进的是什么？"
    );

    // API 配置
    private final AIModelProperties.ProviderConfig providerConfig;
    private final AIModelProperties.ModelCandidate modelCandidate;
    private final Map<String, Tool> tools;

    // Transcript 和 Compact 处理器
    private final TranscriptWriter transcriptWriter;
    private final MicrocompactProcessor microcompactProcessor;
    private final AutocompactProcessor autocompactProcessor;
    private final SessionMemoryWriter sessionMemoryWriter;

    // 实验参数
    private final String experimentId;
    private final String compactMode;  // "baseline", "microcompact", "autocompact"
    private final int maxTurnsPerQuestion;
    private final String modelName;

    /**
     * 创建实验实例
     *
     * @param apiKey           API Key
     * @param experimentId     实验ID（用于区分 Run）
     * @param compactMode      Compact 模式: "baseline", "microcompact", "autocompact"
     */
    public AgentLoopExperiment4(String apiKey, String experimentId, String compactMode) {
        this(apiKey, experimentId, compactMode, "qwen-max");
    }

    /**
     * 创建实验实例（指定模型）
     *
     * @param apiKey           API Key
     * @param experimentId     实验ID（用于区分 Run）
     * @param compactMode      Compact 模式: "baseline", "microcompact", "autocompact"
     * @param modelName        模型名称
     */
    public AgentLoopExperiment4(String apiKey, String experimentId, String compactMode, String modelName) {
        this.experimentId = experimentId;
        this.compactMode = compactMode;
        this.maxTurnsPerQuestion = 5;  // 每个问题最多 5 轮 Agent Loop
        this.modelName = modelName;

        // 配置百炼 API
        this.providerConfig = new AIModelProperties.ProviderConfig();
        this.providerConfig.setUrl("https://dashscope.aliyuncs.com");
        this.providerConfig.setApiKey(apiKey);

        this.modelCandidate = new AIModelProperties.ModelCandidate();
        this.modelCandidate.setModel(modelName);

        // 注册工具
        this.tools = new HashMap<>();
        QueryRewriteMCPExecutor rewriteExecutor = new QueryRewriteMCPExecutor();
        McpToolAdapter rewriteTool = new McpToolAdapter(rewriteExecutor);
        this.tools.put(rewriteTool.getName(), rewriteTool);

        KnowledgeSearchWithRerankMCPExecutor searchExecutor = new KnowledgeSearchWithRerankMCPExecutor();
        McpToolAdapter searchTool = new McpToolAdapter(searchExecutor);
        this.tools.put(searchTool.getName(), searchTool);

        // Transcript 写入器
        this.transcriptWriter = new TranscriptWriter(experimentId);

        // Compact 处理器（根据模式选择）
        this.microcompactProcessor = new MicrocompactProcessor(3);
        this.microcompactProcessor.setEnabled("microcompact".equalsIgnoreCase(compactMode));

        this.autocompactProcessor = new AutocompactProcessor(3, apiKey);
        this.autocompactProcessor.setEnabled("autocompact".equalsIgnoreCase(compactMode));
        this.autocompactProcessor.setSummaryModel("qwen-turbo");  // 使用轻量模型生成摘要

        // SessionMemory 写入器（仅 autocompact 模式启用）
        this.sessionMemoryWriter = "autocompact".equalsIgnoreCase(compactMode)
                ? new SessionMemoryWriter(experimentId) : null;
        if (this.sessionMemoryWriter != null) {
            this.autocompactProcessor.setSessionMemoryWriter(this.sessionMemoryWriter);
        }
    }

    /**
     * 运行实验
     */
    public void run() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  Agent Loop 实验 4 — Transcript + Compact 系列对比          ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("实验ID: {}", experimentId);
        log.info("模型: {}", modelName);
        log.info("Compact 模式: {}", getCompactModeDescription());
        log.info("对话轮数: {}", CONVERSATION_SCRIPT.size());
        log.info("每轮最大 Agent Turn: {}", maxTurnsPerQuestion);
        log.info("Transcript 路径: {}", transcriptWriter.getTranscriptPath());
        if (sessionMemoryWriter != null) {
            log.info("SessionMemory 路径: {}", sessionMemoryWriter.getMemoryPath());
        }
        log.info("");

        // 初始化写入器
        transcriptWriter.init();
        if (sessionMemoryWriter != null) {
            sessionMemoryWriter.init();
        }

        // 消息历史（跨轮累积）
        List<JsonObject> messages = new ArrayList<>();

        // 全局统计
        List<TurnStats> allTurnStats = new ArrayList<>();
        int totalToolCalls = 0;
        long totalTimeMs = 0;

        try {
            // 写入 System Prompt
            transcriptWriter.startTurn(0);
            transcriptWriter.writeSystemMessage(SYSTEM_PROMPT);
            messages.add(createSystemMessage(SYSTEM_PROMPT));

            long experimentStartTime = System.currentTimeMillis();

            // 遍历对话脚本
            for (int conversationTurn = 1; conversationTurn <= CONVERSATION_SCRIPT.size(); conversationTurn++) {
                String userQuery = CONVERSATION_SCRIPT.get(conversationTurn - 1);

                log.info("\n========== Conversation Turn {} ========== ", conversationTurn);
                log.info("用户问题: {}", userQuery);

                // 写入 user message
                transcriptWriter.startTurn(conversationTurn);
                transcriptWriter.writeUserMessage(userQuery);
                messages.add(createUserMessage(userQuery));

                // 记录本轮开始时的 messages 统计
                TurnStats stats = new TurnStats();
                stats.conversationTurn = conversationTurn;
                stats.userQuery = userQuery;
                stats.messagesBefore = messages.size();

                // 设置当前主题（用于 SessionMemory）
                if (sessionMemoryWriter != null) {
                    sessionMemoryWriter.setTopic(conversationTurn, userQuery);
                }

                // 计算 messages 统计
                String statsStr = transcriptWriter.getMessagesStats(messages);
                log.info(statsStr);
                stats.charsBefore = calculateTotalChars(messages);
                stats.toolResultCharsBefore = calculateToolResultChars(messages);

                long turnStartTime = System.currentTimeMillis();

                // 执行 Agent Loop（单轮）
                AgentLoopResult result = runAgentLoop(messages, conversationTurn, userQuery);

                long turnEndTime = System.currentTimeMillis();
                long turnTimeMs = turnEndTime - turnStartTime;

                // 记录结果
                stats.messagesAfter = messages.size();
                stats.charsAfter = calculateTotalChars(messages);
                stats.toolResultCharsAfter = calculateToolResultChars(messages);
                stats.agentTurns = result.getTurnCount();
                stats.toolCalls = result.getToolCallHistory().size();
                stats.timeMs = turnTimeMs;
                stats.terminationReason = result.getReason().toString();
                stats.newChars = stats.charsAfter - stats.charsBefore;
                stats.compactedCount = getTotalCompactedCount();

                allTurnStats.add(stats);
                totalToolCalls += stats.toolCalls;

                log.info("本轮耗时: {} ms", turnTimeMs);
                log.info("本轮新增字符: {}", stats.newChars);
                log.info("本轮 Agent Turn: {}", stats.agentTurns);
                log.info("本轮工具调用: {}", stats.toolCalls);

                // 打印模型回答摘要
                if (result.getFinalResponse() != null) {
                    String summary = result.getFinalResponse().length() > 200
                            ? result.getFinalResponse().substring(0, 200) + "..."
                            : result.getFinalResponse();
                    log.info("模型回答摘要: {}", summary);
                }

                totalTimeMs += turnTimeMs;
            }

            long experimentEndTime = System.currentTimeMillis();
            long totalExperimentTimeMs = experimentEndTime - experimentStartTime;

            // 打印完整统计
            printFinalStats(allTurnStats, totalToolCalls, totalTimeMs, totalExperimentTimeMs);

        } finally {
            // 写入 SessionMemory 最终统计
            if (sessionMemoryWriter != null) {
                sessionMemoryWriter.writeFinalStats(autocompactProcessor.getStats());
                sessionMemoryWriter.close();
            }
            transcriptWriter.close();
        }
    }

    /**
     * 执行 Compact（根据模式选择）
     *
     * @param messages          当前消息列表
     * @param conversationTurn  当前对话轮次
     * @param userQuery         当前用户问题（用于 Autocompact 生成更相关的摘要）
     */
    private int performCompact(List<JsonObject> messages, int conversationTurn, String userQuery) {
        if ("microcompact".equalsIgnoreCase(compactMode)) {
            return microcompactProcessor.compact(messages, conversationTurn, transcriptWriter.getTranscriptPath());
        } else if ("autocompact".equalsIgnoreCase(compactMode)) {
            return autocompactProcessor.compact(messages, conversationTurn, transcriptWriter.getTranscriptPath(), userQuery);
        }
        return 0;  // baseline 不执行 compact
    }

    /**
     * 获取 Compact 模式描述
     */
    private String getCompactModeDescription() {
        if ("microcompact".equalsIgnoreCase(compactMode)) {
            return "Microcompact (纯代码逻辑, threshold=3)";
        } else if ("autocompact".equalsIgnoreCase(compactMode)) {
            return "Autocompact (LLM摘要, qwen-turbo, threshold=3)";
        }
        return "Baseline (无 Compact)";
    }

    /**
     * 获取总 Compact 数
     */
    private int getTotalCompactedCount() {
        if ("microcompact".equalsIgnoreCase(compactMode)) {
            return microcompactProcessor.getStats().get("total_compacted") != null
                    ? (Integer) microcompactProcessor.getStats().get("total_compacted") : 0;
        } else if ("autocompact".equalsIgnoreCase(compactMode)) {
            return autocompactProcessor.getStats().get("total_compacted") != null
                    ? (Integer) autocompactProcessor.getStats().get("total_compacted") : 0;
        }
        return 0;
    }

    /**
     * 执行单轮 Agent Loop
     *
     * @param messages          当前消息列表
     * @param conversationTurn  当前对话轮次
     * @param userQuery         当前用户问题
     */
    private AgentLoopResult runAgentLoop(List<JsonObject> messages, int conversationTurn, String userQuery) {
        int agentTurnCount = 0;
        List<AgentLoopResult.ToolCallRecord> toolCallHistory = new ArrayList<>();

        try {
            while (true) {
                agentTurnCount++;

                log.info("\n---------- Agent Turn {} (Conversation Turn {}) ----------",
                        agentTurnCount, conversationTurn);

                // 护栏：检查最大轮次
                if (agentTurnCount > maxTurnsPerQuestion) {
                    log.warn("达到最大 Agent 轮次限制: {}", maxTurnsPerQuestion);
                    return AgentLoopResult.maxTurnsReached(agentTurnCount, toolCallHistory);
                }

                // === 执行 Compact（在 LLM 调用前）===
                int compacted = performCompact(messages, conversationTurn, userQuery);
                if (compacted > 0) {
                    log.info("{}: compact {} 条 tool_result", compactMode, compacted);
                    // 打印 compact 后的统计
                    log.info(transcriptWriter.getMessagesStats(messages));
                }

                // 1. 调用 LLM（带 tools 参数）
                JsonObject responseJson = callLLMWithTools(messages);
                transcriptWriter.updateTokenStats(responseJson);

                JsonObject message = extractMessage(responseJson);

                // 2. 检查是否有 tool_calls
                List<ToolCallInfo> toolCalls = extractToolCalls(message);

                if (toolCalls.isEmpty()) {
                    // 没有工具调用 = 模型返回最终回答
                    String finalContent = extractContent(message);
                    log.info("模型返回最终回答");

                    // 写入 assistant message
                    transcriptWriter.writeAssistantMessage(message);
                    messages.add(message);

                    return AgentLoopResult.completed(finalContent, agentTurnCount, toolCallHistory);
                }

                // 3. 有工具调用 → 执行工具
                log.info("模型请求调用 {} 个工具", toolCalls.size());

                // 将 assistant 消息添加到历史
                transcriptWriter.writeAssistantMessage(message);
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
                            Map<String, Object> input = parseArguments(tc.arguments);
                            result = tool.execute(input);
                            result.setToolCallId(tc.toolCallId);
                        } catch (Exception e) {
                            log.error("工具执行失败: {}", e.getMessage(), e);
                            result = ToolResult.error(tc.toolName, e.getMessage());
                            result.setToolCallId(tc.toolCallId);
                        }
                    }

                    log.info("工具结果长度: {} 字符", result.getContent().length());

                    // 记录历史
                    toolCallHistory.add(AgentLoopResult.ToolCallRecord.builder()
                            .turn(agentTurnCount)
                            .toolName(tc.toolName)
                            .toolCallId(tc.toolCallId)
                            .input(tc.arguments)
                            .output(result.getContent().length() > 100
                                    ? result.getContent().substring(0, 100) + "..."
                                    : result.getContent())
                            .success(!result.isError())
                            .build());

                    // 4. 将工具结果拼回消息历史
                    transcriptWriter.writeToolResultMessage(tc.toolCallId, tc.toolName, result.getContent(), result.isError());
                    messages.add(createToolResultMessage(result));
                }
            }
        } catch (Exception e) {
            log.error("Agent Loop 执行失败", e);
            return AgentLoopResult.error(e.getMessage(), agentTurnCount, toolCallHistory);
        }
    }

    /**
     * 调用 LLM（带 tools 参数）
     */
    private JsonObject callLLMWithTools(List<JsonObject> messages) {
        try {
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
                    JsonObject toolDef = GSON.fromJson(tool.toFunctionDefinition(), JsonObject.class);
                    toolsArray.add(toolDef);
                }
                requestBody.add("tools", toolsArray);
            }

            log.debug("请求体大小: {} 字符", requestBody.toString().length());

            // 调用百炼 API
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
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
                String responseStr = response.body().string();
                return GSON.fromJson(responseStr, JsonObject.class);
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
        JsonObject args = GSON.fromJson(arguments, JsonObject.class);
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

    private long calculateTotalChars(List<JsonObject> messages) {
        long total = 0;
        for (JsonObject msg : messages) {
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                total += msg.get("content").getAsString().length();
            }
        }
        return total;
    }

    private long calculateToolResultChars(List<JsonObject> messages) {
        long total = 0;
        for (JsonObject msg : messages) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            if ("tool".equals(role) && msg.has("content") && !msg.get("content").isJsonNull()) {
                total += msg.get("content").getAsString().length();
            }
        }
        return total;
    }

    private void printFinalStats(List<TurnStats> stats, int totalToolCalls, long totalTimeMs, long totalExperimentTimeMs) {
        log.info("\n========== 实验完成 ========== ");
        log.info("实验ID: {}", experimentId);
        log.info("Compact 模式: {}", getCompactModeDescription());
        log.info("Transcript 路径: {}", transcriptWriter.getTranscriptPath());
        if (sessionMemoryWriter != null) {
            log.info("SessionMemory 路径: {}", sessionMemoryWriter.getMemoryPath());
        }

        // 打印上下文膨胀曲线表格
        log.info("\n## 上下文膨胀曲线");
        log.info("| Turn | messages 总字符 | tool_result 字符 | tool_result 占比 | 本轮新增字符 | Agent Turns | 工具调用 |");
        log.info("|------|----------------|-----------------|-----------------|------------|-------------|---------|");

        for (TurnStats ts : stats) {
            double toolResultPercent = ts.charsAfter > 0
                    ? (ts.toolResultCharsAfter * 100.0 / ts.charsAfter) : 0;
            log.info("| {} | {} | {} | {:.1f}% | {} | {} | {} |",
                    ts.conversationTurn, ts.charsAfter, ts.toolResultCharsAfter,
                    toolResultPercent, ts.newChars, ts.agentTurns, ts.toolCalls);
        }

        // 汇总
        log.info("\n## 汇总统计");
        log.info("总对话轮数: {}", stats.size());
        log.info("总工具调用数: {}", totalToolCalls);
        log.info("总 Agent Turn: {}", stats.stream().mapToInt(s -> s.agentTurns).sum());
        log.info("最终 messages 总字符: {}", stats.isEmpty() ? 0 : stats.get(stats.size() - 1).charsAfter);
        log.info("最终 tool_result 字符: {}", stats.isEmpty() ? 0 : stats.get(stats.size() - 1).toolResultCharsAfter);
        log.info("总耗时 (Agent Loop): {} ms", totalTimeMs);
        log.info("总耗时 (完整实验): {} ms", totalExperimentTimeMs);

        // Compact 统计（根据模式输出）
        if (!"baseline".equalsIgnoreCase(compactMode)) {
            log.info("\n## {} 统计", compactMode);
            Map<String, Object> compactStats = "microcompact".equalsIgnoreCase(compactMode)
                    ? microcompactProcessor.getStats() : autocompactProcessor.getStats();
            log.info("总 compact 数: {}", compactStats.get("total_compacted"));
            log.info("总节省字符: {}", compactStats.get("total_chars_saved"));

            if ("autocompact".equalsIgnoreCase(compactMode)) {
                log.info("LLM 调用次数: {}", compactStats.get("total_llm_calls"));
                log.info("LLM 总耗时: {} ms", compactStats.get("total_llm_time_ms"));
                log.info("跳过已压缩数: {}", compactStats.get("skipped_count"));
                log.info("摘要模型: {}", compactStats.get("summary_model"));
            }
        }
    }

    /**
     * 单轮统计记录
     */
    private static class TurnStats {
        int conversationTurn;
        String userQuery;
        int messagesBefore;
        int messagesAfter;
        long charsBefore;
        long charsAfter;
        long toolResultCharsBefore;
        long toolResultCharsAfter;
        int agentTurns;
        int toolCalls;
        long timeMs;
        String terminationReason;
        long newChars;
        int compactedCount;
    }

    /**
     * 工具调用信息
     */
    private record ToolCallInfo(String toolCallId, String toolName, String arguments) {}

    /**
     * 主入口
     */
    public static void main(String[] args) {
        // API Key 配置
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "sk-e468852b76324d17b8131d6d8a58dda8";
        }
        if (args.length > 0) {
            apiKey = args[0];
        }

        // 确定运行模式: baseline, microcompact, autocompact
        String compactMode = args.length > 1 ? args[1] : "baseline";

        // 模型选择（默认 qwen-max）
        String modelName = args.length > 2 ? args[2] : "qwen-max";

        // 根据 compactMode 生成 experimentId
        String experimentId;
        switch (compactMode.toLowerCase()) {
            case "microcompact":
                experimentId = "run2_microcompact";
                break;
            case "autocompact":
                experimentId = "run3_autocompact";
                break;
            default:
                experimentId = "run1_baseline";
                compactMode = "baseline";
        }

        log.info("API Key: {}...", apiKey.substring(0, Math.min(8, apiKey.length())));
        log.info("运行模式: {} (Compact: {})", experimentId, compactMode);
        log.info("使用模型: {}", modelName);

        AgentLoopExperiment4 experiment = new AgentLoopExperiment4(apiKey, experimentId, compactMode, modelName);
        experiment.run();
    }
}