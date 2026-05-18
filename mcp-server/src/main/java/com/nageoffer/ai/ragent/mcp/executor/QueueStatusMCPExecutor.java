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

package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.service.QueueService;
import com.nageoffer.ai.ragent.mcp.vo.QueueStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 叫号状态 MCP 执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueStatusMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "queue_status";

    private final QueueService queueService;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> params = new LinkedHashMap<>();

        params.put("department", MCPToolDefinition.ParameterDef.builder()
                .description("科室名称，如：内科、外科、儿科、妇科、骨科、中医科等")
                .type("string")
                .required(false)
                .build());

        params.put("doctorName", MCPToolDefinition.ParameterDef.builder()
                .description("医生姓名（支持模糊匹配）")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询校医院门诊的当前叫号状态和排队情况，包含当前叫到的号码、等待人数、预计等待时间等信息")
                .parameters(params)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String department = request.getStringParameter("department");
            String doctorName = request.getStringParameter("doctorName");

            List<QueueStatusVO> statuses = queueService.queryQueueStatus(department, doctorName);

            if (statuses.isEmpty()) {
                return MCPToolResponse.success(TOOL_ID,
                        "未查询到相关叫号信息，可能该科室今日未开诊或已下班。");
            }

            String text = buildResultText(statuses);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCount", statuses.size());
            data.put("queueList", statuses);

            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(TOOL_ID)
                    .textResult(text)
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("叫号查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    private String buildResultText(List<QueueStatusVO> statuses) {
        StringBuilder sb = new StringBuilder();
        sb.append("【校医院门诊叫号查询结果】\n\n");

        if (statuses.size() == 1) {
            QueueStatusVO s = statuses.get(0);
            sb.append(formatSingle(s));
        } else {
            sb.append(String.format("共查询到 %d 条叫号信息：\n\n", statuses.size()));
            for (int i = 0; i < statuses.size(); i++) {
                QueueStatusVO s = statuses.get(i);
                sb.append(String.format("▶ %s\n", s.getDeptName()));
                sb.append(formatSingle(s));
                if (i < statuses.size() - 1) {
                    sb.append("\n────────────────────\n\n");
                }
            }
        }

        sb.append("\n数据来自校医院实时系统。");
        return sb.toString().trim();
    }

    private String formatSingle(QueueStatusVO s) {
        StringBuilder sb = new StringBuilder();
        if (s.getDoctorName() != null) {
            sb.append("医生：").append(s.getDoctorName());
            if (s.getDoctorTitle() != null) sb.append("（").append(s.getDoctorTitle()).append("）");
            sb.append("\n");
        }
        if (s.getFloor() != null) {
            sb.append("位置：").append(s.getFloor()).append("\n");
        }
        sb.append("当前叫号：").append(s.getCurrentNo()).append("号\n");
        sb.append("等待人数：").append(s.getWaitingCount()).append("人\n");
        if (s.getEstimatedWaitMinutes() != null && s.getEstimatedWaitMinutes() > 0) {
            sb.append("预计等待：").append(s.getEstimatedWaitMinutes()).append("分钟\n");
        }
        if (s.getLastCallTime() != null) {
            sb.append("最后叫号：").append(s.getLastCallTime()).append("\n");
        }
        return sb.toString().trim();
    }
}
