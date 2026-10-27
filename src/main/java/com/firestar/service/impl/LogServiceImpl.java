package com.firestar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.common.DataScopeHelper;
import com.firestar.common.SecurityContextUtils;
import com.firestar.dto.AuditLogVO;
import com.firestar.dto.LoginLogVO;
import com.firestar.entity.AuditLog;
import com.firestar.entity.LoginLog;
import com.firestar.entity.User;
import com.firestar.mapper.AuditLogMapper;
import com.firestar.mapper.LoginLogMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.LogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 日志服务实现。写入走 REQUIRES_NEW 与业务事务隔离；查询为只读。
 */
@Slf4j
@Service
public class LogServiceImpl implements LogService {

    private final AuditLogMapper auditLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final UserMapper userMapper;

    public LogServiceImpl(AuditLogMapper auditLogMapper, LoginLogMapper loginLogMapper,
                          UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveAudit(AuditLog log) {
        auditLogMapper.insert(log);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveLogin(LoginLog log) {
        loginLogMapper.insert(log);
    }

    @Override
    public IPage<LoginLogVO> pageLoginLogs(Integer pageNum, Integer pageSize,
                                          String username, String status,
                                          LocalDateTime startTime, LocalDateTime endTime) {
        int current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 500);

        // 行级数据隔离（2026-10-24 虎哥）：非 ROLE_ADMIN 只看自己的登录日志
        // （LoginLog.username 存的是登录账号，直接用，不必回查 userId）。
        // 查询框里的「账号」是 admin 在全员视角下的二次筛选，与隔离条件叠加生效。
        String me = SecurityContextUtils.currentUsername();
        LambdaQueryWrapper<LoginLog> qw = new LambdaQueryWrapper<>();
        qw.like(username != null && !username.isBlank(), LoginLog::getUsername, username)
           .eq(status != null && !status.isBlank(), LoginLog::getLoginStatus, status)
           .ge(startTime != null, LoginLog::getLoginAt, startTime)
           .le(endTime != null, LoginLog::getLoginAt, endTime)
           .orderByDesc(LoginLog::getLoginAt);
        DataScopeHelper.own(qw, LoginLog::getUsername, me);
        IPage<LoginLog> page = loginLogMapper.selectPage(new Page<>(current, size), qw);

        Page<LoginLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toLoginVo).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public IPage<AuditLogVO> pageAuditLogs(Integer pageNum, Integer pageSize,
                                           String operator, String targetType, String operationType,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        int current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 500);

        // 操作人账号 → operatorId（支持模糊）
        Set<Long> operatorIds = null;
        if (operator != null && !operator.isBlank()) {
            List<User> users = userMapper.selectList(
                    new LambdaQueryWrapper<User>().like(User::getUsername, operator).select(User::getId));
            operatorIds = users.stream().map(User::getId).collect(Collectors.toSet());
            if (operatorIds.isEmpty()) {
                // 查无此人 → 直接返回空页
                return new Page<>(current, size, 0);
            }
        }

        // 行级数据隔离（2026-10-24 虎哥）：非 ROLE_ADMIN 只看自己的操作日志
        // （AuditLog.operatorId = 自己）。「操作人」查询框是 admin 全员视角下的二次筛选，
        // 与隔离条件叠加生效（operatorIds 与 me 同时命中才会留下记录）。
        Long me = DataScopeHelper.currentUserId(userMapper);
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        qw.in(operatorIds != null, AuditLog::getOperatorId, operatorIds)
           .eq(targetType != null && !targetType.isBlank(), AuditLog::getTargetType, targetType)
           .eq(operationType != null && !operationType.isBlank(), AuditLog::getOperationType, operationType)
           .ge(startTime != null, AuditLog::getOperatedAt, startTime)
           .le(endTime != null, AuditLog::getOperatedAt, endTime)
           .orderByDesc(AuditLog::getOperatedAt);
        DataScopeHelper.own(qw, AuditLog::getOperatorId, me);
        IPage<AuditLog> page = auditLogMapper.selectPage(new Page<>(current, size), qw);

        // operatorId → username 回填
        Set<Long> ids = page.getRecords().stream().map(AuditLog::getOperatorId).collect(Collectors.toSet());
        Map<Long, String> nameById = ids.stream()
                .filter(id -> id != null)
                .collect(Collectors.toMap(id -> id, id -> {
                    User u = userMapper.selectById(id);
                    return u == null ? null : u.getUsername();
                }, (a, b) -> a));

        Page<AuditLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(a -> toAuditVo(a, nameById)).collect(Collectors.toList()));
        return voPage;
    }

    private LoginLogVO toLoginVo(LoginLog e) {
        LoginLogVO v = new LoginLogVO();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setUsername(e.getUsername());
        v.setLoginStatus(e.getLoginStatus());
        v.setIpAddress(e.getIpAddress());
        v.setUserAgent(e.getUserAgent());
        v.setFailReason(e.getFailReason());
        v.setLoginAt(e.getLoginAt());
        return v;
    }

    private AuditLogVO toAuditVo(AuditLog e, Map<Long, String> nameById) {
        AuditLogVO v = new AuditLogVO();
        v.setId(e.getId());
        v.setOperatorId(e.getOperatorId());
        v.setOperatorName(e.getOperatorId() != null ? nameById.get(e.getOperatorId()) : null);
        v.setOperationType(e.getOperationType());
        v.setTargetType(e.getTargetType());
        v.setTargetId(e.getTargetId());
        v.setBeforeValue(e.getBeforeValue());
        v.setAfterValue(e.getAfterValue());
        v.setOperatedAt(e.getOperatedAt());
        v.setIpAddress(e.getIpAddress());
        v.setRemark(e.getRemark());
        return v;
    }
}
