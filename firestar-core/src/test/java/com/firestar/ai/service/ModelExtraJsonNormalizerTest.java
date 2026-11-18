package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.ai.config.AiProperties;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * extra_json 明文归一化单测。
 *
 * <p>背景：V214 在 SQL 里预置的 TTS 行 extra_json 是明文，而页面保存路径一律走
 * {@code cryptoService.encrypt} —— 同一列两种形态。本测试守住「启动时把存量明文
 * 统一加密成 enc:v1:」的行为，且必须幂等（已加密的不重复动）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelExtraJsonNormalizerTest {

    @Mock ModelConfigMapper mapper;

    private CryptoService crypto;
    private ModelExtraJsonNormalizer normalizer;

    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), ModelConfig.class);
    }

    @BeforeEach
    void setUp() {
        AiProperties aiProps = new AiProperties();
        aiProps.setKek("unit-test-kek-please-change-32bytes!");
        crypto = new CryptoService(aiProps);
        normalizer = new ModelExtraJsonNormalizer(mapper, crypto);
    }

    private ModelConfig row(long id, String extra) {
        ModelConfig m = new ModelConfig();
        m.setId(id);
        m.setModelType("tts");
        m.setProvider("dashscope");
        m.setExtraJson(extra);
        return m;
    }

    @Test
    @DisplayName("1. 明文 extra_json → 加密回写，且能解回原文")
    void encryptPlainExtraJson() {
        String plain = "{\"voice\":\"longanyang\",\"format\":\"mp3\",\"sample_rate\":22050}";
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(row(4L, plain)));

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(1, fixed);
        ArgumentCaptor<ModelConfig> cap = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).updateById(cap.capture());
        String stored = cap.getValue().getExtraJson();
        assertTrue(stored.startsWith("enc:v1:"), "应加密为 enc:v1: 前缀，实际: " + stored);
        assertEquals(plain, crypto.decrypt(stored), "解密后应还原原文");
        assertEquals(4L, cap.getValue().getId());
        assertEquals("bootstrap", cap.getValue().getUpdatedBy());
    }

    @Test
    @DisplayName("2. 已是密文的行 → 跳过，不重复加密")
    void skipAlreadyEncrypted() {
        String encrypted = crypto.encrypt("{\"voice\":\"longanyang\"}");
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(row(5L, encrypted)));

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(0, fixed);
        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("3. null / 空串 extra_json → 跳过")
    void skipNullAndBlank() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(row(1L, null), row(2L, "   ")));

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(0, fixed);
        verify(mapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    @DisplayName("4. 明文与密文混合 → 只处理明文那行")
    void mixedRowsOnlyPlainFixed() {
        String encrypted = crypto.encrypt("{\"dims\":1024}");
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(
                        row(4L, "{\"voice\":\"longanyang\"}"),
                        row(5L, encrypted)));

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(1, fixed);
        ArgumentCaptor<ModelConfig> cap = ArgumentCaptor.forClass(ModelConfig.class);
        verify(mapper).updateById(cap.capture());
        assertEquals(4L, cap.getValue().getId());
    }

    @Test
    @DisplayName("5. 幂等：归一化后再跑一次 → 无任何写入")
    void idempotent() {
        String plain = "{\"voice\":\"longanyang\"}";
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(row(4L, plain)));

        assertEquals(1, normalizer.normalizePlainExtraJson());

        // 第二次：库里已是密文
        String encrypted = crypto.encrypt(plain);
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(row(4L, encrypted)));
        assertEquals(0, normalizer.normalizePlainExtraJson());
    }

    @Test
    @DisplayName("6. 单行加密异常不影响其它行")
    void oneRowFailureDoesNotBlockOthers() {
        String plain = "{\"voice\":\"longanyang\"}";
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(row(4L, plain), row(6L, plain)));
        // 第一次 update 抛异常
        when(mapper.updateById(any(ModelConfig.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(1);

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(1, fixed, "一行失败后另一行仍应被加密");
    }

    @Test
    @DisplayName("7. 查询条件限定 extra_json 非空（避免全表扫描无谓解密）")
    void queryFiltersNonNull() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.<ModelConfig>emptyList());

        int fixed = normalizer.normalizePlainExtraJson();

        assertEquals(0, fixed);
        verify(mapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }
}
