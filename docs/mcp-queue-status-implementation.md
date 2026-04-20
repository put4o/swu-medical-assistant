# 叫号查询 MCP 工具实现方案

> 文档版本：v1.0
> 创建日期：2026-04-20
> 工具 ID：`queue_status`
> 数据策略：完全使用模拟数据

---

## 一、需求概述

### 1.1 功能定义

| 属性 | 说明 |
|------|------|
| **工具名称** | 叫号查询 |
| **工具 ID** | `queue_status` |
| **工具类型** | MCP (Model Context Protocol) |
| **功能描述** | 查询校医院门诊/急诊的当前叫号状态和排队情况 |

### 1.2 输入输出规格

**输入参数：**

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `department` | string | 否 | 全部科室 | 科室名称，如：内科、外科、儿科、眼科、口腔科、皮肤科、骨科、妇产科、儿科、中医科 |
| `roomNo` | string | 否 | - | 诊室编号，如：A101、B205 |
| `doctorName` | string | 否 | - | 医生姓名（模糊匹配） |

**输出内容：**

| 字段 | 说明 |
|------|------|
| 当前叫号 | 当前正在就诊的号码 |
| 等待人数 | 当前排队等待的人数 |
| 预计等待时间 | 根据等待人数估算的等待时长 |
| 科室/诊室信息 | 查询的具体科室或诊室 |
| 最后更新时间 | 数据刷新时间 |

---

## 二、技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MCP Client (Bootstrap)                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              RetrievalEngine → IntentResolver                │   │
│  │                     ↓                                         │   │
│  │         MCP Tool Registry (RemoteMCPToolExecutor)            │   │
│  └─────────────────────────────┬────────────────────────────────┘   │
└────────────────────────────────┼────────────────────────────────────┘
                                 │ HTTP JSON-RPC
                                 │ POST /mcp
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         MCP Server (mcp-server)                      │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────┐ │
│  │  MCPEndpoint   │→ │MCPToolDispatcher│→ │ QueueStatusMCPExecutor │ │
│  │  POST /mcp     │  │  (路由分发)     │  │    @Component          │ │
│  └────────────────┘  └────────────────┘  └───────────┬────────────┘ │
│                                                        │              │
│                                                        ▼              │
│                                              ┌────────────────────┐   │
│                                              │   QueueService     │   │
│                                              │ (模拟数据生成)     │   │
│                                              └────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 文件清单

| 文件路径 | 操作 | 说明 |
|----------|------|------|
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/QueueStatusMCPExecutor.java` | 新增 | 叫号查询执行器 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/QueueService.java` | 新增 | 叫号数据服务（模拟数据） |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/model/QueueStatus.java` | 新增 | 叫号状态数据模型 |
| `mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/model/DepartmentInfo.java` | 新增 | 科室信息数据模型 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeFactory.java` | 修改 | 添加医疗意图节点 |
| `bootstrap/src/main/resources/prompt/mcp-queue-status-prompt.st` | 新增 | 叫号查询结果格式化模板 |

---

## 三、数据模型设计

### 3.1 叫号状态数据 (QueueStatus)

```java
@Data
@Builder
public class QueueStatus {
    /** 科室名称 */
    private String department;
    
    /** 诊室编号 */
    private String roomNo;
    
    /** 当前叫号 */
    private Integer currentNumber;
    
    /** 等待人数 */
    private Integer waitingCount;
    
    /** 每人平均就诊时长（分钟） */
    private Integer avgMinutesPerPerson;
    
    /** 预计等待时间（分钟） */
    private Integer estimatedWaitMinutes;
    
    /** 今日开始时间 */
    private String startTime;
    
    /** 最后叫号时间 */
    private String lastCallTime;
    
    /** 数据更新时间 */
    private String updateTime;
}
```

### 3.2 科室信息 (DepartmentInfo)

```java
@Data
@Builder
public class DepartmentInfo {
    /** 科室编码 */
    private String code;
    
    /** 科室名称 */
    private String name;
    
    /** 诊室数量 */
    private Integer roomCount;
    
    /** 楼层位置 */
    private String floor;
    
    /** 今日是否开诊 */
    private Boolean isOpen;
    
    /** 上班时间 */
    private String workTime;
}
```

### 3.3 模拟科室数据

```java
private static final List<DepartmentInfo> DEPARTMENTS = List.of(
    DepartmentInfo.builder().code("NEIKE").name("内科").roomCount(5).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("WAIKE").name("外科").roomCount(4).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("ERKE").name("儿科").roomCount(3).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("FUKE").name("妇科").roomCount(3).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("CHANKE").name("产科").roomCount(2).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("YANKE").name("眼科").roomCount(2).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("ERBIHOUKE").name("耳鼻喉科").roomCount(2).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("KOUQIANGKE").name("口腔科").roomCount(3).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("PIFUKE").name("皮肤科").roomCount(2).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("GUKE").name("骨科").roomCount(3).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("ZHONGYIKE").name("中医科").roomCount(2).floor("4楼").isOpen(true).workTime("08:00-17:30").build(),
    DepartmentInfo.builder().code("JIZHENKE").name("急诊科").roomCount(4).floor("1楼").isOpen(true).workTime("24小时").build()
);
```

---

## 四、Executor 实现

### 4.1 QueueStatusMCPExecutor.java

文件路径：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/executor/QueueStatusMCPExecutor.java`

```java
package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import com.nageoffer.ai.ragent.mcp.model.QueueStatus;
import com.nageoffer.ai.ragent.mcp.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueStatusMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "queue_status";
    
    private final QueueService queueService;

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("department", MCPToolDefinition.ParameterDef.builder()
                .description("科室名称，如：内科、外科、儿科、妇科、骨科、眼科、口腔科、皮肤科、中医科等")
                .type("string")
                .required(false)
                .build());

        parameters.put("roomNo", MCPToolDefinition.ParameterDef.builder()
                .description("诊室编号，如：A101、B205")
                .type("string")
                .required(false)
                .build());

        parameters.put("doctorName", MCPToolDefinition.ParameterDef.builder()
                .description("医生姓名（支持模糊匹配）")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询校医院门诊的当前叫号状态和排队情况，支持按科室、诊室、医生姓名查询")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            String department = request.getStringParameter("department");
            String roomNo = request.getStringParameter("roomNo");
            String doctorName = request.getStringParameter("doctorName");

            // 查询叫号数据
            List<QueueStatus> statuses = queueService.queryQueueStatus(department, roomNo, doctorName);

            if (statuses.isEmpty()) {
                return MCPToolResponse.success(TOOL_ID, 
                    "未查询到相关叫号信息，可能该科室今日未开诊或已下班。");
            }

            // 构建响应文本
            String textResult = buildResultText(statuses, department, roomNo, doctorName);
            
            // 构建结构化数据
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCount", statuses.size());
            data.put("queueList", statuses.stream()
                    .map(queueService::toMap)
                    .toList());

            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(TOOL_ID)
                    .textResult(textResult)
                    .data(data)
                    .costMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("叫号查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "QUERY_ERROR", 
                    "查询叫号信息失败: " + e.getMessage());
        }
    }

    private String buildResultText(List<QueueStatus> statuses, String department, 
                                   String roomNo, String doctorName) {
        StringBuilder sb = new StringBuilder();
        sb.append("【校医院门诊叫号查询结果】\n\n");

        if (statuses.size() == 1) {
            // 单个结果
            QueueStatus status = statuses.get(0);
            sb.append(formatSingleQueue(status));
        } else {
            // 多个结果
            sb.append(String.format("共查询到 %d 条叫号信息：\n\n", statuses.size()));
            for (int i = 0; i < statuses.size(); i++) {
                QueueStatus status = statuses.get(i);
                sb.append(String.format("▶ %s %s\n", 
                        status.getDepartment(), 
                        status.getRoomNo() != null ? status.getRoomNo() : ""));
                sb.append(formatSingleQueue(status));
                if (i < statuses.size() - 1) {
                    sb.append("\n────────────────────\n\n");
                }
            }
        }

        sb.append("\n💡 提示：以上为模拟数据，实际数据需对接医院叫号系统。");
        return sb.toString().trim();
    }

    private String formatSingleQueue(QueueStatus status) {
        StringBuilder sb = new StringBuilder();
        
        if (status.getDepartment() != null) {
            sb.append("📍 科室：").append(status.getDepartment()).append("\n");
        }
        if (status.getRoomNo() != null) {
            sb.append("🚪 诊室：").append(status.getRoomNo()).append("\n");
        }
        
        sb.append("📢 当前叫号：").append(status.getCurrentNumber()).append("号\n");
        sb.append("⏳ 等待人数：").append(status.getWaitingCount()).append("人\n");
        
        if (status.getEstimatedWaitMinutes() != null && status.getEstimatedWaitMinutes() > 0) {
            sb.append("⏰ 预计等待：").append(status.getEstimatedWaitMinutes()).append("分钟\n");
        }
        
        if (status.getLastCallTime() != null) {
            sb.append("🕐 最后叫号：").append(status.getLastCallTime()).append("\n");
        }
        
        if (status.getWaitingCount() != null && status.getWaitingCount() == 0) {
            sb.append("\n✨ 当前无需等待，可以直接就诊。");
        } else if (status.getWaitingCount() != null && status.getWaitingCount() <= 3) {
            sb.append("\n👍 等待人数较少，建议尽快前往。");
        } else if (status.getWaitingCount() != null && status.getWaitingCount() >= 10) {
            sb.append("\n⚠️ 等待人数较多，建议提前预约或稍后再来。");
        }
        
        return sb.toString().trim();
    }
}
```

---

## 五、Service 实现

### 5.1 QueueService.java

文件路径：`mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/service/QueueService.java`

```java
package com.nageoffer.ai.ragent.mcp.service;

import com.nageoffer.ai.ragent.mcp.model.DepartmentInfo;
import com.nageoffer.ai.ragent.mcp.model.QueueStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueueService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    
    // 模拟科室数据
    private static final List<DepartmentInfo> DEPARTMENTS = List.of(
            DepartmentInfo.builder().code("NEIKE").name("内科").roomCount(5).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("WAIKE").name("外科").roomCount(4).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("ERKE").name("儿科").roomCount(3).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("FUKE").name("妇科").roomCount(3).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("CHANKE").name("产科").roomCount(2).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("YANKE").name("眼科").roomCount(2).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("ERBIHOUKE").name("耳鼻喉科").roomCount(2).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("KOUQIANGKE").name("口腔科").roomCount(3).floor("2楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("PIFUK").name("皮肤科").roomCount(2).floor("3楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("GUKE").name("骨科").roomCount(3).floor("1楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("ZHONGYIKE").name("中医科").roomCount(2).floor("4楼").isOpen(true).workTime("08:00-17:30").build(),
            DepartmentInfo.builder().code("JIZHENKE").name("急诊科").roomCount(4).floor("1楼").isOpen(true).workTime("24小时").build()
    );

    // 诊室数据缓存（按天刷新）
    private final Map<String, QueueStatus> roomStatusCache = new ConcurrentHashMap<>();
    private String cacheDate = "";

    /**
     * 查询叫号状态
     */
    public List<QueueStatus> queryQueueStatus(String department, String roomNo, String doctorName) {
        List<QueueStatus> statuses = new ArrayList<>();

        // 初始化今日数据
        initializeTodayDataIfNeeded();

        // 根据条件过滤
        Collection<QueueStatus> allStatuses = roomStatusCache.values();
        
        if (roomNo != null && !roomNo.isBlank()) {
            // 按诊室查询
            statuses.addAll(allStatuses.stream()
                    .filter(s -> s.getRoomNo() != null && s.getRoomNo().contains(roomNo))
                    .toList());
        } else if (department != null && !department.isBlank()) {
            // 按科室查询
            statuses.addAll(allStatuses.stream()
                    .filter(s -> s.getDepartment() != null && s.getDepartment().contains(department))
                    .toList());
        } else if (doctorName != null && !doctorName.isBlank()) {
            // 按医生查询（模拟）
            statuses.addAll(allStatuses.stream()
                    .filter(s -> s.getDepartment() != null)
                    .toList());
        } else {
            // 返回全部
            statuses.addAll(allStatuses);
        }

        return statuses.stream()
                .sorted(Comparator.comparing(QueueStatus::getDepartment))
                .toList();
    }

    /**
     * 初始化今日模拟数据
     */
    private synchronized void initializeTodayDataIfNeeded() {
        String today = LocalDate.now().toString();
        if (today.equals(cacheDate)) {
            return;
        }

        roomStatusCache.clear();
        Random random = new Random(LocalDate.now().toEpochDay());

        for (DepartmentInfo dept : DEPARTMENTS) {
            if (!dept.getIsOpen()) continue;

            // 为每个诊室生成数据
            for (int room = 1; room <= dept.getRoomCount(); room++) {
                String roomNo = generateRoomNo(dept.getFloor(), room);
                QueueStatus status = generateQueueStatus(dept, roomNo, random);
                roomStatusCache.put(roomNo, status);
            }
        }

        cacheDate = today;
        log.info("叫号模拟数据初始化完成，共 {} 条记录", roomStatusCache.size());
    }

    /**
     * 生成诊室编号
     */
    private String generateRoomNo(String floor, int roomIndex) {
        char floorCode = switch (floor) {
            case "1楼" -> 'A';
            case "2楼" -> 'B';
            case "3楼" -> 'C';
            case "4楼" -> 'D';
            default -> 'X';
        };
        return String.format("%c%03d", floorCode, roomIndex);
    }

    /**
     * 生成单条叫号状态
     */
    private QueueStatus generateQueueStatus(DepartmentInfo dept, String roomNo, Random random) {
        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime();
        LocalTime startTime = LocalTime.of(8, 0);
        LocalTime endTime = LocalTime.of(17, 30);

        // 判断是否在工作时间
        boolean isWorkingHours = !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime);

        if (!isWorkingHours) {
            return QueueStatus.builder()
                    .department(dept.getName())
                    .roomNo(roomNo)
                    .currentNumber(0)
                    .waitingCount(0)
                    .startTime(dept.getWorkTime())
                    .updateTime(now.format(TIME_FORMATTER))
                    .build();
        }

        // 计算当前叫号（基于时间的确定性随机）
        long daySeed = LocalDate.now().toEpochDay();
        Random dayRandom = new Random(daySeed + roomNo.hashCode());
        
        int baseNumber = 20 + dayRandom.nextInt(30);
        int hoursSinceStart = (currentTime.getHour() - 8);
        int currentNumber = Math.min(100, baseNumber + hoursSinceStart * 5 + random.nextInt(10));

        // 等待人数（0-20）
        int waitingCount = random.nextInt(21);

        // 每人就诊时间（3-8分钟）
        int avgMinutes = 3 + random.nextInt(6);
        int estimatedWait = waitingCount * avgMinutes;

        // 最后叫号时间
        String lastCallTime = currentTime.minusMinutes(random.nextInt(5))
                .format(TIME_FORMATTER);

        return QueueStatus.builder()
                .department(dept.getName())
                .roomNo(roomNo)
                .currentNumber(currentNumber)
                .waitingCount(waitingCount)
                .avgMinutesPerPerson(avgMinutes)
                .estimatedWaitMinutes(estimatedWait)
                .startTime(startTime.format(TIME_FORMATTER))
                .lastCallTime(lastCallTime)
                .updateTime(now.format(TIME_FORMATTER))
                .build();
    }

    /**
     * 转换为 Map（用于结构化输出）
     */
    public Map<String, Object> toMap(QueueStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("department", status.getDepartment());
        if (status.getRoomNo() != null) map.put("roomNo", status.getRoomNo());
        map.put("currentNumber", status.getCurrentNumber());
        map.put("waitingCount", status.getWaitingCount());
        if (status.getEstimatedWaitMinutes() != null) {
            map.put("estimatedWaitMinutes", status.getEstimatedWaitMinutes());
        }
        if (status.getLastCallTime() != null) map.put("lastCallTime", status.getLastCallTime());
        map.put("updateTime", status.getUpdateTime());
        return map;
    }

    /**
     * 获取所有科室列表
     */
    public List<DepartmentInfo> getAllDepartments() {
        return DEPARTMENTS;
    }
}
```

---

## 六、Intent Tree 配置

### 6.1 修改 IntentTreeFactory.java

在 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentTreeFactory.java` 中添加医疗意图节点。

**修改位置**：在 `buildIntentTree()` 方法末尾添加

```java
// ========== 5. 校医院服务（叫号查询 & 报告查询）========== //

IntentNode medical = IntentNode.builder()
        .id("medical")
        .name("校医院服务")
        .level(DOMAIN)
        .kind(IntentKind.MCP)
        .description("校医院相关的服务查询，包括叫号状态、检查报告等")
        .build();

// 叫号查询
IntentNode queueQuery = IntentNode.builder()
        .id("medical-queue")
        .name("叫号查询")
        .level(CATEGORY)
        .parentId(medical.getId())
        .mcpToolId("queue_status")
        .kind(IntentKind.MCP)
        .promptTemplate(MCP_QUEUE_STATUS_PROMPT_TEMPLATE)
        .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
        .description("查询校医院门诊的当前叫号状态和排队情况")
        .examples(List.of(
                "现在内科有多少人在排队？",
                "A101诊室叫到几号了？",
                "骨科还要等多久？",
                "儿科现在人多吗？",
                "今天的妇科号排到多少了？",
                "眼科叫号情况怎么样？"
        ))
        .topK(1)
        .build();

medical.setChildren(List.of(queueQuery));
roots.add(medical);
```

**新增常量**：在文件末尾添加

```java
// ===================== 校医院 MCP 提示词模板 =====================

public static final String MCP_QUEUE_STATUS_PARAMETER_EXTRACT_PROMPT = """
        Hello，你是一个高度专业且严谨的【工具参数提取器】。
        
        你的唯一任务是：严格按照提供的【工具定义】（Tool Definition）和【参数列表】（Parameters）的约束，从【用户问题】（User Query）中提取所有必要的参数，并以 JSON 格式输出。
        
        ### 核心提取逻辑
        1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
        2. **参数范围限定**：只提取 <parameters> 标签内定义的参数，**禁止**添加任何工具定义中不存在的额外字段。
        3. **必填参数处理**：如果参数是 **"required": false** 且在用户问题中无法找到明确值，使用默认值或忽略该参数。
        4. **枚举值映射**：将用户口语化表达映射到支持的枚举值（如"内科"→"内科"）。
        
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

private static final String MCP_QUEUE_STATUS_PROMPT_TEMPLATE = """
        Hello，你是专业的校医院智能助手。系统已调用内部工具获取到了最新的【叫号数据】。
        你的任务是将这些数据转化为**易读、自然**的回复。
        
        【核心处理规则】
        1. **直接回答**：开门见山地回答用户问题。
        2. **重点突出**：
           - 当前叫号号码（醒目展示）
           - 等待人数
           - 预计等待时间
        3. **实用建议**：根据等待情况给出合理建议（人少建议尽快来、人多建议提前预约等）。
        4. **格式要求**：
           - 使用 emoji 增强可读性
           - 关键数字加粗处理
        
        【异常与边界处理】
        1. **数据为空**：如果【叫号数据】为空，回答"未查询到相关叫号信息，可能该科室今日未开诊或已下班"。
        2. **非工作时间**：如果查询时间在工作时间外，提醒用户医院服务时间。
        
        【禁止事项】
        - 严禁虚构叫号信息。
        - 严禁透露数据来源（模拟数据）。
        
        【叫号数据】
        %s
        
        【用户问题】
        %s
        """;
```

---

## 七、测试用例设计

### 7.1 单元测试用例

| 用例编号 | 输入 | 预期输出 |
|----------|------|----------|
| TC-001 | `department=内科` | 返回内科所有诊室的叫号信息 |
| TC-002 | `roomNo=A101` | 返回A101诊室的叫号信息 |
| TC-003 | `department=儿科, roomNo=B101` | 返回B101诊室（儿科）的叫号信息 |
| TC-004 | `department=不存在的科室` | 返回空或提示无数据 |
| TC-005 | 无参数 | 返回所有诊室的叫号信息（限制10条） |

### 7.2 端到端测试场景

**场景1：用户询问科室等待情况**
```
用户：现在内科有多少人在排队？
期望：返回内科各诊室的叫号状态，包含等待人数和预计等待时间
```

**场景2：用户查询特定诊室**
```
用户：A101现在叫到几号了？
期望：返回A101诊室的具体叫号信息
```

**场景3：非工作时间查询**
```
用户：晚上8点查询骨科叫号
期望：提示当前非工作时间，显示服务时间
```

---

## 八、实现步骤清单

| 步骤 | 任务 | 文件/位置 | 预计工时 |
|------|------|-----------|----------|
| 1 | 创建数据模型 `QueueStatus.java` | `mcp-server/.../model/` | 0.5h |
| 2 | 创建数据模型 `DepartmentInfo.java` | `mcp-server/.../model/` | 0.5h |
| 3 | 创建服务类 `QueueService.java` | `mcp-server/.../service/` | 2h |
| 4 | 创建执行器 `QueueStatusMCPExecutor.java` | `mcp-server/.../executor/` | 2h |
| 5 | 配置 IntentTree | `bootstrap/.../IntentTreeFactory.java` | 1h |
| 6 | 添加 Prompt 模板 | `bootstrap/.../prompt/` | 1h |
| 7 | 单元测试 | `mcp-server/src/test/` | 1h |
| 8 | 集成测试 | - | 1h |
| **总计** | | | **9h** |

---

## 九、后续扩展建议

### 9.1 对接真实系统

当校医院具备以下条件时，可升级为真实数据：

1. **叫号系统 API**：获取实时叫号数据
2. **科室信息系统**：获取准确的科室、诊室、医生排班数据
3. **预约系统集成**：获取预约信息用于更准确的等待时间估算

### 9.2 功能增强

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 预约提醒 | 等待人数减少时推送提醒 | P2 |
| 历史查询 | 查询历史某天的叫号情况 | P3 |
| 多院区支持 | 支持查询不同院区 | P3 |

---

## 十、免责声明

当前实现使用**模拟数据**，实际使用时需注意：

1. 所有叫号数据均为随机生成，不反映真实情况
2. 等待时间估算基于平均3-8分钟/人的假设
3. 接入真实系统前，请勿用于实际就医决策

---

*文档结束*
