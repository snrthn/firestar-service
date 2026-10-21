package com.firestar.dto;

import lombok.Data;

/**
 * 初始化状态视图。
 * initialized：是否已完成初始化（sys_initialized）。
 * trialMode：是否试运行模式（trial_mode），仅 true 时可重置。
 * canReset：trialMode && initialized，前端据此显示重置入口。
 * adminExists：是否存在超管账号（admin），用于向导提示。
 */
@Data
public class InitStatusVO {
    private boolean initialized;
    private boolean trialMode;
    private boolean canReset;
    private boolean adminExists;
}
