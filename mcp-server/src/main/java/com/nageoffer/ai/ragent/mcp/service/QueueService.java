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

import com.nageoffer.ai.ragent.mcp.vo.QueueStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 叫号查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final JdbcTemplate jdbcTemplate;

    private static final int AVG_MINUTES_PER_PATIENT = 5;

    /**
     * 查询叫号状态
     *
     * @param department  科室名称（可选，模糊匹配）
     * @param doctorName 医生姓名（可选，模糊匹配）
     * @return 叫号状态列表
     */
    public List<QueueStatusVO> queryQueueStatus(String department, String doctorName) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT d.id as dept_id, d.name as dept_name, d.floor, d.work_time,
                   doc.id as doctor_id, doc.name as doctor_name, doc.title as doctor_title,
                   qs.current_no, qs.waiting_count, qs.total_called, qs.status,
                   qs.last_call_time
            FROM t_queue_status qs
            JOIN t_department d ON qs.dept_id = d.id
            LEFT JOIN t_doctor doc ON qs.doctor_id = doc.id
            WHERE qs.queue_date = ?
              AND qs.deleted = 0
              AND d.deleted = 0
            """);

        List<Object> params = new ArrayList<>();
        params.add(today);

        if (department != null && !department.isBlank()) {
            log.info("[QueueService] 科室过滤参数 department=[{}], 长度={}, UTF-8字节={}",
                    department, department.length(), Arrays.toString(department.getBytes(StandardCharsets.UTF_8)));
            // 先用等值匹配测试，确认数据存在
            sql.append(" AND (d.name = ? OR d.code = ? OR d.name LIKE ? OR d.code LIKE ?)");
            params.add(department);
            params.add(department.toUpperCase());
            params.add("%" + department + "%");
            params.add("%" + department.toUpperCase() + "%");
        }

        if (doctorName != null && !doctorName.isBlank()) {
            sql.append(" AND doc.name LIKE ?");
            params.add("%" + doctorName + "%");
        }

        sql.append(" ORDER BY d.sort_order");

        log.info("[QueueService] SQL=[{}], params={}", sql, params);
        List<QueueStatusVO> results = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            int waiting = rs.getInt("waiting_count");
            int estimated = waiting * AVG_MINUTES_PER_PATIENT;

            String lastCall = null;
            Timestamp t = rs.getTimestamp("last_call_time");
            if (t != null) {
                lastCall = t.toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            }

            return QueueStatusVO.builder()
                    .deptId(rs.getString("dept_id"))
                    .deptName(rs.getString("dept_name"))
                    .floor(rs.getString("floor"))
                    .workTime(rs.getString("work_time"))
                    .doctorId(rs.getString("doctor_id"))
                    .doctorName(rs.getString("doctor_name"))
                    .doctorTitle(rs.getString("doctor_title"))
                    .currentNo(rs.getInt("current_no"))
                    .waitingCount(waiting)
                    .avgMinutes(AVG_MINUTES_PER_PATIENT)
                    .estimatedWaitMinutes(estimated)
                    .totalCalled(rs.getInt("total_called"))
                    .lastCallTime(lastCall)
                    .status(rs.getString("status"))
                    .build();
        }, params.toArray());

        log.info("[QueueService] 查询结果共 {} 条", results.size());
        return results;
    }
}
