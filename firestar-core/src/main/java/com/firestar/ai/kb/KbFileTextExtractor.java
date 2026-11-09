package com.firestar.ai.kb;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * REFERENCE 附件文本抽取器（步骤 5 扩展，纯函数、无 Spring、可直接单测）。
 *
 * <p>把常见办公文档解析成纯文本，交给 {@link KbMarkdownSplitter} 切片索引。
 * 支持的格式与解析路线：
 * <ul>
 *   <li>docx → Apache POI XWPF（段落 + 表格）；</li>
 *   <li>xlsx → Apache POI XSSF（逐 sheet，行列拼成「单元格 | 单元格」）；</li>
 *   <li>pdf  → Apache PDFBox 3.x {@link PDFTextStripper}；</li>
 *   <li>txt / md / csv / log → 文本直读（UTF-8，失败回落 GBK，适配中文 Windows 导出）；</li>
 *   <li>html / htm → 复用 {@link HelpArticleSource#htmlToMarkdown} 清洗（标题/列表/链接保留为 MD）。</li>
 * </ul>
 * 不支持的扩展名抛 {@link UnsupportedFileTypeException}，由调用方记日志跳过（不阻断发布）。</p>
 *
 * <p>设计边界：v1 不处理扫描版 PDF（无文字层）与加密文档——这类文件抽取结果为空，
 * 调用方据此跳过索引即可；后续可接 OCR，但不在本期范围。</p>
 */
public final class KbFileTextExtractor {

    /** 不支持的附件类型（调用方据此优雅跳过，不影响其它切片） */
    public static final class UnsupportedFileTypeException extends Exception {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }

    private KbFileTextExtractor() {
    }

    /**
     * 抽取附件文本。
     *
     * @param originalName 原始文件名（取扩展名判断类型；为 null/无扩展名时按未知处理）
     * @param bytes        文件字节
     * @return 解析出的纯文本（可能为空串，调用方按需跳过）
     * @throws UnsupportedFileTypeException 扩展名不在支持列表
     * @throws IOException                  解析失败（POI/PDFBox 底层 IO）
     */
    public static String extract(String originalName, byte[] bytes)
            throws UnsupportedFileTypeException, IOException {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String ext = ext(originalName);
        switch (ext) {
            case "txt":
            case "md":
            case "markdown":
            case "csv":
            case "log":
            case "text":
            case "json":
                return readText(bytes);
            case "docx":
                return extractDocx(bytes);
            case "xlsx":
                return extractXlsx(bytes);
            case "pdf":
                return extractPdf(bytes);
            case "html":
            case "htm":
                return HelpArticleSource.htmlToMarkdown(new String(bytes, StandardCharsets.UTF_8));
            default:
                throw new UnsupportedFileTypeException("不支持的附件类型：." + (ext.isEmpty() ? "(无扩展名)" : ext));
        }
    }

    // ==================== docx ====================

    private static String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append("\n");
                }
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText() == null ? "" : cell.getText().trim());
                    }
                    sb.append(String.join(" | ", cells)).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        }
    }

    // ==================== xlsx ====================

    private static String extractXlsx(byte[] bytes) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int sheets = wb.getNumberOfSheets();
            for (int si = 0; si < sheets; si++) {
                XSSFSheet sheet = wb.getSheetAt(si);
                if (sheet == null) {
                    continue;
                }
                sb.append("# ").append(sheet.getSheetName()).append("\n\n");
                for (Row row : sheet) {
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(cell == null ? "" : cellToString(cell));
                    }
                    sb.append(String.join(" | ", cells)).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        }
    }

    private static String cellToString(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                double v = cell.getNumericCellValue();
                // 整数形态不显示小数点（金额/数量更可读）
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    // ==================== pdf ====================

    private static String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            return text == null ? "" : text.trim();
        }
    }

    // ==================== 文本直读 ====================

    private static String readText(byte[] bytes) {
        // 先 UTF-8；若出现替换字符（乱码）回落 GBK，覆盖中文 Windows 默认编码导出的 csv/txt
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') < 0) {
            return utf8;
        }
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            return utf8;
        }
    }

    // ==================== 工具 ====================

    private static String ext(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(dot + 1).toLowerCase();
    }
}
