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

package com.nageoffer.ai.ragent.mcp.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nageoffer.ai.ragent.mcp.vo.ReportItemVO;
import com.nageoffer.ai.ragent.mcp.vo.ReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检查报告查询服务
 */
@Slf4j
@Service
public class ReportService {

    private final JdbcTemplate jdbcTemplate;
    private final Gson gson = new Gson();

    public ReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 查询检查报告列表
     *
     * @param userId     用户 ID
     * @param reportType 报告类型（可选，模糊匹配）
     * @param startDate  查询开始日期（可选）
     * @param endDate    查询结束日期（可选）
     * @return 报告列表
     */
    public List<ReportVO> queryReports(String userId, String reportType,
                                       LocalDate startDate, LocalDate endDate) {

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT id, report_no, patient_name, type, name, category, status,
                   sample_time, report_time, doctor, conclusion, items_json,
                   pdf_url, report_date
            FROM t_medical_report
            WHERE user_id = ?
              AND deleted = 0
            """);

        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (startDate != null) {
            sql.append(" AND sample_time >= ?");
            params.add(startDate.atStartOfDay());
        }
        if (endDate != null) {
            sql.append(" AND sample_time <= ?");
            params.add(LocalDateTime.of(endDate.getYear(), endDate.getMonth(), endDate.getDayOfMonth(), 23, 59, 59));
        }
        if (reportType != null && !reportType.isBlank()) {
            sql.append(" AND (type LIKE ? OR name LIKE ?)");
            params.add("%" + reportType + "%");
            params.add("%" + reportType + "%");
        }

        sql.append(" ORDER BY sample_time DESC NULLS LAST, report_time DESC");

        log.info("[ReportService] 查询报告 SQL=[{}], params={}", sql, params);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            List<ReportItemVO> items = parseItems(rs.getString("items_json"));
            int abnormalCount = (int) items.stream().filter(ReportItemVO::isAbnormal).count();

            String sampleTime = null;
            Timestamp st = rs.getTimestamp("sample_time");
            if (st != null) sampleTime = st.toLocalDateTime().format(TIME_FMT);

            String reportTime = null;
            Timestamp rt = rs.getTimestamp("report_time");
            if (rt != null) reportTime = rt.toLocalDateTime().format(TIME_FMT);

            String reportDate = null;
            Timestamp rd = rs.getTimestamp("report_date");
            if (rd != null) reportDate = rd.toLocalDateTime().toLocalDate().toString();

            return ReportVO.builder()
                    .reportId(rs.getString("id"))
                    .reportNo(rs.getString("report_no"))
                    .patientName(rs.getString("patient_name"))
                    .type(rs.getString("type"))
                    .name(rs.getString("name"))
                    .status(rs.getString("status"))
                    .sampleTime(sampleTime)
                    .reportTime(reportTime)
                    .doctor(rs.getString("doctor"))
                    .conclusion(rs.getString("conclusion"))
                    .items(items)
                    .hasAbnormal(abnormalCount > 0)
                    .abnormalCount(abnormalCount)
                    .pdfUrl(rs.getString("pdf_url"))
                    .reportDate(reportDate)
                    .build();
        }, params.toArray());
    }

    /**
     * 解析 items_json
     */
    private List<ReportItemVO> parseItems(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return gson.fromJson(json, new TypeToken<List<ReportItemVO>>(){}.getType());
        } catch (Exception e) {
            log.warn("[ReportService] 解析 items_json 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
