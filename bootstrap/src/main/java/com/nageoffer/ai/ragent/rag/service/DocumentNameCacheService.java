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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档名称缓存服务
 * <p>
 * 启动时从 t_knowledge_document 加载 id → docName 的映射
 * 提供快速查询文档名称的能力，用于引用来源展示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNameCacheService {

    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 文档ID → 文档名称缓存
     */
    private final Map<String, String> docNameCache = new ConcurrentHashMap<>();

    /**
     * 启动时加载所有文档名称
     */
    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * 刷新缓存（全量）
     */
    public void refreshCache() {
        try {
            LambdaQueryWrapper<KnowledgeDocumentDO> wrapper = new LambdaQueryWrapper<>();
            wrapper.select(KnowledgeDocumentDO::getId, KnowledgeDocumentDO::getDocName)
                    .eq(KnowledgeDocumentDO::getDeleted, 0);

            java.util.List<KnowledgeDocumentDO> documents = documentMapper.selectList(wrapper);

            docNameCache.clear();
            for (KnowledgeDocumentDO doc : documents) {
                if (doc.getId() != null && doc.getDocName() != null) {
                    docNameCache.put(String.valueOf(doc.getId()), doc.getDocName());
                }
            }

            log.info("文档名称缓存已加载，共 {} 条记录", docNameCache.size());
        } catch (Exception e) {
            log.error("加载文档名称缓存失败", e);
        }
    }

    /**
     * 添加或更新单个文档名称
     *
     * @param docId   文档ID
     * @param docName 文档名称
     */
    public void put(String docId, String docName) {
        if (docId != null && docName != null) {
            docNameCache.put(docId, docName);
        }
    }

    /**
     * 移除文档名称
     *
     * @param docId 文档ID
     */
    public void remove(String docId) {
        if (docId != null) {
            docNameCache.remove(docId);
        }
    }

    /**
     * 获取文档名称
     *
     * @param docId 文档ID
     * @return 文档名称，不存在时返回 null
     */
    public String getDocName(String docId) {
        if (docId == null) {
            return null;
        }
        return docNameCache.get(docId);
    }

    /**
     * 获取文档名称，不存在时返回默认值
     *
     * @param docId        文档ID
     * @param defaultValue 默认值
     * @return 文档名称
     */
    public String getDocName(String docId, String defaultValue) {
        String name = getDocName(docId);
        return name != null ? name : defaultValue;
    }

    /**
     * 获取缓存大小
     */
    public int size() {
        return docNameCache.size();
    }
}