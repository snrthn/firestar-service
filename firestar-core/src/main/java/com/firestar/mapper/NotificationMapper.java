package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Notification;
import org.apache.ibatis.annotations.Insert;

/**
 * 通知 Mapper。
 *
 * insertIgnore：dedup 的唯一屏障。先查后插在并发下会双落
 * （两个请求同时查"没有"、同时插），唯一键 + INSERT IGNORE 让数据库兜底。
 *
 * ⚠️ 原生 @Insert 不走 MetaObjectHandler 自动填充，version / created_at /
 *    updated_at 必须在 SQL 里显式给值（否则 version=NULL，之后 updateById
 *    会让乐观锁插件静默放行——AuditMetaObjectHandler 注释里实锤过的坑）。
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    @Insert("INSERT IGNORE INTO sys_notification "
            + "(tenant_id, version, receiver_id, type, title, content, biz_type, biz_id, link_url, level, read_at, read_channel, dedup_key, expire_at, created_by, created_at, updated_by, updated_at) "
            + "VALUES "
            + "(#{tenantId}, 0, #{receiverId}, #{type}, #{title}, #{content}, #{bizType}, #{bizId}, #{linkUrl}, #{level}, #{readAt}, #{readChannel}, #{dedupKey}, #{expireAt}, #{createdBy}, NOW(), #{updatedBy}, NOW())")
    int insertIgnore(Notification notification);
}
