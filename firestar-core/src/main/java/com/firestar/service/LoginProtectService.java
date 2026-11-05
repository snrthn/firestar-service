package com.firestar.service;

/**
 * 登录爆破防护服务（L1 账户锁定 + L2 IP 限流 + 审计闭环）。
 *
 * <p>设计要点（社区+大厂调研结论，2026-11-05）：
 * <ul>
 *   <li>L1 按<b>账户</b>计数（不能只按 IP，否则攻击者换 IP 即绕过；OWASP/NIST 共识）。</li>
 *   <li>临时锁 + 自助/管理员解锁，<b>不用低阈值永久锁</b>（否则成为 DoS 武器）。</li>
 *   <li>成功后清零计数；锁/解锁动作本身也写审计（sys_login_log 的 LOCKED/UNLOCKED），形成闭环。</li>
 *   <li>firestar 当前无 Redis，全部 MySQL 落地（唯一索引 + 原子自增保证并发安全）。</li>
 * </ul>
 */
public interface LoginProtectService {

    /**
     * 登录失败：账户失败计数 +1；达到阈值则置锁定，并写 LOCKED 审计日志 + 推送安全告警。
     *
     * @param userId   失败账号的用户 ID（可能为 null，如用户名不存在时不计 L1）
     * @param username 尝试登录的账号
     * @param clientIp 客户端真实 IP
     * @param userAgent 浏览器 UA
     */
    void onLoginFail(Long userId, String username, String clientIp, String userAgent);

    /** 登录成功：清零该账号的失败计数（删除锁定/计数行）。 */
    void onLoginSuccess(String username);

    /** 账号当前是否被锁定（locked_until 非 null 且未过期）。 */
    boolean isLocked(String username);

    /** 剩余锁定秒数（未锁定返回 0）。 */
    long getRemainingLockSeconds(String username);

    /**
     * 管理员手动解锁：清零失败计数，并写 UNLOCKED 审计日志 + 推送安全告警。
     *
     * @param username 被解锁账号
     * @param operator 操作管理员账号
     */
    void unlock(String username, String operator);

    /**
     * L2：记录并校验来源 IP 的登录请求频率。超限抛出 {@link com.firestar.exception.LoginRateLimitException}。
     *
     * @param ip 客户端真实 IP（X-Forwarded-For 最左段，可伪造，仅作近似识别）
     */
    void acquire(String ip);
}
