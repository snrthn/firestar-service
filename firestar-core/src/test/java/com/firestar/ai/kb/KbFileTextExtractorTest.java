package com.firestar.ai.kb;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KbFileTextExtractor 纯函数单测（无 Spring，直接跑）。
 * 覆盖 docx / xlsx / pdf / txt / html 解析路线与不支持类型抛异常。
 */
@DisplayName("KbFileTextExtractor：REFERENCE 附件文本抽取")
class KbFileTextExtractorTest {

    @Test
    @DisplayName("docx：段落文本被抽取")
    void docx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("附件标题测试");
            doc.createParagraph().createRun().setText("第二段内容");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            String text = KbFileTextExtractor.extract("报告.docx", out.toByteArray());
            assertTrue(text.contains("附件标题测试"), "应含首段文本");
            assertTrue(text.contains("第二段内容"), "应含次段文本");
        }
    }

    @Test
    @DisplayName("xlsx：sheet 名 + 行列以 | 拼出")
    void xlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("人员");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("姓名");
            row.createCell(1).setCellValue("年龄");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            String text = KbFileTextExtractor.extract("名单.xlsx", out.toByteArray());
            assertTrue(text.contains("# 人员"), "应带 sheet 名作为 H1");
            assertTrue(text.contains("姓名 | 年龄"), "应拼出表头行");
        }
    }

    @Test
    @DisplayName("pdf：文字层被抽取（ASCII，避免 CJK 字体缺失导致空抽）")
    void pdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello PDFBox extraction");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            String text = KbFileTextExtractor.extract("说明.pdf", out.toByteArray());
            assertTrue(text.contains("Hello PDFBox extraction"), "应抽取出 PDF 文本");
        }
    }

    @Test
    @DisplayName("txt：UTF-8 直读")
    void txt() throws Exception {
        String text = KbFileTextExtractor.extract("笔记.txt", "纯文本一行内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(text.contains("纯文本一行内容"));
    }

    @Test
    @DisplayName("html：标题与段落清洗为 Markdown")
    void html() throws Exception {
        String html = "<h1>合同标题</h1><p>正文第一段</p>";
        String text = KbFileTextExtractor.extract("合同.html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(text.contains("# 合同标题"), "h1 应转 #");
        assertTrue(text.contains("正文第一段"), "p 应保留");
    }

    @Test
    @DisplayName("不支持的类型抛 UnsupportedFileTypeException")
    void unsupported() {
        assertThrows(KbFileTextExtractor.UnsupportedFileTypeException.class,
                () -> KbFileTextExtractor.extract("压缩包.zip", new byte[]{1, 2, 3}));
    }
}
