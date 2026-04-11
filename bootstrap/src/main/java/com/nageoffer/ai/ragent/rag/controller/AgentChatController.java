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

package com.nageoffer.ai.ragent.rag.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.rag.agentloop.AgentLoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 模式对话控制器
 *
 * <p>提供 Agent Loop 模式的问答能力，支持工具调用。</p>
 *
 * <p>与 Pipeline 模式的区别：</p>
 * <ul>
 *   <li>Pipeline 模式（/rag/v3/chat）：检索 → 拼接 prompt → LLM 回答</li>
 *   <li>Agent 模式（/api/ragent/agent/chat）：LLM 自主决定是否调用工具 → 执行工具 → LLM 继续回答</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentLoopService agentLoopService;

    /**
     * Agent 模式流式对话
     *
     * <p>SSE 事件格式：</p>
     * <ul>
     *   <li>event: content, data: 文本片段</li>
     *   <li>event: done, data: ""</li>
     *   <li>event: error, data: 错误信息</li>
     * </ul>
     *
     * @param question       用户问题
     * @param conversationId 会话ID（可选，不传则不记录历史）
     * @return SSE 流
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter agentChat(
            @RequestParam String question,
            @RequestParam(required = false) String conversationId) {

        log.info("Agent Chat 请求: question={}, conversationId={}", question, conversationId);

        // 创建 SSE 发送器（无超时限制）
        SseEmitter emitter = new SseEmitter(0L);

        // 获取用户ID
        String userId = UserContext.getUserId();

        // 如果没有传 conversationId，生成一个临时的（但不保存历史）
        String actualConversationId = StrUtil.isNotBlank(conversationId)
                ? conversationId
                : IdUtil.getSnowflakeNextIdStr();

        // 异步执行 Agent Loop
        agentLoopService.runAgent(question, actualConversationId, userId, emitter);

        return emitter;
    }
}