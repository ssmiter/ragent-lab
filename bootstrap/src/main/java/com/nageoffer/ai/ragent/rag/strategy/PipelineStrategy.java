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

package com.nageoffer.ai.ragent.rag.strategy;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Pipeline RAG 策略实现
 *
 * <p>包装现有的 RAGChatService，提供快速稳定的问答能力。
 * 适合简单直接的问题。</p>
 *
 * <p>SYSTEM 意图处理：在调用 streamChat 前先进行意图分类，
 * 如果检测到 allSystemOnly=true，抛出 StrategyHandoffException
 * 将请求转交给 Agent 策略处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineStrategy implements ChatStrategy {

    private final RAGChatService ragChatService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final ConversationMemoryService memoryService;

    @Override
    public String getName() {
        return "pipeline";
    }

    @Override
    public String getDescription() {
        return """
Pipeline RAG 模式：固定流程的检索增强生成。

特点：
- 快速稳定，响应时间短（通常 3-5 秒）
- 流程固定：查询改写 → 意图识别 → 检索 → Rerank → 生成
- 适合简单、直接、单一意图的问题

擅长处理的问题类型：
1. 知识点查询："XX 是什么"、"如何配置 XX"
2. 功能说明："系统的核心功能有哪些"
3. 单一事实确认："XX 的默认值是多少"
4. 快速问答："这是什么"

不擅长的问题类型：
1. 多维度对比："对比 A 和 B 的优缺点"
2. 复杂推理："根据文档推导 XX 的最佳实践"
3. 多步骤问题："如何一步步完成 XX"
4. 需要多轮检索的复杂问题

建议：
- 问题长度 < 50 字、包含"是什么"等简单查询词 → 优先选择
- 问题复杂或需要深入分析 → 考虑 Agent 模式
""";
    }

    @Override
    public int getPriority() {
        // Pipeline 作为基础模式，优先级较高
        return 10;
    }

    @Override
    public void execute(ChatRequest request, SseEmitter emitter) {
        log.info("Pipeline Strategy 执行: question={}, conversationId={}",
                request.getQuestion(), request.getConversationId());

        String question = request.getQuestion();
        String conversationId = StrUtil.isNotBlank(request.getConversationId())
                ? request.getConversationId()
                : IdUtil.getSnowflakeNextIdStr();

        // ========== SYSTEM 意图预检测（同步执行，在进入异步线程前）==========
        // 目的：避免在 RAGChatServiceImpl 的异步线程中抛出异常导致无法传播
        String userId = UserContext.getUserId();
        List<ChatMessage> history = loadHistory(conversationId, userId);

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));

        if (allSystemOnly) {
            // SYSTEM 意图（问候语、关于系统等）交给 Agent 处理
            // Agent 有 system_info_query 工具可以返回真实的系统元信息
            log.info("PipelineStrategy 检测到 SYSTEM 意图，转交给 Agent 策略: question={}", question);
            throw StrategyHandoffException.forSystemIntent();
        }

        // ========== 正常 Pipeline 流程 ==========
        // 调用 RAGChatService，后续流程（歧义引导、检索、生成）在异步线程执行
        ragChatService.streamChat(
                question,
                conversationId,
                request.getDeepThinking(),
                emitter
        );
    }

    /**
     * 加载对话历史
     *
     * <p>复用 ConversationMemoryService，与 RAGChatServiceImpl 一致。</p>
     */
    private List<ChatMessage> loadHistory(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        try {
            List<ChatMessage> history = memoryService.load(conversationId, userId);
            // 返回历史（不含当前问题，因为意图分类不需要）
            return history != null ? history : List.of();
        } catch (Exception e) {
            log.warn("加载对话历史失败，将使用空历史: {}", e.getMessage());
            return List.of();
        }
    }
}