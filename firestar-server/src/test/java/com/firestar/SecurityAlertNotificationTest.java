package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.LoginFail;
import com.firestar.entity.Notification;
import com.firestar.entity.User;
import com.firestar.mapper.LoginFailMapper;
import com.firestar.mapper.NotificationMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.LoginProtectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 安全告警「并入通知中心铃铛」验证（Q2 闭环收尾）。
 *
 * <p>设计：锁定 / 解锁事件不再裸广播无人接的 SECURITY_ALERT，而是经 NotificationService#publish
 * 落 sys_notification（接收人 = 全体 ROLE_ADMIN，种子 V4 已建 admin/id=1 绑 ROLE_ADMIN），
 * 并由 NOTIFY 广播驱动前端铃铛 + 右下角轻提醒。
 *
 * <p>本测试断言：触发锁定 / 解锁后，管理员（id=1）确实收到对应通知记录——证明告警有消费端、
 * 闭环闭合。连 firestar_test 集成库（@ActiveProfiles("it")），阈值取测试友好值 max-retry=3。
 */
@SpringBootTest
@ActiveProfiles("it")
class SecurityAlertNotificationTest {

    @Autowired
    private LoginProtectService protectService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private LoginFailMapper loginFailMapper;
    @Autowired
    private NotificationMapper notificationMapper;

    private static final String PWD = "Bf@123456";
    private final List<Long> createdIds = new ArrayList<>();
    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String u : createdUsernames) {
            loginFailMapper.delete(new LambdaQueryWrapper<LoginFail>().eq(LoginFail::getUsername, u));
            // 清理本测试产生的通知（按内容含测试用户名定位，避免误删种子通知）
            notificationMapper.delete(new LambdaQueryWrapper<Notification>().like(Notification::getContent, u));
        }
        createdIds.forEach(userMapper::deleteById);
        createdIds.clear();
        createdUsernames.clear();
    }

    private String createUser() {
        String username = "alert_" + System.nanoTime() + "_" + createdIds.size();
        User u = new User();
        u.setUsername(username);
        u.setPassword(new BCryptPasswordEncoder().encode(PWD));
        u.setNickname("alert");
        u.setStatus("ENABLED");
        userMapper.insert(u);
        createdIds.add(u.getId());
        createdUsernames.add(username);
        return username;
    }

    @Test
    @DisplayName("Q2：账号锁定后，管理员收到「账号锁定安全告警」通知（铃铛消费端）")
    void lockNotifiesAdmin() {
        String u = createUser();
        Long uid = createdIds.get(createdIds.size() - 1);
        for (int i = 0; i < 3; i++) {
            protectService.onLoginFail(uid, u, "127.0.0.1", "ua");
        }
        long n = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, 1L)
                .like(Notification::getContent, u));
        assertEquals(1, n, "锁定事件应向管理员(id=1)推送一条含该账号的安全通知");
    }

    @Test
    @DisplayName("Q2：管理员手动解锁后，管理员收到「账号解锁通知」通知")
    void unlockNotifiesAdmin() {
        String u = createUser();
        Long uid = createdIds.get(createdIds.size() - 1);
        for (int i = 0; i < 3; i++) {
            protectService.onLoginFail(uid, u, "127.0.0.1", "ua");
        }
        protectService.unlock(u, "admin");
        long n = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, 1L)
                .like(Notification::getContent, u)
                .like(Notification::getTitle, "解锁"));
        assertEquals(1, n, "解锁事件应向管理员(id=1)推送一条「解锁」标题的通知");
    }
}
