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
     * <p><b>V206 媒体类型契约（classify）</b>：上传接口只有一个，服务端无法从请求判断来源，
     * 故由 {@code classify} 显式声明「本次要不要做媒体分类」，避免隐式契约：
     * <ul>
     *   <li>{@code true}：文件管理页上传（唯一可由用户手选文件类型的入口）。手选值必须在
     *       file_type 的 5 项白名单内；未选则由<b>服务端按扩展名推导</b>。
     *       后端内部确知类型的调用（如头像固定 IMAGE）也传 true；</li>
     *   <li>{@code false}：其他所有业务场景，一律 {@code ATTACHMENT}，不推导、不分类，
     *       即使显式传了 fileType 也忽略（防伪造入参）。</li>
     * </ul>
     * 分类结果只描述媒体格式，业务归属仍由 {@code bizType} 承担，两者不再纠缠。
     *
     * @param file     上传的文件
     * @param bizType  关联业务类型（invoice/receipt 等，为空归到 common）
     * @param bizId    关联业务 ID，可空
     * @param fileType 媒体类型（字典 file_type 的 5 项），仅 classify=true 时生效；为空则推导
     * @param alias    文件别名（V23，可空；文件管理处维护，仅展示/审计用）
     * @param classify 是否做媒体分类，见上方契约
     */
    FileVO upload(MultipartFile file, String bizType, Long bizId, String fileType, String alias,
                  boolean classify);

    /**
     * 文件分页列表（默认只含 NORMAL，已删除的不出现在列表中）。
     *
     * <p><b>数据隔离分流（2026-10-26 修正）</b>：
     * <ul>
     *   <li>带 {@code bizType}（业务上下文，如审批详情拉某单附件）→ 附件归属业务单据、随单据可见性走，
     *       不按上传人隔离，否则审批人看不到别人传的附件（银行回单/签收证据等）；</li>
     *   <li>不带 {@code bizType}（文件管理「我的文件」浏览）→ 仅看自己上传的（uploaderId = 自己）。</li>
     * </ul>
     * 读接口类级 file:view 已移除，业务页展示附件不再被文件模块权限卡住；写操作仍由 file:manage 收口。
     *
     * @param bizId 业务 ID（bizType 非空时建议传入，后端按它精确过滤，避免返回同类型全部附件）
     */
    IPage<FileVO> list(Integer pageNum, Integer pageSize, String bizType, Long bizId, String fileType, String keyword);

    /** 文件详情（不返回内容，只返回元信息）。 */
    FileVO detail(Long id);

    /**
     * 生成访问地址：预览传 preview=true（inline），下载传 false（attachment）。
     * 下载时累加下载次数并写入下载记录（红线 5：谁下载了什么、什么时间）；
     * ⚠️ 预览不计数、不写记录 —— 预览不是下载行为，计进去会污染审计口径
     * （2026-10-16 修：此前 accessUrl 对预览也无条件 +1 并落下载记录）。
     */
    String accessUrl(Long id, boolean preview, HttpServletRequest request);

    /**
     * 更新文件别名（V23；文件管理处维护，仅展示/审计用）。
     * 留审计（@Auditable UPDATE/FILE），可清空（传空串回退显示 originalName）。
     */
    FileVO updateAlias(Long id, String alias);

    /**
     * 删除文件。按项目分层约定（sys_ 表不带逻辑删除、靠 status 停用），
     * 这里只把 status 标为 DELETED，DB 记录与 OSS 对象均保留 —— 财务凭证有合规保留期，
     * 物理清理交给后续归档任务执行。
     */
    void delete(Long id);

    /**
     * 某文件的下载记录分页列表。
     *
     * @param keyword 文件名/别名模糊匹配（V23：下载记录表本身不存文件名，
     *                先反查 sys_file_storage 得 fileId 再过滤，参数化查询无注入）
     */
    IPage<FileDownloadLogVO> downloadLogs(Long fileId, Integer pageNum, Integer pageSize, String keyword);

    /**
     * 由后端代理输出文件内容（仅本地存储模式需要）。
     * OSS 模式请走 {@link #accessUrl} 的签名 URL，不要让文件流经应用服务器 ——
     * 跨地域中转会把双重流量费和 ECS 出网带宽的问题重新引入。
     */
    void stream(Long id, boolean preview, HttpServletResponse response);
}
