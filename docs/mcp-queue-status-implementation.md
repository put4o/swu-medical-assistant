# 叫号查询 MCP 工具实现方案

> 文档版本：v2.0（简化版）
> 创建日期：2026-04-20
> 工具 ID：`queue_status`

---

## 一、需求概述

### 输入参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `department` | string | 否 | 科室名称，如：内科、外科、儿科 |
| `doctorName` | string | 否 | 医生姓名（模糊匹配） |

### 输出内容

| 字段 | 说明 |
|------|------|
| 当前叫号 | 当前正在就诊的号码 |
| 等待人数 | 当前排队等待的人数 |
| 预计等待时间 | 根据等待人数估算的等待时长 |
| 科室/医生 | 科室和医生信息 |

---

## 二、数据库设计

### 2.1 科室表 `t_department`

```sql
CREATE TABLE t_department (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    code           VARCHAR(32)  NOT NULL UNIQUE,   -- NEIKE, WAIKE...
    name           VARCHAR(64)  NOT NULL,           -- 内科, 外科...
    floor          VARCHAR(16),
    work_time      VARCHAR(32)  DEFAULT '08:00-17:30',
    is_emergency   SMALLINT     DEFAULT 0,
    sort_order     INTEGER       DEFAULT 0,
    deleted        SMALLINT     DEFAULT 0
);
```

### 2.2 医生表 `t_doctor`

```sql
CREATE TABLE t_doctor (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(64)  NOT NULL,
    dept_id         VARCHAR(20)  NOT NULL,
    title           VARCHAR(32),                     -- 主任医师, 主治医师...
    specialty       VARCHAR(128),
    status          VARCHAR(16)  DEFAULT 'active',
    deleted         SMALLINT     DEFAULT 0,
    FOREIGN KEY (dept_id) REFERENCES t_department(id)
);
```

### 2.3 叫号状态表 `t_queue_status`（核心表）

```sql
CREATE TABLE t_queue_status (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    dept_id         VARCHAR(20)  NOT NULL,
    doctor_id       VARCHAR(20),
    queue_date      DATE        NOT NULL,
    current_no      INTEGER      DEFAULT 0,          -- 当前叫到的号
    waiting_count   INTEGER      DEFAULT 0,          -- 等待人数
    total_called    INTEGER      DEFAULT 0,          -- 今日已叫号总数
    status          VARCHAR(16)  DEFAULT 'open',    -- open, paused, closed
    last_call_time  TIMESTAMP,
    deleted         SMALLINT     DEFAULT 0,
    FOREIGN KEY (dept_id) REFERENCES t_department(id),
    UNIQUE (dept_id, queue_date, deleted)
);
```

### 2.4 数据关系

```
t_department (1) ─────── (N) t_doctor
      │                        │
      └── (N) t_queue_status ──┘
```

---

## 三、技术架构

```
┌─────────────────────────────────────────────────┐
│            MCP Client (Bootstrap)                 │
│         IntentResolver → MCP Tool                │
└─────────────────────────┬───────────────────────┘
                          │ HTTP JSON-RPC
                          ▼
┌─────────────────────────────────────────────────┐
│              MCP Server (mcp-server)              │
│  ┌─────────────────┐   ┌──────────────────────┐ │
│  │ MCPEndpoint     │ → │QueueStatusMCPExecutor │ │
│  └─────────────────┘   └──────────┬───────────┘ │
│                                    │              │
│                                    ▼              │
│                          ┌──────────────────┐    │
│                          │   QueueService    │    │
│                          └──────────┬─────────┘    │
│                                    │              │
│                                    ▼              │
│                          ┌──────────────────┐    │
│                          │    Repository     │    │
│                          └──────────────────┘    │
└─────────────────────────────────────────────────┘
```

---

## 四、数据模型

### 4.1 VO

```java
@Data
@Builder
public class QueueStatusVO {
    private String deptId;
    private String deptName;
    private String doctorId;
    private String doctorName;
    private String doctorTitle;
    private String floor;
    private String workTime;
    private Integer currentNo;
    private Integer waitingCount;
    private Integer avgMinutes;           // 每人平均就诊时间，默认5分钟
    private Integer estimatedWaitMinutes; // 预计等待时间
    private Integer totalCalled;
    private String lastCallTime;
    private String status;
}
```

---

## 五、Service 实现

```java
@Service
@RequiredArgsConstructor
public class QueueService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 查询叫号状态
     */
    public List<QueueStatusVO> queryQueueStatus(String department, String doctorName) {
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT d.id as dept_id, d.name as dept_name, d.floor, d.work_time,
                   doc.id as doctor_id, doc.name as doctor_name, doc.title as doctor_title,
                   qs.current_no, qs.waiting_count, qs.total_called, qs.status,
                   qs.last_call_time
            FROM t_queue_status qs
            JOIN t_department d ON qs.dept_id = d.id
            LEFT JOIN t_doctor doc ON qs.doctor_id = doc.id
            WHERE qs.queue_date = CURRENT_DATE
              AND qs.deleted = 0
              AND d.deleted = 0
            """);

        List<Object> params = new ArrayList<>();

        if (department != null && !department.isBlank()) {
            sql.append(" AND (d.name LIKE ? OR d.code LIKE ?)");
            params.add("%" + department + "%");
            params.add("%" + department.toUpperCase() + "%");
        }

        if (doctorName != null && !doctorName.isBlank()) {
            sql.append(" AND doc.name LIKE ?");
            params.add("%" + doctorName + "%");
        }

        sql.append(" ORDER BY d.sort_order");

        List<QueueStatusVO> results = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            int waiting = rs.getInt("waiting_count");
            int avg = 5; // 默认每人5分钟
            int estimated = waiting * avg;

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
                    .avgMinutes(avg)
                    .estimatedWaitMinutes(estimated)
                    .totalCalled(rs.getInt("total_called"))
                    .lastCallTime(lastCall)
                    .status(rs.getString("status"))
                    .build();
        }, params.toArray());

        return results;
    }
}
```

---

## 六、Executor 实现

```java
@Component
@RequiredArgsConstructor
public class QueueStatusMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "queue_status";
    private final QueueService queueService;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> params = new LinkedHashMap<>();

        params.put("department", MCPToolDefinition.ParameterDef.builder()
                .description("科室名称，如：内科、外科、儿科、妇科、骨科等")
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
                .description("查询校医院门诊的当前叫号状态和排队情况")
                .parameters(params)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
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

        sb.append("\n💡 数据来自校医院实时系统。");
        return sb.toString().trim();
    }

    private String formatSingle(QueueStatusVO s) {
        StringBuilder sb = new StringBuilder();
        if (s.getDoctorName() != null) {
            sb.append("👨‍⚕️ ").append(s.getDoctorName());
            if (s.getDoctorTitle() != null) sb.append("（").append(s.getDoctorTitle()).append("）");
            sb.append("\n");
        }
        if (s.getFloor() != null) {
            sb.append("📍 位置：").append(s.getFloor()).append("\n");
        }
        sb.append("📢 当前叫号：").append(s.getCurrentNo()).append("号\n");
        sb.append("⏳ 等待人数：").append(s.getWaitingCount()).append("人\n");
        if (s.getEstimatedWaitMinutes() != null && s.getEstimatedWaitMinutes() > 0) {
            sb.append("⏰ 预计等待：").append(s.getEstimatedWaitMinutes()).append("分钟\n");
        }
        if (s.getLastCallTime() != null) {
            sb.append("🕐 最后叫号：").append(s.getLastCallTime()).append("\n");
        }
        return sb.toString().trim();
    }
}
```

---

## 七、Intent Tree 配置

```java
IntentNode medical = IntentNode.builder()
        .id("medical")
        .name("校医院服务")
        .level(DOMAIN)
        .kind(IntentKind.MCP)
        .build();

IntentNode queueQuery = IntentNode.builder()
        .id("medical-queue")
        .name("叫号查询")
        .level(CATEGORY)
        .parentId(medical.getId())
        .mcpToolId("queue_status")
        .kind(IntentKind.MCP)
        .description("查询校医院门诊的当前叫号状态和排队情况")
        .examples(List.of(
                "现在内科有多少人在排队？",
                "骨科还要等多久？",
                "儿科现在人多吗？"
        ))
        .build();

medical.setChildren(List.of(queueQuery));
```

---

## 八、后续扩展

| 功能 | 说明 |
|------|------|
| 挂号功能 | 基于 `t_schedule` 和 `t_registration` 表扩展 |

---

*文档结束*
