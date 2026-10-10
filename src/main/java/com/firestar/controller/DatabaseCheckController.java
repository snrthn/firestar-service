package com.firestar.controller;

import com.firestar.service.DatabaseCheckService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据库自检接口。
 *
 * 【定位：这是运维/部署诊断接口，不是业务接口】
 * 用途有三个：
 *   1. 部署后确认「连的是对的库、表建全了没、迁移跑到哪一版」
 *   2. 前端健康检测（**前端唯一入口就是这一个**）
 *   3. 线上排查「为什么接口报 500」，先看这里
 *
 * 【为什么故意不用 actuator 暴露】
 * actuator 默认 details 收紧（show-details=never）是有意的——
 * 避免泄漏库名表名。这个自检端点要的就是这些细节，
 * 所以做成独立业务端点，而不是去放开 actuator 的权限。
 * 反过来说，**前端要检测也不该用 /actuator/health**：
 * 它只报连接池/磁盘这类粗粒度状态，出问题时前端只会看到"服务不健康"，
 * 却看不到「连的是不是对的库、表建了几张、迁移到第几版」这些真正有用的信息。
 *
 * ⚠️【安全提醒】返回体含库名、表名、Flyway 版本，属于内部运维信息。
 * 当前 SecurityConfig 是空壳阶段全放行，**接上 JWT 后必须把
 * {@code /system/**} 纳入鉴权**（要求运维权限），而不是放进白名单。
 * 现在能调是因为还没做鉴权，不是设计如此。
 *
 * 【🔴 路径不含 /api 前缀 —— 这是被代理配置逼出来的，改之前先读这段】
 * 前端 vite.config.ts 的代理是：
 *   '/api' → target: http://localhost:2025
 *            rewrite: (path) => path.replace(/^\/api/, '')   ← **剥掉** /api
 * 所以前端调 `/api/system/db-check` 时，**到达后端的是 `/system/db-check`**。
 * 若这里再声明 @RequestMapping("/api/system")，就会变成 `/api/api/system/...`，
 * 而开发期因为前端同源，浏览器看不出异常，一旦部署到 Nginx（也做 /api/ 剥离）
 * 就会直接 404。**后端路由一律不带 /api**，前缀由代理层统一处理。
 * 排查这类问题时：先用 curl 直连 2025 验证后端本身，
 * 再用 curl 打外网验证代理拼接，两个都通才算真的通。
 */
@RestController
@RequestMapping("/system")
public class DatabaseCheckController {

    private final DatabaseCheckService databaseCheckService;

    public DatabaseCheckController(DatabaseCheckService databaseCheckService) {
        this.databaseCheckService = databaseCheckService;
    }

    /**
     * 数据库自检 —— **前端健康检测的唯一入口**。
     *
     * GET /system/db-check    （经前端代理时前端调 /api/system/db-check）
     *
     * 返回 200 = 一切正常；返回 503 = 有问题，body 里有具体原因。
     * 故意用 503 而不是 200 + 布尔值：这样 CI 的 `curl -f` 就能直接判定成败，
     * 前端也不用先拿到 body 再判断状态码 —— axios 默认对非 2xx 抛错，
     * 503 会直接进catch 分支，语义比"200 里塞个 false"更直白。
     *
     * 【为什么同时接受带斜杠和不带斜杠】
     * Spring Boot 3 默认 `setUseTrailingSlashMatch=false`，
     * `/system/db-check` 与 `/system/db-check/` 是两个不同路径，后者 404。
     * 而这类运维探针接口的使用者往往是手敲 curl 或从文档里复制粘贴，
     * 多一个斜杠就排查半天 —— 实测已经因此被误判成「接口没生效」。
     * 探针类接口应该宽容，业务接口才应该严格。
     *
     * 【为什么不再有 summary 版】
     * 原来的 `/db-check/summary` 是为了给高频探针一个轻量返回，
     * 但实测发现它并非真正的轻量 —— 它内部同样调 `service.check()`，
     * 一样要遍历 information_schema，只是最后少吐几个字段。
     * **省下的只是一次 JSON 序列化，不省任何一次数据库查询**，
     * 等于白白多维护一个入口却没换来性能收益。现在统一用这一个。
     *
     * 【返回体结构】完整的自检报告，字段说明见 DatabaseCheckService#check：
     *   connection —— 连接信息（URL 已脱敏，绝不含明文密码）
     *   tables     —— 业务表数量/引擎表数量/清单比对结果
     *   flyway     —— 已应用的版本、checksum、是否成功
     *   seedData   —— 种子数据条数（验证 V2 是否完整执行）
     *   errors     —— 空数组表示一切正常，非空时每一条都是一句人话结论
     *   healthy    —— 上述 errors 是否为空，前端直接读这个字段即可
     */
    @GetMapping({"/db-check", "/db-check/"})
    public ResponseEntity<Map<String, Object>> dbCheck() {
        Map<String, Object> report = databaseCheckService.check();
        boolean healthy = Boolean.TRUE.equals(report.get("healthy"));
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(report);
    }
}
