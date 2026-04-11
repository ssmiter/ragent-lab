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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieve.MilvusRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（带 Rerank）- 进程内调用版本
 *
 * <p>直接调用 bootstrap 进程内的 MilvusRetrieverService 和 RerankService，
 * 无需 HTTP 封装，零网络开销。</p>
 *
 * <p>支持三个知识库：</p>
 * <ul>
 *   <li>ragentdocs: Ragent 项目文档（架构、RAG、MCP、意图树等）</li>
 *   <li>ssddocs: SSD 理论文档（状态空间模型、原论文理论）</li>
 *   <li>dualssddocs: DualSSD 创新文档（块级别状态管理、改进）</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchWithRerankTool implements Tool {

    private static final String TOOL_NAME = "knowledge_search_with_rerank";

    /**
     * 支持的知识库列表
     */
    private static final List<String> VALID_COLLECTIONS = List.of("ragentdocs", "ssddocs", "dualssddocs");

    private final MilvusRetrieverService retrieverService;
    private final RerankService rerankService;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return """
Search a specific knowledge base for relevant information. Returns ranked text chunks with relevance scores (Rerank scores, typically 0.7-0.95).

You can search three different knowledge bases:
- ragentdocs: Ragent project documentation (system architecture, RAG implementation, MCP tools, intent tree, chunking strategy, frontend tech stack)
- ssddocs: SSD (State Space Duality) original theory and paper content (state space models, mathematical derivation)
- dualssddocs: DualSSD innovation documentation (improvements over SSD, block-level state management)

If unsure which knowledge base to search, start with the most likely one based on the question topic.
You can search multiple knowledge bases by calling this tool multiple times with different collection values.
""";
    }

    @Override
    public String getInputSchema() {
        return """
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "The search query. Be specific and use keywords relevant to the topic."
    },
    "collection": {
      "type": "string",
      "enum": ["ragentdocs", "ssddocs", "dualssddocs"],
      "description": "Which knowledge base to search. Choose based on the question topic."
    },
    "top_k": {
      "type": "integer",
      "description": "Maximum number of document chunks to return, default 5",
      "default": 5
    }
  },
  "required": ["query", "collection"]
}
""";
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // 1. 从 input 中取参数
        String query = getStringParam(input, "query");
        String collection = getStringParam(input, "collection");
        int topK = getIntParam(input, "top_k", 5);

        // 2. 参数校验
        if (query == null || query.isBlank()) {
            return ToolResult.error(TOOL_NAME, "参数 query 不能为空");
        }

        if (collection == null || collection.isBlank()) {
            return ToolResult.error(TOOL_NAME, "参数 collection 不能为空，请指定知识库：ragentdocs、ssddocs 或 dualssddocs");
        }

        if (!VALID_COLLECTIONS.contains(collection)) {
            return ToolResult.error(TOOL_NAME,
                    "无效的知识库: " + collection + "。请选择：ragentdocs、ssddocs 或 dualssddocs");
        }

        try {
            // 3. 向量检索获取候选
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

            // 4. Rerank 精排
            List<RetrievedChunk> reranked = rerankService.rerank(query, candidates, topK);

            log.info("knowledge_search_with_rerank 完成: collection={}, query={}, candidates={}, topK={}, 返回{}个chunk",
                    collection, query, candidates.size(), topK, reranked.size());

            // 5. 格式化结果为文本返回
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