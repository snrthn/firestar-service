package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 货物主表（V71）—— 看板「货物维度」的唯一事实源（镜像 fin_customer）。
 *
 * <p><b>为什么要有这张表：</b>V1 baseline 里货物只是 fin_item 上的一个自由文本字段
 * （fin_item 是「按项目挂的货物行」，每行带 project_id + 规格/单位/数量/单价）。
 * 文本货物名意味着「同一货物写了两个名字」在看板里会裂成两个主体，无法做按货物维度的
 * 应收/回款/账龄排名。V71 给它建立了可以被外键引用的实体（与 V68 客户主表同范式）。</p>
 *
 * <p><b>与货物行的关系（虎哥 2026-10-26 拍板「B 建货物主表，像客户」）：</b>
 * <ul>
 *   <li>{@code fin_item.goods_id} → 本表 id，<b>0 = 未关联</b>；</li>
 *   <li>本表<b>仅存货物名称</b>（全局唯一）作为去重事实源；规格/单位是合同专属，
 *       留在 fin_item 行上，选中货物只带出名称，不进主表。</li>
 * </ul></p>
 *
 * <p><b>一期范围：</b>只做主表 + 回填 + 项目货物行关联下拉，不做独立 CRUD 页面/菜单/权限点。
 * 建档入口只有两个：V71 迁移的历史回填，以及立项/编辑项目时的「按名称 upsert」
 * （见 ProjectServiceImpl.persistItems 内的 resolveGoods）。</p>
 *
 * <p><b>编号：</b>goodsNo = {@code GOODS-{yyyyMMdd}-{id 四位补齐}}，与项目号
 * {@code PJ-} / 客户号 {@code CUS-} 完全同构（沿用 V68 决策#3 的近似格式）。</p>
 *
 * 【状态字段纪律】status 一律 String 接字典 common_status（ENABLED/DISABLED），
 * 不写 Java enum —— 字典加值不该要求改代码重新发版（同 Customer/Project 实体注释）。
 */
@Getter
@Setter
@TableName(value = "fin_goods", autoResultMap = true)
public class Goods extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 货物编号（GOODS-{yyyyMMdd}-{id 四位补齐}，插入后再生成，因编号里要带自增 id） */
    private String goodsNo;

    /** 货物名称（业务唯一键，同租户内唯一；去重事实源） */
    private String goodsName;

    /** 状态（字典 common_status：ENABLED/DISABLED） */
    private String status;

    /** 备注 */
    private String remark;
}
