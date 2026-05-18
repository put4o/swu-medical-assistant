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

/**
 * 叫号状态 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatusVO {

    /**
     * 科室 ID
     */
    private String deptId;

    /**
     * 科室名称
     */
    private String deptName;

    /**
     * 科室所在楼层
     */
    private String floor;

    /**
     * 科室工作时间
     */
    private String workTime;

    /**
     * 医生 ID
     */
    private String doctorId;

    /**
     * 医生姓名
     */
    private String doctorName;

    /**
     * 医生职称
     */
    private String doctorTitle;

    /**
     * 当前叫到的号
     */
    private Integer currentNo;

    /**
     * 等待人数
     */
    private Integer waitingCount;

    /**
     * 每人平均就诊时间（分钟）
     */
    private Integer avgMinutes;

    /**
     * 预计等待时间（分钟）
     */
    private Integer estimatedWaitMinutes;

    /**
     * 今日已叫号总数
     */
    private Integer totalCalled;

    /**
     * 最后叫号时间（格式 HH:mm）
     */
    private String lastCallTime;

    /**
     * 状态：open, paused, closed
     */
    private String status;
}
