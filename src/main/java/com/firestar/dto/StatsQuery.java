package com.firestar.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 看板统计统一查询参数（L1 只读统计层）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>时间切面：{@code periodPreset} 与 {@code periodStart/periodEnd} 二选一，预设优先；
 *       预设取值 THIS_MONTH / THIS_QUARTER / THIS_YEAR / LAST_12_MONTH / ALL。</li>
 *   <li>主体切面：{@code projectId} 单选；{@code customerId}/{@code managerId} 下推到
 *       fin_project 收敛出 projectId 集合后再下推到各业务表（见 StatsServiceImpl）。</li>
 *   <li>本对象只描述"查什么"，不描述"怎么算"——口径契约以 docs/数据看板-指标体系设计.md 为准。</li>
 * </ul>
 */
@Data
public class StatsQuery {

    /** 期间起（含） */
    private LocalDate periodStart;

    /** 期间止（含） */
    private LocalDate periodEnd;

    /** 期间预设：THIS_MONTH / THIS_QUARTER / THIS_YEAR / LAST_12_MONTH / ALL（或 null） */
    private String periodPreset;

    /** 项目过滤（单选） */
    private Long projectId;

    /** 客户过滤（V68 主表 id；null 表示全部客户） */
    private Long customerId;

    /** 销售经理过滤（sys_user.id；null 表示全部） */
    private Long managerId;
}
