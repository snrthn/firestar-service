package com.firestar.common;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 未认证（401）响应的统一出口。
 *
 * 【为什么要有这个类】
 * 401 有三条不同的产生路径，历史上各写各的，格式一旦漂移前端就解析不到 message：
 *   1. JwtAuthenticationFilter —— token 无效/过期/黑名单/被顶号（写 JSON）
 *   2. SecurityConfig.authenticationEntryPoint —— 请求压根没带 token，在 Spring Security
 *      授权阶段被拒（2026-10-18 前默认是 Spring Security 6 的 Http403ForbiddenEntryPoint，
 *      返的是 403，导致前端 realtimeClient 的 401 分支永不命中）
 *   3. SSE 端点内部 —— /sse/** 已 permitAll，鉴权下沉到端点自己做，需要手写 401
 *
 * 三者必须输出**完全相同**的 JSON 结构，否则前端 res.json() 拿不到 message，
 * 只能回落兜底文案。
 *
 * 【格式约定】与 Result 序列化结果保持一致：{"code":401,"message":"...","data":null}
 * 前端 api/request.ts 与 realtimeClient.ts 都按 code/message 解析。
 */
public final class UnauthorizedResponses {

    private UnauthorizedResponses() {
    }

    /** 「登录已过期」的标准文案（缺少 token / token 解析失败 / 用户不存在） */
    public static final String MSG_EXPIRED = "登录已过期，请重新登录";

    /** 「被顶号」的标准文案（current_jti 与 token 中的 jti 不匹配） */
    public static final String MSG_KICKED = "您的账号已在其他设备登录，原会话已失效，请重新登录";

    /**
     * 直接向 HttpServletResponse 写 401 JSON（供 Filter / EntryPoint 使用，
     * 这两个地方拿不到 ResponseEntity 机制）。
     */
    public static void write(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json(message));
    }

    /** 生成与 Result 序列化一致的 401 JSON 字符串 */
    public static String json(String message) {
        return "{\"code\":401,\"message\":\"" + escape(message) + "\",\"data\":null}";
    }

    /**
     * 极简 JSON 转义。文案都是本类里的常量或后端内部消息，不含用户可控输入，
     * 但仍防一手：万一将来拼了动态内容（如异常 message），含双引号/反斜杠会
     * 破坏 JSON 结构，前端 res.json() 直接抛错 → 整个 401 分支失效。
     */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
