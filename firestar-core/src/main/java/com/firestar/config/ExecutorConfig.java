package com.firestar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计 / 日志异步落库线程池。
 *
 * <p>审计切面在业务事务提交后，把 sys_audit_log / sys_login_log 的 insert 派发到这里执行，
 * 避免占用业务请求线程、也不参与业务事务（独立 REQUIRES_NEW 事务）。
 *
 * <p>队列 + 拒绝策略：日志写入是「尽力而为」的旁路，打满后由调用方线程直接执行（CallerRuns），
 * 保证不丢审计；不会因日志瓶颈拖垮主流程。
 */
@Configuration
public class ExecutorConfig {

    /** 审计日志专用线程池 bean 名，切面按名注入 */
    public static final String LOG_WRITE_EXECUTOR = "logWriteExecutor";

    /** 知识库向量化专用线程池 bean 名（发布后异步生成 embedding，不阻塞发布主流程）。 */
    public static final String KB_VECTORIZE_EXECUTOR = "kbVectorizeExecutor";

    /** 历史数据导入专用线程池 bean 名（v3 方案：异步流水线，单 key 串行靠 DB 唯一索引）。 */
    public static final String IMPORT_EXECUTOR = "importExecutor";

    @Bean(name = LOG_WRITE_EXECUTOR)
    public Executor logWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("audit-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 知识库向量化线程池：单线程足够（切片生成是 IO 密集、低频操作），队列有界，
     * 打满后 CallerRuns 由发布线程兜底执行，保证向量不丢；不参与业务事务。
     */
    @Bean(name = KB_VECTORIZE_EXECUTOR)
    public Executor kbVectorizeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("kb-vector-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 历史数据导入线程池（方案 v3 §1.4）：core=2 / max=4、队列 200、AbortPolicy。
     *
     * <p>单批 ≤2000 行秒级完成，队列不会长期堆积；打满时 submit 直接返回
     * 「稍后重试」（由 ImportService 捕获 RejectedExecutionException 转 429/503），
     * 不用 CallerRuns（避免占住上传请求线程）。同 key 串行靠 DB 唯一索引 uk_active，
     * 不靠线程池排队。线程名前缀便于日志定位；shutdown 时等待在跑批次收尾。</p>
     */
    @Bean(name = IMPORT_EXECUTOR)
    public Executor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("import-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
