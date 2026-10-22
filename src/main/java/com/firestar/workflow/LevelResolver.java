package com.firestar.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 职级 → 审批人解析（Step 3 会签语义：同职级全员，每人一个并行任务，全部通过才算过）。
 *
 * <p>generateBpmn 的 LEVEL 分支把节点职级编码（{@code NodeCfg.assignee}，如 "L3"）内联进 UEL 表达式：
 * <pre>  ${levelResolver.resolveList('L3')}  </pre>
 * 本 bean 按 {@code level_code} 查 {@code sys_user}，返回该职级全体用户的<b>用户名</b>列表
 * （与 SecurityContext 的 principal = username 保持一致，保证后续待办查询能按当前登录用户名命中）。</p>
 *
 * <p>空职级 / 该职级无人在岗 → 返回空列表；多实例 0 元素会直接跳过该节点，不会卡死流程。</p>
 */
@Slf4j
@Component("levelResolver")
public class LevelResolver {

    private final UserMapper userMapper;

    public LevelResolver(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 按职级编码解析出审批人用户名列表（会签：同职级全员候选） */
    public List<String> resolveList(String levelCode) {
        if (levelCode == null || levelCode.isBlank()) {
            log.warn("[levelResolver] 节点未配置职级编码，返回空列表（节点将自动跳过）");
            return List.of();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getLevelCode, levelCode.trim())
                .eq(User::getStatus, "ENABLED"));
        List<String> usernames = users.stream()
                .map(User::getUsername)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toList());
        log.info("[levelResolver] 职级 {} 解析到 {} 名审批人: {}", levelCode, usernames.size(), usernames);
        return usernames;
    }
}
