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

    /** 上次重置时间（仅重置页的鉴权端点回显，匿名端点恒为 null） */
    private String lastResetTime;

    /** 上次重置操作人（仅重置页的鉴权端点回显，匿名端点恒为 null） */
    private String lastResetBy;
}
