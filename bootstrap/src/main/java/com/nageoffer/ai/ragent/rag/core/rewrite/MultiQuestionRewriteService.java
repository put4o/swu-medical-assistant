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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        //如果查询改写开关关闭，则直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            //做规则归一化
            String normalized = queryTermMappingService.normalize(userQuestion);
            //做规则拆分
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        //查询改写开关打开，则调用LLM做改写 + 拆分
        //先做归一化
        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);
        //再调用LLM做改写 + 拆分
        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 先用默认改写做归一化，再进行多问句拆分。
     */
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分
    }

    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        //加载系统提示词
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        //构建请求
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);
        //调用LLM
        try {
            //调用LLM
            String raw = llmService.chat(req);
            //解析结果
            RewriteResult parsed = parseRewriteAndSplit(raw);
            //如果解析结果不为空，则返回解析结果
            if (parsed != null) {
                log.info("""
                        RAG用户问题查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }

            log.warn("查询改写+拆分解析失败，使用归一化问题兜底 - normalizedQuestion={}", normalizedQuestion);
        } catch (Exception e) {
            log.warn("查询改写+拆分 LLM 调用失败，使用归一化问题兜底 - question={}，normalizedQuestion={}", originalQuestion, normalizedQuestion, e);
        }

        // 统一兜底逻辑
        return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
    }

    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
                                                //构建消息列表  
        List<ChatMessage> messages = new ArrayList<>();
        //如果系统提示词不为空，则添加系统提示词
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近 1-2 轮的 User 和 Assistant 消息
        // 过滤掉 System 摘要，避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            List<ChatMessage> recentHistory = history.stream()
                    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                            || msg.getRole() == ChatMessage.Role.ASSISTANT)
                    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
                    .toList();
            messages.addAll(recentHistory);
        }

        //添加用户问题
        messages.add(ChatMessage.user(question));
        //构建请求

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }


    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分，去掉收尾空格，去掉空字符串
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        //如果拆分结果为空，则返回原始问题
        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        //如果拆分结果不为空，则将结果中的问号改为？
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
