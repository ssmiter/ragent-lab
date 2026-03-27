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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索命中结果
 * <p>
 * 表示一次向量检索或相关性搜索命中的单条记录
 * 包含原始文档片段 主键以及相关性得分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 文档ID（来自 metadata.doc_id）
     * 用于关联 t_knowledge_document 表获取文档名称等信息
     */
    private String docId;

    /**
     * 来源路径（来自 metadata.source_location）
     * 原始文件路径或URL
     */
    private String sourceLocation;

    /**
     * 分块序号（来自 metadata.chunk_index）
     * 该 chunk 在原文中的位置
     */
    private Integer chunkIndex;

    /**
     * 知识库ID（来自 metadata.kb_id）
     */
    private String kbId;

    /**
     * 兼容原有调用的三参数构造函数
     */
    public RetrievedChunk(String id, String text, Float score) {
        this.id = id;
        this.text = text;
        this.score = score;
    }
}
