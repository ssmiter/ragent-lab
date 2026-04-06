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

import com.nageoffer.ai.ragent.experiment.agentloop.tools.KnowledgeSearchTool;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.mcp.tools.KnowledgeSearchWithRerankMCPExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent Loop 实验 1.5 - 统一工具接口 + Rerank 检索
 *
 * <p>改进点：</p>
 * <ol>
 *   <li>使用 McpToolAdapter 统一 MCP 和 Agent Loop 工具接口</li>
 *   <li>检索工具走 rerank 链路，分数更准确（0.7-0.95 区间）</li>
 *   <li>System Prompt 阈值根据 rerank 分数分布调整</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * # 确保 ragent 服务启动（提供检索 + rerank 接口）
 * # 然后在 IDEA 中运行此 main 方法
 * </pre>
 */
public class AgentLoopExperiment1_5 {

    /**
     * 知识库助手的 System Prompt（针对 Rerank 分数调整）
     * Rerank 分数通常在 0.7-0.95 区间
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 可用工具
你有以下工具可用：
- knowledge_search_with_rerank: 在知识库中搜索相关信息，使用向量检索+Rerank精排。分数通常在0.7-0.95区间。

# 行为指导
1. 收到用户问题后，先使用 knowledge_search_with_rerank 搜索知识库
2. 观察搜索结果的 Rerank 分数：
   - 分数 > 0.85: 高度相关，信息准确，可以直接基于搜索结果回答
   - 分数 0.75-0.85: 中等相关，可信参考，可以回答但可能需要补充
   - 分数 < 0.75: 低相关，建议换一个查询重新搜索
3. 如果第一次搜索结果分数较低（< 0.75），尝试：
   - 使用更具体的关键词
   - 使用文档中可能出现的专业术语
   - 拆解问题，搜索子问题
4. 最多重试 2 次搜索，如果仍然找不到相关信息，诚实告知用户
5. 回答时引用来源文档名（来源字段）

# 语言
始终使用中文回答用户问题。
""";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Agent Loop 实验 1.5 — 统一接口 + Rerank 检索                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // API Key 配置
        String apiKey = "sk-e468852b76324d17b8131d6d8a58dda8";
        if (apiKey == null || apiKey.isBlank()) {
            if (args.length > 0) {
                apiKey = args[0];
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：缺少 API Key");
            System.err.println("请通过环境变量 DASHSCOPE_API_KEY 或命令行参数提供");
            System.exit(1);
        }

        System.out.println("API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        System.out.println();

        // 配置百炼 API
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://dashscope.aliyuncs.com");
        providerConfig.setApiKey(apiKey);

        AIModelProperties.ModelCandidate modelCandidate = new AIModelProperties.ModelCandidate();
        modelCandidate.setModel("qwen-plus-latest");

        // ===== 使用 McpToolAdapter 注册 MCP 工具 =====
        Map<String, Tool> tools = new HashMap<>();

        // 创建 MCP 执行器（带 Rerank）
        KnowledgeSearchWithRerankMCPExecutor mcpExecutor = new KnowledgeSearchWithRerankMCPExecutor();

        // 通过适配器转换为 Agent Loop 的 Tool 接口
        McpToolAdapter adapter = new McpToolAdapter(mcpExecutor);
        tools.put(adapter.getName(), adapter);

        System.out.println("配置信息:");
        System.out.println("  - 提供商: 百炼 (DashScope)");
        System.out.println("  - 模型: qwen-plus-latest");
        System.out.println("  - 工具: " + tools.keySet());
        System.out.println("  - 最大轮次: 10");
        System.out.println("  - System Prompt: 已配置（Rerank 阈值调整）");
        System.out.println();
        System.out.println("  - 接口统一: McpToolAdapter");
        System.out.println("  - 检索链路: 向量检索 + Rerank 精排");
        System.out.println();

        System.out.println("注意：请确保 ragent 服务已启动（localhost:9090），否则检索会失败");
        System.out.println();

        // 创建 AgentLoop（带 System Prompt）
        AgentLoop agentLoop = new AgentLoop(
                null, providerConfig, modelCandidate, tools, 10, SYSTEM_PROMPT);

        // 测试问题
        String userQuery = "Ragent系统的整体架构是什么";

        System.out.println("用户问题: " + userQuery);
        System.out.println();

        AgentLoopResult result = agentLoop.run(userQuery);

        // 打印报告
        System.out.println("\n" + result.getReport());
    }
}