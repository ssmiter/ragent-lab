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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.DocumentNameCacheService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();

    /**
     * 文档名称缓存服务
     */
    private final DocumentNameCacheService documentNameCacheService;

    /**
     * 检索结果（用于生成引用来源）
     */
    private List<RetrievedChunk> retrievedChunks;

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();
        this.documentNameCacheService = params.getDocumentNameCacheService();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    @Override
    public void setRetrievedChunks(List<RetrievedChunk> chunks) {
        this.retrievedChunks = chunks;
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        Long messageId = null;
        if (StrUtil.isNotBlank(content)) {
            messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content));
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        // 拼接引用来源
        String finalAnswer = answer.toString();
        String citationSection = buildCitationSection();
        if (StrUtil.isNotBlank(citationSection)) {
            finalAnswer = finalAnswer + "\n\n" + citationSection;
        }

        Long messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(finalAnswer));
        String title = resolveTitleForEvent();
        String messageIdText = messageId == null ? null : String.valueOf(messageId);
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }

    /**
     * 构建引用来源折叠区域（Markdown 格式）
     */
    private String buildCitationSection() {
        if (CollUtil.isEmpty(retrievedChunks)) {
            return null;
        }

        // 按文档去重，保留最高分
        Map<String, RetrievedChunk> docToBestChunk = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retrievedChunks) {
            // 使用 docId 或 sourceLocation 作为文档标识
            String docKey = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() :
                    (StrUtil.isNotBlank(chunk.getSourceLocation()) ? chunk.getSourceLocation() : null);
            if (docKey == null) {
                docKey = "unknown-" + chunk.getId(); // 兜底
            }

            RetrievedChunk existing = docToBestChunk.get(docKey);
            if (existing == null) {
                docToBestChunk.put(docKey, chunk);
            } else {
                // 保留分数更高的
                Float existingScore = existing.getScore() != null ? existing.getScore() : 0f;
                Float newScore = chunk.getScore() != null ? chunk.getScore() : 0f;
                if (newScore > existingScore) {
                    docToBestChunk.put(docKey, chunk);
                }
            }
        }

        if (docToBestChunk.isEmpty()) {
            return null;
        }

        List<RetrievedChunk> uniqueDocs = new ArrayList<>(docToBestChunk.values());

        StringBuilder sb = new StringBuilder();
        sb.append("<details>\n");
        sb.append("<summary>📚 引用来源（共").append(uniqueDocs.size()).append("篇文档）</summary>\n\n");

        for (int i = 0; i < uniqueDocs.size(); i++) {
            RetrievedChunk chunk = uniqueDocs.get(i);
            String docName = extractDocName(chunk);
            String score = chunk.getScore() != null ? String.format("%.2f", chunk.getScore()) : "N/A";

            sb.append(i + 1).append(". ").append(docName);
            sb.append(" — 相关度 ").append(score).append("\n");
        }

        sb.append("</details>");
        return sb.toString();
    }

    /**
     * 从 chunk 提取文档名称
     */
    private String extractDocName(RetrievedChunk chunk) {
        // 优先使用 sourceLocation 的文件名
        if (StrUtil.isNotBlank(chunk.getSourceLocation())) {
            String location = chunk.getSourceLocation();
            int lastSlash = location.lastIndexOf('/');
            int lastBackslash = location.lastIndexOf('\\');
            int lastSep = Math.max(lastSlash, lastBackslash);
            if (lastSep >= 0 && lastSep < location.length() - 1) {
                return location.substring(lastSep + 1);
            }
            return location;
        }
        // 尝试通过 docId 查缓存获取文档名
        if (StrUtil.isNotBlank(chunk.getDocId())) {
            String cachedName = documentNameCacheService.getDocName(chunk.getDocId());
            if (StrUtil.isNotBlank(cachedName)) {
                return cachedName;
            }
            return chunk.getDocId();
        }
        return "未知来源";
    }

    }
