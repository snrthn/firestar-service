package com.firestar.ai.service;

import com.firestar.ai.core.ChatProviderManager;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【模型配置】展示顺序（V215）单测：不连库、不启 Spring，只看「写进 sort_no 的值对不对」。
 *
 * <p>覆盖三条容易写错的规则：
 * <ol>
 *   <li>新增配置追加到末尾（当前最大 sort_no + 10），不打断管理员调好的顺序；</li>
 *   <li>整表重排按 (index+1)*10 回写，且<b>只写变化的行</b>（减少无意义的 update/审计噪音）；</li>
 *   <li>ids 与库内行集合不一致 / 有重复 / 为空 → 拒绝执行且一行都不写
 *       （并发增删后拿旧列表重排，会把别人的新增行挤到未知位置）。</li>
 * </ol>
 */
class ModelConfigSortTest {

    private ModelConfigMapper mapper;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelConfigMapper.class);
        service = new ModelConfigService(mapper, mock(CryptoService.class), mock(ChatProviderManager.class));
        when(mapper.insert(any(ModelConfig.class))).thenReturn(1);
        when(mapper.updateById(any(ModelConfig.class))).thenReturn(1);
    }

    @Test
    @DisplayName("1. 新增配置追加到末尾：最大 sort_no + 10")
    void createAppendsToEnd() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 10), row(2L, 40)));
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(chatConfig("deepseek"), "tester");

        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).insert(saved.capture());
        assertEquals(50, saved.getValue().getSortNo(), "新配置应排在 40 之后（步长 10）");
    }

    @Test
    @DisplayName("2. 空表新增：sort_no 从 10 开始，不是 0")
    void createOnEmptyTableStartsAtTen() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(chatConfig("openai"), "tester");

        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).insert(saved.capture());
        assertEquals(10, saved.getValue().getSortNo());
    }

    @Test
    @DisplayName("3. 重排：按提交顺序回写 10/20/30，只更新真正变化的行")
    void reorderWritesOnlyChangedRows() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 10), row(2L, 20), row(3L, 30)));

        // 把第 2 行提到最前：期望 2→10、1→20、3 仍是 30（不产生 update）
        service.reorder(List.of(2L, 1L, 3L), "tester");

        ArgumentCaptor<ModelConfig> patches = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper, times(2)).updateById(patches.capture());
        List<ModelConfig> written = patches.getAllValues();
        assertEquals(List.of(2L, 1L), written.stream().map(ModelConfig::getId).toList());
        assertEquals(List.of(10, 20), written.stream().map(ModelConfig::getSortNo).toList());
    }

    @Test
    @DisplayName("4. 顺序没变：一行都不写")
    void reorderNoChangeWritesNothing() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 10), row(2L, 20)));

        service.reorder(List.of(1L, 2L), "tester");

        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("5. ids 与库内不一致（有人同时增删）：拒绝且不写任何行")
    void reorderRejectsMismatchedIdSet() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 10), row(2L, 20)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.reorder(List.of(1L), "tester"));
        assertTrue(e.getMessage().contains("请刷新"), "错误文案要能指导用户刷新重试，实际：" + e.getMessage());
        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("6. ids 重复 / 为空：拒绝且不写任何行")
    void reorderRejectsDuplicateOrEmptyIds() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 10), row(2L, 20)));

        assertThrows(IllegalArgumentException.class, () -> service.reorder(List.of(1L, 1L), "tester"));
        assertThrows(IllegalArgumentException.class, () -> service.reorder(List.of(), "tester"));
        assertThrows(IllegalArgumentException.class, () -> service.reorder(null, "tester"));
        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    // ---------------------------------------------------------------- 辅助

    private static ModelConfig row(Long id, int sortNo) {
        ModelConfig c = new ModelConfig();
        c.setId(id);
        c.setModelType("chat");
        c.setProvider("p" + id);
        c.setSortNo(sortNo);
        return c;
    }

    private static ModelConfig chatConfig(String provider) {
        ModelConfig c = new ModelConfig();
        c.setModelType("chat");
        c.setProvider(provider);
        c.setBaseUrl("https://example.com/v1");
        c.setActive(0);
        return c;
    }
}
