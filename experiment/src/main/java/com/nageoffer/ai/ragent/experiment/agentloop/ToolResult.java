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

/**
 * 工具执行结果
 *
 * <p>对应 OpenAI function calling 响应中的 tool_call 结果，
 * 需要以特定格式回传给模型继续对话。</p>
 *
 * <p>成功时的响应格式：</p>
 * <pre>
 * {
 *   "tool_call_id": "call_xxx",
 *   "role": "tool",
 *   "content": "Echo: hello world"
 * }
 * </pre>
 *
 * <p>失败时需要设置 isError=true：</p>
 * <pre>
 * {
 *   "tool_call_id": "call_xxx",
 *   "role": "tool",
 *   "content": "Error: ...",
 *   "is_error": true
 * }
 * </pre>
 */
@Data
@Builder
public class ToolResult {

    /**
     * 工具调用 ID
     * <p>对应模型响应中 tool_call 的 id，用于关联请求和响应</p>
     */
    private String toolCallId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 执行结果内容
     */
    private String content;

    /**
     * 是否为错误结果
     * <p>当工具执行失败时设为 true，模型会看到错误信息并可能重试</p>
     */
    @Builder.Default
    private boolean isError = false;

    /**
     * 创建成功结果
     */
    public static ToolResult success(String toolName, String content) {
        return ToolResult.builder()
                .toolName(toolName)
                .content(content)
                .isError(false)
                .build();
    }

    /**
     * 创建错误结果
     */
    public static ToolResult error(String toolName, String errorMessage) {
        return ToolResult.builder()
                .toolName(toolName)
                .content("<tool_use_error>" + errorMessage + "</tool_use_error>")
                .isError(true)
                .build();
    }

    /**
     * 转换为 API 请求格式
     * <p>用于构造回传给模型的 tool 消息</p>
     */
    public String toToolMessage() {
        if (isError) {
            return String.format("""
                {"role": "tool", "tool_call_id": "%s", "content": "%s"}
                """,
                toolCallId,
                escapeJson(content)
            );
        }
        return String.format(
                "{\"role\": \"tool\", \"tool_call_id\": \"%s\", \"content\": \"%s\"}",
                toolCallId,
                escapeJson(content)
        );
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}