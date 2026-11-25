package com.firestar.ai.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：SSE 流异步线程必须能读到主线程已认证用户的权限。
 *
 * <p>根因复盘：AiChatController.chatStream 在【主线程】读 isAdmin/username，但 authorities
 * 原先在 CompletableFuture.runAsync 的【异步线程内部】现读 SecurityContextHolder。Spring Security
 * 默认 MODE_THREADLOCAL，上下文不跨线程传播 → 异步线程 auth=null → authorities 空集 →
 * allVisible 把需权限的工具（query_orgs/org:manage）裁掉、ToolExecutor.hasPerm 拒绝，
 * 表现为「管理员查得到组织架构，主管查不到」，尽管主管角色确实配了 org:manage。</p>
 *
 * <p>修复：用 {@link DelegatingSecurityContextRunnable#create(Runnable)} 包装 runAsync 任务，
 * 在调用线程捕获上下文、执行线程注入。本测试同时锁定两点：
 * ① 裸 runAsync 子线程读不到 authorities（证明根因真实存在，防止误判为配置问题）；
 * ② 包装后子线程能读到完整 authorities，覆盖两道权限闸的读取口径。</p>
 *
 * <p>不启动 Spring 上下文、不连库，纯 ThreadLocal 行为验证，稳定且快。</p>
 */
class SecurityContextPropagationTest {

    private static final String PERM_ORG_MANAGE = "PERM:org:manage";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 以主管身份在主线程建立上下文（ROLE_SUPERVISOR + PERM:org:manage，与生产一致） */
    private void loginAsSupervisor() {
        Authentication auth = new UsernamePasswordAuthenticationToken("supervisor", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"),
                        new SimpleGrantedAuthority(PERM_ORG_MANAGE)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 在指定线程里读取 authorities（供两道闸口径使用） */
    private static List<String> authoritiesOnCurrentThread() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return List.of();
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();
    }

    /**
     * ① 根因锁定：裸 CompletableFuture.runAsync 不传播 SecurityContext，
     * 异步线程读到的 authorities 为空——这正是主管经 AI 查不到工具的原因。
     */
    @Test
    void bareRunAsyncLosesAuthorities() throws Exception {
        loginAsSupervisor();
        assertTrue(authoritiesOnCurrentThread().contains(PERM_ORG_MANAGE),
                "主线程应持有 org:manage 权限");

        AtomicReference<List<String>> asyncAuthorities = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            asyncAuthorities.set(authoritiesOnCurrentThread());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步任务应在 5s 内完成");
        assertNotNull(asyncAuthorities.get());
        assertFalse(asyncAuthorities.get().contains(PERM_ORG_MANAGE),
                "裸 runAsync 的异步线程不应继承 ThreadLocal 上下文（根因复现）");
    }

    /**
     * ② 修复验证：DelegatingSecurityContextRunnable.create 包装后，
     * 异步线程读到完整 authorities，两道权限闸（allVisible / hasPerm）口径均满足。
     */
    @Test
    void delegatingRunnablePropagatesAuthorities() throws Exception {
        loginAsSupervisor();
        SecurityContext captured = SecurityContextHolder.getContext();

        AtomicReference<List<String>> asyncAuthorities = new AtomicReference<>();
        AtomicReference<String> asyncUsername = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture.runAsync(DelegatingSecurityContextRunnable.create(() -> {
            asyncAuthorities.set(authoritiesOnCurrentThread());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            asyncUsername.set(auth == null ? null : auth.getName());
            latch.countDown();
        }, captured));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步任务应在 5s 内完成");
        assertNotNull(asyncAuthorities.get(), "包装后异步线程应拿到上下文");
        assertTrue(asyncAuthorities.get().contains(PERM_ORG_MANAGE),
                "包装后异步线程应读到 PERM:org:manage，query_orgs 不再被裁掉");
        assertTrue(asyncAuthorities.get().contains("ROLE_SUPERVISOR"),
                "角色码也应透传（isAdmin 判定与 KbDoc ACL 依赖）");
        assertEquals("supervisor", asyncUsername.get(), "用户名应一并透传");
        assertNotNull(captured, "捕获的上下文非空");
    }

    /**
     * ③ 上下文恢复：包装任务执行期间使用调用方上下文，结束后恢复 worker 线程原上下文。
     * 这是 DelegatingSecurityContextRunnable 的核心契约——防止线程池线程被业务身份永久污染。
     * 在同一线程同步执行包装任务即可验证（run/finally 的 setContext 恢复逻辑与线程无关）。
     */
    @Test
    void delegatingRunnableRestoresWorkerContextAfterRun() {
        // worker 线程（如 ForkJoinPool 线程）原本的身份
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("worker", null,
                        List.of(new SimpleGrantedAuthority("ROLE_WORKER"))));

        // 调用方（主线程）捕获的身份，与 worker 不同
        SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
        callerContext.setAuthentication(
                new UsernamePasswordAuthenticationToken("caller", null,
                        List.of(new SimpleGrantedAuthority(PERM_ORG_MANAGE))));

        Runnable wrapped = DelegatingSecurityContextRunnable.create(() -> {
            Authentication during = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(during);
            assertEquals("caller", during.getName(), "执行期间应切换到调用方上下文");
            assertTrue(during.getAuthorities().stream()
                    .anyMatch(a -> PERM_ORG_MANAGE.equals(a.getAuthority())));
        }, callerContext);

        wrapped.run();

        Authentication after = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(after);
        assertEquals("worker", after.getName(), "执行完应恢复 worker 线程原上下文，避免污染线程池");
    }
}
