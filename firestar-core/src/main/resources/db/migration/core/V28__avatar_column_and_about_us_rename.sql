-- V28 (P1 收尾): 用户头像字段 + 关于我们富文本扩容（纯 schema 迁移）
-- 键名重命名 open_source_notice -> about_us 已拆到 V29（数据迁移）。
-- 全部幂等，可重复执行；用 procedure + information_schema 守卫，避免 ALTER/MODIFY 重复报错。
-- 执行方式：mysql source <本文件>（不要走 PowerShell 管道，DELIMITER 与中文会出问题）。

DROP PROCEDURE IF EXISTS v28_p1_avatar_and_about;
DELIMITER $$
CREATE PROCEDURE v28_p1_avatar_and_about()
BEGIN
    -- 1) sys_user 增加头像文件ID列（存 sys_file_storage.id，前端走 /files/{id}/raw 预览；为空则昵称首字兜底）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_user' AND column_name = 'avatar_url'
    ) THEN
        ALTER TABLE sys_user
            ADD COLUMN avatar_url VARCHAR(255) NULL
            COMMENT '头像文件ID（sys_file_storage.id），为空则昵称首字兜底'
            AFTER email;
    END IF;

    -- 2) sys_config.config_value 扩容为 mediumtext（关于我们/隐私等富文本场景需要长文本；仅当还不是）
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'firestar' AND table_name = 'sys_config'
          AND column_name = 'config_value' AND column_type <> 'mediumtext'
    ) THEN
        ALTER TABLE sys_config
            MODIFY COLUMN config_value MEDIUMTEXT NULL
            COMMENT '参数值（关于我们/隐私等富文本场景需要长文本）';
    END IF;
END$$
DELIMITER ;

CALL v28_p1_avatar_and_about();
DROP PROCEDURE IF EXISTS v28_p1_avatar_and_about;
