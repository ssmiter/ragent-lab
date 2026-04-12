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

package com.nageoffer.ai.ragent.rag.agentloop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.retrieve.MilvusRetrieverService;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Loop 服务
 *
 * <p>负责组装和运行 AgentLoop，提供 Agent 模式的问答能力。</p>
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>加载对话历史（复用现有 memoryService）</li>
 *   <li>组装 system prompt</li>
 *   <li>创建并运行 AgentLoop</li>
 *   <li>流式返回最终回答</li>
 *   <li>保存 assistant 回复到对话历史</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopService {

    private final MilvusRetrieverService retrieverService;
    private final RerankService rerankService;
    private final ConversationMemoryService memoryService;
    private final AIModelProperties aiModelProperties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentNodeMapper intentNodeMapper;

    /**
     * 最大轮次限制
     */
    private static final int MAX_TURNS = 6;

    /**
     * System Prompt
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库问答助手。你有以下工具可用：
- knowledge_search_with_rerank：在知识库中搜索相关内容
- system_info_query：查询系统元信息（知识库列表、系统能力、领域分类等）

使用规则：
1. 收到用户问题后，先判断问题类型：
   - 如果用户询问"系统有哪些知识库"、"系统能做什么"等元信息问题 → 使用 system_info_query
   - 如果用户询问知识库中的具体内容 → 使用 knowledge_search_with_rerank
2. 搜索结果中，rerank_score > 0.85 表示高度相关，< 0.75 表示低相关
3. 如果搜索结果质量不够，可以换关键词重新搜索
4. 基于搜索结果回答时，引用具体内容
5. 如果知识库中确实没有相关信息，诚实告知
6. 对于问候语（如"你好"、"hello"），可以直接回答，无需调用工具

知识库路由策略：
1. 选择知识库时，仔细阅读工具描述中的内容范围说明，不要只看名称
2. 如果首次搜索结果不佳，考虑切换到其他可能相关的知识库
3. 跨领域问题可以依次搜索多个知识库
4. 不确定某个知识库的内容时，先使用 system_info_query 查看其详细描述和示例问题
""";

    /**
     * 运行 Agent Loop（异步执行）
     *
     * @param question       用户问题
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param emitter        SSE 发送器
     */
    @Async
    public void runAgent(String question, String conversationId, String userId, SseEmitter emitter) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        String taskId = IdUtil.getSnowflakeNextIdStr();

        try {
            log.info("Agent Loop 开始执行, question={}, conversationId={}, userId={}",
                    question, conversationId, userId);

            // 1. 发送 meta 事件（前端期望的格式）
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));

            // 2. 获取 LLM 配置
            AIModelProperties.ProviderConfig providerConfig = getProviderConfig();
            AIModelProperties.ModelCandidate modelCandidate = getModelCandidate();

            if (providerConfig == null || modelCandidate == null) {
                log.error("LLM 配置缺失，无法执行 Agent Loop");
                sender.sendEvent(SSEEventType.ERROR.value(), "LLM 配置缺失");
                sender.complete();
                return;
            }

            // 3. 加载对话历史（可选，用于上下文）
            List<ChatMessage> history = null;
            if (StrUtil.isNotBlank(conversationId) && StrUtil.isNotBlank(userId)) {
                try {
                    history = memoryService.load(conversationId, userId);
                    log.debug("加载对话历史: {} 条消息", history != null ? history.size() : 0);
                } catch (Exception e) {
                    log.warn("加载对话历史失败，将忽略历史: {}", e.getMessage());
                }
            }

            // 4. 组装用户问题（可以包含历史上下文）
            String effectiveQuestion = buildEffectiveQuestion(question, history);

            // 5. 创建工具
            Tool searchTool = new KnowledgeSearchWithRerankTool(retrieverService, rerankService, knowledgeBaseMapper, intentNodeMapper);
            Tool systemInfoTool = new SystemInfoTool(knowledgeBaseMapper, intentNodeMapper);
            Map<String, Tool> tools = new HashMap<>();
            tools.put(searchTool.getName(), searchTool);
            tools.put(systemInfoTool.getName(), systemInfoTool);

            // 6. 创建并运行 AgentLoop
            AgentLoop loop = new AgentLoop(
                    providerConfig.getUrl(),
                    providerConfig.getApiKey(),
                    modelCandidate.getModel(),
                    tools,
                    MAX_TURNS,
                    SYSTEM_PROMPT
            );

            AgentLoopResult result = loop.run(effectiveQuestion);

            // 7. 记录执行报告
            log.info("\n{}", result.getReport());

            // 8. 流式发送最终回答（使用前端期望的 message 事件格式）
            String finalResponse = result.getFinalResponse();
            if (finalResponse != null && !finalResponse.isBlank()) {
                streamContent(finalResponse, sender);
            } else {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta("response", "抱歉，未能生成有效回答。"));
            }

            // 9. 保存 assistant 回复到对话历史
            Long messageId = null;
            if (StrUtil.isNotBlank(conversationId) && StrUtil.isNotBlank(userId) && finalResponse != null) {
                try {
                    messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(finalResponse));
                    log.debug("保存 assistant 回复到对话历史");
                } catch (Exception e) {
                    log.warn("保存对话历史失败: {}", e.getMessage());
                }
            }

            // 10. 发送 finish 和 done 事件（前端期望的格式）
            sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(
                    messageId != null ? String.valueOf(messageId) : null, null));
            sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
            sender.complete();

            log.info("Agent Loop 执行完成, turnCount={}, toolCalls={}",
                    result.getTurnCount(),
                    result.getToolCallHistory().size());

        } catch (Exception e) {
            log.error("Agent Loop 执行失败", e);
            try {
                sender.sendEvent(SSEEventType.ERROR.value(), "执行失败: " + e.getMessage());
                sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
                sender.complete();
            } catch (Exception ex) {
                log.error("发送错误事件失败", ex);
            }
        }
    }

    /**
     * 获取提供商配置（默认取第一个）
     */
    private AIModelProperties.ProviderConfig getProviderConfig() {
        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        // 返回第一个可用的 provider
        return providers.values().stream()
                .filter(p -> StrUtil.isNotBlank(p.getApiKey()) && StrUtil.isNotBlank(p.getUrl()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取模型配置（默认模型）
     */
    private AIModelProperties.ModelCandidate getModelCandidate() {
        AIModelProperties.ModelGroup chatGroup = aiModelProperties.getChat();
        if (chatGroup == null || chatGroup.getCandidates() == null || chatGroup.getCandidates().isEmpty()) {
            return null;
        }

        // 优先找默认模型
        String defaultModelId = chatGroup.getDefaultModel();
        if (StrUtil.isNotBlank(defaultModelId)) {
            for (AIModelProperties.ModelCandidate candidate : chatGroup.getCandidates()) {
                if (defaultModelId.equals(candidate.getId())) {
                    return candidate;
                }
            }
        }

        // 否则返回第一个启用的模型
        return chatGroup.getCandidates().stream()
                .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> StrUtil.isNotBlank(c.getModel()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 组装有效问题（可以包含历史上下文）
     *
     * <p>MVP 阶段：直接使用用户问题，暂不考虑历史上下文</p>
     */
    private String buildEffectiveQuestion(String question, List<ChatMessage> history) {
        // MVP 阶段：直接返回用户问题
        // 后续可以在此加入历史上下文摘要或改写逻辑
        return question;
    }

    /**
     * 流式发送内容（使用前端期望的 message 事件格式）
     */
    private void streamContent(String content, SseEmitterSender sender) {
        int chunkSize = 5;
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();

        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;

            if (count >= chunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta("response", buffer.toString()));
                buffer.setLength(0);
                count = 0;

                // 添加小延迟模拟流式效果
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta("response", buffer.toString()));
        }
    }
}