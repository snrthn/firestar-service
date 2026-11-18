package com.firestar.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.ai.crypto.CryptoService;
import com.firestar.ai.entity.ModelConfig;
import com.firestar.ai.mapper.ModelConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * extra_json 存储口径归一化器。
 *
 * <p><b>为什么需要</b>：{@code sys_model_config.extra_json} 本该一律密文
 * （页面保存路径 {@code ModelConfigService} 走的是 {@code cryptoService.encrypt}），
 * 但 V214 迁移为了「预置一个 TTS Provider」，在 SQL 里写死了明文 JSON：
 * <pre>{"voice":"longanyang","format":"mp3","sample_rate":22050}</pre>
 * 于是同一列出现两种形态：TTS 行明文、Embedding 行密文。功能上兼容
 * （{@code CryptoService.decrypt} 对明文原样返回），但排障时极易误判
 * 「这行为什么没加密」。</p>
 *
 * <p><b>为什么不用 Flyway 迁移做</b>：SM4-GCM 加密是 Java 侧能力，SQL 做不到；
 * 且 KEK 是环境变量，迁移脚本里绝不能出现其字面量（见 Flyway 占位符铁律）。</p>
 *
 * <p><b>执行时机</b>：启动期 {@code ModelExtraJsonEncryptRunner} 调用，幂等
 * （已加密的行直接跳过），失败只告警不阻断启动（启动失败会连带打掉整个后端，
 * 远比留一行明文严重）。</p>
 */
@Service
public class ModelExtraJsonNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ModelExtraJsonNormalizer.class);

    private final ModelConfigMapper mapper;
    private final CryptoService crypto;

    @Autowired
    public ModelExtraJsonNormalizer(ModelConfigMapper mapper, CryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    /**
     * 把存量明文 extra_json 加密回写。
     *
     * @return 本次实际加密的行数（0 表示无需处理）
     */
    public int normalizePlainExtraJson() {
        List<ModelConfig> rows = mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>().isNotNull(ModelConfig::getExtraJson));
        int fixed = 0;
        for (ModelConfig row : rows) {
            String raw = row.getExtraJson();
            if (raw == null || raw.isBlank() || crypto.isEncrypted(raw)) {
                continue;
            }
            try {
                ModelConfig upd = new ModelConfig();
                upd.setId(row.getId());
                upd.setExtraJson(crypto.encrypt(raw));
                upd.setUpdatedBy("bootstrap");
                upd.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(upd);
                fixed++;
                log.info("[AI] extra_json 明文归一化: id={} modelType={} provider={}",
                        row.getId(), row.getModelType(), row.getProvider());
            } catch (Exception e) {
                log.warn("[AI] extra_json 明文归一化失败 id={}: {}", row.getId(), e.getMessage());
            }
        }
        if (fixed > 0) {
            log.info("[AI] extra_json 明文归一化完成: {} 行已加密", fixed);
        }
        return fixed;
    }
}
