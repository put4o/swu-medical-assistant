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

package com.nageoffer.ai.ragent.rag.core.guidance;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.GuidanceProperties;
import com.nageoffer.ai.ragent.rag.constant.RAGConstant;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final PromptTemplateLoader promptTemplateLoader;

    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        //如果歧义引导开关关闭，则直接返回none
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }
        //查找歧义组
        AmbiguityGroup group = findAmbiguityGroup(subIntents);
        if (group == null || CollUtil.isEmpty(group.optionIds())) {
            return GuidanceDecision.none();
        }
        //获取意图名称列表
        List<String> systemNames = resolveOptionNames(group.optionIds());
        //如果问题中包含系统意图名称，则跳过歧义引导
        if (shouldSkipGuidance(question, systemNames)) {
            return GuidanceDecision.none();
        }
        //构建提示词
        String prompt = buildPrompt(group.topicName(), group.optionIds());
        //返回提示词
        return GuidanceDecision.prompt(prompt);
    }

    //查找歧义组
    private AmbiguityGroup findAmbiguityGroup(List<SubQuestionIntent> subIntents) {
        //如果子问题列表为空或子问题数量不为1，则直接返回null
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }
        //过滤候选节点，仅留下分数大于INTENT_MIN_SCORE，并且是知识库意图
        List<NodeScore> candidates = filterCandidates(subIntents.get(0).nodeScores());
        //如果候选节点数量小于2，则直接返回null
        if (candidates.size() < 2) {
            return null;
        }
        //按意图名称分组
        Map<String, List<NodeScore>> grouped = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getName()))
                .collect(Collectors.groupingBy(ns -> normalizeName(ns.getNode().getName())));
        //按意图名称分组
        Optional<Map.Entry<String, List<NodeScore>>> best = grouped.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), sortByScore(entry.getValue())))
                .filter(entry -> entry.getValue().size() > 1) //如果意图数量小于2，则跳过
                .filter(entry -> passScoreRatio(entry.getValue())) //如果意图分数比例小于阈值，则跳过
                .filter(entry -> hasMultipleSystems(entry.getValue())) //如果意图系统数量小于2，则跳过
                .max(Comparator.comparingDouble(entry -> entry.getValue().get(0).getScore())); //按意图分数排序
        //如果最佳意图为空，则直接返回null
        if (best.isEmpty()) {
            return null;
        }
        //获取最佳意图
        List<NodeScore> groupScores = best.get().getValue();
        //获取意图名称
        String topicName = Optional.ofNullable(groupScores.get(0).getNode().getName())
                .orElse(best.get().getKey());
        //获取意图ID列表
        List<String> optionIds = collectSystemOptions(groupScores);
        //如果意图ID列表数量小于2，则直接返回null
        if (optionIds.size() < 2) {
            return null;
        }
        return new AmbiguityGroup(topicName, trimOptions(optionIds));
    }

    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        //如果分数列表为空，则直接返回空列表
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        //过滤分数小于INTENT_MIN_SCORE的意图
        //过滤非知识库意图
        return scores.stream()
                .filter(ns -> ns.getScore() >= RAGConstant.INTENT_MIN_SCORE) //如果意图分数小于INTENT_MIN_SCORE，则跳过
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB()) //如果意图节点为空或意图节点不是知识库意图，则跳过
                .toList(); //返回过滤后的意图列表
    }

    //收集系统意图ID列表
    private List<String> collectSystemOptions(List<NodeScore> groupScores) {
        Set<String> ordered = new LinkedHashSet<>();
        for (NodeScore score : groupScores) {
            IntentNode node = score.getNode();
            String systemId = resolveSystemNodeId(node);
            if (StrUtil.isNotBlank(systemId)) {
                ordered.add(systemId);
            }
        }
        return new ArrayList<>(ordered);
    }

    private boolean shouldSkipGuidance(String question, List<String> systemNames) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(systemNames)) {
            return false;
        }
        String normalizedQuestion = normalizeName(question);
        for (String name : systemNames) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            for (String alias : buildSystemAliases(name)) {
                if (alias.length() < 2) {
                    continue;
                }
                if (normalizedQuestion.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    //获取系统意图名称
    private List<String> resolveOptionNames(List<String> optionIds) {
        if (CollUtil.isEmpty(optionIds)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String id : optionIds) {
            IntentNode node = intentNodeRegistry.getNodeById(id);
            if (node == null) {
                continue;
            }
            String name = StrUtil.blankToDefault(node.getName(), node.getId());
            names.add(name);
        }
        return names;
    }

    private List<String> buildSystemAliases(String systemName) {
        if (StrUtil.isBlank(systemName)) {
            return List.of();
        }
        String normalized = normalizeName(systemName);
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(normalized)) {
            aliases.add(normalized);
        }
        return aliases;
    }

    private boolean passScoreRatio(List<NodeScore> group) {
        if (group.size() < 2) {
            return false;
        }
        double top = group.get(0).getScore();
        double second = group.get(1).getScore();
        if (top <= 0) {
            return false;
        }
        double ratio = second / top;
        return ratio >= Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.0D);
    }

    private boolean hasMultipleSystems(List<NodeScore> group) {
        Set<String> systems = group.stream()
                .map(NodeScore::getNode)
                .map(this::resolveSystemNodeId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        return systems.size() > 1;
    }

    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        for (; ; ) {
            IntentLevel level = current.getLevel();
            if (level == IntentLevel.CATEGORY && (parent == null || parent.getLevel() == IntentLevel.DOMAIN)) {
                return current.getId();
            }
            if (parent == null) {
                return current.getId();
            }
            current = parent;
            parent = fetchParent(current);
        }
    }

    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    private List<NodeScore> sortByScore(List<NodeScore> scores) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    private List<String> trimOptions(List<String> optionIds) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(optionIds.size());
        if (optionIds.size() <= maxOptions) {
            return optionIds;
        }
        return optionIds.subList(0, maxOptions);
    }

    private String buildPrompt(String topicName, List<String> optionIds) {
        String options = renderOptions(optionIds);
        return promptTemplateLoader.render(
                RAGConstant.GUIDANCE_PROMPT_PATH,
                Map.of(
                        "topic_name", StrUtil.blankToDefault(topicName, ""),
                        "options", options
                )
        );
    }

    private String renderOptions(List<String> optionIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < optionIds.size(); i++) {
            String id = optionIds.get(i);
            IntentNode node = intentNodeRegistry.getNodeById(id);
            String name = node == null || StrUtil.isBlank(node.getName()) ? id : node.getName();
            sb.append(i + 1).append(") ").append(name).append("\n");
        }
        return sb.toString().trim();
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private record AmbiguityGroup(String topicName, List<String> optionIds) {
    }
}
