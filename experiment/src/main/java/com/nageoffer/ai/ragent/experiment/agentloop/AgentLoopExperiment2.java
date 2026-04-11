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
 * Agent Loop 实验 2 — 多工具编排（rewrite_query + knowledge_search）
 *
 * <p>核心假设：</p>
 * <blockquote>
 * Agent Loop 的"按需重写"优于 Pipeline 的"无脑总是重写"。
 * 模型能自主判断：清晰问题直接搜，模糊问题先重写再搜。
 * </blockquote>
 *
 * <p>测试问题分两组：</p>
 * <ul>
 *   <li>A组（6个）：正常技术问题，预期不需要重写</li>
 *   <li>B组（4个）：刁难问题，口语化/模糊，预期需要重写</li>
 * </ul>
 */
public class AgentLoopExperiment2 {

    /**
     * 知识库助手的 System Prompt（双工具版本）
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 你的工具

你有两个工具：

1. **rewrite_query** — 将模糊或口语化的查询改写为更精确的检索查询
   - 当用户问题模糊、口语化、或包含不精确的表述时使用
   - 当用户问题已经清晰具体时，跳过重写，直接搜索

2. **knowledge_search_with_rerank** — 在知识库中搜索相关信息，使用向量检索+Rerank精排。分数通常在0.7-0.95区间。
   - 使用精确的关键词或短语
   - 观察返回的分数判断结果质量

# 决策流程

收到用户问题后，你需要判断：

**路径A — 直接搜索：** 如果问题清晰、使用了技术术语、目标明确
  → 直接调用 knowledge_search_with_rerank

**路径B — 先重写再搜索：** 如果问题模糊、口语化、或你不确定最佳检索词
  → 先调用 rewrite_query 获得更精确的查询
  → 然后用重写后的查询调用 knowledge_search_with_rerank

**路径C — 搜索后重试：** 如果首次搜索结果分数低于 0.75
  → 考虑用不同角度的查询再搜一次（可以先 rewrite_query 再搜，或直接换关键词）
  → 最多重试 2 次

# 分数判断

观察 knowledge_search_with_rerank 返回的 Rerank 分数：
- 分数 > 0.85: 高度相关，信息准确，可以直接基于搜索结果回答
- 分数 0.75-0.85: 中等相关，可信参考，可以回答但可能需要补充
- 分数 < 0.75: 低相关，建议换一个查询重新搜索

# 注意事项

1. 不是每个问题都需要重写。对于清晰的技术问题，直接搜索通常更有效。
2. rewrite_query 返回的 confidence 信息可以帮助你判断重写质量。
3. 回答时引用来源文档名（来源字段）。
4. 如果找不到相关信息，诚实告知用户。

# 语言
始终使用中文回答用户问题。
""";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Agent Loop 实验 2 — 多工具编排（rewrite + search）          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // API Key 配置
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "sk-e468852b76324d17b8131d6d8a58dda8"; // 默认
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
        modelCandidate.setModel("qwen-plus-latest");

        // ===== 注册两个工具 =====
        Map<String, Tool> tools = new HashMap<>();

        // 查询重写工具
        QueryRewriteMCPExecutor rewriteExecutor = new QueryRewriteMCPExecutor();
        McpToolAdapter rewriteTool = new McpToolAdapter(rewriteExecutor);
        tools.put(rewriteTool.getName(), rewriteTool);

        // 检索工具（带 Rerank）
        KnowledgeSearchWithRerankMCPExecutor searchExecutor = new KnowledgeSearchWithRerankMCPExecutor();
        McpToolAdapter searchTool = new McpToolAdapter(searchExecutor);
        tools.put(searchTool.getName(), searchTool);

        System.out.println("配置信息:");
        System.out.println("  - 提供商: 百炼 (DashScope)");
        System.out.println("  - 模型: qwen-plus-latest");
        System.out.println("  - 工具: " + tools.keySet());
        System.out.println("  - 最大轮次: 10");
        System.out.println("  - System Prompt: 双工具编排版本");
        System.out.println();

        System.out.println("测试问题:");
        System.out.println("  A组（正常问题，预期不需要重写）:");
        System.out.println("    Q1: Ragent系统的整体架构是什么？");
        System.out.println("    Q2: 分块策略是怎么实现的？");
        System.out.println("    Q3: 意图识别的流程是什么？");
        System.out.println("    Q4: MCP工具是怎么注册和调用的？");
        System.out.println("    Q5: 多模型降级策略怎么工作？");
        System.out.println("    Q6: 前端是什么技术栈？");
        System.out.println("  B组（刁难问题，预期需要重写）:");
        System.out.println("    Q7: 之前看过一个什么自适应的东西，好像和分数有关？");
        System.out.println("    Q8: MCP 工具注册后是怎么被 Agent Loop 调用的？");
        System.out.println("    Q9: ragent 有没有用 LangChain？");
        System.out.println("    Q10: 那个把文档切成小块的功能咋实现的");
        System.out.println();

        // 创建 AgentLoop
        AgentLoop agentLoop = new AgentLoop(
                null, providerConfig, modelCandidate, tools, 10, SYSTEM_PROMPT);

        // 选择测试问题（可通过 args[1] 指定）
        String questionId = args.length > 1 ? args[1] : "Q7";
        String userQuery = getTestQuestion(questionId);

        System.out.println("当前测试: " + questionId);
        System.out.println("用户问题: " + userQuery);
        System.out.println();

        AgentLoopResult result = agentLoop.run(userQuery);

        // 打印报告
        System.out.println("\n" + result.getReport());
    }

    /**
     * 获取测试问题
     */
    private static String getTestQuestion(String id) {
        return switch (id) {
            case "Q1" -> "Ragent系统的整体架构是什么？";
            case "Q2" -> "分块策略是怎么实现的？";
            case "Q3" -> "意图识别的流程是什么？";
            case "Q4" -> "MCP工具是怎么注册和调用的？";
            case "Q5" -> "多模型降级策略怎么工作？";
            case "Q6" -> "前端是什么技术栈？";
            case "Q7" -> "之前看过一个什么自适应的东西，好像和分数有关？";
            case "Q8" -> "MCP 工具注册后是怎么被 Agent Loop 调用的？";
            case "Q9" -> "ragent 有没有用 LangChain？";
            case "Q10" -> "那个把文档切成小块的功能咋实现的";
            default -> id; // 如果不匹配，直接用输入作为问题
        };
    }
}