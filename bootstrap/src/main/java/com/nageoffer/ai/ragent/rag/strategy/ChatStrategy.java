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

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答处理策略接口
 *
 * <p>所有问答处理模式（Pipeline、Agent、多知识库路由等）都实现此接口，
 * Router 根据策略的描述信息自动选择最合适的策略执行。</p>
 *
 * <p>设计理念：像 Tool 接口一样，Strategy 也是可插拔的。
 * 新增处理模式只需实现此接口并注册为 Spring Bean，无需修改现有代码。</p>
 */
public interface ChatStrategy {

    /**
     * 策略名称（唯一标识）
     *
     * <p>用于日志记录和调试，建议使用简洁的标识符如 "pipeline"、"agent"。</p>
     */
    String getName();

    /**
     * 策略描述（用于路由决策）
     *
     * <p>Router 根据此描述判断问题是否适合该策略。描述应包含：</p>
     * <ul>
     *   <li>该策略擅长处理的问题类型</li>
     *   <li>该策略的特点（快速、稳定、灵活等）</li>
     *   <li>典型适用场景示例</li>
     * </ul>
     */
    String getDescription();

    /**
     * 执行问答处理
     *
     * <p>所有策略通过统一的输入输出接口执行，Router 调用此方法后，
     * SSE 输出对前端透明（用户无感知策略切换）。</p>
     *
     * @param request 统一的问答请求
     * @param emitter SSE 发射器（用于流式输出）
     */
    void execute(ChatRequest request, SseEmitter emitter);

    /**
     * 策略优先级（数值越小优先级越高）
     *
     * <p>当多个策略都适合时，优先级高的策略优先被选择。
     * 默认优先级为 100。</p>
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 是否启用该策略
     *
     * <p>可用于动态禁用某些策略（如配置开关）。</p>
     */
    default boolean isEnabled() {
        return true;
    }
}