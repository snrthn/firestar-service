-- V17：字典数据状态域值归一修正
-- 背景：V2 种子当年把 status 写成中文'启用'/'停用'，后续迁移（V10 归一 sys_role、
-- 字典模块迭代归一 sys_dict_type）已把全库状态域值统一为 ENABLED/DISABLED，
-- 代码侧（DictServiceImpl.applyDataCheck）也只接受这两个英文域值。
-- V16 补「外链 LINK」字典项时照抄了 V2 的旧中文风格写入'启用'，成为 sys_dict_data
-- 全表唯一中文状态残留（id=83，2026-10-15 探针发现：sys_dict_data 83 条 = V2 基线 82 + V16 外链 1）。
-- 本迁移幂等归一：已修正的环境命中 0 行，新环境部署 V16 后由本迁移自动收口。
UPDATE `sys_dict_data` SET `status` = 'ENABLED' WHERE `status` = '启用';
UPDATE `sys_dict_data` SET `status` = 'DISABLED' WHERE `status` = '停用';
