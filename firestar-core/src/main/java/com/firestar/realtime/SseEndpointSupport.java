package com.firestar.realtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.firestar.common.JwtUtil;
import com.firestar.common.UnauthorizedResponses;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.BiFunction;

/**
 * SSE 端点公共支撑：握手鉴权 + 统一建流。
 *
 * 【为什么 /sse/** 在 Spring Security 里是 permitAll，鉴权却要在这里重做一遍】
 * SSE 是长连接，握手发生在 Security 过滤器链之外也无法很好地表达「这笔请求的身份已过期」：
 * 走 anyRequest().authenticated() 时，带上过期 token 的请求会被 JwtAuthenticationFilter
 * 直接写 401 JSON 打断 —— 状态码对，但 SSE 客户端期望的是拿到明确的 code/message，
 * 且 Signature 发生变化时（比如将来换 EventSource、token 走 query）会一次次在
 * 过滤器链上开特例。把裁决下沉到端点内部，行为可控且可读：
 *
 *   缺 token / token 解析失败 / 用户不存在 → 401 MSG_EXPIRED
 *   token 有效但 current_jti 不匹配（被顶号）→ 401 MSG_KICKED
 *
 * 与 JwtAuthenticationFilter 保持完全一致的判定口径和 JSON 格式（见 UnauthorizedResponses），
 * 前端 realtimeClient.ts 的 401 分支才有意义。
 *
 * ⚠️ 注意：这个类里的三段校验是**唯一会真正执行**的那份。历史上 AuthController.stream
 * 里写的同名逻辑是死代码（请求根本到不了 Controller），已废弃，勿再照抄。
 */
@Service
public class SseEndpointSupport {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final RealtimeGateway realtimeGateway;

    public SseEndpointSupport(JwtUtil jwtUtil, UserMapper userMapper, RealtimeGateway realtimeGateway) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.realtimeGateway = realtimeGateway;
    }

    /**
     * 主通道：校验通过则建立实时流（/sse/realtime，/sse/ping 等扩展点共用此实现）。
     */
    public Object streamResponse(HttpServletRequest request) {
        return serve(request, realtimeGateway::connect);
    }

    /**
     * 通用握手：校验通过后由 {@code onSuccess} 决定返回什么样的 SSE 响应，
     * 用于在同一前缀下扩展新的流（如 /sse/ping 探针）。
     * <p>
     * 2026-10-18 的治理目标就是：**新增一条流只需要加一个 @GetMapping，
     * 不用碰 nginx、不用碰 SecurityConfig。**
     *
     * 【为什么返回 Object 而不是 ResponseEntity】SseEmitter 必须**裸返回**。
     * 2026-10-19 实测：把它包进 ResponseEntity.ok().body(emitter) 后，
     * HttpEntityMethodProcessor 会按 preset Content-Type（text/event-stream）去找
     * HttpMessageConverter，而 SseEmitter 没有任何转换器 → 抛
     * HttpMessageNotWritableException: No converter for SseEmitter ... with preset
     * Content-Type 'text/event-stream'，被兜底处理器吞成 HTTP 200 + {"code":500}。
     * 裸返回时 Spring 会交给 ResponseBodyEmitterReturnValueHandler（异步长连接）处理。
     * 401 分支返回的是 ResponseEntity<String>，走 HttpEntityMethodProcessor，两者互不干扰
     * —— 处理器是按**运行时返回值的实际类型**选的，所以 Object 是安全的。
     *
     * @param onSuccess 入参为 (username, jti)，返回要交给浏览器的 SseEmitter
     */
    public Object serve(HttpServletRequest request,
                        BiFunction<String, String, SseEmitter> onSuccess) {
        String token = extractToken(request);
        if (token == null) {
            return unauthorized(UnauthorizedResponses.MSG_EXPIRED);
        }
        String username;
        String jti;
        try {
            username = jwtUtil.getUsername(token);
            jti = jwtUtil.getJti(token);
        } catch (Exception e) {
            return unauthorized(UnauthorizedResponses.MSG_EXPIRED);
        }
        // ⚠️ 为什么 select 里必须带上 username（2026-10-19 实测踩到）：
        // 若只 select 一个 current_jti，当该列为 NULL 时 MyBatis 认为这是「空行」，
        // 不会创建结果对象（returnInstanceForEmptyRow 默认 false）→ selectOne 返回 null，
        // 于是「尚未纳入单点登录管控的存量用户」会被误判成「用户不存在」→ 挡出 401。
        // 多带一个恒非空的 username 列，行对象就一定会被创建，逻辑才对得上：
        //   user == null            → 用户确实不存在
        //   currentJti == null      → 用户存在但还没重新登录过，未纳入管控，放行
        User user = userMapper.selectOne(new QueryWrapper<User>()
                .eq("username", username)
                .select("username", "current_jti"));
        if (user == null) {
            return unauthorized(UnauthorizedResponses.MSG_EXPIRED);
        }
        // jti 不匹配 = 该 token 已被同账号重新登录顶替（与 JwtAuthenticationFilter 判定一致）。
        // current_jti 为 null 视为尚未纳入单点管控，放行（迁移前已登录的旧会话）。
                // ⚠️ current_jti == null 表示「该用户尚未纳入单点登录管控」（典型：current_jti 字段
        // 上线前就已登录、之后没重新登录过的存量会话），**必须放行**。这里原来是
        //   if (currentJti == null || !jti.equals(currentJti))
        // —— 把「未纳入管控」误判成「被顶号」，导致凡 current_jti 为 NULL 的用户，
        // 哪怕拿着刚签发的有效 token 也永远握不上 SSE（永远回「您的账号已在其他设备登录」）。
        // 这段是从旧 AuthController.stream 原样搬过来的，bug 也一起搬过来了；
        // 2026-10-19 本地实测复现，改成与 JwtAuthenticationFilter 同一口径。
        if (user.getCurrentJti() != null && !user.getCurrentJti().equals(jti)) {
            return unauthorized(UnauthorizedResponses.MSG_KICKED);
        }
        return onSuccess.apply(username, jti);
    }

    private static ResponseEntity<Object> unauthorized(String message) {
        // 用 String body + 显式 JSON content-type：保证输出字节与 JwtAuthenticationFilter
        // 手写的那份完全一致，前端两条都按 code/message 解析。
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(UnauthorizedResponses.json(message));
    }

    private static String extractToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7);
        }
        return null;
    }

    /** 供扩展点复用：快速构造一个「发一帧就结束」的 emitter */
    public static SseEmitter oneShot(long timeoutMs, String eventName, Object payload) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
