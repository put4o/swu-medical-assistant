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

package com.nageoffer.ai.ragent.mcp.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检查报告 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportVO {

    private String reportId;
    private String reportNo;
    private String patientName;
    private String type;
    private String name;
    private String status;
    private String sampleTime;
    private String reportTime;
    private String doctor;
    private String conclusion;
    private String reportDate;
    private String pdfUrl;
    private List<ReportItemVO> items;
    private boolean hasAbnormal;
    private int abnormalCount;
}