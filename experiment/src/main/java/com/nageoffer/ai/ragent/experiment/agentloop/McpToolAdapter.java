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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具适配器 - 将 MCPToolExecutor 适配为 Agent Loop 的 Tool 接口
 *
 * <p>核心价值：</p>
 * <ul>
 *   <li>工具只需要实现一次（MCPToolExecutor）</li>
 *   <li>外部 Agent（如 Claude Code）通过 MCP 协议调用</li>
 *   <li>内部 Agent Loop 通过适配器直接调用（进程内，零 HTTP）</li>
 *   <li>模型看到的工具定义完全一致</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>
 * MCPToolExecutor searchExecutor = new KnowledgeSearchMCPExecutor();
 * Tool tool = new McpToolAdapter(searchExecutor);
 * agentLoop.registerTool(tool);
 * </pre>
 */
public class McpToolAdapter implements Tool {

    private final MCPToolExecutor executor;
    private final MCPToolDefinition definition;
    private final Gson gson = new Gson();

    /**
     * 创建适配器
     *
     * @param executor MCP 工具执行器
     */
    public McpToolAdapter(MCPToolExecutor executor) {
        this.executor = executor;
        this.definition = executor.getToolDefinition();
    }

    @Override
    public String getName() {
        return definition.getToolId();
    }

    @Override
    public String getDescription() {
        return definition.getDescription();
    }

    @Override
    public String getInputSchema() {
        // 构建 JSON Schema
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        List<String> requiredFields = new ArrayList<>();

        Map<String, MCPToolDefinition.ParameterDef> params = definition.getParameters();
        if (params != null) {
            for (Map.Entry<String, MCPToolDefinition.ParameterDef> entry : params.entrySet()) {
                String paramName = entry.getKey();
                MCPToolDefinition.ParameterDef paramDef = entry.getValue();

                JsonObject paramSchema = new JsonObject();
                paramSchema.addProperty("type", paramDef.getType() != null ? paramDef.getType() : "string");

                if (paramDef.getDescription() != null) {
                    paramSchema.addProperty("description", paramDef.getDescription());
                }

                if (paramDef.getDefaultValue() != null) {
                    paramSchema.add("default", gson.toJsonTree(paramDef.getDefaultValue()));
                }

                if (paramDef.getEnumValues() != null && !paramDef.getEnumValues().isEmpty()) {
                    JsonArray enumArray = new JsonArray();
                    for (String enumValue : paramDef.getEnumValues()) {
                        enumArray.add(enumValue);
                    }
                    paramSchema.add("enum", enumArray);
                }

                properties.add(paramName, paramSchema);

                if (paramDef.isRequired()) {
                    requiredFields.add(paramName);
                }
            }
        }

        schema.add("properties", properties);

        if (!requiredFields.isEmpty()) {
            JsonArray requiredArray = new JsonArray();
            for (String field : requiredFields) {
                requiredArray.add(field);
            }
            schema.add("required", requiredArray);
        }

        return gson.toJson(schema);
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 构建 MCPToolRequest
        MCPToolRequest request = MCPToolRequest.builder()
                .toolId(getName())
                .parameters(new HashMap<>(input))
                .build();

        // 调用 MCP 执行器（进程内）
        MCPToolResponse response = executor.execute(request);

        // 转换为 ToolResult
        if (response.isSuccess()) {
            String content = response.getTextResult();
            return ToolResult.success(getName(), content != null ? content : "");
        } else {
            String errorMsg = response.getErrorMessage();
            return ToolResult.error(getName(), errorMsg != null ? errorMsg : "Unknown error");
        }
    }
}