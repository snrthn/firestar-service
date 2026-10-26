package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Goods;
import org.apache.ibatis.annotations.Mapper;

/**
 * 货物主表 Mapper（V71）。
 *
 * <p>同 CustomerMapper 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。</p>
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {
}
