package com.firestar.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.FileDownloadLogVO;
import com.firestar.dto.FileVO;
import com.firestar.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件管理接口（前端 /api/files/**，代理剥离 /api 后到 /files/**）。
 *
 * <p>路径与权限对齐设计文档「文件管理 5 个 API」的既有规划，不另起炉灶。
 *
 * <p>【权限口径】类级 file:view（读），写操作（上传 / 删除）在方法上收紧为 file:manage。
 * 按 PermissionInterceptor 的规则，方法注解覆盖类注解。
 *
 * <p>【下载为什么返回 URL 而不是文件流】
 * Bucket 在北京、ECS 在张家口（跨地域），服务端中转下载要计两次流出费且占 ECS 出网带宽。
 * 因此 download / preview 只返回后端签发的短时效签名 URL，由浏览器直连 OSS。
 * 仅本地存储模式（dev）没有签名能力，才走 /{id}/raw 由后端代理输出。
 */
@RestController
@RequirePerm("file:view")
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** 上传文件（multipart/form-data）。服务端中转，见 FileServiceImpl 注释。 */
    @PostMapping("/upload")
    @RequirePerm("file:manage")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String bizType,
                                 @RequestParam(required = false) Long bizId,
                                 @RequestParam(required = false) String fileType,
                                 @RequestParam(required = false) String alias) {
        return Result.success(fileService.upload(file, bizType, bizId, fileType, alias));
    }

    /** 文件分页列表（默认每页 10 条，与前端 el-pagination 默认规格一致）。 */
    @GetMapping("/list")
    public Result<IPage<FileVO>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                      @RequestParam(required = false) String bizType,
                                      @RequestParam(required = false) String fileType,
                                      @RequestParam(required = false) String keyword) {
        return Result.success(fileService.list(pageNum, pageSize, bizType, fileType, keyword));
    }

    /** 获取文件信息（不返回内容）。 */
    @GetMapping("/{id}")
    public Result<FileVO> detail(@PathVariable Long id) {
        return Result.success(fileService.detail(id));
    }

    /** 下载：返回带时效的签名 URL，并记录下载日志（红线 5）。 */
    @GetMapping("/{id}/download")
    public Result<String> download(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(fileService.accessUrl(id, false, request));
    }

    /**
     * 在线预览：与下载的区别只是 Content-Disposition 为 inline。
     * ⚠️ 依赖已备案自定义域名 oss.snrthn.com —— 通过 OSS 默认域名访问会被浏览器
     * 强制下载而非预览（2025-03 起新 Bucket 还不允许用默认外网域名调数据类 API）。
     * 仅 PDF / 图片可预览，Office 文档需额外转码服务，当前走下载。
     */
    @GetMapping("/{id}/preview")
    public Result<String> preview(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(fileService.accessUrl(id, true, request));
    }

    /**
     * 删除文件：只把 status 标为 DELETED，DB 记录与 OSS 对象都保留（财务凭证合规保留期）。
     * 接口文档口径为「仅管理员」，故收紧到 file:manage。
     */
    @DeleteMapping("/{id}")
    @RequirePerm("file:manage")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.success();
    }

    /** 更新文件别名（V23；文件管理处维护，仅展示/审计用；传空串清空回退 originalName）。 */
    @PutMapping("/{id}/alias")
    @RequirePerm("file:manage")
    public Result<FileVO> updateAlias(@PathVariable Long id, @RequestParam String alias) {
        return Result.success(fileService.updateAlias(id, alias));
    }

    /** 文件下载记录列表。 */
    @GetMapping("/{id}/download-logs")
    public Result<IPage<FileDownloadLogVO>> downloadLogs(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "1") Integer pageNum,
                                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                                         @RequestParam(required = false) String keyword) {
        return Result.success(fileService.downloadLogs(id, pageNum, pageSize, keyword));
    }

    /** 下载记录全量分页（供「下载记录」菜单独立查询，不按文件过滤）。 */
    @GetMapping("/download-logs")
    public Result<IPage<FileDownloadLogVO>> allDownloadLogs(@RequestParam(defaultValue = "1") Integer pageNum,
                                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                                            @RequestParam(required = false) String keyword) {
        return Result.success(fileService.downloadLogs(null, pageNum, pageSize, keyword));
    }

    /** 本地存储模式下的文件内容输出（OSS 模式不会走到这里）。 */
    @GetMapping("/{id}/raw")
    public void raw(@PathVariable Long id,
                    @RequestParam(defaultValue = "0") int preview,
                    HttpServletResponse response) {
        fileService.stream(id, preview == 1, response);
    }
}
