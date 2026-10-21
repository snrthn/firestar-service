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
}
