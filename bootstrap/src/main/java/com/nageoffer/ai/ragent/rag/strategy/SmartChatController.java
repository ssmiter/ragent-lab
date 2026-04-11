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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 智能对话控制器
 *
 * <p>统一入口端点，Router 自动选择最合适的策略处理问题。
 * 用户无需手动选择 Pipeline 或 Agent 模式。</p>
 *
 * <p>与原有端点的关系：</p>
 * <ul>
 *   <li>/rag/v3/chat - Pipeline 模式（保留，用于手动指定）</li>
 *   <li>/agent/chat - Agent 模式（保留，用于手动指定）</li>
 *   <li>/smart/chat - 智能路由模式（新增，推荐使用）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/smart")
@RequiredArgsConstructor
public class SmartChatController {

    private final StrategyRouter strategyRouter;

    /**
     * 智能路由流式对话
     *
     * <p>Router 根据问题特征自动选择 Pipeline 或 Agent 策略。</p>
     *
     * <p>SSE 事件格式（与 Pipeline/Agent 一致）：</p>
     * <ul>
     *   <li>event: meta, data: {conversationId, taskId}</li>
     *   <li>event: message, data: {type, delta}</li>
     *   <li>event: finish, data: {messageId, title}</li>
     *   <li>event: done, data: [DONE]</li>
     * </ul>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（可选）
     * @param strategy       强制指定策略（可选，值为 "pipeline" 或 "agent"）
     * @return SSE 流
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter smartChat(
            @RequestParam String question,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String strategy) {

        log.info("Smart Chat 请求: question={}, conversationId={}, strategy={}",
                question, conversationId, strategy);

        // 创建 SSE 发送器（无超时限制）
        SseEmitter emitter = new SseEmitter(0L);

        // 获取用户 ID
        String userId = UserContext.getUserId();

        // 构建统一请求
        ChatRequest request = ChatRequest.builder()
                .question(question)
                .conversationId(StrUtil.isNotBlank(conversationId) ? conversationId : null)
                .userId(userId)
                .forceStrategy(StrUtil.isNotBlank(strategy) ? strategy : null)
                .build();

        // 路由并执行
        strategyRouter.routeAndExecute(request, emitter);

        return emitter;
    }

    /**
     * 获取可用策略列表（用于前端展示或调试）
     */
    @GetMapping("/strategies")
    public StrategyRouter.StrategyInfo[] getStrategies() {
        return strategyRouter.getStrategyInfos().toArray(new StrategyRouter.StrategyInfo[0]);
    }
}