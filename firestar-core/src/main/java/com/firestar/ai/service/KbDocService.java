package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.firestar.ai.entity.KbAttachment;
import com.firestar.ai.entity.KbChunk;
import com.firestar.ai.entity.KbDoc;
import com.firestar.ai.kb.KbMarkdownSplitter;
import com.firestar.ai.mapper.KbAttachmentMapper;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import com.firestar.ai.vo.KbDocDetailVO;
import com.firestar.ai.vo.KbDocUpdateReq;
import com.firestar.ai.vo.KbNodeCreateReq;
import com.firestar.ai.vo.KbNodeMoveReq;
import com.firestar.ai.vo.KbNodePageVO;
import com.firestar.ai.vo.KbNodeVO;
import com.firestar.ai.vo.KbAttachmentInfo;
import com.firestar.ai.kb.KbFileTextExtractor;
import com.firestar.annotation.Auditable;
import com.firestar.common.SecurityContextUtils;
import com.firestar.entity.FileStorage;
import com.firestar.exception.BizException;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库节点 / 文档服务（步骤 4.5，契约见设计文档 §13.4）。
 *
 * <p>加载策略：{@code /nodes} 只下发轻量骨架（绝不带 contentMd），正文由 {@code /docs/{id}} 单独拉；
 * 不传 parentId = 一次返回全量可见骨架，超过 {@code KB_LAZY_THRESHOLD} 才置 lazyRequired 并分页；
 * 传 parentId = 只取直接子节点，按 pageSize / pageToken 翻页（对齐飞书 wiki node list）。</p>
 *
 * <p>安全：{@code acl_roles} 在后端统一过滤，ROLE_ADMIN 无条件放行；写操作全部过 @RequirePerm +
 * @Auditable。MySQL 5.7 无 CHECK 约束，防环 / 文件夹无正文 / 删除拒绝有子节点全在 Service 保证。</p>
 */
@Service
public class KbDocService {

    private static final Logger log = LoggerFactory.getLogger(KbDocService.class);

    private static final String ROLE_INLINE = "INLINE";
    private static final String ROLE_REFERENCE = "REFERENCE";
    private static final String ROLE_ORIGINAL = "ORIGINAL";
    private static final String ROLE_EXPIRED = "EXPIRED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String TYPE_FOLDER = "FOLDER";
    private static final String TYPE_DOC = "DOC";
    private static final String FILE_NORMAL = "NORMAL";
    private static final String STORAGE_LOCAL = "LOCAL";

    private final KbDocMapper docMapper;
    private final KbAttachmentMapper attachmentMapper;
    private final KbChunkMapper chunkMapper;
    private final FileStorageMapper fileMapper;
    private final FileStorageService fileStorageService;

    public KbDocService(KbDocMapper docMapper,
                        KbAttachmentMapper attachmentMapper,
                        KbChunkMapper chunkMapper,
                        FileStorageMapper fileMapper,
                        FileStorageService fileStorageService) {
        this.docMapper = docMapper;
        this.attachmentMapper = attachmentMapper;
        this.chunkMapper = chunkMapper;
        this.fileMapper = fileMapper;
        this.fileStorageService = fileStorageService;
    }

    // ==================== 查询 ====================

    /**
     * 节点骨架。ACL 过滤在后端一次算完整棵可见树，避免飞书官方警告的
     * 「展开后 items 为空但 hasMore=true」——hasChild 也基于可见集合计算。
     */
    public KbNodePageVO nodes(Long parentId, Integer pageSize, String pageToken, String keyword) {
        boolean admin = SecurityContextUtils.isAdmin();
        Set<String> authorities = currentAuthorities();
        List<KbDoc> visible = new ArrayList<>();
        for (KbDoc d : docMapper.selectList(null)) {
            if (KbTreeRules.isVisible(d.getAclRoles(), admin, authorities)) {
                visible.add(d);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            visible.removeIf(d -> d.getTitle() == null || !d.getTitle().contains(kw));
        }
        visible.sort(Comparator
                .comparingLong((KbDoc d) -> d.getParentId() == null ? 0L : d.getParentId())
                .thenComparingInt(d -> d.getSortNo() == null ? 0 : d.getSortNo())
                .thenComparingLong(d -> d.getId() == null ? 0L : d.getId()));

        Set<Long> parentsWithChildren = visible.stream()
                .map(KbDoc::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int offset = KbTreeRules.parseOffset(pageToken);
        int limit;
        List<KbDoc> scope;
        boolean lazyRequired = false;
        if (parentId != null) {
            scope = visible.stream()
                    .filter(d -> parentId.equals(d.getParentId()))
                    .collect(Collectors.toList());
            limit = KbTreeRules.normalizePageSize(pageSize);
        } else {
            scope = visible;
            if (visible.size() > KbTreeRules.KB_LAZY_THRESHOLD) {
                lazyRequired = true;
                limit = KbTreeRules.normalizePageSize(pageSize);
            } else {
                limit = Math.max(visible.size(), 1);
            }
        }
        List<KbDoc> page = KbTreeRules.slice(scope, offset, limit);
        int nextOffset = offset + page.size();
        boolean hasMore = nextOffset < scope.size();

        KbNodePageVO vo = new KbNodePageVO();
        vo.setItems(page.stream()
                .map(d -> toNodeVO(d, parentsWithChildren))
                .collect(Collectors.toList()));
        vo.setHasMore(hasMore);
        vo.setNextPageToken(hasMore ? String.valueOf(nextOffset) : null);
        vo.setLazyRequired(lazyRequired);
        return vo;
    }

    /** 文档正文（唯一含 contentMd 的接口） */
    public KbDocDetailVO doc(Long id) {
        KbDoc d = require(id);
        assertVisible(d);
        if (isFolder(d)) {
            throw new BizException("文件夹没有正文，请展开后选择文档");
        }
        KbDocDetailVO vo = new KbDocDetailVO();
        vo.setId(d.getId());
        vo.setTitle(d.getTitle());
        vo.setSummary(d.getSummary());
        vo.setContentMd(d.getContentMd());
        vo.setStatus(d.getStatus());
        vo.setVersion(d.getVersion());
        vo.setAclRoles(d.getAclRoles());
        vo.setEgressAllowed(d.getEgressAllowed() == null || d.getEgressAllowed() == 1);
        vo.setUpdatedAt(d.getUpdatedAt());
        return vo;
    }

    // ==================== 写操作 ====================

    @Transactional
    @Auditable(operationType = "CREATE", targetType = "KB_DOC",
            targetIdSpEl = "#result", fields = {"title", "nodeType", "parentId"})
    public Long create(KbNodeCreateReq req, String operator) {
        String title = normalizeTitle(req.getTitle());
        String nodeType = req.getNodeType();
        if (!TYPE_DOC.equals(nodeType) && !TYPE_FOLDER.equals(nodeType)) {
            throw new BizException("nodeType 只能是 DOC 或 FOLDER");
        }
        long parentId = req.getParentId() == null ? 0L : req.getParentId();
        List<KbDoc> all = docMapper.selectList(null);
        KbDoc parent = null;
        if (parentId != 0) {
            parent = require(parentId);
            assertVisible(parent);
            if (!isFolder(parent)) {
                throw new BizException("父节点必须是文件夹");
            }
        }
        int sortNo = req.getSortNo() != null
                ? req.getSortNo() : KbTreeRules.nextSortNo(all, parentId);
        LocalDateTime now = LocalDateTime.now();

        KbDoc d = new KbDoc();
        d.setTitle(title);
        d.setParentId(parentId);
        d.setNodeType(nodeType);
        d.setSortNo(sortNo);
        d.setStatus("DRAFT");
        d.setVersion(1);
        d.setSourceType("MD");
        d.setEgressAllowed(1);
        // 新建 DOC 自带唯一 H1，保证 validateMarkdown 从第一秒就通过
        d.setContentMd(TYPE_DOC.equals(nodeType) ? "# " + title + "\n\n" : null);
        d.setCreatedBy(operator);
        d.setUpdatedBy(operator);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.setTenantId(0L);
        docMapper.insert(d);

        Long id = d.getId();
        KbDoc patch = new KbDoc();
        patch.setId(id);
        patch.setPath(KbTreeRules.computePath(parent == null ? null : pathOf(parent), id));
        docMapper.updateById(patch);
        return id;
    }

    /**
     * 保存正文。fileRefs <b>以正文为准重新提取</b>，不信前端传参；
     * 同步 sys_kb_attachment.INLINE 关系（只增删 INLINE，不动 REFERENCE/ORIGINAL/EXPIRED）。
     */
    @Transactional
    @Auditable(operationType = "UPDATE", targetType = "KB_DOC",
            targetIdSpEl = "#id", entityClass = KbDoc.class,
            fields = {"title", "status", "nodeType"})
    public void update(Long id, KbDocUpdateReq req, String operator) {
        KbDoc d = require(id);
        assertVisible(d);
        if (isFolder(d)) {
            throw new BizException("文件夹不能保存正文");
        }
        String title = normalizeTitle(req.getTitle());
        String contentMd = req.getContentMd();
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("正文不能为空");
        }
        String summary = req.getSummary();
        if (summary != null && summary.length() > 512) {
            throw new BizException("摘要不能超过 512 字符");
        }
        syncInlineAttachments(id, KbTreeRules.collectFileRefs(contentMd), operator);

        docMapper.update(null, new LambdaUpdateWrapper<KbDoc>()
                .eq(KbDoc::getId, id)
                .set(KbDoc::getTitle, title)
                .set(KbDoc::getSummary, summary)
                .set(KbDoc::getContentMd, contentMd)
                .set(KbDoc::getUpdatedBy, operator)
                .set(KbDoc::getUpdatedAt, LocalDateTime.now()));
    }

    /** 改名（DOC / FOLDER 通用，对应前端树内 inline 重命名，属本轮补的第 8 个接口） */
    @Transactional
    @Auditable(operationType = "UPDATE", targetType = "KB_DOC",
            targetIdSpEl = "#id", entityClass = KbDoc.class,
            fields = {"title", "path"}, remark = "重命名知识库节点")
    public void rename(Long id, String title, String operator) {
        KbDoc d = require(id);
        assertVisible(d);
        String normalized = normalizeTitle(title);
        docMapper.update(null, new LambdaUpdateWrapper<KbDoc>()
                .eq(KbDoc::getId, id)
                .set(KbDoc::getTitle, normalized)
                .set(KbDoc::getUpdatedBy, operator)
                .set(KbDoc::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 移动：防环 + 重算自身与整棵子树 path + <b>整组重排同级 sort_no</b>。
     *
     * <p>契约（§21，对齐飞书「前端不碰排序号」+ Confluence 相对位置）：前端只发
     * {@code position + targetId}，不传排序号。BEFORE/AFTER 以目标兄弟的父为新父，
     * APPEND 以 targetId 为新父（0 = 根）；服务端据
     * {@link KbTreeRules#resolveSiblingOrder} 得到插入后的完整顺序，在同一事务里把
     * 同父兄弟的 sort_no 统一改成 10/20/30…，避免前端算号导致的「拖到 A 前面却跑到
     * 末尾」「目录文件混排顺序漂移」。目录与文档同级混排，不按 node_type 分组。</p>
     *
     * <p>position 为空时回落到旧的 parentId + sortNo 通道，兼容存量客户端，不产生破坏。</p>
     */
    @Transactional
    @Auditable(operationType = "UPDATE", targetType = "KB_DOC",
            targetIdSpEl = "#id", entityClass = KbDoc.class,
            fields = {"title", "parentId", "path"}, remark = "移动知识库节点")
    public void move(Long id, KbNodeMoveReq req, String operator) {
        KbDoc d = require(id);
        assertVisible(d);
        List<KbDoc> all = docMapper.selectList(null);

        boolean relative = req.getPosition() != null && !req.getPosition().isBlank();
        String position = KbTreeRules.normalizePosition(req.getPosition());
        long newParentId;
        Long anchorId = null;
        if (KbTreeRules.POS_BEFORE.equals(position) || KbTreeRules.POS_AFTER.equals(position)) {
            if (req.getTargetId() == null || req.getTargetId() == 0L) {
                throw new BizException("相对位置必须指定目标兄弟节点");
            }
            KbDoc anchor = require(req.getTargetId());
            assertVisible(anchor);
            newParentId = anchor.getParentId() == null ? 0L : anchor.getParentId();
            anchorId = anchor.getId();
        } else if (relative) {
            if (req.getTargetId() == null) {
                throw new BizException("APPEND 必须指定目标父节点（0 = 根目录）");
            }
            newParentId = req.getTargetId();
        } else {
            newParentId = req.getParentId() == null ? 0L : req.getParentId();
        }

        KbTreeRules.assertNoCycle(all, id, newParentId);

        KbDoc parent = newParentId == 0 ? null : require(newParentId);
        if (parent != null) {
            assertVisible(parent);
            if (!isFolder(parent)) {
                throw new BizException("目标父节点必须是文件夹");
            }
        }
        String newBase = KbTreeRules.computePath(parent == null ? null : pathOf(parent), id);
        String oldBase = pathOf(d);
        LocalDateTime now = LocalDateTime.now();

        int sortNo;
        if (relative) {
            List<Long> order = KbTreeRules.resolveSiblingOrder(all, id, newParentId, position, anchorId);
            sortNo = 0;
            for (int i = 0; i < order.size(); i++) {
                long sid = order.get(i);
                int no = (i + 1) * 10;
                if (sid == id) {
                    sortNo = no;
                } else {
                    // 只带非空字段的 patch：updateById 不会误改 title / content_md / path
                    KbDoc patch = new KbDoc();
                    patch.setId(sid);
                    patch.setSortNo(no);
                    patch.setUpdatedBy(operator);
                    patch.setUpdatedAt(now);
                    docMapper.updateById(patch);
                }
            }
        } else {
            sortNo = req.getSortNo() != null
                    ? req.getSortNo() : KbTreeRules.nextSortNo(all, newParentId);
        }

        docMapper.update(null, new LambdaUpdateWrapper<KbDoc>()
                .eq(KbDoc::getId, id)
                .set(KbDoc::getParentId, newParentId)
                .set(KbDoc::getSortNo, sortNo)
                .set(KbDoc::getPath, newBase)
                .set(KbDoc::getUpdatedBy, operator)
                .set(KbDoc::getUpdatedAt, now));
        for (KbDoc child : KbTreeRules.descendants(all, id)) {
            KbDoc patch = new KbDoc();
            patch.setId(child.getId());
            patch.setPath(KbTreeRules.rebasePath(child.getPath(), oldBase, newBase));
            docMapper.updateById(patch);
        }
    }

    /** 删除：有子节点拒绝；文档删掉时级联清掉附件与切片关系 */
    @Transactional
    @Auditable(operationType = "DELETE", targetType = "KB_DOC",
            targetIdSpEl = "#id", entityClass = KbDoc.class,
            fields = {"title", "status", "path"})
    public void delete(Long id, String operator) {
        KbDoc d = require(id);
        assertVisible(d);
        Long childCount = docMapper.selectCount(
                new LambdaQueryWrapper<KbDoc>().eq(KbDoc::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("请先删除或移走子节点");
        }
        attachmentMapper.delete(new LambdaQueryWrapper<KbAttachment>()
                .eq(KbAttachment::getDocId, id));
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocId, id));
        docMapper.deleteById(id);
    }

    /**
     * 发布：不接 Flowable（P0 直接生效），但把三件预留位一次落实——
     * version +1、旧版本 chunk 置失效、旧 ORIGINAL 附件转 EXPIRED（不检索不出域仅留痕）。
     */
    @Transactional
    @Auditable(operationType = "UPDATE", targetType = "KB_DOC",
            targetIdSpEl = "#id", entityClass = KbDoc.class,
            fields = {"title", "status", "version"}, remark = "发布知识库文档")
    public void publish(Long id, String operator) {
        KbDoc d = require(id);
        assertVisible(d);
        if (isFolder(d)) {
            throw new BizException("文件夹不能发布");
        }
        int newVersion = (d.getVersion() == null ? 1 : d.getVersion()) + 1;
        LocalDateTime now = LocalDateTime.now();
        docMapper.update(null, new LambdaUpdateWrapper<KbDoc>()
                .eq(KbDoc::getId, id)
                .set(KbDoc::getStatus, STATUS_PUBLISHED)
                .set(KbDoc::getVersion, newVersion)
                .set(KbDoc::getPublishedAt, now)
                .set(KbDoc::getPublishedBy, operator)
                .set(KbDoc::getUpdatedBy, operator)
                .set(KbDoc::getUpdatedAt, now));
        chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                .eq(KbChunk::getDocId, id)
                .set(KbChunk::getStatus, 0));
        // 步骤 5：按新版本重新切片并落库（splitToChunks 已置 status=1 / vectorStatus=NONE /
        // createdAt / tenantId=0，此处直接 insert；version 与文档新版本对齐，保证检索只命中最新版）
        List<KbChunk> chunks = new ArrayList<>(
                KbMarkdownSplitter.splitToChunks(id, newVersion, d.getContentMd()));
        // REFERENCE 附件：解析文本 → 切片 → 同 docId/version 落库，被主检索链路天然召回
        for (KbAttachment att : attachmentMapper.selectList(
                new LambdaQueryWrapper<KbAttachment>()
                        .eq(KbAttachment::getDocId, id)
                        .eq(KbAttachment::getRole, ROLE_REFERENCE)
                        .eq(KbAttachment::getIndexable, 1))) {
            indexReferenceAttachment(id, newVersion, att, chunks);
        }
        // 统一顺序编号后落库（避免 MD 切片与附件切片 seq 重叠，虽无唯一约束但保持可读）
        int seq = 0;
        for (KbChunk c : chunks) {
            c.setSeq(seq++);
            chunkMapper.insert(c);
        }
        attachmentMapper.update(null, new LambdaUpdateWrapper<KbAttachment>()
                .eq(KbAttachment::getDocId, id)
                .eq(KbAttachment::getRole, ROLE_ORIGINAL)
                .set(KbAttachment::getRole, ROLE_EXPIRED)
                .set(KbAttachment::getExpiredAt, now));
    }

    // ==================== 附件（REFERENCE）管理 ====================

    /** 列出文档的 REFERENCE 附件（补充材料，前端附件卡片用，带回原文件名） */
    public List<KbAttachmentInfo> listReferenceAttachments(Long docId) {
        KbDoc d = require(docId);
        assertVisible(d);
        List<KbAttachment> list = attachmentMapper.selectList(new LambdaQueryWrapper<KbAttachment>()
                .eq(KbAttachment::getDocId, docId)
                .eq(KbAttachment::getRole, ROLE_REFERENCE)
                .orderByAsc(KbAttachment::getSortNo, KbAttachment::getId));
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> fileIds = list.stream().map(KbAttachment::getFileId).distinct().toList();
        Map<Long, FileStorage> fileMap = fileMapper.selectBatchIds(fileIds).stream()
                .collect(java.util.stream.Collectors.toMap(FileStorage::getId, f -> f, (a, b) -> a));
        List<KbAttachmentInfo> out = new ArrayList<>();
        for (KbAttachment a : list) {
            KbAttachmentInfo info = new KbAttachmentInfo();
            info.setId(a.getId());
            info.setDocId(a.getDocId());
            info.setFileId(a.getFileId());
            FileStorage fs = fileMap.get(a.getFileId());
            info.setOriginalName(fs == null ? null : fs.getOriginalName());
            info.setIndexable(a.getIndexable());
            info.setEgressAllowed(a.getEgressAllowed());
            info.setSortNo(a.getSortNo());
            info.setCreatedAt(a.getCreatedAt());
            out.add(info);
        }
        return out;
    }

    /**
     * 新增 REFERENCE 附件并默认纳入检索（indexable=1）。
     *
     * <p>先校验文件真实存在且状态 NORMAL（防伪造 fileId）；同一 fileId 已挂过 REFERENCE 则幂等返回旧记录。
     * 仅登记关系，<b>不立即切片</b>——切片在下次 {@link #publish} 时与正文一并重新生成，
     * 与「发布才生效」的语义一致（草稿态改附件不影响线上检索）。</p>
     */
    @Transactional
    @Auditable(operationType = "CREATE", targetType = "KB_ATTACHMENT",
            targetIdSpEl = "#result", fields = {"docId", "fileId"})
    public Long addReferenceAttachment(Long docId, Long fileId, String operator) {
        KbDoc d = require(docId);
        assertVisible(d);
        if (isFolder(d)) {
            throw new BizException("文件夹不能挂附件");
        }
        if (fileId == null || fileId == 0L) {
            throw new BizException("缺少文件 id");
        }
        FileStorage fs = fileMapper.selectById(fileId);
        if (fs == null || !FILE_NORMAL.equals(fs.getStatus())) {
            throw new BizException("文件不存在或已删除，请重新上传");
        }
        KbAttachment existing = attachmentMapper.selectOne(new LambdaQueryWrapper<KbAttachment>()
                .eq(KbAttachment::getDocId, docId)
                .eq(KbAttachment::getFileId, fileId)
                .eq(KbAttachment::getRole, ROLE_REFERENCE));
        if (existing != null) {
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        KbAttachment a = new KbAttachment();
        a.setDocId(docId);
        a.setFileId(fileId);
        a.setRole(ROLE_REFERENCE);
        a.setIndexable(1);
        a.setEgressAllowed(1);
        a.setSortNo(0);
        a.setCreatedBy(operator);
        a.setCreatedAt(now);
        a.setTenantId(0L);
        attachmentMapper.insert(a);
        return a.getId();
    }

    /** 删除 REFERENCE 附件关系（仅登记层，不删 sys_file_storage 原文件） */
    @Transactional
    @Auditable(operationType = "DELETE", targetType = "KB_ATTACHMENT",
            targetIdSpEl = "#attId", fields = {"docId"})
    public void removeAttachment(Long attId, String operator) {
        KbAttachment a = attachmentMapper.selectById(attId);
        if (a == null) {
            throw new BizException("附件关系不存在");
        }
        require(a.getDocId());
        attachmentMapper.deleteById(attId);
    }

    /** 切换 REFERENCE 附件是否纳入检索；改的是登记层的 indexable，下次发布生效 */
    @Transactional
    @Auditable(operationType = "UPDATE", targetType = "KB_ATTACHMENT",
            targetIdSpEl = "#attId", fields = {"indexable"})
    public void setAttachmentIndexable(Long attId, int indexable, String operator) {
        KbAttachment a = attachmentMapper.selectById(attId);
        if (a == null) {
            throw new BizException("附件关系不存在");
        }
        require(a.getDocId());
        attachmentMapper.update(null, new LambdaUpdateWrapper<KbAttachment>()
                .eq(KbAttachment::getId, attId)
                .set(KbAttachment::getIndexable, indexable == 1 ? 1 : 0));
    }

    /**
     * 把单条 REFERENCE 附件解析为文本并切片，追加到待落库 chunks。
     *
     * <p>边界处理（任意失败都不阻断发布，只记日志跳过）：
     * <ul>
     *   <li>文件不存在 / 已删除 → 跳过；</li>
     *   <li><b>OSS 存储</b>：v1 服务端无读流能力（{@code FileStorageService.read} 仅 LOCAL 支持），
     *       跳过并提示需切本地存储或后续接 OSS 服务端下载；</li>
     *   <li>解析失败 / 不支持的类型 / 抽取为空 → 跳过。</li>
     * </ul>
     * 切片 sourceRef 前缀为 {@code kb:att/<fileId>}，供检索反解 fileId。</p>
     */
    private void indexReferenceAttachment(Long docId, int version, KbAttachment att, List<KbChunk> out) {
        Long fileId = att.getFileId();
        FileStorage fs = fileMapper.selectById(fileId);
        if (fs == null || !FILE_NORMAL.equals(fs.getStatus())) {
            log.warn("REFERENCE 附件文件不存在或已删除，跳过索引：docId={}, fileId={}", docId, fileId);
            return;
        }
        if (!STORAGE_LOCAL.equals(fileStorageService.type())) {
            log.warn("非本地存储（{}）暂不支持服务端解析，跳过附件索引：docId={}, fileId={}，"
                    + "可改用本地存储或后续接入 OSS 服务端下载", fileStorageService.type(), docId, fileId);
            return;
        }
        byte[] data;
        try (java.io.InputStream in = fileStorageService.read(fs.getStoragePath())) {
            data = in.readAllBytes();
        } catch (Exception e) {
            log.warn("读取 REFERENCE 附件字节失败，跳过索引：docId={}, fileId={}, err={}",
                    docId, fileId, e.getMessage());
            return;
        }
        String text;
        try {
            text = KbFileTextExtractor.extract(fs.getOriginalName(), data);
        } catch (KbFileTextExtractor.UnsupportedFileTypeException e) {
            log.warn("REFERENCE 附件类型不支持，跳过索引：docId={}, fileId={}, name={}",
                    docId, fileId, fs.getOriginalName());
            return;
        } catch (Exception e) {
            log.warn("解析 REFERENCE 附件失败，跳过索引：docId={}, fileId={}, name={}, err={}",
                    docId, fileId, fs.getOriginalName(), e.getMessage());
            return;
        }
        if (text == null || text.isBlank()) {
            log.warn("REFERENCE 附件抽取文本为空，跳过索引：docId={}, fileId={}, name={}",
                    docId, fileId, fs.getOriginalName());
            return;
        }
        String md = "# " + (fs.getOriginalName() == null ? "附件" : fs.getOriginalName()) + "\n\n" + text;
        out.addAll(KbMarkdownSplitter.splitToChunks(docId, version, md, "kb:att/" + fileId));
    }

    // ==================== 私有 ====================

    /** 校验 fileId 真实存在，再把 INLINE 关系补齐到与正文引用完全一致 */
    private void syncInlineAttachments(Long docId, Set<Long> refs, String operator) {
        if (!refs.isEmpty()) {
            List<FileStorage> found = fileMapper.selectBatchIds(refs);
            if (found.size() != refs.size()
                    || found.stream().anyMatch(f -> "DELETED".equals(f.getStatus()))) {
                throw new BizException("正文引用了不存在或已删除的文件，请重新上传插图");
            }
        }
        List<KbAttachment> existing = attachmentMapper.selectList(
                new LambdaQueryWrapper<KbAttachment>()
                        .eq(KbAttachment::getDocId, docId)
                        .eq(KbAttachment::getRole, ROLE_INLINE));
        Set<Long> existingFileIds = new HashSet<>();
        List<Long> toRemove = new ArrayList<>();
        for (KbAttachment a : existing) {
            existingFileIds.add(a.getFileId());
            if (!refs.contains(a.getFileId())) {
                toRemove.add(a.getId());
            }
        }
        if (!toRemove.isEmpty()) {
            attachmentMapper.deleteBatchIds(toRemove);
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long fileId : refs) {
            if (existingFileIds.contains(fileId)) {
                continue;
            }
            KbAttachment a = new KbAttachment();
            a.setDocId(docId);
            a.setFileId(fileId);
            a.setRole(ROLE_INLINE);
            a.setIndexable(0);
            a.setEgressAllowed(0);
            a.setSortNo(0);
            a.setCreatedBy(operator);
            a.setCreatedAt(now);
            a.setTenantId(0L);
            attachmentMapper.insert(a);
        }
    }

    private KbNodeVO toNodeVO(KbDoc d, Set<Long> parentsWithChildren) {
        KbNodeVO vo = new KbNodeVO();
        vo.setId(d.getId());
        vo.setParentId(d.getParentId());
        vo.setTitle(d.getTitle());
        vo.setNodeType(d.getNodeType());
        vo.setSortNo(d.getSortNo());
        vo.setPath(d.getPath());
        vo.setStatus(d.getStatus());
        vo.setVersion(d.getVersion());
        vo.setHasChild(parentsWithChildren.contains(d.getId()));
        vo.setAclRoles(d.getAclRoles());
        vo.setUpdatedAt(d.getUpdatedAt());
        return vo;
    }

    private KbDoc require(Long id) {
        if (id == null) {
            throw new BizException("缺少节点 id");
        }
        KbDoc d = docMapper.selectById(id);
        if (d == null) {
            throw new BizException("知识库节点不存在: " + id);
        }
        return d;
    }

    private void assertVisible(KbDoc d) {
        if (!KbTreeRules.isVisible(d.getAclRoles(), SecurityContextUtils.isAdmin(), currentAuthorities())) {
            throw new BizException("无权访问该知识库文档");
        }
    }

    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a != null && a.getAuthority() != null) {
                out.add(a.getAuthority());
            }
        }
        return out;
    }

    private boolean isFolder(KbDoc d) {
        return TYPE_FOLDER.equals(d.getNodeType());
    }

    /** 存量脏数据 path 可能为 null，用 /id/ 兜底，避免整棵子树路径算错 */
    private String pathOf(KbDoc d) {
        return d.getPath() == null || d.getPath().isBlank()
                ? "/" + d.getId() + "/" : d.getPath();
    }

    private String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("标题不能为空");
        }
        String t = raw.trim();
        if (t.length() > 256) {
            throw new BizException("标题不能超过 256 字符");
        }
        return t;
    }
}
