package com.firestar.common;

/** 导入模块的阶段 / 状态常量（与 V218 字典 import_batch_status / import_phase 对齐）。 */
public final class ImportStatus {

    private ImportStatus() {
    }

    // 阶段
    public static final String PHASE_QUEUED = "QUEUED";
    public static final String PHASE_PARSING = "PARSING";
    public static final String PHASE_VALIDATING = "VALIDATING";
    public static final String PHASE_WRITING = "WRITING";
    public static final String PHASE_RECONCILING = "RECONCILING";
    public static final String PHASE_DONE = "DONE";

    // 状态
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 是否为终态（终态才释放 active_flag、发通知） */
    public static boolean isTerminal(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_PARTIAL.equals(status)
                || STATUS_FAILED.equals(status) || STATUS_ARCHIVED.equals(status);
    }
}
