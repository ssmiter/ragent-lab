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

package com.nageoffer.ai.ragent.rag.agentloop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统信息查询工具
 *
 * <p>提供系统元信息查询能力，包括知识库列表、意图树结构、系统能力说明。
 * 当用户询问"系统有哪些知识库"、"系统能做什么"等元信息问题时使用。</p>
 *
 * <p>数据来源：</p>
 * <ul>
 *   <li>知识库列表：从 t_knowledge_base 表获取</li>
 *   <li>意图树结构：从 t_intent_node 表获取</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SystemInfoTool implements Tool {

    private static final String TOOL_NAME = "system_info_query";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentNodeMapper intentNodeMapper;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return """
Query system metadata and information. Use this tool when the user asks about:

1. What knowledge bases are available in the system (e.g., "系统有哪些知识库", "有哪些知识库")
2. What the system can do (e.g., "你能做什么", "系统功能有哪些")
3. System capabilities and processing modes
4. Intent tree structure (domain categories the system can handle)

This tool returns real system information, not fabricated content. Always use this tool for system-level queries.
""";
    }

    @Override
    public String getInputSchema() {
        return """
{
  "type": "object",
  "properties": {
    "query_type": {
      "type": "string",
      "enum": ["knowledge_bases", "intent_tree", "system_capabilities", "all"],
      "description": "Type of information to query. 'knowledge_bases' for KB list, 'intent_tree' for domain categories, 'system_capabilities' for what the system can do, 'all' for complete overview.",
      "default": "all"
    }
  },
  "required": []
}
""";
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String queryType = getStringParam(input, "query_type", "all");

        try {
            StringBuilder result = new StringBuilder();

            switch (queryType) {
                case "knowledge_bases":
                    result.append(getKnowledgeBasesInfo());
                    break;
                case "intent_tree":
                    result.append(getIntentTreeInfo());
                    break;
                case "system_capabilities":
                    result.append(getSystemCapabilitiesInfo());
                    break;
                case "all":
                default:
                    result.append(getSystemOverview());
                    break;
            }

            log.info("system_info_query executed: queryType={}", queryType);
            return ToolResult.success(TOOL_NAME, result.toString());

        } catch (Exception e) {
            log.error("system_info_query execution failed", e);
            return ToolResult.error(TOOL_NAME, "查询系统信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取系统完整概览
     */
    private String getSystemOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统概览】\n\n");
        sb.append(getSystemCapabilitiesInfo()).append("\n\n");
        sb.append(getKnowledgeBasesInfo()).append("\n\n");
        sb.append(getIntentTreeInfo());
        return sb.toString();
    }

    /**
     * 获取知识库列表信息
     * 包含从意图树获取的语义描述和示例问题
     */
    private String getKnowledgeBasesInfo() {
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getDeleted, 0)
                        .orderByAsc(KnowledgeBaseDO::getId)
        );

        if (kbList.isEmpty()) {
            return "【知识库列表】\n当前系统未配置任何知识库。";
        }

        // 从意图树获取 KB 类型叶子节点的路由信息
        Map<String, KbRoutingInfo> routingInfoMap = getKbRoutingInfo();

        StringBuilder sb = new StringBuilder();
        sb.append("【知识库列表】共 ").append(kbList.size()).append(" 个知识库：\n\n");

        for (int i = 0; i < kbList.size(); i++) {
            KnowledgeBaseDO kb = kbList.get(i);
            String collectionName = kb.getCollectionName();
            KbRoutingInfo routingInfo = routingInfoMap.get(collectionName);

            sb.append((i + 1)).append(". ").append(kb.getName()).append("\n");
            sb.append("   - ID: ").append(kb.getId()).append("\n");
            sb.append("   - 向量集合: ").append(collectionName).append("\n");

            // 补充语义描述（如果有）
            if (routingInfo != null && routingInfo.description != null && !routingInfo.description.isBlank()) {
                sb.append("   - 内容范围: ").append(routingInfo.description).append("\n");
            }

            // 补充示例问题（如果有）
            if (routingInfo != null && routingInfo.examples != null && !routingInfo.examples.isBlank()) {
                String examplesStr = formatExamples(routingInfo.examples);
                if (!examplesStr.isBlank()) {
                    sb.append("   - 示例问题: ").append(examplesStr).append("\n");
                }
            }

            if (kb.getEmbeddingModel() != null) {
                sb.append("   - 嵌入模型: ").append(kb.getEmbeddingModel()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 从意图树获取 KB 类型叶子节点的路由信息
     */
    private Map<String, KbRoutingInfo> getKbRoutingInfo() {
        List<IntentNodeDO> kbLeafNodes = intentNodeMapper.selectList(
                new LambdaQueryWrapper<IntentNodeDO>()
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
                        // 只取 KB 类型
                        .and(w -> w.eq(IntentNodeDO::getKind, IntentKind.KB.getCode())
                                   .or().isNull(IntentNodeDO::getKind))
                        // 只取叶子节点（有 collectionName）
                        .isNotNull(IntentNodeDO::getCollectionName)
                        .ne(IntentNodeDO::getCollectionName, "")
        );

        return kbLeafNodes.stream()
                .filter(n -> n.getCollectionName() != null && !n.getCollectionName().isBlank())
                .collect(Collectors.toMap(
                        IntentNodeDO::getCollectionName,
                        n -> new KbRoutingInfo(n.getDescription(), n.getExamples()),
                        (a, b) -> a
                ));
    }

    /**
     * 格式化示例问题（从 JSON 数组转为可读文本）
     */
    private String formatExamples(String examplesJson) {
        if (examplesJson == null || examplesJson.isBlank()) {
            return "";
        }
        try {
            // 尝试解析 JSON 数组
            if (examplesJson.startsWith("[")) {
                List<String> examples = cn.hutool.json.JSONUtil.toList(examplesJson, String.class);
                if (examples.isEmpty()) {
                    return "";
                }
                // 取前 3 个示例，用 " / " 连接
                return examples.stream()
                        .limit(3)
                        .collect(Collectors.joining(" / "));
            }
            // 非数组格式，直接返回
            return examplesJson;
        } catch (Exception e) {
            // 解析失败，直接返回原始文本
            return examplesJson;
        }
    }

    /**
     * KB 路由信息（内部使用）
     */
    private record KbRoutingInfo(String description, String examples) {}

    /**
     * 获取意图树结构概览（只展示 KB 类型的节点）
     */
    private String getIntentTreeInfo() {
        List<IntentNodeDO> allNodes = intentNodeMapper.selectList(
                new LambdaQueryWrapper<IntentNodeDO>()
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
                        .orderByAsc(IntentNodeDO::getLevel, IntentNodeDO::getSortOrder)
        );

        if (allNodes.isEmpty()) {
            return "【领域分类】\n当前系统未配置意图树。";
        }

        // 筛选 KB 类型的节点
        List<IntentNodeDO> kbNodes = allNodes.stream()
                .filter(n -> n.getKind() == null || n.getKind() == IntentKind.KB.getCode())
                .collect(Collectors.toList());

        // 构建树形结构
        Map<String, List<IntentNodeDO>> childrenMap = kbNodes.stream()
                .filter(n -> n.getParentCode() != null)
                .collect(Collectors.groupingBy(IntentNodeDO::getParentCode));

        List<IntentNodeDO> roots = kbNodes.stream()
                .filter(n -> n.getParentCode() == null)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("【领域分类】系统可处理以下领域的问题：\n\n");

        for (IntentNodeDO root : roots) {
            appendNode(sb, root, childrenMap, 0);
        }

        return sb.toString();
    }

    /**
     * 递归展示节点
     */
    private void appendNode(StringBuilder sb, IntentNodeDO node,
                            Map<String, List<IntentNodeDO>> childrenMap, int depth) {
        String indent = "  ".repeat(depth);
        String prefix = depth == 0 ? "■ " : "● ";

        sb.append(indent).append(prefix).append(node.getName());
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append(" — ").append(node.getDescription());
        }
        sb.append("\n");

        List<IntentNodeDO> children = childrenMap.get(node.getIntentCode());
        if (children != null && !children.isEmpty()) {
            for (IntentNodeDO child : children) {
                appendNode(sb, child, childrenMap, depth + 1);
            }
        }
    }

    /**
     * 获取系统能力说明
     */
    private String getSystemCapabilitiesInfo() {
        return """
【系统能力】

本系统是一个智能问答助手，具备以下处理能力：

1. **知识库问答 (Pipeline模式)**
   - 快速检索知识库内容
   - 适合简单直接的问题查询
   - 自动识别意图并定向检索

2. **智能对话 (Agent模式)**
   - 支持复杂问题的多轮检索
   - 能够进行对比分析和深度推理
   - 更灵活的回答策略

3. **支持的领域**
   - 见下方【领域分类】部分

4. **使用方式**
   - 直接提问即可，系统会自动选择最佳处理模式
   - 简单问题会快速回答，复杂问题会深入分析
""";
    }

    private String getStringParam(Map<String, Object> input, String key, String defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }
}