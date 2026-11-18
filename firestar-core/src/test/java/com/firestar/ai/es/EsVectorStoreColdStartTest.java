package com.firestar.ai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.firestar.ai.config.EsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EsVectorStore 冷启动单测：索引不存在时 deleteByDocId 必须先建索引，
 * 否则首次向量化第一步（清旧向量）就 404 中断，永远没人建索引（死锁）。
 */
@ExtendWith(MockitoExtension.class)
class EsVectorStoreColdStartTest {

    @Mock ElasticsearchClient client;
    @Mock ElasticsearchIndicesClient indices;

    @Test
    @DisplayName("索引不存在时 deleteByDocId 先 ensureIndex 建索引，不再 404 中断")
    void deleteByDocIdEnsuresIndexOnColdStart() throws IOException {
        when(client.indices()).thenReturn(indices);
        // exists() 返回 false → 触发 create
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(false));
        when(indices.create(any(Function.class))).thenReturn(null);
        // deleteByQuery 本身返回类型复杂，这里只验证不因索引不存在而中断；mock 掉即可
        when(client.deleteByQuery(any(Function.class))).thenReturn(null);

        EsProperties props = new EsProperties();
        props.setIndex("firestar_kb_vector");
        EsVectorStore store = new EsVectorStore(props, 1024, client);

        assertDoesNotThrow(() -> store.deleteByDocId(920001L));

        verify(indices, times(1)).exists(any(Function.class));
        verify(indices, times(1)).create(any(Function.class));
        verify(client, times(1)).deleteByQuery(any(Function.class));
    }

    @Test
    @DisplayName("索引已存在时 deleteByDocId 不重复建索引")
    void deleteByDocIdSkipsCreateWhenIndexExists() throws IOException {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));
        when(client.deleteByQuery(any(Function.class))).thenReturn(null);

        EsProperties props = new EsProperties();
        props.setIndex("firestar_kb_vector");
        EsVectorStore store = new EsVectorStore(props, 1024, client);

        assertDoesNotThrow(() -> store.deleteByDocId(920001L));

        verify(indices, times(1)).exists(any(Function.class));
        verify(indices, never()).create(any(Function.class));
    }
}
