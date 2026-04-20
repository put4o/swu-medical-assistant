# 检查报告查询 MCP 工具实现方案

> 文档版本：v1.0
> 创建日期：2026-04-20
> 工具 ID：`report_query`
> 数据策略：完全使用模拟数据
> 隐私等级：高（涉及患者隐私数据）

---

## 一、需求概述

### 1.1 功能定义

| 属性 | 说明 |
|------|------|
| **工具名称** | 检查报告查询 |
| **工具 ID** | `report_query` |
| **工具类型** | MCP (Model Context Protocol) |
| **功能描述** | 查询患者的检验、检查报告结果，包含关键指标和参考值对比 |
| **隐私等级** | 高（需用户认证） |

### 1.2 输入输出规格

**输入参数：**

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `reportType` | string | 否 | 全部类型 | 报告类型：血常规/尿常规/生化/免疫/放射/CT/核磁/B超/心电图/其他 |
| `startDate` | string | 否 | 最近30天 | 查询开始日期（YYYY-MM-DD） |
| `endDate` | string | 否 | 今天 | 查询结束日期（YYYY-MM-DD） |

**输出内容：**

| 字段 | 说明 |
|------|------|
| 报告列表 | 查询到的报告概要列表 |
| 报告详情 | 单个报告的完整内容 |
| 指标结果 | 各检查项目的数值和参考范围 |
| 异常标注 | 高于/低于参考值的指标高亮显示 |
| 报告状态 | 已出/未出/处理中 |

### 1.3 与 queue_status 的主要区别

| 维度 | queue_status | report_query |
|------|--------------|--------------|
| 隐私等级 | 低（公开数据） | **高（患者隐私）** |
| 用户认证 | 不需要 | **必须** |
| 数据来源 | 实时叫号 | 历史报告 |
| 敏感信息 | 无 | **有（患者信息）** |
| 数据脱敏 | 不需要 | **必须** |

---

## 二、技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           MCP Client (Bootstrap)                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │              RetrievalEngine → IntentResolver                     │    │
│  │                     ↓                                             │    │
│  │         MCP Tool Registry (RemoteMCPToolExecutor)                 │    │
│  │                      ↓                                            │    │
│  │              MCPRequest (userId 已注入)                            │    │
│  └─────────────────────────────┬───────────────────────────────────┘    │
└────────────────────────────────┼─────────────────────────────────────────┘
                                 │ HTTP JSON-RPC
                                 │ POST /mcp
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            MCP Server (mcp-server)                        │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────┐    │
│  │  MCPEndpoint   │→ │MCPToolDispatcher│→ │ReportQueryMCPExecutor │    │
│  │  POST /mcp     │  │  (路由分发)     │  │    @Component          │    │
│  └────────────────┘  └────────────────┘  └───────────┬────────────┘    │
│                                                        │                │
│                                                        ▼                │
│                                              ┌────────────────────┐    │
│                                              │   ReportService    │    │
│                                              │  (模拟数据 + 脱敏) │    │
│                                              └────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 安全架构

```
┌────────────────────────────────────────────────────────────────────┐
│                         安全验证流程                                 │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  1. 用户认证检查                                                   │
│     └─→ 检查 request.userId 是否存在且有效                          │
│         ├─→ 无效 → 返回 UNAUTHORIZED 错误                          │
│         └─→ 有效 → 继续                                            │
│                                                                    │
│  2. 数据访问控制                                                   │
│     └─→ 检查用户是否有权限访问查询的 patientId                       │
│         ├─→ 无权限 → 返回 ACCESS_DENIED 错误                       │
│         └─→ 有权限 → 继续                                          │
│                                                                    │
│  3. 数据脱敏处理                                                   │
│     └─→ 过滤敏感信息（身份证号、手机号、详细地址等）                  │
│                                                                    │
│  4. 审计日志                                                       │
│     └─→ 记录查询操作（用户、报告类型、时间）                         │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### 2.3 文件清单

| 文件路径 | 操作 | 说明 |
|----------|------|------|
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/ReportQueryMCPExecutor.java` | 新增 | 检查报告查询执行器 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/ReportService.java` | 新增 | 报告数据服务（模拟数据） |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/PrivacyService.java` | 新增 | 数据脱敏服务 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/model/MedicalReport.java` | 新增 | 报告数据模型 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/model/ReportItem.java` | 新增 | 报告项目模型 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/model/PatientInfo.java` | 新增 | 患者信息模型（脱敏版） |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/enums/ReportType.java` | 新增 | 报告类型枚举 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/enums/ReportStatus.java` | 新增 | 报告状态枚举 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeFactory.java` | 修改 | 添加报告查询意图节点 |
| `bootstrap/src/main/resources/prompt/mcp-report-query-prompt.st` | 新增 | 报告查询结果格式化模板 |

---

## 三、数据模型设计

### 3.1 报告类型枚举 (ReportType)

```java
public enum ReportType {
    /** 血常规 */
    BLOOD_ROUTINE("血常规", "检验", "血液"),
    
    /** 尿常规 */
    URINE_ROUTINE("尿常规", "检验", "尿液"),
    
    /** 生化检验 */
    BIOCHEMISTRY("生化", "检验", "血液"),
    
    /** 免疫检验 */
    IMMUNITY("免疫", "检验", "血液"),
    
    /** 放射检查 */
    XRAY("X光", "检查", "影像"),
    
    /** CT检查 */
    CT("CT", "检查", "影像"),
    
    /** 核磁共振 */
    MRI("核磁共振", "检查", "影像"),
    
    /** 超声检查 */
    BULTRASOUND("B超", "检查", "影像"),
    
    /** 心电图 */
    ECG("心电图", "检查", "功能"),
    
    /** 其他 */
    OTHER("其他", "其他", "其他");

    private final String displayName;
    private final String category;
    private final String sampleType;

    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getSampleType() { return sampleType; }
}
```

### 3.2 报告状态枚举 (ReportStatus)

```java
public enum ReportStatus {
    /** 已出（可查看） */
    AVAILABLE("available", "已出"),
    
    /** 处理中 */
    PROCESSING("processing", "处理中"),
    
    /** 未出 */
    PENDING("pending", "未出"),
    
    /** 已归档 */
    ARCHIVED("archived", "已归档");

    private final String code;
    private final String displayName;

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}
```

### 3.3 报告项目 (ReportItem)

```java
@Data
@Builder
public class ReportItem {
    /** 项目名称 */
    private String name;
    
    /** 检测值 */
    private String value;
    
    /** 单位 */
    private String unit;
    
    /** 参考范围 */
    private String referenceRange;
    
    /** 结果标志：H(高)、L(低)、N(正常)、null(无参考值) */
    private String flag;
    
    /** 是否异常 */
    public boolean isAbnormal() {
        return flag != null && !"N".equals(flag);
    }
}
```

### 3.4 检查报告 (MedicalReport)

```java
@Data
@Builder
public class MedicalReport {
    /** 报告ID */
    private String reportId;
    
    /** 报告编号 */
    private String reportNo;
    
    /** 报告类型 */
    private ReportType reportType;
    
    /** 报告名称 */
    private String reportName;
    
    /** 报告状态 */
    private ReportStatus status;
    
    /** 采样时间 */
    private String sampleTime;
    
    /** 报告时间 */
    private String reportTime;
    
    /** 送检医生 */
    private String doctor;
    
    /** 检查项目列表 */
    private List<ReportItem> items;
    
    /** 报告结论 */
    private String conclusion;
    
    /** 报告备注 */
    private String remarks;
    
    /** 是否异常 */
    public boolean hasAbnormal() {
        return items != null && items.stream().anyMatch(ReportItem::isAbnormal);
    }
    
    /** 获取异常项目数 */
    public long getAbnormalCount() {
        return items != null ? items.stream().filter(ReportItem::isAbnormal).count() : 0;
    }
}
```

### 3.5 患者信息（脱敏版）(PatientInfo)

```java
@Data
@Builder
public class PatientInfo {
    /** 患者ID */
    private String patientId;
    
    /** 脱敏姓名（显示姓氏） */
    private String name;
    
    /** 脱敏手机号 */
    private String phone;
    
    /** 脱敏证件号 */
    private String idCard;
    
    /** 年龄 */
    private Integer age;
    
    /** 性别 */
    private String gender;
}
```

---

## 四、Executor 实现

### 4.1 ReportQueryMCPExecutor.java

文件路径：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/ReportQueryMCPExecutor.java`

```java
package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.enums.ReportStatus;
import com.nageoffer.ai.ragent.mcp.enums.ReportType;
import com.nageoffer.ai.ragent.mcp.model.MedicalReport;
import com.nageoffer.ai.ragent.mcp.service.PrivacyService;
import com.nageoffer.ai.ragent.mcp.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportQueryMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "report_query";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final ReportService reportService;
    private final PrivacyService privacyService;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("reportType", MCPToolDefinition.ParameterDef.builder()
                .description("报告类型：血常规、尿常规、生化、免疫、X光、CT、核磁、B超、心电图、其他")
                .type("string")
                .required(false)
                .enumValues(List.of(
                        "血常规", "尿常规", "生化", "免疫", "X光", "CT", "核磁", "B超", "心电图", "其他"
                ))
                .build());

        parameters.put("startDate", MCPToolDefinition.ParameterDef.builder()
                .description("查询开始日期，格式：YYYY-MM-DD，如：2026-04-01")
                .type("string")
                .required(false)
                .build());

        parameters.put("endDate", MCPToolDefinition.ParameterDef.builder()
                .description("查询结束日期，格式：YYYY-MM-DD，如：2026-04-20")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询患者的检验、检查报告结果，包含关键指标和参考值对比，可查看血常规、尿常规、生化、影像等各类报告")
                .parameters(parameters)
                .requireUserId(true)  // 必须用户认证
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 验证用户认证
            String userId = request.getUserId();
            if (userId == null || userId.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "UNAUTHORIZED", 
                        "请先登录后再查询报告");
            }

            // 2. 解析参数
            String reportType = request.getStringParameter("reportType");
            String startDate = request.getStringParameter("startDate");
            String endDate = request.getStringParameter("endDate");

            // 3. 验证日期参数
            LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
            LocalDate end = parseDate(endDate, LocalDate.now());
            
            if (start.isAfter(end)) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", 
                        "开始日期不能晚于结束日期");
            }

            // 4. 查询报告数据（使用用户ID作为患者标识）
            List<MedicalReport> reports = reportService.queryReports(
                    userId, reportType, start, end);

            // 5. 数据脱敏处理
            reports = privacyService.maskSensitiveData(reports);

            // 6. 构建响应
            String textResult = buildResultText(reports, reportType, start, end);
            
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCount", reports.size());
            data.put("reportList", reports.stream()
                    .map(reportService::toSummaryMap)
                    .toList());

            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(TOOL_ID)
                    .textResult(textResult)
                    .data(data)
                    .costMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("报告查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "QUERY_ERROR", 
                    "查询报告失败: " + e.getMessage());
        }
    }

    private LocalDate parseDate(String dateStr, LocalDate defaultValue) {
        if (dateStr == null || dateStr.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("日期解析失败: {}", dateStr);
            return defaultValue;
        }
    }

    private String buildResultText(List<MedicalReport> reports, String reportType,
                                  LocalDate startDate, LocalDate endDate) {
        StringBuilder sb = new StringBuilder();
        
        // 标题
        sb.append("【校医院检查报告查询结果】\n\n");
        
        // 日期范围
        sb.append(String.format("查询范围：%s 至 %s\n", 
                startDate.format(DATE_FORMATTER), endDate.format(DATE_FORMATTER)));
        
        if (reportType != null && !reportType.isBlank()) {
            sb.append(String.format("报告类型：%s\n", reportType));
        }
        
        sb.append(String.format("共查询到 %d 份报告\n\n", reports.size()));
        sb.append("────────────────────────────────────────\n\n");

        if (reports.isEmpty()) {
            sb.append("未查询到符合条件的报告。\n");
        } else {
            // 按报告时间倒序排列
            for (int i = 0; i < reports.size(); i++) {
                MedicalReport report = reports.get(i);
                sb.append(formatReportDetail(report, i + 1));
                if (i < reports.size() - 1) {
                    sb.append("\n────────────────────────────────────────\n\n");
                }
            }
        }

        // 提示信息
        sb.append("\n\n💡 温馨提示：\n");
        sb.append("• 以上为模拟数据，实际报告请以医院出具版本为准\n");
        sb.append("• 如有疑问，请咨询医务人员或前往医院查询\n");
        sb.append("• 报告仅供辅助参考，最终诊断以医生为准");

        return sb.toString().trim();
    }

    private String formatReportDetail(MedicalReport report, int index) {
        StringBuilder sb = new StringBuilder();

        // 报告概览
        sb.append(String.format("【%d】%s\n", index, report.getReportName()));
        sb.append(String.format("报告编号：%s\n", report.getReportNo()));
        sb.append(String.format("报告类型：%s\n", report.getReportType().getDisplayName()));
        
        // 状态
        String statusText = switch (report.getStatus()) {
            case AVAILABLE -> "✅ 已出";
            case PROCESSING -> "⏳ 处理中";
            case PENDING -> "📝 未出";
            case ARCHIVED -> "📁 已归档";
        };
        sb.append(String.format("状态：%s\n", statusText));

        if (report.getStatus() != ReportStatus.AVAILABLE) {
            sb.append(String.format("采样时间：%s\n", report.getSampleTime()));
            sb.append("\n⚠️ 报告尚未出具，请耐心等待或前往医院咨询。");
            return sb.toString();
        }

        // 已出报告的详细信息
        if (report.getSampleTime() != null) {
            sb.append(String.format("采样时间：%s\n", report.getSampleTime()));
        }
        if (report.getReportTime() != null) {
            sb.append(String.format("报告时间：%s\n", report.getReportTime()));
        }
        if (report.getDoctor() != null) {
            sb.append(String.format("送检医生：%s\n", report.getDoctor()));
        }

        // 检查项目
        if (report.getItems() != null && !report.getItems().isEmpty()) {
            sb.append("\n📋 检查结果：\n");
            
            for (var item : report.getItems()) {
                String flag = "";
                if ("H".equals(item.getFlag())) {
                    flag = " ⬆️ 偏高";
                } else if ("L".equals(item.getFlag())) {
                    flag = " ⬇️ 偏低";
                }
                
                String valueStr = item.getValue() + (item.getUnit() != null ? " " + item.getUnit() : "");
                sb.append(String.format("  • %s：%s%s\n", 
                        item.getName(), valueStr, flag));
                
                if (item.getReferenceRange() != null && !item.getReferenceRange().isBlank()) {
                    sb.append(String.format("    参考值：%s\n", item.getReferenceRange()));
                }
            }

            // 异常统计
            long abnormalCount = report.getItems().stream()
                    .filter(item -> item.getFlag() != null && !"N".equals(item.getFlag()))
                    .count();
            
            if (abnormalCount > 0) {
                sb.append(String.format("\n⚠️ 共 %d 项指标异常，请关注。\n", abnormalCount));
            } else {
                sb.append("\n✨ 所有指标均在正常范围内。\n");
            }
        }

        // 结论
        if (report.getConclusion() != null && !report.getConclusion().isBlank()) {
            sb.append(String.format("\n📝 结论：%s\n", report.getConclusion()));
        }

        return sb.toString().trim();
    }
}
```

---

## 五、Service 实现

### 5.1 ReportService.java

文件路径：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/ReportService.java`

```java
package com.nageoffer.ai.ragent.mcp.service;

import com.nageoffer.ai.ragent.mcp.enums.ReportStatus;
import com.nageoffer.ai.ragent.mcp.enums.ReportType;
import com.nageoffer.ai.ragent.mcp.model.MedicalReport;
import com.nageoffer.ai.ragent.mcp.model.ReportItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportService {

    private static final DateTimeFormatter DATETIME_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    // 报告数据缓存（按用户+日期）
    private final Map<String, List<MedicalReport>> reportCache = new ConcurrentHashMap<>();
    
    // 医生名单
    private static final List<String> DOCTORS = List.of(
            "张医生", "李医生", "王医生", "刘医生", "陈医生",
            "杨医生", "赵医生", "黄医生", "周医生", "吴医生"
    );

    /**
     * 查询报告列表
     */
    public List<MedicalReport> queryReports(String userId, String reportType,
                                           LocalDate startDate, LocalDate endDate) {
        // 初始化用户报告数据（如果需要）
        initializeUserReportsIfNeeded(userId);
        
        String cacheKey = buildCacheKey(userId, startDate, endDate);
        List<MedicalReport> reports = reportCache.getOrDefault(cacheKey, Collections.emptyList());
        
        // 按类型过滤
        if (reportType != null && !reportType.isBlank()) {
            ReportType type = matchReportType(reportType);
            if (type != null) {
                reports = reports.stream()
                        .filter(r -> r.getReportType() == type)
                        .toList();
            }
        }
        
        // 按日期排序（倒序）
        return reports.stream()
                .sorted((a, b) -> {
                    String timeA = a.getReportTime() != null ? a.getReportTime() : a.getSampleTime();
                    String timeB = b.getReportTime() != null ? b.getReportTime() : b.getSampleTime();
                    return timeB.compareTo(timeA);
                })
                .collect(Collectors.toList());
    }

    /**
     * 初始化用户报告数据（模拟）
     */
    private synchronized void initializeUserReportsIfNeeded(String userId) {
        String today = LocalDate.now().toString();
        String cacheKey = userId + "_" + today;
        
        if (reportCache.containsKey(cacheKey)) {
            return;
        }
        
        List<MedicalReport> reports = generateMockReports(userId);
        reportCache.put(cacheKey, reports);
        log.info("报告模拟数据初始化完成，用户：{}，记录数：{}", userId, reports.size());
    }

    /**
     * 生成模拟报告数据
     */
    private List<MedicalReport> generateMockReports(String userId) {
        List<MedicalReport> reports = new ArrayList<>();
        Random random = new Random(userId.hashCode());
        
        LocalDate today = LocalDate.now();
        
        // 生成最近30天的报告
        for (int daysAgo = 0; daysAgo < 30; daysAgo++) {
            LocalDate date = today.minusDays(daysAgo);
            
            // 每天随机生成0-3份报告
            int reportCount = random.nextInt(4);
            
            for (int i = 0; i < reportCount; i++) {
                ReportType type = ReportType.values()[random.nextInt(ReportType.values().length)];
                MedicalReport report = generateSingleReport(type, date, random);
                reports.add(report);
            }
        }
        
        return reports;
    }

    /**
     * 生成单份报告
     */
    private MedicalReport generateSingleReport(ReportType type, LocalDate date, Random random) {
        String reportId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String reportNo = String.format("RPT%s%s%04d", 
                date.format(DateTimeFormatter.ofPattern("yyyyMMdd")), 
                type.name().substring(0, 2), 
                random.nextInt(10000));
        
        LocalDateTime sampleTime = date.atTime(8 + random.nextInt(9), random.nextInt(60));
        LocalDateTime reportTime = sampleTime.plusHours(2 + random.nextInt(4));
        
        // 70% 已出，20% 处理中，10% 未出
        ReportStatus status;
        int statusRoll = random.nextInt(100);
        if (statusRoll < 70) {
            status = ReportStatus.AVAILABLE;
        } else if (statusRoll < 90) {
            status = ReportStatus.PROCESSING;
        } else {
            status = ReportStatus.PENDING;
        }
        
        String doctor = DOCTORS.get(random.nextInt(DOCTORS.size()));
        
        // 生成检查项目
        List<ReportItem> items = generateReportItems(type, random);
        
        return MedicalReport.builder()
                .reportId(reportId)
                .reportNo(reportNo)
                .reportType(type)
                .reportName(buildReportName(type))
                .status(status)
                .sampleTime(sampleTime.format(DATETIME_FORMATTER))
                .reportTime(status == ReportStatus.AVAILABLE ? reportTime.format(DATETIME_FORMATTER) : null)
                .doctor(doctor)
                .items(status == ReportStatus.AVAILABLE ? items : null)
                .conclusion(generateConclusion(type, items, random))
                .build();
    }

    /**
     * 生成报告名称
     */
    private String buildReportName(ReportType type) {
        return switch (type) {
            case BLOOD_ROUTINE -> "血液常规检验报告";
            case URINE_ROUTINE -> "尿液常规检验报告";
            case BIOCHEMISTRY -> "生化检验报告";
            case IMMUNITY -> "免疫学检验报告";
            case XRAY -> "数字化X线摄影（DR）报告";
            case CT -> "计算机断层扫描（CT）报告";
            case MRI -> "磁共振成像（MRI）报告";
            case BULTRASOUND -> "彩色多普勒超声检查报告";
            case ECG -> "常规十二导联心电图报告";
            default -> "医学检查报告";
        };
    }

    /**
     * 生成检查项目
     */
    private List<ReportItem> generateReportItems(ReportType type, Random random) {
        List<ReportItem> items = new ArrayList<>();
        
        switch (type) {
            case BLOOD_ROUTINE -> {
                items.addAll(generateBloodRoutineItems(random));
            }
            case URINE_ROUTINE -> {
                items.addAll(generateUrineRoutineItems(random));
            }
            case BIOCHEMISTRY -> {
                items.addAll(generateBiochemistryItems(random));
            }
            case ECG -> {
                items.add(ReportItem.builder()
                        .name("心率").value(String.valueOf(60 + random.nextInt(40)))
                        .unit("次/分").referenceRange("60-100").flag("N").build());
                items.add(ReportItem.builder()
                        .name("PR间期").value(String.valueOf(120 + random.nextInt(80)))
                        .unit("ms").referenceRange("120-200").flag("N").build());
                items.add(ReportItem.builder()
                        .name("QRS波群").value(String.valueOf(80 + random.nextInt(40)))
                        .unit("ms").referenceRange("80-120").flag("N").build());
            }
            default -> {
                items.add(ReportItem.builder()
                        .name("检查结果").value("未见明显异常")
                        .flag("N").build());
            }
        }
        
        return items;
    }

    private List<ReportItem> generateBloodRoutineItems(Random random) {
        List<ReportItem> items = new ArrayList<>();
        
        // 白细胞
        double wbc = 4.0 + random.nextDouble() * 6.0;
        items.add(ReportItem.builder()
                .name("白细胞(WBC)")
                .value(String.format("%.2f", wbc))
                .unit("×10⁹/L")
                .referenceRange("3.50-9.50")
                .flag(wbc < 3.5 ? "L" : wbc > 9.5 ? "H" : "N")
                .build());
        
        // 红细胞
        double rbc = 3.5 + random.nextDouble() * 2.5;
        items.add(ReportItem.builder()
                .name("红细胞(RBC)")
                .value(String.format("%.2f", rbc))
                .unit("×10¹²/L")
                .referenceRange("3.80-5.10")
                .flag(rbc < 3.8 ? "L" : rbc > 5.1 ? "H" : "N")
                .build());
        
        // 血红蛋白
        double hgb = 110 + random.nextDouble() * 60;
        items.add(ReportItem.builder()
                .name("血红蛋白(HGB)")
                .value(String.format("%.0f", hgb))
                .unit("g/L")
                .referenceRange("115-150")
                .flag(hgb < 115 ? "L" : hgb > 150 ? "H" : "N")
                .build());
        
        // 血小板
        int plt = 100 + random.nextInt(200);
        items.add(ReportItem.builder()
                .name("血小板(PLT)")
                .value(String.valueOf(plt))
                .unit("×10⁹/L")
                .referenceRange("125-350")
                .flag(plt < 125 ? "L" : plt > 350 ? "H" : "N")
                .build());
        
        // 淋巴细胞百分比
        double lymPct = 20 + random.nextDouble() * 40;
        items.add(ReportItem.builder()
                .name("淋巴细胞百分比(LYMP%)")
                .value(String.format("%.1f", lymPct))
                .unit("%")
                .referenceRange("20.0-50.0")
                .flag(lymPct < 20 ? "L" : lymPct > 50 ? "H" : "N")
                .build());
        
        return items;
    }

    private List<ReportItem> generateUrineRoutineItems(Random random) {
        List<ReportItem> items = new ArrayList<>();
        
        items.add(ReportItem.builder()
                .name("尿蛋白").value(random.nextInt(3) == 0 ? "阳性" : "阴性")
                .referenceRange("阴性").flag("N").build());
        
        items.add(ReportItem.builder()
                .name("尿糖").value(random.nextInt(5) == 0 ? "+" : "-")
                .referenceRange("阴性").flag("N").build());
        
        items.add(ReportItem.builder()
                .name("尿潜血").value(random.nextInt(4) == 0 ? "阳性" : "阴性")
                .referenceRange("阴性").flag("N").build());
        
        items.add(ReportItem.builder()
                .name("酸碱度(pH)")
                .value(String.format("%.1f", 5.0 + random.nextDouble() * 3.0))
                .unit("")
                .referenceRange("4.5-8.0")
                .flag("N")
                .build());
        
        items.add(ReportItem.builder()
                .name("尿比重")
                .value(String.format("%.%.3f", 1.010 + random.nextDouble() * 0.020))
                .referenceRange("1.003-1.030")
                .flag("N")
                .build());
        
        return items;
    }

    private List<ReportItem> generateBiochemistryItems(Random random) {
        List<ReportItem> items = new ArrayList<>();
        
        // 谷丙转氨酶
        double alt = 10 + random.nextDouble() * 60;
        items.add(ReportItem.builder()
                .name("谷丙转氨酶(ALT)")
                .value(String.format("%.1f", alt))
                .unit("U/L")
                .referenceRange("9-50")
                .flag(alt > 50 ? "H" : "N")
                .build());
        
        // 谷草转氨酶
        double ast = 10 + random.nextDouble() * 50;
        items.add(ReportItem.builder()
                .name("谷草转氨酶(AST)")
                .value(String.format("%.1f", ast))
                .unit("U/L")
                .referenceRange("15-40")
                .flag(ast > 40 ? "H" : "N")
                .build());
        
        // 空腹血糖
        double glucose = 3.9 + random.nextDouble() * 4.0;
        items.add(ReportItem.builder()
                .name("空腹血糖(GLU)")
                .value(String.format("%.1f", glucose))
                .unit("mmol/L")
                .referenceRange("3.90-6.10")
                .flag(glucose < 3.9 ? "L" : glucose > 6.1 ? "H" : "N")
                .build());
        
        // 肌酐
        double creatinine = 45 + random.nextDouble() * 60;
        items.add(ReportItem.builder()
                .name("肌酐(CRE)")
                .value(String.format("%.1f", creatinine))
                .unit("μmol/L")
                .referenceRange("41-73")
                .flag(creatinine > 73 ? "H" : "N")
                .build());
        
        // 尿素氮
        double bun = 2.6 + random.nextDouble() * 6.0;
        items.add(ReportItem.builder()
                .name("尿素氮(BUN)")
                .value(String.format("%.1f", bun))
                .unit("mmol/L")
                .referenceRange("2.6-7.5")
                .flag(bun > 7.5 ? "H" : "N")
                .build());
        
        return items;
    }

    /**
     * 生成结论
     */
    private String generateConclusion(ReportType type, List<ReportItem> items, Random random) {
        if (items == null || items.isEmpty()) {
            return "未见明显异常";
        }
        
        long abnormalCount = items.stream()
                .filter(item -> item.getFlag() != null && !"N".equals(item.getFlag()))
                .count();
        
        if (abnormalCount == 0) {
            return "检验结果在正常范围内。";
        } else if (abnormalCount <= 2) {
            return String.format("部分指标轻度异常，建议定期复查。", abnormalCount);
        } else {
            return String.format("部分指标异常，建议进一步检查或咨询医生。", abnormalCount);
        }
    }

    /**
     * 匹配报告类型
     */
    private ReportType matchReportType(String reportType) {
        if (reportType == null) return null;
        
        String type = reportType.trim();
        return switch (type) {
            case "血常规", "血液", "血" -> ReportType.BLOOD_ROUTINE;
            case "尿常规", "尿液", "尿" -> ReportType.URINE_ROUTINE;
            case "生化", "生化检验" -> ReportType.BIOCHEMISTRY;
            case "免疫", "免疫检验" -> ReportType.IMMUNITY;
            case "X光", "X-ray", "Xray", "DR" -> ReportType.XRAY;
            case "CT", "ct" -> ReportType.CT;
            case "核磁", "MRI", "核磁共振" -> ReportType.MRI;
            case "B超", "超声", "彩超" -> ReportType.BULTRASOUND;
            case "心电图", "心电", "ECG" -> ReportType.ECG;
            default -> {
                for (ReportType rt : ReportType.values()) {
                    if (rt.getDisplayName().contains(type) || type.contains(rt.getDisplayName())) {
                        yield rt;
                    }
                }
                yield null;
            }
        };
    }

    private String buildCacheKey(String userId, LocalDate startDate, LocalDate endDate) {
        return userId + "_" + startDate.toString() + "_" + endDate.toString();
    }

    /**
     * 转换为摘要Map
     */
    public Map<String, Object> toSummaryMap(MedicalReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportId", report.getReportId());
        map.put("reportNo", report.getReportNo());
        map.put("reportType", report.getReportType().getDisplayName());
        map.put("reportName", report.getReportName());
        map.put("status", report.getStatus().getDisplayName());
        if (report.getReportTime() != null) {
            map.put("reportTime", report.getReportTime());
        } else if (report.getSampleTime() != null) {
            map.put("sampleTime", report.getSampleTime());
        }
        map.put("hasAbnormal", report.hasAbnormal());
        return map;
    }
}
```

### 5.2 PrivacyService.java

文件路径：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/PrivacyService.java`

```java
package com.nageoffer.ai.ragent.mcp.service;

import com.nageoffer.ai.ragent.mcp.model.MedicalReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据脱敏服务
 * <p>
 * 对报告中的敏感信息进行脱敏处理，防止隐私泄露
 */
@Slf4j
@Service
public class PrivacyService {

    // 手机号正则：138****5678
    private static final Pattern PHONE_PATTERN = 
            Pattern.compile("(\\d{3})\\d{4}(\\d{4})");
    
    // 身份证正则：310***********1234
    private static final Pattern ID_CARD_PATTERN = 
            Pattern.compile("(\\d{3})\\d{10}(\\d{4})");
    
    // 姓名保留姓氏：全名 → 张**
    private static final Pattern NAME_PATTERN = 
            Pattern.compile("^([\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]+)$");

    /**
     * 对报告列表进行脱敏处理
     */
    public List<MedicalReport> maskSensitiveData(List<MedicalReport> reports) {
        if (reports == null) {
            return new ArrayList<>();
        }
        
        return reports.stream()
                .map(this::maskReportData)
                .collect(Collectors.toList());
    }

    /**
     * 对单份报告进行脱敏处理
     */
    private MedicalReport maskReportData(MedicalReport report) {
        if (report == null) {
            return null;
        }
        
        // 克隆报告对象（避免修改原数据）
        MedicalReport masked = MedicalReport.builder()
                .reportId(maskReportId(report.getReportId()))
                .reportNo(report.getReportNo())
                .reportType(report.getReportType())
                .reportName(report.getReportName())
                .status(report.getStatus())
                .sampleTime(report.getSampleTime())
                .reportTime(report.getReportTime())
                .doctor(report.getDoctor())
                .items(report.getItems())
                .conclusion(report.getConclusion())
                .remarks(report.getRemarks())
                .build();
        
        return masked;
    }

    /**
     * 脱敏报告ID：保留前后各2位
     */
    public String maskReportId(String reportId) {
        if (reportId == null || reportId.length() <= 4) {
            return "****";
        }
        return reportId.substring(0, 2) + "****" + reportId.substring(reportId.length() - 2);
    }

    /**
     * 脱敏手机号：138****5678
     */
    public String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return PHONE_PATTERN.matcher(phone).replaceAll("$1****$2");
    }

    /**
     * 脱敏身份证号：310***********1234
     */
    public String maskIdCard(String idCard) {
        if (idCard == null) {
            return null;
        }
        return ID_CARD_PATTERN.matcher(idCard).replaceAll("$1***********$2");
    }

    /**
     * 脱敏姓名：保留姓氏，张三 → 张*
     */
    public String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        
        var matcher = NAME_PATTERN.matcher(name);
        if (matcher.matches()) {
            String surname = matcher.group(1);
            String remaining = matcher.group(2);
            return surname + "*".repeat(Math.max(1, remaining.length()));
        }
        
        // 英文名或混合：保留首字母
        if (name.length() > 2) {
            return name.charAt(0) + "*".repeat(name.length() - 1);
        }
        
        return "*".repeat(name.length());
    }

    /**
     * 脱敏地址：保留省市
     */
    public String maskAddress(String address) {
        if (address == null || address.length() <= 10) {
            return "******";
        }
        return address.substring(0, 6) + "******" + 
                (address.length() > 20 ? address.substring(address.length() - 4) : "");
    }

    /**
     * 记录审计日志
     */
    public void logAudit(String userId, String action, String reportType) {
        log.info("[审计] 用户: {}, 操作: {}, 报告类型: {}, 时间: {}", 
                userId, action, reportType, java.time.LocalDateTime.now());
    }
}
```

---

## 六、Intent Tree 配置

### 6.1 修改 IntentTreeFactory.java

**修改位置**：在 `queueQuery` 节点后添加

```java
// 报告查询
IntentNode reportQuery = IntentNode.builder()
        .id("medical-report")
        .name("报告查询")
        .level(CATEGORY)
        .parentId(medical.getId())
        .mcpToolId("report_query")
        .kind(IntentKind.MCP)
        .promptTemplate(MCP_REPORT_QUERY_PROMPT_TEMPLATE)
        .paramPromptTemplate(MCP_REPORT_PARAMETER_EXTRACT_PROMPT)
        .description("查询患者的检验、检查报告结果")
        .examples(List.of(
                "我的血常规报告出来了吗？",
                "最近的CT检查结果是什么？",
                "查看一下上周的尿常规",
                "生化检验有什么异常吗？",
                "心电图报告在哪里看？"
        ))
        .topK(1)
        .build();

medical.setChildren(List.of(queueQuery, reportQuery));
```

**新增常量**：在文件末尾添加

```java
// ===================== 检查报告 MCP 提示词模板 =====================

public static final String MCP_REPORT_PARAMETER_EXTRACT_PROMPT = """
        Hello，你是一个高度专业且严谨的【工具参数提取器】。
        
        你的唯一任务是：严格按照提供的【工具定义】和【参数列表】的约束，
        从【用户问题】中提取所有必要的参数，并以 JSON 格式输出。
        
        ### 核心提取逻辑
        1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
        2. **参数范围限定**：只提取 <parameters> 标签内定义的参数。
        3. **报告类型识别**：将用户口语化表达映射到支持的类型：
           - "血常规"、"验血" → "血常规"
           - "尿检"、"尿液" → "尿常规"
           - "CT"、"ct" → "CT"
        4. **日期识别**：将相对日期映射为标准格式：
           - "今天" → 今天日期
           - "上周"、"这周" → 对应日期范围
        
        ### 输入数据与输出格式
        请勿在输出 JSON 对象之外添加任何解释、注释或其他文本。
        
        #### 【工具定义】
        <tool_definition>
        %s
        </tool_definition>
        
        #### 【用户问题】
        <user_query>
        %s
        </user_query>
        
        #### 【输出格式（JSON Object Only）】
        {"param_name_1": value_1, "param_name_2": value_2, ...}
        """;

private static final String MCP_REPORT_QUERY_PROMPT_TEMPLATE = """
        Hello，你是专业的校医院检验报告解读助手。
        系统已调用内部工具获取到了最新的【检验报告数据】。
        你的任务是将这些数据转化为**专业、易懂**的回复。
        
        【核心处理规则】
        1. **结构化呈现**：
           - 先展示报告基本信息（类型、时间、状态）
           - 再展示各项指标结果
           - 最后给出结论和建议
        2. **异常高亮**：
           - 使用 ⬆️ 标注偏高指标
           - 使用 ⬇️ 标注偏低指标
           - 简要说明异常的可能原因
        3. **参考值说明**：
           - 清晰展示参考范围
           - 帮助用户理解指标含义
        4. **专业但不晦涩**：
           - 使用通俗语言解释医学术语
           - 避免过多专业缩写
        
        【异常与边界处理】
        1. **报告未出**：说明报告状态，建议等待时间或咨询医院。
        2. **数据为空**：提示用户没有符合条件的报告，建议检查日期范围。
        3. **全部正常**：正面告知，增强用户信心。
        4. **部分异常**：客观说明，指导后续行动（如复查、就诊）。
        
        【重要声明】
        - 明确标注"仅供参考，以医生诊断为准"
        - 不做疾病诊断或治疗建议
        - 异常指标建议"咨询医生"
        
        【禁止事项】
        - 严禁做出诊断结论
        - 严禁推荐具体用药
        - 严禁保证报告准确性
        
        【检验报告数据】
        %s
        
        【用户问题】
        %s
        """;
```

---

## 七、安全措施

### 7.1 身份验证流程

```java
@Override
public MCPToolResponse execute(MCPToolRequest request) {
    // 1. 检查用户ID是否存在
    String userId = request.getUserId();
    if (userId == null || userId.isBlank()) {
        log.warn("[安全] 报告查询请求缺少用户ID");
        return MCPToolResponse.error(TOOL_ID, "UNAUTHORIZED", 
                "请先登录后再查询报告");
    }
    
    // 2. 验证用户状态（可选：调用用户服务验证）
    // if (!userService.isActive(userId)) {
    //     return MCPToolResponse.error(TOOL_ID, "USER_INACTIVE", "用户账号已停用");
    // }
    
    // 3. 记录审计日志
    privacyService.logAudit(userId, "QUERY_REPORT", 
            request.getStringParameter("reportType"));
    
    // 4. 继续执行查询...
}
```

### 7.2 数据访问控制

```java
/**
 * 检查用户是否有权限访问某份报告
 * <p>
 * 实际实现中，应连接用户-患者关系表进行验证
 */
public boolean canAccessReport(String userId, String patientId) {
    // 模拟：userId 即为 patientId
    // 实际应查询数据库验证关系
    return userId != null && userId.equals(patientId);
}
```

### 7.3 脱敏处理清单

| 数据类型 | 脱敏方式 | 示例 |
|----------|----------|------|
| 手机号 | 中间4位隐藏 | 138****5678 |
| 身份证号 | 中间10位隐藏 | 310***********1234 |
| 姓名 | 保留姓氏 | 张三 → 张* |
| 详细地址 | 保留省市 | 重庆市北碚区... |
| 报告ID | 保留前后2位 | AB****12 |

---

## 八、测试用例设计

### 8.1 单元测试用例

| 用例编号 | 输入 | 预期输出 |
|----------|------|----------|
| TC-R001 | 无参数（登录用户） | 返回最近30天全部报告 |
| TC-R002 | `reportType=血常规` | 返回血常规报告列表 |
| TC-R003 | `reportType=CT, startDate=2026-04-01` | 返回4月份CT报告 |
| TC-R004 | `reportType=不存在的类型` | 返回空列表 |
| TC-R005 | 无userId | 返回UNAUTHORIZED错误 |
| TC-R006 | startDate > endDate | 返回INVALID_PARAMS错误 |

### 8.2 安全测试用例

| 用例编号 | 测试场景 | 预期结果 |
|----------|----------|----------|
| SEC-001 | 未登录用户查询 | 返回UNAUTHORIZED |
| SEC-002 | 查询返回数据包含手机号 | 手机号已脱敏 |
| SEC-003 | 查询返回数据包含身份证 | 身份证已脱敏 |
| SEC-004 | 审计日志记录 | 日志包含用户ID、操作类型、时间 |

### 8.3 端到端测试场景

**场景1：用户查询血常规报告**
```
用户：我的血常规报告出来了吗？
期望：返回血常规报告列表，包含各指标结果和参考值对比
```

**场景2：用户询问异常指标**
```
用户：生化检验有什么异常吗？
期望：重点展示异常指标，给出说明和建议
```

**场景3：报告未出**
```
用户：CT检查结果
期望：提示报告状态为"处理中"或"未出"，说明预计时间
```

---

## 九、实现步骤清单

| 步骤 | 任务 | 文件/位置 | 预计工时 |
|------|------|-----------|----------|
| 1 | 创建枚举 `ReportType.java` | `mcp-server/.../enums/` | 0.5h |
| 2 | 创建枚举 `ReportStatus.java` | `mcp-server/.../enums/` | 0.5h |
| 3 | 创建模型 `ReportItem.java` | `mcp-server/.../model/` | 0.5h |
| 4 | 创建模型 `MedicalReport.java` | `mcp-server/.../model/` | 0.5h |
| 5 | 创建服务 `PrivacyService.java` | `mcp-server/.../service/` | 1h |
| 6 | 创建服务 `ReportService.java` | `mcp-server/.../service/` | 3h |
| 7 | 创建执行器 `ReportQueryMCPExecutor.java` | `mcp-server/.../executor/` | 2h |
| 8 | 配置 IntentTree | `bootstrap/.../IntentTreeFactory.java` | 1h |
| 9 | 添加 Prompt 模板 | `bootstrap/.../prompt/` | 1h |
| 10 | 安全测试 | - | 2h |
| **总计** | | | **12h** |

---

## 十、后续扩展建议

### 10.1 对接真实系统

当校医院具备以下条件时，可升级为真实数据：

| 数据源 | 集成方式 | 复杂度 |
|--------|----------|--------|
| LIS（检验信息系统） | API对接或数据库直连 | 中 |
| PACS（影像系统） | API对接获取报告文本 | 高 |
| 医院HIS系统 | API对接 | 高 |

### 10.2 功能增强

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 报告推送 | 新报告出具时主动推送 | P1 |
| 趋势分析 | 多期报告指标对比 | P2 |
| 报告解读 | AI辅助解读报告含义 | P2 |
| PDF下载 | 支持下载原始报告 | P2 |
| 历史追溯 | 查询更长时间跨度报告 | P3 |

---

## 十一、免责声明

当前实现使用**模拟数据**，涉及隐私处理逻辑：

1. 所有报告数据均为随机生成，不反映真实患者信息
2. 脱敏处理逻辑已实现，但实际效果需对接真实系统后验证
3. 接入真实系统前，请勿用于实际医疗决策

---

*文档结束*
