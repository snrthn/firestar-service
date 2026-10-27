package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Receipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 由 V1__baseline.sql 自动生成。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。
 *
 * 例外：selectMaxDailySeq 用 @Select 是必要的——它必须"看到逻辑删除行"。
 * 背景：uk_tenant_receipt_no(tenant_id, receipt_no) 唯一键不区分 is_deleted，
 * 被逻辑删除的单号仍物理占用唯一键。若生成器只看 is_deleted=0 的行
 * （BaseMapper 自动 CRUD 模板默认行为），会复用已删单号 → 撞唯一键 → 500。
 *
 * 实现：MP 的逻辑删除过滤（is_deleted=0）是拼在【自动注入的 CRUD SQL 模板】里的，
 * @Select 手写 SQL 不经过模板注入、不会被追加该条件，天然含已删行。
 * 注意：不要画蛇添足加 @InterceptorIgnore(logicDelete=...)——项目 MP 3.5.7 的
 * 该注解没有 logicDelete 属性，会导致编译失败、服务起不来（2026-10-27 实锤）。
 */
@Mapper
public interface ReceiptMapper extends BaseMapper<Receipt> {

    /**
     * 取指定日期当天所有收款单（含逻辑删除）中的最大序号数字。
     * receipt_no 形如 SK-yyyyMMdd-NNN，序号取最后一个 '-' 之后部分。
     * 无记录时返回 0，调用方 +1 即为下一个序号。
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(receipt_no, '-', -1) AS UNSIGNED)), 0) " +
            "FROM fin_receipt WHERE receipt_no LIKE CONCAT('SK-', #{date}, '-%')")
    Integer selectMaxDailySeq(@Param("date") String date);
}
