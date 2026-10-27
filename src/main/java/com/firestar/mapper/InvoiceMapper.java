package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 由 V1__baseline.sql 自动生成。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。
 *
 * 例外：selectMaxDailySeq 用 @Select 是必要的——它必须"看到逻辑删除行"。
 * 背景：uk_tenant_invoice_no(tenant_id, invoice_no) 唯一键不区分 is_deleted，
 * 被逻辑删除的发票号仍物理占用唯一键。若生成器只看 is_deleted=0 的行
 * （BaseMapper 自动 CRUD 模板默认行为），会复用已删发票号 → 撞唯一键 → 500。
 *
 * 实现：MP 的逻辑删除过滤（is_deleted=0）拼在【自动注入的 CRUD SQL 模板】里，
 * @Select 手写 SQL 不经过模板注入、不会被追加该条件，天然含已删行。
 * 注意：不要加 @InterceptorIgnore(logicDelete=...)——项目 MP 3.5.7 的该注解
 * 没有 logicDelete 属性，会编译失败、服务起不来（2026-10-27 ReceiptMapper 实锤）。
 */
@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {

    /**
     * 取指定日期当天所有发票（含逻辑删除）中的最大序号数字。
     * invoice_no 形如 FP-yyyyMMdd-NNN，序号取最后一个 '-' 之后部分。
     * 无记录时返回 0，调用方 +1 即为下一个序号。
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(invoice_no, '-', -1) AS UNSIGNED)), 0) " +
            "FROM fin_invoice WHERE invoice_no LIKE CONCAT('FP-', #{date}, '-%')")
    Integer selectMaxDailySeq(@Param("date") String date);

    /**
     * 按发票号查重，用于 create/update 业务层拦截「手填重复票号 → 撞唯一键 500」。
     *
     * <p>为何用 @Select 原生 SQL 而非 BaseMapper.selectOne：唯一键 uk_tenant_invoice_no
     * 不区分逻辑删除（@TableLogic 软删行仍物理占键），若复用已删发票号也会撞键 500；
     * 本查询必须"看到逻辑删除行"，故走 @Select 原生 SQL（同 selectMaxDailySeq 范式），
     * 天然含已删行，不漏判。</p>
     *
     * <p>excludeId：编辑时排除自身（自身 = 没改号，不应误拦）；传 null 即不排除。</p>
     */
    @Select("SELECT id FROM fin_invoice WHERE invoice_no = #{invoiceNo} " +
            "AND (#{excludeId} IS NULL OR id <> #{excludeId}) LIMIT 1")
    Long selectIdByInvoiceNo(@Param("invoiceNo") String invoiceNo, @Param("excludeId") Long excludeId);
}
