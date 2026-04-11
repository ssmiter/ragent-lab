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

import lombok.Builder;
import lombok.Getter;

/**
 * 统一的问答请求封装
 *
 * <p>将 Pipeline 和 Agent 模式的输入参数统一封装，
 * Strategy 实现只需处理这个统一格式。</p>
 */
@Getter
@Builder
public class ChatRequest {

    /**
     * 用户问题
     */
    private final String question;

    /**
     * 会话 ID（可选，空时创建新会话）
     */
    private final String conversationId;

    /**
     * 用户 ID（从 UserContext 获取）
     */
    private final String userId;

    /**
     * 是否开启深度思考模式（Pipeline 特有参数）
     *
     * <p>Agent 模式不使用此参数，Strategy 实现时可以忽略。</p>
     */
    @Builder.Default
    private final Boolean deepThinking = false;

    /**
     * 是否强制指定策略（可选）
     *
     * <p>如果设置，Router 将跳过决策直接使用指定策略。
     * 用于调试或用户手动选择模式。</p>
     */
    private final String forceStrategy;
}