-- 1. 检查数据库当前日期和时区
SELECT NOW(), CURRENT_DATE, CURRENT_TIMESTAMP, version();

-- 2. 检查急诊科是否存在
SELECT id, code, name FROM t_department WHERE name LIKE '%急诊%' OR code = 'JIZHEN';

-- 3. 检查 t_queue_status 中急诊科的记录
SELECT qs.id, qs.dept_id, qs.queue_date, qs.current_no, qs.waiting_count, qs.status, qs.deleted
FROM t_queue_status qs
WHERE qs.dept_id = 'DEPT012';

-- 4. 检查所有 t_queue_status 记录
SELECT qs.id, qs.dept_id, qs.queue_date, qs.current_no, qs.status, qs.deleted
FROM t_queue_status qs
ORDER BY qs.queue_date DESC;

-- 5. 对比 CURRENT_DATE 和存储的 queue_date
SELECT qs.queue_date, CURRENT_DATE,
       qs.queue_date = CURRENT_DATE AS is_same_date,
       qs.queue_date > CURRENT_DATE AS is_future,
       qs.queue_date < CURRENT_DATE AS is_past
FROM t_queue_status qs;

-- 6. 检查 JDBC 时区
-- 如果上面显示 is_same_date = false，说明时区导致日期差了一天
