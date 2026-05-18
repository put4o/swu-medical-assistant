-- 查看 t_patient 表上所有约束的名称
SELECT conname, contype, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 't_patient'::regclass;

-- 查看 t_patient 表结构
\d t_patient
