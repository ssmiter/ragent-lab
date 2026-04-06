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

import java.util.HashMap;
import java.util.Map;

/**
 * Agent Loop 实验 1 - 真正的 Agent Loop
 *
 * <p>实验目标：将 Agent Loop 骨架升级为能回答知识库问题的完整 Agent。</p>
 *
 * <p>关键改进：</p>
 * <ol>
 *   <li>添加 System Prompt（引导模型行为）</li>
 *   <li>KnowledgeSearchTool（真实的向量检索能力）</li>
 *   <li>模型自主决定搜索策略（重试、换查询）</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * # 确保 ragent 服务启动（提供向量检索接口）
 * # 然后在 IDEA 中运行此 main 方法
 * </pre>
 *
 * <p>预期结果：</p>
 * <ol>
 *   <li>模型先调用 knowledge_search 搜索知识库</li>
 *   <li>根据分数判断结果质量（高 → 直接回答，低 → 重试）</li>
 *   <li>最终生成基于检索结果的专业回答</li>
 * </ol>
 */
public class AgentLoopExperiment1 {

    /**
     * 知识库助手的 System Prompt
     *
     * <p>基于 CC System Prompt 设计模式：</p>
     * <ul>
     *   <li>角色定义：明确助手身份</li>
     *   <li>工具说明：告诉模型有什么工具可用</li>
     *   <li>行为准则：指导模型如何决策</li>
     *   <li>格式要求：回答风格和语言</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
你是一个知识库助手，帮助用户检索和回答知识库相关问题。

# 可用工具
你有以下工具可用：
- knowledge_search: 在知识库中搜索相关信息。返回按相关性排序的文本片段和分数。

# 行为指导
1. 收到用户问题后，先使用 knowledge_search 搜索知识库
2. 观察搜索结果的分数：
   - 分数 > 0.8: 高度相关，可以直接基于搜索结果回答
   - 分数 0.6-0.8: 中等相关，可以参考，但可能需要补充解释
   - 分数 < 0.6: 低相关，建议换一个查询重新搜索
3. 如果第一次搜索结果分数较低（< 0.6），尝试：
   - 使用更具体的关键词
   - 使用文档中可能出现的专业术语
   - 拆解问题，搜索子问题
4. 最多重试 2 次搜索，如果仍然找不到相关信息，诚实告知用户
5. 回答时引用来源文档名（source 字段）

# 语言
始终使用中文回答用户问题。
""";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Agent Loop 实验 1 — 真正的 Agent Loop                  ║");
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

        // 注册工具
        Map<String, Tool> tools = new HashMap<>();
        tools.put("knowledge_search", new KnowledgeSearchTool());

        System.out.println("配置信息:");
        System.out.println("  - 提供商: 百炼 (DashScope)");
        System.out.println("  - 模型: qwen-plus-latest");
        System.out.println("  - 工具: " + tools.keySet());
        System.out.println("  - 最大轮次: 10");
        System.out.println("  - System Prompt: 已配置");
        System.out.println();

        System.out.println("注意：请确保 ragent 服务已启动（localhost:9090），否则检索会失败");
        System.out.println();

        // 创建 AgentLoop（带 System Prompt）
        AgentLoop agentLoop = new AgentLoop(
                null, providerConfig, modelCandidate, tools, 10, SYSTEM_PROMPT);

        // 测试问题
        String userQuery = "Ragent的当前选择的向量数据库是什么";

        System.out.println("用户问题: " + userQuery);
        System.out.println();

        AgentLoopResult result = agentLoop.run(userQuery);

        // 打印报告
        System.out.println("\n" + result.getReport());
    }
}