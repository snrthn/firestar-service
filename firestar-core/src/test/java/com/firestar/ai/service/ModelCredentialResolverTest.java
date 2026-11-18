package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 模型凭据解析器单测 —— 只认库里的生效行。
 *
 * <p>2026-11-18 起凭据来源收敛为【模型配置】页录入（DB 唯一真源），
 * 因此这里不再有 "properties 兜底 / 启动回填" 相关用例；
 * 反向守护的不变量是：<b>DB 无生效行必须返回 null</b>，
 * 绝不能从任何其它来源（properties / 环境变量）偷偷取到 key。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelCredentialResolverTest {

    @Mock ModelConfigMapper mapper;

    private CryptoService crypto;
    private ModelCredentialResolver resolver;

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
        resolver = new ModelCredentialResolver(mapper, crypto, new ObjectMapper());
    }

    @Test
    @DisplayName("DB 有生效行 → 用库里的加密凭据（解密后返回）")
    void resolveFromDbWhenActive() {
        ModelConfig row = new ModelConfig();
        row.setModelType("embedding");
        row.setProvider("dashscope");
        row.setActive(1);
        row.setBaseUrl("https://custom.example.com/v1");
        row.setModel("custom-embed-model");
        row.setApiKey(crypto.encrypt("sk-db-secret"));
        row.setExtraJson(crypto.encrypt("{\"dims\":1024}"));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        ModelCredentialResolver.Credential c = resolver.resolve("embedding");
        assertNotNull(c);
        assertEquals("sk-db-secret", c.apiKey());
        assertEquals("custom-embed-model", c.model());
        assertEquals("https://custom.example.com/v1", c.baseUrl());
        assertEquals("1024", c.extraStr("dims", null));
    }

    @Test
    @DisplayName("DB 无生效行 → 四类模型一律返回 null（不再回退 properties / 环境变量）")
    void resolveNoActiveReturnsNullForAllTypes() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertNull(resolver.resolve("embedding"), "embedding 无生效行必须为 null");
        assertNull(resolver.resolve("chat"), "chat 无生效行必须为 null");
        assertNull(resolver.resolve("rerank"), "rerank 无生效行必须为 null");
        assertNull(resolver.resolve("tts"), "tts 无生效行必须为 null");
    }

    @Test
    @DisplayName("extra_json 密文 → 解析成 Map；空值/损坏 → 降级空 Map 不抛")
    void extraJsonParsedAndDegraded() {
        ModelConfig row = new ModelConfig();
        row.setModelType("tts");
        row.setProvider("dashscope");
        row.setActive(1);
        row.setApiKey(crypto.encrypt("sk-tts"));
        row.setExtraJson(crypto.encrypt("{\"voice\":\"longanyang\",\"sample_rate\":22050}"));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(row);

        ModelCredentialResolver.Credential c = resolver.resolve("tts");
        assertNotNull(c);
        assertEquals("longanyang", c.extraStr("voice", null));
        assertEquals("22050", c.extraStr("sample_rate", null));
        assertEquals("def", c.extraStr("missing", "def"));

        // 损坏的 extra_json 不能让整个解析炸掉（降级为空 Map）
        ModelConfig broken = new ModelConfig();
        broken.setModelType("tts");
        broken.setActive(1);
        broken.setExtraJson("enc:v1:这不是合法密文");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(broken);
        ModelCredentialResolver.Credential c2 = resolver.resolve("tts");
        assertNotNull(c2);
        assertTrue(c2.extra().isEmpty(), "extra_json 解析失败应降级为空 Map");
    }
}
