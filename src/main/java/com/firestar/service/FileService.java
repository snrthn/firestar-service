package com.firestar.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.dto.FileDownloadLogVO;
import com.firestar.dto.FileVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件业务编排：校验 → 落存储 → 记 DB → 签发访问 URL。
 *
 * <p>与 {@link FileStorageService} 的分工：
 * 本接口管「业务规则」（谁传的、属于哪个业务、能不能下、计数与留痕），
 * FileStorageService 只管「字节怎么存进去、URL 怎么签出来」。
 */
public interface FileService {

    /**
     * 上传文件（服务端中转）。
     *
     * @param file     上传的文件
     * @param bizType  关联业务类型（invoice/receipt 等，为空归到 common）
     * @param bizId    关联业务 ID，可空
     * @param fileType 文件类型（字典 file_type），为空用 ATTACHMENT
     */
    FileVO upload(MultipartFile file, String bizType, Long bizId, String fileType);

    /** 文件分页列表（默认只含 NORMAL，已删除的不出现在列表中）。 */
    IPage<FileVO> list(Integer pageNum, Integer pageSize, String bizType, String fileType, String keyword);

    /** 文件详情（不返回内容，只返回元信息）。 */
    FileVO detail(Long id);

    /**
     * 生成访问地址：预览传 preview=true（inline），下载传 false（attachment）。
     * 同时累加下载次数并写入下载记录（红线 5：谁下载了什么、什么时间）。
     */
    String accessUrl(Long id, boolean preview, HttpServletRequest request);

    /**
     * 删除文件。按项目分层约定（sys_ 表不带逻辑删除、靠 status 停用），
     * 这里只把 status 标为 DELETED，DB 记录与 OSS 对象均保留 —— 财务凭证有合规保留期，
     * 物理清理交给后续归档任务执行。
     */
    void delete(Long id);

    /** 某文件的下载记录分页列表。 */
    IPage<FileDownloadLogVO> downloadLogs(Long fileId, Integer pageNum, Integer pageSize);

    /**
     * 由后端代理输出文件内容（仅本地存储模式需要）。
     * OSS 模式请走 {@link #accessUrl} 的签名 URL，不要让文件流经应用服务器 ——
     * 跨地域中转会把双重流量费和 ECS 出网带宽的问题重新引入。
     */
    void stream(Long id, boolean preview, HttpServletResponse response);
}
