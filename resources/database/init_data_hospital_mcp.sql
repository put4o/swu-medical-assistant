-- ============================================
-- Hospital MCP Simplified Mock Data
-- ============================================

-- 强制清空表（无视外键和约束，直接删除所有行）
TRUNCATE TABLE t_registration, t_schedule, t_queue_status, t_doctor, t_patient, t_medical_report, t_department CASCADE;

-- ============================================
-- 1. 科室数据 (12个)
-- ============================================
INSERT INTO t_department (id, code, name, floor, work_time, is_emergency, sort_order, deleted) VALUES
('DEPT001', 'NEIKE', '内科', '1楼', '08:00-17:30', 0, 1, 0),
('DEPT002', 'WAIKE', '外科', '1楼', '08:00-17:30', 0, 2, 0),
('DEPT003', 'ERKE', '儿科', '2楼', '08:00-17:30', 0, 3, 0),
('DEPT004', 'FUKE', '妇科', '3楼', '08:00-17:30', 0, 4, 0),
('DEPT005', 'CHANKE', '产科', '3楼', '08:00-17:30', 0, 5, 0),
('DEPT006', 'YANKE', '眼科', '2楼', '08:00-17:30', 0, 6, 0),
('DEPT007', 'ERBI', '耳鼻喉科', '2楼', '08:00-17:30', 0, 7, 0),
('DEPT008', 'KOUQIANG', '口腔科', '2楼', '08:00-17:30', 0, 8, 0),
('DEPT009', 'PIFU', '皮肤科', '3楼', '08:00-17:30', 0, 9, 0),
('DEPT010', 'GUKE', '骨科', '1楼', '08:00-17:30', 0, 10, 0),
('DEPT011', 'ZHONGYI', '中医科', '4楼', '08:00-17:30', 0, 11, 0),
('DEPT012', 'JIZHEN', '急诊科', '1楼', '24小时', 1, 0, 0);

-- ============================================
-- 2. 医生数据 (16名)
-- ============================================
INSERT INTO t_doctor (id, code, name, dept_id, title, specialty, status, deleted) VALUES
('DOC001', 'D001', '张明华', 'DEPT001', '主任医师', '心血管', 'active', 0),
('DOC002', 'D002', '李芳', 'DEPT001', '副主任医师', '呼吸', 'active', 0),
('DOC003', 'D003', '王建国', 'DEPT002', '主任医师', '普外', 'active', 0),
('DOC004', 'D004', '赵强', 'DEPT002', '主治医师', '骨科', 'active', 0),
('DOC005', 'D005', '郑海', 'DEPT003', '主任医师', '儿内', 'active', 0),
('DOC006', 'D006', '黄娟', 'DEPT003', '副主任医师', '儿童保健', 'active', 0),
('DOC007', 'D007', '杨玲', 'DEPT004', '主任医师', '妇科', 'active', 0),
('DOC008', 'D008', '徐霞', 'DEPT004', '主治医师', '妇科内分泌', 'active', 0),
('DOC009', 'D009', '胡静', 'DEPT005', '主任医师', '产科', 'active', 0),
('DOC010', 'D010', '许医生', 'DEPT006', '主任医师', '眼底', 'active', 0),
('DOC011', 'D011', '韩梅', 'DEPT007', '主治医师', '耳鼻喉', 'active', 0),
('DOC012', 'D012', '吴医生', 'DEPT008', '主任医师', '口腔', 'active', 0),
('DOC013', 'D013', '唐医生', 'DEPT009', '主任医师', '皮肤', 'active', 0),
('DOC014', 'D014', '曾医生', 'DEPT010', '主任医师', '脊柱', 'active', 0),
('DOC015', 'D015', '程医生', 'DEPT011', '主任医师', '中医内科', 'active', 0),
('DOC016', 'D016', '钱医生', 'DEPT012', '副主任医师', '急诊', 'active', 0);

-- ============================================
-- 3. 今日叫号状态 (每个开诊科室一条记录)
-- ============================================
INSERT INTO t_queue_status (id, dept_id, doctor_id, queue_date, current_no, waiting_count, total_called, status, last_call_time, deleted) VALUES
('Q001', 'DEPT001', 'DOC001', CURRENT_DATE, 35, 8, 43, 'open', NOW() - INTERVAL '3 minute', 0),
('Q003', 'DEPT002', 'DOC003', CURRENT_DATE, 28, 6, 34, 'open', NOW() - INTERVAL '4 minute', 0),
('Q005', 'DEPT003', 'DOC005', CURRENT_DATE, 45, 12, 57, 'open', NOW() - INTERVAL '2 minute', 0),
('Q007', 'DEPT004', 'DOC007', CURRENT_DATE, 20, 5, 25, 'open', NOW() - INTERVAL '3 minute', 0),
('Q009', 'DEPT005', 'DOC009', CURRENT_DATE, 18, 4, 22, 'open', NOW() - INTERVAL '2 minute', 0),
('Q010', 'DEPT006', 'DOC010', CURRENT_DATE, 16, 3, 19, 'open', NOW() - INTERVAL '3 minute', 0),
('Q011', 'DEPT007', 'DOC011', CURRENT_DATE, 11, 2, 13, 'open', NOW() - INTERVAL '4 minute', 0),
('Q012', 'DEPT008', 'DOC012', CURRENT_DATE, 33, 9, 42, 'open', NOW() - INTERVAL '3 minute', 0),
('Q013', 'DEPT009', 'DOC013', CURRENT_DATE, 27, 7, 34, 'open', NOW() - INTERVAL '2 minute', 0),
('Q014', 'DEPT010', 'DOC014', CURRENT_DATE, 23, 6, 29, 'open', NOW() - INTERVAL '2 minute', 0),
('Q015', 'DEPT011', 'DOC015', CURRENT_DATE, 31, 8, 39, 'open', NOW() - INTERVAL '3 minute', 0),
('Q016', 'DEPT012', 'DOC016', CURRENT_DATE, 52, 15, 67, 'open', NOW() - INTERVAL '1 minute', 0);

-- ============================================
-- 4. 患者数据
-- ============================================
INSERT INTO t_patient (id, user_id, name, phone, deleted) VALUES
('PAT001', '2001523723396308993', '王小明', '13812345678', 0);

-- ============================================
-- 5. 检查报告数据 (3份典型报告)
-- ============================================
INSERT INTO t_medical_report (id, user_id, patient_name, report_no, type, name, category, status, sample_time, report_time, doctor, conclusion, items_json, pdf_url, report_date, deleted) VALUES
-- 报告1：血常规（已出，有异常）
('REP001', '2001523723396308993', '王*明', 'RPT20260415001', '血常规', '血液常规检验报告', '检验', 'available', '2026-04-15 08:30:00', '2026-04-15 10:45:00', '张明华', '部分指标轻度异常，建议定期复查。', 
'[{"name":"白细胞(WBC)","value":"11.20","unit":"×10⁹/L","ref":"3.50-9.50","flag":"H"},{"name":"红细胞(RBC)","value":"4.25","unit":"×10¹²/L","ref":"3.80-5.10","flag":"N"},{"name":"血红蛋白(HGB)","value":"142","unit":"g/L","ref":"115-150","flag":"N"},{"name":"血小板(PLT)","value":"168","unit":"×10⁹/L","ref":"125-350","flag":"N"},{"name":"淋巴细胞百分比","value":"15.3","unit":"%","ref":"20-50","flag":"L"}]', '/api/reports/pdf/REP001', '2026-04-15', 0),

-- 报告2：生化检验（已出，正常）
('REP002', '2001523723396308993', '王*明', 'RPT20260410002', '生化', '生化检验报告', '检验', 'available', '2026-04-10 08:15:00', '2026-04-10 11:20:00', '张明华', '检验结果在正常范围内。', 
'[{"name":"谷丙转氨酶(ALT)","value":"32.5","unit":"U/L","ref":"9-50","flag":"N"},{"name":"谷草转氨酶(AST)","value":"28.0","unit":"U/L","ref":"15-40","flag":"N"},{"name":"空腹血糖(GLU)","value":"5.20","unit":"mmol/L","ref":"3.90-6.10","flag":"N"},{"name":"肌酐(CRE)","value":"68.5","unit":"μmol/L","ref":"41-73","flag":"N"}]', '/api/reports/pdf/REP002', '2026-04-10', 0),

-- 报告3：CT（处理中）
('REP003', '2001523723396308993', '王*明', 'RPT20260418003', 'CT', '胸部CT检查报告', '检查', 'processing', '2026-04-18 10:00:00', NULL, '许医生', NULL, NULL, NULL, '2026-04-18', 0);

-- ============================================
-- 6. 医生排班数据
-- ============================================
INSERT INTO t_schedule (id, doctor_id, dept_id, schedule_date, shift, quota, used, fee, status, deleted) VALUES
('SCH001', 'DOC001', 'DEPT001', CURRENT_DATE + 1, 'morning', 50, 12, 10.00, 'open', 0),
('SCH002', 'DOC001', 'DEPT001', CURRENT_DATE + 1, 'afternoon', 50, 8, 10.00, 'open', 0),
('SCH003', 'DOC002', 'DEPT001', CURRENT_DATE + 1, 'morning', 50, 20, 10.00, 'open', 0),
('SCH004', 'DOC003', 'DEPT002', CURRENT_DATE + 1, 'morning', 40, 15, 10.00, 'open', 0),
('SCH005', 'DOC005', 'DEPT003', CURRENT_DATE + 1, 'morning', 60, 30, 10.00, 'open', 0),
('SCH006', 'DOC007', 'DEPT004', CURRENT_DATE + 1, 'morning', 40, 18, 10.00, 'open', 0);

-- ============================================
-- 7. 挂号记录
-- ============================================
INSERT INTO t_registration (id, reg_no, patient_id, patient_name, user_id, doctor_id, dept_id, doctor_name, dept_name, visit_date, shift, status, fee, create_time, deleted) VALUES
('REG001', 'REG20260420001', 'PAT001', '王小明', '2001523723396308993', 'DOC001', 'DEPT001', '张明华', '内科', CURRENT_DATE, 'morning', 'registered', 10.00, CURRENT_TIMESTAMP, 0),
('REG002', 'REG20260420002', 'PAT001', '王小明', '2001523723396308993', 'DOC005', 'DEPT003', '郑海', '儿科', CURRENT_DATE, 'morning', 'checked_in', 10.00, CURRENT_TIMESTAMP, 0);
