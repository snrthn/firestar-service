package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 由 V1__baseline.sql 自动生成。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。
 */
@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {
}
