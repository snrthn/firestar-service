package com.firestar.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.firestar.entity.User;
import com.firestar.mapper.UserMapper;

/**
 * 行级数据隔离助手（2026-10-24 虎哥统一要求）。
 *
 * <p><b>背景</b>：立项（ProjectServiceImpl）最早落地了「非 ROLE_ADMIN 只看自己发起的」
 * 行级过滤，但发货 / 文件 / 日志等后加的模块没有跟上——发货的 {@code viewAll} 参数
 * 甚至已经从 Controller 传进来了却没人用，等于全员可见。为杜绝「每加一个模块就漏一次」，
 * 把这段判定收成本助手，新模块照抄两行即可。</p>
 *
 * <p><b>范式（三段式，缺一不可）</b>：
 * <ol>
 *   <li>Controller 侧：{@code SecurityContextUtils.currentUsername(), SecurityContextUtils.isAdmin()}
 *       两个实参传给 Service（不要从前端接收 userId/username —— 那是越权入口）。</li>
 *   <li>Service 方法签名：带 {@code String username, boolean viewAll} 两个形参。</li>
 *   <li>查询构造：{@code DataScopeHelper.own(wrapper, Xxx::get归属列, 归属值)}。</li>
 * </ol>
 * </p>
 *
 * <p><b>已落地归属列对照表</b>（新增模块照此选列）：
 * <pre>
 *   Project          applicantUsername  (String, 直接用 username)
 *   Shipment         applicantId        (Long,   用 currentUserId(userMapper))
 *   FileStorage      uploaderId         (Long)
 *   FileDownloadLog  downloaderId       (Long)
 *   LoginLog         username           (String)
 *   AuditLog         operatorId         (Long)
 * </pre>
 * </p>
 *
 * <p><b>边界</b>：列表页按归属列过滤 ≠ 单条详情也要拦。详情/下载这类按 id 直取的接口
 * 若也要隔离，需自行比对归属（参考 FeedbackServiceImpl「创建人/接收人/admin 三者可见，
 * 其余 404」的口径）；本助手只解决<b>列表查询</b>。</p>
 */
public final class DataScopeHelper {

    private DataScopeHelper() {
    }

    /**
     * 非 ROLE_ADMIN 时给 wrapper 追加「归属列 = 归属值」过滤；admin 原样放行（看全量）。
     *
     * <p>归属值为 null 时仍会追加条件 —— MyBatis-Plus 会生成 {@code col = NULL}，
     * 结果集恒空，属于<b>安全失败</b>（宁可查不到，也不放行全量）。
     * 若想显式返回空页、连 SQL 都不发，配合 {@link #denyAll(Object)} 提前 return。</p>
     *
     * @param wrapper    已拼好业务条件的 wrapper
     * @param ownerColumn 归属列（如 {@code Shipment::getApplicantId}）
     * @param ownerValue  归属值（当前用户名或当前用户 id）
     * @return 同一个 wrapper（便于链式调用）
     */
    public static <T> LambdaQueryWrapper<T> own(LambdaQueryWrapper<T> wrapper,
                                                SFunction<T, ?> ownerColumn,
                                                Object ownerValue) {
        if (SecurityContextUtils.isAdmin()) {
            return wrapper;
        }
        return wrapper.eq(ownerColumn, ownerValue);
    }

    /**
     * 非 ROLE_ADMIN 且归属值缺失 → true，调用方应直接返回空页。
     *
     * <p>典型场景：当前用户在 user 表里查不到 id（脏数据 / 已删用户）。此时不能因为
     * 「不知道是谁」就放行全量，必须按「谁都看不见」处理。</p>
     */
    public static boolean denyAll(Object ownerValue) {
        return !SecurityContextUtils.isAdmin() && ownerValue == null;
    }

    /**
     * 当前登录用户的 id（归属列是 {@code xxxId} 时用）。
     *
     * <p>收在这里是为了避免每个 ServiceImpl 各抄一份 {@code currentUserId()} 私有方法
     * （此前 ProjectServiceImpl / ShipmentServiceImpl 已经各有一份，再加文件、日志就是四份）。</p>
     *
     * @return 用户 id；查不到返回 null（配合 {@link #denyAll(Object)} 使用）
     */
    public static Long currentUserId(UserMapper userMapper) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, SecurityContextUtils.currentUsername())
                .select(User::getId));
        return u == null ? null : u.getId();
    }
}
