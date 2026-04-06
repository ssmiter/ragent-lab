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

import com.nageoffer.ai.ragent.experiment.agentloop.tools.EchoTool;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent Loop 实验 0 - 空转循环验证
 *
 * <p>这是验证 Agent Loop 骨架是否正常工作的最简单测试。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * # 方式 1：直接运行 main 方法
 * mvn -pl experiment exec:java -Dexec.mainClass=com.nageoffer.ai.ragent.experiment.agentloop.AgentLoopExperiment
 *
 * # 方式 2：配置 API Key 后运行
 * DASHSCOPE_API_KEY=your-api-key mvn -pl experiment exec:java ...
 * </pre>
 *
 * <p>预期结果：</p>
 * <ol>
 *   <li>循环执行 1-2 轮</li>
 *   <li>模型调用 echo 工具</li>
 *   <li>工具返回 "Echo: hello world"</li>
 *   <li>模型生成最终回答，循环终止</li>
 * </ol>
 */
public class AgentLoopExperiment {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Agent Loop 实验 0 — 空转循环验证                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // 从环境变量或命令行参数获取 API Key
//        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String apiKey = "sk-e468852b76324d17b8131d6d8a58dda8";
        if (apiKey == null || apiKey.isBlank()) {
            // 尝试从命令行参数获取
            if (args.length > 0) {
                apiKey = args[0];
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：缺少 API Key");
            System.err.println("请通过环境变量 DASHSCOPE_API_KEY 或命令行参数提供");
            System.err.println("示例: DASHSCOPE_API_KEY=sk-xxx mvn -pl experiment exec:java ...");
            System.exit(1);
        }

        System.out.println("API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        System.out.println();

        // 配置百炼 API
        Map<String, Object> providerConfig = new HashMap<>();
        providerConfig.put("url", "https://dashscope.aliyuncs.com");
        providerConfig.put("apiKey", apiKey);

        // 配置模型
        Map<String, Object> modelConfig = new HashMap<>();
        modelConfig.put("model", "qwen-plus-latest");  // 使用 qwen-plus 模型

        // 注册工具
        Map<String, Tool> tools = new HashMap<>();
        tools.put("echo", new EchoTool());

        System.out.println("配置信息:");
        System.out.println("  - 提供商: 百炼 (DashScope)");
        System.out.println("  - 模型: qwen-plus-latest");
        System.out.println("  - 工具: " + tools.keySet());
        System.out.println("  - 最大轮次: 10");
        System.out.println();

        // 创建 AgentLoop
        // 注意：这里需要适配现有的配置类，暂时使用简化的配置
        AgentLoop agentLoop = createAgentLoop(apiKey, tools, 10);

        // 运行测试
        String userQuery = "Please use the echo tool to echo the message 'hello world'. " +
                "After receiving the result, provide a brief summary.";

        System.out.println("用户问题: " + userQuery);
        System.out.println();

        AgentLoopResult result = agentLoop.run(userQuery);

        // 打印报告
        System.out.println("\n" + result.getReport());
    }

    /**
     * 创建 AgentLoop 实例
     *
     * <p>这里简化了配置，直接使用必要的参数创建实例</p>
     */
    private static AgentLoop createAgentLoop(String apiKey, Map<String, Tool> tools, int maxTurns) {
        // 创建简化的配置对象
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://dashscope.aliyuncs.com");
        providerConfig.setApiKey(apiKey);

        AIModelProperties.ModelCandidate modelCandidate = new AIModelProperties.ModelCandidate();
        modelCandidate.setModel("qwen-plus-latest");

        return new AgentLoop(null, providerConfig, modelCandidate, tools, maxTurns);
    }
}