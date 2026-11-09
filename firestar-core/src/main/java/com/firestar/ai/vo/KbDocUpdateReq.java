package com.firestar.ai.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 保存文档请求：对齐前端 KbDocUpdate。
 *
 * <p><b>fileRefs 只是提示，不作为真源</b>：后端一律用 {@code KbTreeRules.collectFileRefs(contentMd)}
 * 从正文重新提取，避免伪造 fileId 或前端漏传导致附件关系漂移（详见 §16）。</p>
 */
@Getter
@Setter
public class KbDocUpdateReq {

    private String title;
    private String summary;
    private String contentMd;
    private List<Long> fileRefs;
}
