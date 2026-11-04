package com.firestar.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 提取（登录审计用）。
 *
 * 【为什么不能直接 getRemoteAddr()】
 * 生产走 nginx 反代（前端 80/443 → 后端 2025），此时 TCP 对端永远是反代机，
 * getRemoteAddr() 只会拿到 127.0.0.1 或内网网关地址，记下来毫无审计价值。
 * 必须优先读反代注入的 X-Forwarded-For / X-Real-IP。
 *
 * 【X-Forwarded-For 形态】
 * "client, proxy1, proxy2" —— 最左是原始客户端，右侧是各级代理。
 * 该头可被伪造（客户端自带），仅用于展示/审计，绝不用于鉴权或限流判定。
 */
public final class IpUtil {

    /** 列宽 sys_user.last_login_ip VARCHAR(64)，超长截断防 DataTruncation */
    private static final int MAX_LEN = 64;

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = firstValid(request.getHeader("X-Forwarded-For"));
        if (ip == null) ip = firstValid(request.getHeader("X-Real-IP"));
        if (ip == null) ip = firstValid(request.getHeader("Proxy-Client-IP"));
        if (ip == null) ip = firstValid(request.getHeader("WL-Proxy-Client-IP"));
        if (ip == null) ip = firstValid(request.getRemoteAddr());
        return truncate(ip);
    }

    /** XFF 是逗号链，取第一个非 unknown 段；单值头直接返回 */
    private static String firstValid(String header) {
        if (header == null || header.isBlank()) return null;
        for (String part : header.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !"unknown".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    private static String truncate(String ip) {
        if (ip == null) return null;
        // 本机直连（开发环境不经 nginx）时 JVM 返回 IPv6 回环，归一为 IPv4 回环便于阅读
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        return ip.length() <= MAX_LEN ? ip : ip.substring(0, MAX_LEN);
    }
}
