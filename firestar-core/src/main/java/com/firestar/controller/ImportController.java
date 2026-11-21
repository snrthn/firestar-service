package com.firestar.controller;

import com.firestar.common.DataScopeHelper;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import com.firestar.dto.FileVO;
import com.firestar.entity.ImportBatch;
import com.firestar.exception.BizException;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FileService;
import com.firestar.service.ImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 历史数据导入接口（前端 /api/import/**，代理剥离 /api 后到 /import/**）。
 *
 * <p>【权限】视图类接口 import:view；归档 import:archive；各类型导入 import:{key}。
 * 【上传即走】submit 先走 FileService.upload 拿 fileId，再落 QUEUED 批次立即返回；
 * 后台流水线异步跑，前台可切走页面。</p>
 */
@Slf4j
@RestController
@RequestMapping("/import")
@RequirePerm("import:view")
public class ImportController {

    private final ImportService importService;
    private final FileService fileService;
    private final UserMapper userMapper;

    public ImportController(ImportService importService, FileService fileService, UserMapper userMapper) {
        this.importService = importService;
        this.fileService = fileService;
        this.userMapper = userMapper;
    }

    /** 已注册的导入类型（前端新建导入下拉） */
    @GetMapping("/keys")
    public Result<List<Map<String, Object>>> keys() {
        return Result.success(importService.listKeys());
    }

    /**
     * 提交导入（multipart）：一次请求完成「上传原始文件 + 落批次 + 派发后台」。
     *
     * @param file           xlsx（≤5MB）
     * @param parentBatchNo  修正重传时指向原失败批次号（可空）
     */
    @PostMapping("/{key}/submit")
    public Result<Map<String, Object>> submit(@PathVariable String key,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) String parentBatchNo) {
        // 类型级权限：import:{key}（类级 import:view 已过，这里再收一层）
        importService.requireHandler(key);
        Long operatorId = DataScopeHelper.currentUserId(userMapper);
        FileVO vo = fileService.upload(file, "import", null, null, null, false);
        String batchNo;
        try {
            batchNo = importService.submit(key, file, vo.getId(), operatorId, parentBatchNo);
        } catch (BizException e) {
            // 提交失败（如 409 同 key 在跑 / 修正目标非法）→ 清理刚传的孤儿文件
            try {
                fileService.delete(vo.getId());
            } catch (Exception ignored) {
                log.warn("[import] 清理孤儿文件失败 fileId={}", vo.getId());
            }
            throw e;
        }
        return Result.success(Map.of("batchNo", batchNo));
    }

    /** 批次详情 */
    @GetMapping("/batches/{id}")
    public Result<ImportBatch> detail(@PathVariable Long id) {
        return Result.success(importService.detail(id));
    }

    /** 导入历史（可按类型过滤；默认只看本人提交） */
    @GetMapping("/history")
    public Result<List<ImportBatch>> history(@RequestParam(required = false) String importKey,
                                             @RequestParam(required = false) Integer limit) {
        Long me = DataScopeHelper.currentUserId(userMapper);
        return Result.success(importService.history(importKey, me, limit));
    }

    /** 下载错误回执 xlsx（含错误信息列 + 隐藏批次号/行号） */
    @GetMapping("/batches/{id}/errors.xlsx")
    public ResponseEntity<ByteArrayResource> errorWorkbook(@PathVariable Long id) {
        byte[] bytes = importService.errorWorkbook(id);
        return attachment(bytes, "import-errors-" + id + ".xlsx");
    }

    /** 下载空模板（代码现场生成） */
    @GetMapping("/templates/{key}")
    public ResponseEntity<ByteArrayResource> template(@PathVariable String key) {
        byte[] bytes = importService.template(key);
        return attachment(bytes, "import-template-" + key + ".xlsx");
    }

    /** 归档（管理员） */
    @PostMapping("/batches/{id}/archive")
    @RequirePerm("import:archive")
    public Result<Void> archive(@PathVariable Long id) {
        importService.archive(id, DataScopeHelper.currentUserId(userMapper));
        return Result.success();
    }

    private static ResponseEntity<ByteArrayResource> attachment(byte[] bytes, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(new ByteArrayResource(bytes));
    }
}
