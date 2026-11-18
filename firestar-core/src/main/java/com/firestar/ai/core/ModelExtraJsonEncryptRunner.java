package com.firestar.ai.core;

import com.firestar.ai.service.ModelExtraJsonNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期 extra_json 存储口径归一化（把存量明文行加密成 {@code enc:v1:}）。
 *
 * <p><b>无条件装配</b>：与 {@code kb.embedding.provider} 无关 —— TTS 行的明文
 * 归一化不能挂在 embedding 的开关上，否则换 provider 时就会漏掉。</p>
 *
 * <p><b>失败只告警不阻断</b>：启动失败会连带打掉整个后端，比留一行明文严重得多；
 * 且读取侧 {@code parseExtra} 兼容明文，明文残留不影响功能。</p>
 */
@Component
@Order(60)
public class ModelExtraJsonEncryptRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelExtraJsonEncryptRunner.class);

    private final ModelExtraJsonNormalizer normalizer;

    public ModelExtraJsonEncryptRunner(ModelExtraJsonNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int fixed = normalizer.normalizePlainExtraJson();
            if (fixed == 0) {
                log.debug("[AI] extra_json 均为密文，无需归一化");
            }
        } catch (Exception e) {
            log.warn("[AI] extra_json 明文归一化异常（不影响启动）: {}", e.getMessage());
        }
    }
}
