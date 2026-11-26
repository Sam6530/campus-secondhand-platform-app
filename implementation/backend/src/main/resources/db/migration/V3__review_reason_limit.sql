-- 数据库迁移号3，仍属于产品V1；保留原V1/V2脚本及既有数据。
ALTER TABLE item_versions MODIFY COLUMN review_reason VARCHAR(1500) NULL;
ALTER TABLE audit_facts MODIFY COLUMN reason VARCHAR(1500) NULL;
