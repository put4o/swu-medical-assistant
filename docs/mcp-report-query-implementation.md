# 检查报告查询 MCP 工具实现方案

> 文档版本：v2.0（简化版 + PDF下载）
> 创建日期：2026-04-20
> 工具 ID：`report_query`
> 隐私等级：高（需用户认证）

---

## 一、需求概述

### 输入参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `reportType` | string | 否 | 报告类型：血常规、尿常规、生化、CT、核磁、B超、心电图等 |
| `startDate` | string | 否 | 查询开始日期（YYYY-MM-DD），默认最近30天 |
| `endDate` | string | 否 | 查询结束日期，默认今天 |

### 输出内容

| 字段 | 说明 |
|------|------|
| 报告列表 | 查询到的报告概要 |
| 指标结果 | 各检查项目的数值和参考范围 |
| 异常标注 | 高于/低于参考值的指标高亮 |
| 报告状态 | 已出/未出/处理中 |
| **PDF链接** | 报告PDF下载链接（已出具的报告） |

---

## 二、数据库设计

### 2.1 患者表 `t_patient`

```sql
CREATE TABLE t_patient (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(20)  NOT NULL,           -- 关联系统用户ID
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(16),
    deleted         SMALLINT     DEFAULT 0,
    UNIQUE (user_id, deleted)
);
```

### 2.2 检查报告表 `t_medical_report`

```sql
CREATE TABLE t_medical_report (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(20)  NOT NULL,           -- 系统用户ID（用于查询权限）
    patient_name    VARCHAR(64),                     -- 脱敏后的患者姓名
    report_no       VARCHAR(64)  NOT NULL UNIQUE,
    type            VARCHAR(32)  NOT NULL,           -- 报告类型
    name            VARCHAR(256),
    category        VARCHAR(16),                     -- 检验, 检查
    status          VARCHAR(16)  DEFAULT 'pending', -- pending, processing, available
    sample_time     TIMESTAMP,
    report_time     TIMESTAMP,
    doctor          VARCHAR(64),
    conclusion      TEXT,
    items_json      JSONB,                           -- 检查项目JSON
    pdf_url         VARCHAR(512),                    -- PDF下载链接
    report_date     DATE,
    deleted         SMALLINT     DEFAULT 0
);
CREATE INDEX idx_report_user ON t_medical_report (user_id);
CREATE INDEX idx_report_type ON t_medical_report (type);
CREATE INDEX idx_report_date ON t_medical_report (report_date);
```

**设计说明：**
- `items_json`：用 JSONB 存储检查项目，避免过度拆分表。JSON 示例：

```json
[
  {"name": "白细胞(WBC)", "value": "11.20", "unit": "×10⁹/L", "ref": "3.50-9.50", "flag": "H"},
  {"name": "红细胞(RBC)", "value": "4.25", "unit": "×10¹²/L", "ref": "3.80-5.10", "flag": "N"}
]
```

- `pdf_url`：PDF 存储路径或 CDN 链接，可通过 Nginx 静态资源或后端接口返回

---

## 三、技术架构

```
┌──────────────────────────────────────────────────────┐
│               MCP Client (Bootstrap)                   │
│           IntentResolver → MCP Tool                    │
└─────────────────────────┬────────────────────────────┘
                          │ HTTP JSON-RPC
                          ▼
┌──────────────────────────────────────────────────────┐
│              MCP Server (mcp-server)                   │
│  ┌─────────────────┐   ┌───────────────────────────┐ │
│  │ MCPEndpoint     │ → │ReportQueryMCPExecutor    │ │
│  └─────────────────┘   └───────────┬───────────────┘ │
│                                     │                  │
│                                     ▼                  │
│                           ┌───────────────────┐      │
│                           │   ReportService    │      │
│                           └───────┬───────────┘      │
│                                   │                  │
│                                   ▼                  │
│                           ┌───────────────────┐      │
│                           │   Repository       │      │
│                           └───────────────────┘      │
└──────────────────────────────────────────────────────┘
```

---

## 四、数据模型

### 4.1 ReportVO

```java
@Data
@Builder
public class ReportVO {
    private String reportId;
    private String reportNo;
    private String patientName;     // 脱敏后
    private String type;            // 报告类型
    private String name;            // 报告名称
    private String status;          // pending, processing, available
    private String sampleTime;
    private String reportTime;
    private String doctor;
    private String conclusion;
    private List<ReportItemVO> items;  // 从 items_json 解析
    private boolean hasAbnormal;
    private Integer abnormalCount;
    private String pdfUrl;          // PDF下载链接
    private String reportDate;
}

@Data
@Builder
public class ReportItemVO {
    private String name;
    private String value;
    private String unit;
    private String ref;        // 参考范围
    private String flag;       // H(高)/L(低)/N(正常)
    
    public boolean isAbnormal() {
        return flag != null && !"N".equals(flag);
    }
}
```

---

## 五、Service 实现

```java
@Service
@RequiredArgsConstructor
public class ReportService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 查询报告列表
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
            params.add(endDate.atTime(23, 59, 59));
        }
        if (reportType != null && !reportType.isBlank()) {
            sql.append(" AND (type LIKE ? OR name LIKE ?)");
            params.add("%" + reportType + "%");
            params.add("%" + reportType + "%");
        }

        sql.append(" ORDER BY sample_time DESC NULLS LAST, report_time DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            // 解析 items_json
            List<ReportItemVO> items = parseItems(rs.getString("items_json"));
            int abnormalCount = (int) items.stream().filter(ReportItemVO::isAbnormal).count();

            String sampleTime = null;
            Timestamp st = rs.getTimestamp("sample_time");
            if (st != null) sampleTime = st.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            String reportTime = null;
            Timestamp rt = rs.getTimestamp("report_time");
            if (rt != null) reportTime = rt.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

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
                    .reportDate(rs.getString("report_date"))
                    .build();
        }, params.toArray());
    }

    /**
     * 解析 items_json
     */
    private List<ReportItemVO> parseItems(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReportItemVO>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
```

---

## 六、Executor 实现

```java
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
                .description("查询开始日期，格式：YYYY-MM-DD")
                .type("string")
                .required(false)
                .build());

        params.put("endDate", MCPToolDefinition.ParameterDef.builder()
                .description("查询结束日期，格式：YYYY-MM-DD")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询患者的检验、检查报告结果，支持PDF报告下载")
                .parameters(params)
                .requireUserId(true)  // 必须登录
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
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
    }

    private LocalDate parseDate(String s, LocalDate defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
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

        // 检查项目
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

            if (r.getHasAbnormal()) {
                sb.append(String.format("\n⚠️ 共 %d 项指标异常，请关注。\n", r.getAbnormalCount()));
            } else {
                sb.append("\n✨ 所有指标均在正常范围内。\n");
            }
        }

        // 结论
        if (r.getConclusion() != null && !r.getConclusion().isBlank()) {
            sb.append(String.format("\n📝 结论：%s\n", r.getConclusion()));
        }

        // PDF下载
        if (r.getPdfUrl() != null && !r.getPdfUrl().isBlank()) {
            sb.append(String.format("\n📄 PDF报告：%s\n", r.getPdfUrl()));
        }

        return sb.toString().trim();
    }
}
```

---

## 七、Intent Tree 配置

```java
IntentNode reportQuery = IntentNode.builder()
        .id("medical-report")
        .name("报告查询")
        .level(CATEGORY)
        .parentId(medical.getId())
        .mcpToolId("report_query")
        .kind(IntentKind.MCP)
        .description("查询患者的检验、检查报告结果")
        .examples(List.of(
                "我的血常规报告出来了吗？",
                "最近的CT检查结果是什么？",
                "查看一下上周的尿常规"
        ))
        .build();

medical.setChildren(List.of(queueQuery, reportQuery));
```

---

## 八、安全说明

| 机制 | 说明 |
|------|------|
| 用户认证 | `requireUserId = true`，必须登录 |
| 权限控制 | `t_medical_report.user_id` 直接关联系统用户，只能查询自己的报告 |
| 数据脱敏 | `patient_name` 已预脱敏（如 "王*明"），不在代码层二次处理 |

---

*文档结束*
