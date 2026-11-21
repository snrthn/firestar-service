package com.firestar.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.firestar.entity.ImportBatch;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导入批次 Mapper（V218）。
 *
 * <p>一律继承 BaseMapper，零手写 SQL（与 CustomerMapper 同纪律）。
 * 「取同 key 处理中批次」用 LambdaQueryWrapper + uk_active 唯一索引保证。</p>
 */
@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {
}
