package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.dto.WriteOffLedgerVO;
import com.firestar.entity.WriteOff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 由 V1__baseline.sql 自动生成。V1 纪律：一律继承 BaseMapper，零手写 SQL。
 * 需要非 CRUD 查询时另建 XML，勿用注解拼 SQL（拦截器改写会失效）。
 *
 * <p>P0-2（2026-10-27）：台账分页破例手写 XML（WriteOffMapper.xml）。
 * 原因：核销台账需跨 fin_write_off / fin_invoice_allocation 两表 UNION 后统一分页，
 * 原实现是两张表全量 selectList 进内存再 subList 分页，台账是只增不减的事实流水，
 * 数据量增长后必然 OOM。财务查询性能必须随数据量线性可控，这一条值得破一次纪律。</p>
 */
@Mapper
public interface WriteOffMapper extends BaseMapper<WriteOff> {

    /** P0-2：核销台账分页明细（UNION ALL + 数据库分页，替代原内存全量分页） */
    List<WriteOffLedgerVO> listLedger(@Param("projectId") Long projectId,
                                      @Param("ledgerType") String ledgerType,
                                      @Param("status") String status,
                                      @Param("keyword") String keyword,
                                      @Param("dateFrom") LocalDate dateFrom,
                                      @Param("dateTo") LocalDate dateTo,
                                      @Param("offset") long offset,
                                      @Param("limit") long limit);

    /** P0-2：核销台账分页总数 */
    long countLedger(@Param("projectId") Long projectId,
                     @Param("ledgerType") String ledgerType,
                     @Param("status") String status,
                     @Param("keyword") String keyword,
                     @Param("dateFrom") LocalDate dateFrom,
                     @Param("dateTo") LocalDate dateTo);

    /**
     * 取指定日期当天所有核销单（含逻辑删除）中的最大序号数字。
     * write_off_no 形如 WO-yyyyMMdd-NNN，序号取最后一个 '-' 之后部分。
     * 无记录时返回 0，调用方 +1 即为下一个序号。
     *
     * <p>为何用 @Select 原生 SQL（同 InvoiceMapper / InvoiceAllocationMapper 范式）：
     * uk_tenant_write_off_no(tenant_id, write_off_no) 唯一键不区分 is_deleted，软删行仍物理占键；
     * BaseMapper.selectCount 会被逻辑删除拦截器追加 is_deleted=0，导致复用以删号撞唯一键。
     * 收款单 update/withdraw 同样是「软删旧核销行 → 重新生成单号」，与原发票同款故障面。</p>
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(write_off_no, '-', -1) AS UNSIGNED)), 0) " +
            "FROM fin_write_off WHERE write_off_no LIKE CONCAT('WO-', #{date}, '-%')")
    Integer selectMaxDailySeq(@Param("date") String date);
}
