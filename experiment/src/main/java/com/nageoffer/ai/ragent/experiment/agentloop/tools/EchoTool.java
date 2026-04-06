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

package com.nageoffer.ai.ragent.experiment.agentloop.tools;

import com.nageoffer.ai.ragent.experiment.agentloop.Tool;
import com.nageoffer.ai.ragent.experiment.agentloop.ToolResult;

import java.util.Map;

/**
 * Echo 工具 - 最简单的测试工具
 *
 * <p>输入什么就返回什么，用于验证 Agent Loop 循环骨架是否正常工作。</p>
 *
 * <p>测试场景：</p>
 * <pre>
 * 输入："Please echo the message 'hello world'"
 * 预期：模型调用 echo 工具 → 得到结果 → 模型生成最终回答（包含 "hello world"）→ 循环终止
 * </pre>
 */
public class EchoTool implements Tool {

    @Override
    public String getName() {
        return "echo";
    }

    @Override
    public String getDescription() {
        return "Echo back the input message. Use this tool when you want to repeat or confirm something.";
    }

    @Override
    public String getInputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "The message to echo back"
                }
              },
              "required": ["message"]
            }
            """;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String message = (String) input.get("message");
        if (message == null || message.isBlank()) {
            return ToolResult.error(getName(), "message parameter is required");
        }
        return ToolResult.success(getName(), "Echo: " + message);
    }
}