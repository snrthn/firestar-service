-- ============================================================
-- 重建集成测试库（firestar_test）—— 只用于测试，绝不碰 dev 库 firestar
-- 消费方：scripts/run-integration-tests.sh
--
-- 空库不需要任何手工建表：Spring Boot 测试启动（it profile）时 Flyway 会
-- 全量重放 V1~V108 + V200 以及 3 个测试专用补偿迁移，自建 schema + 种子。
-- ============================================================
DROP DATABASE IF EXISTS `firestar_test`;
CREATE DATABASE `firestar_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
