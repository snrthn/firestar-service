-- 开发期 Flyway 历史重置（仅 dev/test；prod 严禁）
-- 用途：改动了 V1~V108 迁移内容、或想让某库从零重新全量应用迁移时使用。
-- 本项目 spring.flyway.baseline-on-migrate=false，残留的 flyway_schema_history
-- 会让 Flyway 拒绝与历史不一致的迁移；清空历史后即可重新全量应用。
-- 用法：mysql -u<user> -p<pass> <db> < scripts/reset_flyway.sql

TRUNCATE TABLE flyway_schema_history;

-- 如需连业务数据一起清，按需在此追加业务表 TRUNCATE / DELETE。
-- 例：TRUNCATE TABLE sys_user; TRUNCATE TABLE project; ...（谨慎，仅开发库）
