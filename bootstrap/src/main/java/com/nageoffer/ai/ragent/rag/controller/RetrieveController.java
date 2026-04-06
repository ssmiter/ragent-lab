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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieve.MilvusRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 向量检索控制器
 * - /retrieve: 原始向量检索（无 rerank）
 * - /retrieve-with-rerank: 向量检索 + Rerank（推荐使用）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/retrieve")
public class RetrieveController {

    private final MilvusRetrieverService milvusRetrieverService;
    private final RerankService rerankService;

    /**
     * 原始向量检索（不含 rerank）
     */
    @PostMapping
    public Result<List<RetrievedChunk>> retrieve(@RequestBody RetrieveRequest request) {
        List<RetrievedChunk> chunks = milvusRetrieverService.retrieve(request);
        return Results.success(chunks);
    }

    /**
     * 向量检索 + Rerank（推荐）
     * <p>
     * 流程：
     * 1. 先用 Milvus 检索候选文档（candidateCount，默认 15 个）
     * 2. 用 Rerank 模型精排，返回 topK 个（默认 5 个）
     * 3. 返回结果包含 rerank 后的分数（通常 0.7-0.95）
     * </p>
     */
    @PostMapping("/with-rerank")
    public Result<List<RetrievedChunk>> retrieveWithRerank(@RequestBody RetrieveWithRerankRequest request) {
        int topK = request.getTopK() > 0 ? request.getTopK() : 5;
        int candidateCount = request.getCandidateCount() > 0 ? request.getCandidateCount() : Math.max(topK * 3, 15);

        // 1. 向量检索获取候选
        RetrieveRequest retrieveRequest = RetrieveRequest.builder()
                .query(request.getQuery())
                .topK(candidateCount)
                .collectionName(request.getCollectionName())
                .build();

        List<RetrievedChunk> candidates = milvusRetrieverService.retrieve(retrieveRequest);

        if (candidates.isEmpty()) {
            log.info("retrieve-with-rerank: 无候选文档, query={}", request.getQuery());
            return Results.success(List.of());
        }

        // 2. Rerank 精排
        List<RetrievedChunk> reranked = rerankService.rerank(request.getQuery(), candidates, topK);

        log.info("retrieve-with-rerank 完成: query={}, candidates={}, topK={}, 返回{}个chunk",
                request.getQuery(), candidates.size(), topK, reranked.size());

        return Results.success(reranked);
    }

    /**
     * 带 rerank 的检索请求
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    public static class RetrieveWithRerankRequest {
        /**
         * 用户查询
         */
        private String query;

        /**
         * 最终返回的文档数（默认 5）
         */
        @lombok.Builder.Default
        private int topK = 5;

        /**
         * 候选文档数（默认 15，或 topK*3）
         */
        private int candidateCount;

        /**
         * 向量集合名称
         */
        private String collectionName;
    }
}