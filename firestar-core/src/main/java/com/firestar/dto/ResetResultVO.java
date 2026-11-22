package com.firestar.dto;

import lombok.Data;

import java.util.List;

/**
 * 系统重置返回。
 * executed：是否已真正执行（false 表示本次仅返回了验证码挑战，未执行）。
 * verifyCode：executed=false 时返回，需用户输入以确认。
 * message：提示文案。
 * resetType：light / full。
 * clearedTables：本次清理的表名清单（full 重置为 Flyway clean+migrate，列出占位说明）。
 */
@Data
public class ResetResultVO {
    private boolean executed;
    private String verifyCode;
    private String message;
    private String resetType;
    private List<String> clearedTables;

    /**
     * 是否需要重新执行初始化向导（true = 完整重置后系统回到未初始化态）。
     * 前端据此登出当前会话并跳 /install —— 完整重置会 drop 掉 sys_user，
     * 当前 token 下一请求即 401，停留原页只会看到一连串报错。
     */
    private Boolean requireReinstall;
}
