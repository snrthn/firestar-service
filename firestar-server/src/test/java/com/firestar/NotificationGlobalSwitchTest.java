package com.firestar;

import com.firestar.dto.NotifyCommand;
import com.firestar.entity.Config;
import com.firestar.mapper.ConfigMapper;
import com.firestar.mapper.NotificationMapper;
import com.firestar.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0：通知全局总闸 notify_global_switch 的 fail-close 语义。
 * 核心不变量：开关【缺失 / 不可解析 / false】时 notify 必须安静跳过（不落库），
 * 配置故障场景下「不发」优于「乱发」。
 */
@SpringBootTest
class NotificationGlobalSwitchTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ConfigMapper configMapper;
    @Autowired
    private NotificationMapper notificationMapper;

    private static final String KEY = "notify_global_switch";
    private Long managedConfigId;

    private NotifyCommand sampleCmd() {
        NotifyCommand cmd = new NotifyCommand();
        cmd.setCode("UNIT_TEST_CODE");
        cmd.setReceivers(List.of(1L));
        cmd.setVars(Map.of("x", "1"));
        return cmd;
    }

    @BeforeEach
    void setUp() {
        // 确保存在一条可控的开关配置（字段按 Config 实体内省假设：configKey/configValue/id）
        Config c = new Config();
        c.setConfigKey(KEY);
        c.setConfigValue("false");
        configMapper.insert(c);
        managedConfigId = c.getId();
    }

    @AfterEach
    void tearDown() {
        if (managedConfigId != null) {
            configMapper.deleteById(managedConfigId);
            managedConfigId = null;
        }
    }

    private long countAll() {
        return notificationMapper.selectCount(null);
    }

    @Test
    @DisplayName("P0-总闸关(false)：notify 不落库（fail-close 之「关则静默跳过」）")
    void switchOff_noPersist() {
        configMapper.updateById(withValue("false"));
        long before = countAll();
        notificationService.notify(sampleCmd());
        assertEquals(before, countAll(), "总闸关闭时不应产生任何通知记录");
    }

    @Test
    @DisplayName("P0-总闸缺失：视为关闭，notify 不落库（fail-close 之「配置缺失=关」）")
    void switchMissing_failClose_noPersist() {
        // 临时删掉开关行，模拟「配置缺失」
        configMapper.deleteById(managedConfigId);
        managedConfigId = null;
        long before = countAll();
        notificationService.notify(sampleCmd());
        assertEquals(before, countAll(), "开关缺失必须按关闭处理，不发送");
    }

    @Test
    @DisplayName("P0-总闸不可解析：非 true/1/yes 的值视为关闭，notify 不落库")
    void switchGarbageValue_failClose_noPersist() {
        configMapper.updateById(withValue("maybe"));
        long before = countAll();
        notificationService.notify(sampleCmd());
        assertEquals(before, countAll(), "无法解析为开的脏值必须按关闭处理");
    }

    @Test
    @DisplayName("P0-总闸开(true)：notify 进入后续分支（模板缺失则跳过，属正常，不抛异常）")
    void switchOn_entersPipeline() {
        configMapper.updateById(withValue("true"));
        // 不强制断言落库（取决于 UNIT_TEST_CODE 是否有启用模板）；只验证总闸放行后不抛异常
        assertDoesNotThrow(() -> notificationService.notify(sampleCmd()));
    }

    private Config withValue(String v) {
        Config c = new Config();
        c.setId(managedConfigId);
        c.setConfigValue(v);
        return c;
    }
}
