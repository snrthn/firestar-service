-- ============================================================
-- 重建集成测试库 —— 只用于测试，绝不碰 dev 库 firestar / 生产库
-- 消费方：scripts/run-integration-tests.sh
--
-- 空库不需要任何手工建表：Spring Boot 测试启动（it profile）时 Flyway 会
-- 全量重放 V1~V108 + V200 以及 3 个测试专用补偿迁移，自建 schema + 种子。
--
-- ⚠️ 库名由 run-integration-tests.sh 用 sed 注入下方语句里的占位符：
--    1. 脚本注入前已断言库名匹配 ^[A-Za-z0-9_]+_test$，并清除外部 DB_URL、
--       统一注入 IT_DB_URL，保证 DROP 的目标与 JDBC 连的是同一个库。
--    2. 本文件【禁止】直接手工执行 —— 占位符未替换时 MySQL 会把它当成合法
--       标识符，创建/删除一个以占位符字面量命名的无用空库。
--    3. 默认库名 firestar_test；可用 TEST_DB_NAME=xxx_test 覆盖（真开关）。
-- ============================================================
-- 【从 0 建库兼容】DROP ... IF EXISTS 在库不存在时是 no-op（不报错），
-- 随后 CREATE 直接建库 —— 因此「库不存在」与「库存在」两条路径都能走通，
-- 无需预先手工建库。字符集/排序规则与生产 deploy.sh 的幂等建库保持完全一致。
DROP DATABASE IF EXISTS `{{IT_DB_NAME}}`;
CREATE DATABASE `{{IT_DB_NAME}}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
