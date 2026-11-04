-- ============================================================================
-- V24：文件类型字典补音频/视频（mp3/mp4 上传，2026-10-16）
--
-- 背景：文件管理模块新增 mp3/mp4 格式上传（FileServiceImpl.ALLOWED_EXT 同步放开），
--      前端上传弹窗的「文件类型」下拉与列表类型列走 file_type 字典，
--      按「字典跟着真实业务数据走」的原则，此处补两个字典项。
--
-- 对账依据（真实库实测，勿拍脑袋）：
--   file_type 现有 4 项：BANK_RECEIPT(1) / INVOICE(2) / ATTACHMENT(3) / TEMP(4)；
--   本次追加 AUDIO(5) / VIDEO(6)。
--   迁移后 sys_dict_type=28（不变，未新增类型）、sys_dict_data=104→106（+2）。
--   ⚠️ DatabaseCheckService.readSeedData 的 dictDataCount 必须同步改为 106，
--      否则 /actuator/health 报 DOWN → 503（V19/V20/V22 三次同款事故）。
--
-- 幂等性：sys_dict_data 唯一键 (tenant_id, type_code, dict_value)，
--      ON DUPLICATE KEY UPDATE 保证重复执行安全。
-- ============================================================================

INSERT INTO `sys_dict_data` (`type_code`, `dict_label`, `dict_value`, `sort_no`, `is_default`, `status`, `tenant_id`)
VALUES
    ('file_type', '音频', 'AUDIO', 5, 0, 'ENABLED', 0),
    ('file_type', '视频', 'VIDEO', 6, 0, 'ENABLED', 0)
ON DUPLICATE KEY UPDATE `dict_label`=VALUES(`dict_label`), `sort_no`=VALUES(`sort_no`), `is_default`=VALUES(`is_default`);
