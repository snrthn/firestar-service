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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>覆盖四条容易写错的规则：
 * <ol>
 *   <li>排序号是 1..N 连续序号（虎哥 2026-11-18 反馈 10/20/30 看不懂）：新增表单没填时
 *       追加到末尾（当前最大 + 1）；表单填了就用表单值；</li>
 *   <li>编辑时不传 sortNo = 保持原值（MyBatis-Plus updateById 跳过 null 的语义）；</li>
 *   <li>整表重排按 (index+1) 回写，且<b>只写变化的行</b>（减少无意义的 update/审计噪音）；</li>
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
    @DisplayName("1. 表单没填排序号：新增追加到末尾（最大 sort_no + 1）")
    void createAppendsToEnd() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1), row(2L, 4)));
        when(mapper.selectCount(any())).thenReturn(0L);

        ModelConfig input = chatConfig("deepseek");
        input.setSortNo(null);
        service.create(input, "tester");

        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).insert(saved.capture());
        assertEquals(5, saved.getValue().getSortNo(), "新配置应排在 4 之后");
    }

    @Test
    @DisplayName("2. 空表新增（未填排序号）：sort_no 从 1 开始，不是 0")
    void createOnEmptyTableStartsAtOne() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectCount(any())).thenReturn(0L);

        service.create(chatConfig("openai"), "tester");

        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).insert(saved.capture());
        assertEquals(1, saved.getValue().getSortNo());
    }

    @Test
    @DisplayName("3. 表单填了排序号：按填的存，不再自动追加")
    void createUsesExplicitSortNo() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1)));
        when(mapper.selectCount(any())).thenReturn(0L);

        ModelConfig input = chatConfig("deepseek");
        input.setSortNo(1);
        service.create(input, "tester");

        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).insert(saved.capture());
        assertEquals(1, saved.getValue().getSortNo(), "表单填的值优先于自动追加");
    }

    @Test
    @DisplayName("4. 编辑：填了 sortNo 就更新；不填（null）保持原值不覆盖")
    void updateSortNoOnlyWhenProvided() {
        ModelConfig old = row(7L, 3);
        when(mapper.selectById(7L)).thenReturn(old);

        // 不传 sortNo → patch 里 sortNo 必须是 null（updateById 跳过 null，即"保持原值"）
        ModelConfig noSort = chatConfig("deepseek");
        noSort.setSortNo(null);
        service.update(7L, noSort, "tester");
        ArgumentCaptor<ModelConfig> first = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper, times(1)).updateById(first.capture());
        assertNull(first.getValue().getSortNo(), "未填排序号时不应把原值抹掉");

        // 传 sortNo=1 → 写 1
        ModelConfig withSort = chatConfig("deepseek");
        withSort.setSortNo(1);
        service.update(7L, withSort, "tester");
        ArgumentCaptor<ModelConfig> second = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper, times(2)).updateById(second.capture());
        assertEquals(1, second.getAllValues().get(1).getSortNo());
    }

    @Test
    @DisplayName("5. 重排：按提交顺序回写 1/2/3，只更新真正变化的行")
    void reorderWritesOnlyChangedRows() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1), row(2L, 2), row(3L, 3)));

        // 把第 2 行提到最前：期望 2→1、1→2、3 仍是 3（不产生 update）
        service.reorder(List.of(2L, 1L, 3L), "tester");

        ArgumentCaptor<ModelConfig> patches = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper, times(2)).updateById(patches.capture());
        List<ModelConfig> written = patches.getAllValues();
        assertEquals(List.of(2L, 1L), written.stream().map(ModelConfig::getId).toList());
        assertEquals(List.of(1, 2), written.stream().map(ModelConfig::getSortNo).toList());
    }

    @Test
    @DisplayName("6. 顺序没变：一行都不写")
    void reorderNoChangeWritesNothing() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1), row(2L, 2)));

        service.reorder(List.of(1L, 2L), "tester");

        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("7. ids 与库内不一致（有人同时增删）：拒绝且不写任何行")
    void reorderRejectsMismatchedIdSet() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1), row(2L, 2)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.reorder(List.of(1L), "tester"));
        assertTrue(e.getMessage().contains("请刷新"), "错误文案要能指导用户刷新重试，实际：" + e.getMessage());
        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("8. ids 重复 / 为空：拒绝且不写任何行")
    void reorderRejectsDuplicateOrEmptyIds() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1L, 1), row(2L, 2)));

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
