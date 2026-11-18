package com.firestar.ai.tts;

import java.util.Map;

/**
 * 语音合成提供者 SPI（V214，四类模型最后一块）。
 *
 * <p>与 Embedding/Rerank 对称：凭据由
 * {@link com.firestar.ai.service.ModelCredentialResolver} 按 model_type=tts 从库解析，
 * 供应商配置行独立（与 chat/embedding/rerank 互不干扰）。</p>
 */
public interface TtsProvider {

    /** 实现标识（如 dashscope），日志用。 */
    String id();

    /**
     * 文本合成语音。
     *
     * @param text    待合成文本
     * @param options 可选覆盖（voice / format / sampleRate 等），可为空
     * @return 音频结果（字节 + 格式信息）
     */
    TtsResult synthesize(String text, Map<String, Object> options);

    /** 是否配置了可用凭据。 */
    boolean available();

    /** 音频结果载体。 */
    record TtsResult(byte[] audio, String format, int sampleRate) {
        public String contentType() {
            return switch (format == null ? "mp3" : format.toLowerCase()) {
                case "wav" -> "audio/wav";
                case "opus" -> "audio/opus";
                case "pcm" -> "audio/pcm";
                default -> "audio/mpeg";
            };
        }
    }
}
