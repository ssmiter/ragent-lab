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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 策略路由器
 *
 * <p>根据问题特征自动选择最合适的问答处理策略。</p>
 *
 * <p>路由决策方式（MVP 阶段使用规则，后续可扩展为 LLM）：</p>
 * <ul>
 *   <li>规则判断：基于问题长度、关键词、问题类型等特征</li>
 *   <li>LLM 判断（可选）：对于模糊问题，可调用 LLM 进行决策</li>
 * </ul>
 */
@Slf4j
@Component
public class StrategyRouter {

    private final Map<String, ChatStrategy> strategyMap;
    private final List<ChatStrategy> strategies;

    /**
     * Agent 模式偏向的关键词
     */
    private static final List<String> AGENT_KEYWORDS = List.of(
            "对比", "比较", "区别", "差异",
            "分析", "原理", "为什么", "如何实现",
            "架构", "整体", "系统", "模块",
            "步骤", "流程", "一步步", "如何完成",
            "最佳实践", "优缺点", "利弊"
    );

    /**
     * Pipeline 模式偏向的关键词
     */
    private static final List<String> PIPELINE_KEYWORDS = List.of(
            "是什么", "什么是", "概念", "定义",
            "如何配置", "怎么配置", "配置方法",
            "默认值", "参数", "字段",
            "示例", "例子", "用法"
    );

    /**
     * 问题长度阈值（超过此长度偏向 Agent）
     */
    private static final int LONG_QUESTION_THRESHOLD = 50;

    public StrategyRouter(List<ChatStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .filter(ChatStrategy::isEnabled)
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();
        this.strategyMap = strategyList.stream()
                .collect(Collectors.toMap(ChatStrategy::getName, Function.identity()));
        log.info("StrategyRouter 初始化完成，注册策略: {}", strategies.stream().map(ChatStrategy::getName).toList());
    }

    /**
     * 路由并执行
     *
     * <p>根据请求内容自动选择策略并执行。</p>
     *
     * @param request 问答请求
     * @param emitter SSE 发射器
     */
    public void routeAndExecute(ChatRequest request, SseEmitter emitter) {
        // 1. 检查是否强制指定策略
        if (StrUtil.isNotBlank(request.getForceStrategy())) {
            ChatStrategy forced = strategyMap.get(request.getForceStrategy());
            if (forced != null) {
                log.info("强制使用策略: {}", request.getForceStrategy());
                executeWithHandoffHandling(forced, request, emitter);
                return;
            }
            log.warn("指定的策略不存在: {}, 将自动选择", request.getForceStrategy());
        }

        // 2. 自动决策选择策略
        ChatStrategy selected = selectStrategy(request);
        log.info("路由决策: question={}, selectedStrategy={}", request.getQuestion(), selected.getName());

        // 3. 执行选中的策略（包含 handoff 异常处理）
        executeWithHandoffHandling(selected, request, emitter);
    }

    /**
     * 执行策略并处理策略转交异常
     *
     * <p>如果策略执行过程中抛出 StrategyHandoffException，
     * 将请求转交给目标策略（通常是 Agent）。</p>
     */
    private void executeWithHandoffHandling(ChatStrategy strategy, ChatRequest request, SseEmitter emitter) {
        try {
            strategy.execute(request, emitter);
        } catch (StrategyHandoffException e) {
            log.info("策略转交: from={}, to={}, reason={}",
                    strategy.getName(), e.getTargetStrategy(), e.getReason());

            ChatStrategy targetStrategy = strategyMap.get(e.getTargetStrategy());
            if (targetStrategy != null) {
                targetStrategy.execute(request, emitter);
            } else {
                log.error("转交目标策略不存在: {}", e.getTargetStrategy());
                throw new IllegalArgumentException("Strategy not found: " + e.getTargetStrategy());
            }
        }
    }

    /**
     * 选择策略
     *
     * <p>MVP 阶段使用规则决策，后续可扩展为 LLM 决策。</p>
     */
    private ChatStrategy selectStrategy(ChatRequest request) {
        String question = request.getQuestion();

        // 计算偏向分数
        int agentScore = calculateAgentScore(question);
        int pipelineScore = calculatePipelineScore(question);

        log.debug("路由分数: agent={}, pipeline={}, question={}", agentScore, pipelineScore, question);

        // 根据分数选择策略
        if (agentScore > pipelineScore) {
            return getStrategy("agent");
        } else if (pipelineScore > 0) {
            return getStrategy("pipeline");
        }

        // 默认情况：问题长度 > 50 字偏向 Agent，否则 Pipeline
        if (question.length() > LONG_QUESTION_THRESHOLD) {
            return getStrategy("agent");
        }

        // 默认使用 Pipeline（快速稳定）
        return getStrategy("pipeline");
    }

    /**
     * 计算 Agent 模式偏向分数
     */
    private int calculateAgentScore(String question) {
        int score = 0;

        // Agent 关键词匹配
        for (String keyword : AGENT_KEYWORDS) {
            if (question.contains(keyword)) {
                score += 2;
            }
        }

        // 问题长度加分
        if (question.length() > LONG_QUESTION_THRESHOLD) {
            score += 1;
        }

        // 包含多个子问题（逗号、问号分隔）
        if (question.contains(",") || question.contains("，") || question.split("？").length > 1) {
            score += 1;
        }

        return score;
    }

    /**
     * 计算 Pipeline 模式偏向分数
     */
    private int calculatePipelineScore(String question) {
        int score = 0;

        // 关键词匹配
        for (String keyword : PIPELINE_KEYWORDS) {
            if (question.contains(keyword)) {
                score += 2;
            }
        }

        // 问题简短加分
        if (question.length() < 20) {
            score += 2;
        } else if (question.length() < LONG_QUESTION_THRESHOLD) {
            score += 1;
        }

        return score;
    }

    /**
     * 获取指定策略
     *
     * @throws IllegalArgumentException 如果策略不存在
     */
    private ChatStrategy getStrategy(String name) {
        ChatStrategy strategy = strategyMap.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy not found: " + name);
        }
        return strategy;
    }

    /**
     * 获取所有已注册策略（用于调试或展示）
     */
    public List<ChatStrategy> getAllStrategies() {
        return strategies;
    }

    /**
     * 获取策略信息（用于前端展示或 LLM 决策）
     */
    public List<StrategyInfo> getStrategyInfos() {
        return strategies.stream()
                .map(s -> new StrategyInfo(s.getName(), s.getDescription(), s.getPriority()))
                .toList();
    }

    /**
     * 策略信息记录
     */
    public record StrategyInfo(String name, String description, int priority) {}
}