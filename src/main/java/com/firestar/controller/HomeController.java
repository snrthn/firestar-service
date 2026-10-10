package com.firestar.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目信息 / 冒烟探测接口。
 * 根路径 / 返回项目基本描述，避免默认 Whitelabel Error Page；
 * 另保留 /ping 作为最轻量的存活探测。
 * 后续按业务补齐领域 Controller（报销 / 付款 / 发票 / 审批流 等）。
 */
@RestController
public class HomeController {

    @Value("${spring.application.name:firestar-service}")
    private String appName;

    @Value("${firestar.version:0.0.1}")
    private String version;

    @Value("${firestar.description:火星财务管理系统 —— 面向中小企业的云端财务 SaaS，涵盖报销、付款、发票与审批流。}")
    private String description;

    /** 根路径：返回项目基本描述信息（取代默认 Whitelabel Error Page） */
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", appName);
        m.put("name", "火星财务管理系统");
        m.put("description", description);
        m.put("version", version);
        m.put("timestamp", System.currentTimeMillis());
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("ping", "/ping");
        endpoints.put("health", "/actuator/health");
        endpoints.put("info", "/actuator/info");
        // 注意：这里列的是**后端实际路径（不含 /api）**。
        // 前端经 vite 代理访问时要拼上 /api 前缀，代理会剥掉它再转发过来。
        endpoints.put("dbCheck", "/system/db-check");
        endpoints.put("dbCheckSummary", "/system/db-check/summary");
        m.put("endpoints", endpoints);
        return m;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", appName);
        m.put("status", "UP");
        return m;
    }
}