package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 带逻辑删除的实体基类 —— 仅覆盖 V1 基线中 9 张带 is_deleted 的表。
 *
 * 分层口径见 docs/建表方案.md §6：
 *   一层 不可删只能红冲/反核销：fin_receivable / fin_receipt / fin_invoice /
 *                              fin_write_off / fin_return_order
 *   二层 可删但留痕：fin_project / fin_item / fin_shipment / fin_shipment_line
 *   三层 不带逻辑删除，靠 status 停用：16 张 sys_ 表（含 RBAC / 审计日志 / 文件下载记录）
 *
 * 【关键行为】@TableLogic 让 MP 拦截器在查询时自动追加 is_deleted = 0，
 * delete 时改写为 UPDATE ... SET is_deleted = 1。
 * 也就是说：**裸 SQL 仍能看到软删数据，应用层查不到**——
 * 这不是 bug，是设计。之前用裸 SQL 验证"软删后查不到"会误判。
 *
 * 副作用提醒：软删行仍在表里，若该表有业务唯一索引（如 fin_project.project_no），
 * 同一业务键第二次插入会撞 DuplicateKey。业务侧要提示"该编号已被删除"，
 * 或由业务层恢复原行而不是新建。
 *
 * 【为什么金额索引首列必带 is_deleted】正是为了让拦截器追加的条件走索引。
 */
@Getter
@Setter
public abstract class LogicDeleteEntity extends AuditedEntity {

    private static final long serialVersionUID = 1L;

    /** 逻辑删除标记：0 未删除，1 已删除 */
    @TableLogic
    private Integer isDeleted;

    /** 删除时间 */
    private LocalDateTime deletedAt;

    /** 删除人 ID */
    private Long deletedBy;
}
