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

import com.nageoffer.ai.ragent.rag.agentloop.AgentLoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Loop 策略实现
 *
 * <p>包装现有的 AgentLoopService，提供灵活的工具调用问答能力。
 * 适合需要多轮检索的复杂问题。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStrategy implements ChatStrategy {

    private final AgentLoopService agentLoopService;

    @Override
    public String getName() {
        return "agent";
    }

    @Override
    public String getDescription() {
        return """
Agent Loop 模式：灵活的工具调用循环。

特点：
- 灵活智能，LLM 自主决定检索策略和回答时机
- 支持多轮检索，可以换关键词重新搜索
- 响应时间较长（可能 10-30 秒，取决于轮次）
- 能处理复杂推理和多步骤问题

擅长处理的问题类型：
1. 多维度对比："对比 A 和 B 的优缺点"、"A 和 B 有什么区别"
2. 复杂推理："根据文档推导 XX 的最佳实践"
3. 多步骤问题："如何一步步完成 XX"、"实现 XX 需要做什么"
4. 需要多信息源的问题："XX 系统的整体架构是什么"
5. 分析性问题："为什么 XX 这样设计"、"XX 的原理是什么"

不擅长的问题类型：
1. 简单直接的问题（用 Pipeline 更快）
2. 单一知识点查询（Pipeline 更高效）

建议：
- 问题包含"对比"、"分析"、"整体架构"、"步骤"等关键词 → 优先选择
- 问题长度 > 50 字或包含多个子问题 → 优先选择
- 简单查询问题 → 使用 Pipeline 更高效
""";
    }

    @Override
    public int getPriority() {
        // Agent 作为高级模式，优先级较低（仅在明确需要时选择）
        return 50;
    }

    @Override
    public void execute(ChatRequest request, SseEmitter emitter) {
        log.info("Agent Strategy 执行: question={}, conversationId={}",
                request.getQuestion(), request.getConversationId());

        // 直接调用现有的 AgentLoopService
        agentLoopService.runAgent(
                request.getQuestion(),
                request.getConversationId(),
                request.getUserId(),
                emitter
        );
    }
}