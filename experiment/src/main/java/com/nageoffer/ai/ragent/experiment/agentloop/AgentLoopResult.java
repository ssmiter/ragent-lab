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

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Loop 执行结果
 *
 * <p>记录整个循环的执行过程和最终结果</p>
 */
@Data
@Builder
public class AgentLoopResult {

    /**
     * 终止原因
     */
    public enum TerminationReason {
        /** 模型没有调用工具，返回最终回答 */
        COMPLETED,
        /** 达到最大轮次限制 */
        MAX_TURNS_REACHED,
        /** 发生错误 */
        ERROR
    }

    /**
     * 终止原因
     */
    private TerminationReason reason;

    /**
     * 最终响应内容
     */
    private String finalResponse;

    /**
     * 总轮次
     */
    private int turnCount;

    /**
     * 工具调用历史
     */
    @Builder.Default
    private List<ToolCallRecord> toolCallHistory = new ArrayList<>();

    /**
     * 错误信息（如果有）
     */
    private String errorMessage;

    /**
     * 单次工具调用记录
     */
    @Data
    @Builder
    public static class ToolCallRecord {
        /** 轮次 */
        private int turn;
        /** 工具名 */
        private String toolName;
        /** 工具调用 ID */
        private String toolCallId;
        /** 输入参数 */
        private String input;
        /** 执行结果 */
        private String output;
        /** 是否成功 */
        private boolean success;
    }

    /**
     * 创建成功结果
     */
    public static AgentLoopResult completed(String finalResponse, int turnCount, List<ToolCallRecord> history) {
        return AgentLoopResult.builder()
                .reason(TerminationReason.COMPLETED)
                .finalResponse(finalResponse)
                .turnCount(turnCount)
                .toolCallHistory(history)
                .build();
    }

    /**
     * 创建最大轮次结果
     */
    public static AgentLoopResult maxTurnsReached(int turnCount, List<ToolCallRecord> history) {
        return AgentLoopResult.builder()
                .reason(TerminationReason.MAX_TURNS_REACHED)
                .turnCount(turnCount)
                .toolCallHistory(history)
                .finalResponse("达到最大轮次限制 (" + turnCount + " 轮)，请简化问题或增加轮次限制。")
                .build();
    }

    /**
     * 创建错误结果
     */
    public static AgentLoopResult error(String errorMessage, int turnCount, List<ToolCallRecord> history) {
        return AgentLoopResult.builder()
                .reason(TerminationReason.ERROR)
                .errorMessage(errorMessage)
                .turnCount(turnCount)
                .toolCallHistory(history)
                .build();
    }

    /**
     * 打印执行报告
     */
    public String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== Agent Loop 执行报告 ==========\n");
        sb.append("终止原因: ").append(reason).append("\n");
        sb.append("总轮次: ").append(turnCount).append("\n");
        sb.append("工具调用次数: ").append(toolCallHistory.size()).append("\n");

        if (!toolCallHistory.isEmpty()) {
            sb.append("\n工具调用历史:\n");
            for (ToolCallRecord record : toolCallHistory) {
                sb.append(String.format("  [Turn %d] %s(%s) -> %s\n",
                        record.getTurn(),
                        record.getToolName(),
                        record.getInput(),
                        record.isSuccess() ? "✓" : "✗"));
                if (!record.isSuccess()) {
                    sb.append("    错误: ").append(record.getOutput()).append("\n");
                }
            }
        }

        sb.append("\n最终响应:\n");
        sb.append(finalResponse).append("\n");
        sb.append("========================================\n");
        return sb.toString();
    }
}