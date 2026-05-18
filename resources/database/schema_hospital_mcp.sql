-- ============================================
-- Hospital MCP Simplified Database Schema
-- For: queue_status, report_query, registration
-- Database: PostgreSQL
-- ============================================

-- ============================================
-- 1. Department Table (科室表)
-- ============================================
CREATE TABLE t_department (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    code           VARCHAR(32)  NOT NULL UNIQUE,   -- NEIKE, WAIKE...
    name           VARCHAR(64)  NOT NULL,           -- 内科, 外科...
    floor          VARCHAR(16),                     -- 1楼, 2楼...
    work_time      VARCHAR(32)  DEFAULT '08:00-17:30',
    is_emergency   SMALLINT     DEFAULT 0,
    sort_order     INTEGER       DEFAULT 0,
    deleted        SMALLINT     DEFAULT 0
);
COMMENT ON TABLE t_department IS '科室字典表';

-- ============================================
-- 2. Doctor Table (医生表)
-- ============================================
CREATE TABLE t_doctor (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(64)  NOT NULL,
    dept_id         VARCHAR(20)  NOT NULL,
    title           VARCHAR(32),                     -- 主任医师, 主治医师...
    specialty       VARCHAR(128),                    -- 专长
    status          VARCHAR(16)  DEFAULT 'active',  -- active, on_leave
    deleted         SMALLINT     DEFAULT 0,
    FOREIGN KEY (dept_id) REFERENCES t_department(id)
);
CREATE INDEX idx_doctor_dept ON t_doctor (dept_id);
COMMENT ON TABLE t_doctor IS '医生表';

-- ============================================
-- 3. Queue Status Table (叫号状态表) — 核心表
-- ============================================
CREATE TABLE t_queue_status (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    dept_id         VARCHAR(20)  NOT NULL,
    doctor_id       VARCHAR(20),                     -- 当班医生，可为空
    queue_date      DATE        NOT NULL,
    current_no      INTEGER      DEFAULT 0,          -- 当前叫到的号
    waiting_count   INTEGER      DEFAULT 0,          -- 等待人数
    total_called    INTEGER      DEFAULT 0,          -- 今日已叫号总数
    status          VARCHAR(16)  DEFAULT 'open',    -- open, paused, closed
    last_call_time  TIMESTAMP,                      -- 最后叫号时间
    deleted         SMALLINT     DEFAULT 0,
    FOREIGN KEY (dept_id) REFERENCES t_department(id),
    FOREIGN KEY (doctor_id) REFERENCES t_doctor(id),
    UNIQUE (dept_id, queue_date, deleted)
);
CREATE INDEX idx_queue_date ON t_queue_status (queue_date);
COMMENT ON TABLE t_queue_status IS '叫号状态表';

-- ============================================
-- 4. Patient Table (患者表)
-- ============================================
CREATE TABLE t_patient (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(20)  NOT NULL,           -- 关联系统用户ID
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(16),
    deleted         SMALLINT     DEFAULT 0,
    UNIQUE (user_id)
);
CREATE INDEX idx_patient_user ON t_patient (user_id);
COMMENT ON TABLE t_patient IS '患者表';

-- ============================================
-- 5. Medical Report Table (检查报告表)
-- ============================================
CREATE TABLE t_medical_report (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(20)  NOT NULL,           -- 系统用户ID（用于查询权限）
    patient_name    VARCHAR(64),                     -- 脱敏后的患者姓名
    report_no       VARCHAR(64)  NOT NULL UNIQUE,
    type            VARCHAR(32)  NOT NULL,           -- 报告类型：血常规, 尿常规, CT...
    name            VARCHAR(256),                    -- 报告名称
    category        VARCHAR(16),                     -- 检验, 检查
    status          VARCHAR(16)  DEFAULT 'pending', -- pending, processing, available
    sample_time     TIMESTAMP,                      -- 采样时间
    report_time     TIMESTAMP,                      -- 报告时间
    doctor          VARCHAR(64),                     -- 送检医生
    conclusion      TEXT,                            -- 结论
    items_json      JSONB,                           -- 检查项目JSON（避免过度拆分表）
    pdf_url         VARCHAR(512),                    -- PDF下载链接
    report_date     DATE,                            -- 报告日期
    deleted         SMALLINT     DEFAULT 0
);
CREATE INDEX idx_report_user ON t_medical_report (user_id);
CREATE INDEX idx_report_type ON t_medical_report (type);
CREATE INDEX idx_report_status ON t_medical_report (status);
CREATE INDEX idx_report_date ON t_medical_report (report_date);
COMMENT ON TABLE t_medical_report IS '检查报告表';

-- ============================================
-- 6. Doctor Schedule Table (医生排班表)
-- ============================================
CREATE TABLE t_schedule (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    doctor_id       VARCHAR(20)  NOT NULL,
    dept_id         VARCHAR(20)  NOT NULL,
    schedule_date   DATE        NOT NULL,
    shift           VARCHAR(16)  NOT NULL,           -- morning, afternoon
    quota           INTEGER       DEFAULT 50,          -- 挂号限额
    used            INTEGER       DEFAULT 0,          -- 已使用
    fee             DECIMAL(10,2) DEFAULT 10.00,    -- 挂号费
    status          VARCHAR(16)  DEFAULT 'open',    -- open, full, closed
    deleted         SMALLINT     DEFAULT 0,
    FOREIGN KEY (doctor_id) REFERENCES t_doctor(id),
    FOREIGN KEY (dept_id) REFERENCES t_department(id),
    UNIQUE (doctor_id, schedule_date, shift, deleted)
);
CREATE INDEX idx_schedule_date ON t_schedule (schedule_date);
CREATE INDEX idx_schedule_status ON t_schedule (status);
COMMENT ON TABLE t_schedule IS '医生排班表';

-- ============================================
-- 7. Registration Table (挂号记录表)
-- ============================================
CREATE TABLE t_registration (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    reg_no          VARCHAR(32)  NOT NULL UNIQUE,    -- 挂号流水号
    patient_id      VARCHAR(20)  NOT NULL,
    patient_name    VARCHAR(64),
    user_id         VARCHAR(20)  NOT NULL,
    doctor_id       VARCHAR(20)  NOT NULL,
    dept_id         VARCHAR(20)  NOT NULL,
    schedule_id     VARCHAR(20),
    doctor_name     VARCHAR(64),
    dept_name       VARCHAR(64),
    queue_no        INTEGER,                         -- 排队号
    visit_date      DATE        NOT NULL,
    shift           VARCHAR(16),                     -- morning, afternoon
    status          VARCHAR(16)  DEFAULT 'registered', -- registered, checked_in, cancelled, completed
    fee             DECIMAL(10,2),
    create_time     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0,
    FOREIGN KEY (patient_id) REFERENCES t_patient(id),
    FOREIGN KEY (doctor_id) REFERENCES t_doctor(id),
    FOREIGN KEY (dept_id) REFERENCES t_department(id)
);
CREATE INDEX idx_reg_patient ON t_registration (patient_id);
CREATE INDEX idx_reg_user ON t_registration (user_id);
CREATE INDEX idx_reg_visit ON t_registration (visit_date);
CREATE INDEX idx_reg_status ON t_registration (status);
COMMENT ON TABLE t_registration IS '挂号记录表';
