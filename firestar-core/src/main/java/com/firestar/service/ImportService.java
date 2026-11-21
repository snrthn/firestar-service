package com.firestar.service;

import com.firestar.entity.ImportBatch;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 历史数据导入服务接口（方案 v3）。
 */
public interface ImportService {

    /** 取 Handler（不存在抛 BizException） */
    ImportHandler requireHandler(String key);

    /** 所有已注册导入类型（前端下拉） */
    List<Map<String, Object>> listKeys();

    /**
     * 提交导入（立即返回批次号，后台异步跑）。
     *
     * @param key           导入类型
     * @param file          原始文件（用于算 SHA256 / 文件名）
     * @param fileStorageId 已上传到 FileStorage 的文件 ID（biz_type=import）
     * @param operatorId    提交人
     * @param parentBatchNo 修正批次指向的原批次号（可为 null）
     * @return 批次号
     */
    String submit(String key, MultipartFile file, Long fileStorageId, Long operatorId, String parentBatchNo);

    /** 批次详情 */
    ImportBatch detail(Long id);

    /** 导入历史（可按类型 / 操作人过滤） */
    List<ImportBatch> history(String importKey, Long operatorId, Integer limit);

    /** 生成错误回执 xlsx（仅 FAILED 批次） */
    byte[] errorWorkbook(Long id);

    /** 生成空模板 xlsx（代码现场生成，不落盘） */
    byte[] template(String key);

    /** 归档批次（管理员） */
    void archive(Long id, Long operatorId);
}
