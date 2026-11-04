-- V29 (P1 收尾): 键名 open_source_notice -> about_us
-- 背景：V28 先被手动 source 时尚未含此改名步骤，后续文件补了第 3 步但 Flyway 已记账不会重跑；
--       故此处单独增量补齐，保持 Flyway 权威、可重复执行。
-- 执行方式：mysql source <本文件>（不要走 PowerShell 管道，DELIMITER 与中文会出问题）。

DROP PROCEDURE IF EXISTS v29_rename_about;
DELIMITER $$
CREATE PROCEDURE v29_rename_about()
BEGIN
    -- 仅当 open_source_notice 存在 且 about_us 尚不存在时执行（幂等，可重复跑）
    -- 注意：sys_config 无 config_name 列，改名只需改 config_key + remark。
    IF EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'open_source_notice')
       AND NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'about_us') THEN
        UPDATE sys_config
        SET config_key = 'about_us',
            remark    = '关于我们（富文本）'
        WHERE config_key = 'open_source_notice';
    END IF;
END$$
DELIMITER ;

CALL v29_rename_about();
DROP PROCEDURE IF EXISTS v29_rename_about;
