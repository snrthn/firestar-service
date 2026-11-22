package com.firestar.common;

/**
 * 导入线程上下文 —— 审计降级的开关（方案 v3）。
 *
 * <p>导入会调用既有业务 Service（其中很多方法带 {@code @Auditable}），
 * 2000 行会产生 2000 条实体审计。按虎哥定版「单次导入只留一条批次摘要」，
 * 导入线程内用本 ThreadLocal 通知 {@code AuditLogAspect} 跳过所有实体审计。</p>
 *
 * <p><b>纪律</b>：只在导入专用线程池的 worker 线程里 enter/exit，且必须在 finally 中 clear，
 * 防止 ThreadLocal 泄漏到线程池后续任务（正常业务线程的审计不受影响）。</p>
 */
public final class ImportContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    /** 当前导入批次的操作人 ID（供 Handler 调用的既有 Service 补审计字段用） */
    private static final ThreadLocal<Long> OPERATOR = new ThreadLocal<>();

    private ImportContext() {
    }

    /** 标记当前线程处于导入流水线中（进入时调用） */
    public static void enter() {
        ACTIVE.set(true);
    }

    /** 当前线程是否为导入线程（AuditLogAspect 据此跳过实体审计） */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** 记录本批次导入人（runPipeline 拿到批次后调用） */
    public static void setOperatorId(Long operatorId) {
        OPERATOR.set(operatorId);
    }

    /** 当前批次导入人；非导入线程返回 null */
    public static Long getOperatorId() {
        return OPERATOR.get();
    }

    /** 清除标记（必须在 finally 中调用） */
    public static void clear() {
        ACTIVE.remove();
        OPERATOR.remove();
    }
}
