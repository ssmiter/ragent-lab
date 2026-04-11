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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.mcp.tools.KnowledgeSearchWithRerankMCPExecutor;
import com.nageoffer.ai.ragent.mcp.tools.QueryRewriteMCPExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent Loop 实验 3 — 跨知识库综合检索
 *
 * <p>核心假设：</p>
 * <blockquote>
 * 模型能自主选择知识库，搜完一个发现信息不够时自动搜另一个，最后综合多个来源生成回答。
 * </blockquote>
 *
 * <p>测试问题分三组：</p>
 * <ul>
 *   <li>A组（4个）：单库问题，每个明确属于某一个知识库</li>
 *   <li>B组（4个）：跨库问题，需要搜多个知识库</li>
 *   <li>C组（2个）：边界/刁难问题</li>
 * </ul>
 */
public class AgentLoopExperiment3 {

    /**
     * 知识库助手的 System Prompt（多知识库版本）
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 知识库说明

你可以搜索三个不同的知识库：

1. **ragentdocs** — Ragent 项目文档
   - 内容：系统架构、RAG 实现、MCP 工具、意图树、分块策略、前端技术栈等
   - 适用：关于 Ragent 系统本身的问题

2. **ssddocs** — SSD 理论文档
   - 内容：状态空间模型（State Space Model）、SSD 原论文理论、数学推导
   - 适用：关于 SSD 原始理论和方法的问题

3. **dualssddocs** — DualSSD 创新文档
   - 内容：DualSSD 的创新设计、块级别状态管理、对 SSD 的改进
   - 适用：关于 DualSSD 具体创新和实现的问题

# 你的工具

1. **rewrite_query** — 将模糊或口语化的查询改写为更精确的检索查询
   - 当用户问题模糊、口语化时使用
   - 当用户问题已经清晰具体时，跳过重写

2. **knowledge_search_with_rerank** — 搜索指定知识库
   - 每次调用必须指定 collection 参数
   - 返回 Rerank 分数（通常 0.7-0.95）

# 决策流程

收到用户问题后：

1. **判断问题涉及哪个知识库（可能多个）**
   - 明确属于某一个 → 搜那一个
   - 涉及多个主题 → 分别搜索，综合结果
   - 不确定属于哪个 → 从最可能的开始，根据结果决定是否搜其他库

2. **搜索并评估结果**
   - 分数 > 0.85: 高度相关，可直接回答
   - 分数 0.70-0.85: 中等相关，可参考但可能不完整
   - 分数 < 0.70: 低相关，考虑换知识库或换查询角度

3. **需要时搜索多个知识库**
   - 对比类问题（"A 和 B 有什么区别"）→ 两边都搜
   - 综合类问题（"A 怎么应用于 B"）→ 两边都搜后综合
   - 搜了一个库发现信息不足 → 尝试另一个库

4. **回答时注明信息来源**
   - 告诉用户信息来自哪个知识库
   - 如果综合了多个知识库，分别说明

# 语言
始终使用中文回答用户问题。
""";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Agent Loop 实验 3 — 跨知识库综合检索                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // API Key 配置
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "sk-e468852b76324d17b8131d6d8a58dda8";
        }
        if (args.length > 0) {
            apiKey = args[0];
        }

        System.out.println("API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        System.out.println();

        // 配置百炼 API
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://dashscope.aliyuncs.com");
        providerConfig.setApiKey(apiKey);

        AIModelProperties.ModelCandidate modelCandidate = new AIModelProperties.ModelCandidate();
        modelCandidate.setModel("glm-5");

        // 注册两个工具
        Map<String, Tool> tools = new HashMap<>();

        QueryRewriteMCPExecutor rewriteExecutor = new QueryRewriteMCPExecutor();
        McpToolAdapter rewriteTool = new McpToolAdapter(rewriteExecutor);
        tools.put(rewriteTool.getName(), rewriteTool);

        KnowledgeSearchWithRerankMCPExecutor searchExecutor = new KnowledgeSearchWithRerankMCPExecutor();
        McpToolAdapter searchTool = new McpToolAdapter(searchExecutor);
        tools.put(searchTool.getName(), searchTool);

        System.out.println("配置信息:");
        System.out.println("  - 提供商: 百炼 (DashScope)");
        System.out.println("  - 模型: qwen-plus-latest");
        System.out.println("  - 工具: " + tools.keySet());
        System.out.println("  - 知识库: ragentdocs, ssddocs, dualssddocs");
        System.out.println();

        System.out.println("测试问题:");
        System.out.println("  A组（单库问题）:");
        System.out.println("    Q1: Ragent 系统的整体架构是什么？ → 预期: ragentdocs");
        System.out.println("    Q2: SSD 模型的核心数学原理是什么？ → 预期: ssddocs");
        System.out.println("    Q3: DualSSD 的块级别状态管理是怎么实现的？ → 预期: dualssddocs");
        System.out.println("    Q4: 意图树的节点是怎么配置的？ → 预期: ragentdocs");
        System.out.println("  B组（跨库问题）:");
        System.out.println("    Q5: DualSSD 相比原始 SSD 改进了什么？ → 预期: ssddocs + dualssddocs");
        System.out.println("    Q6: SSD 的理论在 DualSSD 中是怎么被应用的？ → 预期: ssddocs + dualssddocs");
        System.out.println("    Q7: Ragent 系统中有没有用到状态空间模型的思想？ → 预期: 可能跨库搜索");
        System.out.println("    Q8: 块级别状态和 SSD 原始的状态表示有什么区别？ → 预期: ssddocs + dualssddocs");
        System.out.println("  C组（边界问题）:");
        System.out.println("    Q9: 这三个项目之间是什么关系？ → 预期: 可能搜所有三个库");
        System.out.println("    Q10: 状态空间模型最近有什么新进展？ → 预期: 可能搜不到，诚实告知");
        System.out.println();

        // 创建 AgentLoop
        AgentLoop agentLoop = new AgentLoop(
                null, providerConfig, modelCandidate, tools, 15, SYSTEM_PROMPT);

        // 选择测试问题
        String questionId = args.length > 1 ? args[1] : "Q7";
        String userQuery = getTestQuestion(questionId);

        System.out.println("当前测试: " + questionId);
        System.out.println("用户问题: " + userQuery);
        System.out.println();

        AgentLoopResult result = agentLoop.run(userQuery);

        System.out.println("\n" + result.getReport());
    }

    private static String getTestQuestion(String id) {
        return switch (id) {
            case "Q1" -> "Ragent 系统的整体架构是什么？";
            case "Q2" -> "SSD 模型的核心数学原理是什么？";
            case "Q3" -> "DualSSD 的块级别状态管理是怎么实现的？";
            case "Q4" -> "意图树的节点是怎么配置的？";
            case "Q5" -> "DualSSD 相比原始 SSD 改进了什么？";
            case "Q6" -> "SSD 的理论在 DualSSD 中是怎么被应用的？";
            case "Q7" -> "Ragent 系统中有没有用到状态空间模型的思想？或者你认为状态空间模型块级别的状态迁移到RAG中有无可行之处，请给出你的看法";
            case "Q8" -> "块级别状态和 SSD 原始的状态表示有什么区别？";
            case "Q9" -> "这三个项目之间是什么关系？";
            case "Q10" -> "状态空间模型最近有什么新进展？";
            default -> id;
        };
    }
}