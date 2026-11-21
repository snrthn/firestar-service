package com.firestar.service.impl;

import com.firestar.ai.kb.KbFileTextExtractor;
import com.firestar.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V206 classify 契约的纯逻辑单测（不连库、不启 Spring、不碰 OSS）。
 *
 * <p>覆盖设计文档 §2.5 的三条场景与两个历史缺陷：
 * <ul>
 *   <li>其他业务场景一律 ATTACHMENT，且忽略伪造的 fileType；</li>
 *   <li>文件管理页手选值必须落在收窄后的 5 项白名单里（旧 INVOICE/BANK_RECEIPT/TEMP 拒绝）；</li>
 *   <li>文件管理页不选择时由服务端按扩展名推导，未命中归 ATTACHMENT；</li>
 *   <li>堵住「mp3 标成发票文件」的老洞；</li>
 *   <li>头像这类后端内部确知类型的调用仍可显式分类。</li>
 * </ul>
 */
class FileTypeResolveTest {

    @Test
    @DisplayName("其他业务场景：不带 classify → 一律 ATTACHMENT，传了 fileType 也忽略")
    void businessUploadAlwaysAttachment() {
        assertEquals("ATTACHMENT", FileServiceImpl.resolveFileType(null, "jpg", false));
        assertEquals("ATTACHMENT", FileServiceImpl.resolveFileType("IMAGE", "png", false));
        assertEquals("ATTACHMENT", FileServiceImpl.resolveFileType("PDF", "pdf", false));
        // 防伪造入参：业务组件哪天顺手带一个类型，也不能改变分类口径
        assertEquals("ATTACHMENT", FileServiceImpl.resolveFileType("INVOICE", "mp3", false));
    }

    @Test
    @DisplayName("文件管理页：手选值必须在 5 项白名单内，大小写与空格归一")
    void explicitTypeMustBeWhitelisted() {
        assertEquals("IMAGE", FileServiceImpl.resolveFileType("IMAGE", "pdf", true));
        assertEquals("PDF", FileServiceImpl.resolveFileType(" pdf ", "jpg", true));
        assertEquals("AUDIO", FileServiceImpl.resolveFileType("audio", "mp3", true));
        assertEquals("VIDEO", FileServiceImpl.resolveFileType("VIDEO", "mp4", true));
        assertEquals("ATTACHMENT", FileServiceImpl.resolveFileType("ATTACHMENT", "mp3", true));
    }

    @Test
    @DisplayName("旧业务凭证值已剔除：INVOICE / BANK_RECEIPT / TEMP 一律拒绝")
    void legacyBusinessValuesAreRejected() {
        assertThrows(BizException.class, () -> FileServiceImpl.resolveFileType("INVOICE", "mp3", true));
        assertThrows(BizException.class, () -> FileServiceImpl.resolveFileType("BANK_RECEIPT", "pdf", true));
        assertThrows(BizException.class, () -> FileServiceImpl.resolveFileType("TEMP", "txt", true));
        assertThrows(BizException.class, () -> FileServiceImpl.resolveFileType("VIDEO/mp4", "mp4", true));
    }

    @Test
    @DisplayName("文件管理页未选择：服务端按扩展名推导，未命中归 ATTACHMENT")
    void inferByExtension() {
        assertEquals("IMAGE", infer("jpg"));
        assertEquals("IMAGE", infer("JPEG"));
        assertEquals("IMAGE", infer("png"));
        assertEquals("IMAGE", infer("webp"));
        assertEquals("PDF", infer("pdf"));
        assertEquals("AUDIO", infer("mp3"));
        assertEquals("VIDEO", infer("mp4"));
        assertEquals("ATTACHMENT", infer("docx"));
        assertEquals("ATTACHMENT", infer("zip"));
        assertEquals("ATTACHMENT", infer("ofd"));
        assertEquals("ATTACHMENT", infer("unknown"));
        assertEquals("ATTACHMENT", infer(null));
        assertEquals("ATTACHMENT", infer(""));
    }

    @Test
    @DisplayName("知识库附件格式契约：解析器支持的格式必须全部允许上传（md 曾被旧白名单 500 拒绝）")
    void kbAttachmentFormatsAreUploadable() {
        for (String ext : KbFileTextExtractor.SUPPORTED_EXTENSIONS) {
            assertTrue(FileServiceImpl.isAllowedExtension(ext), "上传白名单漏了 ." + ext);
        }
    }

    @Test
    @DisplayName("头像等后端内部调用：确知类型可显式分类，行为不回退")
    void internalAvatarUploadKeepsImage() {
        // ProfileController 传 ("IMAGE", null, true)
        assertEquals("IMAGE", FileServiceImpl.resolveFileType("IMAGE", "png", true));
    }

    private static String infer(String ext) {
        return FileServiceImpl.resolveFileType(null, ext, true);
    }
}
