package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.AuditLogVO;
import com.firestar.dto.LoginLogVO;
import com.firestar.entity.AuditLog;
import com.firestar.entity.LoginLog;

import java.time.LocalDateTime;

/**
 * 日志服务：操作审计（sys_audit_log）+ 登录日志（sys_login_log）的写入与查询。
 *
 * <p>写入方法使用 REQUIRES_NEW，保证与业务事务隔离——即便业务回滚，已提交的审计/登录日志也不受影响；
 * 反过来业务成功、日志写入失败也只是丢一条日志，不会拖垮主流程。
 */
public interface LogService {

    /** 落库一条操作审计（REQUIRES_NEW，供审计切面异步调用） */
    void saveAudit(AuditLog log);

    /** 落库一条登录日志（REQUIRES_NEW，供认证服务调用） */
    void saveLogin(LoginLog log);

    /** 登录日志分页查询（按账号模糊、状态精确、时间区间过滤） */
    IPage<LoginLogVO> pageLoginLogs(Integer pageNum, Integer pageSize,
                                    String username, String status,
                                    LocalDateTime startTime, LocalDateTime endTime);

    /** 操作审计分页查询（按操作人账号模糊、目标类型/操作类型精确、时间区间过滤） */
    IPage<AuditLogVO> pageAuditLogs(Integer pageNum, Integer pageSize,
                                    String operator, String targetType, String operationType,
                                    LocalDateTime startTime, LocalDateTime endTime);
}
