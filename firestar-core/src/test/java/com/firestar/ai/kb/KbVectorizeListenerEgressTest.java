package com.firestar.ai.kb;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.ai.config.EmbeddingProperties;
import com.firestar.ai.config.KbVectorEgressProperties;
import com.firestar.ai.embedding.EmbeddingProvider;
import com.firestar.ai.entity.KbChunk;
import com.firestar.ai.entity.KbDoc;
import com.firestar.ai.es.EsVectorStore;
import com.firestar.ai.mapper.KbChunkMapper;
import com.firestar.ai.mapper.KbDocMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出域闸在向量化管道中的实效验证：受限文档不得调用 embed（正文绝不送第三方）。
 * 不连真实 ES/Ollama，纯 mock。
 */
@ExtendWith(MockitoExtension.class)
class KbVectorizeListenerEgressTest {

    @Mock KbChunkMapper chunkMapper;
    @Mock KbDocMapper docMapper;
    @Mock EmbeddingProvider embeddingProvider;
    @Mock EsVectorStore vectorStore;
    @Mock Executor executor;

    private KbVectorizeListener listener;

    @BeforeAll
    static void initMpLambdaCache() {
        // standalone mock 无 Spring 上下文，LambdaUpdateWrapper<KbChunk> 解析需 TableInfo 缓存
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), KbChunk.class);
    }

    @BeforeEach
    void setUp() {
        KbVectorEgressProperties egressProps = new KbVectorEgressProperties();
        egressProps.setSensitiveRoles("ROLE_HR_MANAGER, ROLE_SUPERVISOR, ROLE_FINANCE_MANAGER");
        egressProps.validate();
        KbVectorEgressPolicy policy = new SensitiveAwareVectorEgressPolicy(egressProps);
        listener = new KbVectorizeListener(chunkMapper, docMapper, embeddingProvider, vectorStore,
                executor, new EmbeddingProperties(), policy);
    }

    private KbChunk chunk() {
        KbChunk c = new KbChunk();
        c.setId(1L);
        c.setDocId(100L);
        c.setVersion(1);
        c.setHeadingPath("# 标题");
        c.setContent("受限正文内容");
        c.setStatus(1);
        c.setVectorStatus("NONE");
        return c;
    }

    @Test
    @DisplayName("1. 受限文档（egress_allowed=0）：返回0、不embed、清旧向量、不改PENDING")
    void egressDisabledSkipsEmbed() throws Exception {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk()));
        KbDoc doc = new KbDoc();
        doc.setId(100L);
        doc.setEgressAllowed(0);
        when(docMapper.selectById(100L)).thenReturn(doc);

        int n = listener.vectorizeDoc(100L, 1);

        assertEquals(0, n);
        verify(embeddingProvider, never()).embed(anyList());          // 正文绝不送第三方
        verify(vectorStore).deleteByDocId(100L);                      // 清旧向量防残留
        verify(chunkMapper, never()).update(isNull(), any());         // 不标 PENDING，保持 NONE
    }

    @Test
    @DisplayName("2. 敏感角色文档（acl_roles=ROLE_HR_MANAGER）：同样拦截 embed")
    void sensitiveRoleSkipsEmbed() throws Exception {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk()));
        KbDoc doc = new KbDoc();
        doc.setId(100L);
        doc.setEgressAllowed(1);
        doc.setAclRoles("ROLE_HR_MANAGER");
        when(docMapper.selectById(100L)).thenReturn(doc);

        int n = listener.vectorizeDoc(100L, 1);

        assertEquals(0, n);
        verify(embeddingProvider, never()).embed(anyList());
    }

    @Test
    @DisplayName("3. 普通文档：正常 embed + upsert + 标 READY")
    void normalDocEmbeds() throws Exception {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk()));
        KbDoc doc = new KbDoc();
        doc.setId(100L);
        doc.setEgressAllowed(1);
        doc.setAclRoles("ROLE_SALES_MANAGER");
        when(docMapper.selectById(100L)).thenReturn(doc);
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(new float[1024]));
        when(vectorStore.index()).thenReturn("firestar_kb_vector");

        int n = listener.vectorizeDoc(100L, 1);

        assertEquals(1, n);
        verify(embeddingProvider).embed(anyList());
        verify(vectorStore).upsert(anyLong(), anyLong(), anyInt(), any(), any(), any(), any(float[].class));
    }

    @Test
    @DisplayName("4. doc 查不到：防御性拦截，不 embed")
    void missingDocSkipsEmbed() throws Exception {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk()));
        when(docMapper.selectById(100L)).thenReturn(null);

        int n = listener.vectorizeDoc(100L, 1);

        assertEquals(0, n);
        verify(embeddingProvider, never()).embed(anyList());
    }
}
