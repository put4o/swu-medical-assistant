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
import com.nageoffer.ai.ragent.mcp.service.ReportService;
import com.nageoffer.ai.ragent.mcp.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查报告查询 MCP 执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportQueryMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "report_query";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ReportService reportService;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> params = new LinkedHashMap<>();

        params.put("reportType", MCPToolDefinition.ParameterDef.builder()
                .description("报告类型：血常规、尿常规、生化、CT、核磁、B超、心电图等")
                .type("string")
                .required(false)
                .build());

        params.put("startDate", MCPToolDefinition.ParameterDef.builder()
                .description("查询开始日期，格式：YYYY-MM-DD，默认最近30天")
                .type("string")
                .required(false)
                .build());

        params.put("endDate", MCPToolDefinition.ParameterDef.builder()
                .description("查询结束日期，格式：YYYY-MM-DD，默认今天")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询患者的检验、检查报告结果，支持PDF报告下载")
                .parameters(params)
                .requireUserId(true)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String userId = request.getUserId();
            if (userId == null || userId.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "UNAUTHORIZED", "请先登录后再查询报告");
            }

            String reportType = request.getStringParameter("reportType");
            String startDate = request.getStringParameter("startDate");
            String endDate = request.getStringParameter("endDate");

            LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
            LocalDate end = parseDate(endDate, LocalDate.now());

            if (start.isAfter(end)) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "开始日期不能晚于结束日期");
            }

            List<ReportVO> reports = reportService.queryReports(userId, reportType, start, end);

            String text = buildResultText(reports, start, end);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCount", reports.size());
            data.put("reportList", reports);

            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(TOOL_ID)
                    .textResult(text)
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("[ReportQueryMCPExecutor] 报告查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    private LocalDate parseDate(String s, LocalDate defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
            log.warn("[ReportQueryMCPExecutor] 日期解析失败: {}", s);
            return defaultVal;
        }
    }

    private String buildResultText(List<ReportVO> reports, LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        sb.append("【校医院检查报告查询结果】\n\n");
        sb.append(String.format("查询范围：%s 至 %s\n", start, end));
        sb.append(String.format("共查询到 %d 份报告\n\n", reports.size()));
        sb.append("────────────────────────────────────────\n\n");

        if (reports.isEmpty()) {
            sb.append("未查询到符合条件的报告。");
        } else {
            for (int i = 0; i < reports.size(); i++) {
                ReportVO r = reports.get(i);
                sb.append(formatReport(r, i + 1));
                if (i < reports.size() - 1) {
                    sb.append("\n────────────────────────────────────────\n\n");
                }
            }
        }

        sb.append("\n\n💡 报告仅供辅助参考，最终诊断以医生为准。");
        return sb.toString().trim();
    }

    private String formatReport(ReportVO r, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%d】%s\n", idx, r.getName()));
        sb.append(String.format("报告编号：%s\n", r.getReportNo()));
        sb.append(String.format("类型：%s\n", r.getType()));

        String statusText = switch (r.getStatus()) {
            case "available" -> "✅ 已出";
            case "processing" -> "⏳ 处理中";
            case "pending" -> "📝 未出";
            default -> r.getStatus();
        };
        sb.append(String.format("状态：%s\n", statusText));

        if (!"available".equals(r.getStatus())) {
            if (r.getSampleTime() != null) sb.append(String.format("采样时间：%s\n", r.getSampleTime()));
            sb.append("\n⚠️ 报告尚未出具，请耐心等待。");
            return sb.toString();
        }

        if (r.getReportTime() != null) sb.append(String.format("报告时间：%s\n", r.getReportTime()));
        if (r.getDoctor() != null) sb.append(String.format("送检医生：%s\n", r.getDoctor()));

        if (r.getItems() != null && !r.getItems().isEmpty()) {
            sb.append("\n📋 检查结果：\n");
            for (var item : r.getItems()) {
                String flag = "";
                if ("H".equals(item.getFlag())) flag = " ⬆️ 偏高";
                else if ("L".equals(item.getFlag())) flag = " ⬇️ 偏低";

                String val = item.getValue() + (item.getUnit() != null ? " " + item.getUnit() : "");
                sb.append(String.format("  • %s：%s%s\n", item.getName(), val, flag));
                if (item.getRef() != null) {
                    sb.append(String.format("    参考值：%s\n", item.getRef()));
                }
            }

            if (r.isHasAbnormal()) {
                sb.append(String.format("\n⚠️ 共 %d 项指标异常，请关注。\n", r.getAbnormalCount()));
            } else {
                sb.append("\n✨ 所有指标均在正常范围内。\n");
            }
        }

        if (r.getConclusion() != null && !r.getConclusion().isBlank()) {
            sb.append(String.format("\n📝 结论：%s\n", r.getConclusion()));
        }

        if (r.getPdfUrl() != null && !r.getPdfUrl().isBlank()) {
            sb.append(String.format("\n📄 PDF报告：%s\n", r.getPdfUrl()));
        }

        return sb.toString().trim();
    }
}
