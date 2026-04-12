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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.core.retrieve.MilvusRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（带 Rerank）- 进程内调用版本
 *
 * <p>直接调用 bootstrap 进程内的 MilvusRetrieverService 和 RerankService，
 * 无需 HTTP 封装，零网络开销。</p>
 *
 * <p>支持的知识库：从 t_knowledge_base 表动态获取，新增知识库无需改代码。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchWithRerankTool implements Tool {

    private static final String TOOL_NAME = "knowledge_search_with_rerank";

    private final MilvusRetrieverService retrieverService;
    private final RerankService rerankService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentNodeMapper intentNodeMapper;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        // 动态获取知识库列表
        List<KnowledgeBaseDO> kbList = getValidKnowledgeBases();
        // 从意图树获取 KB 类型叶子节点的语义描述
        Map<String, KbRoutingInfo> routingInfoMap = getKbRoutingInfo();

        String kbListStr = kbList.stream()
                .map(kb -> {
                    String collectionName = kb.getCollectionName();
                    KbRoutingInfo info = routingInfoMap.get(collectionName);
                    if (info != null && info.description != null && !info.description.isBlank()) {
                        // 精简描述为一行（约 30-50 字）
                        String shortDesc = truncateDescription(info.description, 50);
                        return "- " + collectionName + ": " + kb.getName() + " (" + shortDesc + ")";
                    }
                    return "- " + collectionName + ": " + kb.getName();
                })
                .collect(Collectors.joining("\n"));

        return """
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

Available knowledge bases (each with content scope):
""" + kbListStr + """

If unsure which knowledge base to search, start with the most likely one based on the question topic.
You can search multiple knowledge bases by calling this tool multiple times with different collection values.
""";
    }

    /**
     * 从意图树获取 KB 类型叶子节点的路由信息
     * key: collectionName, value: (description, examples)
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
                        // 如果有多个节点指向同一个 collection，保留第一个
                        (a, b) -> a
                ));
    }

    /**
     * 精简描述为一行，控制在指定长度内
     */
    private String truncateDescription(String desc, int maxLen) {
        if (desc == null || desc.isBlank()) {
            return "";
        }
        // 去除换行，只保留第一句或第一段
        String cleaned = desc.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        // 尝试在第一个句号/逗号处截断
        int cutPoint = cleaned.indexOf('。');
        if (cutPoint > 0 && cutPoint < maxLen) {
            return cleaned.substring(0, cutPoint);
        }
        cutPoint = cleaned.indexOf(',');
        if (cutPoint > 0 && cutPoint < maxLen) {
            return cleaned.substring(0, cutPoint).trim();
        }
        // 简单截断
        return cleaned.substring(0, maxLen - 1) + "...";
    }

    /**
     * KB 路由信息（内部使用）
     */
    private record KbRoutingInfo(String description, String examples) {}

    @Override
    public String getInputSchema() {
        // 动态获取知识库列表构建 enum
        List<KnowledgeBaseDO> kbList = getValidKnowledgeBases();
        String enumStr = kbList.stream()
                .map(KnowledgeBaseDO::getCollectionName)
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", "));

        return "{\n" +
               "  \"type\": \"object\",\n" +
               "  \"properties\": {\n" +
               "    \"query\": {\n" +
               "      \"type\": \"string\",\n" +
               "      \"description\": \"The search query. Be specific and use keywords relevant to the topic.\"\n" +
               "    },\n" +
               "    \"collection\": {\n" +
               "      \"type\": \"string\",\n" +
               "      \"enum\": [" + enumStr + "],\n" +
               "      \"description\": \"Which knowledge base to search. Choose based on the question topic.\"\n" +
               "    },\n" +
               "    \"top_k\": {\n" +
               "      \"type\": \"integer\",\n" +
               "      \"description\": \"Maximum number of document chunks to return, default 5\",\n" +
               "      \"default\": 5\n" +
               "    }\n" +
               "  },\n" +
               "  \"required\": [\"query\", \"collection\"]\n" +
               "}\n";
    }

    /**
     * 从数据库获取有效的知识库列表（与 SystemInfoTool 使用同一数据源）
     */
    private List<KnowledgeBaseDO> getValidKnowledgeBases() {
        return knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getDeleted, 0)
                        .orderByAsc(KnowledgeBaseDO::getId)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 1. 从 input 中取参数
        String query = getStringParam(input, "query");
        String collection = getStringParam(input, "collection");
        int topK = getIntParam(input, "top_k", 5);

        // 2. 动态获取有效知识库列表
        List<KnowledgeBaseDO> kbList = getValidKnowledgeBases();
        List<String> validCollections = kbList.stream()
                .map(KnowledgeBaseDO::getCollectionName)
                .collect(Collectors.toList());

        // 3. 参数校验
        if (query == null || query.isBlank()) {
            return ToolResult.error(TOOL_NAME, "参数 query 不能为空");
        }

        if (collection == null || collection.isBlank()) {
            String availableKbs = validCollections.stream().collect(Collectors.joining("、"));
            return ToolResult.error(TOOL_NAME, "参数 collection 不能为空，可用知识库：" + availableKbs);
        }

        if (!validCollections.contains(collection)) {
            String availableKbs = validCollections.stream().collect(Collectors.joining("、"));
            return ToolResult.error(TOOL_NAME,
                    "无效的知识库: " + collection + "。可用知识库：" + availableKbs);
        }

        try {
            // 4. 向量检索获取候选
            int candidateCount = Math.max(topK * 3, 15);

            RetrieveRequest retrieveRequest = RetrieveRequest.builder()
                    .query(query)
                    .topK(candidateCount)
                    .collectionName(collection)
                    .build();

            List<RetrievedChunk> candidates = retrieverService.retrieve(retrieveRequest);

            if (candidates.isEmpty()) {
                log.info("knowledge_search_with_rerank: 无候选文档, query={}, collection={}", query, collection);
                return ToolResult.success(TOOL_NAME,
                        String.format("知识库 [%s] 中未找到与 \"%s\" 相关的文档片段", collection, query));
            }

            // 5. Rerank 精排
            List<RetrievedChunk> reranked = rerankService.rerank(query, candidates, topK);

            log.info("knowledge_search_with_rerank 完成: collection={}, query={}, candidates={}, topK={}, 返回{}个chunk",
                    collection, query, candidates.size(), topK, reranked.size());

            // 6. 格式化结果为文本返回
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("在知识库 [%s] 中找到 %d 个相关片段：\n\n", collection, reranked.size()));

            for (int i = 0; i < reranked.size(); i++) {
                RetrievedChunk chunk = reranked.get(i);

                String text = chunk.getText() != null ? chunk.getText() : "";
                float score = chunk.getScore() != null ? chunk.getScore() : 0f;
                String sourceLocation = chunk.getSourceLocation() != null ? chunk.getSourceLocation() : "未知来源";

                sb.append("[").append(i + 1).append("] ")
                  .append("(Rerank分数: ").append(String.format("%.2f", score)).append(") ")
                  .append("(来源: ").append(sourceLocation).append(")\n")
                  .append(text).append("\n\n");
            }

            sb.append("---\n");
            sb.append("分数分布说明（Rerank 精排后）：\n");
            sb.append("- 分数 > 0.85: 高度相关，信息准确\n");
            sb.append("- 分数 0.75-0.85: 中等相关，可信参考\n");
            sb.append("- 分数 < 0.75: 低相关，建议换关键词或换知识库重试\n");

            return ToolResult.success(TOOL_NAME, sb.toString());

        } catch (Exception e) {
            log.error("knowledge_search_with_rerank 执行异常, collection={}, query={}", collection, query, e);
            return ToolResult.error(TOOL_NAME, "检索异常: " + e.getMessage());
        }
    }

    private String getStringParam(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private int getIntParam(Map<String, Object> input, String key, int defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}