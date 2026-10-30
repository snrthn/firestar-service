package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.InvoiceAllocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * V1 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。
 *
 * <p>例外：selectMaxDailySeq 用 @Select 是必要的——它必须"看到逻辑删除行"（同 InvoiceMapper 范式）。
 * 背景（2026-10-31 实锤）：uk_tenant_allocation_no(tenant_id, allocation_no) 唯一键不区分
 * is_deleted，软删行仍物理占用唯一键；而 BaseMapper 的 selectCount 会被 MP 逻辑删除拦截器
 * 自动追加 is_deleted=0。于是「发票编辑时软删旧分配 → 重新生成单号」会把已删号当成可用号
 * 复用 → Duplicate entry '0-IA-yyyyMMdd-001' → 编辑已驳回发票必然失败且事务回滚后每次重试都撞。
 * @Select 手写 SQL 不经过 CRUD 模板注入，天然含已删行，取 MAX+1 永不复用。
 * 注意：不要加 @InterceptorIgnore(logicDelete=...)——项目 MP 3.5.7 无该属性，会编译失败。
 */
@Mapper
public interface InvoiceAllocationMapper extends BaseMapper<InvoiceAllocation> {

    /**
     * 取指定日期当天所有核销明细（含逻辑删除）中的最大序号数字。
     * allocation_no 形如 IA-yyyyMMdd-NNN，序号取最后一个 '-' 之后部分。
     * 无记录时返回 0，调用方 +1 即为下一个序号。
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(allocation_no, '-', -1) AS UNSIGNED)), 0) " +
            "FROM fin_invoice_allocation WHERE allocation_no LIKE CONCAT('IA-', #{date}, '-%')")
    Integer selectMaxDailySeq(@Param("date") String date);
}
