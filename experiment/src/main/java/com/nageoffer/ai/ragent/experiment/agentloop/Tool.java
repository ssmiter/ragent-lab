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

import java.util.Map;

/**
 * Agent Loop 的工具接口
 *
 * <p>对标 CC 源码中的 Tool 接口，最小化版本。
 * 每个工具需要提供名称、描述、输入 Schema 和执行逻辑。</p>
 *
 * <p>工具定义会被序列化为 OpenAI 兼容的 function calling 格式：</p>
 * <pre>
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "echo",
 *     "description": "Echo back the input message...",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "message": { "type": "string", "description": "..." }
 *       },
 *       "required": ["message"]
 *     }
 *   }
 * }
 * </pre>
 */
public interface Tool {

    /**
     * 工具名称
     * <p>用于模型调用时标识工具，需要唯一</p>
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 工具描述
     * <p>告诉模型这个工具做什么，什么时候应该使用</p>
     *
     * @return 工具描述文档
     */
    String getDescription();

    /**
     * 输入参数的 JSON Schema
     * <p>定义工具接受的参数结构，使用 JSON Schema 格式</p>
     *
     * <p>示例：</p>
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "message": {
     *       "type": "string",
     *       "description": "The message to echo back"
     *     }
     *   },
     *   "required": ["message"]
     * }
     * </pre>
     *
     * @return JSON Schema 字符串
     */
    String getInputSchema();

    /**
     * 执行工具
     *
     * @param input 工具输入参数，key 为参数名，value 为参数值
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> input);

    /**
     * 将工具转换为 OpenAI function calling 格式
     *
     * @return function definition JSON 字符串
     */
    default String toFunctionDefinition() {
        return String.format("""
            {
              "type": "function",
              "function": {
                "name": "%s",
                "description": "%s",
                "parameters": %s
              }
            }
            """,
            getName(),
            getDescription().replace("\"", "\\\""),
            getInputSchema()
        );
    }
}