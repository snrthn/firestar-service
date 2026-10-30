package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 工作台平台指标（与经营类看板指标解耦，单独端点 /stats/platform）。
 *
 * <p>口径：
 * <ul>
 *   <li>userCount：sys_user 中 status='ENABLED' 的活跃用户数（MP 自动追加逻辑删除过滤）；</li>
 *   <li>todayLoginCount：sys_login_log 中 login_status='SUCCESS' 且 login_at 落在今日 00:00 之后的登录次数；</li>
 *   <li>historyVisitCount：sys_login_log 中 login_status='SUCCESS' 的累计登录次数（历史访问总数）。</li>
 * </ul>
 */
@Getter
@Setter
public class PlatformStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 平台活跃用户总数 */
    private Long userCount;

    /** 今日登录次数（SUCCESS） */
    private Long todayLoginCount;

    /** 历史访问总数（累计 SUCCESS 登录次数） */
    private Long historyVisitCount;
}
