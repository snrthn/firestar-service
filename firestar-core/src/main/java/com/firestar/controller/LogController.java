package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.AuditLogVO;
import com.firestar.dto.LoginLogVO;
import com.firestar.service.LogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * 日志管理接口（前端 /api/logs/**，代理剥离 /api 后到 /logs/**）。
 *
 * <p>前端：日志管理 → 登录日志（/log/login）、操作日志（/log/operation）。
 * 两者共用 log:view 权限点（与菜单 permission 对齐），写操作才用 log:manage。
 *
 * <p>时间区间接受多种格式：yyyy-MM-dd HH:mm:ss / yyyy-MM-dd HH:mm / yyyy-MM-dd。
 */
@RestController
@RequirePerm("log:view")
@RequestMapping("/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    /** 登录日志分页（账号模糊 / 状态精确 / 时间区间） */
    @GetMapping("/login")
    public Result<IPage<LoginLogVO>> loginLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return Result.success(logService.pageLoginLogs(
                pageNum, pageSize, username, status,
                parseTime(startTime), parseTime(endTime)));
    }

    /** 操作审计分页（操作人模糊 / 目标类型精确 / 操作类型精确 / 时间区间） */
    @GetMapping("/operation")
    public Result<IPage<AuditLogVO>> operationLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return Result.success(logService.pageAuditLogs(
                pageNum, pageSize, operator, targetType, operationType,
                parseTime(startTime), parseTime(endTime)));
    }

    private static final List<DateTimeFormatter> TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    /** 空/非法时间串 → null（不参与过滤）；合法串按多格式解析 */
    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.contains("T")) {
            try {
                return LocalDateTime.parse(t); // ISO_LOCAL_DATE_TIME
            } catch (DateTimeParseException ignored) {
                // 落到下方格式继续尝试
            }
        }
        for (DateTimeFormatter f : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return null;
    }
}
