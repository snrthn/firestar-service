package com.firestar.ai.kb;

import com.firestar.ai.entity.KbChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REFERENCE 附件检索链路单测（纯函数）：
 * ① KbChunkHit.parseFileId 从 sourceRef 反解 fileId；
 * ② KbMarkdownSplitter.splitToChunks(docId,version,md,base) 把 base 埋进 sourceRef。
 */
@DisplayName("REFERENCE 附件检索链路：sourceRef 埋 fileId")
class KbAttachmentRetrievalTest {

    @Test
    @DisplayName("parseFileId：附件出处反解出 fileId，MD/帮助文章/空返回 0")
    void parseFileId() {
        assertEquals(42L, KbChunkHit.parseFileId("kb:att/42/sec-abc"));
        assertEquals(0L, KbChunkHit.parseFileId("kb:doc/7/v3/sec-abc"));
        assertEquals(0L, KbChunkHit.parseFileId("kb:help/9/v1/sec-abc"));
        assertEquals(0L, KbChunkHit.parseFileId(null));
    }

    @Test
    @DisplayName("切片 sourceRefBase：附件切片 sourceRef 以 kb:att/<fileId> 前缀")
    void splitterAttSourceRef() {
        List<KbChunk> chunks = KbMarkdownSplitter.splitToChunks(
                7L, 3, "# 附件A\n\n正文内容", "kb:att/42");
        assertTrue(!chunks.isEmpty(), "应有切片");
        assertTrue(chunks.stream().allMatch(c -> c.getSourceRef().startsWith("kb:att/42/")),
                "sourceRef 应以 kb:att/42/ 前缀");
        // 与 MD 文档同 docId/version/status=1，被主检索链路天然召回
        assertTrue(chunks.stream().allMatch(c -> c.getDocId() == 7L));
        assertTrue(chunks.stream().allMatch(c -> c.getVersion() == 3));
        assertTrue(chunks.stream().allMatch(c -> c.getStatus() == 1));
        // 反解验证：检索侧能拿回 fileId
        assertEquals(42L, KbChunkHit.parseFileId(chunks.get(0).getSourceRef()));
    }
}
