package com.firestar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录结果字典「可消费性」验证（修复 V202 状态值 bug 的回归测试）。
 *
 * <p>背景：前端 useDict.ts:73 消费过滤只认 status==='ENABLED'。V202 初版误写 '启用'（中文），
 *   dev / 测试存量行甚至为 'DISABLED'，导致 LOCKED / UNLOCKED 被过滤、前端 fallback 显示原始枚举。
 *   此前字典计数测试只验数量（148→150）不验状态，82/82 绿里藏着这个漏洞。
 *
 * <p>本测试直接查 sys_dict_data，断言 login_status 下 LOCKED / UNLOCKED 两条的 status 必须为
 *   'ENABLED'（V202 种子修正 + V205 纠正迁移双保险），从根上堵住「字典写了却消费不到」的假绿。
 */
@SpringBootTest
@ActiveProfiles("it")
class LoginStatusDictEnabledTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("login_status 的 LOCKED / UNLOCKED 字典项状态必须为 ENABLED（否则前端消费不到）")
    void lockedAndUnlockedMustBeEnabled() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dict_value, status FROM sys_dict_data " +
                "WHERE type_code = 'login_status' AND dict_value IN ('LOCKED','UNLOCKED')");
        assertEquals(2, rows.size(), "login_status 下应存在 LOCKED / UNLOCKED 两条字典数据");
        for (Map<String, Object> r : rows) {
            String value = String.valueOf(r.get("dict_value"));
            String status = String.valueOf(r.get("status"));
            assertEquals("ENABLED", status,
                    "字典项 " + value + " 的 status 必须为 ENABLED（useDict 消费过滤只认 ENABLED），实际为 " + status);
        }
    }

    @Test
    @DisplayName("login_status 全部 5 个枚举值均应为 ENABLED（任一停用都会在结果列 fallback 原始值）")
    void allLoginStatusEnabled() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dict_value, status FROM sys_dict_data WHERE type_code = 'login_status'");
        assertTrue(rows.size() >= 5, "login_status 至少应有 5 个枚举值");
        for (Map<String, Object> r : rows) {
            String value = String.valueOf(r.get("dict_value"));
            String status = String.valueOf(r.get("status"));
            assertEquals("ENABLED", status, "字典项 " + value + " 不应被停用，实际 status=" + status);
        }
    }
}
