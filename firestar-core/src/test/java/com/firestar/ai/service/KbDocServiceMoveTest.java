package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.ai.entity.KbDoc;
import com.firestar.ai.kb.KbPublishGate;
import com.firestar.ai.mapper.KbAttachmentMapper;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import com.firestar.ai.vo.KbNodeMoveReq;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库排序副作用回归（2026-11-25）：relative 整组重排只应改兄弟的 sort_no，
 * 不得刷 updatedAt —— 否则 status=PUBLISHED 的兄弟会被
 * {@link KbTreeRules#hasUnpublishedEdits} 误判成「有未发布修改」。
 *
 * <p>用 Mockito 捕获落库 patch，验证「兄弟只改 sortNo、被拖节点自身仍刷时间戳」
 * 这一非对称契约。纯规则层 KbTreeRulesTest 覆盖不到 Service 落库副作用，故此补在 Service 层。</p>
 */
@ExtendWith(MockitoExtension.class)
class KbDocServiceMoveTest {

    @Mock private KbDocMapper docMapper;
    @Mock private KbAttachmentMapper attachmentMapper;
    @Mock private KbChunkMapper chunkMapper;
    @Mock private FileStorageMapper fileMapper;
    @Mock private FileStorageService fileStorageService;
    @Mock private KbPublishGate publishGate;
    @InjectMocks private KbDocService service;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 20, 10, 0);

    /** 纯 Mockito 单测无 Spring 上下文，LambdaUpdateWrapper 解析列名需要 TableInfo 缓存 */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), KbDoc.class);
    }

    @BeforeEach
    void setUpAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** 发布后干净状态：updatedAt == publishedAt，不应被判「有未发布修改」 */
    private static KbDoc publishedDoc(long id, int sortNo) {
        KbDoc d = new KbDoc();
        d.setId(id);
        d.setParentId(0L);
        d.setNodeType("DOC");
        d.setTitle("文档" + id);
        d.setSortNo(sortNo);
        d.setStatus("PUBLISHED");
        d.setPublishedAt(T0);
        d.setUpdatedAt(T0);
        d.setPath("/" + id + "/");
        return d;
    }

    @Test
    @DisplayName("relative 排序：兄弟只改 sort_no 不刷时间戳，已发布兄弟不得被标未发布修改")
    void moveRelative_doesNotTouchSiblingTimestamps() {
        KbDoc d1 = publishedDoc(1, 10);
        KbDoc d2 = publishedDoc(2, 20);
        KbDoc d3 = publishedDoc(3, 30);
        List<KbDoc> all = new ArrayList<>(List.of(d1, d2, d3));

        when(docMapper.selectById(1L)).thenReturn(d1);
        when(docMapper.selectById(3L)).thenReturn(d3); // AFTER 的锚点兄弟
        when(docMapper.selectList(null)).thenReturn(all);

        KbNodeMoveReq req = new KbNodeMoveReq();
        req.setPosition("AFTER");
        req.setTargetId(3L);
        // 把 1 拖到 3 后面 → order = [2,3,1]：兄弟 2、3 重排，自身 1 排最后
        service.move(1L, req, "tester");

        // ① 兄弟通过 updateById 落 patch：只带 sortNo，时间戳/状态字段必须为 null
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<KbDoc> patchCap = (ArgumentCaptor) ArgumentCaptor.forClass(KbDoc.class);
        verify(docMapper, times(2)).updateById(patchCap.capture());
        for (KbDoc patch : patchCap.getAllValues()) {
            assertNotNull(patch.getSortNo(), "兄弟 patch 必须带新 sort_no");
            assertNull(patch.getUpdatedAt(), "兄弟不得被刷 updated_at（否则误标未发布修改）");
            assertNull(patch.getUpdatedBy(), "兄弟不得被刷 updated_by");
            assertNull(patch.getStatus(), "兄弟 status 不得被改写");
            assertEquals(0L, patch.getParentId() == null ? 0L : patch.getParentId(),
                    "兄弟 patch 不得携带 parentId");
        }
        // 兄弟 2→10、3→20（各自 sortNo 被改，但顺序仍确定）
        List<Integer> pushedSortNos = patchCap.getAllValues().stream()
                .map(KbDoc::getSortNo).sorted().toList();
        assertEquals(List.of(10, 20), pushedSortNos);

        // ② 被拖节点自身仍走 LambdaUpdateWrapper 落库（方案 A 保留：它 parentId/path 真变了）
        //    兄弟走 updateById（上面已捕获），自身走 update(entity, wrapper)，两条通道互不混淆
        verify(docMapper).update(org.mockito.ArgumentMatchers.<KbDoc>isNull(),
                org.mockito.ArgumentMatchers.<LambdaUpdateWrapper<KbDoc>>any());

        // ③ 兄弟对象保持发布后干净状态，不因排序被判「有未发布修改」
        assertFalse(KbTreeRules.hasUnpublishedEdits(d2));
        assertFalse(KbTreeRules.hasUnpublishedEdits(d3));
    }

    @Test
    @DisplayName("旧契约兜底通道（position 为空）不整组重排，任何兄弟都不被 updateById 触碰")
    void moveLegacy_doesNotBatchUpdateSiblings() {
        KbDoc d1 = publishedDoc(1, 10);
        KbDoc d2 = publishedDoc(2, 20);
        List<KbDoc> all = new ArrayList<>(List.of(d1, d2));

        when(docMapper.selectById(1L)).thenReturn(d1);
        when(docMapper.selectList(null)).thenReturn(all);

        KbNodeMoveReq req = new KbNodeMoveReq();
        req.setParentId(0L);
        req.setSortNo(99);
        service.move(1L, req, "tester");

        // 旧通道只 update 自身，无整组兄弟 patch
        verify(docMapper, times(0)).updateById(org.mockito.ArgumentMatchers.any(KbDoc.class));
        assertFalse(KbTreeRules.hasUnpublishedEdits(d2));
    }
}
