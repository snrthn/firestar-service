package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.dto.WriteOffLedgerVO;
import com.firestar.entity.WriteOff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
