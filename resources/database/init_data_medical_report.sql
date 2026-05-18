-- ============================================
-- Medical Report Test Data
-- For: report_query MCP tool testing
-- Database: PostgreSQL
-- ============================================

-- Clean existing test data
DELETE FROM t_medical_report WHERE user_id IN ('test_user_001', 'test_user_002');

-- Insert test patient records
INSERT INTO t_patient (id, user_id, name, phone, deleted) VALUES
    ('PAT001', 'test_user_001', '张三', '13800138001', 0),
    ('PAT002', 'test_user_002', '李四', '13800138002', 0)
ON CONFLICT (user_id) DO UPDATE SET name = EXCLUDED.name;

-- ============================================
-- Test Report 1: 血常规 (Available)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT001',
    'test_user_001',
    '张*',
    'CBC20260415001',
    '血常规',
    '血液细胞分析检验报告',
    '检验',
    'available',
    '2026-04-15 08:30:00',
    '2026-04-15 11:45:00',
    '王医生',
    '白细胞计数略偏高，建议复查。',
    '[
        {"name": "白细胞(WBC)", "value": "11.20", "unit": "×10⁹/L", "ref": "3.50-9.50", "flag": "H"},
        {"name": "红细胞(RBC)", "value": "4.25", "unit": "×10¹²/L", "ref": "3.80-5.10", "flag": "N"},
        {"name": "血红蛋白(HGB)", "value": "138", "unit": "g/L", "ref": "115-150", "flag": "N"},
        {"name": "血小板(PLT)", "value": "210", "unit": "×10⁹/L", "ref": "125-350", "flag": "N"},
        {"name": "中性粒细胞比率(NEUT%)", "value": "68.5", "unit": "%", "ref": "40-75", "flag": "N"},
        {"name": "淋巴细胞比率(LYMPH%)", "value": "25.3", "unit": "%", "ref": "20-50", "flag": "N"}
    ]'::jsonb,
    '/reports/cbc20260415001.pdf',
    '2026-04-15',
    0
);

-- ============================================
-- Test Report 2: 尿常规 (Available)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT002',
    'test_user_001',
    '张*',
    'URIN20260412002',
    '尿常规',
    '尿液分析检验报告',
    '检验',
    'available',
    '2026-04-12 09:00:00',
    '2026-04-12 14:30:00',
    '李医生',
    '各项指标正常。',
    '[
        {"name": "尿白细胞(LEU)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿蛋白(PRO)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿葡萄糖(GLU)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿酮体(KET)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿潜血(BLD)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿胆红素(BIL)", "value": "阴性(-)", "unit": "", "ref": "阴性(-)", "flag": "N"},
        {"name": "尿胆原(URO)", "value": "弱阳性(±)", "unit": "", "ref": "阴性(-)", "flag": "N"}
    ]'::jsonb,
    '/reports/urin20260412002.pdf',
    '2026-04-12',
    0
);

-- ============================================
-- Test Report 3: 生化检验 (Available with abnormalities)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT003',
    'test_user_001',
    '张*',
    'BIO20260410003',
    '生化',
    '肝肾功能生化检验报告',
    '检验',
    'available',
    '2026-04-10 07:45:00',
    '2026-04-10 15:20:00',
    '陈医生',
    '肝功能轻度异常，尿酸偏高。建议低嘌呤饮食，一周后复查。',
    '[
        {"name": "谷丙转氨酶(ALT)", "value": "58", "unit": "U/L", "ref": "9-50", "flag": "H"},
        {"name": "谷草转氨酶(AST)", "value": "42", "unit": "U/L", "ref": "15-40", "flag": "H"},
        {"name": "γ-谷氨酰转肽酶(GGT)", "value": "65", "unit": "U/L", "ref": "10-60", "flag": "H"},
        {"name": "总胆红素(TBIL)", "value": "14.2", "unit": "μmol/L", "ref": "5.1-19.0", "flag": "N"},
        {"name": "肌酐(Cr)", "value": "78", "unit": "μmol/L", "ref": "44-97", "flag": "N"},
        {"name": "尿素氮(BUN)", "value": "5.8", "unit": "mmol/L", "ref": "2.6-7.5", "flag": "N"},
        {"name": "尿酸(UA)", "value": "468", "unit": "μmol/L", "ref": "208-428", "flag": "H"},
        {"name": "空腹血糖(GLU)", "value": "5.3", "unit": "mmol/L", "ref": "3.9-6.1", "flag": "N"}
    ]'::jsonb,
    '/reports/bio20260410003.pdf',
    '2026-04-10',
    0
);

-- ============================================
-- Test Report 4: CT检查 (Processing)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT004',
    'test_user_001',
    '张*',
    'CT20260418004',
    'CT',
    '胸部CT平扫检查报告',
    '检查',
    'processing',
    '2026-04-18 10:00:00',
    NULL,
    '赵医生',
    NULL,
    NULL,
    NULL,
    '2026-04-18',
    0
);

-- ============================================
-- Test Report 5: 核磁共振 (Pending)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT005',
    'test_user_001',
    '张*',
    'MRI20260420005',
    '核磁',
    '头部核磁共振(MRI)检查报告',
    '检查',
    'pending',
    '2026-04-20 14:30:00',
    NULL,
    '孙医生',
    NULL,
    NULL,
    NULL,
    '2026-04-20',
    0
);

-- ============================================
-- Test Report 6: 心电图 (Available - Another user)
-- ============================================
INSERT INTO t_medical_report (
    id, user_id, patient_name, report_no, type, name, category,
    status, sample_time, report_time, doctor, conclusion,
    items_json, pdf_url, report_date, deleted
) VALUES (
    'RPT006',
    'test_user_002',
    '李*',
    'ECG20260416006',
    '心电图',
    '常规十二导联心电图检查报告',
    '检查',
    'available',
    '2026-04-16 15:00:00',
    '2026-04-16 15:30:00',
    '周医生',
    '窦性心律，心电图大致正常。',
    '[
        {"name": "心率(HR)", "value": "72", "unit": "bpm", "ref": "60-100", "flag": "N"},
        {"name": "PR间期", "value": "152", "unit": "ms", "ref": "120-200", "flag": "N"},
        {"name": "QRS时限", "value": "88", "unit": "ms", "ref": "80-120", "flag": "N"},
        {"name": "QT间期", "value": "380", "unit": "ms", "ref": "320-440", "flag": "N"},
        {"name": "心电轴", "value": "正常", "unit": "", "ref": "正常", "flag": "N"}
    ]'::jsonb,
    '/reports/ecg20260416006.pdf',
    '2026-04-16',
    0
);

-- ============================================
-- Verify inserted data
-- ============================================
SELECT id, report_no, type, name, status, patient_name
FROM t_medical_report
WHERE user_id IN ('test_user_001', 'test_user_002')
ORDER BY sample_time DESC;
