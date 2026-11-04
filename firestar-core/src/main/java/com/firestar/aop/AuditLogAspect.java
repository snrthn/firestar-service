package com.firestar.aop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.annotation.Auditable;
import com.firestar.common.IpUtil;
import com.firestar.config.GenericEntityLoader;
import com.firestar.entity.AuditLog;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import com.firestar.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 操作审计切面：拦截 {@link Auditable} 标注的方法，自动写 sys_audit_log。
 *
 * <p>职责边界（与业务解耦）：
 * <ul>
 *   <li><b>同步阶段</b>：在业务方法执行前后，抓取「操作人 / IP / 目标 id / before·after 关键字段快照」；</li>
 *   <li><b>异步阶段</b>：把组装好的 {@link AuditLog} 派发到 logWriteExecutor 落库（独立 REQUIRES_NEW 事务）；</li>
 *   <li><b>事务安全</b>：仅当业务方法成功返回才写审计；若业务回滚（抛异常），本切面不写，
 *       避免「操作失败却留审计」的脏数据。</li>
 * </ul>
 *
 * <p>注意：操作人 / IP 必须在请求线程（同步阶段）抓取，因为 SecurityContext、RequestContext 是
 * 线程绑定的，不会传播到异步线程。审计 insert 本身不需要这些上下文。
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final LogService logService;
    private final UserMapper userMapper;
    private final GenericEntityLoader entityLoader;
    private final Executor logExecutor;
    private final ParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    public AuditLogAspect(LogService logService, UserMapper userMapper,
                          GenericEntityLoader entityLoader,
                          @Qualifier("logWriteExecutor") Executor logExecutor) {
        this.logService = logService;
        this.userMapper = userMapper;
        this.entityLoader = entityLoader;
        this.logExecutor = logExecutor;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        // 解析目标方法（CGLIB/JDK 代理下拿到 impl 方法，保证拿到真实参数名）
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Method targetMethod = AopUtils.getMostSpecificMethod(ms.getMethod(), pjp.getTarget().getClass());
        String[] paramNames = paramDiscoverer.getParameterNames(targetMethod);
        Object[] args = pjp.getArgs();

        EvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], i < args.length ? args[i] : null);
            }
        }

        String op = auditable.operationType().toUpperCase(java.util.Locale.ROOT);
        boolean loadBefore = ("UPDATE".equals(op) || "DELETE".equals(op))
                && auditable.entityClass() != Void.class;

        // before 快照：UPDATE/DELETE 按 id 加载旧实体
        Map<String, Object> before = null;
        Long targetId = evalId(auditable.targetIdSpEl(), ctx);
        if (loadBefore && targetId != null) {
            Object oldEntity = entityLoader.load(auditable.entityClass(), targetId);
            if (oldEntity != null) {
                before = snapshot(oldEntity, auditable.fields());
            }
        }

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            // 业务失败 → 不写审计
            throw t;
        }

        // after 快照来源：#result 是复杂对象用之；否则取第一个复杂参数（通常是 req DTO）
        Object afterSource = result;
        if (isSimpleOrNull(afterSource)) {
            afterSource = firstComplexArg(args);
        }
        Map<String, Object> after = snapshot(afterSource, auditable.fields());

        // create 类：targetId 来自返回值，proceed 后才能取到
        if (targetId == null && !auditable.targetIdSpEl().isBlank()) {
            ctx.setVariable("result", result);
            targetId = evalId(auditable.targetIdSpEl(), ctx);
        }

        Long operatorId = currentOperatorId();
        String ip = currentClientIp();

        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setOperationType(op);
        log.setTargetType(auditable.targetType());
        log.setTargetId(targetId);
        log.setBeforeValue(before);
        log.setAfterValue(after);
        log.setIpAddress(ip);
        log.setRemark(auditable.remark().isBlank()
                ? (op + " " + auditable.targetType()) : auditable.remark());
        log.setOperatedAt(LocalDateTime.now());

        submitWrite(() -> logService.saveAudit(log));
        return result;
    }

    // ===== 内部工具 =====

    private Long evalId(String spEl, EvaluationContext ctx) {
        Object v = eval(spEl, ctx);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof AuditLog) return ((AuditLog) v).getTargetId();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object eval(String spEl, EvaluationContext ctx) {
        if (spEl == null || spEl.isBlank()) return null;
        try {
            Expression expr = spelParser.parseExpression(spEl);
            return expr.getValue(ctx);
        } catch (Exception e) {
            log.debug("Auditable SpEL 求值失败 spEl={} : {}", spEl, e.getMessage());
            return null;
        }
    }

    /** 反射抽取关键字段；对象上没有的字段自动跳过（兼容实体/VO/DTO 字段集差异） */
    private Map<String, Object> snapshot(Object obj, String[] fields) {
        if (obj == null || fields == null || fields.length == 0) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        for (String f : fields) {
            Object v = readField(obj, f);
            if (v != null) {
                map.put(f, v);
            }
        }
        return map.isEmpty() ? null : map;
    }

    private Object readField(Object obj, String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            try {
                Method m = obj.getClass().getMethod(prefix + cap);
                return m.invoke(obj);
            } catch (NoSuchMethodException ignored) {
                // 试下一个前缀 / 退化为字段
            } catch (Exception ignored) {
                // 反射调用异常，忽略
            }
        }
        try {
            Field fld = obj.getClass().getDeclaredField(field);
            fld.setAccessible(true);
            return fld.get(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSimpleOrNull(Object o) {
        if (o == null) return true;
        return o instanceof Number || o instanceof String || o instanceof Boolean
                || o instanceof Character || o instanceof MultipartFile
                || o instanceof HttpServletRequest || o instanceof HttpServletResponse;
    }

    private Object firstComplexArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (!isSimpleOrNull(a)) return a;
        }
        return null;
    }

    private Long currentOperatorId() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null) return null;
            String username = auth.getName();
            if ("anonymousUser".equals(username)) return null;
            User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, username).select(User::getId));
            return u == null ? null : u.getId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentClientIp() {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra instanceof ServletRequestAttributes sa) {
                return IpUtil.getClientIp(sa.getRequest());
            }
        } catch (Exception ignored) {
            // 非 Web 上下文（如定时任务）无 request
        }
        return null;
    }

    /** 业务事务提交后再落库；无活动事务则直接派发，保证「回滚不写审计」 */
    private void submitWrite(Runnable task) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                logExecutor.execute(task);
                            }
                        });
            } else {
                logExecutor.execute(task);
            }
        } catch (Exception e) {
            log.error("审计日志派发失败（已降级为同步写入尝试）: {}", e.getMessage());
            try {
                task.run();
            } catch (Exception ex) {
                log.error("审计日志写入失败，操作审计将缺失: {}", ex.getMessage());
            }
        }
    }
}
