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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rerank 后置处理器
 * <p>
 * 使用 Rerank 模型对结果进行重排序
 * 这是最后一个处理器，输出最终的 Top-K 结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 最后执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;  // 始终启用
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        // 执行 Rerank
        List<RetrievedChunk> reranked = rerankService.rerank(
                context.getMainQuestion(),
                chunks,
                context.getTopK()
        );

        // ===== 自适应 TopK 截断 =====
        int originalSize = reranked.size();
        int minKeep = 3;              // 保底至少保留 3 个
        double dropThreshold = 0.15;  // 分数差阈值

        // 从第1个位置开始扫描所有相邻pair
        int cutPosition = -1;
        double maxDrop = 0.0;

        for (int i = 0; i < reranked.size() - 1; i++) {
            double drop = reranked.get(i).getScore() - reranked.get(i + 1).getScore();
            if (drop > dropThreshold) {
                // 找到第一个超过阈值的drop，截断位置不低于minKeep
                cutPosition = Math.max(i + 1, minKeep);
                maxDrop = drop;
                break;
            }
        }

        List<RetrievedChunk> finalChunks;
        if (cutPosition > 0 && cutPosition < originalSize) {
            finalChunks = reranked.subList(0, cutPosition);
            log.info("自适应截断: 从 {} 个截断到 {} 个, 触发drop={}",
                    originalSize, finalChunks.size(), String.format("%.4f", maxDrop));
        } else {
            finalChunks = reranked;
            log.info("自适应截断: 无需截断, 保留 {} 个chunk", originalSize);
        }
        // ===== 自适应截断结束 =====

        return finalChunks;
    }
}
