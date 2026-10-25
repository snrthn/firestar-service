package com.firestar.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 客户主表（V68）—— 看板「客户维度」的唯一事实源。
 *
 * <p><b>为什么要有这张表：</b>V1 baseline 里客户只是 fin_project 上的一个自由文本字段
 * （当时 V1 注释白纸黑字写「不拆客户表，见方案 §1.3」）。文本客户名意味着
 * 「同一家公司写了两个名字」在看板里就会裂成两个主体，应收/回款/账龄的客户排名
 * 长期不可靠。V68 给它建立了可以被外键引用的实体。</p>
 *
 * <p><b>与项目表的关系（虎哥 2026-10-26 拍板）：</b>
 * <ul>
 *   <li>{@code fin_project.customer_id} → 本表 id，<b>0 = 未关联</b>；</li>
 *   <li>{@code fin_project.customer_name} <b>保留为快照冗余</b>，不移除、不弃用：
 *       项目单据上的客户名是「当时叫什么」的历史事实，客户主表改名不应回过头篡改历史单据；
 *       列表/详情也可继续读它免联表，看板统计则一律按 customer_id 聚合。</li>
 * </ul></p>
 *
 * <p><b>一期范围：</b>只做主表 + 回填 + 项目关联下拉，不做独立 CRUD 页面/菜单/权限点。
 * 建档入口只有两个：V68 迁移的历史回填，以及立项/编辑项目时的「按名称 upsert」
 * （见 ProjectServiceImpl.resolveCustomer）。</p>
 *
 * <p><b>编号：</b>customerNo = {@code CUS-{yyyyMMdd}-{id 四位补齐}}，与项目号
 * {@code PJ-{yyyyMMdd}-{id 四位}} 完全同构（虎哥决策#3：沿用项目号的近似格式）。
 * 编号在 INSERT 之后用 UPDATE 生成，因为要带自增 id。</p>
 *
 * 【状态字段纪律】status 一律 String 接字典 common_status（ENABLED/DISABLED），
 * 不写 Java enum —— 字典加值不该要求改代码重新发版（同 Project 实体注释）。
 */
@Getter
@Setter
@TableName(value = "fin_customer", autoResultMap = true)
public class Customer extends LogicDeleteEntity {

    private static final long serialVersionUID = 1L;

    /** 客户编号（CUS-{yyyyMMdd}-{id 四位补齐}，插入后再生成，因编号里要带自增 id） */
    private String customerNo;

    /** 客户名称（业务唯一键，同租户内唯一） */
    private String customerName;

    /** 联系人 */
    private String contactPerson;

    /** 联系电话 */
    private String contactPhone;

    /** 地址 */
    private String address;

    /** 纳税人识别号 */
    private String taxNo;

    /** 开户银行 */
    private String bankName;

    /** 银行账号 */
    private String bankAccount;

    /** 状态（字典 common_status：ENABLED/DISABLED） */
    private String status;

    /** 备注 */
    private String remark;
}
