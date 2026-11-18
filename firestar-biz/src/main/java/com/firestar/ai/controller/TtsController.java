package com.firestar.ai.controller;

import com.firestar.ai.tts.DashScopeCosyVoiceProvider;
import com.firestar.ai.tts.TtsProvider;
import com.firestar.common.RequirePerm;
import com.firestar.common.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * AI 语音合成接口（V214）：文本 → 语音，供前端播放/下载。
 *
 * <p>返回 base64 音频而非二进制流，便于走统一 {@link Result} 包装与 JWT 鉴权（SSE 之外的常规 JSON 通路）。
 * 权限沿用 AI 模块查看权限；未配置 tts 模型时返回清晰错误，不影响其它 AI 功能。</p>
 */
@RestController
@RequestMapping("/ai/tts")
@RequirePerm("sys:modelConfig:view")
public class TtsController {

    private final TtsProvider provider;

    public TtsController(DashScopeCosyVoiceProvider provider) {
        this.provider = provider;
    }

    /** 合成请求体。 */
    public record TtsRequest(String text, String voice, String format, Integer sampleRate) {}

    /** 合成响应：base64 音频 + 播放所需的 content-type。 */
    public record TtsResponse(String audioBase64, String contentType, String format,
                              int sampleRate, int bytes) {}

    @PostMapping(value = "/synthesize", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<TtsResponse> synthesize(@RequestBody TtsRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            return Result.error("待合成文本不能为空");
        }
        if (!provider.available()) {
            return Result.error("语音合成未配置：请在【模型配置】页启用一条 tts 类型的模型配置");
        }
        Map<String, Object> options = new java.util.HashMap<>();
        if (req.voice() != null && !req.voice().isBlank()) {
            options.put("voice", req.voice());
        }
        if (req.format() != null && !req.format().isBlank()) {
            options.put("format", req.format());
        }
        if (req.sampleRate() != null && req.sampleRate() > 0) {
            options.put("sampleRate", req.sampleRate());
        }
        TtsProvider.TtsResult result = provider.synthesize(req.text(), options);
        String base64 = Base64.getEncoder().encodeToString(result.audio());
        return Result.success(new TtsResponse(base64, result.contentType(), result.format(),
                result.sampleRate(), result.audio().length));
    }

    /** 轻量探活：前端可先问可用性，再决定是否展示语音按钮。 */
    @PostMapping("/available")
    public Result<Boolean> available() {
        return Result.success(provider.available());
    }
}
