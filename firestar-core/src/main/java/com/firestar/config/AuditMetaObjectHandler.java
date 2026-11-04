package com.firestar.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充。
 *
 * 【为什么必须有它 —— 一个真实踩过的坑】
 * MyBatis-Plus 的乐观锁依赖实体上的 version 值：值为 null 时
 * OptimisticLockerInnerInterceptor 会认为"调用方没打算走乐观锁"，
 * 直接放行，生成的 SQL 里既没有 SET version = version + 1，
 * 也没有 WHERE version = ?。
 *
 * 而 insert 后 version 是 null（DB 侧 DEFAULT 0 但不回填实体），
 * 于是「insert 出来的对象直接拿去 updateById」会**静默丢掉乐观锁**，
 * UPDATE 影响行数仍是 1，测试若只断言影响行数根本发现不了。
 * 核销 / 审批这类并发写场景一旦丢了它，就是资金数据错乱。
 *
 * 因此：version 在 insert 时统一填 0，实体与 DB 从此保持一致。
 *
 * 【当前填充范围】
 * ✅ version / createdAt / updatedAt —— 无争议，直接填
 * ⚠️ createdBy / updatedBy —— **暂不填**。取值应来自登录上下文，
 *    登录模块尚未实现，此刻填 0 或常量会造成"看起来有审计人、实际是假的"，
 *    比空值更危险。等 SecurityContextHolder 接入后在此处补齐，
 *    届时业务层可移除显式 setCreatedBy。
 *
 * 【tenantId 也不在此填】
 * 将来启用多租户时由 TenantLineInnerInterceptor 统一改写 SQL，
 * 若这里再填一次会造成两处来源，冲突时难以排查。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // strictInsertFill：字段声明了 fill 才填，父类字段同样生效
        this.strictInsertFill(metaObject, "version", Integer.class, 0);
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 注意：这里**不能**填 version。
        // OptimisticLockerInnerInterceptor 在此之后才把 version+1 写回 SQL，
        // 这里再 set 会覆盖它的计算结果。
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
