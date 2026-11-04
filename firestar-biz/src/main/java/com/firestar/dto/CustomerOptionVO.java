package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 客户下拉选项（V68）—— 立项/编辑项目表单里「客户名称」下拉的数据源。
 *
 * <p>刻意做成瘦 VO 而不是直接吐 Customer 实体：只给前端够用的三个字段，
 * 避免把税号、银行账号这类敏感/无关字段推到列表页面上。</p>
 */
@Getter
@Setter
public class CustomerOptionVO {

    /** 客户 ID → fin_customer.id */
    private Long id;

    /** 客户编号（CUS-yyyyMMdd-NNNN，下拉里做副标题） */
    private String customerNo;

    /** 客户名称（下拉主标题 + 选中后回填为项目的快照 customerName） */
    private String customerName;

    /** 联系电话（选填，选中后可用于预填，当前表单未用到，留着给后续需要） */
    private String contactPhone;
}
