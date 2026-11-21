package com.firestar.service;

import java.util.Map;

/** 货物主数据服务（V218 导入建档 + 同名 merge 空字段）。 */
public interface GoodsService {

    /**
     * 按名称建档 / 同名 merge 空字段。
     *
     * @param name       货物名称（必填）
     * @param fields     其他字段（key 为 Goods 属性名）
     * @param mergeEmpty true=同名已存在时只补空字段
     * @param source     建档来源备注
     * @return 货物 ID
     */
    Long upsert(String name, Map<String, String> fields, boolean mergeEmpty, String source);
}
