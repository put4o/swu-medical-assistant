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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        //生成会话ID，如果会话ID为空，则生成一个雪花ID
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        //生成任务ID，用于链路追踪，如果任务ID为空，则生成一个雪花ID
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        //创建sse回调
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        //获取用户ID
        String userId = UserContext.getUserId();
        //加载会话历史记录，并追加用户问题
        List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

        //改写拆分
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
        //意图识别
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        //歧义引导
        GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
        //如果歧义引导为提示，则直接返回提示
        if (guidanceDecision.isPrompt()) {
            callback.onContent(guidanceDecision.getPrompt());
            callback.onComplete();
            return;
        }

        //判断是否所有意图都是系统意图
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            //获取系统意图的提示词
            String customPrompt = subIntents.stream()
                    //获取意图分数列表  flatMap将每个子问题中的意图分数列表扁平化
                    .flatMap(si -> si.nodeScores().stream())
                    //获取意图提示词  map将每个意图分数转换为提示词
                    .map(ns -> ns.getNode().getPromptTemplate())
                    .filter(StrUtil::isNotBlank) //过滤空提示词
                    .findFirst()
                    .orElse(null);
            //直接调用 LLM 流式返回，不会走 RAG 检索流程
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);
            //绑定任务ID
            taskManager.bindHandle(taskId, handle);
            return;
        }

        //检索
        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K);
        //如果检索结果为空，则返回空回复
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

        //流式输出
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback
        );
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history.subList(0, history.size() - 1));
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
