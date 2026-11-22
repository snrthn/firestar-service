package com.firestar.controller;

import com.firestar.dto.InitStatusVO;
import com.firestar.service.InitResetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 匿名端点 GET /system/init/status 的信息暴露收敛契约（P2 已降级）。
 * 初始化完成后只回 initialized:true，不再回显 trialMode / canReset / adminExists；
 * 未初始化（安装向导阶段）仍回显完整状态供前端判断流程。
 */
@ExtendWith(MockitoExtension.class)
class InitStatusExposureTest {

    @Mock
    private InitResetService initResetService;

    @Test
    void initialized_onlyExposesInitialized() {
        InitStatusVO full = new InitStatusVO();
        full.setInitialized(true);
        full.setTrialMode(true);   // 即便底层为 true，匿名端点也不得回显
        full.setCanReset(true);
        full.setAdminExists(true);
        when(initResetService.getStatus()).thenReturn(full);

        InitResetController controller = new InitResetController(initResetService);
        InitStatusVO vo = controller.status().getData();

        assertTrue(vo.isInitialized());
        assertFalse(vo.isTrialMode(), "初始化完成后不得回显 trialMode");
        assertFalse(vo.isCanReset(), "初始化完成后不得回显 canReset");
        assertFalse(vo.isAdminExists(), "初始化完成后不得回显 adminExists");
    }

    @Test
    void notInitialized_returnsFullStatus() {
        InitStatusVO full = new InitStatusVO();
        full.setInitialized(false);
        full.setTrialMode(true);
        full.setAdminExists(false);
        when(initResetService.getStatus()).thenReturn(full);

        InitResetController controller = new InitResetController(initResetService);
        InitStatusVO vo = controller.status().getData();

        assertFalse(vo.isInitialized());
        assertTrue(vo.isTrialMode(), "安装向导阶段仍需回显 trialMode 供前端判断");
    }

    /**
     * 匿名裁剪必须连 lastReset* 一起抹掉：重置历史（谁在什么时候清过库）
     * 属运维敏感信息，匿名端点不得顺带泄露。
     */
    @Test
    void initialized_anonymousEndpoint_doesNotLeakResetHistory() {
        InitStatusVO full = new InitStatusVO();
        full.setInitialized(true);
        full.setLastResetTime("2026-11-22 22:00:00.0");
        full.setLastResetBy("admin");
        when(initResetService.getStatus()).thenReturn(full);

        InitResetController controller = new InitResetController(initResetService);
        InitStatusVO vo = controller.status().getData();

        assertNull(vo.getLastResetTime(), "匿名端点不得回显 lastResetTime");
        assertNull(vo.getLastResetBy(), "匿名端点不得回显 lastResetBy");
    }

    /**
     * 重置页状态端点必须回完整状态（不裁剪）。
     * 此前前端误用匿名端点，canReset 恒 false → 两个重置按钮永久 disabled。
     */
    @Test
    void resetStatus_returnsUncroppedStatus() {
        InitStatusVO full = new InitStatusVO();
        full.setInitialized(true);
        full.setTrialMode(true);
        full.setCanReset(true);
        full.setAdminExists(true);
        full.setLastResetTime("2026-11-22 22:00:00.0");
        full.setLastResetBy("admin");
        when(initResetService.getResetStatus()).thenReturn(full);

        InitResetController controller = new InitResetController(initResetService);
        InitStatusVO vo = controller.resetStatus().getData();

        assertTrue(vo.isTrialMode(), "重置页必须拿到 trialMode");
        assertTrue(vo.isCanReset(), "重置页必须拿到 canReset，否则按钮永久 disabled");
        assertTrue(vo.isAdminExists());
        assertEquals("admin", vo.getLastResetBy());
    }
}
