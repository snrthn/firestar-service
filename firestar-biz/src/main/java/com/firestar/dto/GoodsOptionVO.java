package com.firestar.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 货物下拉选项（V71）—— 立项/编辑项目表单里「货物名称」下拉的数据源（镜像 CustomerOptionVO）。
 *
 * <p>瘦 VO：只给前端够用的三个字段（id / goodsNo / goodsName），不把无关字段推到页面。</p>
 */
@Getter
@Setter
public class GoodsOptionVO {

    /** 货物 ID → fin_goods.id */
    private Long id;

    /** 货物编号（GOODS-yyyyMMdd-NNNN，下拉里做副标题） */
    private String goodsNo;

    /** 货物名称（下拉主标题 + 选中后回填为货物行的 itemName） */
    private String goodsName;
}
