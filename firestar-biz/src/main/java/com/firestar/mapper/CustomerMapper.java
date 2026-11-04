package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Customer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户主表 Mapper（V68）。
 *
 * <p>同 ItemMapper 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。</p>
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
