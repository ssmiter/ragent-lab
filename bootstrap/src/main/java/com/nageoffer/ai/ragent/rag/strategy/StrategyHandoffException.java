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

/**
 * 策略转交信号
 *
 * <p>当 PipelineStrategy 处理过程中发现无法处理特定意图类型时，
 * 抛出此异常以请求 Router 将请求转交给更有能力的策略（通常是 Agent）。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>SYSTEM 意图：用户询问系统元信息（如"系统有哪些知识库"），Pipeline 无法提供真实数据</li>
 *   <li>其他需要 Agent 处理的复杂场景</li>
 * </ul>
 */
public class StrategyHandoffException extends RuntimeException {

    /**
     * 目标策略名称
     */
    private final String targetStrategy;

    /**
     * 转交原因
     */
    private final String reason;

    public StrategyHandoffException(String targetStrategy, String reason) {
        super("策略转交请求: target=" + targetStrategy + ", reason=" + reason);
        this.targetStrategy = targetStrategy;
        this.reason = reason;
    }

    /**
     * 创建 SYSTEM 意图转交请求
     *
     * <p>请求将请求转交给 Agent 策略处理 SYSTEM 类型的意图。</p>
     */
    public static StrategyHandoffException forSystemIntent() {
        return new StrategyHandoffException("agent", "SYSTEM intent requires Agent strategy");
    }

    public String getTargetStrategy() {
        return targetStrategy;
    }

    public String getReason() {
        return reason;
    }
}